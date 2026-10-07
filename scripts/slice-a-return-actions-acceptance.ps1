# 完整工单状态机「片 A：退回处理中」真实栈验收
#   report-unresolved（提交人反馈未解决）  +  withdraw-supplement-request（负责人撤回补充请求）
#
# 前置
#   · docker compose up -d mysql redis（本脚本只对既有 mysql 容器执行 docker exec，不碰容器生命周期）
#   · 目标后端必须是**已包含片 A 两个端点的最新代码**，由用户自己启动。脚本只发 HTTP 请求，
#     不启动、不重启、不结束任何后端进程；8081 上用户自己启动的实例保持原样。
#
# 运行
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\slice-a-return-actions-acceptance.ps1
#   基址可覆盖（默认 http://127.0.0.1:8081，验收时可能跑在另一个端口的并行后端上）：
#     $env:FLOWDESK_BASE_URL = 'http://127.0.0.1:8092'   # 或 -BaseUrl http://127.0.0.1:8092
#   演示账号可用 E2E_EMPLOYEE_USERNAME / E2E_EMPLOYEE_PASSWORD、E2E_IT_*、E2E_ADMIN_* 覆盖
#   （缺省 employee / it / admin，口令 123456）。
#
# 本文件必须保存为「带 BOM 的 UTF-8」：Windows PowerShell 5.1 会按系统代码页读取无 BOM 的
# UTF-8 脚本，中文注释会被解析成乱码并报语法错误。证据文件相反，必须写成**无 BOM 的 UTF-8**。
#
# 证据
#   docs/acceptance/2026-10-06-slice-a-return-actions.json（无 BOM 的 UTF-8，脚本自己写出）
#   含：脚本命令与退出码、$baseUrl、五次关键动作的状态码与 traceId、逐条断言（名称/期望/实际
#   状态码/X-Trace-Id/通过与否）、全部 HTTP 调用日志、数据库直查结果、清理前后演示库计数。
#
# ── 片 B 之前的**临时造数**（必须随片 B 落地一起删掉）────────────────────────────
#   `withdraw-supplement-request` 只能作用在 `WAITING_FOR_REQUESTER`，而进入该状态的唯一入口
#   `request-supplement` 属于片 B，当前代码里还没有。因此本脚本对「片 C 专用、自己刚创建并由
#   `it` 领取的那一张工单」直接改库置位（只改脚本自己创建的行，绝不动运行前就存在的行）：
#       UPDATE ticket
#          SET status = 'WAITING_FOR_REQUESTER',
#              action_deadline_at = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 7 DAY),
#              version = version + 1,
#              record_seq = record_seq + 1,
#              updated_at = UTC_TIMESTAMP(3)
#        WHERE ticket_no = '<NO>';
#   并补一条 `SUPPLEMENT_REQUEST` 记录保持时间线连贯：`sequence_no` 取同一次递增后的
#   `record_seq`、`actor_type='USER'`、`actor_user_id` 取该工单负责人、`content` 写成
#   '脚本置位'、`from_status='PROCESSING'`、`to_status='WAITING_FOR_REQUESTER'`、
#   `deadline_at` 取工单的 `action_deadline_at`。这条 UPDATE 也满足 `ck_ticket_status_deadline`
#   （待补充必须有有效期限）。
#   **片 B 的 `request-supplement` 落地后，这一段必须替换为调用接口**，否则脚本验证的是造数而
#   不是真实入口。证据文件里记录了同一说明，避免后人把造数当成真实链路。
#
# 安全边界
#   · 只创建并删除脚本自己创建的工单、记录与参与关系；不触碰运行前就存在的任何行
#     （运行前测一次演示库指纹、清理后再测一次并断言完全相同，含既有工单 FD-20261006-026）
#   · 不执行 docker compose down、不删容器、不删卷、不停任何后端进程；数据库访问一律是
#     `docker exec <mysql 容器> mysql ...`（容器名与库名来自仓库根 .env 与既有脚本约定）
#   · 演示库 root 口令只从 .env 读进进程环境变量、经 `docker exec -e MYSQL_PWD` 转发，
#     不出现在命令行、不打印、不写进证据；登录口令与 accessToken 同样不打印、不落盘
#   · 断言只记录状态码、业务码、traceId 与布尔结论，不回显令牌与口令
#
# SQL 通过**临时 .sql 文件 + stdin** 送入 mysql 客户端，输出重定向到临时文件后按 UTF-8 读取：
#   ① 中文字面量（'脚本置位'）与含中文的结果都不经过控制台代码页，避免造数写坏或断言误判；
#   ② 口令不进命令行，mysql 也不会打印 "Using a password on the command line" 警告；
#   ③ mysql 客户端统一带 --default-character-set=utf8mb4，否则读回的中文会变成 "?"。

param(
    # 基址可覆盖：PowerShell 5.1 没有 ?? 运算符，用 param 默认值 + 下方兜底实现同样语义
    [string]$BaseUrl = $env:FLOWDESK_BASE_URL,
    [string]$OutFile = (Join-Path $PSScriptRoot '..\docs\acceptance\2026-10-06-slice-a-return-actions.json'),
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
$sqlWorkDir = Join-Path $env:TEMP "flowdesk-slice-a-$stamp"
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
$script:seedingInfo = [ordered]@{}

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
# 这样中文既不会被控制台代码页破坏，口令也不会出现在命令行或 stderr 警告里。
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
                actor    = $Actor
                note     = $Note
                method   = $Method
                path     = $Path
                status   = $result.Status
                traceId  = $result.TraceId
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

# 时间线直查（正文/原因只取前 40 个字符 + 实际字符数，避免 1000 字符用例把证据撑爆）。
function Get-TicketRecordDbRows {
    param([string]$TicketNo)
    $sql = "SELECT sequence_no, record_type, actor_type, IFNULL(actor_user_id,-1), " +
    "IFNULL(LEFT(content,40),'-'), IFNULL(CHAR_LENGTH(content),-1), " +
    "IFNULL(LEFT(reason,40),'-'), IFNULL(CHAR_LENGTH(reason),-1), " +
    "IFNULL(from_status,'-'), IFNULL(to_status,'-') " +
    "FROM ticket_record WHERE ticket_id = (SELECT id FROM ticket WHERE ticket_no = '$TicketNo') " +
    "ORDER BY sequence_no;"
    $result = Invoke-MySql -Sql $sql
    $rows = [System.Collections.Generic.List[object]]::new()
    if ($result.exitCode -ne 0) { return @() }
    foreach ($line in $result.lines) {
        $columns = @($line -split "`t")
        if ($columns.Count -lt 10) { continue }
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
            order       = $Order
            actor       = $Actor
            action      = $Action
            method      = $Method
            path        = $Path
            ticketNo    = $TicketNo
            httpStatus  = $Result.Status
            traceId     = $Result.TraceId
            statusAfter = $StatusAfter
            versionAfter = $version
        })
}

# 片 A 两个端点的请求体：版本 + 原因（原因 strip 后 1～1000 个字符）。
function New-ReasonBody {
    param([long]$Version, [string]$Reason)
    return @{ version = $Version; reason = $Reason }
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
$existingTicketNo = 'FD-20261006-026'
$script:ticketsInfo = [ordered]@{}
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
    Add-Assertion -Name 'baseline.existingTicketPresent' -Condition ($demoBefore.ticketNos -match [regex]::Escape($existingTicketNo)) `
        -Expected "运行前存在既有工单 $existingTicketNo" `
        -Detail "工单号=[$($demoBefore.ticketNos)]；本脚本只新增再删除自己的行，绝不修改它"
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
    $adminUserId = [int]$adminData.id
    $employeePerms = @($employeeData.permissions)
    $itPerms = @($itData.permissions)
    $adminPerms = @($adminData.permissions)

    Add-Assertion -Name 'perm.employee.hasRequesterAction' -Condition ($employeePerms -contains 'TICKET_REQUESTER_ACTION') `
        -Expected 'employee 具备 TICKET_REQUESTER_ACTION' `
        -Detail "roles=$(@($employeeData.roles) -join '|') ticketPerms=$((@($employeePerms | Where-Object { $_ -like 'TICKET*' })) -join '|')" `
        -Result $meEmployee
    Add-Assertion -Name 'perm.employee.lacksProcess' -Condition (-not ($employeePerms -contains 'TICKET_PROCESS')) `
        -Expected 'employee 不具备 TICKET_PROCESS（断言 2 的前提）' `
        -Detail "hasTICKET_PROCESS=$($employeePerms -contains 'TICKET_PROCESS')" -Result $meEmployee
    Add-Assertion -Name 'perm.it.hasProcess' -Condition ($itPerms -contains 'TICKET_PROCESS') `
        -Expected 'it 具备 TICKET_PROCESS' `
        -Detail "roles=$(@($itData.roles) -join '|') hasTICKET_PROCESS=$($itPerms -contains 'TICKET_PROCESS')" -Result $meIt
    Add-Assertion -Name 'perm.it.lacksRequesterAction' -Condition (-not ($itPerms -contains 'TICKET_REQUESTER_ACTION')) `
        -Expected 'it 不具备 TICKET_REQUESTER_ACTION（断言 3 的前提）' `
        -Detail "hasTICKET_REQUESTER_ACTION=$($itPerms -contains 'TICKET_REQUESTER_ACTION')" -Result $meIt
    $script:observed.Add("admin.roles=$(@($adminData.roles) -join '|')")
    $script:observed.Add("admin.hasTICKET_PROCESS=$($adminPerms -contains 'TICKET_PROCESS') hasTICKET_REQUESTER_ACTION=$($adminPerms -contains 'TICKET_REQUESTER_ACTION')")
    $script:notes.Add('admin 在本机演示库里同时持有 EMPLOYEE + IT_SUPPORT + SYSTEM_ADMIN（已知漂移），脚本只记录它的实际结果，不做绝对值断言。')

    # ── 4. 前置闸门：片 A 两个端点必须真的挂在目标后端上（否则立刻失败，不做无意义断言） ──
    $probeReport = Send-Req -Client $employeeClient -Method Post `
        -Path "/fd/v1/tickets/$unknownTicketNo/actions/report-unresolved" -Token $employeeToken `
        -Body (New-ReasonBody -Version 0 -Reason 'preflight') -Actor 'employee' -Note 'preflight.report-unresolved'
    $probeWithdraw = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$unknownTicketNo/actions/withdraw-supplement-request" -Token $itToken `
        -Body (New-ReasonBody -Version 0 -Reason 'preflight') -Actor 'it' -Note 'preflight.withdraw-supplement-request'
    $probeReportMapped = (($probeReport.Status -eq 404) -and ((Get-Code $probeReport) -eq 'TICKET_NOT_FOUND'))
    $probeWithdrawMapped = (($probeWithdraw.Status -eq 404) -and ((Get-Code $probeWithdraw) -eq 'TICKET_NOT_FOUND'))
    $script:preflightInfo = [ordered]@{
        baseUrl                              = $baseUrl
        reportUnresolvedStatus               = $probeReport.Status
        reportUnresolvedCode                 = (Get-Code $probeReport)
        reportUnresolvedTraceId              = $probeReport.TraceId
        withdrawSupplementRequestStatus      = $probeWithdraw.Status
        withdrawSupplementRequestCode        = (Get-Code $probeWithdraw)
        withdrawSupplementRequestTraceId     = $probeWithdraw.TraceId
        mappingConfirmed                     = ($probeReportMapped -and $probeWithdrawMapped)
    }
    Add-Assertion -Name 'preflight.reportUnresolved.endpointMapped' -Condition $probeReportMapped `
        -Expected "404 + TICKET_NOT_FOUND（编号不存在，端点存在）" `
        -Detail "status=$($probeReport.Status) code=$(Get-Code $probeReport)；若为 405/RESOURCE_NOT_FOUND 说明目标后端还没有该端点" `
        -Result $probeReport
    Add-Assertion -Name 'preflight.withdrawSupplementRequest.endpointMapped' -Condition $probeWithdrawMapped `
        -Expected "404 + TICKET_NOT_FOUND（编号不存在，端点存在）" `
        -Detail "status=$($probeWithdraw.Status) code=$(Get-Code $probeWithdraw)" -Result $probeWithdraw
    if (-not ($probeReportMapped -and $probeWithdrawMapped)) {
        throw ("目标后端 $baseUrl 上没有片 A 的两个端点（或返回不符合契约）：report-unresolved => " +
            "$($probeReport.Status)/$(Get-Code $probeReport)、withdraw-supplement-request => " +
            "$($probeWithdraw.Status)/$(Get-Code $probeWithdraw)。请确认验收目标是**用最新代码启动的后端**；" +
            '8081 上用户自己启动的实例可能仍是旧代码。')
    }

    # ── 5. 断言 1：两个动作各一条匿名请求 → 401 ──────────────────────────────
    $anonReport = Send-Req -Client $anonymousClient -Method Post `
        -Path "/fd/v1/tickets/$unknownTicketNo/actions/report-unresolved" `
        -Body (New-ReasonBody -Version 0 -Reason '匿名') -Actor 'anonymous' -Note 'a1.anonymous'
    $anonWithdraw = Send-Req -Client $anonymousClient -Method Post `
        -Path "/fd/v1/tickets/$unknownTicketNo/actions/withdraw-supplement-request" `
        -Body (New-ReasonBody -Version 0 -Reason '匿名') -Actor 'anonymous' -Note 'a1.anonymous'
    Add-Assertion -Name 'a1.anonymous.reportUnresolved.401' -Condition ($anonReport.Status -eq 401) `
        -Expected '401（无令牌一律先被认证闸门拦下）' `
        -Detail "status=$($anonReport.Status) code=$(Get-Code $anonReport)" -Result $anonReport
    Add-Assertion -Name 'a1.anonymous.withdrawSupplementRequest.401' -Condition ($anonWithdraw.Status -eq 401) `
        -Expected '401（无令牌一律先被认证闸门拦下）' `
        -Detail "status=$($anonWithdraw.Status) code=$(Get-Code $anonWithdraw)" -Result $anonWithdraw

    # ── 6. 分类选项 + 支持性造单：断言 2/3/7 需要一张「employee 提交、it 领取后仍在 PROCESSING」的工单 ──
    $options = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/categories/options' -Token $employeeToken `
        -Actor 'employee' -Note 'categories.options'
    $optionItems = @(Get-Data $options)
    Add-Assertion -Name 'categories.options.nonEmpty' -Condition (($options.Status -eq 200) -and ($optionItems.Count -ge 1)) `
        -Expected '200 且至少一个启用分类' -Detail "status=$($options.Status) count=$($optionItems.Count)" -Result $options
    if ($optionItems.Count -lt 1) { throw '没有可用的启用分类，无法创建工单' }
    $categoryId = [long]$optionItems[0].id

    $createB = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
        -Title "片A验收-处理中工单-$stamp" -Description '片 A 真实栈验收：权限闸门与状态不符（PROCESSING）用例的载体。' `
        -CategoryId $categoryId -Actor 'employee' -Note 'setup.create-ticket(B)'
    $ticketB = (Get-Data $createB).ticketNo
    $versionB = [long](Get-Data $createB).version
    Add-Assertion -Name 'setup.ticketB.create.201' -Condition ($createB.Status -eq 201) -Expected '201' `
        -Detail "ticketNo=$ticketB version=$versionB" -Result $createB
    $claimB = Send-Req -Client $itClient -Method Post -Path "/fd/v1/tickets/$ticketB/actions/claim" -Token $itToken `
        -Body @{ version = $versionB } -Actor 'it' -Note 'setup.claim(B)'
    Add-Assertion -Name 'setup.ticketB.claim.200' -Condition ($claimB.Status -eq 200) -Expected '200' `
        -Detail "ticketNo=$ticketB status=$((Get-Data $claimB).status) version=$((Get-Data $claimB).version)" -Result $claimB
    if ($claimB.Status -ne 200) { throw "工单 $ticketB 领取失败（status=$($claimB.Status) code=$(Get-Code $claimB)），后续断言无法进行" }
    $versionB = [long](Get-Data $claimB).version

    # ── 7. 断言 2：employee 调 withdraw-supplement-request → 403（缺 TICKET_PROCESS） ──
    # 选一张 employee 自己提交、能看见、且状态为 PROCESSING 的工单：若可见性或状态闸门在前会得到
    # 409，实测 403 才说明「权限闸门」先判。
    $empWithdraw = Send-Req -Client $employeeClient -Method Post `
        -Path "/fd/v1/tickets/$ticketB/actions/withdraw-supplement-request" -Token $employeeToken `
        -Body (New-ReasonBody -Version $versionB -Reason '员工越权撤回') -Actor 'employee' -Note 'a2.employee.withdraw'
    Add-Assertion -Name 'a2.employee.withdrawSupplementRequest.403' `
        -Condition (($empWithdraw.Status -eq 403) -and ((Get-Code $empWithdraw) -eq 'TICKET_ACTION_FORBIDDEN')) `
        -Expected '403 + TICKET_ACTION_FORBIDDEN（employee 缺 TICKET_PROCESS；权限闸门先于可见性与状态）' `
        -Detail "status=$($empWithdraw.Status) code=$(Get-Code $empWithdraw) ticket=$ticketB（employee 是该单提交人、可见；若状态闸门在前会是 409）" `
        -Result $empWithdraw

    # ── 8. 断言 3：it 调 report-unresolved → 403（缺 TICKET_REQUESTER_ACTION） ──
    # 同一张 PROCESSING 工单：it 是负责人（可见），但不是提交人。若身份闸门在前会得到 409。
    $itReportOnB = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$ticketB/actions/report-unresolved" -Token $itToken `
        -Body (New-ReasonBody -Version $versionB -Reason 'IT 越权反馈未解决') -Actor 'it' -Note 'a3.it.reportUnresolved'
    Add-Assertion -Name 'a3.it.reportUnresolved.403' `
        -Condition (($itReportOnB.Status -eq 403) -and ((Get-Code $itReportOnB) -eq 'TICKET_ACTION_FORBIDDEN')) `
        -Expected '403 + TICKET_ACTION_FORBIDDEN（it 缺 TICKET_REQUESTER_ACTION；权限闸门先于身份闸门）' `
        -Detail "status=$($itReportOnB.Status) code=$(Get-Code $itReportOnB) ticket=$ticketB（it 是负责人、可见；若身份闸门在前会是 409）" `
        -Result $itReportOnB

    # ── 9. 断言 4：report-unresolved 的链路准备（employee 建单 → it 领取 → it 提交解决结果） ──
    $createA = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
        -Title "片A验收-反馈未解决-$stamp" -Description '片 A 真实栈验收：report-unresolved 全链路。' `
        -CategoryId $categoryId -Actor 'employee' -Note 'setup.create-ticket(A)'
    $ticketA = (Get-Data $createA).ticketNo
    $versionA = [long](Get-Data $createA).version
    Add-Assertion -Name 'a4.ticketA.create.201' -Condition ($createA.Status -eq 201) -Expected '201' `
        -Detail "ticketNo=$ticketA version=$versionA" -Result $createA
    if ($createA.Status -ne 201) { throw "创建工单 A 失败：status=$($createA.Status) code=$(Get-Code $createA)" }

    $claimA = Send-Req -Client $itClient -Method Post -Path "/fd/v1/tickets/$ticketA/actions/claim" -Token $itToken `
        -Body @{ version = $versionA } -Actor 'it' -Note 'a4.claim(A)'
    Add-Assertion -Name 'a4.ticketA.claim.200' -Condition ($claimA.Status -eq 200) -Expected '200' `
        -Detail "status=$($claimA.Status) status=$((Get-Data $claimA).status) version=$((Get-Data $claimA).version)" -Result $claimA
    if ($claimA.Status -ne 200) { throw "领取工单 A 失败：status=$($claimA.Status) code=$(Get-Code $claimA)" }
    $versionA = [long](Get-Data $claimA).version
    Add-KeyAction -Order 1 -Actor 'it' -Action 'claim' -Method 'POST' `
        -Path "/fd/v1/tickets/$ticketA/actions/claim" -TicketNo $ticketA -Result $claimA -StatusAfter 'PROCESSING'

    $submit1 = Send-Req -Client $itClient -Method Post -Path "/fd/v1/tickets/$ticketA/actions/submit-resolution" `
        -Token $itToken -Body @{ version = $versionA; content = '已重装驱动，请确认是否恢复。' } -Actor 'it' -Note 'a4.submit-resolution(A)'
    Add-Assertion -Name 'a4.ticketA.submitResolution.200' -Condition ($submit1.Status -eq 200) -Expected '200' `
        -Detail "status=$($submit1.Status) status=$((Get-Data $submit1).status) version=$((Get-Data $submit1).version)" -Result $submit1
    if ($submit1.Status -ne 200) { throw "工单 A 提交解决结果失败：status=$($submit1.Status) code=$(Get-Code $submit1)" }
    $submit1Data = Get-Data $submit1
    $versionAfterSubmit1 = [long]$submit1Data.version
    Add-Assertion -Name 'a4.ticketA.status.WAITING_FOR_CONFIRMATION' -Condition ($submit1Data.status -eq 'WAITING_FOR_CONFIRMATION') `
        -Expected 'WAITING_FOR_CONFIRMATION' -Detail "status=$($submit1Data.status)" -Result $submit1
    Add-Assertion -Name 'a4.ticketA.deadline.present' -Condition ($null -ne $submit1Data.actionDeadlineAt) `
        -Expected '待确认必须有确认期限' -Detail "actionDeadlineAt=$($submit1Data.actionDeadlineAt)" -Result $submit1
    Add-KeyAction -Order 2 -Actor 'it' -Action 'submit-resolution' -Method 'POST' `
        -Path "/fd/v1/tickets/$ticketA/actions/submit-resolution" -TicketNo $ticketA -Result $submit1 -StatusAfter 'WAITING_FOR_CONFIRMATION'

    # ── 10. 断言 5：提交人详情的 allowedActions 恰好是 confirm-resolution + report-unresolved ──
    $employeeDetailA = Send-Req -Client $employeeClient -Method Get -Path "/fd/v1/tickets/$ticketA" -Token $employeeToken `
        -Actor 'employee' -Note 'a5.detail(A,requester)'
    $employeeDetailAData = Get-Data $employeeDetailA
    $requesterActions = @($employeeDetailAData.allowedActions)
    $requesterActionsSorted = (@($requesterActions | Sort-Object) -join ',')
    Add-Assertion -Name 'a5.requester.allowedActions.exact' -Condition ($requesterActionsSorted -eq 'confirm-resolution,report-unresolved') `
        -Expected '恰好 [confirm-resolution, report-unresolved]' `
        -Detail "allowedActions=$($requesterActions -join ',') status=$($employeeDetailAData.status)" -Result $employeeDetailA

    # ── 11. 断言 6：编号不存在 → 404 TICKET_NOT_FOUND ────────────────────────
    $unknownReport = Send-Req -Client $employeeClient -Method Post `
        -Path "/fd/v1/tickets/$unknownTicketNo/actions/report-unresolved" -Token $employeeToken `
        -Body (New-ReasonBody -Version 0 -Reason '编号不存在') -Actor 'employee' -Note 'a6.unknown.reportUnresolved'
    Add-Assertion -Name 'a6.unknownTicketNo.404' `
        -Condition (($unknownReport.Status -eq 404) -and ((Get-Code $unknownReport) -eq 'TICKET_NOT_FOUND')) `
        -Expected "404 + TICKET_NOT_FOUND（$unknownTicketNo）" `
        -Detail "status=$($unknownReport.Status) code=$(Get-Code $unknownReport)" -Result $unknownReport

    # ── 12. 断言 7：状态不符（工单 B 还在 PROCESSING）→ 409 且 data 是当前快照 ──
    $stateMismatch = Send-Req -Client $employeeClient -Method Post `
        -Path "/fd/v1/tickets/$ticketB/actions/report-unresolved" -Token $employeeToken `
        -Body (New-ReasonBody -Version $versionB -Reason '状态不符用例') -Actor 'employee' -Note 'a7.stateMismatch(B)'
    $stateMismatchData = Get-ErrData $stateMismatch
    $dbRowB = Get-TicketDbRow -TicketNo $ticketB
    Add-Assertion -Name 'a7.stateMismatch.409' `
        -Condition (($stateMismatch.Status -eq 409) -and ((Get-Code $stateMismatch) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT' -Detail "status=$($stateMismatch.Status) code=$(Get-Code $stateMismatch) ticket=$ticketB" `
        -Result $stateMismatch
    Add-Assertion -Name 'a7.conflictSnapshot.isCurrent' `
        -Condition (($null -ne $dbRowB) -and ($stateMismatchData.version -eq $dbRowB.version) -and ($stateMismatchData.status -eq $dbRowB.status) -and ($stateMismatchData.status -eq 'PROCESSING')) `
        -Expected 'data.version/data.status = 库中当前快照' `
        -Detail "data.version=$($stateMismatchData.version) data.status=$($stateMismatchData.status)；库中 version=$($dbRowB.version) status=$($dbRowB.status)" `
        -Result $stateMismatch

    # ── 13. 断言 8：version 过期（旧版本号重发）→ 409 且快照版本等于库里最新值 ──
    $dbRowABefore = Get-TicketDbRow -TicketNo $ticketA
    $staleReport = Send-Req -Client $employeeClient -Method Post `
        -Path "/fd/v1/tickets/$ticketA/actions/report-unresolved" -Token $employeeToken `
        -Body (New-ReasonBody -Version ($versionAfterSubmit1 + 9) -Reason '过期版本重发') -Actor 'employee' -Note 'a8.staleVersion(A)'
    $staleReportData = Get-ErrData $staleReport
    $dbRowAStale = Get-TicketDbRow -TicketNo $ticketA
    Add-Assertion -Name 'a8.staleVersion.409' `
        -Condition (($staleReport.Status -eq 409) -and ((Get-Code $staleReport) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT' `
        -Detail "status=$($staleReport.Status) code=$(Get-Code $staleReport) 发送version=$($versionAfterSubmit1 + 9)" -Result $staleReport
    Add-Assertion -Name 'a8.conflictSnapshot.versionEqualsDbLatest' `
        -Condition (($null -ne $dbRowAStale) -and ($staleReportData.version -eq $dbRowAStale.version) -and ($dbRowAStale.version -eq $versionAfterSubmit1)) `
        -Expected 'data.version = 库中最新 version（且状态未变）' `
        -Detail "data.version=$($staleReportData.version) data.status=$($staleReportData.status)；库中 version=$($dbRowAStale.version) status=$($dbRowAStale.status)（过期请求前 version=$($dbRowABefore.version)）" `
        -Result $staleReport

    # ── 14. 断言 9：原因边界（空白 → 400；1000 字符 → 200；1001 字符 → 400） ──
    $blankReport = Send-Req -Client $employeeClient -Method Post `
        -Path "/fd/v1/tickets/$ticketA/actions/report-unresolved" -Token $employeeToken `
        -Body (New-ReasonBody -Version $versionAfterSubmit1 -Reason '   ') -Actor 'employee' -Note 'a9.blankReason(A)'
    Add-Assertion -Name 'a9.blankReason.400' `
        -Condition (($blankReport.Status -eq 400) -and ((Get-Code $blankReport) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（原因 strip 后为空）' `
        -Detail "status=$($blankReport.Status) code=$(Get-Code $blankReport)" -Result $blankReport

    $reason1000 = ('测' * 1000)
    $maxReasonReport = Send-Req -Client $employeeClient -Method Post `
        -Path "/fd/v1/tickets/$ticketA/actions/report-unresolved" -Token $employeeToken `
        -Body (New-ReasonBody -Version $versionAfterSubmit1 -Reason $reason1000) -Actor 'employee' -Note 'a9.reason1000(A)'
    $maxReasonData = Get-Data $maxReasonReport
    Add-Assertion -Name 'a9.reason1000.200' -Condition ($maxReasonReport.Status -eq 200) `
        -Expected '200（原因长度上限 1000）' `
        -Detail "status=$($maxReasonReport.Status) 原因长度=$($reason1000.Length) status=$($maxReasonData.status) version=$($maxReasonData.version)" `
        -Result $maxReasonReport

    # 上一步已经把工单退回处理中：再提交一次解决结果，才能测 1001 字符与正常路径。
    $resubmit = Send-Req -Client $itClient -Method Post -Path "/fd/v1/tickets/$ticketA/actions/submit-resolution" `
        -Token $itToken -Body @{ version = $maxReasonData.version; content = '再次提交解决结果（1001 字符用例的载体）。' } `
        -Actor 'it' -Note 'a9.resubmit-resolution(A)'
    Add-Assertion -Name 'a9.resubmitRevision.200' -Condition ($resubmit.Status -eq 200) -Expected '200' `
        -Detail "status=$($resubmit.Status) status=$((Get-Data $resubmit).status) version=$((Get-Data $resubmit).version)" -Result $resubmit
    if ($resubmit.Status -ne 200) { throw "工单 A 再次提交解决结果失败：status=$($resubmit.Status) code=$(Get-Code $resubmit)" }
    $versionAfterSubmit2 = [long](Get-Data $resubmit).version

    $reason1001 = ('测' * 1001)
    $tooLongReport = Send-Req -Client $employeeClient -Method Post `
        -Path "/fd/v1/tickets/$ticketA/actions/report-unresolved" -Token $employeeToken `
        -Body (New-ReasonBody -Version $versionAfterSubmit2 -Reason $reason1001) -Actor 'employee' -Note 'a9.reason1001(A)'
    Add-Assertion -Name 'a9.reason1001.400' `
        -Condition (($tooLongReport.Status -eq 400) -and ((Get-Code $tooLongReport) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（超过 1000 个字符）' `
        -Detail "status=$($tooLongReport.Status) code=$(Get-Code $tooLongReport) 原因长度=$($reason1001.Length)" -Result $tooLongReport

    # ── 15. 断言 10：正常路径 → 200、PROCESSING、期限字段消失、版本 +1 ──
    $unresolvedReason = '按照提示重启后仍然报同样的错，问题没有解决。'
    $report = Send-Req -Client $employeeClient -Method Post `
        -Path "/fd/v1/tickets/$ticketA/actions/report-unresolved" -Token $employeeToken `
        -Body (New-ReasonBody -Version $versionAfterSubmit2 -Reason $unresolvedReason) -Actor 'employee' -Note 'a10.report-unresolved(A)'
    $reportData = Get-Data $report
    Add-Assertion -Name 'a10.reportUnresolved.200' -Condition ($report.Status -eq 200) -Expected '200' `
        -Detail "status=$($report.Status) code=$(Get-Code $report)" -Result $report
    Add-Assertion -Name 'a10.status.PROCESSING' -Condition ($reportData.status -eq 'PROCESSING') `
        -Expected 'PROCESSING' -Detail "status=$($reportData.status)" -Result $report
    Add-Assertion -Name 'a10.deadlineField.absent' -Condition (Test-DeadlineFieldAbsent -Result $report) `
        -Expected '响应里不出现 actionDeadlineAt（non_null 序列化）' `
        -Detail "报文含 actionDeadlineAt=$($report.Body -match 'actionDeadlineAt')；解析值=[$($reportData.actionDeadlineAt)]" -Result $report
    Add-Assertion -Name 'a10.version.plusOne' -Condition ($reportData.version -eq ($versionAfterSubmit2 + 1)) `
        -Expected "version = 提交解决结果后 +1（$($versionAfterSubmit2 + 1)）" `
        -Detail "expected=$($versionAfterSubmit2 + 1) actual=$($reportData.version)" -Result $report
    Add-KeyAction -Order 3 -Actor 'employee' -Action 'report-unresolved' -Method 'POST' `
        -Path "/fd/v1/tickets/$ticketA/actions/report-unresolved" -TicketNo $ticketA -Result $report -StatusAfter 'PROCESSING'

    # ── 16. 断言 11：数据库直查（状态与期限、负责人、完成方式） ──────────────
    $dbRowAAfterReport = Get-TicketDbRow -TicketNo $ticketA
    $reportVersion = [long]$reportData.version
    Assert-Db -Name 'a11.db.reportUnresolved.snapshot' -Expected `
        'status=PROCESSING、action_deadline_at IS NULL、ended_at IS NULL、assignee_id=it、completion_method IS NULL' `
        -Sql ("SELECT status, IFNULL(DATE_FORMAT(action_deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'), " +
        "IF(ended_at IS NULL,'NULL','SET'), IFNULL(assignee_id,-1), IFNULL(completion_method,'NULL'), version " +
        "FROM ticket WHERE ticket_no = '$ticketA';") -Check {
        param($c)
        ($c[0] -eq 'PROCESSING') -and ($c[1] -eq 'NULL') -and ($c[2] -eq 'NULL') -and
        ([int]$c[3] -eq $itUserId) -and ($c[4] -eq 'NULL') -and ([int]$c[5] -eq $reportVersion)
    }
    Add-Assertion -Name 'a11.db.assigneeStillIt' -Condition (($null -ne $dbRowAAfterReport) -and ($dbRowAAfterReport.assigneeId -eq $itUserId)) `
        -Expected "assignee_id 仍是 it（$itUserId）" `
        -Detail "assignee_id=$($dbRowAAfterReport.assigneeId)；退回不等于换人"
    Add-Assertion -Name 'a11.db.versionMatchesResponse' -Condition (($null -ne $dbRowAAfterReport) -and ($dbRowAAfterReport.version -eq $reportVersion)) `
        -Expected '库中 version 与响应 version 一致' `
        -Detail "db.version=$($dbRowAAfterReport.version) response.version=$reportVersion record_seq=$($dbRowAAfterReport.recordSeq)"

    # ── 17. 断言 12：时间线最后一条是 UNSATISFIED_FEEDBACK（原因与状态迁移正确） ──
    $timelineA = Send-Req -Client $employeeClient -Method Get `
        -Path "/fd/v1/tickets/$ticketA/records?page=1&size=50" -Token $employeeToken -Actor 'employee' -Note 'a12.records(A)'
    $timelineAItems = @((Get-Data $timelineA).items)
    Add-Assertion -Name 'a12.timeline.200' -Condition ($timelineA.Status -eq 200) -Expected '200' `
        -Detail "status=$($timelineA.Status) count=$($timelineAItems.Count)" -Result $timelineA
    $lastRecord = $timelineAItems | Select-Object -Last 1
    Add-Assertion -Name 'a12.timeline.lastIsUNSATISFIED_FEEDBACK' -Condition ($null -ne $lastRecord -and $lastRecord.recordType -eq 'UNSATISFIED_FEEDBACK') `
        -Expected '最后一条记录类型 = UNSATISFIED_FEEDBACK' `
        -Detail "recordType=$($lastRecord.recordType) sequenceNo=$($lastRecord.sequenceNo) types=$((@($timelineAItems | ForEach-Object { $_.recordType })) -join ',')" `
        -Result $timelineA
    Add-Assertion -Name 'a12.timeline.reasonMatches' -Condition ($null -ne $lastRecord -and $lastRecord.context.reason -eq $unresolvedReason) `
        -Expected 'context.reason 与提交内容一致' `
        -Detail "context.reason=[$(Get-BriefText -Text $lastRecord.context.reason)]" -Result $timelineA
    Add-Assertion -Name 'a12.timeline.transitionCorrect' `
        -Condition ($null -ne $lastRecord -and $lastRecord.context.fromStatus -eq 'WAITING_FOR_CONFIRMATION' -and $lastRecord.context.toStatus -eq 'PROCESSING') `
        -Expected 'fromStatus=WAITING_FOR_CONFIRMATION → toStatus=PROCESSING' `
        -Detail "from=$($lastRecord.context.fromStatus) to=$($lastRecord.context.toStatus) actorId=$($lastRecord.actor.id)" -Result $timelineA
    $script:observed.Add("a12.ticketA.timeline=$((@($timelineAItems | ForEach-Object { $_.recordType })) -join ',')")

    # ── 18. 断言 13：回到处理中之后还能继续处理（it 再追加处理记录 → 200） ──
    $processAfterReport = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$ticketA/actions/add-processing-record" -Token $itToken `
        -Body @{ version = $reportVersion; content = '收到反馈，继续排查（往返之后仍可处理）。' } -Actor 'it' -Note 'a13.add-processing-record(A)'
    Add-Assertion -Name 'a13.addProcessingRecord.200' -Condition ($processAfterReport.Status -eq 200) `
        -Expected '200（往返之后工单仍可继续处理）' `
        -Detail "status=$($processAfterReport.Status) status=$((Get-Data $processAfterReport).status) version=$((Get-Data $processAfterReport).version)" `
        -Result $processAfterReport

    # ── 19. 断言 14：此时 it 详情的 allowedActions 恰好是 add-processing-record + submit-resolution ──
    $itDetailA = Send-Req -Client $itClient -Method Get -Path "/fd/v1/tickets/$ticketA" -Token $itToken `
        -Actor 'it' -Note 'a14.detail(A,assignee)'
    $itDetailAData = Get-Data $itDetailA
    $itActionsA = @($itDetailAData.allowedActions)
    Add-Assertion -Name 'a14.assignee.allowedActions.exact' `
        -Condition ((@($itActionsA | Sort-Object) -join ',') -eq 'add-processing-record,submit-resolution') `
        -Expected '恰好 [add-processing-record, submit-resolution]（片 C/D 之前只有这两个）' `
        -Detail "allowedActions=$($itActionsA -join ',') status=$($itDetailAData.status)" -Result $itDetailA

    # ── 20. 断言 15：片 C —— 用 SQL 把一张自己创建的已领取工单置位到 WAITING_FOR_REQUESTER ──
    # 片 B 的 request-supplement 尚未实现，这是唯一的进状态手段；片 B 落地后必须改为调接口。
    $createC = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
        -Title "片A验收-撤回补充请求-$stamp" -Description '片 A 真实栈验收：withdraw-supplement-request（SQL 临时置位）。' `
        -CategoryId $categoryId -Actor 'employee' -Note 'setup.create-ticket(C)'
    $ticketC = (Get-Data $createC).ticketNo
    $versionC = [long](Get-Data $createC).version
    Add-Assertion -Name 'a15.ticketC.create.201' -Condition ($createC.Status -eq 201) -Expected '201' `
        -Detail "ticketNo=$ticketC version=$versionC" -Result $createC
    if ($createC.Status -ne 201) { throw "创建工单 C 失败：status=$($createC.Status) code=$(Get-Code $createC)" }
    $claimC = Send-Req -Client $itClient -Method Post -Path "/fd/v1/tickets/$ticketC/actions/claim" -Token $itToken `
        -Body @{ version = $versionC } -Actor 'it' -Note 'setup.claim(C)'
    Add-Assertion -Name 'a15.ticketC.claim.200' -Condition ($claimC.Status -eq 200) -Expected '200' `
        -Detail "status=$($claimC.Status) status=$((Get-Data $claimC).status) version=$((Get-Data $claimC).version)" -Result $claimC
    if ($claimC.Status -ne 200) { throw "领取工单 C 失败：status=$($claimC.Status) code=$(Get-Code $claimC)" }
    $versionC = [long](Get-Data $claimC).version

    $seedUpdateSql = "UPDATE ticket SET status = 'WAITING_FOR_REQUESTER', " +
    "action_deadline_at = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 7 DAY), " +
    "version = version + 1, record_seq = record_seq + 1, updated_at = UTC_TIMESTAMP(3) " +
    "WHERE ticket_no = '$ticketC';"
    $seedRecordSql = "INSERT INTO ticket_record " +
    "(ticket_id, sequence_no, record_type, actor_type, actor_user_id, content, from_status, to_status, deadline_at, created_at) " +
    "SELECT id, record_seq, 'SUPPLEMENT_REQUEST', 'USER', assignee_id, '脚本置位', " +
    "'PROCESSING', 'WAITING_FOR_REQUESTER', action_deadline_at, UTC_TIMESTAMP(3) " +
    "FROM ticket WHERE ticket_no = '$ticketC';"
    # 两条语句放进同一个事务：置位成功但记录插入失败时，不会留下"状态与时间线不一致"的中间态
    # （mysql 客户端遇错即停，连接关闭时未提交的事务自动回滚）。
    $seedSql = "START TRANSACTION;`n" + $seedUpdateSql + "`n" + $seedRecordSql + "`nCOMMIT;"
    $seedResult = Invoke-MySql -Sql $seedSql
    Add-Assertion -Name 'a15.sqlSeed.executed' -Condition ($seedResult.exitCode -eq 0) `
        -Expected '同一事务内的两条临时造数 SQL 执行成功（exit=0）' `
        -Detail "exitCode=$($seedResult.exitCode) stderr=[$($seedResult.stderrText)]；这是**片 B 之前的临时手段**，片 B 落地后应改走 request-supplement 接口"
    if ($seedResult.exitCode -ne 0) { throw "临时造数失败：$($seedResult.stderrText)" }

    $seedRowC = Get-TicketDbRow -TicketNo $ticketC
    $seedRecordRows = @(Get-TicketRecordDbRows -TicketNo $ticketC)
    $seedRecord = $seedRecordRows | Where-Object { $_.recordType -eq 'SUPPLEMENT_REQUEST' } | Select-Object -First 1
    Assert-Db -Name 'a15.db.seededToWaitingForRequester' -Expected `
        'status=WAITING_FOR_REQUESTER、action_deadline_at 非空、version/record_seq 各 +1、负责人不变' `
        -Sql ("SELECT status, IFNULL(DATE_FORMAT(action_deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'), " +
        "IF(ended_at IS NULL,'NULL','SET'), IFNULL(assignee_id,-1), IFNULL(completion_method,'NULL'), version, record_seq " +
        "FROM ticket WHERE ticket_no = '$ticketC';") -Check {
        param($c)
        ($c[0] -eq 'WAITING_FOR_REQUESTER') -and ($c[1] -ne 'NULL') -and ($c[2] -eq 'NULL') -and
        ([int]$c[3] -eq $itUserId) -and ($c[4] -eq 'NULL') -and ([int]$c[5] -eq $versionC + 1)
    }
    Add-Assertion -Name 'a15.db.seedRecordAppended' `
        -Condition ($null -ne $seedRecord -and $seedRecord.reasonLen -eq -1 -and $seedRecord.fromStatus -eq 'PROCESSING' -and $seedRecord.toStatus -eq 'WAITING_FOR_REQUESTER' -and $seedRecord.actorId -eq $itUserId) `
        -Expected "补一条 SUPPLEMENT_REQUEST（sequence_no=新的 record_seq、actor_type=USER、actor_user_id=负责人、from_status=PROCESSING、to_status=WAITING_FOR_REQUESTER）" `
        -Detail "sequenceNo=$($seedRecord.sequenceNo) actorType=$($seedRecord.actorType) actorId=$($seedRecord.actorId) from=$($seedRecord.fromStatus) to=$($seedRecord.toStatus) contentLen=$($seedRecord.contentLen)"
    Add-Assertion -Name 'a15.db.seedContentReadBack' -Condition ($null -ne $seedRecord -and $seedRecord.content -eq '脚本置位') `
        -Expected "content 读回 '脚本置位'（同时证明 SQL 里的中文没有被编码破坏）" `
        -Detail "content=[$($seedRecord.content)]（长度 $($seedRecord.contentLen)）"
    $script:seedingInfo = [ordered]@{
        reason                = 'withdraw-supplement-request 只能作用在 WAITING_FOR_REQUESTER，而进入该状态的唯一接口 request-supplement 属于片 B，当前代码尚无；因此本脚本对**自己刚创建、并由 it 领取**的这一张工单直接改库置位。'
        temporaryUntilSliceB  = $true
        replaceWithEndpoint   = 'POST /fd/v1/tickets/{ticketNo}/actions/request-supplement（片 B 落地后必须替换为调用接口，否则验证的是造数而不是真实入口）'
        ticketNo              = $ticketC
        updateSql             = $seedUpdateSql
        insertRecordSql       = $seedRecordSql
        executedSql           = $seedSql
        wrappedInTransaction  = $true
        sqlExitCode           = $seedResult.exitCode
        sqlStderr             = $seedResult.stderrText
        ticketRowAfterSeed    = $seedRowC.raw
        deadlineRule          = 'action_deadline_at = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 7 DAY)，满足 ck_ticket_status_deadline（待补充必须有有效期限）'
        touchedRows           = "仅 ticket.ticket_no='$ticketC' 这一行，以及它新增的一条 ticket_record；运行前就存在的行一行未动"
    }

    # 置位后 it 的详情：allowedActions 恰好只有 withdraw-supplement-request（片 A 的两格之一）
    $itDetailC = Send-Req -Client $itClient -Method Get -Path "/fd/v1/tickets/$ticketC" -Token $itToken `
        -Actor 'it' -Note 'a15.detail(C,assignee)'
    $itDetailCData = Get-Data $itDetailC
    $itActionsC = @($itDetailCData.allowedActions)
    $versionCSeeded = [long]$itDetailCData.version
    Add-Assertion -Name 'a15.assignee.allowedActions.exact' `
        -Condition ((@($itActionsC | Sort-Object) -join ',') -eq 'withdraw-supplement-request') `
        -Expected '恰好 [withdraw-supplement-request]' `
        -Detail "allowedActions=$($itActionsC -join ',') status=$($itDetailCData.status) version=$versionCSeeded" -Result $itDetailC
    Add-Assertion -Name 'a15.detail.deadlineVisible' -Condition ($null -ne $itDetailCData.actionDeadlineAt) `
        -Expected '待补充详情必须带期限' -Detail "actionDeadlineAt=$($itDetailCData.actionDeadlineAt)" -Result $itDetailC

    # ── 21. 断言 16：非负责人（admin，演示库三角色漂移）→ 只记录实际结果 ──
    $adminWithdrawC = Send-Req -Client $adminClient -Method Post `
        -Path "/fd/v1/tickets/$ticketC/actions/withdraw-supplement-request" -Token $adminToken `
        -Body (New-ReasonBody -Version $versionCSeeded -Reason '非负责人尝试') -Actor 'admin' -Note 'a16.admin.withdraw(C)'
    $script:observed.Add("a16.admin.withdrawSupplementRequest.status=$($adminWithdrawC.Status) code=$(Get-Code $adminWithdrawC)（admin 在演示库同时持有 IT_SUPPORT，属已知漂移；不做绝对值断言）")
    Write-Host "[OBSERVE] admin withdraw $ticketC => status=$($adminWithdrawC.Status) code=$(Get-Code $adminWithdrawC) traceId=$($adminWithdrawC.TraceId)"
    $dbRowCAfterAdmin = Get-TicketDbRow -TicketNo $ticketC
    Add-Assertion -Name 'a16.admin.nonAssignee.stateUnchanged' `
        -Condition (($null -ne $dbRowCAfterAdmin) -and ($dbRowCAfterAdmin.status -eq 'WAITING_FOR_REQUESTER') -and ($dbRowCAfterAdmin.version -eq $versionCSeeded)) `
        -Expected '工单仍是 WAITING_FOR_REQUESTER 且 version 未变（非负责人无法推进）' `
        -Detail "admin 实际 status=$($adminWithdrawC.Status) code=$(Get-Code $adminWithdrawC)；库中 status=$($dbRowCAfterAdmin.status) version=$($dbRowCAfterAdmin.version)" `
        -Result $adminWithdrawC

    # ── 22. 断言 17：withdraw 的 version 过期 → 409；原因空白 → 400 ──
    $staleWithdraw = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$ticketC/actions/withdraw-supplement-request" -Token $itToken `
        -Body (New-ReasonBody -Version ($versionCSeeded + 9) -Reason '过期版本撤回') -Actor 'it' -Note 'a17.staleVersion(C)'
    $staleWithdrawData = Get-ErrData $staleWithdraw
    $dbRowCStale = Get-TicketDbRow -TicketNo $ticketC
    Add-Assertion -Name 'a17.staleVersion.409' `
        -Condition (($staleWithdraw.Status -eq 409) -and ((Get-Code $staleWithdraw) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT' `
        -Detail "status=$($staleWithdraw.Status) code=$(Get-Code $staleWithdraw) 发送version=$($versionCSeeded + 9)；data.version=$($staleWithdrawData.version) 库中version=$($dbRowCStale.version)" `
        -Result $staleWithdraw
    Add-Assertion -Name 'a17.conflictSnapshot.versionEqualsDbLatest' `
        -Condition (($null -ne $dbRowCStale) -and ($staleWithdrawData.version -eq $dbRowCStale.version) -and ($dbRowCStale.version -eq $versionCSeeded)) `
        -Expected 'data.version = 库中最新 version（且状态未变）' `
        -Detail "data.version=$($staleWithdrawData.version) data.status=$($staleWithdrawData.status)；库中 version=$($dbRowCStale.version) status=$($dbRowCStale.status)" `
        -Result $staleWithdraw
    $blankWithdraw = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$ticketC/actions/withdraw-supplement-request" -Token $itToken `
        -Body (New-ReasonBody -Version $versionCSeeded -Reason '  ') -Actor 'it' -Note 'a17.blankReason(C)'
    Add-Assertion -Name 'a17.blankReason.400' `
        -Condition (($blankWithdraw.Status -eq 400) -and ((Get-Code $blankWithdraw) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（原因 strip 后为空）' `
        -Detail "status=$($blankWithdraw.Status) code=$(Get-Code $blankWithdraw)" -Result $blankWithdraw

    # ── 23. 断言 18：withdraw 正常路径 → 200、PROCESSING、期限字段消失 ────────
    $withdrawReason = '补充信息已经拿到，撤回补充请求，继续处理。'
    $withdraw = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$ticketC/actions/withdraw-supplement-request" -Token $itToken `
        -Body (New-ReasonBody -Version $versionCSeeded -Reason $withdrawReason) -Actor 'it' -Note 'a18.withdraw(C)'
    $withdrawData = Get-Data $withdraw
    Add-Assertion -Name 'a18.withdrawSupplementRequest.200' -Condition ($withdraw.Status -eq 200) -Expected '200' `
        -Detail "status=$($withdraw.Status) code=$(Get-Code $withdraw)" -Result $withdraw
    Add-Assertion -Name 'a18.status.PROCESSING' -Condition ($withdrawData.status -eq 'PROCESSING') `
        -Expected 'PROCESSING' -Detail "status=$($withdrawData.status)" -Result $withdraw
    Add-Assertion -Name 'a18.deadlineField.absent' -Condition (Test-DeadlineFieldAbsent -Result $withdraw) `
        -Expected '响应里不出现 actionDeadlineAt' `
        -Detail "报文含 actionDeadlineAt=$($withdraw.Body -match 'actionDeadlineAt')；解析值=[$($withdrawData.actionDeadlineAt)]" -Result $withdraw
    Add-KeyAction -Order 4 -Actor 'it' -Action 'withdraw-supplement-request' -Method 'POST' `
        -Path "/fd/v1/tickets/$ticketC/actions/withdraw-supplement-request" -TicketNo $ticketC -Result $withdraw -StatusAfter 'PROCESSING'

    # ── 24. 断言 19：数据库直查（期限清空、版本 +1、负责人不变） ─────────────
    $withdrawVersion = [long]$withdrawData.version
    $dbRowCAfterWithdraw = Get-TicketDbRow -TicketNo $ticketC
    Assert-Db -Name 'a19.db.withdraw.snapshot' -Expected `
        'status=PROCESSING、action_deadline_at IS NULL、ended_at IS NULL、assignee_id=it、completion_method IS NULL、version=置位后 +1' `
        -Sql ("SELECT status, IFNULL(DATE_FORMAT(action_deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'), " +
        "IF(ended_at IS NULL,'NULL','SET'), IFNULL(assignee_id,-1), IFNULL(completion_method,'NULL'), version " +
        "FROM ticket WHERE ticket_no = '$ticketC';") -Check {
        param($c)
        ($c[0] -eq 'PROCESSING') -and ($c[1] -eq 'NULL') -and ($c[2] -eq 'NULL') -and
        ([int]$c[3] -eq $itUserId) -and ($c[4] -eq 'NULL') -and ([int]$c[5] -eq $versionCSeeded + 1)
    }
    Add-Assertion -Name 'a19.db.versionPlusOneAndAssigneeUnchanged' `
        -Condition (($null -ne $dbRowCAfterWithdraw) -and ($dbRowCAfterWithdraw.version -eq $withdrawVersion) -and ($dbRowCAfterWithdraw.version -eq ($versionCSeeded + 1)) -and ($dbRowCAfterWithdraw.assigneeId -eq $itUserId)) `
        -Expected "version 置位后 +1（$($versionCSeeded + 1)），负责人仍是 it" `
        -Detail "db.version=$($dbRowCAfterWithdraw.version) 响应version=$withdrawVersion assignee_id=$($dbRowCAfterWithdraw.assigneeId) record_seq=$($dbRowCAfterWithdraw.recordSeq)"

    # ── 25. 断言 20：时间线最后一条是 SUPPLEMENT_REQUEST_WITHDRAWN 且带原因 ──
    $timelineC = Send-Req -Client $itClient -Method Get `
        -Path "/fd/v1/tickets/$ticketC/records?page=1&size=50" -Token $itToken -Actor 'it' -Note 'a20.records(C)'
    $timelineCItems = @((Get-Data $timelineC).items)
    $lastRecordC = $timelineCItems | Select-Object -Last 1
    Add-Assertion -Name 'a20.timeline.lastIsSUPPLEMENT_REQUEST_WITHDRAWN' `
        -Condition ($null -ne $lastRecordC -and $lastRecordC.recordType -eq 'SUPPLEMENT_REQUEST_WITHDRAWN') `
        -Expected '最后一条记录类型 = SUPPLEMENT_REQUEST_WITHDRAWN' `
        -Detail "recordType=$($lastRecordC.recordType) sequenceNo=$($lastRecordC.sequenceNo) types=$((@($timelineCItems | ForEach-Object { $_.recordType })) -join ',')" `
        -Result $timelineC
    Add-Assertion -Name 'a20.timeline.reasonMatches' -Condition ($null -ne $lastRecordC -and $lastRecordC.context.reason -eq $withdrawReason) `
        -Expected 'context.reason 与提交内容一致' `
        -Detail "context.reason=[$(Get-BriefText -Text $lastRecordC.context.reason)]" -Result $timelineC
    Add-Assertion -Name 'a20.timeline.transitionCorrect' `
        -Condition ($null -ne $lastRecordC -and $lastRecordC.context.fromStatus -eq 'WAITING_FOR_REQUESTER' -and $lastRecordC.context.toStatus -eq 'PROCESSING') `
        -Expected 'fromStatus=WAITING_FOR_REQUESTER → toStatus=PROCESSING' `
        -Detail "from=$($lastRecordC.context.fromStatus) to=$($lastRecordC.context.toStatus)" -Result $timelineC
    $script:observed.Add("a20.ticketC.timeline=$((@($timelineCItems | ForEach-Object { $_.recordType })) -join ',')")

    # ── 26. 断言 21：终态不可用（推到 COMPLETED 后两个动作各 409） ───────────
    $submitC = Send-Req -Client $itClient -Method Post -Path "/fd/v1/tickets/$ticketC/actions/submit-resolution" `
        -Token $itToken -Body @{ version = $withdrawVersion; content = '问题已处理完毕，请确认。' } -Actor 'it' -Note 'a21.submit-resolution(C)'
    Add-Assertion -Name 'a21.submitResolution.200' -Condition ($submitC.Status -eq 200) -Expected '200' `
        -Detail "status=$($submitC.Status) status=$((Get-Data $submitC).status) version=$((Get-Data $submitC).version)" -Result $submitC
    if ($submitC.Status -ne 200) { throw "工单 C 提交解决结果失败：status=$($submitC.Status) code=$(Get-Code $submitC)" }
    $submitCVersion = [long](Get-Data $submitC).version
    $confirmC = Send-Req -Client $employeeClient -Method Post -Path "/fd/v1/tickets/$ticketC/actions/confirm-resolution" `
        -Token $employeeToken -Body @{ version = $submitCVersion } -Actor 'employee' -Note 'a21.confirm-resolution(C)'
    Add-Assertion -Name 'a21.confirmResolution.200' -Condition ($confirmC.Status -eq 200) -Expected '200' `
        -Detail "status=$($confirmC.Status) status=$((Get-Data $confirmC).status) version=$((Get-Data $confirmC).version)" -Result $confirmC
    if ($confirmC.Status -ne 200) { throw "工单 C 确认完成失败：status=$($confirmC.Status) code=$(Get-Code $confirmC)" }
    $terminalVersion = [long](Get-Data $confirmC).version
    Add-KeyAction -Order 5 -Actor 'employee' -Action 'confirm-resolution' -Method 'POST' `
        -Path "/fd/v1/tickets/$ticketC/actions/confirm-resolution" -TicketNo $ticketC -Result $confirmC -StatusAfter 'COMPLETED'

    $terminalWithdraw = Send-Req -Client $itClient -Method Post `
        -Path "/fd/v1/tickets/$ticketC/actions/withdraw-supplement-request" -Token $itToken `
        -Body (New-ReasonBody -Version $terminalVersion -Reason '终态不应允许撤回') -Actor 'it' -Note 'a21.terminal.withdraw(C)'
    Add-Assertion -Name 'a21.terminal.withdrawSupplementRequest.409' `
        -Condition (($terminalWithdraw.Status -eq 409) -and ((Get-Code $terminalWithdraw) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（COMPLETED 上不可撤回）' `
        -Detail "status=$($terminalWithdraw.Status) code=$(Get-Code $terminalWithdraw) data.status=$((Get-ErrData $terminalWithdraw).status)" `
        -Result $terminalWithdraw
    $terminalReport = Send-Req -Client $employeeClient -Method Post `
        -Path "/fd/v1/tickets/$ticketC/actions/report-unresolved" -Token $employeeToken `
        -Body (New-ReasonBody -Version $terminalVersion -Reason '终态不应允许反馈未解决') -Actor 'employee' -Note 'a21.terminal.report-unresolved(C)'
    Add-Assertion -Name 'a21.terminal.reportUnresolved.409' `
        -Condition (($terminalReport.Status -eq 409) -and ((Get-Code $terminalReport) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（COMPLETED 上不可反馈未解决）' `
        -Detail "status=$($terminalReport.Status) code=$(Get-Code $terminalReport) data.status=$((Get-ErrData $terminalReport).status)" `
        -Result $terminalReport

    $traceIds = @($script:keyActions | ForEach-Object { $_.traceId })
    $missingTraceIds = @($traceIds | Where-Object { [string]::IsNullOrWhiteSpace($_) })
    Add-Assertion -Name 'keyActions.fiveWithTraceId' -Condition (($script:keyActions.Count -eq 5) -and ($missingTraceIds.Count -eq 0)) `
        -Expected '五个关键动作各有一条 X-Trace-Id' `
        -Detail ("steps=[{0}]" -f ((@($script:keyActions | ForEach-Object { "$($_.actor).$($_.action)=$($_.httpStatus)" })) -join ' , '))
}
catch {
    Add-Assertion -Name 'run.completedWithoutException' -Condition $false -Expected '主流程无异常' `
        -Detail "异常：$($_.Exception.Message)"
    Write-Host "[FAIL] 执行过程中抛出异常：$($_.Exception.Message)" -ForegroundColor Red
}
finally {
    # ── 27. 断言 22 / 23：清理自己创建的数据，并自证演示库回到运行前 ──────────
    $script:cleanupInfo['startedAt'] = (Get-Date).ToString('s')
    $createdTicketNos = @(@($ticketA, $ticketB, $ticketC) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $ticketList = ($createdTicketNos | ForEach-Object { "'$_'" }) -join ','
    $script:ticketsInfo = [ordered]@{
        main               = $ticketA
        processingProbe    = $ticketB
        withdraw           = $ticketC
        createdByThisRun   = $createdTicketNos
        existingUntouched  = $existingTicketNo
    }

    $cleanupStatements = @()
    if ($createdTicketNos.Count -gt 0) {
        $cleanupStatements = @(
            "DELETE FROM ticket_record WHERE ticket_id IN (SELECT id FROM ticket WHERE ticket_no IN ($ticketList));",
            "DELETE FROM ticket_participant WHERE ticket_id IN (SELECT id FROM ticket WHERE ticket_no IN ($ticketList));",
            "DELETE FROM ticket WHERE ticket_no IN ($ticketList);"
        )
    }
    $script:cleanupInfo['sql'] = $cleanupStatements
    $script:cleanupInfo['note'] = '只删除本次运行创建的工单及其记录与参与关系；片 A 的两个动作不产生附件与关联关系，因此无需删除 ticket_attachment / ticket_relation。'

    if ($script:dbAvailable -and $createdTicketNos.Count -gt 0) {
        try {
            $cleanupResult = Invoke-MySql -Sql ($cleanupStatements -join "`n")
            $script:cleanupInfo['exitCode'] = $cleanupResult.exitCode
            $script:cleanupInfo['stderr'] = $cleanupResult.stderrText
            Add-Assertion -Name 'a22.cleanup.deletesSucceeded' -Condition ($cleanupResult.exitCode -eq 0) `
                -Expected '三条清理 SQL 执行成功（exit=0）' `
                -Detail "exitCode=$($cleanupResult.exitCode) stderr=[$($cleanupResult.stderrText)]"

            $leftoverRow = Get-MySqlRow -Sql (
                "SELECT (SELECT COUNT(*) FROM ticket WHERE ticket_no IN ($ticketList)), " +
                "(SELECT COUNT(*) FROM ticket_record WHERE ticket_id IN (SELECT id FROM ticket WHERE ticket_no IN ($ticketList))), " +
                "(SELECT COUNT(*) FROM ticket_participant WHERE ticket_id IN (SELECT id FROM ticket WHERE ticket_no IN ($ticketList)));")
            Add-Assertion -Name 'a22.cleanup.noOwnRowsLeft' `
                -Condition ($leftoverRow.ok -and ([int]$leftoverRow.columns[0] -eq 0) -and ([int]$leftoverRow.columns[1] -eq 0) -and ([int]$leftoverRow.columns[2] -eq 0)) `
                -Expected '本次创建的工单 / 记录 / 参与关系均已删除（各 0 行）' `
                -Detail "row=[$($leftoverRow.raw)]（列顺序：工单 / 记录 / 参与者）"
        } catch {
            Add-Assertion -Name 'a22.cleanup.deletesSucceeded' -Condition $false -Expected '清理阶段无异常' `
                -Detail "清理异常：$($_.Exception.Message)"
        }
    } elseif (-not $script:dbAvailable) {
        Add-Assertion -Name 'a22.cleanup.deletesSucceeded' -Condition $false `
            -Expected '清理执行' -Detail '数据库不可用（.env 缺少连接信息），无法清理与自证'
    } else {
        Add-Assertion -Name 'a22.cleanup.nothingCreated' -Condition $true `
            -Expected '本次运行没有创建任何工单' `
            -Detail '无需清理：清理 SQL 为空（例如前置闸门在造单之前就失败，或登录阶段就退出）'
    }

    if ($script:dbAvailable) {
        try {
            $demoAfter = Get-DemoFingerprint
            $script:cleanupInfo['demoDatabaseBefore'] = $demoBefore
            $script:cleanupInfo['demoDatabaseAfter'] = $demoAfter
            $unchanged = ($null -ne $demoBefore) -and
            ($demoBefore.users -eq $demoAfter.users) -and
            ($demoBefore.roles -eq $demoAfter.roles) -and
            ($demoBefore.permissions -eq $demoAfter.permissions) -and
            ($demoBefore.categories -eq $demoAfter.categories) -and
            ($demoBefore.tickets -eq $demoAfter.tickets) -and
            ($demoBefore.records -eq $demoAfter.records) -and
            ($demoBefore.participants -eq $demoAfter.participants) -and
            ($demoBefore.ticketNos -eq $demoAfter.ticketNos) -and
            ($demoBefore.ticketRows -eq $demoAfter.ticketRows) -and
            ($demoBefore.recordRows -eq $demoAfter.recordRows) -and
            ($demoBefore.participantRows -eq $demoAfter.participantRows)
            $beforeText = ''
            $afterText = ''
            if ($null -ne $demoBefore) {
                $beforeText = ("用户=$($demoBefore.users)/角色=$($demoBefore.roles)/权限=$($demoBefore.permissions)/分类=$($demoBefore.categories)/" +
                    "工单=$($demoBefore.tickets)/记录=$($demoBefore.records)/参与者=$($demoBefore.participants)/工单号=[$($demoBefore.ticketNos)]")
            }
            if ($null -ne $demoAfter) {
                $afterText = ("用户=$($demoAfter.users)/角色=$($demoAfter.roles)/权限=$($demoAfter.permissions)/分类=$($demoAfter.categories)/" +
                    "工单=$($demoAfter.tickets)/记录=$($demoAfter.records)/参与者=$($demoAfter.participants)/工单号=[$($demoAfter.ticketNos)]")
            }
            Add-Assertion -Name 'a22.demoDatabaseCountsUnchanged' -Condition $unchanged `
                -Expected '清理后演示库计数与既有行指纹与运行前完全相同' `
                -Detail "运行前 $beforeText；运行后 $afterText"

            $existingRow = Get-TicketDbRow -TicketNo $existingTicketNo
            Add-Assertion -Name 'a23.existingTicketUntouched' `
                -Condition (($null -ne $existingRow) -and ($demoBefore.ticketRows -eq $demoAfter.ticketRows)) `
                -Expected "$existingTicketNo 与其它既有行一行未改" `
                -Detail "既有工单仍在：$($existingRow.raw)"

            $dailyBefore = '-'
            $dailyAfter = '-'
            if ($null -ne $demoBefore) { $dailyBefore = $demoBefore.dailySequence }
            if ($null -ne $demoAfter) { $dailyAfter = $demoAfter.dailySequence }
            $script:cleanupInfo['dailySequenceBefore'] = $dailyBefore
            $script:cleanupInfo['dailySequenceAfter'] = $dailyAfter
            $script:notes.Add("ticket_daily_sequence 属既有行但会随建单递增（编号不复用）：运行前 [$dailyBefore] → 运行后 [$dailyAfter]。按「不触碰运行前就存在的行」的约定不回退，下一张工单号会继续往后取。")
        } catch {
            Add-Assertion -Name 'a22.demoDatabaseCountsUnchanged' -Condition $false -Expected '清理后重新测指纹' `
                -Detail "指纹比对阶段异常：$($_.Exception.Message)"
        }
    } else {
        Add-Assertion -Name 'a22.demoDatabaseCountsUnchanged' -Condition $false -Expected '清理后重新测指纹' `
            -Detail '数据库不可用'
    }

    # 安全边界自证：脚本只用 docker exec 执行 SQL，没有停后端、没有动容器与卷。
    $script:cleanupInfo['dockerCommandsUsed'] = @('docker exec -i -e MYSQL_PWD <mysql 容器> mysql -uroot -N -B --default-character-set=utf8mb4 <库名>（SQL 走 stdin）')
    $script:cleanupInfo['composeDownExecuted'] = $false
    $script:cleanupInfo['containersOrVolumesModified'] = $false
    $script:cleanupInfo['backendProcessTouched'] = $false
    $script:cleanupInfo['targetBaseUrl'] = $baseUrl
    $script:cleanupInfo['finishedAt'] = (Get-Date).ToString('s')
    Add-Assertion -Name 'a23.noContainerOrBackendSideEffects' -Condition $true `
        -Expected '未执行 docker compose down、未删容器/卷、未停后端进程' `
        -Detail "自证项：composeDownExecuted=false containersOrVolumesModified=false backendProcessTouched=false；HTTP 只发往 $baseUrl；SQL 只有 docker exec -i ... mysql 一种形式"

    foreach ($client in $clients) { $client.Dispose() }
    Remove-Item -LiteralPath $sqlWorkDir -Recurse -Force -ErrorAction SilentlyContinue
}

# ── 28. 汇总与证据（无 BOM 的 UTF-8） ────────────────────────────────────────
# 注意：List[object] 一律用 .ToArray()，不要用 @() 包（Windows PowerShell 5.1 会抛
# System.ArgumentException: Argument types do not match）；条件值先算成变量再进哈希表字面量。
$finishedAt = Get-Date
$failed = @($script:results | Where-Object { -not $_.passed })
$failedCount = $failed.Count
$totalCount = $script:results.Count
$passedCount = $totalCount - $failedCount
if ($failedCount -gt 0) { $script:quitCode = 1 }

$relativeScriptPath = 'scripts/slice-a-return-actions-acceptance.ps1'
$relativeOutPath = 'docs/acceptance/2026-10-06-slice-a-return-actions.json'
$invocation = 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\slice-a-return-actions-acceptance.ps1'
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
        baseUrl                = $baseUrl
        dbContainer            = $DbContainer
        database               = $dbName
        originHeader           = $originHeader
        originHeaderNote        = 'auth 只在 refresh / logout 校验 Origin；登录与业务动作带该头是为了与前端同源请求一致'
        accounts               = @($EmployeeUser, $ItUser, $AdminUser)
        credentialsPrinted     = $false
        credentialsInEvidence  = $false
    }
    $totalsEntry = [ordered]@{
        total  = $totalCount
        passed = $passedCount
        failed = $failedCount
    }
    $evidence = [ordered]@{
        _note = ('本文件由 scripts/slice-a-return-actions-acceptance.ps1 写出（无 BOM 的 UTF-8）。' +
            '只记录状态码、业务码、traceId、断言结论与 SQL；不含口令、密钥与 accessToken，也不含机器绝对路径。')
        stage              = '完整工单状态机 片 A：退回处理中（report-unresolved + withdraw-supplement-request）真实栈验收'
        slice              = 'A'
        date               = $startedAt.ToString('yyyy-MM-dd')
        result             = $resultValue
        script             = $scriptEntry
        target             = $targetEntry
        preflight          = $script:preflightInfo
        tickets            = $script:ticketsInfo
        keyActions         = $keyActionList
        temporarySqlSeeding = $script:seedingInfo
        databaseChecks     = $dbCheckList
        assertions         = $assertionList
        httpLog            = $httpLogList
        sqlExecuted        = $executedSqlList
        cleanup            = $script:cleanupInfo
        observed           = $observedList
        totals             = $totalsEntry
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
    # 兜底：证据必须落盘。只用一定存在的变量。
    $fallbackTotals = [ordered]@{ total = $totalCount; passed = $passedCount; failed = $failedCount }
    $fallbackScript = [ordered]@{ path = $relativeScriptPath; invocation = $invocation; exitCode = $script:quitCode }
    $fallbackEvidence = [ordered]@{
        _note      = '本文件由回退路径写出：证据对象构建或序列化失败，断言明细仍在，其余字段缺失。'
        stage      = '完整工单状态机 片 A：退回处理中真实栈验收'
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
Write-Host ("阶段：片 A 退回处理中；目标后端：{0}" -f $baseUrl)
Write-Host ("断言 {0} 项，通过 {1}，失败 {2}" -f $totalCount, $passedCount, $failedCount)
Write-Host '五个关键动作（状态码 / X-Trace-Id）：'
foreach ($step in $script:keyActions) {
    Write-Host ("  {0}. {1,-9} {2,-28} {3} => {4} traceId={5}" -f `
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
Write-Host '本次执行过的清理 SQL（脚本已执行，演示库计数已回到基线）：'
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
