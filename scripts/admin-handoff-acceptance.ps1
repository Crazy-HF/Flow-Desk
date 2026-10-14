# 管理性交接真实栈验收（docs/api-design.md 8.3）
#   ① GET /fd/v1/admin/users/{userId}/active-ticket-assignments（待交接工单，最小元数据）
#   ② GET /fd/v1/admin/tickets/{ticketNo}/handoff-candidates（接替候选人）
#   ③ POST /fd/v1/users/{userId}/actions/disable（停用账号 + 交接方案）
#   ④ PUT  /fd/v1/users/{userId}/roles（替换角色，摘掉 IT_SUPPORT 时 + 交接方案）
#
# 前置
#   · docker compose up -d mysql redis（本脚本只对既有 mysql 容器执行 docker exec，不碰容器生命周期）
#   · 目标后端必须是**已包含管理性交接的最新代码**，由调用方自己启动。脚本只发 HTTP 请求，不启动、
#     不重启、不结束任何后端进程。
#   · 推荐用**独立库**跑：本仓库分支只到 V8，而共享演示库已被后续批次迁到 V9，两者混跑会掩盖版本差异。
#     做法：建一个空库（flowdesk_handoff_acc），把 .env 的 FLOWDESK_DB_NAME / FLOWDESK_DB_URL 指过去，
#     用 local profile 启动（spring.profiles.group.local=demo 会带上演示种子 employee/it/admin，口令 123456）。
#
# 运行
#   pwsh -NoProfile -ExecutionPolicy Bypass -File scripts\admin-handoff-acceptance.ps1
#   基址可覆盖（默认 http://127.0.0.1:8092，即并行后端惯例端口）：
#     $env:FLOWDESK_BASE_URL = 'http://127.0.0.1:8092'   # 或 -BaseUrl http://127.0.0.1:8092
#   证据路径可覆盖：-OutFile <path> 或别名 -EvidencePath <path>
#
# 本文件保存为「带 BOM 的 UTF-8」：Windows PowerShell 5.1 会按系统代码页读取无 BOM 的 UTF-8 脚本，
# 中文注释会被解析成乱码并报语法错误。证据文件相反，必须写成**无 BOM 的 UTF-8**。
#
# 证据
#   docs/acceptance/2026-10-14-admin-handoff.json（无 BOM 的 UTF-8，脚本自己写出）
#   含：_note、invocation、script / target / preflight / tempPrincipals / fixtures / fingerprints{before,after}
#   / keyActions（每条带 X-Trace-Id）/ httpLog（step / method / path / status / traceId / detail）
#   / dbChecks（SQL + 原始行 + 结论）/ assertions（name / expected / actual / why / passed）/ sqlExecuted
#   / cleanup.sql / safetyBoundary / totals。
#
# 本脚本要证明的六件事（每条都有独立断言，失败会打印"期望/实际/为什么重要"）
#   h1 最小元数据与权限边界：管理员只看得到编号/状态/负责人/版本，**读不到工单正文**（详情 404）；
#      没有 TICKET_ADMIN_HANDOFF 的账号（含只有 EMPLOYEE 的员工）两个查询端点都是 403。
#   h2 缺方案 = 409/ADMIN_HANDOFF_REQUIRED：目标用户仍负责活动工单时，停用请求必须带完整方案，
#      而且被拒的请求在库里**什么都没改**（用户状态/版本、工单负责人/版本/记录序号逐项比对）。
#   h3 版本漂移 = 409/ADMIN_HANDOFF_CONFLICT：ticketVersion 与库不一致时返回冲突并带当前快照。
#   h4 停用 + 全部换人 + 参与关系 + 交接记录**同一事务成功**，并且**成功响应前已完成 Redis 会话撤销**：
#      用被停用账号的旧 access token 再请求 /auth/me 必须 401/AUTH_SESSION_INVALID；
#      接替人此时能通过自己的 IT 身份看到这张工单并读到时间线上的 ADMIN_HANDOFF 记录。
#   h5 替换角色路径：摘掉 IT_SUPPORT 且有在办工单 → 缺方案 409、带方案成功后该用户立刻失去 IT 侧的
#      列表权限；**保留 IT_SUPPORT 时同样带着在办工单也无需方案**（反向对照，证明触发条件是"摘角色"）。
#   h6 共同成功或共同失败：多张工单里只要有一张版本已变，**已经换过人的那张也随事务回滚**，
#      账号仍启用、没有交接记录、也没有撤销会话。
#
# 安全边界
#   · 只创建并删除脚本自己创建的 4 个临时用户（ah-acc-<stamp>-*）与它们的 4 张工单（含记录与参与关系），
#     分类复用演示种子里的既有分类，不新建分类
#   · 不触碰运行前就存在的任何行：运行前后各测一次指纹（八项计数 + 工单/记录/参与关系/用户/用户角色
#     五条聚合指纹）并断言完全相同
#   · 只有一条写库 SQL 不是通过接口发出的（清理用的 DELETE），且都按本次 stamp 前缀/自建主键精确命中
#   · 不执行 docker compose down、不删容器、不删卷、不停任何后端进程；数据库访问一律是
#     `docker exec -i <mysql 容器> mysql ...`（容器名与库名来自仓库根 .env）
#   · 演示库 root 口令只从 .env 读进进程环境变量、经 `docker exec -e MYSQL_PWD` 转发，
#     不出现在命令行、不打印、不写进证据；登录口令与 accessToken 同样不打印、不落盘
#   · 断言只记录状态码、业务码、traceId 与布尔结论，不回显令牌与口令；错误正文只截取前 200 字符
#
# 已知残留（与其它验收脚本同一口径，如实登记）：工单编号取号器 ticket_daily_sequence 会因本次创建的
# 4 张工单而前进，脚本**不回滚**它——它是"当天已发出的编号数"，回滚会让后续编号重复。

param(
    [string]$BaseUrl = $env:FLOWDESK_BASE_URL,
    [Alias('EvidencePath')]
    [string]$OutFile = (Join-Path $PSScriptRoot '..\docs\acceptance\2026-10-14-admin-handoff.json'),
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'),
    [string]$DbContainer = 'flowdesk-mysql-1',
    [string]$AdminUser = 'admin',
    [string]$AdminPassword = '123456'
)

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Net.Http

# ── 0. 基址、状态容器、.env 与演示账号 ───────────────────────────────────────
$baseUrl = $BaseUrl
if ([string]::IsNullOrWhiteSpace($baseUrl)) { $baseUrl = 'http://127.0.0.1:8092' }
$baseUrl = $baseUrl.TrimEnd('/')

$originHeader = 'http://127.0.0.1:5173'
$startedAt = Get-Date
$stamp = $startedAt.ToString('yyyyMMdd-HHmmss')
$sqlWorkDir = Join-Path $env:TEMP "flowdesk-admin-handoff-$stamp"
New-Item -ItemType Directory -Path $sqlWorkDir -Force | Out-Null

$script:sqlCounter = 0
$script:results = [System.Collections.Generic.List[object]]::new()
$script:dbChecks = [System.Collections.Generic.List[object]]::new()
$script:keyActions = [System.Collections.Generic.List[object]]::new()
$script:httpLog = [System.Collections.Generic.List[object]]::new()
$script:observed = [System.Collections.Generic.List[string]]::new()
$script:executedSql = [System.Collections.Generic.List[string]]::new()
$script:quitCode = 0
$script:dbAvailable = $false
$script:cleanupInfo = [ordered]@{}
$script:preflightInfo = [ordered]@{}
$script:fixturesInfo = [ordered]@{}
$script:tempPrincipalInfo = [ordered]@{}
$script:handoffFacts = [ordered]@{}
$script:roleFacts = [ordered]@{}
$script:rollbackFacts = [ordered]@{}
$script:fingerprintBefore = $null
$script:fingerprintAfter = $null
$script:tempUserIds = [System.Collections.Generic.List[long]]::new()
$script:tempTicketNos = [System.Collections.Generic.List[string]]::new()

# .env 由本进程自己加载：数据库直查与清理都依赖它，不能要求调用方先执行 load-env.ps1。
if (Test-Path -LiteralPath $EnvFile) {
    foreach ($line in (Get-Content -LiteralPath $EnvFile)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
        $separator = $trimmed.IndexOf('=')
        if ($separator -le 0) { continue }
        [Environment]::SetEnvironmentVariable(
            $trimmed.Substring(0, $separator).Trim(),
            $trimmed.Substring($separator + 1),
            'Process')
    }
}

$dbName = $env:FLOWDESK_DB_NAME
$rootPassword = $env:FLOWDESK_MYSQL_ROOT_PASSWORD
if ([string]::IsNullOrWhiteSpace($dbName) -or [string]::IsNullOrWhiteSpace($rootPassword)) {
    Write-Host '[FAIL] 缺少 FLOWDESK_DB_NAME / FLOWDESK_MYSQL_ROOT_PASSWORD：请在仓库根 .env 中配置' -ForegroundColor Red
    exit 2
}

$tempPrefix = "ah-acc-$stamp"

# ── 1. 工具函数 ─────────────────────────────────────────────────────────────

# MySQL 访问：SQL 走临时文件 + stdin，输出走临时文件 + UTF-8 读取，口令走 MYSQL_PWD 环境变量。
function Invoke-MySql {
    param([string]$Sql)

    $script:sqlCounter = $script:sqlCounter + 1
    $sqlFile = Join-Path $sqlWorkDir ("stmt-{0:d3}.sql" -f $script:sqlCounter)
    $outFile = Join-Path $sqlWorkDir ("stmt-{0:d3}.out" -f $script:sqlCounter)
    $errFile = Join-Path $sqlWorkDir ("stmt-{0:d3}.err" -f $script:sqlCounter)
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($sqlFile, $Sql, $utf8NoBom)
    $script:executedSql.Add($Sql)

    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $rootPassword, 'Process')
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $commandLine = 'docker exec -i -e MYSQL_PWD ' + $DbContainer +
        ' mysql -uroot -N -B --default-character-set=utf8mb4 ' + $dbName +
        ' < "' + $sqlFile + '" > "' + $outFile + '" 2> "' + $errFile + '"'
        $null = cmd.exe /c $commandLine
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    }

    $lines = [System.Collections.Generic.List[string]]::new()
    if (Test-Path -LiteralPath $outFile) {
        foreach ($line in ([System.IO.File]::ReadAllText($outFile, $utf8NoBom) -split "`r?`n")) {
            if ($line.Trim().Length -eq 0) { continue }
            $lines.Add($line)
        }
    }
    $stderrText = ''
    if (Test-Path -LiteralPath $errFile) {
        $stderrText = ([System.IO.File]::ReadAllText($errFile, $utf8NoBom)).Trim()
    }

    return [pscustomobject]@{
        exitCode   = $exitCode
        lines      = $lines.ToArray()
        stderrText = $stderrText
        sql        = $Sql
    }
}

# 单行结果：会话内第一条数据行，按制表符切列。
function Get-MySqlRow {
    param([string]$Sql)
    $result = Invoke-MySql -Sql $Sql
    if ($result.exitCode -ne 0) {
        return [pscustomobject]@{ ok = $false; raw = "QUERY_FAILED(exit=$($result.exitCode)): $($result.stderrText)"; columns = @() }
    }
    if ($result.lines.Count -eq 0) {
        return [pscustomobject]@{ ok = $false; raw = 'NO_DATA_ROW'; columns = @() }
    }
    return [pscustomobject]@{ ok = $true; raw = $result.lines[0]; columns = @($result.lines[0] -split "`t") }
}

function Get-MySqlScalar {
    param([string]$Sql)
    $row = Get-MySqlRow -Sql $Sql
    if (-not $row.ok) { return $null }
    return $row.columns[0]
}

function New-Client {
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.UseCookies = $false
    $client = New-Object System.Net.Http.HttpClient($handler)
    $client.BaseAddress = [Uri]$baseUrl
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

function New-TraceId {
    return [guid]::NewGuid().ToString()
}

# $Step 是断言编号式的短标签（如 h4.disable），进 httpLog 的 step 字段；$Note 说明这条请求在证明什么。
function Send-Req {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Method,
        [string]$Path,
        [string]$Token,
        $Body,
        [string]$Step = '-',
        [string]$Actor = '-',
        [string]$Note = '-'
    )
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::$Method, $Path)
    $requestTraceId = New-TraceId
    try {
        $request.Headers.Add('Origin', $originHeader)
        $request.Headers.Add('X-Trace-Id', $requestTraceId)
        if ($Token) {
            $request.Headers.Authorization =
            New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Token)
        }
        if ($null -ne $Body) {
            $json = $Body
            if ($Body -isnot [string]) { $json = $Body | ConvertTo-Json -Depth 6 -Compress }
            $request.Content = New-Object System.Net.Http.StringContent(
                $json, [System.Text.Encoding]::UTF8, 'application/json')
        }
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $result = [pscustomobject]@{
            Status  = [int]$response.StatusCode
            Body    = $text
            TraceId = (Get-TraceId $response)
        }
        $script:httpLog.Add([pscustomobject]@{
                step           = $Step
                actor          = $Actor
                note           = $Note
                method         = $Method
                path           = $Path
                status         = $result.Status
                traceId        = $result.TraceId
                requestTraceId = $requestTraceId
                detail         = ("actor=" + $Actor + "；" + $Note)
            })
        return $result
    } finally {
        $request.Dispose()
    }
}

# 建单是 multipart/form-data（`ticket` part 携 JSON），不能用 Send-Req 的 application/json 发。
function Send-CreateTicket {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Token,
        [string]$Title,
        [string]$Description,
        [long]$CategoryId,
        [string]$Step = '-',
        [string]$Actor = 'employee',
        [string]$Note = 'create-ticket'
    )
    $payload = @{
        submissionKey = [guid]::NewGuid().ToString()
        title         = $Title
        description   = $Description
        categoryId    = $CategoryId
        priority      = 'MEDIUM'
    } | ConvertTo-Json -Compress
    $form = New-Object System.Net.Http.MultipartFormDataContent
    $form.Add((New-Object System.Net.Http.StringContent(
                $payload, [System.Text.Encoding]::UTF8, 'application/json')), 'ticket')
    $request = New-Object System.Net.Http.HttpRequestMessage(
        [System.Net.Http.HttpMethod]::Post, '/fd/v1/tickets')
    $requestTraceId = New-TraceId
    try {
        $request.Headers.Add('Origin', $originHeader)
        $request.Headers.Add('X-Trace-Id', $requestTraceId)
        $request.Headers.Authorization =
        New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Token)
        $request.Content = $form
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $result = [pscustomobject]@{
            Status  = [int]$response.StatusCode
            Body    = $text
            TraceId = (Get-TraceId $response)
        }
        $script:httpLog.Add([pscustomobject]@{
                step           = $Step
                actor          = $Actor
                note           = $Note
                method         = 'POST'
                path           = '/fd/v1/tickets'
                status         = $result.Status
                traceId        = $result.TraceId
                requestTraceId = $requestTraceId
                detail         = ("actor=" + $Actor + "；" + $Note + "；contentType=multipart/form-data")
            })
        return $result
    } finally {
        $request.Dispose()
    }
}

function Get-Data {
    param($Result)
    if ($null -eq $Result) { return $null }
    try { return ($Result.Body | ConvertFrom-Json).data } catch { return $null }
}

function Get-Code {
    param($Result)
    if ($null -eq $Result) { return $null }
    try { return ($Result.Body | ConvertFrom-Json).code } catch { return $null }
}

# 错误正文只留前 200 字符，且先剥掉令牌/口令类字段——证据里不出现任何凭据。
function Get-SafeBody {
    param($Result, [int]$Max = 200)
    if ($null -eq $Result) { return '(null)' }
    $text = [string]$Result.Body
    $text = [regex]::Replace($text, '(?i)("(?:accessToken|refreshToken|password|token)"\s*:\s*")[^"]*(")', '$1***$2')
    if ($text.Length -gt $Max) { return ($text.Substring(0, $Max) + '…（共 ' + $text.Length + ' 字符）') }
    return $text
}

# 逐条断言：名称、期望、实际、为什么重要、X-Trace-Id、通过与否。
function Add-Assertion {
    param(
        [string]$Name,
        [bool]$Condition,
        [string]$Expected,
        [string]$Actual,
        [string]$Why,
        $Result
    )
    $httpStatus = $null
    $traceId = $null
    if ($null -ne $Result) {
        $httpStatus = $Result.Status
        $traceId = $Result.TraceId
    }
    $script:results.Add([pscustomobject]@{
            name       = $Name
            expected   = $Expected
            actual     = $Actual
            why        = $Why
            httpStatus = $httpStatus
            traceId    = $traceId
            passed     = $Condition
        })
    if ($Condition) {
        Write-Host ("[PASS] {0} :: {1}" -f $Name, $Actual)
    } else {
        Write-Host ("[FAIL] {0}" -f $Name) -ForegroundColor Red
        Write-Host ("       期望：{0}" -f $Expected) -ForegroundColor Red
        Write-Host ("       实际：{0}" -f $Actual) -ForegroundColor Red
        Write-Host ("       为什么重要：{0}" -f $Why) -ForegroundColor Red
    }
}

# 数据库直查断言：SQL、原始结果行、期望与结论一起进证据。
function Assert-Db {
    param(
        [string]$Name,
        [string]$Sql,
        [string]$Expected,
        [string]$Why,
        [scriptblock]$Check
    )
    $row = Get-MySqlRow -Sql $Sql
    $passed = $false
    $detail = "row=[$($row.raw)]"
    if ($row.ok) {
        try {
            $passed = [bool](& $Check $row.columns)
        } catch {
            $passed = $false
            $detail = "$detail 判定异常：$($_.Exception.Message)"
        }
    }
    $script:dbChecks.Add([pscustomobject]@{
            name     = $Name
            sql      = $Sql
            raw      = $row.raw
            expected = $Expected
            passed   = $passed
        })
    Add-Assertion -Name $Name -Condition $passed -Expected $Expected -Actual $detail -Why $Why
}

function Add-KeyAction {
    param(
        [string]$Name,
        [string]$Detail,
        $Result
    )
    $entry = [ordered]@{ name = $Name; detail = $Detail }
    if ($null -ne $Result) {
        $entry['httpStatus'] = $Result.Status
        $entry['traceId'] = $Result.TraceId
        $entry['code'] = Get-Code $Result
    }
    $script:keyActions.Add([pscustomobject]$entry)
}

# 单张工单的"库里没变"签名：状态、负责人、版本、记录序号、记录条数、参与关系条数。
function Get-TicketSignature {
    param([string]$TicketNo)
    $sql = "SET SESSION group_concat_max_len=1000000; SELECT CONCAT_WS('|', t.status, IFNULL(t.assignee_id,-1), " +
    "t.version, t.record_seq, (SELECT COUNT(*) FROM ticket_record r WHERE r.ticket_id = t.id), " +
    "(SELECT COUNT(*) FROM ticket_participant p WHERE p.ticket_id = t.id)) " +
    "FROM ticket t WHERE t.ticket_no = '$TicketNo';"
    $row = Get-MySqlRow -Sql $sql
    if (-not $row.ok) { return "UNREADABLE[$($row.raw)]" }
    return $row.raw
}

# 指纹：八项计数 + 五条聚合指纹（工单 / 记录 / 参与关系 / 用户 / 用户角色）。
# 运行前与清理后各测一次，字符串完全相同即证明"运行前就存在的行一行都没变"。
function Get-DemoFingerprint {
    $sql = "SET SESSION group_concat_max_len=1000000; SELECT CONCAT_WS(' || ', " +
    "(SELECT COUNT(*) FROM ticket), (SELECT COUNT(*) FROM ticket_record), " +
    "(SELECT COUNT(*) FROM ticket_participant), (SELECT COUNT(*) FROM iam_user), " +
    "(SELECT COUNT(*) FROM iam_user_role), (SELECT COUNT(*) FROM iam_role), " +
    "(SELECT COUNT(*) FROM iam_permission), (SELECT COUNT(*) FROM iam_role_permission), " +
    "IFNULL((SELECT MD5(GROUP_CONCAT(CONCAT_WS(':', id, ticket_no, status, IFNULL(assignee_id,-1), version, record_seq) ORDER BY id SEPARATOR '|')) FROM ticket),'-'), " +
    "IFNULL((SELECT MD5(GROUP_CONCAT(CONCAT_WS(':', id, ticket_id, sequence_no, record_type, IFNULL(actor_user_id,-1)) ORDER BY id SEPARATOR '|')) FROM ticket_record),'-'), " +
    "IFNULL((SELECT MD5(GROUP_CONCAT(CONCAT_WS(':', ticket_id, user_id) ORDER BY ticket_id, user_id SEPARATOR '|')) FROM ticket_participant),'-'), " +
    "IFNULL((SELECT MD5(GROUP_CONCAT(CONCAT_WS(':', id, username, status, version) ORDER BY id SEPARATOR '|')) FROM iam_user),'-'), " +
    "IFNULL((SELECT MD5(GROUP_CONCAT(CONCAT_WS(':', user_id, role_id) ORDER BY user_id, role_id SEPARATOR '|')) FROM iam_user_role),'-')) AS fingerprint;"
    $row = Get-MySqlRow -Sql $sql
    if (-not $row.ok) { return "UNREADABLE[$($row.raw)]" }
    return $row.raw
}

# 登录：成功返回 accessToken（不打印、不进证据），失败返回 $null 并记账。
function Invoke-Login {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Username,
        [string]$Password,
        [string]$Step,
        [string]$Note
    )
    $result = Send-Req -Client $Client -Method 'POST' -Path '/fd/v1/auth/login' -Token $null `
        -Body @{ username = $Username; password = $Password } -Step $Step -Actor $Username -Note $Note
    if ($result.Status -ne 200) { return $null }
    $data = Get-Data $result
    if ($null -eq $data -or [string]::IsNullOrWhiteSpace($data.accessToken)) { return $null }
    return $data.accessToken
}

function Get-UserByUsername {
    param([string]$Username)
    $row = Get-MySqlRow -Sql "SELECT id, status, version FROM iam_user WHERE username = '$Username';"
    if (-not $row.ok) { return $null }
    return [pscustomobject]@{
        id      = [long]$row.columns[0]
        status  = $row.columns[1]
        version = [long]$row.columns[2]
    }
}

function New-HandoffInstruction {
    param([string]$TicketNo, [long]$TicketVersion, [long]$NewAssigneeId)
    return [ordered]@{ ticketNo = $TicketNo; ticketVersion = $TicketVersion; newAssigneeId = $NewAssigneeId }
}

# ── 2. 前置：健康检查、管理员登录、临时主体与夹具 ────────────────────────────
Write-Host ''
Write-Host '=== 管理性交接真实栈验收（docs/api-design.md 8.3） ===' -ForegroundColor Cyan
Write-Host ("基址：{0}；库：{1}；容器：{2}" -f $baseUrl, $dbName, $DbContainer)
Write-Host ''

$adminClient = New-Client
$probeResult = Send-Req -Client $adminClient -Method 'GET' -Path '/actuator/health' -Token $null `
    -Step 'p1.health' -Actor '-' -Note '前置：后端健康检查'
$healthUp = $false
$healthJson = $null
try { $healthJson = $probeResult.Body | ConvertFrom-Json } catch { $healthJson = $null }
if ($null -ne $healthJson) { $healthUp = ($healthJson.status -eq 'UP') }
Add-Assertion -Name 'p1.health' -Condition ($probeResult.Status -eq 200 -and $healthUp) `
    -Expected '200 且 status=UP' -Actual ("status={0} body={1}" -f $probeResult.Status, (Get-SafeBody $probeResult 60)) `
    -Why '后端没起来/连不上时，后面所有断言都会退化成假失败' -Result $probeResult

if (-not $healthUp) {
    Write-Host '[FAIL] 后端不可用：先启动目标后端再跑本脚本' -ForegroundColor Red
    exit 2
}

$adminToken = Invoke-Login -Client $adminClient -Username $AdminUser -Password $AdminPassword `
    -Step 'p2.login.admin' -Note '前置：管理员（SYSTEM_ADMIN，含 TICKET_ADMIN_HANDOFF + USER_MANAGE）登录'
Add-Assertion -Name 'p2.login.admin' -Condition ($null -ne $adminToken) -Expected '200 且返回 accessToken' `
    -Actual ("token=" + $(if ($adminToken) { 'OK' } else { 'MISSING' })) `
    -Why '管理性交接的四个端点都需要管理员身份，登录失败后面无法继续'

if ($null -eq $adminToken) { exit 2 }

$employeeToken = Invoke-Login -Client (New-Client) -Username 'employee' -Password '123456' `
    -Step 'p3.login.employee' -Note '前置：演示员工登录（只有 EMPLOYEE 角色，用于权限边界对照）'
Add-Assertion -Name 'p3.login.employee' -Condition ($null -ne $employeeToken) -Expected '200 且返回 accessToken' `
    -Actual ("token=" + $(if ($employeeToken) { 'OK' } else { 'MISSING' })) `
    -Why 'h1 要证明"没有 TICKET_ADMIN_HANDOFF 的账号拿不到交接端点"，需要这个身份'

$script:dbAvailable = ((Get-MySqlRow -Sql 'SELECT 1;').ok)
Add-Assertion -Name 'p4.db.reachable' -Condition $script:dbAvailable -Expected '能连上库并返回一行' `
    -Actual ("dbAvailable=" + $script:dbAvailable) `
    -Why '库层断言（负责人、版本、记录、参与关系、会话撤销后的状态）是本脚本的主要证据'

# 指纹必须在**创建任何夹具之前**取：它是"运行前就存在的行"的基准。
$script:fingerprintBefore = Get-DemoFingerprint

$employeeRoleId = [long](Get-MySqlScalar -Sql "SELECT id FROM iam_role WHERE code = 'EMPLOYEE';")
$itSupportRoleId = [long](Get-MySqlScalar -Sql "SELECT id FROM iam_role WHERE code = 'IT_SUPPORT';")
$systemAdminRoleId = [long](Get-MySqlScalar -Sql "SELECT id FROM iam_role WHERE code = 'SYSTEM_ADMIN';")
$categoryId = [long](Get-MySqlScalar -Sql 'SELECT id FROM ticket_category ORDER BY sort_order, id LIMIT 1;')
$adminId = [long](Get-MySqlScalar -Sql "SELECT id FROM iam_user WHERE username = '$AdminUser';")

$script:preflightInfo = [ordered]@{
    baseUrl                  = $baseUrl
    dbName                   = $dbName
    dbContainer              = $DbContainer
    healthStatus             = $(if ($null -ne $healthJson) { $healthJson.status } else { 'UNKNOWN' })
    adminUserId              = $adminId
    employeeRoleId           = $employeeRoleId
    itSupportRoleId          = $itSupportRoleId
    systemAdminRoleId        = $systemAdminRoleId
    reusedCategoryId         = $categoryId
    fingerprintBefore        = $null
    demoRowsAreUntouchedGoal = '运行前存在的行在清理后逐项一致'
}

# 临时主体：用户名带本次 stamp，清理按用户名前缀 + 自建主键精确命中，不可能碰到既有行。
function New-TempUser {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Username,
        [string]$DisplayName,
        [long]$RoleId,
        [string]$Step
    )
    $result = Send-Req -Client $Client -Method 'POST' -Path '/fd/v1/users' -Token $adminToken `
        -Body @{
            username        = $Username
            displayName     = $DisplayName
            initialPassword = 'Handoff#2026'
            roleIds         = @($RoleId)
        } -Step $Step -Actor $AdminUser -Note ("创建临时用户 " + $Username)
    if ($result.Status -ne 201) {
        Add-Assertion -Name $Step -Condition $false -Expected '201' -Actual (Get-SafeBody $result) `
            -Why '临时主体创建失败，后续交接断言无法进行' -Result $result
        return $null
    }
    $data = Get-Data $result
    $script:tempUserIds.Add([long]$data.id)
    Add-Assertion -Name $Step -Condition ($data.status -eq 'ENABLED' -and [long]$data.version -eq 0) `
        -Expected '201 且 ENABLED / version=0' -Actual ("id={0} status={1} version={2}" -f $data.id, $data.status, $data.version) `
        -Why '交接的目标用户与新负责人都要是启用状态，版本从 0 开始才能用乐观锁' -Result $result
    return $data
}

Write-Host ''
Write-Host '--- 夹具：4 个临时主体 + 4 张工单 ---' -ForegroundColor Cyan

$empUser = New-TempUser -Client $adminClient -Username "$tempPrefix-emp" -DisplayName '交接验收员工' `
    -RoleId $employeeRoleId -Step 'p5.create.employee'
$itA = New-TempUser -Client $adminClient -Username "$tempPrefix-ita" -DisplayName '交接验收 IT-A' `
    -RoleId $itSupportRoleId -Step 'p6.create.itA'
$itB = New-TempUser -Client $adminClient -Username "$tempPrefix-itb" -DisplayName '交接验收 IT-B' `
    -RoleId $itSupportRoleId -Step 'p7.create.itB'
$itC = New-TempUser -Client $adminClient -Username "$tempPrefix-itc" -DisplayName '交接验收 IT-C' `
    -RoleId $itSupportRoleId -Step 'p8.create.itC'
$itD = New-TempUser -Client $adminClient -Username "$tempPrefix-itd" -DisplayName '交接验收 IT-D' `
    -RoleId $itSupportRoleId -Step 'p9.create.itD'

if ($null -eq $empUser -or $null -eq $itA -or $null -eq $itB -or $null -eq $itC -or $null -eq $itD) {
    Write-Host '[FAIL] 临时主体不齐，脚本终止（清理见 evidence 的 cleanup.sql）' -ForegroundColor Red
    exit 3
}

$empClient = New-Client
$empToken = Invoke-Login -Client $empClient -Username "$tempPrefix-emp" -Password 'Handoff#2026' `
    -Step 'p10.login.emp' -Note '前置：临时员工登录（提交工单的人）'

$itAClient = New-Client
$itAToken = Invoke-Login -Client $itAClient -Username "$tempPrefix-ita" -Password 'Handoff#2026' `
    -Step 'p11.login.itA' -Note '前置：IT-A 登录（交接的原负责人）'

$itBClient = New-Client
$itBToken = Invoke-Login -Client $itBClient -Username "$tempPrefix-itb" -Password 'Handoff#2026' `
    -Step 'p12.login.itB' -Note '前置：IT-B 登录（接替人）'

$itCClient = New-Client
$itCToken = Invoke-Login -Client $itCClient -Username "$tempPrefix-itc" -Password 'Handoff#2026' `
    -Step 'p13.login.itC' -Note '前置：IT-C 登录（角色替换路径的目标用户）'

$itDClient = New-Client
$itDToken = Invoke-Login -Client $itDClient -Username "$tempPrefix-itd" -Password 'Handoff#2026' `
    -Step 'p14.login.itD' -Note '前置：IT-D 登录（回滚路径的目标用户）'

Add-Assertion -Name 'p15.tempLogins' `
    -Condition ($null -ne $empToken -and $null -ne $itAToken -and $null -ne $itBToken -and $null -ne $itCToken -and $null -ne $itDToken) `
    -Expected '五个临时身份都能登录' -Actual ("emp={0} itA={1} itB={2} itC={3} itD={4}" -f `
        $(if ($empToken) { 'OK' } else { 'FAIL' }), $(if ($itAToken) { 'OK' } else { 'FAIL' }), `
        $(if ($itBToken) { 'OK' } else { 'FAIL' }), $(if ($itCToken) { 'OK' } else { 'FAIL' }), `
        $(if ($itDToken) { 'OK' } else { 'FAIL' })) `
    -Why '临时身份用于"原负责人/接替人"的真实 HTTP 侧观察'

$script:tempPrincipalInfo = [ordered]@{
    usernamePrefix = $tempPrefix
    employeeUserId = [long]$empUser.id
    itAUserId      = [long]$itA.id
    itBUserId      = [long]$itB.id
    itCUserId      = [long]$itC.id
    itDUserId      = [long]$itD.id
}

# 建单 + 领取，返回工单编号与领取后的版本。
function New-ClaimedTicket {
    param(
        [string]$Title,
        [string]$StepPrefix,
        [System.Net.Http.HttpClient]$ItClient,
        [string]$ItToken,
        [string]$ItName
    )
    $created = Send-CreateTicket -Client $empClient -Token $empToken -Title $Title `
        -Description '管理性交接验收夹具' -CategoryId $categoryId `
        -Step "$StepPrefix.create" -Actor "$tempPrefix-emp" -Note "临时员工提交工单：$Title"
    if ($created.Status -ne 201) {
        Add-Assertion -Name "$StepPrefix.create" -Condition $false -Expected '201' `
            -Actual (Get-SafeBody $created) -Why '没有工单就无法验证交接' -Result $created
        return $null
    }
    $ticketNo = (Get-Data $created).ticketNo
    $script:tempTicketNos.Add($ticketNo)

    $claimed = Send-Req -Client $ItClient -Method 'POST' -Path "/fd/v1/tickets/$ticketNo/actions/claim" `
        -Token $ItToken -Body @{ version = 0 } -Step "$StepPrefix.claim" -Actor $ItName `
        -Note "由 $ItName 领取工单"
    if ($claimed.Status -ne 200) {
        Add-Assertion -Name "$StepPrefix.claim" -Condition $false -Expected '200' `
            -Actual (Get-SafeBody $claimed) -Why '只有"处理中"的工单才有当前负责人，交接才有对象' -Result $claimed
        return $null
    }
    $data = Get-Data $claimed
    Add-Assertion -Name "$StepPrefix.claim" `
        -Condition ($data.status -eq 'PROCESSING' -and [long]$data.version -eq 1) `
        -Expected '200 且 PROCESSING / version=1' `
        -Actual ("ticketNo={0} status={1} version={2}" -f $data.ticketNo, $data.status, $data.version) `
        -Why '领取后这张工单成为该 IT 用户的"活动工单"' -Result $claimed

    return [pscustomobject]@{ ticketNo = $ticketNo; version = [long]$data.version }
}

$t1 = New-ClaimedTicket -Title '交接验收：停用账号路径' -StepPrefix 'f1' -ItClient $itAClient -ItToken $itAToken -ItName 'IT-A'
$t2 = New-ClaimedTicket -Title '交接验收：角色替换路径' -StepPrefix 'f2' -ItClient $itCClient -ItToken $itCToken -ItName 'IT-C'
$t3 = New-ClaimedTicket -Title '交接验收：回滚路径（一）' -StepPrefix 'f3' -ItClient $itDClient -ItToken $itDToken -ItName 'IT-D'
$t4 = New-ClaimedTicket -Title '交接验收：回滚路径（二）' -StepPrefix 'f4' -ItClient $itDClient -ItToken $itDToken -ItName 'IT-D'

if ($null -eq $t1 -or $null -eq $t2 -or $null -eq $t3 -or $null -eq $t4) {
    Write-Host '[FAIL] 夹具工单不齐，脚本终止（清理见 evidence 的 cleanup.sql）' -ForegroundColor Red
    exit 3
}

$script:fixturesInfo = [ordered]@{
    stopPathTicket     = @{ ticketNo = $t1.ticketNo; initialVersion = $t1.version; assigneeUserId = [long]$itA.id }
    rolePathTicket     = @{ ticketNo = $t2.ticketNo; initialVersion = $t2.version; assigneeUserId = [long]$itC.id }
    rollbackTicketA    = @{ ticketNo = $t3.ticketNo; initialVersion = $t3.version; assigneeUserId = [long]$itD.id }
    rollbackTicketB    = @{ ticketNo = $t4.ticketNo; initialVersion = $t4.version; assigneeUserId = [long]$itD.id }
}

$script:preflightInfo['fingerprintBefore'] = $script:fingerprintBefore

# ── 3. h1 最小元数据与权限边界 ───────────────────────────────────────────────
Write-Host ''
Write-Host '--- h1 最小元数据与权限边界 ---' -ForegroundColor Cyan

$assignments = Send-Req -Client $adminClient -Method 'GET' `
    -Path "/fd/v1/admin/users/$($itA.id)/active-ticket-assignments" -Token $adminToken `
    -Step 'h1.assignments' -Actor $AdminUser -Note '管理员读取待交接工单（最小元数据）'
$assignmentsData = Get-Data $assignments
$assignmentItem = $null
if ($null -ne $assignmentsData -and @($assignmentsData).Count -ge 1) { $assignmentItem = @($assignmentsData)[0] }
$assignmentFields = @()
if ($null -ne $assignmentItem) { $assignmentFields = @($assignmentItem.PSObject.Properties.Name) }
Add-Assertion -Name 'h1.assignments.minimalFields' `
    -Condition ($assignments.Status -eq 200 -and @($assignmentsData).Count -eq 1 `
        -and ($assignmentFields -notcontains 'title') -and ($assignmentFields -notcontains 'description') `
        -and ($assignmentFields -notcontains 'records') -and ($assignmentFields -notcontains 'attachments')) `
    -Expected '200，恰好 1 张，且不含 title/description/records/attachments' `
    -Actual ("status={0} count={1} fields={2}" -f $assignments.Status, @($assignmentsData).Count, ($assignmentFields -join ',')) `
    -Why '管理员只应看到交接所需的最小元数据，正文与记录必须留在工单权限之后' -Result $assignments

Add-Assertion -Name 'h1.assignments.values' `
    -Condition ($null -ne $assignmentItem -and $assignmentItem.ticketNo -eq $t1.ticketNo `
        -and $assignmentItem.status -eq 'PROCESSING' -and [long]$assignmentItem.assigneeId -eq [long]$itA.id `
        -and [long]$assignmentItem.version -eq $t1.version) `
    -Expected ("ticketNo={0} status=PROCESSING assigneeId={1} version={2}" -f $t1.ticketNo, $itA.id, $t1.version) `
    -Actual ("ticketNo={0} status={1} assigneeId={2} version={3}" -f $assignmentItem.ticketNo, $assignmentItem.status, $assignmentItem.assigneeId, $assignmentItem.version) `
    -Why '客户端就是拿这份编号+版本去组 handoffs 的，错了后面必然 409' -Result $assignments

$adminDetail = Send-Req -Client $adminClient -Method 'GET' -Path "/fd/v1/tickets/$($t1.ticketNo)" `
    -Token $adminToken -Step 'h1.adminDetail' -Actor $AdminUser `
    -Note '同一个管理员读工单详情（应被最小可见性挡住）'
Add-Assertion -Name 'h1.adminCannotReadTicketBody' `
    -Condition ($adminDetail.Status -eq 404 -and (Get-Code $adminDetail) -eq 'TICKET_NOT_FOUND') `
    -Expected '404 / TICKET_NOT_FOUND' `
    -Actual ("status={0} code={1}" -f $adminDetail.Status, (Get-Code $adminDetail)) `
    -Why '交接权限不等于工单查看权：管理员拿不到标题/描述/记录/附件' -Result $adminDetail

$empAssignments = Send-Req -Client $empClient -Method 'GET' `
    -Path "/fd/v1/admin/users/$($itA.id)/active-ticket-assignments" -Token $employeeToken `
    -Step 'h1.employeeAssignments' -Actor 'employee' -Note '演示员工访问交接查询端点（应 403）'
Add-Assertion -Name 'h1.employeeForbiddenOnAssignments' `
    -Condition ($empAssignments.Status -eq 403 -and (Get-Code $empAssignments) -eq 'ACCESS_DENIED') `
    -Expected '403 / ACCESS_DENIED' -Actual ("status={0} code={1}" -f $empAssignments.Status, (Get-Code $empAssignments)) `
    -Why '端点按 TICKET_ADMIN_HANDOFF 把关，普通工单权限不能替代' -Result $empAssignments

$candidates = Send-Req -Client $adminClient -Method 'GET' `
    -Path "/fd/v1/admin/tickets/$($t1.ticketNo)/handoff-candidates" -Token $adminToken `
    -Step 'h1.candidates' -Actor $AdminUser -Note '管理员读取接替候选人'
$candidateIds = @()
if ($null -ne (Get-Data $candidates)) { $candidateIds = @((Get-Data $candidates) | ForEach-Object { [long]$_.id }) }
Add-Assertion -Name 'h1.candidates.excludeRequesterAndAssignee' `
    -Condition ($candidates.Status -eq 200 -and ($candidateIds -contains [long]$itB.id) `
        -and ($candidateIds -notcontains [long]$itA.id) -and ($candidateIds -notcontains [long]$empUser.id)) `
    -Expected ("含 IT-B({0})；不含原负责人 IT-A({1}) 与提交人({2})" -f $itB.id, $itA.id, $empUser.id) `
    -Actual ("candidateIds=" + ($candidateIds -join ',')) `
    -Why '候选人必须排除提交人与原负责人，否则交接会造出"自己处理自己的单"' -Result $candidates

$empCandidates = Send-Req -Client $empClient -Method 'GET' `
    -Path "/fd/v1/admin/tickets/$($t1.ticketNo)/handoff-candidates" -Token $employeeToken `
    -Step 'h1.employeeCandidates' -Actor 'employee' -Note '演示员工访问候选人端点（应 403）'
Add-Assertion -Name 'h1.employeeForbiddenOnCandidates' `
    -Condition ($empCandidates.Status -eq 403 -and (Get-Code $empCandidates) -eq 'ACCESS_DENIED') `
    -Expected '403 / ACCESS_DENIED' -Actual ("status={0} code={1}" -f $empCandidates.Status, (Get-Code $empCandidates)) `
    -Why '两个读取端点共用同一条权限口径' -Result $empCandidates

# ── 4. h2 缺方案：409/ADMIN_HANDOFF_REQUIRED ────────────────────────────────
Write-Host ''
Write-Host '--- h2 缺方案 = 409/ADMIN_HANDOFF_REQUIRED ---' -ForegroundColor Cyan

$itABefore = Get-UserByUsername -Username "$tempPrefix-ita"
$t1SignatureBefore = Get-TicketSignature -TicketNo $t1.ticketNo

$noPlan = Send-Req -Client $adminClient -Method 'POST' -Path "/fd/v1/users/$($itA.id)/actions/disable" `
    -Token $adminToken -Body @{ version = $itABefore.version } -Step 'h2.disable.noPlan' -Actor $AdminUser `
    -Note '停用仍负责活动工单的账号，但不带交接方案'
Add-Assertion -Name 'h2.missingPlanIsRequired' `
    -Condition ($noPlan.Status -eq 409 -and (Get-Code $noPlan) -eq 'ADMIN_HANDOFF_REQUIRED') `
    -Expected '409 / ADMIN_HANDOFF_REQUIRED' `
    -Actual ("status={0} code={1}" -f $noPlan.Status, (Get-Code $noPlan)) `
    -Why '账号停用后他不能再处理工单；没有方案就停用会把工单悬在那儿' -Result $noPlan

$itAAfterReject = Get-UserByUsername -Username "$tempPrefix-ita"
Add-Assertion -Name 'h2.rejectedRequestChangesNothingUser' `
    -Condition ($itAAfterReject.status -eq 'ENABLED' -and $itAAfterReject.version -eq $itABefore.version) `
    -Expected ("ENABLED / version={0}" -f $itABefore.version) `
    -Actual ("{0} / version={1}" -f $itAAfterReject.status, $itAAfterReject.version) `
    -Why '被拒的请求不能留下"半停用"状态'

Add-Assertion -Name 'h2.rejectedRequestChangesNothingTicket' `
    -Condition ((Get-TicketSignature -TicketNo $t1.ticketNo) -eq $t1SignatureBefore) `
    -Expected '工单签名（状态/负责人/版本/记录序号/记录数/参与关系数）完全不变' `
    -Actual (Get-TicketSignature -TicketNo $t1.ticketNo) `
    -Why '校验失败必须发生在任何写入之前，工单一行都不许动'

# ── 5. h3 版本漂移：409/ADMIN_HANDOFF_CONFLICT ──────────────────────────────
Write-Host ''
Write-Host '--- h3 版本漂移 = 409/ADMIN_HANDOFF_CONFLICT ---' -ForegroundColor Cyan

$stalePlan = Send-Req -Client $adminClient -Method 'POST' -Path "/fd/v1/users/$($itA.id)/actions/disable" `
    -Token $adminToken -Body @{
        version       = $itABefore.version
        handoffReason = '账号离职，工单转由他人续办'
        handoffs      = @((New-HandoffInstruction -TicketNo $t1.ticketNo -TicketVersion ($t1.version + 1) -NewAssigneeId ([long]$itB.id)))
    } -Step 'h3.disable.staleTicketVersion' -Actor $AdminUser `
    -Note '带方案的停用，但 handoffs 里的 ticketVersion 比库里大 1'
Add-Assertion -Name 'h3.staleTicketVersionIsConflict' `
    -Condition ($stalePlan.Status -eq 409 -and (Get-Code $stalePlan) -eq 'ADMIN_HANDOFF_CONFLICT') `
    -Expected '409 / ADMIN_HANDOFF_CONFLICT' `
    -Actual ("status={0} code={1}" -f $stalePlan.Status, (Get-Code $stalePlan)) `
    -Why '方案里的工单版本是乐观锁：客户端读到之后工单被推进过就必须重新组织方案' -Result $stalePlan

$staleData = Get-Data $stalePlan
Add-Assertion -Name 'h3.conflictCarriesTicketSnapshot' `
    -Condition ($null -ne $staleData -and [long]$staleData.version -eq $t1.version -and $staleData.status -eq 'PROCESSING') `
    -Expected ("data.version={0} data.status=PROCESSING" -f $t1.version) `
    -Actual ("data.version={0} data.status={1}" -f $staleData.version, $staleData.status) `
    -Why '冲突响应要带当前快照，前端才能刷新后重试' -Result $stalePlan

Add-Assertion -Name 'h3.staleRejectedChangesNothing' `
    -Condition ((Get-TicketSignature -TicketNo $t1.ticketNo) -eq $t1SignatureBefore `
        -and (Get-UserByUsername -Username "$tempPrefix-ita").status -eq 'ENABLED') `
    -Expected '工单签名不变且账号仍 ENABLED' `
    -Actual ((Get-TicketSignature -TicketNo $t1.ticketNo) + ' / ' + (Get-UserByUsername -Username "$tempPrefix-ita").status) `
    -Why '版本不符要在写库前被挡下'

# ── 6. h4 成功交接：停用 + 换人 + 记录 + 会话撤销 ───────────────────────────
Write-Host ''
Write-Host '--- h4 成功交接（停用账号路径） ---' -ForegroundColor Cyan

$handoffReason = "账号离职，工单转由 IT-B 续办（$stamp）"
$disableOk = Send-Req -Client $adminClient -Method 'POST' -Path "/fd/v1/users/$($itA.id)/actions/disable" `
    -Token $adminToken -Body @{
        version       = $itABefore.version
        handoffReason = $handoffReason
        handoffs      = @((New-HandoffInstruction -TicketNo $t1.ticketNo -TicketVersion $t1.version -NewAssigneeId ([long]$itB.id)))
    } -Step 'h4.disable.withPlan' -Actor $AdminUser `
    -Note '停用账号 + 完整交接方案（同事务：账号变更 + 换人 + 参与关系 + 交接记录）'
$disableData = Get-Data $disableOk
Add-KeyAction -Name '停用账号并完成管理性交接' -Detail ("ticketNo={0} itA={1} -> itB={2}" -f $t1.ticketNo, $itA.id, $itB.id) -Result $disableOk
Add-Assertion -Name 'h4.disableSucceeds' `
    -Condition ($disableOk.Status -eq 200 -and $disableData.status -eq 'DISABLED' `
        -and [long]$disableData.version -eq ($itABefore.version + 1)) `
    -Expected ("200 且 DISABLED / version={0}" -f ($itABefore.version + 1)) `
    -Actual ("status={0} userStatus={1} version={2}" -f $disableOk.Status, $disableData.status, $disableData.version) `
    -Why '停用与交接必须一起成功' -Result $disableOk

$itAAfter = Get-UserByUsername -Username "$tempPrefix-ita"
Assert-Db -Name 'h4.db.userDisabled' `
    -Sql "SELECT status, version FROM iam_user WHERE id = $($itA.id);" `
    -Expected ("DISABLED / version={0}" -f ($itABefore.version + 1)) `
    -Why '库里的账号状态才是最终事实' `
    -Check { param($c) $c[0] -eq 'DISABLED' -and [long]$c[1] -eq ($itABefore.version + 1) }

Assert-Db -Name 'h4.db.ticketReassigned' `
    -Sql ("SELECT status, assignee_id, version, record_seq FROM ticket WHERE ticket_no = '{0}';" -f $t1.ticketNo) `
    -Expected ("PROCESSING / assignee={0} / version={1} / record_seq={2}（领取时已写下 CREATE+CLAIM 两条）" -f $itB.id, ($t1.version + 1), 3) `
    -Why '交接只换负责人：状态不变，版本与记录序号各推进一次' `
    -Check { param($c) $c[0] -eq 'PROCESSING' -and [long]$c[1] -eq [long]$itB.id -and [long]$c[2] -eq ($t1.version + 1) -and [long]$c[3] -eq 3 }

Assert-Db -Name 'h4.db.handoffRecord' `
    -Sql ("SELECT record_type, actor_type, actor_user_id, from_assignee_id, to_assignee_id, from_status, to_status, reason, sequence_no " +
        "FROM ticket_record WHERE ticket_id = (SELECT id FROM ticket WHERE ticket_no = '{0}') ORDER BY sequence_no DESC LIMIT 1;" -f $t1.ticketNo) `
    -Expected ("ADMIN_HANDOFF / USER / actor={0} / from={1} / to={2} / PROCESSING>PROCESSING / reason=handoffReason / sequence_no=3" -f $adminId, $itA.id, $itB.id) `
    -Why '没有专门的交接记录表：交接记录就是 ticket_record 里一条 ADMIN_HANDOFF，reason 承载 handoffReason' `
    -Check {
        param($c)
        $c[0] -eq 'ADMIN_HANDOFF' -and $c[1] -eq 'USER' -and [long]$c[2] -eq $adminId -and
        [long]$c[3] -eq [long]$itA.id -and [long]$c[4] -eq [long]$itB.id -and
        $c[5] -eq 'PROCESSING' -and $c[6] -eq 'PROCESSING' -and $c[7] -eq $handoffReason -and [long]$c[8] -eq 3
    }

Assert-Db -Name 'h4.db.participantAdded' `
    -Sql (("SELECT (SELECT COUNT(*) FROM ticket_participant WHERE ticket_id = (SELECT id FROM ticket WHERE ticket_no = '{0}') AND user_id = {1}), " +
        "(SELECT COUNT(*) FROM ticket_participant WHERE ticket_id = (SELECT id FROM ticket WHERE ticket_no = '{0}') AND user_id = {2});") -f $t1.ticketNo, $itB.id, $itA.id) `
    -Expected '接替人 1 条参与关系；原负责人历史参与关系保留 1 条' `
    -Why '接替人要能立刻看到这张工单；原负责人的历史参与关系不能删' `
    -Check { param($c) [long]$c[0] -eq 1 -and [long]$c[1] -eq 1 }

# 成功响应前必须完成 Redis 会话撤销：被停用账号的旧 access token 立刻失效。
$itAMeAfter = Send-Req -Client $itAClient -Method 'GET' -Path '/fd/v1/auth/me' -Token $itAToken `
    -Step 'h4.itA.sessionRevoked' -Actor "$tempPrefix-ita" -Note '被停用账号用旧 access token 再请求 /auth/me'
Add-Assertion -Name 'h4.sessionRevokedBeforeResponse' `
    -Condition ($itAMeAfter.Status -eq 401 -and (Get-Code $itAMeAfter) -eq 'AUTH_SESSION_INVALID') `
    -Expected '401 / AUTH_SESSION_INVALID' `
    -Actual ("status={0} code={1}" -f $itAMeAfter.Status, (Get-Code $itAMeAfter)) `
    -Why 'MySQL 与 Redis 没有天然原子事务：这条断言证明"账号已变更却返回会话撤销成功"没有发生' -Result $itAMeAfter

$itAAssignmentsAfter = Send-Req -Client $adminClient -Method 'GET' `
    -Path "/fd/v1/admin/users/$($itA.id)/active-ticket-assignments" -Token $adminToken `
    -Step 'h4.assignmentsAfter' -Actor $AdminUser -Note '交接后该用户名下已无待交接工单'
Add-Assertion -Name 'h4.noActiveAssignmentLeft' `
    -Condition ($itAAssignmentsAfter.Status -eq 200 -and @((Get-Data $itAAssignmentsAfter)).Count -eq 0) `
    -Expected '200 且数组为空' `
    -Actual ("status={0} count={1}" -f $itAAssignmentsAfter.Status, @((Get-Data $itAAssignmentsAfter)).Count) `
    -Why '停用后不能留下无效负责人：这份清单是"还能不能停用"的判据' -Result $itAAssignmentsAfter

$itBList = Send-Req -Client $itBClient -Method 'GET' `
    -Path "/fd/v1/tickets?scope=ASSIGNED_TO_ME&pageNo=1&pageSize=50" -Token $itBToken `
    -Step 'h4.itBSeesTicket' -Actor "$tempPrefix-itb" -Note '接替人按"我负责的"列表查看'
$itBItems = @()
if ($null -ne (Get-Data $itBList)) { $itBItems = @((Get-Data $itBList).items) }
$itBHasTicket = @($itBItems | Where-Object { $_.ticketNo -eq $t1.ticketNo }).Count -eq 1
Add-Assertion -Name 'h4.newAssigneeSeesTicket' `
    -Condition ($itBList.Status -eq 200 -and $itBHasTicket) `
    -Expected ("200 且列表含 {0}" -f $t1.ticketNo) `
    -Actual ("status={0} items={1} contains={2}" -f $itBList.Status, $itBItems.Count, $itBHasTicket) `
    -Why '负责人替换 + 参与关系都生效，接替人才接得住这张工单' -Result $itBList

$itBRecords = Send-Req -Client $itBClient -Method 'GET' `
    -Path "/fd/v1/tickets/$($t1.ticketNo)/records?pageNo=1&pageSize=50" -Token $itBToken `
    -Step 'h4.itBReadsTimeline' -Actor "$tempPrefix-itb" -Note '接替人读取时间线，确认 ADMIN_HANDOFF 记录可见'
$recordTypes = @()
$lastRecordType = ''
$lastRecordReason = ''
$lastRecordContext = $null
if ($null -ne (Get-Data $itBRecords)) {
    $recordItems = @((Get-Data $itBRecords).items)
    $recordTypes = @($recordItems | ForEach-Object { $_.recordType })
    if ($recordItems.Count -gt 0) {
        $lastRecord = $recordItems[$recordItems.Count - 1]
        $lastRecordType = $lastRecord.recordType
        # 原因在记录类型化的 context 里（ADMIN_HANDOFF 的 context = fromAssigneeId / toAssigneeId / reason）
        $lastRecordContext = $lastRecord.context
        if ($null -ne $lastRecordContext) { $lastRecordReason = $lastRecordContext.reason }
    }
}
Add-Assertion -Name 'h4.timelineShowsAdminHandoff' `
    -Condition ($itBRecords.Status -eq 200 -and $lastRecordType -eq 'ADMIN_HANDOFF' -and $lastRecordReason -eq $handoffReason `
        -and [long]$lastRecordContext.toAssigneeId -eq [long]$itB.id -and [long]$lastRecordContext.fromAssigneeId -eq [long]$itA.id) `
    -Expected ("最后一条记录 ADMIN_HANDOFF，context 含 from={0} / to={1} / reason=交接原因；实际序列=" -f $itA.id, $itB.id) + ($recordTypes -join '>') `
    -Actual ("status={0} types={1} lastReason={2} from={3} to={4}" -f $itBRecords.Status, ($recordTypes -join '>'), $lastRecordReason, $lastRecordContext.fromAssigneeId, $lastRecordContext.toAssigneeId) `
    -Why '交接要在不可变时间线上留痕，且对新的负责人可见' -Result $itBRecords

$script:handoffFacts = [ordered]@{
    ticketNo        = $t1.ticketNo
    userVersionFrom = $itABefore.version
    userVersionTo   = $itAAfter.version
    ticketVersionTo = $t1.version + 1
    newAssigneeId   = [long]$itB.id
    reason          = $handoffReason
    timeline        = ($recordTypes -join '>')
    sessionRevoked  = ($itAMeAfter.Status -eq 401)
}

# ── 7. h5 角色替换路径（摘掉 IT_SUPPORT） ───────────────────────────────────
Write-Host ''
Write-Host '--- h5 角色替换路径（摘掉 IT_SUPPORT） ---' -ForegroundColor Cyan

$itCBefore = Get-UserByUsername -Username "$tempPrefix-itc"
$t2SignatureBefore = Get-TicketSignature -TicketNo $t2.ticketNo
$employeeOnlyRoles = @($employeeRoleId)

$rolesNoPlan = Send-Req -Client $adminClient -Method 'PUT' -Path "/fd/v1/users/$($itC.id)/roles" `
    -Token $adminToken -Body @{ version = $itCBefore.version; roleIds = $employeeOnlyRoles } `
    -Step 'h5.roles.noPlan' -Actor $AdminUser -Note '替换角色摘掉 IT_SUPPORT，但不带交接方案'
Add-Assertion -Name 'h5.roleRemovalWithoutPlanIsRequired' `
    -Condition ($rolesNoPlan.Status -eq 409 -and (Get-Code $rolesNoPlan) -eq 'ADMIN_HANDOFF_REQUIRED') `
    -Expected '409 / ADMIN_HANDOFF_REQUIRED' `
    -Actual ("status={0} code={1}" -f $rolesNoPlan.Status, (Get-Code $rolesNoPlan)) `
    -Why '摘掉 IT_SUPPORT 之后他不能再处理工单，触发条件与停用一致' -Result $rolesNoPlan

Assert-Db -Name 'h5.rejectedRoleChangeChangesNothing' `
    -Sql (("SELECT (SELECT COUNT(*) FROM iam_user_role WHERE user_id = {0} AND role_id = {1}), " +
        "(SELECT status FROM iam_user WHERE id = {0}), (SELECT assignee_id FROM ticket WHERE ticket_no = '{2}');") -f $itC.id, $itSupportRoleId, $t2.ticketNo) `
    -Expected 'IT_SUPPORT 关系仍在（1）、账号 ENABLED、工单负责人仍是 IT-C' `
    -Why '被拒的角色替换不能留下"角色已摘、工单却没人"的中间态' `
    -Check { param($c) [long]$c[0] -eq 1 -and $c[1] -eq 'ENABLED' -and [long]$c[2] -eq [long]$itC.id }

$rolesReason = "岗位调整，摘除 IT 支持角色并交接工单（$stamp）"
$rolesOk = Send-Req -Client $adminClient -Method 'PUT' -Path "/fd/v1/users/$($itC.id)/roles" `
    -Token $adminToken -Body @{
        version       = $itCBefore.version
        roleIds       = $employeeOnlyRoles
        handoffReason = $rolesReason
        handoffs      = @((New-HandoffInstruction -TicketNo $t2.ticketNo -TicketVersion $t2.version -NewAssigneeId ([long]$itB.id)))
    } -Step 'h5.roles.withPlan' -Actor $AdminUser -Note '替换角色（摘掉 IT_SUPPORT）+ 完整交接方案'
Add-KeyAction -Name '替换角色并完成管理性交接' -Detail ("ticketNo={0} itC={1} -> itB={2}" -f $t2.ticketNo, $itC.id, $itB.id) -Result $rolesOk
$rolesData = Get-Data $rolesOk
$roleCodes = @()
if ($null -ne $rolesData -and $null -ne $rolesData.roles) { $roleCodes = @($rolesData.roles | ForEach-Object { $_.code }) }
Add-Assertion -Name 'h5.roleRemovalSucceeds' `
    -Condition ($rolesOk.Status -eq 200 -and $rolesData.status -eq 'ENABLED' -and ($roleCodes -notcontains 'IT_SUPPORT')) `
    -Expected ("200，账号仍 ENABLED，角色集合不含 IT_SUPPORT；实际=[" + ($roleCodes -join ',') + ']') `
    -Actual ("status={0} userStatus={1} roles={2}" -f $rolesOk.Status, $rolesData.status, ($roleCodes -join ',')) `
    -Why '角色替换是"摘掉处理能力"，不是停用账号' -Result $rolesOk

Assert-Db -Name 'h5.db.itSupportRemovedAndTicketReassigned' `
    -Sql (("SELECT (SELECT COUNT(*) FROM iam_user_role WHERE user_id = {0} AND role_id = {1}), " +
        "(SELECT COUNT(*) FROM iam_user_role WHERE user_id = {0} AND role_id = {2}), " +
        "(SELECT assignee_id FROM ticket WHERE ticket_no = '{3}'), " +
        "(SELECT COUNT(*) FROM ticket_record WHERE ticket_id = (SELECT id FROM ticket WHERE ticket_no = '{3}') AND record_type = 'ADMIN_HANDOFF');") `
        -f $itC.id, $itSupportRoleId, $employeeRoleId, $t2.ticketNo) `
    -Expected ("IT_SUPPORT 关系 0 条、EMPLOYEE 关系 1 条、工单负责人={0}、ADMIN_HANDOFF 记录 1 条" -f $itB.id) `
    -Why '角色表、工单表、记录表三处必须在同一事务里一起变' `
    -Check { param($c) [long]$c[0] -eq 0 -and [long]$c[1] -eq 1 -and [long]$c[2] -eq [long]$itB.id -and [long]$c[3] -eq 1 }

$itCMeAfter = Send-Req -Client $itCClient -Method 'GET' -Path '/fd/v1/auth/me' -Token $itCToken `
    -Step 'h5.itC.sessionRevoked' -Actor "$tempPrefix-itc" -Note '角色被替换的账号用旧 token 再请求 /auth/me'
Add-Assertion -Name 'h5.roleChangeRevokesSession' `
    -Condition ($itCMeAfter.Status -eq 401 -and (Get-Code $itCMeAfter) -eq 'AUTH_SESSION_INVALID') `
    -Expected '401 / AUTH_SESSION_INVALID' `
    -Actual ("status={0} code={1}" -f $itCMeAfter.Status, (Get-Code $itCMeAfter)) `
    -Why '摘掉 IT_SUPPORT 后旧 token 不能再带着旧权限继续用' -Result $itCMeAfter

$itCQueue = Send-Req -Client $itCClient -Method 'GET' `
    -Path '/fd/v1/tickets?scope=PENDING_QUEUE&pageNo=1&pageSize=10' -Token $itCToken `
    -Step 'h5.itCQueueForbidden' -Actor "$tempPrefix-itc" -Note '失去 IT_SUPPORT 后访问待受理队列'
$itCClient2 = New-Client
$itCToken2 = Invoke-Login -Client $itCClient2 -Username "$tempPrefix-itc" -Password 'Handoff#2026' `
    -Step 'h5.itCRelogin' -Note '失去 IT_SUPPORT 后重新登录（会话已撤销，需重新取票）'
$itCQueue2 = Send-Req -Client $itCClient2 -Method 'GET' `
    -Path '/fd/v1/tickets?scope=PENDING_QUEUE&pageNo=1&pageSize=10' -Token $itCToken2 `
    -Step 'h5.itCQueueForbiddenAfterRelogin' -Actor "$tempPrefix-itc" -Note '重新登录后仍未持有 TICKET_VIEW_QUEUE'
Add-Assertion -Name 'h5.roleRemovalActuallyRemovesPermission' `
    -Condition ($itCQueue2.Status -eq 403) `
    -Expected '403（权限真的没了，不只是会话失效）' `
    -Actual ("revokedTokenStatus={0}；reloginStatus={1} code={2}" -f $itCQueue.Status, $itCQueue2.Status, (Get-Code $itCQueue2)) `
    -Why '摘掉 IT_SUPPORT 的效果必须是"他再也进不了 IT 列表"，而不只是"旧 token 过期"' -Result $itCQueue2

# 反向对照：保留 IT_SUPPORT 时，即使手上还有在办工单也不需要方案，也不撤销会话。
$itBBefore = Get-UserByUsername -Username "$tempPrefix-itb"
$keepRoles = Send-Req -Client $adminClient -Method 'PUT' -Path "/fd/v1/users/$($itB.id)/roles" `
    -Token $adminToken -Body @{ version = $itBBefore.version; roleIds = @($itSupportRoleId) } `
    -Step 'h5.keepItSupportNeedsNoPlan' -Actor $AdminUser `
    -Note 'IT-B 手上已有 2 张在办工单，但这次替换保留 IT_SUPPORT 且集合未变'
Add-Assertion -Name 'h5.keepingItSupportNeedsNoPlan' `
    -Condition ($keepRoles.Status -eq 200) `
    -Expected '200（幂等：集合未变，既不要求方案也不撤会话）' `
    -Actual ("status={0} code={1}" -f $keepRoles.Status, (Get-Code $keepRoles)) `
    -Why '触发条件是"摘掉 IT_SUPPORT"，不是"手上有工单"；否则只是改个显示名都要组织交接' -Result $keepRoles

$itBMeAfterKeep = Send-Req -Client $itBClient -Method 'GET' -Path '/fd/v1/auth/me' -Token $itBToken `
    -Step 'h5.itBSessionKept' -Actor "$tempPrefix-itb" -Note '集合未变时 IT-B 的会话不应被撤销'
Add-Assertion -Name 'h5.unchangedRoleSetKeepsSession' `
    -Condition ($itBMeAfterKeep.Status -eq 200) `
    -Expected '200（会话保留）' -Actual ("status={0}" -f $itBMeAfterKeep.Status) `
    -Why '幂等请求不该有副作用：不撤会话、不改版本' -Result $itBMeAfterKeep

$script:roleFacts = [ordered]@{
    ticketNo          = $t2.ticketNo
    targetUserId      = [long]$itC.id
    newAssigneeId     = [long]$itB.id
    roleCodesAfter    = ($roleCodes -join ',')
    sessionRevoked    = ($itCMeAfter.Status -eq 401)
    reloginQueueStatus = $itCQueue2.Status
    keepItSupportStatus = $keepRoles.Status
}

# ── 8. h6 共同成功或共同失败（回滚） ────────────────────────────────────────
Write-Host ''
Write-Host '--- h6 多张工单时共同成功或共同失败 ---' -ForegroundColor Cyan

$itDBefore = Get-UserByUsername -Username "$tempPrefix-itd"
$t3SignatureBefore = Get-TicketSignature -TicketNo $t3.ticketNo
$t4SignatureBefore = Get-TicketSignature -TicketNo $t4.ticketNo

$rollbackAttempt = Send-Req -Client $adminClient -Method 'POST' -Path "/fd/v1/users/$($itD.id)/actions/disable" `
    -Token $adminToken -Body @{
        version       = $itDBefore.version
        handoffReason = "账号离职，两张工单一起交接（$stamp）"
        handoffs      = @(
            (New-HandoffInstruction -TicketNo $t3.ticketNo -TicketVersion $t3.version -NewAssigneeId ([long]$itB.id)),
            (New-HandoffInstruction -TicketNo $t4.ticketNo -TicketVersion ($t4.version + 1) -NewAssigneeId ([long]$itB.id))
        )
    } -Step 'h6.disable.partialPlan' -Actor $AdminUser `
    -Note '两张工单的方案里第二张版本已过期：第一张会被先换人，用于验证整体回滚'
Add-Assertion -Name 'h6.oneStaleTicketFailsWholeRequest' `
    -Condition ($rollbackAttempt.Status -eq 409 -and (Get-Code $rollbackAttempt) -eq 'ADMIN_HANDOFF_CONFLICT') `
    -Expected '409 / ADMIN_HANDOFF_CONFLICT' `
    -Actual ("status={0} code={1}" -f $rollbackAttempt.Status, (Get-Code $rollbackAttempt)) `
    -Why '任一张工单的版本变化都必须让整次停用失败' -Result $rollbackAttempt

Add-Assertion -Name 'h6.firstTicketRolledBack' `
    -Condition ((Get-TicketSignature -TicketNo $t3.ticketNo) -eq $t3SignatureBefore) `
    -Expected '第一张（按 id 序先被处理的那张）签名与请求前完全一致' `
    -Actual (Get-TicketSignature -TicketNo $t3.ticketNo) `
    -Why '这就是"账号或角色变更、全部负责人替换、参与关系、交接记录必须共同成功或共同失败"的直接证据'

Add-Assertion -Name 'h6.secondTicketUntouched' `
    -Condition ((Get-TicketSignature -TicketNo $t4.ticketNo) -eq $t4SignatureBefore) `
    -Expected '第二张签名不变' -Actual (Get-TicketSignature -TicketNo $t4.ticketNo) `
    -Why '失败请求不能在工单上留痕'

$itDAfter = Get-UserByUsername -Username "$tempPrefix-itd"
Add-Assertion -Name 'h6.userStillEnabled' `
    -Condition ($itDAfter.status -eq 'ENABLED' -and $itDAfter.version -eq $itDBefore.version) `
    -Expected ("ENABLED / version={0}" -f $itDBefore.version) `
    -Actual ("{0} / version={1}" -f $itDAfter.status, $itDAfter.version) `
    -Why '交接失败时账号状态不能被单独改掉'

Assert-Db -Name 'h6.db.noHandoffRecords' `
    -Sql ("SELECT COUNT(*) FROM ticket_record WHERE record_type = 'ADMIN_HANDOFF' " +
        "AND ticket_id IN (SELECT id FROM ticket WHERE ticket_no IN ('{0}', '{1}'));" -f $t3.ticketNo, $t4.ticketNo) `
    -Expected '0 条交接记录' `
    -Why '记录与换人同事务：回滚之后一条都不能剩' `
    -Check { param($c) [long]$c[0] -eq 0 }

$itDMeAfter = Send-Req -Client $itDClient -Method 'GET' -Path '/fd/v1/auth/me' -Token $itDToken `
    -Step 'h6.itDSessionKept' -Actor "$tempPrefix-itd" -Note '失败的交接不应撤销会话（撤销排在成功写入之后）'
Add-Assertion -Name 'h6.failedHandoffDoesNotRevokeSession' `
    -Condition ($itDMeAfter.Status -eq 200) `
    -Expected '200（会话仍有效）' -Actual ("status={0}" -f $itDMeAfter.Status) `
    -Why '会话撤销在写库之后、提交之前；写库失败就没有撤销这一步' -Result $itDMeAfter

$script:rollbackFacts = [ordered]@{
    ticketA          = $t3.ticketNo
    ticketB          = $t4.ticketNo
    attemptStatus    = $rollbackAttempt.Status
    attemptCode      = (Get-Code $rollbackAttempt)
    ticketARolledBack = ((Get-TicketSignature -TicketNo $t3.ticketNo) -eq $t3SignatureBefore)
    userStillEnabled = ($itDAfter.status -eq 'ENABLED')
    sessionKept      = ($itDMeAfter.Status -eq 200)
}

# ── 9. 清理：只删本次创建的行 ───────────────────────────────────────────────
Write-Host ''
Write-Host '--- 清理：只删本次创建的行 ---' -ForegroundColor Cyan

$cleanupStatements = [System.Collections.Generic.List[string]]::new()
$cleanupStatements.Add("DELETE FROM ticket_record WHERE ticket_id IN (SELECT id FROM ticket WHERE requester_id = $($empUser.id));")
$cleanupStatements.Add("DELETE FROM ticket_participant WHERE ticket_id IN (SELECT id FROM ticket WHERE requester_id = $($empUser.id));")
$cleanupStatements.Add("DELETE FROM ticket WHERE requester_id = $($empUser.id);")
$cleanupStatements.Add("DELETE FROM iam_user_role WHERE user_id IN ($($empUser.id), $($itA.id), $($itB.id), $($itC.id), $($itD.id));")
$cleanupStatements.Add("DELETE FROM iam_user WHERE id IN ($($empUser.id), $($itA.id), $($itB.id), $($itC.id), $($itD.id));")

$cleanupOutput = [System.Collections.Generic.List[string]]::new()
foreach ($statement in $cleanupStatements) {
    $result = Invoke-MySql -Sql $statement
    $cleanupOutput.Add("exit=$($result.exitCode) sql=$statement")
    if ($result.exitCode -ne 0) {
        Add-Assertion -Name 'cleanup.statement' -Condition $false -Expected 'exit=0' `
            -Actual ("$statement -> exit=$($result.exitCode) $($result.stderrText)") `
            -Why '清理失败会留下临时行，影响指纹比对'
    }
}
$script:cleanupInfo = [ordered]@{
    sql     = $cleanupStatements.ToArray()
    output  = $cleanupOutput.ToArray()
    note    = '按本次 stamp 创建的 5 个用户主键与其提交的工单精确删除；分类复用既有行，不删；ticket_daily_sequence 不回滚（见文件头"已知残留"）'
}

Assert-Db -Name 'cleanup.tempRowsRemoved' `
    -Sql ("SELECT (SELECT COUNT(*) FROM iam_user WHERE username LIKE '$tempPrefix%'), " +
        "(SELECT COUNT(*) FROM ticket WHERE requester_id = $($empUser.id)), " +
        "(SELECT COUNT(*) FROM ticket_record WHERE record_type = 'ADMIN_HANDOFF' AND actor_user_id = $adminId " +
        "AND ticket_id NOT IN (SELECT id FROM ticket));") `
    -Expected '临时用户 0 行；临时工单 0 行' `
    -Why '清理干净才谈得上"没碰运行前的数据"' `
    -Check { param($c) [long]$c[0] -eq 0 -and [long]$c[1] -eq 0 }

$script:fingerprintAfter = Get-DemoFingerprint
Add-Assertion -Name 'cleanup.fingerprintUnchanged' `
    -Condition ($script:fingerprintAfter -eq $script:fingerprintBefore) `
    -Expected '运行前与清理后的指纹完全相同（八项计数 + 五条聚合指纹）' `
    -Actual ("before=" + $script:fingerprintBefore + "`n         after =" + $script:fingerprintAfter) `
    -Why '脚本只应动自己创建的行；指纹相同即证明既有数据一行未改'

# ── 10. 汇总与证据（无 BOM 的 UTF-8） ───────────────────────────────────────
$finishedAt = Get-Date
$passed = @($script:results | Where-Object { $_.passed }).Count
$failed = @($script:results | Where-Object { -not $_.passed }).Count
$script:quitCode = 0
if ($failed -gt 0) { $script:quitCode = 1 }

$relativeOutPath = $OutFile
try {
    $resolvedOut = [System.IO.Path]::GetFullPath($OutFile)
    $repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
    if ($resolvedOut.StartsWith($repoRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        $relativeOutPath = $resolvedOut.Substring($repoRoot.Length).TrimStart('\', '/') -replace '\\', '/'
    }
} catch { $relativeOutPath = $OutFile }

$evidence = [ordered]@{
    _note       = '管理性交接（docs/api-design.md 8.3）真实栈验收；本文件由 scripts/admin-handoff-acceptance.ps1 生成，无 BOM 的 UTF-8'
    invocation  = ("pwsh -NoProfile -ExecutionPolicy Bypass -File scripts\admin-handoff-acceptance.ps1 -BaseUrl " + $baseUrl)
    script      = [ordered]@{
        path         = 'scripts/admin-handoff-acceptance.ps1'
        evidencePath = $relativeOutPath
        baseUrl      = $baseUrl
        startedAt    = $startedAt.ToString('yyyy-MM-ddTHH:mm:sszzz')
        finishedAt   = $finishedAt.ToString('yyyy-MM-ddTHH:mm:sszzz')
        durationSec  = [math]::Round(($finishedAt - $startedAt).TotalSeconds, 1)
        stamp        = $stamp
    }
    target      = [ordered]@{
        health       = $(if ($null -ne $healthJson) { $healthJson.status } else { 'UNKNOWN' })
        dbName       = $dbName
        dbContainer  = $DbContainer
        flywayNote   = '独立空库 → Flyway V1..V8 + 演示种子（local profile 含 demo 组）'
        adminUserId  = $adminId
    }
    preflight        = $script:preflightInfo
    tempPrincipals   = $script:tempPrincipalInfo
    fixtures         = $script:fixturesInfo
    handoffFacts     = $script:handoffFacts
    roleChangeFacts  = $script:roleFacts
    rollbackFacts    = $script:rollbackFacts
    fingerprints     = [ordered]@{
        before        = $script:fingerprintBefore
        after         = $script:fingerprintAfter
        identical     = ($script:fingerprintAfter -eq $script:fingerprintBefore)
    }
    keyActions       = $script:keyActions.ToArray()
    assertions       = $script:results.ToArray()
    dbChecks         = $script:dbChecks.ToArray()
    httpLog          = $script:httpLog.ToArray()
    sqlExecuted      = $script:executedSql.ToArray()
    cleanup          = $script:cleanupInfo
    safetyBoundary   = @(
        '只创建并删除脚本自己创建的 5 个临时用户（ah-acc-<stamp>-*）与其提交的 4 张工单（含记录与参与关系）',
        '分类复用演示种子里的既有分类；不新建、不删除分类',
        '运行前与清理后各测一次指纹（八项计数 + 五条聚合指纹）并断言完全相同',
        '不执行 docker compose down、不删容器、不删卷、不停任何后端进程',
        '命令行与证据中不出现 root 口令、登录口令或 accessToken（错误正文截断到 200 字符并先脱敏）',
        '已知残留：ticket_daily_sequence 会因本次 4 张工单前进，脚本不回滚它（回滚会导致编号重复）'
    )
    totals           = [ordered]@{
        assertions   = $script:results.Count
        passed       = $passed
        failed       = $failed
        httpRequests = $script:httpLog.Count
        dbChecks     = $script:dbChecks.Count
        exitCode     = $script:quitCode
    }
}

$json = $evidence | ConvertTo-Json -Depth 12
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$outDirectory = Split-Path -Parent ([System.IO.Path]::GetFullPath($OutFile))
if (-not (Test-Path -LiteralPath $outDirectory)) { New-Item -ItemType Directory -Path $outDirectory -Force | Out-Null }
[System.IO.File]::WriteAllText([System.IO.Path]::GetFullPath($OutFile), $json, $utf8NoBom)

Write-Host ''
Write-Host ("断言：{0} 通过 / {1} 失败（共 {2} 条）；HTTP 请求 {3} 次；库断言 {4} 条" -f `
        $passed, $failed, $script:results.Count, $script:httpLog.Count, $script:dbChecks.Count)
Write-Host ("证据文件：{0}" -f [System.IO.Path]::GetFullPath($OutFile))
if ($failed -eq 0) {
    Write-Host '真实栈验收通过。' -ForegroundColor Green
} else {
    Write-Host '真实栈验收存在失败断言，见上面的期望/实际/为什么重要。' -ForegroundColor Red
}

exit $script:quitCode
