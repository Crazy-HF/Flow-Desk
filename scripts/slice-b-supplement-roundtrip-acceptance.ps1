# 完整工单状态机「片 B：补充往返」真实栈验收
#   request-supplement（当前负责人请求员工补充）  +  supplement（提交人补充信息，正文版）
#
# 前置
#   · docker compose up -d mysql redis（本脚本只对既有 mysql 容器执行 docker exec，不碰容器生命周期）
#   · 目标后端必须是**已包含片 B 两个端点的最新代码**，由用户自己启动。脚本只发 HTTP 请求，
#     不启动、不重启、不结束任何后端进程。
#
# 运行
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\slice-b-supplement-roundtrip-acceptance.ps1
#   基址可覆盖（默认 http://127.0.0.1:8081，并行后端常用 8092）：
#     $env:FLOWDESK_BASE_URL = 'http://127.0.0.1:8092'   # 或 -BaseUrl http://127.0.0.1:8092
#   演示账号可用 E2E_EMPLOYEE_USERNAME / E2E_EMPLOYEE_PASSWORD、E2E_IT_*、E2E_ADMIN_* 覆盖
#   （缺省 employee / it / admin，口令 123456）。
#
# 本文件必须保存为「带 BOM 的 UTF-8」：Windows PowerShell 5.1 会按系统代码页读取无 BOM 的
# UTF-8 脚本，中文注释会被解析成乱码并报语法错误。证据文件相反，必须写成**无 BOM 的 UTF-8**。
#
# 证据
#   docs/acceptance/2026-10-07-slice-b-supplement-roundtrip.json（无 BOM 的 UTF-8，脚本自己写出）
#   含：脚本命令与退出码、$baseUrl、关键动作的状态码与 traceId、逐条断言（名称/期望/实际状态码/
#   X-Trace-Id/通过与否）、全部 HTTP 调用日志、数据库直查结果、清理前后演示库计数与指纹。
#
# 与片 A 脚本的关系
#   · 片 A 的 `withdraw-supplement-request` 当时只能靠 SQL 临时把工单置为 WAITING_FOR_REQUESTER，
#     因为进入该状态的唯一入口 `request-supplement` 属于片 B。本脚本**不再使用任何 SQL 造数**：
#     「待补充」全程由 `request-supplement` 经接口进入，因此它同时取代了片 A 脚本里那段临时手段。
#   · 片 A 的证据文件与其脚本原件按项目惯例不改写，保留当时的真实形态。
#
# 安全边界
#   · 只创建并删除脚本自己创建的工单、记录与参与关系；不触碰运行前就存在的任何行
#     （运行前后各测一次演示库指纹并断言完全相同，含既有工单 FD-20261006-026）
#   · 不执行 docker compose down、不删容器、不删卷、不停任何后端进程；数据库访问一律是
#     `docker exec -i <mysql 容器> mysql ...`（容器名与库名来自仓库根 .env 与既有脚本约定）
#   · 演示库 root 口令只从 .env 读进进程环境变量、经 `docker exec -e MYSQL_PWD` 转发，
#     不出现在命令行、不打印、不写进证据；登录口令与 accessToken 同样不打印、不落盘
#   · 断言只记录状态码、业务码、traceId 与布尔结论，不回显令牌与口令
#
# SQL 通过**临时 .sql 文件 + stdin** 送入 mysql 客户端，输出重定向到临时文件后按 UTF-8 读取：
#   ① 中文字面量与含中文的结果都不经过控制台代码页，避免断言误判；
#   ② 口令不进命令行，mysql 也不会打印 "Using a password on the command line" 警告；
#   ③ mysql 客户端统一带 --default-character-set=utf8mb4，否则读回的中文会变成 "?"。

param(
    # 基址可覆盖：PowerShell 5.1 没有 ?? 运算符，用 param 默认值 + 下方兜底实现同样语义
    [string]$BaseUrl = $env:FLOWDESK_BASE_URL,
    [string]$OutFile = (Join-Path $PSScriptRoot '..\docs\acceptance\2026-10-07-slice-b-supplement-roundtrip.json'),
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'),
    [string]$DbContainer = 'flowdesk-mysql-1',
    [string]$EmployeeUser = $env:E2E_EMPLOYEE_USERNAME,
    [string]$EmployeePassword = $env:E2E_EMPLOYEE_PASSWORD,
    [string]$ItUser = $env:E2E_IT_USERNAME,
    [string]$ItPassword = $env:E2E_IT_PASSWORD,
    [string]$AdminUser = $env:E2E_ADMIN_USERNAME,
    [string]$AdminPassword = $env:E2E_ADMIN_PASSWORD
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
$sqlWorkDir = Join-Path $env:TEMP "flowdesk-slice-b-$stamp"
New-Item -ItemType Directory -Path $sqlWorkDir -Force | Out-Null

$script:sqlCounter = 0
$script:results = [System.Collections.Generic.List[object]]::new()
$script:dbChecks = [System.Collections.Generic.List[object]]::new()
$script:keyActions = [System.Collections.Generic.List[object]]::new()
$script:httpLog = [System.Collections.Generic.List[object]]::new()
$script:notes = [System.Collections.Generic.List[string]]::new()
$script:observed = [System.Collections.Generic.List[string]]::new()
$script:executedSql = [System.Collections.Generic.List[string]]::new()
$script:quitCode = 0
$script:dbAvailable = $false
$script:cleanupInfo = [ordered]@{}
$script:preflightInfo = [ordered]@{}
$script:windowInfo = [ordered]@{}

if ([string]::IsNullOrWhiteSpace($EmployeeUser)) { $EmployeeUser = 'employee' }
if ([string]::IsNullOrWhiteSpace($EmployeePassword)) { $EmployeePassword = '123456' }
if ([string]::IsNullOrWhiteSpace($ItUser)) { $ItUser = 'it' }
if ([string]::IsNullOrWhiteSpace($ItPassword)) { $ItPassword = '123456' }
if ([string]::IsNullOrWhiteSpace($AdminUser)) { $AdminUser = 'admin' }
if ([string]::IsNullOrWhiteSpace($AdminPassword)) { $AdminPassword = '123456' }

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

function Send-Req {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Method,
        [string]$Path,
        [string]$Token,
        $Body,
        [string]$Actor = '-',
        [string]$Note = '-'
    )
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::$Method, $Path)
    try {
        $request.Headers.Add('Origin', $originHeader)
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
                actor   = $Actor
                note    = $Note
                method  = $Method
                path    = $Path
                status  = $result.Status
                traceId = $result.TraceId
            })
        return $result
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
        [long]$CategoryId,
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
        $result = [pscustomobject]@{
            Status  = [int]$response.StatusCode
            Body    = $text
            TraceId = (Get-TraceId $response)
        }
        $script:httpLog.Add([pscustomobject]@{
                actor   = $Actor
                note    = $Note
                method  = 'POST'
                path    = '/fd/v1/tickets'
                status  = $result.Status
                traceId = $result.TraceId
            })
        return $result
    } finally {
        $request.Dispose()
    }
}

# 片 B 的 supplement 是 multipart/form-data：ticket 部件携带 JSON，必须自带 application/json。
# $WithFilePart 为真时额外挂一个文件 part，用来证明服务端显式 400 而不是静默忽略。
function Send-Supplement {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Token,
        [string]$TicketNo,
        [long]$Version,
        [string]$Content,
        [switch]$WithFilePart,
        [switch]$EmptyFilePart,
        [string]$Actor = 'employee',
        [string]$Note = 'supplement'
    )
    $payload = @{ version = $Version; content = $Content } | ConvertTo-Json -Compress
    # 变量名不能叫 $content：PowerShell 变量名不区分大小写，它会与参数 $Content 同名，
    # 赋值会把参数的类型槽改成 String，随后的 .Add(...) 直接抛 "does not contain a method named 'Add'"。
    $form = New-Object System.Net.Http.MultipartFormDataContent
    $form.Add((New-Object System.Net.Http.StringContent(
                $payload, [System.Text.Encoding]::UTF8, 'application/json')), 'ticket')
    if ($WithFilePart) {
        # 不能用 New-Object System.Net.Http.ByteArrayContent($bytes)：PowerShell 会把 $bytes 的每个元素
        # 当成一个构造参数（实测报 "argument count: 4"），因此显式用 -ArgumentList 传单个数组。
        [byte[]]$fileBytes = 1, 2, 3, 4
        if ($EmptyFilePart) { [byte[]]$fileBytes = @() }
        $fileContent = New-Object System.Net.Http.ByteArrayContent -ArgumentList (, $fileBytes)
        $fileContent.Headers.ContentType =
        New-Object System.Net.Http.Headers.MediaTypeHeaderValue('image/png')
        $form.Add($fileContent, 'files', 'screenshot.png')
    }
    $path = "/fd/v1/tickets/$TicketNo/actions/supplement"
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, $path)
    try {
        $request.Headers.Add('Origin', $originHeader)
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
                actor   = $Actor
                note    = $Note
                method  = 'POST'
                path    = $path
                status  = $result.Status
                traceId = $result.TraceId
            })
        return $result
    } finally {
        $request.Dispose()
    }
}

function Get-Data {
    param($Result)
    try { return ($Result.Body | ConvertFrom-Json).data } catch { return $null }
}

function Get-Code {
    param($Result)
    if ($null -eq $Result) { return $null }
    try { return ($Result.Body | ConvertFrom-Json).code } catch { return $null }
}

# 409 的 data 里带当前快照（version / status），供前端刷新后重试。
function Get-ErrData {
    param($Result)
    if ($null -eq $Result) { return $null }
    try { return ($Result.Body | ConvertFrom-Json).data } catch { return $null }
}

# 逐条断言：名称、期望、实际状态码、X-Trace-Id、通过与否。$Result 可空（非 HTTP 断言）。
function Add-Assertion {
    param(
        [string]$Name,
        [bool]$Condition,
        [string]$Expected,
        [string]$Detail,
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
            httpStatus = $httpStatus
            traceId    = $traceId
            passed     = $Condition
            detail     = $Detail
        })
    $mark = 'FAIL'
    if ($Condition) { $mark = 'PASS' }
    Write-Host ("[{0}] {1} :: {2}" -f $mark, $Name, $Detail)
}

# 数据库直查断言：SQL、原始结果行、期望与结论一起进证据。
function Assert-Db {
    param(
        [string]$Name,
        [string]$Sql,
        [string]$Expected,
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
    Add-Assertion -Name $Name -Condition $passed -Expected $Expected -Detail $detail
}

function Get-BriefText {
    param([string]$Text, [int]$Max = 40)
    if ($null -eq $Text) { return '(null)' }
    if ($Text.Length -le $Max) { return $Text }
    return ($Text.Substring(0, $Max) + '…（共 ' + $Text.Length + ' 字符）')
}

# 「期限字段不出现」：spring.jackson.default-property-inclusion=non_null，
# 值为 null 的 actionDeadlineAt 根本不参与序列化，所以断言原始报文里连字段名都没有。
function Test-DeadlineFieldAbsent {
    param($Result)
    if ($null -eq $Result) { return $false }
    return (-not ($Result.Body -match 'actionDeadlineAt'))
}

# 工单快照：status | action_deadline_at | ended_at | assignee_id | completion_method | version | record_seq | requester_id
function Get-TicketDbRow {
    param([string]$TicketNo)
    $sql = "SELECT status, " +
    "IFNULL(DATE_FORMAT(action_deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'), " +
    "IF(ended_at IS NULL,'NULL','SET'), IFNULL(assignee_id,-1), IFNULL(completion_method,'NULL'), " +
    "version, record_seq, requester_id FROM ticket WHERE ticket_no = '$TicketNo';"
    $row = Get-MySqlRow -Sql $sql
    if (-not $row.ok) { return $null }
    $columns = @($row.columns)
    if ($columns.Count -lt 8) { return $null }
    return [pscustomobject]@{
        status           = $columns[0]
        actionDeadlineAt = $columns[1]
        endedAt          = $columns[2]
        assigneeId       = [int]$columns[3]
        completionMethod = $columns[4]
        version          = [int]$columns[5]
        recordSeq        = [int]$columns[6]
        requesterId      = [int]$columns[7]
        raw              = $row.raw
    }
}

# 时间线直查（正文只取前 40 个字符 + 实际字符数，避免长文本把证据撑爆）。
function Get-TicketRecordDbRows {
    param([string]$TicketNo)
    $sql = "SELECT sequence_no, record_type, actor_type, IFNULL(actor_user_id,-1), " +
    "IFNULL(LEFT(content,40),'-'), IFNULL(CHAR_LENGTH(content),-1), " +
    "IFNULL(LEFT(reason,40),'-'), IFNULL(CHAR_LENGTH(reason),-1), " +
    "IFNULL(from_status,'-'), IFNULL(to_status,'-'), " +
    "IFNULL(DATE_FORMAT(deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL') " +
    "FROM ticket_record WHERE ticket_id = (SELECT id FROM ticket WHERE ticket_no = '$TicketNo') " +
    "ORDER BY sequence_no;"
    $result = Invoke-MySql -Sql $sql
    $rows = [System.Collections.Generic.List[object]]::new()
    if ($result.exitCode -ne 0) { return @() }
    foreach ($line in $result.lines) {
        $columns = @($line -split "`t")
        if ($columns.Count -lt 11) { continue }
        $rows.Add([pscustomobject]@{
                sequenceNo = [int]$columns[0]
                recordType = $columns[1]
                actorType  = $columns[2]
                actorId    = [int]$columns[3]
                content    = $columns[4]
                contentLen = [int]$columns[5]
                reason     = $columns[6]
                reasonLen  = [int]$columns[7]
                fromStatus = $columns[8]
                toStatus   = $columns[9]
                deadlineAt = $columns[10]
            })
    }
    return $rows.ToArray()
}

# 演示库指纹：运行前与清理后各测一次，断言完全相同（既有行一行都不许变）。
function Get-DemoFingerprint {
    $countsSql = "SELECT (SELECT COUNT(*) FROM iam_user), (SELECT COUNT(*) FROM iam_role), " +
    "(SELECT COUNT(*) FROM iam_permission), (SELECT COUNT(*) FROM ticket_category), " +
    "(SELECT COUNT(*) FROM ticket), (SELECT COUNT(*) FROM ticket_record), " +
    "(SELECT COUNT(*) FROM ticket_participant);"
    $countsRow = Get-MySqlRow -Sql $countsSql
    $counts = @($countsRow.columns)
    $ticketNos = Get-MySqlRow -Sql "SELECT IFNULL(GROUP_CONCAT(ticket_no ORDER BY id SEPARATOR ','),'-') FROM ticket;"
    $ticketRows = Get-MySqlRow -Sql (
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(ticket_no,'|',status,'|',version,'|',record_seq,'|'," +
        "IFNULL(assignee_id,-1),'|',IFNULL(DATE_FORMAT(action_deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'),'|'," +
        "IF(ended_at IS NULL,'NULL','SET'),'|',IFNULL(completion_method,'NULL'),'|'," +
        "DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f')) ORDER BY id SEPARATOR ' ;; '),'-') FROM ticket;")
    $recordRows = Get-MySqlRow -Sql (
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(id,'|',ticket_id,'|',sequence_no,'|',record_type,'|',actor_type,'|'," +
        "IFNULL(actor_user_id,-1),'|',DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f')) " +
        "ORDER BY id SEPARATOR ' ;; '),'-') FROM ticket_record;")
    $participantRows = Get-MySqlRow -Sql (
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(ticket_id,'|',user_id,'|'," +
        "DATE_FORMAT(first_assigned_at,'%Y-%m-%d %H:%i:%s.%f')) ORDER BY ticket_id,user_id SEPARATOR ' ;; '),'-') " +
        "FROM ticket_participant;")
    # ticket_daily_sequence 是既有行，但通过接口建单必然让它递增（编号不复用），
    # 因此只记录不断言，也不回退——回退就是改动运行前就存在的行。
    $dailySequence = Get-MySqlRow -Sql (
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(business_date,'=',current_value) ORDER BY business_date SEPARATOR ' ;; '),'-') " +
        "FROM ticket_daily_sequence;")

    $fingerprint = [ordered]@{
        at              = (Get-Date).ToString('s')
        users           = -1
        roles           = -1
        permissions     = -1
        categories      = -1
        tickets         = -1
        records         = -1
        participants    = -1
        ticketNos       = $ticketNos.raw
        ticketRows      = $ticketRows.raw
        recordRows      = $recordRows.raw
        participantRows = $participantRows.raw
        dailySequence   = $dailySequence.raw
        readOk          = $countsRow.ok
    }
    if ($countsRow.ok -and $counts.Count -ge 7) {
        $fingerprint.users = [int]$counts[0]
        $fingerprint.roles = [int]$counts[1]
        $fingerprint.permissions = [int]$counts[2]
        $fingerprint.categories = [int]$counts[3]
        $fingerprint.tickets = [int]$counts[4]
        $fingerprint.records = [int]$counts[5]
        $fingerprint.participants = [int]$counts[6]
    }
    return [pscustomobject]$fingerprint
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
        [string]$StatusAfter
    )
    $version = $null
    if ($null -ne $Result) {
        $data = Get-Data $Result
        if ($null -ne $data) { $version = $data.version }
    }
    $script:keyActions.Add([pscustomobject]@{
            order        = $Order
            actor        = $Actor
            action       = $Action
            method       = $Method
            path         = $Path
            ticketNo     = $TicketNo
            httpStatus   = $Result.Status
            traceId      = $Result.TraceId
            statusAfter  = $StatusAfter
            versionAfter = $version
        })
}

# 片 B 两个端点的请求体：JSON 动作带版本 + 正文；multipart 由 Send-Supplement 负责。
function New-ContentBody {
    param([long]$Version, [string]$Content)
    return @{ version = $Version; content = $Content }
}

$employeeClient = New-Client
$itClient = New-Client
$adminClient = New-Client
$anonymousClient = New-Client
$clients = @($employeeClient, $itClient, $adminClient, $anonymousClient)

$ticketA = ''
$ticketB = ''
$ticketC = ''
$unknownTicketNo = 'FD-19990101-001'
$ticketsInfo = [ordered]@{}
$demoBefore = $null
$demoAfter = $null

try {
    # ── 2. 演示库运行前指纹（只读；清理后必须逐字段相同） ───────────────────
    Add-Assertion -Name 'precondition.dotEnvLoaded' -Condition $script:dbAvailable `
        -Expected '.env 中 FLOWDESK_DB_NAME 与 FLOWDESK_MYSQL_ROOT_PASSWORD 可读' `
        -Detail "dbContainer=$DbContainer dbNameAvailable=$(-not [string]::IsNullOrWhiteSpace($dbName)) rootPasswordAvailable=$(-not [string]::IsNullOrWhiteSpace($rootPassword))（值不打印）"
    if (-not $script:dbAvailable) {
        throw "无法从 $EnvFile 读取演示库连接信息（FLOWDESK_DB_NAME / FLOWDESK_MYSQL_ROOT_PASSWORD），数据库直查与清理无法进行"
    }

    $demoBefore = Get-DemoFingerprint
    Add-Assertion -Name 'baseline.demoDatabaseReadBeforeRun' -Condition $demoBefore.readOk `
        -Expected '运行前可读取演示库计数' `
        -Detail ("运行前：用户 {0} / 角色 {1} / 权限 {2} / 分类 {3} / 工单 {4} / 记录 {5} / 参与者 {6}；工单号=[{7}]" -f `
            $demoBefore.users, $demoBefore.roles, $demoBefore.permissions, $demoBefore.categories, `
            $demoBefore.tickets, $demoBefore.records, $demoBefore.participants, $demoBefore.ticketNos)
    Add-Assertion -Name 'baseline.existingTicketsRecorded' -Condition ($demoBefore.ticketNos -ne '-') `
        -Expected '运行前演示库里已有工单（集合被完整记录，清理后必须逐行不变）' `
        -Detail "工单号=[$($demoBefore.ticketNos)]；本脚本只新增再删除自己的行，绝不修改运行前就存在的任何行"
    Write-Host "演示库基线：$($demoBefore.ticketNos)（工单 $($demoBefore.tickets) / 记录 $($demoBefore.records) / 参与者 $($demoBefore.participants)）"
    Write-Host "目标后端：$baseUrl（脚本只发 HTTP，不启动/不重启/不结束任何后端进程）"

    # ── 3. 三角色登录与身份自检 ─────────────────────────────────────────────
    $loginEmployee = Send-Req -Client $employeeClient -Method Post -Path '/fd/v1/auth/login' `
        -Body @{ username = $EmployeeUser; password = $EmployeePassword } -Actor 'employee' -Note 'login'
    $loginIt = Send-Req -Client $itClient -Method Post -Path '/fd/v1/auth/login' `
        -Body @{ username = $ItUser; password = $ItPassword } -Actor 'it' -Note 'login'
    $loginAdmin = Send-Req -Client $adminClient -Method Post -Path '/fd/v1/auth/login' `
        -Body @{ username = $AdminUser; password = $AdminPassword } -Actor 'admin' -Note 'login'
    Add-Assertion -Name 'login.employee.200' -Condition ($loginEmployee.Status -eq 200) -Expected '200' `
        -Detail "status=$($loginEmployee.Status)" -Result $loginEmployee
    Add-Assertion -Name 'login.it.200' -Condition ($loginIt.Status -eq 200) -Expected '200' `
        -Detail "status=$($loginIt.Status)" -Result $loginIt
    Add-Assertion -Name 'login.admin.200' -Condition ($loginAdmin.Status -eq 200) -Expected '200' `
        -Detail "status=$($loginAdmin.Status)" -Result $loginAdmin
    if (($loginEmployee.Status -ne 200) -or ($loginIt.Status -ne 200) -or ($loginAdmin.Status -ne 200)) {
        throw "三角色登录未全部返回 200（employee=$($loginEmployee.Status) it=$($loginIt.Status) admin=$($loginAdmin.Status)）；口令错误或账号被改，请用 E2E_* 覆盖或在演示库核对账号"
    }
    $employeeToken = (Get-Data $loginEmployee).accessToken
    $itToken = (Get-Data $loginIt).accessToken
    $adminToken = (Get-Data $loginAdmin).accessToken
    Add-Assertion -Name 'login.tokensIssued' `
        -Condition ((-not [string]::IsNullOrEmpty($employeeToken)) -and (-not [string]::IsNullOrEmpty($itToken)) -and (-not [string]::IsNullOrEmpty($adminToken))) `
        -Expected '三条 accessToken 均已取得' -Detail '令牌不落盘、不打印、不写进证据'

    $meEmployee = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/auth/me' -Token $employeeToken -Actor 'employee' -Note 'me'
    $meIt = Send-Req -Client $itClient -Method Get -Path '/fd/v1/auth/me' -Token $itToken -Actor 'it' -Note 'me'
    $meAdmin = Send-Req -Client $adminClient -Method Get -Path '/fd/v1/auth/me' -Token $adminToken -Actor 'admin' -Note 'me'
    $employeeData = Get-Data $meEmployee
    $itData = Get-Data $meIt
    $adminData = Get-Data $meAdmin
    $employeeUserId = [int]$employeeData.id
    $itUserId = [int]$itData.id
    # admin 的 id 也要取：断言 4 需要为它造一条参与关系，否则 admin 看不见工单，只能测到 404 而测不到身份闸门
    $adminUserId = [int]$adminData.id
    $employeePerms = @($employeeData.permissions)
    $itPerms = @($itData.permissions)
    $adminPerms = @($adminData.permissions)

    Add-Assertion -Name 'perm.employee.hasRequesterAction' -Condition ($employeePerms -contains 'TICKET_REQUESTER_ACTION') `
        -Expected 'employee 具备 TICKET_REQUESTER_ACTION（supplement 的权限前提）' `
        -Detail "roles=$(@($employeeData.roles) -join '|') ticketPerms=$((@($employeePerms | Where-Object { $_ -like 'TICKET*' })) -join '|')" `
        -Result $meEmployee
    Add-Assertion -Name 'perm.employee.lacksProcess' -Condition (-not ($employeePerms -contains 'TICKET_PROCESS')) `
        -Expected 'employee 不具备 TICKET_PROCESS（request-supplement 403 的前提）' `
        -Detail "hasTICKET_PROCESS=$($employeePerms -contains 'TICKET_PROCESS')" -Result $meEmployee
    Add-Assertion -Name 'perm.it.hasProcess' -Condition ($itPerms -contains 'TICKET_PROCESS') `
        -Expected 'it 具备 TICKET_PROCESS（request-supplement 的权限前提）' `
        -Detail "roles=$(@($itData.roles) -join '|') hasTICKET_PROCESS=$($itPerms -contains 'TICKET_PROCESS')" -Result $meIt
    Add-Assertion -Name 'perm.it.lacksRequesterAction' -Condition (-not ($itPerms -contains 'TICKET_REQUESTER_ACTION')) `
        -Expected 'it 不具备 TICKET_REQUESTER_ACTION（supplement 403 的前提）' `
        -Detail "hasTICKET_REQUESTER_ACTION=$($itPerms -contains 'TICKET_REQUESTER_ACTION')" -Result $meIt
    $script:observed.Add("admin.roles=$(@($adminData.roles) -join '|')")
    $script:observed.Add("admin.hasTICKET_PROCESS=$($adminPerms -contains 'TICKET_PROCESS') hasTICKET_REQUESTER_ACTION=$($adminPerms -contains 'TICKET_REQUESTER_ACTION')")
    $script:notes.Add('admin 在本机演示库里同时持有 EMPLOYEE + IT_SUPPORT + SYSTEM_ADMIN（已知漂移，授权数据未被改动），脚本只记录它的实际结果，不做绝对值断言。')

    # ── 4. 前置闸门：片 B 两个端点必须真的挂在目标后端上 ─────────────────────
    $probeRequest = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$unknownTicketNo/actions/request-supplement" -Token $itToken `
        -Body (New-ContentBody -Version 0 -Content 'preflight') -Actor 'it' -Note 'preflight.request-supplement'
    $probeSupplement = Send-Supplement -Client $employeeClient -Token $employeeToken `
        -TicketNo $unknownTicketNo -Version 0 -Content 'preflight' -Actor 'employee' -Note 'preflight.supplement'
    $probeRequestMapped = (($probeRequest.Status -eq 404) -and ((Get-Code $probeRequest) -eq 'TICKET_NOT_FOUND'))
    $probeSupplementMapped = (($probeSupplement.Status -eq 404) -and ((Get-Code $probeSupplement) -eq 'TICKET_NOT_FOUND'))
    $script:preflightInfo = [ordered]@{
        baseUrl                     = $baseUrl
        requestSupplementStatus     = $probeRequest.Status
        requestSupplementCode       = (Get-Code $probeRequest)
        requestSupplementTraceId    = $probeRequest.TraceId
        supplementStatus            = $probeSupplement.Status
        supplementCode              = (Get-Code $probeSupplement)
        supplementTraceId           = $probeSupplement.TraceId
        mappingConfirmed            = ($probeRequestMapped -and $probeSupplementMapped)
    }
    Add-Assertion -Name 'preflight.requestSupplement.endpointMapped' -Condition $probeRequestMapped `
        -Expected '404 + TICKET_NOT_FOUND（编号不存在，端点存在）' `
        -Detail "status=$($probeRequest.Status) code=$(Get-Code $probeRequest)；若为 405/RESOURCE_NOT_FOUND 说明目标后端还没有该端点" `
        -Result $probeRequest
    Add-Assertion -Name 'preflight.supplement.endpointMapped' -Condition $probeSupplementMapped `
        -Expected '404 + TICKET_NOT_FOUND（编号不存在，端点存在）' `
        -Detail "status=$($probeSupplement.Status) code=$(Get-Code $probeSupplement)；415 说明 multipart 的 ticket 部件缺少 application/json" `
        -Result $probeSupplement
    if (-not ($probeRequestMapped -and $probeSupplementMapped)) {
        throw ("目标后端 $baseUrl 上没有片 B 的两个端点（或返回不符合契约）：request-supplement => " +
            "$($probeRequest.Status)/$(Get-Code $probeRequest)、supplement => " +
            "$($probeSupplement.Status)/$(Get-Code $probeSupplement)。请确认验收目标是**用最新代码启动的后端**。")
    }

    # ── 5. 断言 1：两个动作各一条匿名请求 → 401 ──────────────────────────────
    $anonRequest = Send-Req -Client $anonymousClient -Method Post `
        -Path "/fd/v1/tickets/$unknownTicketNo/actions/request-supplement" `
        -Body (New-ContentBody -Version 0 -Content '匿名') -Actor 'anonymous' -Note 'a1.anonymous.request-supplement'
    $anonSupplement = Send-Supplement -Client $anonymousClient -Token '' `
        -TicketNo $unknownTicketNo -Version 0 -Content '匿名' -Actor 'anonymous' -Note 'a1.anonymous.supplement'
    Add-Assertion -Name 'a1.anonymous.requestSupplement.401' -Condition ($anonRequest.Status -eq 401) `
        -Expected '401（无令牌一律先被认证闸门拦下）' `
        -Detail "status=$($anonRequest.Status) code=$(Get-Code $anonRequest)" -Result $anonRequest
    Add-Assertion -Name 'a1.anonymous.supplement.401' -Condition ($anonSupplement.Status -eq 401) `
        -Expected '401（无令牌一律先被认证闸门拦下）' `
        -Detail "status=$($anonSupplement.Status) code=$(Get-Code $anonSupplement)" -Result $anonSupplement

    # ── 6. 分类选项 + 三张工单（全部由接口创建，无 SQL 造数） ─────────────────
    $options = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/categories/options' -Token $employeeToken `
        -Actor 'employee' -Note 'categories.options'
    $optionItems = @(Get-Data $options)
    Add-Assertion -Name 'categories.options.nonEmpty' -Condition (($options.Status -eq 200) -and ($optionItems.Count -ge 1)) `
        -Expected '200 且至少一个启用分类' -Detail "status=$($options.Status) count=$($optionItems.Count)" -Result $options
    if ($optionItems.Count -lt 1) { throw '没有可用的启用分类，无法创建工单' }
    $categoryId = [long]$optionItems[0].id

    # A：正常往返（request-supplement → supplement → 再处理 → 再请求补充）
    $createA = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
        -Title "片B验收-补充往返-$stamp" -Description '片 B 真实栈验收：正常往返与期限重新计算。' `
        -CategoryId $categoryId -Actor 'employee' -Note 'setup.create-ticket(A)'
    $ticketA = (Get-Data $createA).ticketNo
    $versionA = [long](Get-Data $createA).version
    Add-Assertion -Name 'setup.ticketA.create.201' -Condition ($createA.Status -eq 201) -Expected '201' `
        -Detail "ticketNo=$ticketA version=$versionA" -Result $createA
    if ($createA.Status -ne 201) { throw "工单 A 创建失败（status=$($createA.Status) code=$(Get-Code $createA)），后续断言无法进行" }

    $claimA = Send-Req -Client $itClient -Method Post -Path "/fd/v1/tickets/$ticketA/actions/claim" -Token $itToken `
        -Body @{ version = $versionA } -Actor 'it' -Note 'setup.claim(A)'
    Add-Assertion -Name 'setup.ticketA.claim.200' -Condition ($claimA.Status -eq 200) -Expected '200' `
        -Detail "status=$((Get-Data $claimA).status) version=$((Get-Data $claimA).version)" -Result $claimA
    if ($claimA.Status -ne 200) { throw "工单 A 领取失败（status=$($claimA.Status) code=$(Get-Code $claimA)）" }
    $versionA = [long](Get-Data $claimA).version
    Add-KeyAction -Order 1 -Actor 'it' -Action 'claim' -Method 'POST' `
        -Path "/fd/v1/tickets/$ticketA/actions/claim" -TicketNo $ticketA -Result $claimA -StatusAfter 'PROCESSING'

    # B：权限与状态闸门用例的载体（处理中，由 it 负责）
    $createB = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
        -Title "片B验收-闸门载体-$stamp" -Description '片 B 真实栈验收：403 / 400 / 409 与文件 part 拒绝。' `
        -CategoryId $categoryId -Actor 'employee' -Note 'setup.create-ticket(B)'
    $ticketB = (Get-Data $createB).ticketNo
    $versionB = [long](Get-Data $createB).version
    Add-Assertion -Name 'setup.ticketB.create.201' -Condition ($createB.Status -eq 201) -Expected '201' `
        -Detail "ticketNo=$ticketB version=$versionB" -Result $createB
    $claimB = Send-Req -Client $itClient -Method Post -Path "/fd/v1/tickets/$ticketB/actions/claim" -Token $itToken `
        -Body @{ version = $versionB } -Actor 'it' -Note 'setup.claim(B)'
    Add-Assertion -Name 'setup.ticketB.claim.200' -Condition ($claimB.Status -eq 200) -Expected '200' `
        -Detail "status=$((Get-Data $claimB).status) version=$((Get-Data $claimB).version)" -Result $claimB
    if ($claimB.Status -ne 200) { throw "工单 B 领取失败（status=$($claimB.Status) code=$(Get-Code $claimB)）" }
    $versionB = [long](Get-Data $claimB).version

    $ticketsInfo['A'] = @{ ticketNo = $ticketA; purpose = '正常往返 + 期限重新计算 + 终态' }
    $ticketsInfo['B'] = @{ ticketNo = $ticketB; purpose = '权限 / 字段 / 版本冲突 / 文件 part 拒绝' }
    # ── 7. 断言 2：employee 调 request-supplement → 403（缺 TICKET_PROCESS） ──
    $empRequest = Send-Req -Client $employeeClient -Method Post `
        -Path "/fd/v1/tickets/$ticketB/actions/request-supplement" -Token $employeeToken `
        -Body (New-ContentBody -Version $versionB -Content '员工越权请求补充') -Actor 'employee' -Note 'a2.employee.requestSupplement'
    Add-Assertion -Name 'a2.employee.requestSupplement.403' `
        -Condition (($empRequest.Status -eq 403) -and ((Get-Code $empRequest) -eq 'TICKET_ACTION_FORBIDDEN')) `
        -Expected '403 + TICKET_ACTION_FORBIDDEN（权限闸门先于可见性与状态）' `
        -Detail "status=$($empRequest.Status) code=$(Get-Code $empRequest)" -Result $empRequest

    # ── 8. 断言 3：it 调 supplement → 403（缺 TICKET_REQUESTER_ACTION） ──────
    $itSupplement = Send-Supplement -Client $itClient -Token $itToken `
        -TicketNo $ticketB -Version $versionB -Content 'IT 越权补充' -Actor 'it' -Note 'a3.it.supplement'
    Add-Assertion -Name 'a3.it.supplement.403' `
        -Condition (($itSupplement.Status -eq 403) -and ((Get-Code $itSupplement) -eq 'TICKET_ACTION_FORBIDDEN')) `
        -Expected '403 + TICKET_ACTION_FORBIDDEN（权限闸门先于可见性与状态）' `
        -Detail "status=$($itSupplement.Status) code=$(Get-Code $itSupplement)" -Result $itSupplement

    # ── 9. 断言 4：可见但不是提交人 → 409（身份闸门） ────────────────────────
    # 目标是让"可见性"不再是障碍，只留身份判定：admin 在本机演示库同时持有三种角色，
    # 因此它有 TICKET_REQUESTER_ACTION（权限闸门会放行）。可见性方面，admin 对 employee 提交的
    # 工单本来不可见（实测 404/TICKET_NOT_FOUND），所以这里先给它补一条历史参与关系，
    # 使 TICKET_VIEW_PARTICIPATED 生效——这一步只写脚本自己创建的工单，随清理一并删除。
    # 列名里 first_assigned_at / last_assigned_at 必须加反引号：MySQL 8 把 ASSIGNED 当关键字，
    # 不加反引号实测报 ERROR 1064。反引号在 PowerShell 双引号串里是转义符，因此改用单引号段拼接。
    $participantSql = 'INSERT INTO ticket_participant (ticket_id, user_id, `first_assigned_at`, `last_assigned_at`) ' +
    'SELECT id, ' + $adminUserId + ', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM ticket WHERE ticket_no = ''' + $ticketB + ''';'
    $participantResult = Invoke-MySql -Sql $participantSql
    $participantAdded = ($participantResult.exitCode -eq 0)
    Add-Assertion -Name 'a4.setup.adminParticipantAdded' -Condition $participantAdded `
        -Expected '为 admin 补一条历史参与关系，使它能看见工单 B（只为触发身份闸门）' `
        -Detail "exitCode=$($participantResult.exitCode) stderr=[$(Get-BriefText $participantResult.stderrText 120)]；该行随工单 B 一起被清理 SQL 删除"
    $script:observed.Add("a4 造可见性：为 admin($adminUserId) 插入 ticket_participant(ticket=$ticketB)，随清理 SQL 按 ticket_id 删除")

    $adminSupplement = Send-Supplement -Client $adminClient -Token $adminToken `
        -TicketNo $ticketB -Version $versionB -Content '管理员越权补充' -Actor 'admin' -Note 'a4.admin.supplement'
    $adminHasRequesterAction = $adminPerms -contains 'TICKET_REQUESTER_ACTION'
    if ($adminHasRequesterAction) {
        Add-Assertion -Name 'a4.admin.supplement.409' `
            -Condition (($adminSupplement.Status -eq 409) -and ((Get-Code $adminSupplement) -eq 'TICKET_CONFLICT')) `
            -Expected '409 + TICKET_CONFLICT（可见但不是提交人；权限闸门放行后才轮到身份闸门）' `
            -Detail "status=$($adminSupplement.Status) code=$(Get-Code $adminSupplement)" -Result $adminSupplement
    } else {
        $script:observed.Add("a4.admin.supplement 未按 409 断言：admin 不具备 TICKET_REQUESTER_ACTION，实测 status=$($adminSupplement.Status) code=$(Get-Code $adminSupplement)")
        Add-Assertion -Name 'a4.admin.supplement.recordedOnly' -Condition ($adminSupplement.Status -in 403, 409) `
            -Expected '403 或 409（按 admin 是否持有提交人动作权限）' `
            -Detail "status=$($adminSupplement.Status) code=$(Get-Code $adminSupplement)" -Result $adminSupplement
    }

    # ── 10. 断言 5：编号不存在 → 404，且不确认工单是否存在 ────────────────────
    $unknownRequest = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$unknownTicketNo/actions/request-supplement" -Token $itToken `
        -Body (New-ContentBody -Version 0 -Content '不存在的工单') -Actor 'it' -Note 'a5.unknown.requestSupplement'
    $unknownSupplement = Send-Supplement -Client $employeeClient -Token $employeeToken `
        -TicketNo $unknownTicketNo -Version 0 -Content '不存在的工单' -Actor 'employee' -Note 'a5.unknown.supplement'
    Add-Assertion -Name 'a5.unknown.requestSupplement.404' `
        -Condition (($unknownRequest.Status -eq 404) -and ((Get-Code $unknownRequest) -eq 'TICKET_NOT_FOUND')) `
        -Expected '404 + TICKET_NOT_FOUND' -Detail "status=$($unknownRequest.Status) code=$(Get-Code $unknownRequest)" -Result $unknownRequest
    Add-Assertion -Name 'a5.unknown.supplement.404' `
        -Condition (($unknownSupplement.Status -eq 404) -and ((Get-Code $unknownSupplement) -eq 'TICKET_NOT_FOUND')) `
        -Expected '404 + TICKET_NOT_FOUND' -Detail "status=$($unknownSupplement.Status) code=$(Get-Code $unknownSupplement)" -Result $unknownSupplement

    # ── 11. 断言 6：字段校验（空白 / 超长 / 缺字段）→ 400 ─────────────────────
    $blankRequest = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$ticketB/actions/request-supplement" -Token $itToken `
        -Body (New-ContentBody -Version $versionB -Content '   ') -Actor 'it' -Note 'a6.blank.requestSupplement'
    Add-Assertion -Name 'a6.blank.requestSupplement.400' `
        -Condition (($blankRequest.Status -eq 400) -and ((Get-Code $blankRequest) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（@NotBlank 在服务层之前拦下纯空白）' `
        -Detail "status=$($blankRequest.Status) code=$(Get-Code $blankRequest)" -Result $blankRequest

    $longContent = '补' * 10001
    $longRequest = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$ticketB/actions/request-supplement" -Token $itToken `
        -Body (New-ContentBody -Version $versionB -Content $longContent) -Actor 'it' -Note 'a6.long.requestSupplement'
    Add-Assertion -Name 'a6.overLength.requestSupplement.400' `
        -Condition (($longRequest.Status -eq 400) -and ((Get-Code $longRequest) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（10001 字符超过 @Size(max=10000)）' `
        -Detail "status=$($longRequest.Status) code=$(Get-Code $longRequest) contentLength=$($longContent.Length)" -Result $longRequest

    $longSupplement = Send-Supplement -Client $employeeClient -Token $employeeToken `
        -TicketNo $ticketB -Version $versionB -Content $longContent -Actor 'employee' -Note 'a6.long.supplement'
    Add-Assertion -Name 'a6.overLength.supplement.400' `
        -Condition (($longSupplement.Status -eq 400) -and ((Get-Code $longSupplement) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（multipart 的 ticket 部件同样受 @Size 约束）' `
        -Detail "status=$($longSupplement.Status) code=$(Get-Code $longSupplement) contentLength=$($longContent.Length)" -Result $longSupplement

    # ── 12. 断言 7：带文件 part 的 supplement → 400，且不是静默忽略 ───────────
    # 本版本 supplement 只接受正文（片 A 范围裁决）；若服务端静默忽略文件，它会返回 200
    # 并通过状态迁移——那正是这条断言要防的。
    $fileSupplement = Send-Supplement -Client $employeeClient -Token $employeeToken `
        -TicketNo $ticketB -Version $versionB -Content '带附件的补充' -WithFilePart -Actor 'employee' -Note 'a7.filePart.supplement'
    Add-Assertion -Name 'a7.filePart.supplement.400' `
        -Condition (($fileSupplement.Status -eq 400) -and ((Get-Code $fileSupplement) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（文件 part 显式拒绝，不静默忽略）' `
        -Detail "status=$($fileSupplement.Status) code=$(Get-Code $fileSupplement) message=$((Get-ErrData $fileSupplement))" -Result $fileSupplement

    $emptyFileSupplement = Send-Supplement -Client $employeeClient -Token $employeeToken `
        -TicketNo $ticketB -Version $versionB -Content '带 0 字节附件的补充' -WithFilePart -EmptyFilePart -Actor 'employee' -Note 'a7.emptyFilePart.supplement'
    Add-Assertion -Name 'a7.emptyFilePart.supplement.400' `
        -Condition ($emptyFileSupplement.Status -eq 400) `
        -Expected '400（0 字节文件同样是文件 part，不能因为它是空的就放行）' `
        -Detail "status=$($emptyFileSupplement.Status) code=$(Get-Code $emptyFileSupplement)" -Result $emptyFileSupplement

    # 文件 part 被拒后工单必须仍在「处理中」且版本未变——拒绝是"什么都没发生"，不是半个动作
    $rowBAfterRejects = Get-TicketDbRow -TicketNo $ticketB
    Add-Assertion -Name 'a7.afterRejects.ticketUnchanged' `
        -Condition (($null -ne $rowBAfterRejects) -and ($rowBAfterRejects.status -eq 'PROCESSING') -and ($rowBAfterRejects.version -eq $versionB) -and ($rowBAfterRejects.actionDeadlineAt -eq 'NULL')) `
        -Expected "状态仍为 PROCESSING、version 仍为 $versionB、期限为 NULL" `
        -Detail "row=[$($rowBAfterRejects.raw)]"

    # ── 13. 断言 8：版本过期 → 409 且带当前快照 ───────────────────────────────
    $staleRequest = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$ticketB/actions/request-supplement" -Token $itToken `
        -Body (New-ContentBody -Version ($versionB - 1) -Content '版本过期') -Actor 'it' -Note 'a8.stale.requestSupplement'
    $staleData = Get-ErrData $staleRequest
    Add-Assertion -Name 'a8.staleVersion.requestSupplement.409' `
        -Condition (($staleRequest.Status -eq 409) -and ((Get-Code $staleRequest) -eq 'TICKET_CONFLICT') -and ([long]$staleData.version -eq $versionB) -and ($staleData.status -eq 'PROCESSING')) `
        -Expected '409 + TICKET_CONFLICT，data 带当前 version/status 快照' `
        -Detail "status=$($staleRequest.Status) code=$(Get-Code $staleRequest) snapshotVersion=$($staleData.version) snapshotStatus=$($staleData.status)" -Result $staleRequest

    # ── 14. 断言 9：正常路径 A —— 请求补充 → 200，进入待补充 ─────────────────
    $requestA = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$ticketA/actions/request-supplement" -Token $itToken `
        -Body (New-ContentBody -Version $versionA -Content '  请补充打印机型号，以及打印时弹出的完整报错  ') `
        -Actor 'it' -Note 'a9.requestSupplement(A)'
    $dataRequestA = Get-Data $requestA
    Add-Assertion -Name 'a9.requestSupplement.200' `
        -Condition (($requestA.Status -eq 200) -and ($dataRequestA.status -eq 'WAITING_FOR_REQUESTER') -and ([long]$dataRequestA.version -eq ($versionA + 1))) `
        -Expected '200，status=WAITING_FOR_REQUESTER，version+1' `
        -Detail "status=$($requestA.Status) businessStatus=$($dataRequestA.status) version=$($dataRequestA.version) assignee=$($dataRequestA.assignee.displayName) deadline=$($dataRequestA.actionDeadlineAt)" `
        -Result $requestA
    Add-KeyAction -Order 2 -Actor 'it' -Action 'request-supplement' -Method 'POST' `
        -Path "/fd/v1/tickets/$ticketA/actions/request-supplement" -TicketNo $ticketA -Result $requestA -StatusAfter 'WAITING_FOR_REQUESTER'
    if ($requestA.Status -ne 200) { throw "工单 A 请求补充失败（status=$($requestA.Status) code=$(Get-Code $requestA)），后续断言无法进行" }
    $versionA = [long]$dataRequestA.version

    # 期限必须等于动作时刻 + 7×24h（文档口径 7 天，配置项 flowdesk.ticket.supplement-window）
    $actionTimeA = [datetime]::Parse($dataRequestA.actionTime).ToUniversalTime()
    $deadlineA = [datetime]::Parse($dataRequestA.actionDeadlineAt).ToUniversalTime()
    $windowSeconds = ($deadlineA - $actionTimeA).TotalSeconds
    $expectedWindow = 7 * 24 * 3600
    $script:windowInfo = [ordered]@{
        actionTime           = $dataRequestA.actionTime
        actionDeadlineAt     = $dataRequestA.actionDeadlineAt
        windowSeconds        = $windowSeconds
        expectedWindowSeconds = $expectedWindow
        configKey            = 'flowdesk.ticket.supplement-window（application.yml 默认 7d）'
    }
    Add-Assertion -Name 'a9.deadlineEqualsSevenDays' `
        -Condition ($windowSeconds -eq $expectedWindow) `
        -Expected "期限 = 动作时刻 + $expectedWindow 秒（7×24h）" `
        -Detail "actionTime=$($dataRequestA.actionTime) deadline=$($dataRequestA.actionDeadlineAt) 实测差=$windowSeconds 秒" -Result $requestA

    # 期限必须真的落库（ck_ticket_status_deadline：待补充必须有期限）
    # 比较用**字符串规范化**而不是 DateTime 解析：MySQL 返回 "2026-10-14 02:14:27.027000"，
    # 响应是 "2026-10-14T02:14:27.027Z"，直接 Parse 会带上本机时区（这里实测差 8 小时），
    # 秒级比较既排除了时区，也容纳了 DATETIME(3) 与响应之间的微秒表示差异。
    $rowAAfterRequest = Get-TicketDbRow -TicketNo $ticketA
    $deadlineMatchesDb = $false
    $dbDeadlineBrief = '(row 不可读)'
    if ($null -ne $rowAAfterRequest) {
        $dbDeadlineBrief = $rowAAfterRequest.actionDeadlineAt
        $dbSeconds = $rowAAfterRequest.actionDeadlineAt.Substring(0, 19)
        $responseSeconds = $dataRequestA.actionDeadlineAt.Replace('T', ' ').Substring(0, 19)
        $deadlineMatchesDb = ($dbSeconds -eq $responseSeconds)
    }
    Add-Assertion -Name 'a9.deadlinePersistedMatchesResponse' -Condition $deadlineMatchesDb `
        -Expected '库里的 action_deadline_at 与响应一致（比较到秒）' `
        -Detail "row=[$($rowAAfterRequest.raw)]；库里秒级=[$($dbDeadlineBrief)]；响应秒级=[$($dataRequestA.actionDeadlineAt)]"

    # ── 15. 断言 10：待补充期间的互斥行为 ────────────────────────────────────
    $resolveWhileWaiting = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$ticketA/actions/submit-resolution" -Token $itToken `
        -Body (New-ContentBody -Version $versionA -Content '待补充期间直接提交解决') -Actor 'it' -Note 'a10.resolveWhileWaiting'
    Add-Assertion -Name 'a10.submitResolutionWhileWaiting.409' `
        -Condition (($resolveWhileWaiting.Status -eq 409) -and ((Get-Code $resolveWhileWaiting) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（待补充期间不能提交解决结果，先撤回或等员工补充）' `
        -Detail "status=$($resolveWhileWaiting.Status) code=$(Get-Code $resolveWhileWaiting)" -Result $resolveWhileWaiting

    $processWhileWaiting = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$ticketA/actions/add-processing-record" -Token $itToken `
        -Body (New-ContentBody -Version $versionA -Content '待补充期间追加处理记录') -Actor 'it' -Note 'a10.processWhileWaiting'
    Add-Assertion -Name 'a10.addProcessingRecordWhileWaiting.409' `
        -Condition (($processWhileWaiting.Status -eq 409) -and ((Get-Code $processWhileWaiting) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（该动作只允许处理中）' `
        -Detail "status=$($processWhileWaiting.Status) code=$(Get-Code $processWhileWaiting)" -Result $processWhileWaiting

    $repeatRequest = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$ticketA/actions/request-supplement" -Token $itToken `
        -Body (New-ContentBody -Version $versionA -Content '待补充期间再次请求补充') -Actor 'it' -Note 'a10.repeatRequestSupplement'
    Add-Assertion -Name 'a10.repeatRequestSupplement.409' `
        -Condition (($repeatRequest.Status -eq 409) -and ((Get-Code $repeatRequest) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（要再次请求必须先撤回，docs/kickoff.md 4.11）' `
        -Detail "status=$($repeatRequest.Status) code=$(Get-Code $repeatRequest)" -Result $repeatRequest

    # ── 16. 断言 11：allowedActions 在待补充状态下按视角互斥 ─────────────────
    $detailItWaiting = Send-Req -Client $itClient -Method Get -Path "/fd/v1/tickets/$ticketA" -Token $itToken `
        -Actor 'it' -Note 'a11.detail.itWaiting'
    $detailEmployeeWaiting = Send-Req -Client $employeeClient -Method Get -Path "/fd/v1/tickets/$ticketA" -Token $employeeToken `
        -Actor 'employee' -Note 'a11.detail.employeeWaiting'
    $itWaitingActions = @((Get-Data $detailItWaiting).allowedActions)
    $employeeWaitingActions = @((Get-Data $detailEmployeeWaiting).allowedActions)
    Add-Assertion -Name 'a11.allowedActions.itWaiting' `
        -Condition (($itWaitingActions.Count -eq 1) -and ($itWaitingActions[0] -eq 'withdraw-supplement-request')) `
        -Expected '恰好 [withdraw-supplement-request]（负责人待补充时只能撤回）' `
        -Detail "allowedActions=$($itWaitingActions -join ',')" -Result $detailItWaiting
    Add-Assertion -Name 'a11.allowedActions.employeeWaiting' `
        -Condition (($employeeWaitingActions.Count -eq 1) -and ($employeeWaitingActions[0] -eq 'supplement')) `
        -Expected '恰好 [supplement]（提交人待补充时只能补充）' `
        -Detail "allowedActions=$($employeeWaitingActions -join ',')" -Result $detailEmployeeWaiting
    Add-Assertion -Name 'a11.deadlineVisibleToBothParties' `
        -Condition (((Get-Data $detailItWaiting).actionDeadlineAt) -and ((Get-Data $detailEmployeeWaiting).actionDeadlineAt)) `
        -Expected '两种视角的详情都返回同一个 actionDeadlineAt（界面据此展示截止时间）' `
        -Detail "it=$((Get-Data $detailItWaiting).actionDeadlineAt) employee=$((Get-Data $detailEmployeeWaiting).actionDeadlineAt)" -Result $detailEmployeeWaiting

    # ── 17. 断言 12：正常路径 A —— 员工补充 → 200，回到处理中且期限清空 ───────
    $supplementA = Send-Supplement -Client $employeeClient -Token $employeeToken `
        -TicketNo $ticketA -Version $versionA -Content '  型号是 L3153，报错信息为"打印机未响应"  ' `
        -Actor 'employee' -Note 'a12.supplement(A)'
    $dataSupplementA = Get-Data $supplementA
    Add-Assertion -Name 'a12.supplement.200' `
        -Condition (($supplementA.Status -eq 200) -and ($dataSupplementA.status -eq 'PROCESSING') -and ([long]$dataSupplementA.version -eq ($versionA + 1))) `
        -Expected '200，status=PROCESSING，version+1' `
        -Detail "status=$($supplementA.Status) businessStatus=$($dataSupplementA.status) version=$($dataSupplementA.version) assignee=$($dataSupplementA.assignee.displayName)" `
        -Result $supplementA
    Add-Assertion -Name 'a12.expiredDeadlineAbsentFromResponse' -Condition (Test-DeadlineFieldAbsent $supplementA) `
        -Expected 'actionDeadlineAt 字段整体不出现（non_null 序列化 + 期限已失效）' `
        -Detail "body 里是否出现 actionDeadlineAt：$(-not (Test-DeadlineFieldAbsent $supplementA))" -Result $supplementA
    Add-KeyAction -Order 3 -Actor 'employee' -Action 'supplement' -Method 'POST' `
        -Path "/fd/v1/tickets/$ticketA/actions/supplement" -TicketNo $ticketA -Result $supplementA -StatusAfter 'PROCESSING'
    if ($supplementA.Status -ne 200) { throw "工单 A 补充失败（status=$($supplementA.Status) code=$(Get-Code $supplementA)），后续断言无法进行" }
    $versionA = [long]$dataSupplementA.version

    $rowAAfterSupplement = Get-TicketDbRow -TicketNo $ticketA
    Add-Assertion -Name 'a12.db.returnedToProcessingWithNullDeadline' `
        -Condition (($null -ne $rowAAfterSupplement) -and ($rowAAfterSupplement.status -eq 'PROCESSING') -and ($rowAAfterSupplement.actionDeadlineAt -eq 'NULL') -and ($rowAAfterSupplement.endedAt -eq 'NULL') -and ($rowAAfterSupplement.assigneeId -eq $itUserId) -and ($rowAAfterSupplement.version -eq $versionA)) `
        -Expected 'PROCESSING + 期限 NULL + 未结束 + 负责人仍是 it + version 与响应一致' `
        -Detail "row=[$($rowAAfterSupplement.raw)]"

    # ── 18. 断言 13：时间线（记录类型、顺序、正文、期限冻结） ─────────────────
    $timelineA = Get-TicketRecordDbRows -TicketNo $ticketA
    $timelineTypes = @($timelineA | ForEach-Object { $_.recordType })
    $timelineSeqs = @($timelineA | ForEach-Object { $_.sequenceNo })
    $expectedTypes = @('CREATE', 'CLAIM', 'SUPPLEMENT_REQUEST', 'REQUESTER_SUPPLEMENT')
    $expectedSeqs = @(1, 2, 3, 4)
    Add-Assertion -Name 'a13.timeline.recordTypesAndOrder' `
        -Condition (($timelineTypes.Count -eq 4) -and (($timelineTypes -join ',') -eq ($expectedTypes -join ','))) `
        -Expected "记录类型恰为 $($expectedTypes -join ' → ')" `
        -Detail "实测=$($timelineTypes -join ',')"
    Add-Assertion -Name 'a13.timeline.sequencesAreContinuous' `
        -Condition (($timelineSeqs.Count -eq 4) -and (($timelineSeqs -join ',') -eq ($expectedSeqs -join ','))) `
        -Expected "序号恰为 $($expectedSeqs -join ',')（无跳号）" `
        -Detail "实测=$($timelineSeqs -join ',')"

    $requestRecord = $timelineA | Where-Object { $_.recordType -eq 'SUPPLEMENT_REQUEST' }
    $supplementRecord = $timelineA | Where-Object { $_.recordType -eq 'REQUESTER_SUPPLEMENT' }
    # 记录里的 deadline_at 是 DATETIME(3)（秒 + 毫秒 + 6 位补齐），响应是 ISO-8601 带 Z；
    # 因此比较到秒，既避开时区也避开微秒表示差异（与 a9.deadlinePersistedMatchesResponse 同一口径）。
    $requestRecordSeconds = '(记录不可读)'
    $requestDeadlineMatches = $false
    if ($null -ne $requestRecord) {
        $requestRecordSeconds = $requestRecord.deadlineAt.Substring(0, 19)
        $requestDeadlineMatches = ($requestRecordSeconds -eq $dataRequestA.actionDeadlineAt.Replace('T', ' ').Substring(0, 19))
    }
    Add-Assertion -Name 'a13.supplementRequestRecord.frozenDeadline' `
        -Condition (($null -ne $requestRecord) -and $requestDeadlineMatches -and ($requestRecord.fromStatus -eq 'PROCESSING') -and ($requestRecord.toStatus -eq 'WAITING_FOR_REQUESTER') -and ($requestRecord.actorId -eq $itUserId)) `
        -Expected 'SUPPLEMENT_REQUEST 冻结当时的期限，并记录 PROCESSING → WAITING_FOR_REQUESTER，操作人是负责人' `
        -Detail "record deadline=$($requestRecordSeconds) 响应期限=$($dataRequestA.actionDeadlineAt) from=$($requestRecord.fromStatus) to=$($requestRecord.toStatus) actorId=$($requestRecord.actorId)"
    Add-Assertion -Name 'a13.supplementRecord.contentAndStatus' `
        -Condition (($null -ne $supplementRecord) -and ($supplementRecord.deadlineAt -eq 'NULL') -and ($supplementRecord.fromStatus -eq 'WAITING_FOR_REQUESTER') -and ($supplementRecord.toStatus -eq 'PROCESSING') -and ($supplementRecord.actorId -eq $employeeUserId) -and ($supplementRecord.content -eq '型号是 L3153，报错信息为"打印机未响应"')) `
        -Expected 'REQUESTER_SUPPLEMENT 不写期限，正文去除首尾空白后落库，操作人是提交人' `
        -Detail "record deadline=$($supplementRecord.deadlineAt) content=[$($supplementRecord.content)] from=$($supplementRecord.fromStatus) to=$($supplementRecord.toStatus) actorId=$($supplementRecord.actorId)"

    # ── 19. 断言 14：补充之后负责人可以继续处理并再次请求补充（期限重新计算） ──
    $processAfterSupplement = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$ticketA/actions/add-processing-record" -Token $itToken `
        -Body (New-ContentBody -Version $versionA -Content '已按补充的型号重新安装驱动') -Actor 'it' -Note 'a14.processAfterSupplement'
    Add-Assertion -Name 'a14.addProcessingRecordAfterSupplement.200' `
        -Condition (($processAfterSupplement.Status -eq 200) -and ((Get-Data $processAfterSupplement).status -eq 'PROCESSING')) `
        -Expected '200（往返是闭环，补充后工单可以继续正常推进）' `
        -Detail "status=$($processAfterSupplement.Status) businessStatus=$((Get-Data $processAfterSupplement).status)" -Result $processAfterSupplement
    if ($processAfterSupplement.Status -ne 200) { throw "工单 A 补充后追加处理记录失败（status=$($processAfterSupplement.Status)）" }
    $versionA = [long](Get-Data $processAfterSupplement).version

    $requestAgain = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$ticketA/actions/request-supplement" -Token $itToken `
        -Body (New-ContentBody -Version $versionA -Content '还需要一张设备背面的标签照片') -Actor 'it' -Note 'a14.requestSupplementAgain'
    Add-Assertion -Name 'a14.secondRequestSupplement.200' `
        -Condition (($requestAgain.Status -eq 200) -and ((Get-Data $requestAgain).status -eq 'WAITING_FOR_REQUESTER')) `
        -Expected '200（撤回或员工补充之后可以再次请求补充）' `
        -Detail "status=$($requestAgain.Status) businessStatus=$((Get-Data $requestAgain).status)" -Result $requestAgain
    if ($requestAgain.Status -ne 200) { throw "工单 A 第二次请求补充失败（status=$($requestAgain.Status) code=$(Get-Code $requestAgain)）" }
    $versionA = [long](Get-Data $requestAgain).version
    $secondActionTime = [datetime]::Parse((Get-Data $requestAgain).actionTime).ToUniversalTime()
    $secondDeadline = [datetime]::Parse((Get-Data $requestAgain).actionDeadlineAt).ToUniversalTime()
    Add-Assertion -Name 'a14.secondDeadlineRecalculated' `
        -Condition ((($secondDeadline - $secondActionTime).TotalSeconds -eq $expectedWindow) -and ($secondDeadline -gt $deadlineA)) `
        -Expected '第二次期限从第二次请求时刻重新计算，且晚于第一次' `
        -Detail "第一次 deadline=$($dataRequestA.actionDeadlineAt)；第二次 actionTime=$((Get-Data $requestAgain).actionTime) deadline=$((Get-Data $requestAgain).actionDeadlineAt)"

    # ── 20. 断言 15：撤回仍然可用（片 A 动作在新链路下未被破坏） ─────────────
    $withdrawA = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$ticketA/actions/withdraw-supplement-request" -Token $itToken `
        -Body @{ version = $versionA; reason = '信息已足够，撤回补充请求' } -Actor 'it' -Note 'a15.withdraw(A)'
    Add-Assertion -Name 'a15.withdrawAfterSecondRequest.200' `
        -Condition (($withdrawA.Status -eq 200) -and ((Get-Data $withdrawA).status -eq 'PROCESSING')) `
        -Expected '200（片 A 的撤回在片 B 之后仍然工作，回到处理中）' `
        -Detail "status=$($withdrawA.Status) businessStatus=$((Get-Data $withdrawA).status)" -Result $withdrawA
    if ($withdrawA.Status -eq 200) { $versionA = [long](Get-Data $withdrawA).version }

    # ── 21. 断言 16：终态可区分（补充往返之后仍能走完确认链路） ───────────────
    $resolveA = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$ticketA/actions/submit-resolution" -Token $itToken `
        -Body (New-ContentBody -Version $versionA -Content '已按补充信息更换驱动，请确认') -Actor 'it' -Note 'a16.submitResolution(A)'
    Add-Assertion -Name 'a16.submitResolution.200' `
        -Condition (($resolveA.Status -eq 200) -and ((Get-Data $resolveA).status -eq 'WAITING_FOR_CONFIRMATION')) `
        -Expected '200 且进入 WAITING_FOR_CONFIRMATION（补充往返不破坏既有主链）' `
        -Detail "status=$($resolveA.Status) businessStatus=$((Get-Data $resolveA).status)" -Result $resolveA
    if ($resolveA.Status -eq 200) { $versionA = [long](Get-Data $resolveA).version }
    Add-KeyAction -Order 4 -Actor 'it' -Action 'submit-resolution' -Method 'POST' `
        -Path "/fd/v1/tickets/$ticketA/actions/submit-resolution" -TicketNo $ticketA -Result $resolveA -StatusAfter 'WAITING_FOR_CONFIRMATION'

    $confirmA = Send-Req -Client $employeeClient -Method Post `
        -Path "/fd/v1/tickets/$ticketA/actions/confirm-resolution" -Token $employeeToken `
        -Body @{ version = $versionA } -Actor 'employee' -Note 'a16.confirmResolution(A)'
    Add-Assertion -Name 'a16.confirmResolution.200' `
        -Condition (($confirmA.Status -eq 200) -and ((Get-Data $confirmA).status -eq 'COMPLETED')) `
        -Expected '200 且进入终态 COMPLETED' `
        -Detail "status=$($confirmA.Status) businessStatus=$((Get-Data $confirmA).status)" -Result $confirmA
    Add-KeyAction -Order 5 -Actor 'employee' -Action 'confirm-resolution' -Method 'POST' `
        -Path "/fd/v1/tickets/$ticketA/actions/confirm-resolution" -TicketNo $ticketA -Result $confirmA -StatusAfter 'COMPLETED'

    $rowAFinal = Get-TicketDbRow -TicketNo $ticketA
    Add-Assertion -Name 'a16.db.terminalSnapshotConsistent' `
        -Condition (($null -ne $rowAFinal) -and ($rowAFinal.status -eq 'COMPLETED') -and ($rowAFinal.actionDeadlineAt -eq 'NULL') -and ($rowAFinal.endedAt -eq 'SET') -and ($rowAFinal.completionMethod -eq 'REQUESTER_CONFIRMED') -and ($rowAFinal.assigneeId -eq $itUserId)) `
        -Expected 'COMPLETED + 期限 NULL + ended_at SET + completion_method=REQUESTER_CONFIRMED + 保留负责人' `
        -Detail "row=[$($rowAFinal.raw)]"

    # ── 22. 断言 17：终态与版本过期仍按既有契约拒绝 ──────────────────────────
    $terminalSupplement = Send-Supplement -Client $employeeClient -Token $employeeToken `
        -TicketNo $ticketA -Version $versionA -Content '终态后再次补充' -Actor 'employee' -Note 'a17.terminal.supplement'
    Add-Assertion -Name 'a17.terminal.supplement.409' `
        -Condition (($terminalSupplement.Status -eq 409) -and ((Get-Code $terminalSupplement) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（终态不可再补充）' `
        -Detail "status=$($terminalSupplement.Status) code=$(Get-Code $terminalSupplement)" -Result $terminalSupplement

    $terminalRequest = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$ticketA/actions/request-supplement" -Token $itToken `
        -Body (New-ContentBody -Version 0 -Content '终态后请求补充') -Actor 'it' -Note 'a17.terminal.requestSupplement'
    Add-Assertion -Name 'a17.terminal.requestSupplement.409' `
        -Condition (($terminalRequest.Status -eq 409) -and ((Get-Code $terminalRequest) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（终态不可再请求补充）' `
        -Detail "status=$($terminalRequest.Status) code=$(Get-Code $terminalRequest)" -Result $terminalRequest

    # ── 23. 断言 18：真库 CHECK 约束不是摆设 ────────────────────────────────
    # 非等待态带期限会被 ck_ticket_status_deadline 拒绝；MySQL 返回 error 3819 / SQL state HY000。
    $checkProbe = Invoke-MySql -Sql (
        "UPDATE ticket SET action_deadline_at = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 7 DAY) " +
        "WHERE ticket_no = '$ticketA';")
    $checkRejected = ($checkProbe.exitCode -ne 0) -and ($checkProbe.stderrText -match 'ck_ticket_status_deadline')
    Add-Assertion -Name 'a18.checkConstraint.rejectsDeadlineOnNonWaitingState' -Condition $checkRejected `
        -Expected 'MySQL 拒绝：ck_ticket_status_deadline（终态不得带期限）' `
        -Detail "exitCode=$($checkProbe.exitCode) stderr=[$(Get-BriefText $checkProbe.stderrText 120)]"
    if (-not $checkRejected) {
        # 探针若意外成功，会把工单改成"已完成但带期限"的非法行——立即回滚成合法值
        $null = Invoke-MySql -Sql "UPDATE ticket SET action_deadline_at = NULL WHERE ticket_no = '$ticketA';"
        $script:observed.Add("a18 探针未被约束拒绝，已把工单 $ticketA 的期限改回 NULL（避免留下非法行）")
    }

    # ── 24. 断言 19：既有工单与演示库计数未被触碰 ───────────────────────────
    Write-Host ''
    Write-Host '验收断言全部执行完毕，开始清理与基线核对。'
}
catch {
    Add-Assertion -Name 'run.unexpectedFailure' -Condition $false -Expected '脚本正常跑完' `
        -Detail ("异常：" + $_.Exception.Message)
}
finally {
    foreach ($client in $clients) {
        if ($null -ne $client) { $client.Dispose() }
    }
}

# ── 25. 清理：只删本次创建的工单及其记录与参与关系 ──────────────────────────
$script:cleanupInfo['startedAt'] = (Get-Date).ToString('s')
$ownTickets = @($ticketA, $ticketB) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

try {
    if ($ownTickets.Count -eq 0) {
        $script:cleanupInfo['note'] = '本次运行没有创建任何工单，无需清理。'
        Add-Assertion -Name 'a22.cleanup.nothingCreated' -Condition $true -Expected '无自建工单' -Detail '脚本在建单前就失败，未写入任何数据'
    } else {
        $quoted = ($ownTickets | ForEach-Object { "'" + $_ + "'" }) -join ','
        $ticketList = "SELECT id FROM ticket WHERE ticket_no IN ($quoted)"
        $cleanupStatements = @(
            "DELETE FROM ticket_record WHERE ticket_id IN ($ticketList);",
            "DELETE FROM ticket_participant WHERE ticket_id IN ($ticketList);",
            "DELETE FROM ticket WHERE ticket_no IN ($quoted);"
        )
        $script:cleanupInfo['ticketNos'] = $ownTickets
        $script:cleanupInfo['sql'] = $cleanupStatements
        $script:cleanupInfo['note'] = '只删除本次运行创建的工单及其记录与参与关系；片 B 的两个动作不产生附件与工单关联，因此无需删除 ticket_attachment / ticket_relation。'

        $cleanupResult = Invoke-MySql -Sql ($cleanupStatements -join "`n")
        $script:cleanupInfo['exitCode'] = $cleanupResult.exitCode
        $script:cleanupInfo['stderr'] = $cleanupResult.stderrText
        Add-Assertion -Name 'a22.cleanup.deletesSucceeded' -Condition ($cleanupResult.exitCode -eq 0) `
            -Expected '清理 SQL 退出码 0' `
            -Detail "exitCode=$($cleanupResult.exitCode) stderr=[$(Get-BriefText $cleanupResult.stderrText 120)]"

        $leftRow = Get-MySqlRow -Sql "SELECT COUNT(*) FROM ticket WHERE ticket_no IN ($quoted);"
        Add-Assertion -Name 'a22.cleanup.noOwnRowsLeft' -Condition ($leftRow.ok -and ([int]$leftRow.columns[0] -eq 0)) `
            -Expected '自建工单已全部删除' -Detail "row=[$($leftRow.raw)]"
    }
} catch {
    Add-Assertion -Name 'a22.cleanup.deletesSucceeded' -Condition $false -Expected '清理阶段无异常' `
        -Detail ("清理异常：" + $_.Exception.Message)
}

# ── 26. 清理后基线核对 ──────────────────────────────────────────────────────
try {
    $demoAfter = Get-DemoFingerprint
    $script:cleanupInfo['demoDatabaseBefore'] = $demoBefore
    $script:cleanupInfo['demoDatabaseAfter'] = $demoAfter

    $sameCounts = ($null -ne $demoBefore) -and ($demoBefore.tickets -eq $demoAfter.tickets) -and `
        ($demoBefore.records -eq $demoAfter.records) -and ($demoBefore.participants -eq $demoAfter.participants) -and `
        ($demoBefore.users -eq $demoAfter.users) -and ($demoBefore.roles -eq $demoAfter.roles) -and `
        ($demoBefore.permissions -eq $demoAfter.permissions) -and ($demoBefore.categories -eq $demoAfter.categories)
    Add-Assertion -Name 'a23.baseline.countsRestored' -Condition $sameCounts `
        -Expected '演示库七项计数与运行前完全一致' `
        -Detail ("运行前 工单={0}/记录={1}/参与者={2}/用户={3}/角色={4}/权限={5}/分类={6}；运行后 工单={7}/记录={8}/参与者={9}/用户={10}/角色={11}/权限={12}/分类={13}" -f `
            $demoBefore.tickets, $demoBefore.records, $demoBefore.participants, $demoBefore.users, $demoBefore.roles, $demoBefore.permissions, $demoBefore.categories, `
            $demoAfter.tickets, $demoAfter.records, $demoAfter.participants, $demoAfter.users, $demoAfter.roles, $demoAfter.permissions, $demoAfter.categories)

    $sameTicketNos = ($null -ne $demoBefore) -and ($demoBefore.ticketNos -eq $demoAfter.ticketNos)
    Add-Assertion -Name 'a23.baseline.ticketRowsUntouched' -Condition $sameTicketNos `
        -Expected '既有工单号集合与运行前完全一致（含 FD-20261006-026）' `
        -Detail "运行前=[$($demoBefore.ticketNos)]；运行后=[$($demoAfter.ticketNos)]"

    $sameTicketFingerprint = ($null -ne $demoBefore) -and ($demoBefore.ticketRows -eq $demoAfter.ticketRows)
    Add-Assertion -Name 'a23.baseline.ticketFingerprintUntouched' -Condition $sameTicketFingerprint `
        -Expected '既有工单的 status/version/record_seq/负责人/期限/结束时间逐行一致' `
        -Detail "运行前指纹长度=$($demoBefore.ticketRows.Length)；运行后指纹长度=$($demoAfter.ticketRows.Length)；是否相同=$sameTicketFingerprint"

    $sameRecordFingerprint = ($null -ne $demoBefore) -and ($demoBefore.recordRows -eq $demoAfter.recordRows)
    Add-Assertion -Name 'a23.baseline.recordFingerprintUntouched' -Condition $sameRecordFingerprint `
        -Expected '既有记录的 id/序号/类型/时间逐条一致（不可变时间线没有被改动）' `
        -Detail "运行前指纹长度=$($demoBefore.recordRows.Length)；运行后指纹长度=$($demoAfter.recordRows.Length)；是否相同=$sameRecordFingerprint"

    $sameParticipants = ($null -ne $demoBefore) -and ($demoBefore.participantRows -eq $demoAfter.participantRows)
    Add-Assertion -Name 'a23.baseline.participantFingerprintUntouched' -Condition $sameParticipants `
        -Expected '既有参与关系逐条一致' `
        -Detail "运行前=[$($demoBefore.participantRows)]；运行后=[$($demoAfter.participantRows)]"

    $script:cleanupInfo['dailySequenceBefore'] = $demoBefore.dailySequence
    $script:cleanupInfo['dailySequenceAfter'] = $demoAfter.dailySequence
    Add-Assertion -Name 'a23.dailySequenceRecordedOnly' -Condition ($null -ne $demoAfter) `
        -Expected 'ticket_daily_sequence 只记录不回退（接口建单必然递增，编号不复用）' `
        -Detail "运行前=[$($demoBefore.dailySequence)]；运行后=[$($demoAfter.dailySequence)]"
} catch {
    Add-Assertion -Name 'a23.baseline.checkFailed' -Condition $false -Expected '清理后基线核对可完成' `
        -Detail ("核对异常：" + $_.Exception.Message)
}

$script:cleanupInfo['dockerCommandsUsed'] = @('docker exec -i -e MYSQL_PWD <mysql 容器> mysql -uroot -N -B --default-character-set=utf8mb4 <库名>（SQL 走 stdin）')
$script:cleanupInfo['composeDownExecuted'] = $false
$script:cleanupInfo['containersOrVolumesModified'] = $false
$script:cleanupInfo['backendProcessTouched'] = $false
$script:cleanupInfo['targetBaseUrl'] = $baseUrl
$script:cleanupInfo['finishedAt'] = (Get-Date).ToString('s')

# ── 27. 汇总与证据（无 BOM 的 UTF-8） ───────────────────────────────────────
# 注意：List[object] 一律用 .ToArray()，不要用 @() 包（Windows PowerShell 5.1 会抛
# System.ArgumentException: Argument types do not match）；条件值先算成变量再进哈希表字面量。
$finishedAt = Get-Date
$failed = @($script:results | Where-Object { -not $_.passed })
$failedCount = $failed.Count
$totalCount = $script:results.Count
$passedCount = $totalCount - $failedCount
if ($failedCount -gt 0) { $script:quitCode = 1 }

$relativeScriptPath = 'scripts/slice-b-supplement-roundtrip-acceptance.ps1'
$relativeOutPath = 'docs/acceptance/2026-10-07-slice-b-supplement-roundtrip.json'
$invocation = 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\slice-b-supplement-roundtrip-acceptance.ps1'
$scriptLineCount = -1
try { $scriptLineCount = @(Get-Content -LiteralPath $MyInvocation.MyCommand.Path).Count } catch { $scriptLineCount = -1 }

$evidenceBuilt = $false
$evidenceBuildError = $null
$json = $null
try {
    $resultValue = 'FAIL'
    if ($failedCount -eq 0) { $resultValue = 'PASS' }

    $assertionList = $script:results.ToArray()
    $dbCheckList = $script:dbChecks.ToArray()
    $keyActionList = $script:keyActions.ToArray()
    $httpLogList = $script:httpLog.ToArray()
    $notesList = $script:notes.ToArray()
    $observedList = $script:observed.ToArray()
    $executedSqlList = $script:executedSql.ToArray()

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
    }
    $targetEntry = [ordered]@{
        baseUrl               = $baseUrl
        dbContainer           = $DbContainer
        database              = $dbName
        originHeader          = $originHeader
        accounts              = @($EmployeeUser, $ItUser, $AdminUser)
        credentialsPrinted    = $false
        credentialsInEvidence = $false
    }
    $totalsEntry = [ordered]@{
        total  = $totalCount
        passed = $passedCount
        failed = $failedCount
    }
    $evidence = [ordered]@{
        _note = ('本文件由 scripts/slice-b-supplement-roundtrip-acceptance.ps1 写出（无 BOM 的 UTF-8）。' +
            '只记录状态码、业务码、traceId、断言结论与 SQL；不含口令、密钥与 accessToken，也不含机器绝对路径。')
        stage               = '完整工单状态机 片 B：补充往返（request-supplement + supplement）真实栈验收'
        slice               = 'B'
        date                = $startedAt.ToString('yyyy-MM-dd')
        result              = $resultValue
        script              = $scriptEntry
        target              = $targetEntry
        preflight           = $script:preflightInfo
        supplementWindow    = $script:windowInfo
        tickets             = $ticketsInfo
        keyActions          = $keyActionList
        databaseChecks      = $dbCheckList
        assertions          = $assertionList
        httpLog             = $httpLogList
        sqlExecuted         = $executedSqlList
        cleanup             = $script:cleanupInfo
        observed            = $observedList
        totals              = $totalsEntry
        notes               = $notesList
    }
    $json = $evidence | ConvertTo-Json -Depth 12
    $evidenceBuilt = $true
} catch {
    $script:quitCode = 1
    $evidenceBuildError = $_.Exception.Message
    Write-Host ("[FAIL] 证据对象构建失败：{0}" -f $evidenceBuildError) -ForegroundColor Red
}

if (-not $evidenceBuilt) {
    # 兜底：证据必须落盘。只用一定存在的变量。
    $fallbackTotals = [ordered]@{ total = $totalCount; passed = $passedCount; failed = $failedCount }
    $fallbackScript = [ordered]@{ path = $relativeScriptPath; invocation = $invocation; exitCode = $script:quitCode }
    $fallbackEvidence = [ordered]@{
        _note      = '本文件由回退路径写出：证据对象构建或序列化失败，断言明细仍在，其余字段缺失。'
        stage      = '完整工单状态机 片 B：补充往返真实栈验收'
        result     = 'FAIL'
        error      = $evidenceBuildError
        script     = $fallbackScript
        assertions = $script:results.ToArray()
        totals     = $fallbackTotals
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
Write-Host ("阶段：片 B 补充往返；目标后端：{0}" -f $baseUrl)
Write-Host ("断言 {0} 项，通过 {1}，失败 {2}" -f $totalCount, $passedCount, $failedCount)
Write-Host '关键动作（状态码 / X-Trace-Id）：'
foreach ($step in $script:keyActions) {
    Write-Host ("  {0}. {1,-9} {2,-26} {3} => {4} traceId={5}" -f `
            $step.order, $step.actor, $step.action, $step.ticketNo, $step.httpStatus, $step.traceId)
}
Write-Host '数据库直查：'
foreach ($check in $script:dbChecks) {
    $mark = 'FAIL'
    if ($check.passed) { $mark = 'PASS' }
    Write-Host ("  [{0}] {1} :: {2}" -f $mark, $check.name, $check.raw)
}
if ($null -ne $demoBefore) {
    Write-Host ("演示库计数：运行前 工单={0}/记录={1}/参与者={2}/工单号=[{3}]" -f `
            $demoBefore.tickets, $demoBefore.records, $demoBefore.participants, $demoBefore.ticketNos)
}
if ($null -ne $demoAfter) {
    Write-Host ("            运行后 工单={0}/记录={1}/参与者={2}/工单号=[{3}]" -f `
            $demoAfter.tickets, $demoAfter.records, $demoAfter.participants, $demoAfter.ticketNos)
}
Write-Host ("证据：{0}（无 BOM UTF-8={1}，可回读解析={2}，{3} 字节）" -f `
        $relativeOutPath, (-not $hasBom), ($null -ne $parsedBack), $writtenBytes.Length)
Write-Host '本次执行过的清理 SQL（脚本已执行）：'
if ($script:cleanupInfo.Contains('sql') -and @($script:cleanupInfo['sql']).Count -gt 0) {
    foreach ($statement in $script:cleanupInfo['sql']) { Write-Host "  $statement" }
} else {
    Write-Host '  （本次运行没有创建任何工单，无需清理）'
}
if ($failedCount -gt 0) {
    Write-Host '失败项：' -ForegroundColor Red
    $failed | ForEach-Object { Write-Host ("  - {0} :: {1}" -f $_.name, $_.detail) -ForegroundColor Red }
}
Write-Host ("退出码：{0}" -f $script:quitCode)
exit $script:quitCode
