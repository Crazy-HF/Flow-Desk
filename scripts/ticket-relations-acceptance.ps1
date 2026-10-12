# 完整版 backlog 2「工单关联」真实栈验收（2026-10-12）
#   POST /fd/v1/tickets 的 sourceTicketNo（创建后续工单引用原工单）
#   + GET /fd/v1/tickets/{ticketNo} 的 relations[]（方向 + 对端摘要 + 固定排序）
#   + 既有「重复关闭 DUPLICATE」路径的回归（两类关联共存）
#
# 前置
#   · docker compose up -d mysql redis（本脚本只对既有 mysql 容器执行 docker exec，不碰容器生命周期）
#   · 目标后端必须是**包含工单关联（2026-10-12）的最新代码**，由调用方自己启动。脚本只发 HTTP 请求，
#     不启动、不重启、不结束任何后端进程。
#   · 换基址：$env:FLOWDESK_BASE_URL = 'http://127.0.0.1:8092' 或 -BaseUrl http://127.0.0.1:8092
#   · 演示账号可用 E2E_EMPLOYEE_USERNAME / E2E_EMPLOYEE_PASSWORD、E2E_IT_* 覆盖（缺省 employee/it，口令 123456）
#
# 运行
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ticket-relations-acceptance.ps1 -BaseUrl http://127.0.0.1:8092
#   证据路径可覆盖：-OutFile <path> 或别名 -EvidencePath <path>
#
# 本文件必须保存为「带 BOM 的 UTF-8」：Windows PowerShell 5.1 会按系统代码页读取无 BOM 的
# UTF-8 脚本，中文注释会被解析成乱码并报语法错误。证据文件相反，必须写成**无 BOM 的 UTF-8**。
#
# 证据
#   docs/acceptance/2026-10-12-ticket-relations.json（无 BOM 的 UTF-8，脚本自己写出）
#   含：_note、invocation（带 -BaseUrl）、script.evidencePath（仓库根相对路径）、script / target /
#   preflight / fingerprints{before,after} / keyActions（每条带 X-Trace-Id）/ httpLog / dbChecks /
#   assertions（name / expected / actual / why / passed）/ sqlExecuted / cleanup.sql / totals。
#
# 权限闸门顺序（断言）
#   认证 401 → 权限 403 → 校验 400（原工单不可见与不存在统一 400，不回显他人工单是否存在）。
#
# 安全边界
#   · 只创建并删除脚本自己创建的工单、记录、参与关系、关联、1 个临时用户与 1 个临时角色
#     （含它们的 iam_user_role / iam_role_permission）；临时用户的口令占位由演示员工行复制，
#     本脚本只改 user/role 的主键级删除，不触碰运行前就存在的任何行
#   · 运行前后各测一次演示库指纹（10 项计数 + 工单/记录/参与/关联/用户/用户角色/角色/角色权限
#     逐行指纹）并断言完全相同
#   · 不执行 docker compose down、不删容器、不删卷、不停任何后端进程；数据库访问一律是
#     `docker exec -i <mysql 容器> mysql ...`（容器名与库名来自仓库根 .env 与既有脚本约定）
#   · 演示库 root 口令只从 .env 读进进程环境变量、经 `docker exec -e MYSQL_PWD` 转发，
#     不出现在命令行、不打印、不写进证据；登录口令与 accessToken 同样不打印、不落盘
#   · 断言只记录状态码、业务码、traceId 与布尔结论，不回显令牌与口令；错误正文只截取前 200 字符

param(
    # 基址可覆盖：PowerShell 5.1 没有 ?? 运算符，用 param 默认值 + 下方兜底实现同样语义
    [string]$BaseUrl = $env:FLOWDESK_BASE_URL,
    # 证据路径：别名 -EvidencePath 供调用方按另一种叫法覆盖
    [Alias('EvidencePath')]
    [string]$OutFile = (Join-Path $PSScriptRoot '..\docs\acceptance\2026-10-12-ticket-relations.json'),
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'),
    [string]$DbContainer = 'flowdesk-mysql-1',
    [string]$EmployeeUser = $env:E2E_EMPLOYEE_USERNAME,
    [string]$EmployeePassword = $env:E2E_EMPLOYEE_PASSWORD,
    [string]$ItUser = $env:E2E_IT_USERNAME,
    [string]$ItPassword = $env:E2E_IT_PASSWORD
)

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Net.Http

# ── 0. 基址、状态容器、.env 与演示账号 ───────────────────────────────────────
$baseUrl = $BaseUrl
if ([string]::IsNullOrWhiteSpace($baseUrl)) { $baseUrl = 'http://127.0.0.1:8081' }
$baseUrl = $baseUrl.TrimEnd('/')

$originHeader = 'http://127.0.0.1:5173'
$startedAt = Get-Date
$stamp = $startedAt.ToString('yyyyMMdd-HHmmss')
$sqlWorkDir = Join-Path $env:TEMP "flowdesk-relations-acc-$stamp"
New-Item -ItemType Directory -Path $sqlWorkDir -Force | Out-Null

$script:sqlCounter = 0
$script:results = [System.Collections.Generic.List[object]]::new()
$script:dbChecks = [System.Collections.Generic.List[object]]::new()
$script:keyActions = [System.Collections.Generic.List[object]]::new()
$script:httpLog = [System.Collections.Generic.List[object]]::new()
$script:notes = [System.Collections.Generic.List[string]]::new()
$script:executedSql = [System.Collections.Generic.List[string]]::new()
$script:quitCode = 0
$script:dbAvailable = $false
$script:cleanupInfo = [ordered]@{}
$script:preflightInfo = [ordered]@{}
$script:ticketsInfo = [ordered]@{}
$script:tempPrincipalInfo = [ordered]@{}
$script:relationFactsInfo = [ordered]@{}
$script:duplicateLegacyInfo = [ordered]@{}

if ([string]::IsNullOrWhiteSpace($EmployeeUser)) { $EmployeeUser = 'employee' }
if ([string]::IsNullOrWhiteSpace($EmployeePassword)) { $EmployeePassword = '123456' }
if ([string]::IsNullOrWhiteSpace($ItUser)) { $ItUser = 'it' }
if ([string]::IsNullOrWhiteSpace($ItPassword)) { $ItPassword = '123456' }

# 临时主体命名：用户名与角色编码都带本次运行的 stamp，清理时按主键精确命中，
# 不可能碰到运行前就存在的行（演示库里没有这两个前缀）。
$tempUsernamePrefix = "rl-acc-$stamp"
$tempRoleCodePrefix = "RL_ACC_$($stamp.Replace('-','_'))"
$ticketTitlePrefix = "RL-ACC-$stamp"

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
if ((-not [string]::IsNullOrWhiteSpace($dbName)) -and (-not [string]::IsNullOrWhiteSpace($rootPassword))) {
    $script:dbAvailable = $true
}

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

# $Step 是断言编号式的短标签（如 a2.followUp.create），进 httpLog 的 step 字段；
# $Note 是人话说明「这条请求在证明什么」。
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
                detail         = ("actor=" + $Actor + "；" + $Note + "；contentType=application/json")
            })
        return $result
    } finally {
        $request.Dispose()
    }
}

# 创建工单是 multipart/form-data（`ticket` part 携 JSON，与既有切片脚本同形）。
# 本脚本的版本多两个可选参数：显式 submissionKey（幂等重放）与 sourceTicketNo（后续工单引用）。
function Send-CreateTicket {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Token,
        [string]$Title,
        [string]$Description,
        [long]$CategoryId,
        [string]$SourceTicketNo,
        [string]$SubmissionKey,
        [string]$Step = '-',
        [string]$Actor = 'employee',
        [string]$Note = 'create-ticket'
    )
    if ([string]::IsNullOrWhiteSpace($SubmissionKey)) {
        $SubmissionKey = [guid]::NewGuid().ToString()
    }
    $payloadMap = [ordered]@{
        submissionKey = $SubmissionKey
        title         = $Title
        description   = $Description
        categoryId    = $CategoryId
        priority      = 'MEDIUM'
    }
    if (-not [string]::IsNullOrWhiteSpace($SourceTicketNo)) {
        $payloadMap['sourceTicketNo'] = $SourceTicketNo
    }
    $payload = $payloadMap | ConvertTo-Json -Compress
    $form = New-Object System.Net.Http.MultipartFormDataContent
    $form.Add((New-Object System.Net.Http.StringContent(
                $payload, [System.Text.Encoding]::UTF8, 'application/json')), 'ticket')
    $request = New-Object System.Net.Http.HttpRequestMessage(
        [System.Net.Http.HttpMethod]::Post, '/fd/v1/tickets')
    $requestTraceId = New-TraceId
    try {
        $request.Headers.Add('Origin', $originHeader)
        $request.Headers.Add('X-Trace-Id', $requestTraceId)
        if ($Token) {
            $request.Headers.Authorization =
            New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Token)
        }
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
                detail         = ("actor=" + $Actor + "；" + $Note +
                "；contentType=multipart/form-data（ticket part 携 JSON；sourceTicketNo=" +
                $(if ([string]::IsNullOrWhiteSpace($SourceTicketNo)) { '(未传)' } else { $SourceTicketNo }) +
                "，submissionKey=" + $SubmissionKey + "）")
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

function Get-BriefText {
    param([string]$Text, [int]$Max = 40)
    if ($null -eq $Text) { return '(null)' }
    if ($Text.Length -le $Max) { return $Text }
    return ($Text.Substring(0, $Max) + '…（共 ' + $Text.Length + ' 字符）')
}

# 逐条断言：名称、期望、实际、为什么重要、X-Trace-Id、通过与否。$Result 可空（非 HTTP 断言）。
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
        [int]$Order,
        [string]$Actor,
        [string]$Action,
        [string]$Method,
        [string]$Path,
        [string]$TicketNo,
        $Result,
        [string]$Step = '-'
    )
    $httpStatus = $null
    $traceId = $null
    if ($null -ne $Result) {
        $httpStatus = $Result.Status
        $traceId = $Result.TraceId
    }
    $script:keyActions.Add([pscustomobject]@{
            order      = $Order
            step       = $Step
            actor      = $Actor
            action     = $Action
            method     = $Method
            path       = $Path
            ticketNo   = $TicketNo
            httpStatus = $httpStatus
            traceId    = $traceId
        })
}

# 演示库指纹：运行前与清理后各测一次，断言完全相同（既有行一行都不许变）。
# 十项计数按验收要求逐项记录：ticket / ticket_record / ticket_participant / ticket_relation /
# iam_user / iam_user_role / iam_role / iam_role_permission（另附 iam_permission 与 ticket_category）。
function Get-DemoFingerprint {
    $countsSql = "SELECT (SELECT COUNT(*) FROM ticket), (SELECT COUNT(*) FROM ticket_record), " +
    "(SELECT COUNT(*) FROM ticket_participant), (SELECT COUNT(*) FROM ticket_relation), " +
    "(SELECT COUNT(*) FROM iam_user), (SELECT COUNT(*) FROM iam_user_role), " +
    "(SELECT COUNT(*) FROM iam_role), (SELECT COUNT(*) FROM iam_role_permission), " +
    "(SELECT COUNT(*) FROM iam_permission), (SELECT COUNT(*) FROM ticket_category);"
    $countsRow = Get-MySqlRow -Sql $countsSql
    $counts = @($countsRow.columns)
    $ticketNos = Get-MySqlRow -Sql "SELECT IFNULL(GROUP_CONCAT(ticket_no ORDER BY id SEPARATOR ','),'-') FROM ticket;"
    $ticketRows = Get-MySqlRow -Sql (
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(ticket_no,'|',status,'|',version,'|',record_seq,'|'," +
        "IFNULL(assignee_id,-1),'|',IF(ended_at IS NULL,'NULL','SET'),'|'," +
        "IFNULL(close_method,'NULL'),'|',IFNULL(close_reason,'NULL'),'|'," +
        "DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f')) ORDER BY id SEPARATOR ' ;; '),'-') FROM ticket;")
    $recordRows = Get-MySqlRow -Sql (
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(id,'|',ticket_id,'|',sequence_no,'|',record_type,'|',actor_type,'|'," +
        "IFNULL(actor_user_id,-1),'|',DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f')) " +
        "ORDER BY id SEPARATOR ' ;; '),'-') FROM ticket_record;")
    $participantRows = Get-MySqlRow -Sql (
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(ticket_id,'|',user_id,'|'," +
        "DATE_FORMAT(first_assigned_at,'%Y-%m-%d %H:%i:%s.%f')) ORDER BY ticket_id,user_id SEPARATOR ' ;; '),'-') " +
        "FROM ticket_participant;")
    $relationRows = Get-MySqlRow -Sql (
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(source_ticket_id,'|',target_ticket_id,'|',relation_type,'|'," +
        "IFNULL(created_by,-1),'|',DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f')) ORDER BY id SEPARATOR ' ;; '),'-') " +
        "FROM ticket_relation;")
    $userRows = Get-MySqlRow -Sql (
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(id,'|',username,'|',status) ORDER BY id SEPARATOR ' ;; '),'-') FROM iam_user;")
    $userRoleRows = Get-MySqlRow -Sql (
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(user_id,'|',role_id) ORDER BY user_id,role_id SEPARATOR ' ;; '),'-') FROM iam_user_role;")
    $roleRows = Get-MySqlRow -Sql (
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(id,'|',code) ORDER BY id SEPARATOR ' ;; '),'-') FROM iam_role;")
    $rolePermissionRows = Get-MySqlRow -Sql (
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(role_id,'|',permission_id) ORDER BY role_id,permission_id SEPARATOR ' ;; '),'-') " +
        "FROM iam_role_permission;")
    # ticket_daily_sequence 是既有行，但通过接口建单必然让它递增（编号不复用），
    # 因此只记录不断言，也不回退——回退就是改动运行前就存在的行。
    $dailySequence = Get-MySqlRow -Sql (
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(business_date,'=',current_value) ORDER BY business_date SEPARATOR ' ;; '),'-') " +
        "FROM ticket_daily_sequence;")

    $fingerprint = [ordered]@{
        at                 = (Get-Date).ToString('s')
        tickets            = -1
        records            = -1
        participants       = -1
        relations          = -1
        users              = -1
        userRoles          = -1
        roles              = -1
        rolePermissions    = -1
        permissions        = -1
        categories         = -1
        ticketNos          = $ticketNos.raw
        ticketRows         = $ticketRows.raw
        recordRows         = $recordRows.raw
        participantRows    = $participantRows.raw
        relationRows       = $relationRows.raw
        userRows           = $userRows.raw
        userRoleRows       = $userRoleRows.raw
        roleRows           = $roleRows.raw
        rolePermissionRows = $rolePermissionRows.raw
        dailySequence      = $dailySequence.raw
        readOk             = $countsRow.ok
    }
    if ($countsRow.ok -and $counts.Count -ge 10) {
        $fingerprint.tickets = [int]$counts[0]
        $fingerprint.records = [int]$counts[1]
        $fingerprint.participants = [int]$counts[2]
        $fingerprint.relations = [int]$counts[3]
        $fingerprint.users = [int]$counts[4]
        $fingerprint.userRoles = [int]$counts[5]
        $fingerprint.roles = [int]$counts[6]
        $fingerprint.rolePermissions = [int]$counts[7]
        $fingerprint.permissions = [int]$counts[8]
        $fingerprint.categories = [int]$counts[9]
    }
    return [pscustomobject]$fingerprint
}

# 一条关联行（source 精确命中本脚本自建的工单）。
function Get-RelationRow {
    param([long]$SourceTicketId)
    return Get-MySqlRow -Sql (
        "SELECT IFNULL(source_ticket_id,-1), IFNULL(target_ticket_id,-1), IFNULL(relation_type,'NULL'), " +
        "IFNULL(created_by,-1) FROM ticket_relation WHERE source_ticket_id = $SourceTicketId;")
}

# ── 2. 客户端与登录 ─────────────────────────────────────────────────────────

$employeeClient = New-Client
$itClient = New-Client
$anonymousClient = New-Client
$tempClient = New-Client
$clients = @($employeeClient, $itClient, $anonymousClient, $tempClient)

$unknownTicketNo = 'FD-19990101-001'
$employeeUserId = -1
$itUserId = -1
$demoBefore = $null
$demoAfter = $null
$categoryId = -1
$tempUser = $null
$foreignTicketNo = ''

# 载体工单（全部由接口创建，收尾按标题前缀精确删除）
$ticketOriginal = ''    # 原工单（无引用创建）
$ticketFollowUp = ''    # 后续工单（引用原工单创建）
$ticketReplay = ''      # 幂等重放（同一提交键第二次请求返回同一张单）
$replaySubmissionKey = [guid]::NewGuid().ToString()
$ticketDuplicate = ''   # 被重复关闭的工单（回归既有 DUPLICATE 关联）

Write-Host ("目标后端：{0}；证据：{1}" -f $baseUrl, $OutFile)

# ── 3. 预检与登录 ───────────────────────────────────────────────────────────

# 匿名创建：统一安全链必须拦在鉴权之前（401 + AUTH_REQUIRED）
$anonCreate = Send-CreateTicket -Client $anonymousClient -Token '' `
    -Title "$ticketTitlePrefix-匿名探测" -Description '匿名创建必须被拒绝' -CategoryId 1 `
    -Step 'preflight.anonymous.create' -Actor 'anonymous' -Note '无令牌创建工单，必须 401'
Add-Assertion -Name 'preflight.anonymous.create.401' `
    -Condition (($anonCreate.Status -eq 401) -and ((Get-Code $anonCreate) -eq 'AUTH_REQUIRED')) `
    -Expected '401 + AUTH_REQUIRED' `
    -Actual "status=$($anonCreate.Status) code=$(Get-Code $anonCreate)" `
    -Why '创建端点受统一安全链保护；匿名可写意味着整个工单入口失守' `
    -Result $anonCreate

$loginEmployee = Send-Req -Client $employeeClient -Method Post -Path '/fd/v1/auth/login' `
    -Body @{ username = $EmployeeUser; password = $EmployeePassword } -Step 'login.employee' `
    -Actor 'employee' -Note '演示员工登录'
$loginIt = Send-Req -Client $itClient -Method Post -Path '/fd/v1/auth/login' `
    -Body @{ username = $ItUser; password = $ItPassword } -Step 'login.it' -Actor 'it' `
    -Note '演示 IT 登录（重复关闭回归用）'
Add-Assertion -Name 'login.employee.200' -Condition ($loginEmployee.Status -eq 200) -Expected '200' `
    -Actual "status=$($loginEmployee.Status) code=$(Get-Code $loginEmployee)" `
    -Why '创建与详情断言全部由这个身份执行；登录失败说明演示账号口令与 .env/默认值不一致' `
    -Result $loginEmployee
Add-Assertion -Name 'login.it.200' -Condition ($loginIt.Status -eq 200) -Expected '200' `
    -Actual "status=$($loginIt.Status) code=$(Get-Code $loginIt)" `
    -Why '重复关闭回归需要 IT 领取并关闭；登录失败则该组断言无法进行' `
    -Result $loginIt
if (($loginEmployee.Status -ne 200) -or ($loginIt.Status -ne 200)) {
    throw "演示账号登录未全部返回 200（employee=$($loginEmployee.Status) it=$($loginIt.Status)）；口令错误或账号被改，请用 E2E_* 覆盖或在演示库核对账号"
}
$employeeToken = (Get-Data $loginEmployee).accessToken
$itToken = (Get-Data $loginIt).accessToken

# 分类选项：接口建单必须带 categoryId
$options = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/categories/options' `
    -Token $employeeToken -Step 'categories.options' -Actor 'employee' -Note '读取启用分类，供建单使用'
$optionItems = @(Get-Data $options)
Add-Assertion -Name 'categories.options.nonEmpty' `
    -Condition (($options.Status -eq 200) -and ($optionItems.Count -ge 1)) `
    -Expected '200 且至少一个启用分类' `
    -Actual "status=$($options.Status) count=$($optionItems.Count)" `
    -Why '接口建单必须带 categoryId；没有启用分类就没法造出真实的载体工单' `
    -Result $options
if ($optionItems.Count -lt 1) { throw '演示库没有启用分类，无法创建载体工单' }
$categoryId = [long]$optionItems[0].id

# 数据库直连预检：没有 .env 或容器不在时，指纹与直查断言会是 FAIL 而不是静默跳过
$pingRow = Get-MySqlRow -Sql "SELECT 1;"
Add-Assertion -Name 'preflight.db.available' -Condition ($script:dbAvailable -and $pingRow.ok) `
    -Expected '1（.env 加载且 docker exec 可查库）' `
    -Actual ("dbAvailable=$($script:dbAvailable) pingOk=$($pingRow.ok) raw=[$($pingRow.raw)]") `
    -Why '指纹、关联行直查与清理都依赖数据库直连；不可用时后面所有直查断言都失去意义' `
    -Result $null

$demoBefore = Get-DemoFingerprint
$script:preflightInfo = [ordered]@{
    baseUrl            = $baseUrl
    dbAvailable        = $script:dbAvailable
    demoFingerprintAt  = $demoBefore.at
    demoCountsBefore   = @(
        $demoBefore.tickets, $demoBefore.records, $demoBefore.participants, $demoBefore.relations,
        $demoBefore.users, $demoBefore.userRoles, $demoBefore.roles, $demoBefore.rolePermissions
    )
    categoryId         = $categoryId
    unknownTicketNo    = $unknownTicketNo
}

# ── 4. 临时主体：一个只有 TICKET_VIEW_OWN 的用户 ────────────────────────────
# 两个用途：① 建一张"别人的工单"作为不可见引用的目标；② 以"没有 TICKET_CREATE 的账号"
# 调创建接口，证明 403 闸门。口令占位从演示员工行复制，因此可以用员工口令登录。
$tempUsername = "$tempUsernamePrefix-viewer"
$tempDisplayName = "工单关联验收临时账号-$stamp"
$tempRoleCode = "$tempRoleCodePrefix-VIEWER"
$tempStatements = @(
    "INSERT INTO iam_role (code, name, description, created_at) VALUES " +
    "('$tempRoleCode', '$tempDisplayName 角色', '工单关联真实验收临时角色（脚本创建，收尾删除）', UTC_TIMESTAMP(3));",
    "INSERT INTO iam_role_permission (role_id, permission_id, granted_by, granted_at) " +
    "SELECT r.id, p.id, NULL, UTC_TIMESTAMP(3) FROM iam_role r JOIN iam_permission p ON p.code = 'TICKET_VIEW_OWN' " +
    "WHERE r.code = '$tempRoleCode';",
    "INSERT INTO iam_user (username, display_name, password, status, created_at, updated_at, version) " +
    "SELECT '$tempUsername', '$tempDisplayName', u.password, 'ENABLED', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 0 " +
    "FROM iam_user u WHERE u.username = '$EmployeeUser';",
    "INSERT INTO iam_user_role (user_id, role_id, granted_by, granted_at) " +
    "SELECT u.id, r.id, NULL, UTC_TIMESTAMP(3) FROM iam_user u JOIN iam_role r ON r.code = '$tempRoleCode' " +
    "WHERE u.username = '$tempUsername';"
) -join "`n"
$tempInsert = Invoke-MySql -Sql $tempStatements
$tempIdRow = Get-MySqlRow -Sql ("SELECT IFNULL(u.id,-1), IFNULL(r.id,-1) FROM iam_user u " +
    "LEFT JOIN iam_role r ON r.code = '$tempRoleCode' WHERE u.username = '$tempUsername';")
$tempUserId = -1
$tempRoleId = -1
if ($tempIdRow.ok) {
    $tempUserId = [int]$tempIdRow.columns[0]
    $tempRoleId = [int]$tempIdRow.columns[1]
}
$tempCreated = (($tempInsert.exitCode -eq 0) -and ($tempUserId -gt 0) -and ($tempRoleId -gt 0))
Add-Assertion -Name 'setup.temp.viewer.created' -Condition $tempCreated `
    -Expected '临时用户 + 只含 TICKET_VIEW_OWN 的角色建好' `
    -Actual ("username=$tempUsername roleCode=$tempRoleCode userId=$tempUserId roleId=$tempRoleId " +
    "exitCode=$($tempInsert.exitCode) stderr=[$(Get-BriefText $tempInsert.stderrText 120)]") `
    -Why '临时主体是"他人工单不可见"与 403 两格的唯一装置；建不出来这两格无法进行' `
    -Result $null
if (-not $tempCreated) { throw "临时主体创建失败（userId=$tempUserId roleId=$tempRoleId）" }
$tempUser = [pscustomobject]@{ username = $tempUsername; userId = $tempUserId; roleId = $tempRoleId; roleCode = $tempRoleCode }
$script:tempPrincipalInfo = [ordered]@{
    username        = $tempUsername
    userId          = $tempUserId
    roleCode        = $tempRoleCode
    roleId          = $tempRoleId
    permissionCodes = @('TICKET_VIEW_OWN')
    purpose         = '① 别人的待受理工单（不可见引用目标）② 无 TICKET_CREATE 账号的 403 闸门'
}

# 临时用户的工单：SQL 造数夹具（不需要走接口——它的存在只是为了"员工看不到它"）。
$foreignTicketNo = "RL-FIXTURE-$stamp"
$foreignFixtureSql = "INSERT INTO ticket (ticket_no, submission_key, requester_id, title, description, " +
"category_id, priority, status, record_seq, version, created_at, updated_at) VALUES " +
"('$foreignTicketNo', '$( [guid]::NewGuid().ToString() )', $tempUserId, " +
"'$ticketTitlePrefix-别人的工单', '不可见引用目标（脚本造数夹具）', $categoryId, 'MEDIUM', 'PENDING', 1, 0, " +
"UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));"
$foreignInsert = Invoke-MySql -Sql $foreignFixtureSql
$foreignRow = Get-MySqlRow -Sql ("SELECT IFNULL(t.id,-1) FROM ticket t WHERE t.ticket_no = '$foreignTicketNo';")
$foreignId = -1
if ($foreignRow.ok) { $foreignId = [int]$foreignRow.columns[0] }
Add-Assertion -Name 'setup.foreignTicket.created' `
    -Condition (($foreignInsert.exitCode -eq 0) -and ($foreignId -gt 0)) `
    -Expected '他人待受理工单夹具插入成功' `
    -Actual "ticketNo=$foreignTicketNo ticketId=$foreignId exitCode=$($foreignInsert.exitCode)" `
    -Why '它专供"原工单对提交人不可见 → 400"这一格；没有它，该格只能跳过' `
    -Result $null

# ── 5. a1 无引用创建：详情返回空 relations[] ────────────────────────────────

$createOriginal = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
    -Title "$ticketTitlePrefix-原工单" -Description '工单关联验收：先提交的原工单。' `
    -CategoryId $categoryId -Step 'a1.create.original' -Actor 'employee' -Note '不带 sourceTicketNo 创建原工单'
Add-Assertion -Name 'a1.create.original.201' -Condition ($createOriginal.Status -eq 201) -Expected '201' `
    -Actual "status=$($createOriginal.Status) code=$(Get-Code $createOriginal) body=[$(Get-SafeBody $createOriginal)]" `
    -Why '原工单是后续全部断言的地基；这一步失败后面没有可引用的对象' -Result $createOriginal
if ($createOriginal.Status -ne 201) { throw "原工单创建失败（status=$($createOriginal.Status)）" }
$ticketOriginal = (Get-Data $createOriginal).ticketNo
Add-KeyAction -Order 1 -Actor 'employee' -Action 'create.original' -Method 'POST' -Path '/fd/v1/tickets' `
    -TicketNo $ticketOriginal -Result $createOriginal -Step 'a1.create.original'

$originalIdRow = Get-MySqlRow -Sql ("SELECT IFNULL(id,-1), IFNULL(requester_id,-1) FROM ticket WHERE ticket_no = '$ticketOriginal';")
$originalId = -1
if ($originalIdRow.ok) {
    $originalId = [int]$originalIdRow.columns[0]
    $employeeUserId = [int]$originalIdRow.columns[1]
}

$detailOriginal = Send-Req -Client $employeeClient -Method Get -Path "/fd/v1/tickets/$ticketOriginal" `
    -Token $employeeToken -Step 'a1.detail.original' -Actor 'employee' -Note '读取原工单详情（关联为空）'
$detailOriginalData = Get-Data $detailOriginal
$detailPossessesRelationsField = $false
try {
    $null = $detailOriginalData.relations
    $detailPossessesRelationsField = $null -ne ($detailOriginalData.PSObject.Properties['relations'])
} catch { $detailPossessesRelationsField = $false }
$emptyRelations = @($detailOriginalData.relations)
Add-Assertion -Name 'a1.detail.original.200' -Condition ($detailOriginal.Status -eq 200) -Expected '200' `
    -Actual "status=$($detailOriginal.Status) code=$(Get-Code $detailOriginal)" `
    -Why '详情是关联摘要的唯一出口；这一步同时证明目标后端包含工单关联代码' -Result $detailOriginal
Add-Assertion -Name 'a1.detail.relations.fieldPresent' -Condition $detailPossessesRelationsField `
    -Expected 'relations 字段存在（空数组）' `
    -Actual ("fieldPresent=$detailPossessesRelationsField count=$($emptyRelations.Count)；" +
    "若字段不存在说明目标后端不含 2026-10-12 的关联代码") `
    -Why 'relations 是本次新增的契约字段；用"字段存在且为空"同时证明端点存活与"无关联返回空数组"' `
    -Result $detailOriginal
Add-Assertion -Name 'a1.detail.relations.empty' -Condition ($emptyRelations.Count -eq 0) `
    -Expected '0 条' -Actual "count=$($emptyRelations.Count)" `
    -Why '没有关联的工单必须返回空数组而不是省略字段，界面才能统一按列表渲染' -Result $detailOriginal
Assert-Db -Name 'a1.db.original.hasNoRelation' `
    -Sql "SELECT COUNT(*) FROM ticket_relation WHERE source_ticket_id = $originalId OR target_ticket_id = $originalId;" `
    -Expected '0' -Why '无引用创建不得写关联（关联只在带 sourceTicketNo 的创建里产生）' `
    -Check { param($columns) ([int]$columns[0] -eq 0) }

# ── 6. a2 引用创建：FOLLOW_UP 方向、摘要与双向详情 ──────────────────────────

# 编号两侧带空白：命令构造器应去除空白后再校验与落库
$createFollowUp = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
    -Title "$ticketTitlePrefix-后续工单" -Description '工单关联验收：问题再次出现，引用原工单。' `
    -CategoryId $categoryId -SourceTicketNo "  $ticketOriginal  " `
    -Step 'a2.create.followUp' -Actor 'employee' -Note '带 sourceTicketNo（含首尾空白）创建后续工单'
Add-Assertion -Name 'a2.create.followUp.201' -Condition ($createFollowUp.Status -eq 201) -Expected '201' `
    -Actual "status=$($createFollowUp.Status) code=$(Get-Code $createFollowUp) body=[$(Get-SafeBody $createFollowUp)]" `
    -Why '后续工单创建成功是关联写入的前提；400 说明可见性校验把原工单误判为不可见' `
    -Result $createFollowUp
if ($createFollowUp.Status -ne 201) { throw "后续工单创建失败（status=$($createFollowUp.Status)）" }
$ticketFollowUp = (Get-Data $createFollowUp).ticketNo
Add-KeyAction -Order 2 -Actor 'employee' -Action 'create.followUp' -Method 'POST' -Path '/fd/v1/tickets' `
    -TicketNo $ticketFollowUp -Result $createFollowUp -Step 'a2.create.followUp'

$followUpIdRow = Get-MySqlRow -Sql ("SELECT IFNULL(id,-1) FROM ticket WHERE ticket_no = '$ticketFollowUp';")
$followUpId = -1
if ($followUpIdRow.ok) { $followUpId = [int]$followUpIdRow.columns[0] }

$relationRow = Get-RelationRow -SourceTicketId $followUpId
Add-Assertion -Name 'a2.db.followUp.relationWritten' -Condition $relationRow.ok `
    -Expected '1 行：source=后续工单 target=原工单 type=FOLLOW_UP created_by=提交人' `
    -Actual "row=[$($relationRow.raw)]" `
    -Why '方向约定（source = 新工单、target = 原工单）是唯一键与四格标签的共同前提' -Result $null
$relationSql = "SELECT source_ticket_id, target_ticket_id, relation_type, created_by " +
"FROM ticket_relation WHERE source_ticket_id = $followUpId;"
Assert-Db -Name 'a2.db.relation.directionAndType' -Sql $relationSql `
    -Expected "source=$followUpId target=$originalId type=FOLLOW_UP createdBy=$employeeUserId" `
    -Why '写入行必须与"新工单指向原工单"的方向逐字一致，created_by 记提交人' `
    -Check { param($columns)
        ([int]$columns[0] -eq $followUpId) -and ([int]$columns[1] -eq $originalId) -and
        ($columns[2] -eq 'FOLLOW_UP') -and ([int]$columns[3] -eq $employeeUserId) }
Assert-Db -Name 'a2.db.relation.onePerSource' `
    -Sql "SELECT COUNT(*) FROM ticket_relation WHERE source_ticket_id = $followUpId AND relation_type = 'FOLLOW_UP';" `
    -Expected '1' -Why '唯一键 (source, relation_type) 保证一张单只有一条后续指向' `
    -Check { param($columns) ([int]$columns[0] -eq 1) }

$detailFollowUp = Send-Req -Client $employeeClient -Method Get -Path "/fd/v1/tickets/$ticketFollowUp" `
    -Token $employeeToken -Step 'a2.detail.followUp' -Actor 'employee' -Note '后续工单详情：应返回 OUTGOING 关联'
$followUpRelations = @((Get-Data $detailFollowUp).relations)
$outgoing = $null
if ($followUpRelations.Count -ge 1) { $outgoing = $followUpRelations[0] }
Add-Assertion -Name 'a2.detail.followUp.oneRelation' -Condition ($followUpRelations.Count -eq 1) `
    -Expected '1 条' -Actual "count=$($followUpRelations.Count)" `
    -Why '新单视角应恰有一条出向关联' -Result $detailFollowUp
Add-Assertion -Name 'a2.detail.followUp.outgoingShape' `
    -Condition (($null -ne $outgoing) -and ($outgoing.relationType -eq 'FOLLOW_UP') -and
    ($outgoing.direction -eq 'OUTGOING') -and ($outgoing.ticketNo -eq $ticketOriginal) -and
    ($outgoing.status -eq 'PENDING') -and ($outgoing.title -eq "$ticketTitlePrefix-原工单")) `
    -Expected "FOLLOW_UP + OUTGOING + $ticketOriginal + 原工单标题 + PENDING" `
    -Actual ("type=$($outgoing.relationType) direction=$($outgoing.direction) ticketNo=$($outgoing.ticketNo) " +
    "status=$($outgoing.status) title=$($outgoing.title)") `
    -Why '新单视角是"本单是 source"→ OUTGOING；摘要的四个字段是前端四格标签之外的全部信息' `
    -Result $detailFollowUp

$detailOriginalAgain = Send-Req -Client $employeeClient -Method Get -Path "/fd/v1/tickets/$ticketOriginal" `
    -Token $employeeToken -Step 'a2.detail.original' -Actor 'employee' -Note '原工单详情：应返回 INCOMING 关联'
$originalRelations = @((Get-Data $detailOriginalAgain).relations)
$incoming = $null
if ($originalRelations.Count -ge 1) { $incoming = $originalRelations[0] }
Add-Assertion -Name 'a2.detail.original.incomingShape' `
    -Condition (($originalRelations.Count -eq 1) -and ($null -ne $incoming) -and
    ($incoming.relationType -eq 'FOLLOW_UP') -and ($incoming.direction -eq 'INCOMING') -and
    ($incoming.ticketNo -eq $ticketFollowUp) -and ($incoming.title -eq "$ticketTitlePrefix-后续工单")) `
    -Expected "1 条：FOLLOW_UP + INCOMING + $ticketFollowUp" `
    -Actual ("count=$($originalRelations.Count) type=$($incoming.relationType) direction=$($incoming.direction) " +
    "ticketNo=$($incoming.ticketNo)") `
    -Why '同一行数据在原工单视角必须翻转为 INCOMING——方向是"相对当前工单"算的，不是写死的' `
    -Result $detailOriginalAgain

$script:relationFactsInfo = [ordered]@{
    originalTicket = $ticketOriginal
    followUpTicket = $ticketFollowUp
    relationRow    = $relationRow.raw
    outgoing       = ("type=" + $outgoing.relationType + " direction=" + $outgoing.direction)
    incoming       = ("type=" + $incoming.relationType + " direction=" + $incoming.direction)
}

# ── 7. a3 非法引用：不存在与不可见统一 400，且不落任何数据 ────────────────────

$createUnknownSource = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
    -Title "$ticketTitlePrefix-无效引用-不存在" -Description '引用不存在的编号必须被拒绝' `
    -CategoryId $categoryId -SourceTicketNo $unknownTicketNo `
    -Step 'a3.create.unknownSource' -Actor 'employee' -Note '引用不存在的编号'
Add-Assertion -Name 'a3.create.unknownSource.400' `
    -Condition (($createUnknownSource.Status -eq 400) -and ((Get-Code $createUnknownSource) -eq 'VALIDATION_FAILED')) `
    -Expected '400 + VALIDATION_FAILED' `
    -Actual "status=$($createUnknownSource.Status) code=$(Get-Code $createUnknownSource) body=[$(Get-SafeBody $createUnknownSource)]" `
    -Why '不存在的编号必须被校验挡住；201 说明后端忽略了 sourceTicketNo（旧构建或字段没接上）' `
    -Result $createUnknownSource

$createInvisibleSource = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
    -Title "$ticketTitlePrefix-无效引用-他人" -Description '引用他人不可见工单必须被拒绝' `
    -CategoryId $categoryId -SourceTicketNo $foreignTicketNo `
    -Step 'a3.create.invisibleSource' -Actor 'employee' -Note '引用别人的待受理工单'
Add-Assertion -Name 'a3.create.invisibleSource.400' `
    -Condition (($createInvisibleSource.Status -eq 400) -and ((Get-Code $createInvisibleSource) -eq 'VALIDATION_FAILED')) `
    -Expected '400 + VALIDATION_FAILED' `
    -Actual "status=$($createInvisibleSource.Status) code=$(Get-Code $createInvisibleSource) body=[$(Get-SafeBody $createInvisibleSource)]" `
    -Why '他人的工单对员工不可见；必须与"不存在"同一种 400，不回显他人工单是否存在' `
    -Result $createInvisibleSource

Assert-Db -Name 'a3.db.noTicketFromRejectedCreates' `
    -Sql "SELECT COUNT(*) FROM ticket WHERE title LIKE '$ticketTitlePrefix-无效引用%';" `
    -Expected '0' -Why '被拒绝的创建不落工单——校验先于编号分配与插入' `
    -Check { param($columns) ([int]$columns[0] -eq 0) }
Assert-Db -Name 'a3.db.noRelationFromRejectedCreates' `
    -Sql "SELECT COUNT(*) FROM ticket_relation r JOIN ticket t ON t.id = r.source_ticket_id WHERE t.title LIKE '$ticketTitlePrefix-%';" `
    -Expected '1（只算 a2 写入的那一条）' `
    -Why '两条被拒绝的创建都不能写关联；计数为 1 说明只有 a2 的合法引用落了库' `
    -Check { param($columns) ([int]$columns[0] -eq 1) }

# ── 8. a4 幂等重放：同一提交键不重复写关联 ──────────────────────────────────

$replayFirst = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
    -Title "$ticketTitlePrefix-幂等重放" -Description '工单关联验收：同一提交键重放。' `
    -CategoryId $categoryId -SourceTicketNo $ticketOriginal -SubmissionKey $replaySubmissionKey `
    -Step 'a4.replay.first' -Actor 'employee' -Note '第一次请求（该提交键）'
$replaySecond = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
    -Title "$ticketTitlePrefix-幂等重放" -Description '工单关联验收：同一提交键重放。' `
    -CategoryId $categoryId -SourceTicketNo $ticketOriginal -SubmissionKey $replaySubmissionKey `
    -Step 'a4.replay.second' -Actor 'employee' -Note '同一提交键第二次请求'
$replayFirstNo = ''
if ($replayFirst.Status -eq 201) { $replayFirstNo = (Get-Data $replayFirst).ticketNo }
$replaySecondNo = ''
if ($replaySecond.Status -eq 201) { $replaySecondNo = (Get-Data $replaySecond).ticketNo }
$ticketReplay = $replayFirstNo
Add-Assertion -Name 'a4.replay.sameTicketNo' `
    -Condition (($replayFirst.Status -eq 201) -and ($replaySecond.Status -eq 201) -and
    ($replayFirstNo -ne '') -and ($replayFirstNo -eq $replaySecondNo)) `
    -Expected '两次都是 201 且编号相同' `
    -Actual "first=$($replayFirst.Status)/$replayFirstNo second=$($replaySecond.Status)/$replaySecondNo" `
    -Why '重放必须返回首次创建结果；编号不同意味着同一次提交建出了两张工单' `
    -Result $replaySecond
Add-KeyAction -Order 3 -Actor 'employee' -Action 'create.replay' -Method 'POST' -Path '/fd/v1/tickets' `
    -TicketNo $replayFirstNo -Result $replayFirst -Step 'a4.replay.first'
if ($replayFirstNo -ne '') {
    $replayIdRow = Get-MySqlRow -Sql ("SELECT IFNULL(id,-1) FROM ticket WHERE ticket_no = '$replayFirstNo';")
    $replayId = -1
    if ($replayIdRow.ok) { $replayId = [int]$replayIdRow.columns[0] }
    Assert-Db -Name 'a4.db.replay.singleRelation' `
        -Sql "SELECT COUNT(*) FROM ticket_relation WHERE source_ticket_id = $replayId;" `
        -Expected '1' -Why '重放不再写第二条 FOLLOW_UP（唯一键与幂等路径双重保护）' `
        -Check { param($columns) ([int]$columns[0] -eq 1) }
    Assert-Db -Name 'a4.db.replay.singleTicket' `
        -Sql "SELECT COUNT(*) FROM ticket WHERE title LIKE '$ticketTitlePrefix-幂等重放';" `
        -Expected '1' -Why '同一提交键在库里只能有一行' `
        -Check { param($columns) ([int]$columns[0] -eq 1) }
}

# ── 9. a5 既有重复关闭回归：DUPLICATE 关联与两类关联共存排序 ─────────────────

$createDuplicate = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
    -Title "$ticketTitlePrefix-重复关闭" -Description '工单关联验收：被重复关闭的工单。' `
    -CategoryId $categoryId -Step 'a5.create.duplicate' -Actor 'employee' -Note '创建将被关闭为重复的工单'
$ticketDuplicate = ''
if ($createDuplicate.Status -eq 201) { $ticketDuplicate = (Get-Data $createDuplicate).ticketNo }
Add-Assertion -Name 'a5.create.duplicate.201' -Condition ($createDuplicate.Status -eq 201) -Expected '201' `
    -Actual "status=$($createDuplicate.Status) ticketNo=$ticketDuplicate" `
    -Why '重复关闭回归的载体；失败则该组无法进行' -Result $createDuplicate
if ($ticketDuplicate -eq '') { throw "重复关闭载体工单创建失败（status=$($createDuplicate.Status)）" }
$duplicateIdRow = Get-MySqlRow -Sql ("SELECT IFNULL(id,-1) FROM ticket WHERE ticket_no = '$ticketDuplicate';")
$duplicateId = -1
if ($duplicateIdRow.ok) { $duplicateId = [int]$duplicateIdRow.columns[0] }

$claimDuplicate = Send-Req -Client $itClient -Method Post -Path "/fd/v1/tickets/$ticketDuplicate/actions/claim" `
    -Token $itToken -Body @{ version = 0 } -Step 'a5.claim' -Actor 'it' -Note 'IT 领取待关闭工单'
Add-Assertion -Name 'a5.claim.200' -Condition ($claimDuplicate.Status -eq 200) -Expected '200' `
    -Actual "status=$($claimDuplicate.Status) code=$(Get-Code $claimDuplicate)" `
    -Why '关闭只允许「处理中」的当前负责人；领取失败则关闭无法进行' -Result $claimDuplicate
Add-KeyAction -Order 4 -Actor 'it' -Action 'claim' -Method 'POST' `
    -Path "/fd/v1/tickets/$ticketDuplicate/actions/claim" -TicketNo $ticketDuplicate `
    -Result $claimDuplicate -Step 'a5.claim'

if ($claimDuplicate.Status -eq 200) {
    # 关闭动作的 created_by 记的是领取后的负责人（IT），从行上读回而不是猜
    $itIdRow = Get-MySqlRow -Sql ("SELECT IFNULL(assignee_id,0) FROM ticket WHERE ticket_no = '$ticketDuplicate';")
    if ($itIdRow.ok) { $itUserId = [int]$itIdRow.columns[0] }
}
$closeDuplicate = Send-Req -Client $itClient -Method Post -Path "/fd/v1/tickets/$ticketDuplicate/actions/close" `
    -Token $itToken -Body @{
        version           = 1
        reasonCode        = 'DUPLICATE'
        description       = '与先前提交的原工单重复'
        duplicateTicketNo = $ticketOriginal
    } -Step 'a5.close.duplicate' -Actor 'it' -Note '按重复原因关闭，目标指向原工单'
Add-Assertion -Name 'a5.close.duplicate.200' -Condition ($closeDuplicate.Status -eq 200) -Expected '200' `
    -Actual "status=$($closeDuplicate.Status) code=$(Get-Code $closeDuplicate) body=[$(Get-SafeBody $closeDuplicate)]" `
    -Why '既有 DUPLICATE 写入路径必须不受本次改动影响（回归）' -Result $closeDuplicate
Add-KeyAction -Order 5 -Actor 'it' -Action 'close.duplicate' -Method 'POST' `
    -Path "/fd/v1/tickets/$ticketDuplicate/actions/close" -TicketNo $ticketDuplicate `
    -Result $closeDuplicate -Step 'a5.close.duplicate'

Assert-Db -Name 'a5.db.duplicate.relationWritten' `
    -Sql ("SELECT relation_type, IFNULL(created_by,-1), IFNULL(target_ticket_id,-1) FROM ticket_relation " +
    "WHERE source_ticket_id = $duplicateId;") `
    -Expected "DUPLICATE / createdBy=$itUserId / target=$originalId" `
    -Why '重复关闭的既有方向（source=被关闭的本单）必须保持不变' `
    -Check { param($columns)
        ($columns[0] -eq 'DUPLICATE') -and ([int]$columns[1] -eq $itUserId) -and ([int]$columns[2] -eq $originalId) }
Assert-Db -Name 'a5.db.duplicate.closeFields' `
    -Sql "SELECT status, IFNULL(close_method,'NULL'), IFNULL(close_reason,'NULL') FROM ticket WHERE id = $duplicateId;" `
    -Expected 'CLOSED / MANUAL / DUPLICATE' -Why '关闭字段与状态不因本次改动漂移' `
    -Check { param($columns) ($columns[0] -eq 'CLOSED') -and ($columns[1] -eq 'MANUAL') -and ($columns[2] -eq 'DUPLICATE') }

# 共存与固定排序：原工单视角此时应返回 3 条——两条入向 FOLLOW_UP（a2、a4 各一条）+ 一条入向 DUPLICATE
$detailOriginalSorted = Send-Req -Client $employeeClient -Method Get -Path "/fd/v1/tickets/$ticketOriginal" `
    -Token $employeeToken -Step 'a5.detail.originalSorted' -Actor 'employee' `
    -Note '原工单详情：后续与重复两类入向关联，按"后续在前、重复在后"排序'
$sortedRelations = @((Get-Data $detailOriginalSorted).relations)
$sortedTypes = @($sortedRelations | ForEach-Object { $_.relationType })
$sortedDirections = @($sortedRelations | ForEach-Object { $_.direction })
Add-Assertion -Name 'a5.detail.relations.count' -Condition ($sortedRelations.Count -eq 3) `
    -Expected '3 条（a2/a4 两条 FOLLOW_UP + a5 一条 DUPLICATE）' `
    -Actual "count=$($sortedRelations.Count) types=$(($sortedTypes -join ','))" `
    -Why '两类关联共存说明唯一键 (source, relation_type) 按预期工作，不是"一张单只能有一条关联"' `
    -Result $detailOriginalSorted
Add-Assertion -Name 'a5.detail.relations.fixedOrder' `
    -Condition (($sortedTypes.Count -eq 3) -and ($sortedTypes[0] -eq 'FOLLOW_UP') -and
    ($sortedTypes[1] -eq 'FOLLOW_UP') -and ($sortedTypes[2] -eq 'DUPLICATE')) `
    -Expected 'FOLLOW_UP, FOLLOW_UP, DUPLICATE' -Actual ("types=" + ($sortedTypes -join ',')) `
    -Why '排序是契约的一部分：后续关联在前、重复关联在后，界面分组才不会随数据变化' `
    -Result $detailOriginalSorted
Add-Assertion -Name 'a5.detail.relations.allIncoming' `
    -Condition (@($sortedDirections | Where-Object { $_ -ne 'INCOMING' }).Count -eq 0) `
    -Expected '全部 INCOMING' -Actual ("directions=" + ($sortedDirections -join ',')) `
    -Why '原工单是三组关系的 target，方向必须全部翻转' -Result $detailOriginalSorted

$script:duplicateLegacyInfo = [ordered]@{
    duplicateTicket = $ticketDuplicate
    duplicateId     = $duplicateId
    itUserId        = $itUserId
    relationTypes   = $sortedTypes
    directions      = $sortedDirections
}

# ── 10. a6 权限闸门：无 TICKET_CREATE 的账号 403；匿名详情 401 ───────────────

$loginTemp = Send-Req -Client $tempClient -Method Post -Path '/fd/v1/auth/login' `
    -Body @{ username = $tempUsername; password = $EmployeePassword } -Step 'login.tempViewer' `
    -Actor 'temp-viewer' -Note '临时账号登录（只持 TICKET_VIEW_OWN）'
$tempToken = ''
if ($loginTemp.Status -eq 200) { $tempToken = (Get-Data $loginTemp).accessToken }
Add-Assertion -Name 'a6.login.tempViewer.200' -Condition ($loginTemp.Status -eq 200) -Expected '200' `
    -Actual "status=$($loginTemp.Status) code=$(Get-Code $loginTemp)" `
    -Why '403 闸门需要真实登录的"低权限账号"；登录失败则该格无法进行' -Result $loginTemp

if ($tempToken -ne '') {
    $createByTemp = Send-CreateTicket -Client $tempClient -Token $tempToken `
        -Title "$ticketTitlePrefix-无权限创建" -Description '没有 TICKET_CREATE 不能创建' `
        -CategoryId $categoryId -Step 'a6.create.byTempViewer' -Actor 'temp-viewer' `
        -Note '无 TICKET_CREATE 的账号创建工单'
    Add-Assertion -Name 'a6.create.byTempViewer.403' `
        -Condition (($createByTemp.Status -eq 403) -and ((Get-Code $createByTemp) -eq 'ACCESS_DENIED')) `
        -Expected '403 + ACCESS_DENIED' `
        -Actual "status=$($createByTemp.Status) code=$(Get-Code $createByTemp)" `
        -Why '方法级授权先于请求体语义；没有创建权限的账号连"原工单是否可见"都不该被判断' `
        -Result $createByTemp
    Assert-Db -Name 'a6.db.noTicketFromForbiddenCreate' `
        -Sql "SELECT COUNT(*) FROM ticket WHERE title LIKE '$ticketTitlePrefix-无权限创建%';" `
        -Expected '0' -Why '被 403 的创建不落任何数据' `
        -Check { param($columns) ([int]$columns[0] -eq 0) }

    $tempDetailOwn = Send-Req -Client $tempClient -Method Get -Path "/fd/v1/tickets/$foreignTicketNo" `
        -Token $tempToken -Step 'a6.detail.tempOwnTicket' -Actor 'temp-viewer' `
        -Note '临时账号查看自己的夹具工单（证明 400 不是"编号打错"）'
    Add-Assertion -Name 'a6.detail.tempOwnTicket.200' -Condition ($tempDetailOwn.Status -eq 200) `
        -Expected '200（夹具工单对它的提交人可见）' `
        -Actual "status=$($tempDetailOwn.Status) code=$(Get-Code $tempDetailOwn)" `
        -Why '夹具工单真实存在且可被它自己的提交人看到，因此员工引用它得到 400 是"不可见"而不是"不存在"' `
        -Result $tempDetailOwn
}

$anonDetail = Send-Req -Client $anonymousClient -Method Get -Path "/fd/v1/tickets/$ticketOriginal" `
    -Token '' -Step 'a6.detail.anonymous' -Actor 'anonymous' -Note '无令牌读取详情'
Add-Assertion -Name 'a6.detail.anonymous.401' `
    -Condition (($anonDetail.Status -eq 401) -and ((Get-Code $anonDetail) -eq 'AUTH_REQUIRED')) `
    -Expected '401 + AUTH_REQUIRED' `
    -Actual "status=$($anonDetail.Status) code=$(Get-Code $anonDetail)" `
    -Why '详情是工单正文的出口；匿名可读意味着数据泄露' -Result $anonDetail

# ── 11. a7 清理与指纹 ───────────────────────────────────────────────────────

$script:cleanupInfo['startedAt'] = (Get-Date).ToString('s')
$cleanupSql = @(
    "DELETE r FROM ticket_relation r JOIN ticket t ON t.id = r.source_ticket_id WHERE t.title LIKE '$ticketTitlePrefix-%';",
    "DELETE r FROM ticket_relation r JOIN ticket t ON t.id = r.target_ticket_id WHERE t.title LIKE '$ticketTitlePrefix-%';",
    "DELETE r FROM ticket_record r JOIN ticket t ON t.id = r.ticket_id WHERE t.title LIKE '$ticketTitlePrefix-%';",
    "DELETE p FROM ticket_participant p JOIN ticket t ON t.id = p.ticket_id WHERE t.title LIKE '$ticketTitlePrefix-%';",
    "DELETE FROM ticket WHERE title LIKE '$ticketTitlePrefix-%';",
    "DELETE ur FROM iam_user_role ur JOIN iam_user u ON u.id = ur.user_id WHERE u.username = '$tempUsername';",
    "DELETE rp FROM iam_role_permission rp JOIN iam_role r ON r.id = rp.role_id WHERE r.code = '$tempRoleCode';",
    "DELETE FROM iam_role WHERE code = '$tempRoleCode';",
    "DELETE FROM iam_user WHERE username = '$tempUsername';"
)
$cleanupResult = Invoke-MySql -Sql ($cleanupSql -join "`n")
Add-Assertion -Name 'a7.cleanup.sqlApplied' -Condition ($cleanupResult.exitCode -eq 0) `
    -Expected '退出码 0' -Actual "exitCode=$($cleanupResult.exitCode) stderr=[$(Get-BriefText $cleanupResult.stderrText 160)]" `
    -Why '本脚本创建的行必须由自己删干净（指纹对照的前提）' -Result $null

Assert-Db -Name 'a7.cleanup.noLeftoverTickets' `
    -Sql "SELECT COUNT(*) FROM ticket WHERE title LIKE '$ticketTitlePrefix-%' OR ticket_no = '$foreignTicketNo';" `
    -Expected '0' -Why '自建工单与夹具工单都必须删除' `
    -Check { param($columns) ([int]$columns[0] -eq 0) }
Assert-Db -Name 'a7.cleanup.noLeftoverPrincipals' `
    -Sql ("SELECT (SELECT COUNT(*) FROM iam_user WHERE username = '$tempUsername') + " +
    "(SELECT COUNT(*) FROM iam_role WHERE code = '$tempRoleCode');") `
    -Expected '0' -Why '临时用户与临时角色都必须删除（含它们的用户角色与角色权限）' `
    -Check { param($columns) ([int]$columns[0] -eq 0) }

$demoAfter = Get-DemoFingerprint
$fingerprintEqual = $false
if (($null -ne $demoBefore) -and ($null -ne $demoAfter)) {
    $fingerprintEqual = (($demoBefore.tickets -eq $demoAfter.tickets) -and
        ($demoBefore.records -eq $demoAfter.records) -and
        ($demoBefore.participants -eq $demoAfter.participants) -and
        ($demoBefore.relations -eq $demoAfter.relations) -and
        ($demoBefore.users -eq $demoAfter.users) -and
        ($demoBefore.userRoles -eq $demoAfter.userRoles) -and
        ($demoBefore.roles -eq $demoAfter.roles) -and
        ($demoBefore.rolePermissions -eq $demoAfter.rolePermissions) -and
        ($demoBefore.ticketNos -eq $demoAfter.ticketNos) -and
        ($demoBefore.ticketRows -eq $demoAfter.ticketRows) -and
        ($demoBefore.relationRows -eq $demoAfter.relationRows) -and
        ($demoBefore.userRows -eq $demoAfter.userRows) -and
        ($demoBefore.userRoleRows -eq $demoAfter.userRoleRows) -and
        ($demoBefore.roleRows -eq $demoAfter.roleRows) -and
        ($demoBefore.rolePermissionRows -eq $demoAfter.rolePermissionRows))
}
Add-Assertion -Name 'a7.fingerprint.unchanged' -Condition $fingerprintEqual `
    -Expected '运行前与清理后逐项相同（10 项计数 + 逐行指纹）' `
    -Actual ("before(tickets={0}) after(tickets={1}) relationsBefore={2} relationsAfter={3}" -f `
        $demoBefore.tickets, $demoAfter.tickets, $demoBefore.relations, $demoAfter.relations) `
    -Why '本脚本只允许动自己创建的行；指纹不同意味着动了运行前就存在的行' -Result $null

$script:cleanupInfo['sql'] = @($cleanupSql)
$script:cleanupInfo['finishedAt'] = (Get-Date).ToString('s')

# 临时主体的登记（进证据，便于核对清理范围）
$script:ticketsInfo = [ordered]@{
    original  = $ticketOriginal
    followUp  = $ticketFollowUp
    replay    = $ticketReplay
    duplicate = $ticketDuplicate
    foreign   = $foreignTicketNo
}

# ── 12. 证据与汇总 ──────────────────────────────────────────────────────────

$finishedAt = Get-Date
$failed = @($script:results | Where-Object { -not $_.passed })
$failedCount = $failed.Count
$totalCount = $script:results.Count
$passedCount = $totalCount - $failedCount
if ($failedCount -gt 0) { $script:quitCode = 1 }

$relativeScriptPath = 'scripts/ticket-relations-acceptance.ps1'
$relativeOutPath = 'docs/acceptance/2026-10-12-ticket-relations.json'
# 证据里的 evidencePath 只写仓库相对路径；用 GetFullPath 而不是 Resolve-Path——
# 首次运行时目标文件尚不存在，Resolve-Path 会失败，那会让"首跑"与"重跑"生成不同形状的证据。
$evidencePathForJson = $relativeOutPath
try {
    $repoRootFull = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
    $outFull = [System.IO.Path]::GetFullPath($OutFile)
    if ($outFull.StartsWith($repoRootFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        $evidencePathForJson = ($outFull.Substring($repoRootFull.Length).TrimStart('\', '/') -replace '\\', '/')
    }
} catch { $evidencePathForJson = $relativeOutPath }
# 记录本次真实用到的基址：脚本默认基址是 8081，关联代码在旧构建上不存在，
# 若 invocation 不带 -BaseUrl，照抄执行会打到没有该接口的后端。
$invocation = 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ticket-relations-acceptance.ps1 -BaseUrl ' + $baseUrl
$scriptLineCount = -1
try { $scriptLineCount = @(Get-Content -LiteralPath $MyInvocation.MyCommand.Path).Count } catch { $scriptLineCount = -1 }

$evidenceBuilt = $false
$evidenceBuildError = $null
$json = $null
try {
    $resultValue = 'FAIL'
    if ($failedCount -eq 0) { $resultValue = 'PASS' }

    $scriptEntry = [ordered]@{
        path              = $relativeScriptPath
        lines             = $scriptLineCount
        invocation        = $invocation
        powershellVersion = $PSVersionTable.PSVersion.ToString()
        exitCode          = $script:quitCode
        startedAt         = $startedAt.ToString('s')
        finishedAt        = $finishedAt.ToString('s')
        durationSeconds   = [math]::Round(($finishedAt - $startedAt).TotalSeconds, 1)
        baseUrl           = $baseUrl
        baseUrlSource     = 'param -BaseUrl / 环境变量 FLOWDESK_BASE_URL / 缺省 http://127.0.0.1:8081（PowerShell 5.1 没有 ??，用 param 默认值 + 兜底实现）'
        evidencePath      = $evidencePathForJson
    }
    $targetEntry = [ordered]@{
        baseUrl            = $baseUrl
        dbContainer        = $DbContainer
        database           = $dbName
        originHeader       = $originHeader
        accounts           = @($EmployeeUser, $ItUser)
        tempPrincipals     = @('viewer=只持 TICKET_VIEW_OWN（403 闸门 + 他人工单夹具）')
        endpointsUnderTest = @(
            'POST /fd/v1/tickets（sourceTicketNo 可选字段）',
            'GET  /fd/v1/tickets/{ticketNo}（relations[] 字段）',
            'POST /fd/v1/tickets/{ticketNo}/actions/close（DUPLICATE 关联回归）'
        )
        credentialsPrinted    = $false
        credentialsInEvidence = $false
    }
    $evidence = [ordered]@{
        _note = ('本文件由 scripts/ticket-relations-acceptance.ps1 写出（无 BOM 的 UTF-8）。' +
        '验收范围：完整版 backlog 2「工单关联」——创建后续工单的 sourceTicketNo（可见性校验、' +
        '带空白编号、无效引用统一 400、幂等重放）、详情 relations[]（OUTGOING/INCOMING 方向、' +
        '对端摘要四字段、固定排序、空数组）、既有 DUPLICATE 重复关闭路径的回归与两类关联共存，' +
        '以及匿名 401 / 无 TICKET_CREATE 403 两道闸门。' +
        '只记录状态码、业务码、traceId、断言结论与 SQL；不含口令、密钥与 accessToken，也不含机器绝对路径。')
        stage              = '完整版 backlog 2：工单关联真实栈验收'
        slice              = 'relations'
        date               = $startedAt.ToString('yyyy-MM-dd')
        result             = $resultValue
        invocation         = $invocation
        script             = $scriptEntry
        target             = $targetEntry
        preflight          = $script:preflightInfo
        tempPrincipals     = $script:tempPrincipalInfo
        relationFacts      = $script:relationFactsInfo
        duplicateLegacy    = $script:duplicateLegacyInfo
        tickets            = $script:ticketsInfo
        fingerprints       = [ordered]@{ before = $demoBefore; after = $demoAfter }
        keyActions         = $script:keyActions.ToArray()
        dbChecks           = $script:dbChecks.ToArray()
        assertions         = $script:results.ToArray()
        httpLog            = $script:httpLog.ToArray()
        sqlExecuted        = $script:executedSql.ToArray()
        cleanup            = $script:cleanupInfo
        totals             = [ordered]@{ total = $totalCount; passed = $passedCount; failed = $failedCount }
    }
    $json = $evidence | ConvertTo-Json -Depth 12
    $evidenceBuilt = $true
} catch {
    $script:quitCode = 1
    $evidenceBuildError = $_.Exception.Message
    Write-Host ("[FAIL] 证据对象构建失败：{0}" -f $evidenceBuildError) -ForegroundColor Red
}

if (-not $evidenceBuilt) {
    $fallbackEvidence = [ordered]@{
        _note      = '本文件由回退路径写出：证据对象构建或序列化失败，断言明细仍在，其余字段缺失。'
        stage      = '完整版 backlog 2：工单关联真实栈验收'
        result     = 'FAIL'
        error      = $evidenceBuildError
        assertions = $script:results.ToArray()
        totals     = [ordered]@{ total = $totalCount; passed = $passedCount; failed = $failedCount }
        cleanup    = $script:cleanupInfo
    }
    $json = $fallbackEvidence | ConvertTo-Json -Depth 12
}

[System.IO.File]::WriteAllText($OutFile, $json, (New-Object System.Text.UTF8Encoding($false)))

$writtenBytes = [System.IO.File]::ReadAllBytes($OutFile)
$hasBom = ($writtenBytes.Length -ge 3 -and $writtenBytes[0] -eq 0xEF -and $writtenBytes[1] -eq 0xBB -and $writtenBytes[2] -eq 0xBF)
$parsedBack = $null
try { $parsedBack = [System.IO.File]::ReadAllText($OutFile) | ConvertFrom-Json } catch { $parsedBack = $null }

Write-Host ''
Write-Host '── 汇总 ──────────────────────────────────────────────'
Write-Host ("阶段：工单关联；目标后端：{0}" -f $baseUrl)
Write-Host ("脚本：{0}（{1} 行）；断言 {2} 项，通过 {3}，失败 {4}" -f `
        $relativeScriptPath, $scriptLineCount, $totalCount, $passedCount, $failedCount)
Write-Host '关键动作（状态码 / X-Trace-Id）：'
foreach ($step in $script:keyActions) {
    Write-Host ("  {0}. {1,-16} {2,-22} {3,-20} => {4} traceId={5}" -f `
            $step.order, $step.actor, $step.action, $step.ticketNo, $step.httpStatus, $step.traceId)
}
Write-Host '数据库直查：'
foreach ($check in $script:dbChecks) {
    $mark = 'FAIL'
    if ($check.passed) { $mark = 'PASS' }
    Write-Host ("  [{0}] {1} :: {2}" -f $mark, $check.name, $check.raw)
}
if ($null -ne $demoBefore) {
    Write-Host ("演示库计数：运行前 工单={0}/记录={1}/参与者={2}/关联={3}/用户={4}/用户角色={5}/角色={6}/角色权限={7}" -f `
            $demoBefore.tickets, $demoBefore.records, $demoBefore.participants, $demoBefore.relations, `
            $demoBefore.users, $demoBefore.userRoles, $demoBefore.roles, $demoBefore.rolePermissions)
}
if ($null -ne $demoAfter) {
    Write-Host ("            运行后 工单={0}/记录={1}/参与者={2}/关联={3}/用户={4}/用户角色={5}/角色={6}/角色权限={7}" -f `
            $demoAfter.tickets, $demoAfter.records, $demoAfter.participants, $demoAfter.relations, `
            $demoAfter.users, $demoAfter.userRoles, $demoAfter.roles, $demoAfter.rolePermissions)
}
Write-Host ("证据：{0}（无 BOM UTF-8={1}，可回读解析={2}，{3} 字节）" -f `
        $relativeOutPath, (-not $hasBom), ($null -ne $parsedBack), $writtenBytes.Length)
Write-Host '本次执行过的清理 SQL（脚本已执行）：'
foreach ($statement in @($script:cleanupInfo['sql'])) { Write-Host "  $statement" }
if ($failedCount -gt 0) {
    Write-Host '失败项：' -ForegroundColor Red
    foreach ($item in $failed) {
        Write-Host ("  - {0}" -f $item.name) -ForegroundColor Red
        Write-Host ("      期望：{0}" -f $item.expected) -ForegroundColor Red
        Write-Host ("      实际：{0}" -f $item.actual) -ForegroundColor Red
        Write-Host ("      为什么重要：{0}" -f $item.why) -ForegroundColor Red
    }
}
Write-Host ("退出码：{0}" -f $script:quitCode)
exit $script:quitCode