<#
    阶段 4：从空库执行 Flyway + 三角色登录 + 四态主链演示（临时库 / 临时端口，用后即删）

    目的
      在不触碰演示库 `flowdesk`、也不触碰已在 8081 运行的既有后端进程的前提下，证明
      「一个全新空库 + demo Profile」可以独立走完：Flyway 建表迁移 → 演示种子 → 三角色登录
      → 待受理 → 处理中 → 待员工确认 → 已完成 的四态主链，并且终态快照与工单记录满足约束。

    隔离手段
      · 临时数据库 `flowdesk_stage4_clean`（同一个容器 flowdesk-mysql-1 内新建，结束时 DROP）
      · 临时后端端口 8091（结束时杀掉本脚本启动的进程，并核对 netstat 无监听）
      · Redis 用既有 6380 实例，只写会话键（flowdesk:auth:session:*），不清库、不改配置

    安全边界
      · 只对演示库做 SELECT 计数（读写都在临时库）；不执行 docker compose down，
        不删容器、不删卷、不删任何已存在的数据；不做任何 git 写操作
      · 数据库口令与 JWT 密钥只从仓库根 .env 读进进程环境变量，不打印、不写进证据

    为什么临时库的连接用户是 root
      容器里的应用用户 `flowdesk` 只有 `flowdesk.*` 的权限（SHOW GRANTS 实测），
      无法 `CREATE DATABASE`；给它在临时库上临时授权会在容器里留下授权残留，因此本次
      临时运行统一用 .env 里的 root 口令，运行结束即 DROP DATABASE，不留下任何授权变更。

    运行（必须用脚本文件执行，不要在同一个 -Command 里声明 function 后接着调用）
      powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stage4-clean-db-demo.ps1

    演示账号可通过 E2E_* 环境变量覆盖：
      E2E_EMPLOYEE_USERNAME / E2E_EMPLOYEE_PASSWORD、E2E_IT_USERNAME / E2E_IT_PASSWORD、
      E2E_ADMIN_USERNAME / E2E_ADMIN_PASSWORD（缺省 employee / it / admin，口令 123456）

    证据：docs/acceptance/2026-10-06-stage4-clean-db-demo.json（无 BOM 的 UTF-8）

    注意：本文件必须保存为「带 BOM 的 UTF-8」。Windows PowerShell 5.1 会按系统代码页读取
    无 BOM 的 UTF-8 脚本，中文注释会被解析成乱码并报语法错误。
#>

param(
    [string]$RepoRoot = (Join-Path $PSScriptRoot '..'),
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'),
    [string]$OutFile = (Join-Path $PSScriptRoot '..\docs\acceptance\2026-10-06-stage4-clean-db-demo.json'),
    [string]$DbContainer = 'flowdesk-mysql-1',
    [string]$DemoDatabase = 'flowdesk',
    [string]$TempDatabase = 'flowdesk_stage4_clean',
    [int]$Port = 8091,
    [int]$DemoBackendPort = 8081,
    [string]$JavaHome = $env:JAVA_HOME,
    [int]$ReadyTimeoutSeconds = 90,
    [string]$EmployeeUser = $env:E2E_EMPLOYEE_USERNAME,
    [string]$EmployeePassword = $env:E2E_EMPLOYEE_PASSWORD,
    [string]$ItUser = $env:E2E_IT_USERNAME,
    [string]$ItPassword = $env:E2E_IT_PASSWORD,
    [string]$AdminUser = $env:E2E_ADMIN_USERNAME,
    [string]$AdminPassword = $env:E2E_ADMIN_PASSWORD
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

# ── 0. 基线：解析参数、加载 .env、安全护栏 ──────────────────────────────────
$repoFull = (Resolve-Path -LiteralPath $RepoRoot).Path
$envFull = (Resolve-Path -LiteralPath $EnvFile).Path
$outDirFull = (Resolve-Path -LiteralPath (Split-Path -Parent $OutFile)).Path
$outFull = Join-Path $outDirFull (Split-Path -Leaf $OutFile)

if ([string]::IsNullOrWhiteSpace($JavaHome) -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw "JAVA_HOME 不可用：[$JavaHome]（请用 -JavaHome 指定 JDK 21 安装目录）"
}
if ($TempDatabase -eq $DemoDatabase) { throw '临时库名不能等于演示库名' }
if ($TempDatabase -notlike 'flowdesk_stage4_*') { throw "临时库名必须以 flowdesk_stage4_ 开头，收到：$TempDatabase" }
if ($Port -eq $DemoBackendPort) { throw '临时后端端口不能等于既有后端端口' }
if ($Port -lt 1024 -or $Port -gt 65535) { throw "临时后端端口不合法：$Port" }

$envValues = @{}
foreach ($line in (Get-Content -LiteralPath $envFull)) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
    $index = $trimmed.IndexOf('=')
    if ($index -le 0) { continue }
    $name = $trimmed.Substring(0, $index).Trim()
    $value = $trimmed.Substring($index + 1)
    $envValues[$name] = $value
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}

$requiredNames = @(
    'FLOWDESK_DB_URL', 'FLOWDESK_DB_USERNAME', 'FLOWDESK_DB_PASSWORD', 'FLOWDESK_MYSQL_ROOT_PASSWORD',
    'FLOWDESK_REDIS_HOST', 'FLOWDESK_REDIS_PORT', 'FLOWDESK_JWT_SECRET', 'FLOWDESK_ALLOWED_ORIGINS',
    'FLOWDESK_ATTACHMENT_ROOT', 'FLOWDESK_COOKIE_SECURE')
$missingNames = @($requiredNames | Where-Object { -not $envValues.ContainsKey($_) })
if ($missingNames.Count -gt 0) { throw ".env 缺少必需项：$($missingNames -join ', ')" }

if ([string]::IsNullOrWhiteSpace($EmployeeUser)) { $EmployeeUser = 'employee' }
if ([string]::IsNullOrWhiteSpace($EmployeePassword)) { $EmployeePassword = '123456' }
if ([string]::IsNullOrWhiteSpace($ItUser)) { $ItUser = 'it' }
if ([string]::IsNullOrWhiteSpace($ItPassword)) { $ItPassword = '123456' }
if ([string]::IsNullOrWhiteSpace($AdminUser)) { $AdminUser = 'admin' }
if ([string]::IsNullOrWhiteSpace($AdminPassword)) { $AdminPassword = '123456' }

# 临时库的连接串：沿用 .env 原串，只替换库名，其余查询参数照抄。
# 注意：这里刻意用显式拼接，而不是 "/$DemoDatabase?" 这种写法——PowerShell 会把结尾的 ?
# 当成变量名的一部分（"$DemoDatabase?" 被解析为未定义变量 $DemoDatabase?），静默替换失败。
$demoUrl = $envValues['FLOWDESK_DB_URL']
$demoDbToken = '/' + $DemoDatabase + '?'
$tempDbToken = '/' + $TempDatabase + '?'
$tempUrl = $demoUrl.Replace($demoDbToken, $tempDbToken)
if ($tempUrl -eq $demoUrl) { throw "无法从 FLOWDESK_DB_URL 里替换出临时库连接串：$demoUrl" }

# 子进程环境变量：datasource 指向临时库，其余照抄 .env（口令只进环境变量，不回显）。
[Environment]::SetEnvironmentVariable('FLOWDESK_DB_URL', $tempUrl, 'Process')
[Environment]::SetEnvironmentVariable('FLOWDESK_DB_USERNAME', 'root', 'Process')
[Environment]::SetEnvironmentVariable('FLOWDESK_DB_PASSWORD', $envValues['FLOWDESK_MYSQL_ROOT_PASSWORD'], 'Process')
[Environment]::SetEnvironmentVariable('JAVA_HOME', $JavaHome, 'Process')

$rootPassword = $envValues['FLOWDESK_MYSQL_ROOT_PASSWORD']
$originHeader = 'http://127.0.0.1:5173'
$startedAt = Get-Date
$stamp = $startedAt.ToString('yyyyMMdd-HHmmss')
$workDir = Join-Path $env:TEMP "flowdesk-stage4-clean-$stamp"
New-Item -ItemType Directory -Path $workDir -Force | Out-Null
$stdoutLog = Join-Path $workDir 'backend-stdout.log'
$stderrLog = Join-Path $workDir 'backend-stderr.log'

$script:results = New-Object System.Collections.Generic.List[object]
$script:steps = New-Object System.Collections.Generic.List[object]
$script:notes = New-Object System.Collections.Generic.List[string]
$script:cleanup = [ordered]@{}
$script:flywayRows = @()
$script:startupLogHighlights = @()
$script:ticketNo = ''
$script:snapshotText = ''
$script:recordRows = @()
$script:quitCode = 0

function Write-Step {
    param([string]$Message)
    Write-Host ''
    Write-Host ("== [{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $Message)
}

function Add-Assertion {
    param([string]$Name, [bool]$Condition, [string]$Detail)
    $script:results.Add([pscustomobject]@{ name = $Name; passed = $Condition; detail = $Detail })
    $mark = if ($Condition) { 'PASS' } else { 'FAIL' }
    Write-Host ("[{0}] {1} :: {2}" -f $mark, $Name, $Detail)
}

# 原生 docker/netstat 的 stderr 在 $ErrorActionPreference='Stop' 下会变成终止性错误，
# 因此统一在这个包装里临时降级，并把 stderr 以文本形式并回输出。
function Invoke-NativeText {
    param([string]$FilePath, [string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = & $FilePath @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($item in $raw) {
        if ($item -is [System.Management.Automation.ErrorRecord]) {
            $lines.Add('STDERR: ' + $item.Exception.Message)
        } else {
            $lines.Add([string]$item)
        }
    }
    return [pscustomobject]@{ exitCode = $code; lines = @($lines) }
}

# MySQL 访问：口令走 MYSQL_PWD 环境变量并由 docker exec -e 转发，不出现在命令行里。
function Invoke-MySql {
    param([string]$Database, [string]$Sql, [string]$User = 'root', [string]$Password = $rootPassword)
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $Password, 'Process')
    try {
        $arguments = @('exec', '-e', 'MYSQL_PWD', $DbContainer, 'mysql', '-u', $User, '-N', '-B')
        if (-not [string]::IsNullOrEmpty($Database)) { $arguments += $Database }
        $arguments += @('-e', $Sql)
        return Invoke-NativeText -FilePath 'docker' -Arguments $arguments
    } finally {
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

function Get-MySqlLines {
    param([string]$Database, [string]$Sql, [string]$User = 'root')
    $result = Invoke-MySql -Database $Database -Sql $Sql -User $User
    if ($result.exitCode -ne 0) {
        throw "MySQL 查询失败（exit=$($result.exitCode)）：$($result.lines -join ' | ')"
    }
    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($line in $result.lines) {
        if ($line.Trim().Length -eq 0) { continue }
        $lines.Add($line)
    }
    return @($lines)
}

function Get-ListenerPids {
    param([int]$LocalPort)
    $result = Invoke-NativeText -FilePath 'netstat' -Arguments @('-ano')
    $found = New-Object System.Collections.Generic.List[int]
    foreach ($line in $result.lines) {
        if ($line -notmatch 'LISTENING') { continue }
        if ($line -notmatch (":{0}\s" -f $LocalPort)) { continue }
        $parts = @($line.Trim() -split '\s+')
        $last = $parts[$parts.Length - 1]
        if ($last -match '^\d+$') { $found.Add([int]$last) }
    }
    return @($found | Sort-Object -Unique)
}

function Get-ListenerTexts {
    param([int]$LocalPort)
    $result = Invoke-NativeText -FilePath 'netstat' -Arguments @('-ano')
    $texts = New-Object System.Collections.Generic.List[string]
    foreach ($line in $result.lines) {
        if ($line -notmatch 'LISTENING') { continue }
        if ($line -notmatch (":{0}\s" -f $LocalPort)) { continue }
        $texts.Add(($line.Trim() -replace '\s+', ' '))
    }
    return @($texts)
}

function Get-ProcessCommandLine {
    param([int]$ProcessId)
    try {
        $info = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction Stop
        if ($null -eq $info) { return $null }
        return [string]$info.CommandLine
    } catch {
        return $null
    }
}

function New-Client {
    param([string]$BaseAddress = "http://127.0.0.1:$Port")
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.UseCookies = $false
    $client = New-Object System.Net.Http.HttpClient($handler)
    $client.BaseAddress = [Uri]$BaseAddress
    $client.Timeout = [TimeSpan]::FromSeconds(60)
    return $client
}

function Get-TraceId {
    param($Response)
    $values = $null
    if ($Response.Headers.TryGetValues('X-Trace-Id', [ref]$values)) {
        return ($values | Select-Object -First 1)
    }
    return $null
}

function Send-Req {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Method,
        [string]$Path,
        [string]$Token,
        $Body
    )
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::$Method, $Path)
    try {
        $request.Headers.Add('Origin', $originHeader)
        if ($Token) {
            $request.Headers.Authorization =
                New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Token)
        }
        if ($null -ne $Body) {
            $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 6 -Compress }
            $request.Content = New-Object System.Net.Http.StringContent(
                $json, [System.Text.Encoding]::UTF8, 'application/json')
        }
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        return [pscustomobject]@{
            Status  = [int]$response.StatusCode
            Body    = $text
            TraceId = (Get-TraceId $response)
        }
    } finally {
        $request.Dispose()
    }
}

function Send-CreateTicket {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Token,
        [string]$Title,
        [string]$Description,
        [long]$CategoryId
    )
    $payload = @{
        submissionKey = [guid]::NewGuid().ToString()
        title         = $Title
        description   = $Description
        categoryId    = $CategoryId
        priority      = 'HIGH'
    } | ConvertTo-Json -Compress
    $content = New-Object System.Net.Http.MultipartFormDataContent
    $content.Add((New-Object System.Net.Http.StringContent(
                $payload, [System.Text.Encoding]::UTF8, 'application/json')), 'ticket')
    $request = New-Object System.Net.Http.HttpRequestMessage(
        [System.Net.Http.HttpMethod]::Post, '/fd/v1/tickets')
    try {
        $request.Headers.Add('Origin', $originHeader)
        $request.Headers.Authorization =
            New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Token)
        $request.Content = $content
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        return [pscustomobject]@{
            Status  = [int]$response.StatusCode
            Body    = $text
            TraceId = (Get-TraceId $response)
        }
    } finally {
        $request.Dispose()
    }
}

function Get-Data {
    param($Result)
    return ($Result.Body | ConvertFrom-Json).data
}

function Get-Code {
    param($Result)
    if ($Result.Status -ge 200 -and $Result.Status -lt 300) { return $null }
    try { return ($Result.Body | ConvertFrom-Json).code } catch { return $null }
}

# 工单快照：status | completion_method | action_deadline_at | ended_at | assignee_id | version | requester_id
function Get-TicketSnapshot {
    param([string]$TicketNo)
    $sql = "SELECT status, IFNULL(completion_method,'-'), IFNULL(DATE_FORMAT(action_deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'), " +
    "IF(ended_at IS NULL,'NULL','SET'), IFNULL(assignee_id,-1), version, requester_id " +
    "FROM ticket WHERE ticket_no='$TicketNo';"
    $lines = @(Get-MySqlLines -Database $TempDatabase -Sql $sql)
    if ($lines.Count -eq 0) { return $null }
    $columns = @($lines[0] -split "`t")
    if ($columns.Count -lt 7) { return $null }
    return [pscustomobject]@{
        status           = $columns[0]
        completionMethod = $columns[1]
        actionDeadlineAt = $columns[2]
        endedAt          = $columns[3]
        assigneeId       = [int]$columns[4]
        version          = [int]$columns[5]
        requesterId      = [int]$columns[6]
    }
}

function Mask-Path {
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return $Text }
    $masked = $Text -replace [regex]::Escape($repoFull), '<REPO>'
    $masked = $masked -replace [regex]::Escape($JavaHome), '<JDK21_HOME>'
    $masked = $masked -replace [regex]::Escape($env:TEMP), '<TEMP>'
    return $masked
}

$backendStarted = $false
$backendProcess = $null
$appPid = $null
$clients = New-Object System.Collections.Generic.List[object]

try {
    # ── 1. 演示库基线计数（只读） ────────────────────────────────────────────
    Write-Step '演示库基线计数（只读；本次运行结束后必须完全一致）'
    $demoBefore = @(Get-MySqlLines -Database $DemoDatabase -Sql (
            "SELECT (SELECT COUNT(*) FROM iam_user), (SELECT COUNT(*) FROM ticket), " +
            "(SELECT COUNT(*) FROM ticket_category), (SELECT COUNT(*) FROM ticket_record), " +
            "(SELECT IFNULL(MAX(id),0) FROM ticket), (SELECT COUNT(*) FROM iam_user_role);"))
    $demoBeforeColumns = @($demoBefore[0] -split "`t")
    $demoTicketsBefore = @(Get-MySqlLines -Database $DemoDatabase -Sql "SELECT IFNULL(GROUP_CONCAT(ticket_no ORDER BY id),'-') FROM ticket;")
    $demoBeforeState = [ordered]@{
        users         = [int]$demoBeforeColumns[0]
        tickets       = [int]$demoBeforeColumns[1]
        categories    = [int]$demoBeforeColumns[2]
        records       = [int]$demoBeforeColumns[3]
        maxTicketId   = [int]$demoBeforeColumns[4]
        userRoleLinks = [int]$demoBeforeColumns[5]
        ticketNos     = $demoTicketsBefore[0]
        at            = (Get-Date).ToString('s')
    }
    Write-Host ("演示库 flowdesk：用户 {0}、工单 {1}、分类 {2}、工单记录 {3}、工单号 [{4}]" -f `
            $demoBeforeState.users, $demoBeforeState.tickets, $demoBeforeState.categories, `
            $demoBeforeState.records, $demoBeforeState.ticketNos)

    $demoBackendPidsBefore = @(Get-ListenerPids -LocalPort $DemoBackendPort)
    Write-Host ("既有后端 {0} 端口监听 PID：{1}（本脚本不会对它执行任何操作）" -f $DemoBackendPort, ($demoBackendPidsBefore -join ','))

    # ── 2. 空库前提：临时库先 DROP 再 CREATE ─────────────────────────────────
    Write-Step "空库前提：删除并重建临时库 $TempDatabase"
    $existedBefore = @(Get-MySqlLines -Sql (
            "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$TempDatabase';"))
    Add-Assertion 'precondition.tempDatabaseAbsentBeforeRun' ([int]$existedBefore[0] -eq 0) `
        "运行前 information_schema 中 $TempDatabase 的 schema 数=$($existedBefore[0])"
    Add-Assertion 'precondition.tempDatabaseIsNotDemoDatabase' ($TempDatabase -ne $DemoDatabase) `
        "temp=$TempDatabase demo=$DemoDatabase"

    $portListenersBefore = @(Get-ListenerPids -LocalPort $Port)
    Add-Assertion 'precondition.portFreeBeforeStart' ($portListenersBefore.Count -eq 0) `
        "启动前 $Port 监听 PID=[$($portListenersBefore -join ',')]"

    $ddl = Invoke-MySql -Sql (
        "DROP DATABASE IF EXISTS $TempDatabase; " +
        "CREATE DATABASE $TempDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;")
    if ($ddl.exitCode -ne 0) { throw "创建临时库失败：$($ddl.lines -join ' | ')" }
    $tableCountAfterCreate = @(Get-MySqlLines -Sql (
            "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$TempDatabase';"))
    Add-Assertion 'precondition.emptyDatabaseAfterCreate' ([int]$tableCountAfterCreate[0] -eq 0) `
        "CREATE 之后临时库内表数量=$($tableCountAfterCreate[0])（Flyway 尚未运行）"

    # ── 3. 启动临时后端（demo Profile，端口 8091） ──────────────────────────
    Write-Step "启动临时后端：demo Profile、端口 $Port、datasource 指向 $TempDatabase"
    $mvnw = Join-Path $repoFull 'mvnw.cmd'
    # 本机进程环境块里同时存在 NO_PROXY 与 no_proxy，Windows PowerShell 5.1 的 Start-Process
    # （以及 Get-ChildItem Env:）会把环境变量读进大小写不敏感的字典，遇到这种重复键直接抛
    # "Item has already been added. Key in dictionary: 'NO_PROXY' Key being added: 'no_proxy'"。
    # 因此这里不用 Start-Process，改用 System.Diagnostics.Process 直接启动：不触碰环境字典，
    # 子进程原样继承父进程环境块（含本次的 FLOWDESK_DB_* 覆盖），重定向交给 cmd.exe 自己 > 2>。
    $stdoutLogQuoted = '"' + $stdoutLog + '"'
    $stderrLogQuoted = '"' + $stderrLog + '"'
    $cmdCommand = 'mvnw.cmd -B spring-boot:run -Dspring-boot.run.profiles=demo ' +
    "-Dspring-boot.run.arguments=--server.port=$Port" +
    ' > ' + $stdoutLogQuoted + ' 2> ' + $stderrLogQuoted
    $processStartInfo = New-Object System.Diagnostics.ProcessStartInfo
    $processStartInfo.FileName = 'cmd.exe'
    $processStartInfo.Arguments = '/c ' + $cmdCommand
    $processStartInfo.WorkingDirectory = $repoFull
    $processStartInfo.UseShellExecute = $false
    $processStartInfo.CreateNoWindow = $true
    $backendProcess = New-Object System.Diagnostics.Process
    $backendProcess.StartInfo = $processStartInfo
    $null = $backendProcess.Start()
    $backendStarted = $true
    $script:notes.Add("临时后端启动方式：System.Diagnostics.Process（cmd.exe /c mvnw.cmd ...），未使用 Start-Process")

    $healthClient = New-Client
    $clients.Add($healthClient)
    $healthBody = $null
    $healthWaitStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($healthWaitStopwatch.Elapsed.TotalSeconds -lt $ReadyTimeoutSeconds) {
        if ($backendProcess.HasExited) { break }
        try {
            $healthResponse = $healthClient.GetAsync('/actuator/health').GetAwaiter().GetResult()
            $healthText = $healthResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if ([int]$healthResponse.StatusCode -eq 200 -and $healthText -match '"status"\s*:\s*"UP"') {
                $healthBody = $healthText
                break
            }
        } catch {
            # 端口尚未监听：继续轮询
        }
        Start-Sleep -Seconds 2
    }
    $healthWaitStopwatch.Stop()
    $readySeconds = [math]::Round($healthWaitStopwatch.Elapsed.TotalSeconds, 1)

    Add-Assertion 'backend.healthUpWithin90s' ($null -ne $healthBody) `
        "等待 $readySeconds 秒后 /actuator/health = $(if ($null -ne $healthBody) { $healthBody } else { '未就绪' })"

    $appPids = @(Get-ListenerPids -LocalPort $Port)
    Add-Assertion 'backend.listeningOnTempPort' ($appPids.Count -ge 1) "监听 PID=[$($appPids -join ',')]"
    if ($appPids.Count -gt 0) {
        $appPid = $appPids[0]
        $appCommandLine = Get-ProcessCommandLine -ProcessId $appPid
        Add-Assertion 'backend.processIdentityHasTempPort' `
            (($null -ne $appCommandLine) -and ($appCommandLine -match [regex]::Escape("--server.port=$Port"))) `
            "PID=$appPid 命令行含 --server.port=$Port：$([bool]($appCommandLine -match [regex]::Escape("--server.port=$Port")))"
        Add-Assertion 'backend.processIsNotExistingDemoBackend' `
            ($appPids -notcontains $demoBackendPidsBefore[0]) `
            "临时 $Port PID=$appPid；既有 $DemoBackendPort PID=[$($demoBackendPidsBefore -join ',')]"
        $script:notes.Add("临时后端 JVM PID=$appPid（父进程 cmd.exe PID=$($backendProcess.Id)）")
    }

    # 启动日志摘要：profile、Flyway 迁移与库名，作为「从空库执行 Flyway」的过程证据。
    # 日志此刻仍被 cmd.exe 的重定向句柄持有，必须用 FileShare.ReadWrite 打开；
    # 读日志只是补充证据，失败也不能中断主链，所以整段包裹 try/catch。
    Start-Sleep -Seconds 1
    $logText = ''
    foreach ($logPath in @($stdoutLog, $stderrLog)) {
        try {
            if (-not (Test-Path -LiteralPath $logPath)) { continue }
            $logStream = [System.IO.File]::Open($logPath, [System.IO.FileMode]::Open,
                [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
            try {
                $logReader = New-Object System.IO.StreamReader($logStream, [System.Text.Encoding]::UTF8)
                $logText += $logReader.ReadToEnd()
                $logReader.Dispose()
            } finally {
                $logStream.Dispose()
            }
        } catch {
            $script:notes.Add("读取后端日志失败（不影响断言）：$($_.Exception.Message)")
        }
    }
    $highlightPattern = '(?i)(profile|Migrating schema|Successfully applied|Schema history table|jdbc:mysql|Flyway|Started FlowDeskApplication|Tomcat started)'
    $highlights = New-Object System.Collections.Generic.List[string]
    foreach ($line in ($logText -split "`r?`n")) {
        if ($line -match $highlightPattern) { $highlights.Add((Mask-Path $line.Trim())) }
        if ($highlights.Count -ge 40) { break }
    }
    $script:startupLogHighlights = @($highlights)

    # ── 4. Flyway 记录（临时库直查） ────────────────────────────────────────
    Write-Step 'Flyway 记录（临时库 flyway_schema_history）'
    $flywayRaw = @(Get-MySqlLines -Database $TempDatabase -Sql (
            "SELECT installed_rank, IFNULL(version,'-'), description, script, type, success, IFNULL(execution_time,-1) " +
            "FROM flyway_schema_history ORDER BY installed_rank;"))
    $flywayVersions = New-Object System.Collections.Generic.List[string]
    $flywayAllSuccess = $true
    $flywayRepeatables = New-Object System.Collections.Generic.List[string]
    foreach ($row in $flywayRaw) {
        $columns = @($row -split "`t")
        $entry = [ordered]@{
            installedRank = [int]$columns[0]
            version       = $columns[1]
            description   = $columns[2]
            script        = $columns[3]
            type          = $columns[4]
            success       = [int]$columns[5]
            executionTime = [int]$columns[6]
        }
        $script:flywayRows += [pscustomobject]$entry
        if ($entry.success -ne 1) { $flywayAllSuccess = $false }
        if ($entry.type -eq 'SQL' -and $entry.version -ne '-') { $flywayVersions.Add($entry.version) }
        if ($entry.version -eq '-') { $flywayRepeatables.Add($entry.script) }
    }
    $versionChain = ($flywayVersions -join ',')
    Write-Host "Flyway 应用版本：$versionChain；全部 success=1：$flywayAllSuccess；repeatable：$($flywayRepeatables -join ',')"
    Add-Assertion 'flyway.appliedVersionsAre1_2_4_5_6' ($versionChain -eq '1,2,4,5,6') `
        "实际版本链=[$versionChain]（V3 是历史遗留已删除版本，不应出现）"
    Add-Assertion 'flyway.noFailedRecord' $flywayAllSuccess `
        "记录数=$($script:flywayRows.Count)，success 全为 1：$flywayAllSuccess"

    # ── 5. 演示种子数量（只在该 profile 生效） ──────────────────────────────
    Write-Step '演示种子数量断言'
    $seedRow = @(Get-MySqlLines -Database $TempDatabase -Sql (
            "SELECT (SELECT COUNT(*) FROM iam_user), (SELECT COUNT(*) FROM iam_role), " +
            "(SELECT COUNT(*) FROM iam_permission), (SELECT COUNT(*) FROM ticket_category), " +
            "(SELECT COUNT(*) FROM ticket_category WHERE status='DISABLED');"))
    $seedColumns = @($seedRow[0] -split "`t")
    $seedUserNames = @(Get-MySqlLines -Database $TempDatabase -Sql "SELECT IFNULL(GROUP_CONCAT(username ORDER BY id),'-') FROM iam_user;")[0]
    $seedCategoryNames = @(Get-MySqlLines -Database $TempDatabase -Sql (
            "SELECT IFNULL(GROUP_CONCAT(CONCAT(name,'/',status) ORDER BY sort_order, id),'-') FROM ticket_category;"))[0]
    $seedState = [ordered]@{
        users              = [int]$seedColumns[0]
        roles              = [int]$seedColumns[1]
        permissions        = [int]$seedColumns[2]
        categories         = [int]$seedColumns[3]
        disabledCategories = [int]$seedColumns[4]
        usernames          = $seedUserNames
        categoryNames      = $seedCategoryNames
        seededByRepeatable = $flywayRepeatables -contains 'R__seed_demo_data.sql'
    }
    Add-Assertion 'seed.users.count3' ($seedState.users -eq 3) "iam_user=$($seedState.users)"
    Add-Assertion 'seed.users.areDemoThree' ($seedUserNames -eq 'employee,it,admin') "usernames=[$seedUserNames]"
    Add-Assertion 'seed.roles.count3' ($seedState.roles -eq 3) "iam_role=$($seedState.roles)"
    Add-Assertion 'seed.permissions.count14' ($seedState.permissions -eq 14) "iam_permission=$($seedState.permissions)"
    Add-Assertion 'seed.categories.count5' ($seedState.categories -eq 5) "ticket_category=$($seedState.categories)"
    Add-Assertion 'seed.categories.disabledCount1' ($seedState.disabledCategories -eq 1) `
        "DISABLED 分类数=$($seedState.disabledCategories)"
    Add-Assertion 'seed.demoSeedOnlyInThisProfile' ($seedState.seededByRepeatable -and $seedState.users -eq 3) `
        "flyway 记录里的 repeatable 脚本=[$($flywayRepeatables -join ',')]；它由 demo Profile 的 classpath:db/demo 位置引入"

    # ── 6. 三角色登录 ───────────────────────────────────────────────────────
    Write-Step '三角色登录（employee / it / admin）'
    $employeeClient = New-Client; $clients.Add($employeeClient)
    $itClient = New-Client; $clients.Add($itClient)
    $adminClient = New-Client; $clients.Add($adminClient)

    $loginEmployee = Send-Req -Client $employeeClient -Method Post -Path '/fd/v1/auth/login' `
        -Body @{ username = $EmployeeUser; password = $EmployeePassword }
    Add-Assertion 'login.employee.200' ($loginEmployee.Status -eq 200) `
        "status=$($loginEmployee.Status) traceId=$($loginEmployee.TraceId)"
    $employeeToken = (Get-Data $loginEmployee).accessToken

    $loginIt = Send-Req -Client $itClient -Method Post -Path '/fd/v1/auth/login' `
        -Body @{ username = $ItUser; password = $ItPassword }
    Add-Assertion 'login.it.200' ($loginIt.Status -eq 200) `
        "status=$($loginIt.Status) traceId=$($loginIt.TraceId)"
    $itToken = (Get-Data $loginIt).accessToken

    $loginAdmin = Send-Req -Client $adminClient -Method Post -Path '/fd/v1/auth/login' `
        -Body @{ username = $AdminUser; password = $AdminPassword }
    Add-Assertion 'login.admin.200' ($loginAdmin.Status -eq 200) `
        "status=$($loginAdmin.Status) traceId=$($loginAdmin.TraceId)"
    $adminToken = (Get-Data $loginAdmin).accessToken

    $logins = New-Object System.Collections.Generic.List[object]
    foreach ($pair in @(
            @{ role = 'EMPLOYEE'; username = $EmployeeUser; result = $loginEmployee; client = $employeeClient; token = $employeeToken },
            @{ role = 'IT_SUPPORT'; username = $ItUser; result = $loginIt; client = $itClient; token = $itToken },
            @{ role = 'SYSTEM_ADMIN'; username = $AdminUser; result = $loginAdmin; client = $adminClient; token = $adminToken })) {
        $me = Send-Req -Client $pair.client -Method Get -Path '/fd/v1/auth/me' -Token $pair.token
        $meData = Get-Data $me
        $logins.Add([pscustomobject]@{
                expectedRole = $pair.role
                username     = $pair.username
                loginStatus  = $pair.result.Status
                loginTraceId = $pair.result.TraceId
                meStatus     = $me.Status
                meTraceId    = $me.TraceId
                userId       = $meData.id
                displayName  = $meData.displayName
                roles        = @($meData.roles)
                permissions  = @($meData.permissions)
            })
    }
    $employeeLogin = $logins[0]
    $itLogin = $logins[1]
    $adminLogin = $logins[2]
    Add-Assertion 'login.employee.rolesContainsEMPLOYEE' (@($employeeLogin.roles) -contains 'EMPLOYEE') `
        "roles=$(@($employeeLogin.roles) -join '|') userId=$($employeeLogin.userId)"
    Add-Assertion 'login.it.rolesContainsIT_SUPPORT' (@($itLogin.roles) -contains 'IT_SUPPORT') `
        "roles=$(@($itLogin.roles) -join '|') userId=$($itLogin.userId)"
    Add-Assertion 'login.admin.rolesContainsSYSTEM_ADMIN' (@($adminLogin.roles) -contains 'SYSTEM_ADMIN') `
        "roles=$(@($adminLogin.roles) -join '|') userId=$($adminLogin.userId)"
    Add-Assertion 'login.tokensIssued' `
        ((-not [string]::IsNullOrEmpty($employeeToken)) -and (-not [string]::IsNullOrEmpty($itToken)) -and (-not [string]::IsNullOrEmpty($adminToken))) `
        "三条 accessToken 均已取得（不落盘、不写进证据）"

    # ── 7. 分类选项（用启用分类创建工单） ───────────────────────────────────
    Write-Step '读取启用分类选项'
    $options = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/categories/options' -Token $employeeToken
    $optionItems = @(Get-Data $options)
    $targetCategory = $optionItems | Where-Object { $_.name -eq '账号与权限' } | Select-Object -First 1
    if ($null -eq $targetCategory) { $targetCategory = $optionItems | Select-Object -First 1 }
    Add-Assertion 'categories.options.hasEnabledAccountCategory' ($null -ne $targetCategory) `
        "options=$(@($optionItems | ForEach-Object { $_.name }) -join '|')；选用 id=$($targetCategory.id) name=$($targetCategory.name)"
    $categoryId = [long]$targetCategory.id

    # ── 8. 四态主链：五步 HTTP 动作 ─────────────────────────────────────────
    Write-Step '四态主链第 1 步：员工创建工单（201）'
    $title = "空库演示-账号无法登录-$stamp"
    $create = Send-CreateTicket -Client $employeeClient -Token $employeeToken -Title $title `
        -Description '阶段 4 空库演示：员工提交、IT 处理、员工确认的四态主链。' -CategoryId $categoryId
    Add-Assertion 'chain.step1.create.201' ($create.Status -eq 201) `
        "status=$($create.Status) traceId=$($create.TraceId)"
    $script:ticketNo = (Get-Data $create).ticketNo
    $snapshot1 = Get-TicketSnapshot -TicketNo $script:ticketNo
    Add-Assertion 'chain.step1.db.statusPENDING' ($snapshot1.status -eq 'PENDING') `
        "status=$($snapshot1.status) version=$($snapshot1.version) requesterId=$($snapshot1.requesterId)"
    Add-Assertion 'chain.step1.db.versionZero' ($snapshot1.version -eq 0) "version=$($snapshot1.version)"
    Add-Assertion 'chain.step1.db.noAssignee' ($snapshot1.assigneeId -eq -1) "assignee_id=[$($snapshot1.assigneeId)]"
    $script:steps.Add([pscustomobject]@{
            step = 1; actor = 'employee'; action = 'create-ticket'; method = 'POST'; path = '/fd/v1/tickets'
            httpStatus = $create.Status; traceId = $create.TraceId
            ticketNo = $script:ticketNo; statusAfter = $snapshot1.status; versionAfter = $snapshot1.version
        })

    Write-Step '四态主链第 2 步：IT 领取（200）'
    $claim = Send-Req -Client $itClient -Method Post -Path "/fd/v1/tickets/$($script:ticketNo)/actions/claim" `
        -Token $itToken -Body @{ version = $snapshot1.version }
    Add-Assertion 'chain.step2.claim.200' ($claim.Status -eq 200) `
        "status=$($claim.Status) traceId=$($claim.TraceId)"
    $claimData = Get-Data $claim
    $snapshot2 = Get-TicketSnapshot -TicketNo $script:ticketNo
    Add-Assertion 'chain.step2.db.statusPROCESSING' ($snapshot2.status -eq 'PROCESSING') "status=$($snapshot2.status)"
    Add-Assertion 'chain.step2.db.assigneeIsIt' ($snapshot2.assigneeId -eq $itLogin.userId) `
        "assignee_id=$($snapshot2.assigneeId) itUserId=$($itLogin.userId)"
    Add-Assertion 'chain.step2.db.versionIncremented' ($snapshot2.version -eq ($snapshot1.version + 1)) `
        "expected=$($snapshot1.version + 1) actual=$($snapshot2.version)"
    $script:steps.Add([pscustomobject]@{
            step = 2; actor = 'it'; action = 'claim'; method = 'POST'
            path = "/fd/v1/tickets/$($script:ticketNo)/actions/claim"
            httpStatus = $claim.Status; traceId = $claim.TraceId
            ticketNo = $script:ticketNo; statusAfter = $snapshot2.status; versionAfter = $snapshot2.version
        })

    Write-Step '四态主链第 3 步：IT 追加处理记录（200）'
    $processContent = '已重置账号密码，请用新密码登录后确认。'
    $process = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$($script:ticketNo)/actions/add-processing-record" `
        -Token $itToken -Body @{ version = $snapshot2.version; content = $processContent }
    Add-Assertion 'chain.step3.addProcessingRecord.200' ($process.Status -eq 200) `
        "status=$($process.Status) traceId=$($process.TraceId)"
    $snapshot3 = Get-TicketSnapshot -TicketNo $script:ticketNo
    Add-Assertion 'chain.step3.db.statusUnchanged' ($snapshot3.status -eq $snapshot2.status) `
        "before=$($snapshot2.status) after=$($snapshot3.status)"
    Add-Assertion 'chain.step3.db.assigneeUnchanged' ($snapshot3.assigneeId -eq $snapshot2.assigneeId) `
        "before=$($snapshot2.assigneeId) after=$($snapshot3.assigneeId)"
    Add-Assertion 'chain.step3.db.versionPlusOne' ($snapshot3.version -eq ($snapshot2.version + 1)) `
        "expected=$($snapshot2.version + 1) actual=$($snapshot3.version)"
    $script:steps.Add([pscustomobject]@{
            step = 3; actor = 'it'; action = 'add-processing-record'; method = 'POST'
            path = "/fd/v1/tickets/$($script:ticketNo)/actions/add-processing-record"
            httpStatus = $process.Status; traceId = $process.TraceId
            ticketNo = $script:ticketNo; statusAfter = $snapshot3.status; versionAfter = $snapshot3.version
        })

    Write-Step '四态主链第 4 步：IT 提交解决结果（200，限期 +7 天）'
    $resolutionContent = '已确认账号状态正常，问题原因为密码过期。'
    $submit = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$($script:ticketNo)/actions/submit-resolution" `
        -Token $itToken -Body @{ version = $snapshot3.version; content = $resolutionContent }
    Add-Assertion 'chain.step4.submitResolution.200' ($submit.Status -eq 200) `
        "status=$($submit.Status) traceId=$($submit.TraceId)"
    $submitData = Get-Data $submit
    $snapshot4 = Get-TicketSnapshot -TicketNo $script:ticketNo
    Add-Assertion 'chain.step4.db.statusWAITING_FOR_CONFIRMATION' ($snapshot4.status -eq 'WAITING_FOR_CONFIRMATION') `
        "status=$($snapshot4.status)"
    Add-Assertion 'chain.step4.db.deadlinePresent' ($snapshot4.actionDeadlineAt -ne 'NULL') `
        "action_deadline_at=[$($snapshot4.actionDeadlineAt)]"
    $deadlineDeltaSql = "SELECT IFNULL(TIMESTAMPDIFF(SECOND, DATE_ADD(r.created_at, INTERVAL 7 DAY), t.action_deadline_at),'NULL') " +
    "FROM ticket t JOIN ticket_record r ON r.ticket_id = t.id AND r.record_type = 'RESOLUTION' " +
    "WHERE t.ticket_no='$($script:ticketNo)';"
    $deadlineDeltaRaw = @(Get-MySqlLines -Database $TempDatabase -Sql $deadlineDeltaSql)[0]
    $deadlineDeltaSeconds = $null
    if ($deadlineDeltaRaw -match '^-?\d+$') { $deadlineDeltaSeconds = [int]$deadlineDeltaRaw }
    Add-Assertion 'chain.step4.db.deadlineIsResolutionPlus7DaysWithin60s' `
        (($null -ne $deadlineDeltaSeconds) -and ([math]::Abs($deadlineDeltaSeconds) -le 60)) `
        "action_deadline_at - (RESOLUTION 记录 created_at + 7 天) = $deadlineDeltaRaw 秒（要求 |差值| <= 60）"
    $responseDeltaSeconds = $null
    try {
        $responseDelta = ([datetimeoffset]::Parse($submitData.actionDeadlineAt)) - ([datetimeoffset]::Parse($submitData.actionTime))
        $responseDeltaSeconds = [math]::Round($responseDelta.TotalSeconds, 1)
    } catch { $responseDeltaSeconds = $null }
    Add-Assertion 'chain.step4.response.deadlineIsSevenDaysWithin60s' `
        (($null -ne $responseDeltaSeconds) -and ([math]::Abs($responseDeltaSeconds - 604800) -le 60)) `
        "响应 actionDeadlineAt=[$($submitData.actionDeadlineAt)] actionTime=[$($submitData.actionTime)] 差值=$responseDeltaSeconds 秒（604800=7 天）"
    Add-Assertion 'chain.step4.db.assigneeStillIt' ($snapshot4.assigneeId -eq $itLogin.userId) `
        "assignee_id=$($snapshot4.assigneeId)"
    $script:steps.Add([pscustomobject]@{
            step = 4; actor = 'it'; action = 'submit-resolution'; method = 'POST'
            path = "/fd/v1/tickets/$($script:ticketNo)/actions/submit-resolution"
            httpStatus = $submit.Status; traceId = $submit.TraceId
            ticketNo = $script:ticketNo; statusAfter = $snapshot4.status; versionAfter = $snapshot4.version
            actionDeadlineAt = $snapshot4.actionDeadlineAt; deadlineOffsetSecondsFromSevenDays = $deadlineDeltaSeconds
        })

    Write-Step '四态主链第 5 步：员工确认解决（200）'
    $confirm = Send-Req -Client $employeeClient -Method Post `
        -Path "/fd/v1/tickets/$($script:ticketNo)/actions/confirm-resolution" `
        -Token $employeeToken -Body @{ version = $snapshot4.version }
    Add-Assertion 'chain.step5.confirmResolution.200' ($confirm.Status -eq 200) `
        "status=$($confirm.Status) traceId=$($confirm.TraceId)"
    $confirmData = Get-Data $confirm
    $snapshot5 = Get-TicketSnapshot -TicketNo $script:ticketNo
    Add-Assertion 'chain.step5.db.statusCOMPLETED' ($snapshot5.status -eq 'COMPLETED') "status=$($snapshot5.status)"
    Add-Assertion 'chain.step5.db.versionPlusOne' ($snapshot5.version -eq ($snapshot4.version + 1)) `
        "expected=$($snapshot4.version + 1) actual=$($snapshot5.version)"
    $script:steps.Add([pscustomobject]@{
            step = 5; actor = 'employee'; action = 'confirm-resolution'; method = 'POST'
            path = "/fd/v1/tickets/$($script:ticketNo)/actions/confirm-resolution"
            httpStatus = $confirm.Status; traceId = $confirm.TraceId
            ticketNo = $script:ticketNo; statusAfter = $snapshot5.status; versionAfter = $snapshot5.version
        })

    $traceIds = @($script:steps | ForEach-Object { $_.traceId })
    $missingTraceIds = @($traceIds | Where-Object { [string]::IsNullOrWhiteSpace($_) })
    Add-Assertion 'chain.traceIdPresentOnAllFiveSteps' ($missingTraceIds.Count -eq 0) `
        "五步 X-Trace-Id=[$($traceIds -join ' , ')]"
    Add-Assertion 'chain.successStatusesAre201Then200' `
        ((@($script:steps | ForEach-Object { $_.httpStatus }) -join ',') -eq '201,200,200,200,200') `
        "五步状态码=[$((@($script:steps | ForEach-Object { $_.httpStatus }) -join ','))]"

    # ── 9. 终态数据库直查 ───────────────────────────────────────────────────
    Write-Step '终态数据库直查'
    $script:snapshotText = "status=$($snapshot5.status) completion_method=$($snapshot5.completionMethod) " +
    "action_deadline_at=$($snapshot5.actionDeadlineAt) ended_at=$($snapshot5.endedAt) " +
    "assignee_id=$($snapshot5.assigneeId) version=$($snapshot5.version) requester_id=$($snapshot5.requesterId)"
    Write-Host $script:snapshotText
    Add-Assertion 'final.status.COMPLETED' ($snapshot5.status -eq 'COMPLETED') "status=$($snapshot5.status)"
    Add-Assertion 'final.completionMethod.REQUESTER_CONFIRMED' ($snapshot5.completionMethod -eq 'REQUESTER_CONFIRMED') `
        "completion_method=$($snapshot5.completionMethod)"
    Add-Assertion 'final.actionDeadlineAtNull' ($snapshot5.actionDeadlineAt -eq 'NULL') `
        "action_deadline_at=[$($snapshot5.actionDeadlineAt)]"
    Add-Assertion 'final.endedAtNotNull' ($snapshot5.endedAt -eq 'SET') "ended_at=$($snapshot5.endedAt)"
    Add-Assertion 'final.assigneeIdStillIt' ($snapshot5.assigneeId -eq $itLogin.userId) `
        "assignee_id=$($snapshot5.assigneeId) itUserId=$($itLogin.userId)"
    Add-Assertion 'final.requesterIdIsEmployee' ($snapshot5.requesterId -eq $employeeLogin.userId) `
        "requester_id=$($snapshot5.requesterId) employeeUserId=$($employeeLogin.userId)"

    $script:recordRows = @(Get-MySqlLines -Database $TempDatabase -Sql (
            "SELECT sequence_no, record_type, actor_type, IFNULL(actor_user_id,-1) FROM ticket_record " +
            "WHERE ticket_id = (SELECT id FROM ticket WHERE ticket_no='$($script:ticketNo)') ORDER BY sequence_no;"))
    $recordTypes = @($script:recordRows | ForEach-Object { (@($_ -split "`t")[1]) })
    $recordSequences = @($script:recordRows | ForEach-Object { [int](@($_ -split "`t")[0]) })
    $recordTotalInTempDb = @(Get-MySqlLines -Database $TempDatabase -Sql "SELECT COUNT(*) FROM ticket_record;")[0]
    Add-Assertion 'final.ticketRecord.count5' `
        (($script:recordRows.Count -eq 5) -and ([int]$recordTotalInTempDb -eq 5)) `
        "本工单记录=$($script:recordRows.Count)，临时库记录总数=$recordTotalInTempDb"
    Add-Assertion 'final.ticketRecord.typesOrdered' `
        (($recordTypes -join ',') -eq 'CREATE,CLAIM,PROCESS,RESOLUTION,COMPLETION') `
        "record_type 依序=[$($recordTypes -join ',')]"
    Add-Assertion 'final.ticketRecord.sequences1To5' (($recordSequences -join ',') -eq '1,2,3,4,5') `
        "sequence_no=[$($recordSequences -join ',')]"
    $createRecord = @($script:recordRows | Where-Object { (@($_ -split "`t")[1]) -eq 'CREATE' })[0]
    Add-Assertion 'final.ticketRecord.createActorIsEmployee' `
        ([int](@($createRecord -split "`t")[3]) -eq $employeeLogin.userId) `
        "CREATE 记录 actor_user_id=$([int](@($createRecord -split "`t")[3]))"

    # ── 10. 否定断言：终态再 claim ──────────────────────────────────────────
    Write-Step '否定断言：终态再调用 claim 必须 409'
    $terminalClaim = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$($script:ticketNo)/actions/claim" `
        -Token $itToken -Body @{ version = $snapshot5.version }
    Add-Assertion 'terminal.reClaim.409' ($terminalClaim.Status -eq 409) `
        "status=$($terminalClaim.Status) code=$(Get-Code $terminalClaim) traceId=$($terminalClaim.TraceId)"
    $snapshotAfterNegative = Get-TicketSnapshot -TicketNo $script:ticketNo
    Add-Assertion 'terminal.reClaim.stateUnchanged' `
        (($snapshotAfterNegative.status -eq 'COMPLETED') -and ($snapshotAfterNegative.version -eq $snapshot5.version)) `
        "status=$($snapshotAfterNegative.status) version=$($snapshotAfterNegative.version)"
}
catch {
    $script:notes.Add("执行过程中抛出异常：$($_.Exception.Message)")
    Add-Assertion 'run.completedWithoutException' $false "异常：$($_.Exception.Message)"
    Write-Host "[FAIL] 执行过程中抛出异常：$($_.Exception.Message)" -ForegroundColor Red
}
finally {
    # ── 11. 清理：杀进程 → 删库 → 核对 ──────────────────────────────────────
    Write-Step '清理：杀掉临时后端进程、删除临时库、核对演示库未变'
    $script:cleanup['startedAt'] = (Get-Date).ToString('s')

    if ($backendStarted) {
        try {
        $killTargets = New-Object System.Collections.Generic.List[int]
        $currentPids = @(Get-ListenerPids -LocalPort $Port)
        foreach ($candidatePid in $currentPids) {
            $candidateCommandLine = Get-ProcessCommandLine -ProcessId $candidatePid
            if (($null -ne $candidateCommandLine) -and ($candidateCommandLine -match [regex]::Escape("--server.port=$Port"))) {
                $killTargets.Add($candidatePid)
            }
        }
        $script:cleanup['killedListenerPids'] = @($killTargets)
        $script:cleanup['killGuard'] = "只杀命令行含 --server.port=$Port 的监听进程；$DemoBackendPort 的既有后端不在候选内"
        foreach ($targetPid in $killTargets) {
            $null = Invoke-NativeText -FilePath 'taskkill' -Arguments @('/PID', "$targetPid", '/F')
        }
        $waitStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        while ($waitStopwatch.Elapsed.TotalSeconds -lt 20) {
            if (@(Get-ListenerPids -LocalPort $Port).Count -eq 0) { break }
            Start-Sleep -Milliseconds 500
        }
        if ($null -ne $backendProcess) {
            $backendProcess.Refresh()
            if (-not $backendProcess.HasExited) {
                $null = Invoke-NativeText -FilePath 'taskkill' -Arguments @('/PID', "$($backendProcess.Id)", '/T', '/F')
            }
        }
        Start-Sleep -Milliseconds 500
        } catch {
            Add-Assertion 'cleanup.killPhaseFailed' $false "杀进程阶段异常：$($_.Exception.Message)"
        }
    }

    try {
    $portListenersAfter = @(Get-ListenerTexts -LocalPort $Port)
    $portPidsAfter = @(Get-ListenerPids -LocalPort $Port)
    $script:cleanup['port'] = $Port
    $script:cleanup['listenersAfter'] = @($portListenersAfter)
    $script:cleanup['listeningPidsAfter'] = @($portPidsAfter)
    Add-Assertion 'cleanup.port8091HasNoListener' ($portPidsAfter.Count -eq 0) `
        "netstat -ano 中 :$Port 的 LISTENING 行=[$($portListenersAfter -join ' / ')]"

    $dropResult = Invoke-MySql -Sql "DROP DATABASE IF EXISTS $TempDatabase;"
    $script:cleanup['dropDatabaseExitCode'] = $dropResult.exitCode
    Add-Assertion 'cleanup.dropDatabaseSucceeded' ($dropResult.exitCode -eq 0) `
        "DROP DATABASE IF EXISTS $TempDatabase 退出码=$($dropResult.exitCode)"
    $tempDbAfter = @(Get-MySqlLines -Sql "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$TempDatabase';")
    $script:cleanup['tempDatabaseExistsAfter'] = ([int]$tempDbAfter[0] -ne 0)
    Add-Assertion 'cleanup.tempDatabaseGone' ([int]$tempDbAfter[0] -eq 0) `
        "information_schema 中 $TempDatabase 的 schema 数=$($tempDbAfter[0])"

    $demoAfter = @(Get-MySqlLines -Database $DemoDatabase -Sql (
            "SELECT (SELECT COUNT(*) FROM iam_user), (SELECT COUNT(*) FROM ticket), " +
            "(SELECT COUNT(*) FROM ticket_category), (SELECT COUNT(*) FROM ticket_record), " +
            "(SELECT IFNULL(MAX(id),0) FROM ticket), (SELECT COUNT(*) FROM iam_user_role);"))
    if ($demoAfter.Count -gt 0) {
        $demoAfterColumns = @($demoAfter[0] -split "`t")
        $demoTicketsAfter = @(Get-MySqlLines -Database $DemoDatabase -Sql "SELECT IFNULL(GROUP_CONCAT(ticket_no ORDER BY id),'-') FROM ticket;")
        $demoAfterState = [ordered]@{
            users         = [int]$demoAfterColumns[0]
            tickets       = [int]$demoAfterColumns[1]
            categories    = [int]$demoAfterColumns[2]
            records       = [int]$demoAfterColumns[3]
            maxTicketId   = [int]$demoAfterColumns[4]
            userRoleLinks = [int]$demoAfterColumns[5]
            ticketNos     = $demoTicketsAfter[0]
            at            = (Get-Date).ToString('s')
        }
        $script:cleanup['demoDatabaseBefore'] = $demoBeforeState
        $script:cleanup['demoDatabaseAfter'] = $demoAfterState
        $unchanged = ($demoBeforeState.users -eq $demoAfterState.users) -and
        ($demoBeforeState.tickets -eq $demoAfterState.tickets) -and
        ($demoBeforeState.categories -eq $demoAfterState.categories) -and
        ($demoBeforeState.records -eq $demoAfterState.records) -and
        ($demoBeforeState.ticketNos -eq $demoAfterState.ticketNos)
        Add-Assertion 'cleanup.demoDatabaseCountsUnchanged' $unchanged `
        ("运行前 用户=$($demoBeforeState.users)/工单=$($demoBeforeState.tickets)/分类=$($demoBeforeState.categories)/记录=$($demoBeforeState.records)/工单号=[$($demoBeforeState.ticketNos)]；" +
            "运行后 用户=$($demoAfterState.users)/工单=$($demoAfterState.tickets)/分类=$($demoAfterState.categories)/记录=$($demoAfterState.records)/工单号=[$($demoAfterState.ticketNos)]")
    } else {
        Add-Assertion 'cleanup.demoDatabaseCountsUnchanged' $false '清理阶段无法读取演示库计数'
    }

    $demoBackendPidsAfter = @(Get-ListenerPids -LocalPort $DemoBackendPort)
    $script:cleanup['existingDemoBackendPidsBefore'] = @($demoBackendPidsBefore)
    $script:cleanup['existingDemoBackendPidsAfter'] = @($demoBackendPidsAfter)
    Add-Assertion 'cleanup.existingDemoBackendUntouched' `
        (($demoBackendPidsAfter.Count -eq $demoBackendPidsBefore.Count) -and
            (($demoBackendPidsBefore.Count -eq 0) -or ($demoBackendPidsAfter -contains $demoBackendPidsBefore[0]))) `
        "既有后端 $DemoBackendPort：运行前 PID=[$($demoBackendPidsBefore -join ',')]，运行后 PID=[$($demoBackendPidsAfter -join ',')]"
    Add-Assertion 'cleanup.existingDemoBackendStillListening' ($demoBackendPidsAfter.Count -ge 1) `
        "既有后端运行后仍在 $DemoBackendPort 监听：$($demoBackendPidsAfter.Count -ge 1)"
    $script:cleanup['containersUntouched'] = $true
    $script:cleanup['dockerCommandsUsed'] = @('docker exec（远程执行 mysql 客户端）')
    $script:cleanup['composeDownExecuted'] = $false
    $script:cleanup['volumesRemoved'] = $false
    $script:cleanup['finishedAt'] = (Get-Date).ToString('s')
    } catch {
        Add-Assertion 'cleanup.verificationPhaseFailed' $false "清理核对阶段异常：$($_.Exception.Message)"
        $script:cleanup['verificationError'] = $_.Exception.Message
    }

    foreach ($client in $clients) { $client.Dispose() }
}

# ── 12. 汇总与证据 ──────────────────────────────────────────────────────────
# 注意：这里刻意把每个值先算进普通变量，再放进哈希表字面量，并且用 .ToArray() 而不是 @()
# 来把 List[object] 变成数组。原因是 Windows PowerShell 5.1 的两个真实缺陷（本脚本调通前
# 都实测踩到，且报错行会指到别处）：
#   ① @(<List[object]>) 会抛 System.ArgumentException: Argument types do not match
#      （栈顶是 System.Linq.Expressions.Expression.Condition / PSEnumerableBinder.MaybeDebase；
#      同样是 List，List[string]、List[int]、ArrayList 都不触发，只有 List[object] 触发）。
#   ② 「较大的哈希表字面量 + 嵌套字面量里的 $(if ...)」组合也会抛同一个异常。
# 因此：List[object] 一律 .ToArray() 或直接遍历，条件值一律先算成变量。
# 另外整段用 try/catch 兜底：即使证据对象构建失败，也必须把（回退版）证据写到磁盘上。
$finishedAt = Get-Date
$failed = @($script:results | Where-Object { -not $_.passed })
if ($failed.Count -gt 0) { $script:quitCode = 1 }
$failedCount = $failed.Count
$totalCount = $script:results.Count
$passedCount = $totalCount - $failedCount

$scriptPath = $MyInvocation.MyCommand.Path
$maskedScriptPath = Mask-Path $scriptPath
$invocation = 'powershell -NoProfile -ExecutionPolicy Bypass -File ' +
$maskedScriptPath.Replace('<REPO>/', '').Replace('<REPO>\', '')

$json = $null
$evidenceBuilt = $false
$evidenceBuildError = $null
try {
    $scriptLineCount = @(Get-Content -LiteralPath $scriptPath).Count
    $resultValue = 'FAIL'
    if ($failedCount -eq 0) { $resultValue = 'PASS' }
    $wrapperPidValue = $null
    if ($null -ne $backendProcess) { $wrapperPidValue = [int]$backendProcess.Id }
    $startedAtDate = $startedAt.ToString('yyyy-MM-dd')
    $startedAtText = $startedAt.ToString('s')
    $finishedAtText = $finishedAt.ToString('s')
    $durationSeconds = [math]::Round(($finishedAt - $startedAt).TotalSeconds, 1)

    $assertionList = $script:results.ToArray()
    $stepList = $script:steps.ToArray()
    $loginList = @()
    if ($null -ne $logins) { $loginList = $logins.ToArray() }
    $notesList = @($script:notes)
    $flywayRecordList = @($script:flywayRows)
    $repeatableList = @($flywayRepeatables)
    $flywayVersionChain = ($flywayVersions -join ',')
    $logHighlightList = @($script:startupLogHighlights)
    $pidsBeforeList = @($demoBackendPidsBefore)
    $recordTypeList = @($recordTypes)
    $recordSequenceList = @($recordSequences)
    $redisEndpointText = "$($envValues['FLOWDESK_REDIS_HOST']):$($envValues['FLOWDESK_REDIS_PORT'])（既有实例，只写会话键）"
    $backendCommand = 'mvnw.cmd -B spring-boot:run -Dspring-boot.run.profiles=demo -Dspring-boot.run.arguments=--server.port=' + $Port
    $stdoutLogMasked = Mask-Path $stdoutLog
    $stderrLogMasked = Mask-Path $stderrLog
    $outFileMasked = Mask-Path $outFull

    $ticketRecordEntries = @()
    foreach ($recordRow in $script:recordRows) {
        $recordColumns = @($recordRow -split "`t")
        $ticketRecordEntries += [ordered]@{
            sequenceNo  = [int]$recordColumns[0]
            recordType  = $recordColumns[1]
            actorType   = $recordColumns[2]
            actorUserId = [int]$recordColumns[3]
        }
    }
    $negativeList = @()
    if ($null -ne $terminalClaim) {
        $negativeList = @([ordered]@{
                name     = 'terminal.reClaim.409'
                status   = $terminalClaim.Status
                code     = (Get-Code $terminalClaim)
                traceId  = $terminalClaim.TraceId
                expected = 409
                passed   = ($terminalClaim.Status -eq 409)
            })
    }

    $scriptEntry = [ordered]@{
        path              = 'scripts/stage4-clean-db-demo.ps1'
        lines             = $scriptLineCount
        invocation        = $invocation
        powershellVersion = $PSVersionTable.PSVersion.ToString()
        exitCode          = $script:quitCode
        startedAt         = $startedAtText
        finishedAt        = $finishedAtText
        durationSeconds   = $durationSeconds
    }
    $isolationEntry = [ordered]@{
        tempDatabase                  = $TempDatabase
        tempDatabaseCollation         = 'utf8mb4 / utf8mb4_0900_ai_ci'
        tempDatabaseConnectionString  = $tempUrl
        tempDatabaseUser              = 'root'
        tempDatabaseUserReason        = '容器内应用用户 flowdesk 只有 flowdesk.* 权限（SHOW GRANTS 实测），无法 CREATE DATABASE；给它在临时库上临时授权会在容器里留下授权残留，故本次临时运行统一用 .env 的 root 口令，结束即删库。'
        backendPort                   = $Port
        backendProfile                = 'demo'
        dbContainer                   = $DbContainer
        redisEndpoint                 = $redisEndpointText
        demoDatabase                  = $DemoDatabase
        demoDatabaseWritten           = $false
        existingDemoBackendPort       = $DemoBackendPort
        existingDemoBackendPidsBefore = $pidsBeforeList
    }
    $backendEntry = [ordered]@{
        command                 = $backendCommand
        workingDirectory        = '<REPO>'
        readyAfterSeconds       = $readySeconds
        healthEndpoint          = '/actuator/health'
        healthBody              = $healthBody
        listenerPid             = $appPid
        wrapperPid              = $wrapperPidValue
        stdoutLog               = $stdoutLogMasked
        stderrLog               = $stderrLogMasked
        launchMethod            = 'System.Diagnostics.Process + cmd.exe（本机 Start-Process 因 NO_PROXY/no_proxy 重复键不可用）'
        logHighlights           = $logHighlightList
    }
    $flywayEntry = [ordered]@{
        historyTable      = 'flyway_schema_history'
        records           = $flywayRecordList
        appliedVersions   = $flywayVersionChain
        repeatableScripts = $repeatableList
        allSuccess        = $flywayAllSuccess
    }
    $finalStateEntry = [ordered]@{
        ticketNo        = $script:ticketNo
        snapshot        = $script:snapshotText
        fields          = $snapshot5
        ticketRecords   = $ticketRecordEntries
        recordTypes     = $recordTypeList
        recordSequences = $recordSequenceList
    }
    $totalsEntry = [ordered]@{
        total  = $totalCount
        passed = $passedCount
        failed = $failedCount
    }

    $evidence = [ordered]@{
        _pathsMasked       = '本文件里的机器绝对路径已替换为占位符（<REPO> / <JDK21_HOME> / <TEMP>）；命令、退出码、断言与结论均未改动。'
        stage              = '阶段 4：从空库执行 Flyway + 三角色登录 + 四态主链演示'
        date               = $startedAtDate
        result             = $resultValue
        script             = $scriptEntry
        isolation          = $isolationEntry
        backend            = $backendEntry
        flyway             = $flywayEntry
        seed               = $seedState
        logins             = $loginList
        mainChain          = $stepList
        finalDatabaseState = $finalStateEntry
        negativeAssertions = $negativeList
        assertions         = $assertionList
        totals             = $totalsEntry
        cleanup            = $script:cleanup
        notes              = $notesList
    }
    $json = $evidence | ConvertTo-Json -Depth 12
    $evidenceBuilt = $true
} catch {
    $script:quitCode = 1
    $evidenceBuildError = $_.Exception.Message
    Write-Host ("[FAIL] 证据对象构建失败：{0}" -f $evidenceBuildError) -ForegroundColor Red
}

if (-not $evidenceBuilt) {
    # 兜底：证据必须落盘。这里只用一定存在的变量。
    $fallbackTotals = [ordered]@{ total = $totalCount; passed = $passedCount; failed = $failedCount }
    $fallbackScript = [ordered]@{ path = 'scripts/stage4-clean-db-demo.ps1'; invocation = $invocation; exitCode = $script:quitCode }
    $fallbackEvidence = [ordered]@{
        _pathsMasked = '本文件由回退路径写出：证据对象构建或序列化失败，断言明细仍在，其余字段缺失。'
        stage        = '阶段 4：从空库执行 Flyway + 三角色登录 + 四态主链演示'
        result       = 'FAIL'
        error        = $evidenceBuildError
        script       = $fallbackScript
        totals       = $fallbackTotals
        assertions   = $script:results.ToArray()
        cleanup      = $script:cleanup
        notes        = @($script:notes)
    }
    $json = $fallbackEvidence | ConvertTo-Json -Depth 12
}

[System.IO.File]::WriteAllText($outFull, $json, (New-Object System.Text.UTF8Encoding($false)))

$writtenBytes = [System.IO.File]::ReadAllBytes($outFull)
$hasBom = ($writtenBytes.Length -ge 3 -and $writtenBytes[0] -eq 0xEF -and $writtenBytes[1] -eq 0xBB -and $writtenBytes[2] -eq 0xBF)
$parsedBack = $null
try { $parsedBack = [System.IO.File]::ReadAllText($outFull) | ConvertFrom-Json } catch { $parsedBack = $null }
$selfCheckBom = -not $hasBom
$selfCheckParses = ($null -ne $parsedBack)
$writtenByteCount = $writtenBytes.Length
$outFileMaskedForPrint = Mask-Path $outFull

try {
    Write-Host ''
    Write-Host '── 汇总 ──────────────────────────────────────────────'
    Write-Host ("断言 {0} 项，通过 {1}，失败 {2}" -f $totalCount, $passedCount, $failedCount)
    if ($evidenceBuilt) {
        Write-Host ("Flyway 应用版本：[{0}]；repeatable：[{1}]" -f $flywayVersionChain, ($repeatableList -join ','))
        Write-Host ("种子：用户 {0}、角色 {1}、权限 {2}、分类 {3}（停用 {4}）" -f `
                $seedState.users, $seedState.roles, $seedState.permissions, $seedState.categories, $seedState.disabledCategories)
        Write-Host '五步（状态码 / X-Trace-Id）：'
        foreach ($step in $script:steps) {
            Write-Host ("  {0}. {1,-18} {2} {3} => {4} traceId={5}" -f `
                    $step.step, $step.action, $step.method, $step.path, $step.httpStatus, $step.traceId)
        }
        Write-Host ("终态直查：{0}" -f $script:snapshotText)
        Write-Host ("记录：{0}；序号 {1}" -f ($recordTypeList -join ','), ($recordSequenceList -join ','))
        Write-Host ("清理：{0} 端口监听 PID=[{1}]；临时库存在={2}；演示库计数未变={3}" -f `
                $Port, ($portPidsAfter -join ','), $script:cleanup['tempDatabaseExistsAfter'], `
            (($script:results | Where-Object { $_.name -eq 'cleanup.demoDatabaseCountsUnchanged' }).passed))
    } else {
        Write-Host ("证据对象构建失败：{0}（已写出回退版证据）" -f $evidenceBuildError)
    }
    Write-Host ("证据：{0}（无 BOM UTF-8={1}，可回读解析={2}，{3} 字节）" -f `
            $outFileMaskedForPrint, $selfCheckBom, $selfCheckParses, $writtenByteCount)
    if ($failedCount -gt 0) {
        Write-Host '失败项：' -ForegroundColor Red
        $failed | ForEach-Object { Write-Host ("  - {0} :: {1}" -f $_.name, $_.detail) -ForegroundColor Red }
    }
} catch {
    Write-Host ("[WARN] 汇总输出失败（不影响证据文件）：{0}" -f $_.Exception.Message) -ForegroundColor Yellow
}
Write-Host ("退出码：{0}" -f $script:quitCode)
exit $script:quitCode
