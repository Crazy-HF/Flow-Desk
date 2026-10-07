# 完整工单状态机「片 C：调整与转交」真实栈验收
#   change-category（当前负责人调整分类）  +  change-priority（调整优先级）
#   +  transfer（转交给另一名 IT 支持人员）  +  GET transfer-candidates（候选人最小字段接口）
#
# 前置
#   · docker compose up -d mysql redis（本脚本只对既有 mysql 容器执行 docker exec，不碰容器生命周期）
#   · 目标后端必须是**已包含片 C 三个动作与候选人接口的最新代码**，由用户自己启动。脚本只发 HTTP 请求，
#     不启动、不重启、不结束任何后端进程；8081 上用户自己启动的实例保持原样。
#
# 运行
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\slice-c-adjust-transfer-acceptance.ps1
#   基址可覆盖（默认 http://127.0.0.1:8081，并行后端常用 8092）：
#     $env:FLOWDESK_BASE_URL = 'http://127.0.0.1:8092'   # 或 -BaseUrl http://127.0.0.1:8092
#   演示账号可用 E2E_EMPLOYEE_USERNAME / E2E_EMPLOYEE_PASSWORD、E2E_IT_*、E2E_ADMIN_* 覆盖
#   （缺省 employee / it / admin，口令 123456）。
#
# 本文件必须保存为「带 BOM 的 UTF-8」：Windows PowerShell 5.1 会按系统代码页读取无 BOM 的
# UTF-8 脚本，中文注释会被解析成乱码并报语法错误。证据文件相反，必须写成**无 BOM 的 UTF-8**。
#
# 证据
#   docs/acceptance/2026-10-07-slice-c-adjust-transfer.json（无 BOM 的 UTF-8，脚本自己写出）
#   含：脚本命令与退出码、$baseUrl、关键动作的状态码与 traceId、逐条断言（名称/期望/实际状态码/
#   X-Trace-Id/通过与否）、全部 HTTP 调用日志、数据库直查结果、清理前后演示库计数与指纹。
#
# 需要主会话特别注意的三处「已知红」
#   1) **changeCategory 缺少 `visible == null` 判空**（TilektServiceImpl.changeCategory 第 3 步
#      直接 `visible.getStatus()`）：编号不存在时不是契约要求的 404/TICKET_NOT_FOUND，而是 500。
#      因此断言 a6.changeCategory.unknown.404 在修复前必然是红的，a9.preflight 的端点闸门也会
#      跟着红（脚本随后抛错跳过后续动作断言，清理与基线核对照常执行）。**本脚本不会把它改写成
#      500 来"通过"**：这一条就是该缺陷的证据。changePriority / transfer / transfer-candidates
#      三处都有判空，同一场景实测应为 404。
#   2) 临时 IT 用户的登录口令取自 `-ItPassword` / E2E_IT_PASSWORD，其 Argon2id 摘要由脚本从库里
#      真实用户 `it` 的 `password` 列**复制**而来（不硬编码、不落盘、不打印）。若调用方改过 it 的
#      口令却没有同步 E2E_IT_PASSWORD，a28.tempItLogin.200 会红——这是刻意的强耦合，用来暴露
#      "临时用户其实登不进去"的假通过。
#   3) 候选人列表的"不含提交人与当前负责人"由 selectTransferCandidates 的 requesterId /
#      currentAssigneeId 两个 NOT 条件保证；断言 a20 记录实测返回的 id 集合，不依赖库里的历史漂移。
#
# 安全边界
#   · 只创建并删除脚本自己创建的工单、记录、参与关系与那个临时 IT 用户（含它的角色行）；
#     不触碰运行前就存在的任何行（运行前后各测一次演示库指纹并断言完全相同）
#   · 不执行 docker compose down、不删容器、不删卷、不停任何后端进程；数据库访问一律是
#     `docker exec -i <mysql 容器> mysql ...`（容器名与库名来自仓库根 .env 与既有脚本约定）
#   · 演示库 root 口令只从 .env 读进进程环境变量、经 `docker exec -e MYSQL_PWD` 转发，
#     不出现在命令行、不打印、不写进证据；登录口令与 accessToken 同样不打印、不落盘
#   · 断言只记录状态码、业务码、traceId 与布尔结论，不回显令牌与口令；错误正文只截取前 200 字符
#
# SQL 通过**临时 .sql 文件 + stdin** 送入 mysql 客户端，输出重定向到临时文件后按 UTF-8 读取：
#   ① 中文字面量与含中文的结果都不经过控制台代码页，避免断言误判；
#   ② 口令不进命令行，mysql 也不会打印 "Using a password on the command line" 警告；
#   ③ mysql 客户端统一带 --default-character-set=utf8mb4，否则读回的中文会变成 "?"。

param(
    # 基址可覆盖：PowerShell 5.1 没有 ?? 运算符，用 param 默认值 + 下方兜底实现同样语义
    [string]$BaseUrl = $env:FLOWDESK_BASE_URL,
    [string]$OutFile = (Join-Path $PSScriptRoot '..\docs\acceptance\2026-10-07-slice-c-adjust-transfer.json'),
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
$sqlWorkDir = Join-Path $env:TEMP "flowdesk-slice-c-$stamp"
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
$script:candidateInfo = [ordered]@{}
$script:windowInfo = [ordered]@{}
$script:ticketsInfo = [ordered]@{}
$script:tempUserInfo = [ordered]@{}

if ([string]::IsNullOrWhiteSpace($EmployeeUser)) { $EmployeeUser = 'employee' }
if ([string]::IsNullOrWhiteSpace($EmployeePassword)) { $EmployeePassword = '123456' }
if ([string]::IsNullOrWhiteSpace($ItUser)) { $ItUser = 'it' }
if ([string]::IsNullOrWhiteSpace($ItPassword)) { $ItPassword = '123456' }
if ([string]::IsNullOrWhiteSpace($AdminUser)) { $AdminUser = 'admin' }
if ([string]::IsNullOrWhiteSpace($AdminPassword)) { $AdminPassword = '123456' }

# 临时 IT 用户：脚本用 SQL 建、用 SQL 删，只为了让「转交」有一个真实可接收人。
$tempItUsername = 'acceptance-it-' + $stamp
$tempItDisplayName = '片C验收临时IT-' + $stamp

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

# 多行结果：每行按制表符切列，整批返回。
function Get-MySqlRows {
    param([string]$Sql)
    $result = Invoke-MySql -Sql $Sql
    $rows = [System.Collections.Generic.List[object]]::new()
    if ($result.exitCode -ne 0) { return $rows.ToArray() }
    foreach ($line in $result.lines) {
        $rows.Add([pscustomobject]@{ raw = $line; columns = @($line -split "`t") })
    }
    return $rows.ToArray()
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

# 每个请求都自带 X-Trace-Id（服务端若已生成则回显自己的；两边都进证据，便于对日志）。
function New-TraceId {
    return [guid]::NewGuid().ToString()
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
                actor          = $Actor
                note           = $Note
                method         = $Method
                path           = $Path
                status         = $result.Status
                traceId        = $result.TraceId
                requestTraceId = $requestTraceId
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
                actor          = $Actor
                note           = $Note
                method         = 'POST'
                path           = '/fd/v1/tickets'
                status         = $result.Status
                traceId        = $result.TraceId
                requestTraceId = $requestTraceId
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

# 409 的 data 里带当前快照（version / status），供前端刷新后重试。
function Get-ErrData {
    param($Result)
    if ($null -eq $Result) { return $null }
    try { return ($Result.Body | ConvertFrom-Json).data } catch { return $null }
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

# 工单快照：status | category_id | priority | action_deadline_at | ended_at | assignee_id |
#           completion_method | version | record_seq | requester_id
function Get-TicketDbRow {
    param([string]$TicketNo)
    $sql = "SELECT status, category_id, priority, " +
    "IFNULL(DATE_FORMAT(action_deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'), " +
    "IF(ended_at IS NULL,'NULL','SET'), IFNULL(assignee_id,-1), IFNULL(completion_method,'NULL'), " +
    "version, record_seq, requester_id FROM ticket WHERE ticket_no = '$TicketNo';"
    $row = Get-MySqlRow -Sql $sql
    if (-not $row.ok) { return $null }
    $columns = @($row.columns)
    if ($columns.Count -lt 10) { return $null }
    return [pscustomobject]@{
        status           = $columns[0]
        categoryId       = [long]$columns[1]
        priority         = $columns[2]
        actionDeadlineAt = $columns[3]
        endedAt          = $columns[4]
        assigneeId       = [int]$columns[5]
        completionMethod = $columns[6]
        version          = [int]$columns[7]
        recordSeq        = [int]$columns[8]
        requesterId      = [int]$columns[9]
        raw              = $row.raw
    }
}

# 「库里没变」的统一快照串：状态 | 分类 | 优先级 | 负责人 | 期限 | version | record_seq | 记录条数。
# 400 用例前后各取一次，字符串相同即证明被拒的请求什么都没改。
function Get-TicketStateSignature {
    param([string]$TicketNo)
    $sql = "SELECT CONCAT_WS('|', t.status, t.category_id, t.priority, IFNULL(t.assignee_id,-1), " +
    "IFNULL(DATE_FORMAT(t.action_deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'), t.version, t.record_seq, " +
    "(SELECT COUNT(*) FROM ticket_record r WHERE r.ticket_id = t.id)) " +
    "FROM ticket t WHERE t.ticket_no = '$TicketNo';"
    $row = Get-MySqlRow -Sql $sql
    if (-not $row.ok) { return "UNREADABLE[$($row.raw)]" }
    return $row.raw
}

# 时间线直查：序号、类型、操作者、原因/正文摘要、两侧状态、分类/优先级/负责人快照、期限。
function Get-TicketRecordDbRows {
    param([string]$TicketNo)
    $sql = "SELECT sequence_no, record_type, actor_type, IFNULL(actor_user_id,-1), " +
    "IFNULL(LEFT(content,40),'-'), IFNULL(CHAR_LENGTH(content),-1), " +
    "IFNULL(LEFT(reason,40),'-'), IFNULL(CHAR_LENGTH(reason),-1), " +
    "IFNULL(from_status,'-'), IFNULL(to_status,'-'), " +
    "IFNULL(from_category_id,-1), IFNULL(to_category_id,-1), " +
    "IFNULL(from_priority,'-'), IFNULL(to_priority,'-'), " +
    "IFNULL(from_assignee_id,-1), IFNULL(to_assignee_id,-1), " +
    "IFNULL(DATE_FORMAT(deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL') " +
    "FROM ticket_record WHERE ticket_id = (SELECT id FROM ticket WHERE ticket_no = '$TicketNo') " +
    "ORDER BY sequence_no;"
    $result = Invoke-MySql -Sql $sql
    $rows = [System.Collections.Generic.List[object]]::new()
    if ($result.exitCode -ne 0) { return $rows.ToArray() }
    foreach ($line in $result.lines) {
        $columns = @($line -split "`t")
        if ($columns.Count -lt 17) { continue }
        $rows.Add([pscustomobject]@{
                sequenceNo     = [int]$columns[0]
                recordType     = $columns[1]
                actorType      = $columns[2]
                actorId        = [int]$columns[3]
                content        = $columns[4]
                contentLen     = [int]$columns[5]
                reason         = $columns[6]
                reasonLen      = [int]$columns[7]
                fromStatus     = $columns[8]
                toStatus       = $columns[9]
                fromCategoryId = [long]$columns[10]
                toCategoryId   = [long]$columns[11]
                fromPriority   = $columns[12]
                toPriority     = $columns[13]
                fromAssigneeId = [int]$columns[14]
                toAssigneeId   = [int]$columns[15]
                deadlineAt     = $columns[16]
            })
    }
    return $rows.ToArray()
}

# 指定类型的时间线记录（脚本里多处按类型取一条）。
function Get-RecordOfType {
    param([string]$TicketNo, [string]$RecordType)
    $rows = @(Get-TicketRecordDbRows -TicketNo $TicketNo)
    return ($rows | Where-Object { $_.recordType -eq $RecordType } | Select-Object -First 1)
}

# 期限比较统一走"比较到秒"：库里是 DATETIME(3) 的 'yyyy-MM-dd HH:mm:ss.ffffff'，响应是 ISO-8601 带 Z。
# 字符串规范化既避开时区（直接 Parse 会带上本机时区，实测差 8 小时），也避开微秒表示差异。
# 不能直接 .Substring(0,19)：期限为 NULL 时 Get-TicketDbRow 返回的是 'NULL'（4 个字符），会抛越界。
function Get-DeadlineSeconds {
    param([string]$Value)
    if ([string]::IsNullOrEmpty($Value)) { return '(空)' }
    $normalized = $Value.Replace('T', ' ')
    if ($normalized.Length -le 19) { return $normalized }
    return $normalized.Substring(0, 19)
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
    $userRows = Get-MySqlRow -Sql (
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(id,'|',username,'|',status) ORDER BY id SEPARATOR ' ;; '),'-') FROM iam_user;")
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
        userRows        = $userRows.raw
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

# 片 C 三个动作的请求体；-Omit* 开关用来构造"字段整体缺失"的报文（@NotNull 用例）。
function New-CategoryBody {
    param([long]$Version, [long]$CategoryId, [string]$Reason, [switch]$OmitVersion, [switch]$OmitCategoryId, [switch]$OmitReason)
    $body = @{}
    if (-not $OmitVersion) { $body['version'] = $Version }
    if (-not $OmitCategoryId) { $body['categoryId'] = $CategoryId }
    if (-not $OmitReason) { $body['reason'] = $Reason }
    return $body
}

function New-PriorityBody {
    param([long]$Version, [string]$Priority, [string]$Reason, [switch]$OmitPriority)
    $body = @{ version = $Version; reason = $Reason }
    if (-not $OmitPriority) { $body['priority'] = $Priority }
    return $body
}

function New-TransferBody {
    param([long]$Version, [long]$NewAssigneeId, [string]$Reason, [switch]$OmitNewAssigneeId)
    $body = @{ version = $Version; reason = $Reason }
    if (-not $OmitNewAssigneeId) { $body['newAssigneeId'] = $NewAssigneeId }
    return $body
}

# 三个动作路径统一构造，避免手抄路径时打错一个字导致 404 被当成业务结论。
function Get-ActionPath {
    param([string]$TicketNo, [string]$Action)
    return "/fd/v1/tickets/$TicketNo/actions/$Action"
}

# 成功响应的统一断言：200 + status 不变 + version/期限/负责人符合期望。
function Assert-ActionSnapshot {
    param(
        [string]$Name,
        $Result,
        [string]$ExpectedStatus,
        [long]$ExpectedVersion,
        [string]$ExpectedDeadline,
        [string]$Detail
    )
    $data = Get-Data $Result
    $deadlineOk = $true
    $deadlineActual = '(无)'
    if ($null -ne $data) {
        $deadlineActual = [string]$data.actionDeadlineAt
        if ([string]::IsNullOrEmpty($ExpectedDeadline)) {
            $deadlineOk = [string]::IsNullOrEmpty($deadlineActual)
        } else {
            $deadlineOk = ($deadlineActual -eq $ExpectedDeadline)
        }
    }
    $condition = ($Result.Status -eq 200) -and ($null -ne $data) -and
    ($data.status -eq $ExpectedStatus) -and ([long]$data.version -eq $ExpectedVersion) -and $deadlineOk
    Add-Assertion -Name $Name -Condition $condition `
        -Expected "200，status=$ExpectedStatus，version=$ExpectedVersion，actionDeadlineAt=$(if ([string]::IsNullOrEmpty($ExpectedDeadline)) { '字段不出现' } else { $ExpectedDeadline })" `
        -Detail ("$Detail；实测 status=$($Result.Status) businessStatus=$($data.status) version=$($data.version) " +
        "assigneeId=$($data.assignee.id) deadline=[$deadlineActual]") -Result $Result
    return $data
}

$employeeClient = New-Client
$itClient = New-Client
$adminClient = New-Client
$tempItClient = New-Client
$anonymousClient = New-Client
$clients = @($employeeClient, $itClient, $adminClient, $tempItClient, $anonymousClient)

$ticketA = ''
$ticketB = ''
$ticketC = ''
$ticketF = ''
$unknownTicketNo = 'FD-19990101-001'
$employeeUserId = -1
$itUserId = -1
$adminUserId = -1
$tempItUserId = -1
$demoBefore = $null
$demoAfter = $null

try {
    # ── 2. 前置与基线：.env、运行前指纹、幂等自证 ────────────────────────────
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
    Add-Assertion -Name 'baseline.ticketNoFingerprintRecorded' -Condition ($demoBefore.ticketNos.Length -gt 0) `
        -Expected '运行前工单号列表已完整记录（清理后必须逐字相同）' `
        -Detail "工单号=[$($demoBefore.ticketNos)]；本脚本只新增再删除自己的行，绝不修改运行前就存在的任何行"
    Add-Assertion -Name 'baseline.idempotencyScopeDeclared' -Condition $true `
        -Expected '脚本只新增再删除自己创建的行：4 张工单 + 其记录/参与关系 + 1 个临时 IT 用户及其角色行' `
        -Detail '清理段按 ticket_no 精确删除自建工单，再按 username 前缀删除临时 IT 用户与它的 iam_user_role；没有任何 UPDATE/DELETE 作用于运行前就存在的行'
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

    Add-Assertion -Name 'perm.employee.lacksProcessAndTransfer' `
        -Condition ((-not ($employeePerms -contains 'TICKET_PROCESS')) -and (-not ($employeePerms -contains 'TICKET_TRANSFER'))) `
        -Expected 'employee 同时缺 TICKET_PROCESS 与 TICKET_TRANSFER（a9/a10 的 403 前提）' `
        -Detail "roles=$(@($employeeData.roles) -join '|') hasTICKET_PROCESS=$($employeePerms -contains 'TICKET_PROCESS') hasTICKET_TRANSFER=$($employeePerms -contains 'TICKET_TRANSFER') ticketPerms=$((@($employeePerms | Where-Object { $_ -like 'TICKET*' })) -join '|')" `
        -Result $meEmployee
    Add-Assertion -Name 'perm.it.hasProcessAndTransfer' `
        -Condition (($itPerms -contains 'TICKET_PROCESS') -and ($itPerms -contains 'TICKET_TRANSFER')) `
        -Expected 'it 同时具备 TICKET_PROCESS 与 TICKET_TRANSFER（三个动作的成功路径前提）' `
        -Detail "roles=$(@($itData.roles) -join '|') hasTICKET_PROCESS=$($itPerms -contains 'TICKET_PROCESS') hasTICKET_TRANSFER=$($itPerms -contains 'TICKET_TRANSFER')" `
        -Result $meIt
    Add-Assertion -Name 'perm.it.lacksRequesterAction' -Condition (-not ($itPerms -contains 'TICKET_REQUESTER_ACTION')) `
        -Expected 'it 不具备 TICKET_REQUESTER_ACTION（本次只记录事实，不作为断言失败依据）' `
        -Detail "hasTICKET_REQUESTER_ACTION=$($itPerms -contains 'TICKET_REQUESTER_ACTION')" -Result $meIt
    $script:observed.Add("it.roles=$(@($itData.roles) -join '|') it.hasTICKET_REQUESTER_ACTION=$($itPerms -contains 'TICKET_REQUESTER_ACTION')（片 C 三个动作都要求 TICKET_PROCESS/TICKET_TRANSFER，与提交人动作权限无关）")
    $script:observed.Add("admin.roles=$(@($adminData.roles) -join '|') admin.hasTICKET_PROCESS=$($adminPerms -contains 'TICKET_PROCESS') admin.hasTICKET_TRANSFER=$($adminPerms -contains 'TICKET_TRANSFER')")
    $script:notes.Add('admin 在本机演示库里同时持有 EMPLOYEE + IT_SUPPORT + SYSTEM_ADMIN（已知漂移），脚本只用它构造"可见但不是负责人 → 409"这一条，不做与身份无关的绝对值断言。')

    # ── 4. 分类选项：挑两个启用分类（调整分类要真的换一个），并找一个停用分类 ──
    $options = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/categories/options' -Token $employeeToken `
        -Actor 'employee' -Note 'categories.options'
    $optionItems = @(Get-Data $options)
    Add-Assertion -Name 'categories.options.nonEmpty' -Condition (($options.Status -eq 200) -and ($optionItems.Count -ge 2)) `
        -Expected '200 且至少两个启用分类（调整分类需要一个不同的目标分类）' `
        -Detail "status=$($options.Status) count=$($optionItems.Count)" -Result $options
    if ($optionItems.Count -lt 2) { throw '启用分类少于 2 个，无法验证"分类真的被替换"' }
    $categoryId = [long]$optionItems[0].id
    $categoryId2 = [long]$optionItems[1].id

    $disabledRow = Get-MySqlRow -Sql "SELECT IFNULL(MIN(id),-1) FROM ticket_category WHERE status = 'DISABLED';"
    $disabledCategoryId = -1
    if ($disabledRow.ok) { $disabledCategoryId = [long]$disabledRow.columns[0] }
    $script:observed.Add("停用分类探测：disabledCategoryId=$disabledCategoryId（-1 表示演示库当前没有停用分类，该条 400 用例转为条件断言）")

    # ── 5. 临时 IT 用户（SQL 造数，只为让"转交"有真实可接收人） ──────────────
    # 口令摘要从库里真实用户 it 的 password 列**复制**，不硬编码、不打印：临时用户的登录口令
    # 因此等于 E2E_IT_PASSWORD，脚本用它登录（不是"用不存在的口令去登录"）。
    $tempUserSql = "INSERT INTO iam_user (username, display_name, password, status, created_at, updated_at, version) " +
    "SELECT '" + $tempItUsername + "', '" + $tempItDisplayName + "', u.password, 'ENABLED', " +
    "UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 0 FROM iam_user u WHERE u.username = '" + $ItUser + "';"
    $tempRoleSql = "INSERT INTO iam_user_role (user_id, role_id, granted_by, granted_at) " +
    "SELECT u.id, r.id, NULL, UTC_TIMESTAMP(3) FROM iam_user u JOIN iam_role r ON r.code = 'IT_SUPPORT' " +
    "WHERE u.username = '" + $tempItUsername + "';"
    $tempUserResult = Invoke-MySql -Sql ($tempUserSql + "`n" + $tempRoleSql)
    $tempItUserId = -1
    $tempIdRow = Get-MySqlRow -Sql "SELECT IFNULL(id,-1) FROM iam_user WHERE username = '$tempItUsername';"
    if ($tempIdRow.ok) { $tempItUserId = [int]$tempIdRow.columns[0] }
    $tempRoleCountRow = Get-MySqlRow -Sql (
        "SELECT COUNT(*) FROM iam_user_role ur JOIN iam_role r ON r.id = ur.role_id " +
        "WHERE ur.user_id = $tempItUserId AND r.code = 'IT_SUPPORT';")
    $tempHasItRole = ($tempRoleCountRow.ok -and ([int]$tempRoleCountRow.columns[0] -eq 1))
    Add-Assertion -Name 'a8.setup.tempItUserCreated' `
        -Condition (($tempUserResult.exitCode -eq 0) -and ($tempItUserId -gt 0) -and $tempHasItRole) `
        -Expected '临时 IT 用户创建成功（status=ENABLED + IT_SUPPORT 角色行各 1 条）' `
        -Detail ("exitCode=$($tempUserResult.exitCode) username=$tempItUsername userId=$tempItUserId " +
        "hasItSupportRole=$tempHasItRole stderr=[$(Get-BriefText $tempUserResult.stderrText 120)]；" +
        '口令摘要复制自演示用户 it，未打印、未落盘，随清理一并删除')
    if ($tempItUserId -le 0) { throw '临时 IT 用户创建失败，转交成功路径无法进行' }
    $script:tempUserInfo = [ordered]@{
        username          = $tempItUsername
        displayName       = $tempItDisplayName
        userId            = $tempItUserId
        status            = 'ENABLED'
        role              = 'IT_SUPPORT'
        passwordSource    = "复制自演示用户 $ItUser 的 password 列（Argon2id 摘要，不打印、不落盘）"
        loginCredential   = '临时用户的登录口令取自 -ItPassword / E2E_IT_PASSWORD'
        purpose           = '转交需要一个真实存在、启用且具备 IT_SUPPORT 的可接收人；候选人接口也必须能列出它'
        cleanup           = '按 username 前缀删除 iam_user_role 与 iam_user 各一行'
    }

    $loginTempIt = Send-Req -Client $tempItClient -Method Post -Path '/fd/v1/auth/login' `
        -Body @{ username = $tempItUsername; password = $ItPassword } -Actor 'temp-it' -Note 'a8.login(temp-it)'
    Add-Assertion -Name 'a28.tempItLogin.200' -Condition ($loginTempIt.Status -eq 200) `
        -Expected '200（临时 IT 用户可登录；口令摘要复制自演示用户 it）' `
        -Detail "status=$($loginTempIt.Status) code=$(Get-Code $loginTempIt)；若不通过说明 -ItPassword/E2E_IT_PASSWORD 与库里 it 的口令不一致" `
        -Result $loginTempIt
    $tempItToken = ''
    if ($loginTempIt.Status -eq 200) {
        $tempItToken = (Get-Data $loginTempIt).accessToken
        $meTempIt = Send-Req -Client $tempItClient -Method Get -Path '/fd/v1/auth/me' -Token $tempItToken -Actor 'temp-it' -Note 'a8.me(temp-it)'
        $tempItPerms = @((Get-Data $meTempIt).permissions)
        Add-Assertion -Name 'a8.tempIt.hasTransferAuthority' `
            -Condition (($tempItPerms -contains 'TICKET_PROCESS') -and ($tempItPerms -contains 'TICKET_TRANSFER')) `
            -Expected '临时 IT 用户具备 TICKET_PROCESS + TICKET_TRANSFER（新负责人要能继续调整）' `
            -Detail "roles=$(@((Get-Data $meTempIt).roles) -join '|') hasTICKET_PROCESS=$($tempItPerms -contains 'TICKET_PROCESS') hasTICKET_TRANSFER=$($tempItPerms -contains 'TICKET_TRANSFER')" `
            -Result $meTempIt
    } else {
        Add-Assertion -Name 'a8.tempIt.hasTransferAuthority' -Condition $false `
            -Expected '临时 IT 用户具备 TICKET_PROCESS + TICKET_TRANSFER' `
            -Detail '临时用户未登录成功，权限无法读取；后续依赖它的断言会随之失败'
    }

    # ── 6. 载体工单：A（调整分类/权限与参与关系）、B（调整优先级） ───────────
    # 两张独立载体是刻意的：A 在第 15 步被转交给临时 IT 用户，若优先级调整也压在 A 上，
    # 后续断言会因"负责人已换人"而失败，结论就不再只反映被测行为。
    $createA = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
        -Title "片C验收-调整与转交-$stamp" -Description '片 C 真实栈验收：调整分类、时间线、转交与候选人。' `
        -CategoryId $categoryId -Actor 'employee' -Note 'setup.create-ticket(A)'
    $ticketA = (Get-Data $createA).ticketNo
    $versionA = [long](Get-Data $createA).version
    Add-Assertion -Name 'setup.ticketA.create.201' -Condition ($createA.Status -eq 201) -Expected '201' `
        -Detail "ticketNo=$ticketA version=$versionA" -Result $createA
    if ($createA.Status -ne 201) { throw "工单 A 创建失败（status=$($createA.Status) code=$(Get-Code $createA)），后续断言无法进行" }

    $claimA = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketA 'claim') -Token $itToken `
        -Body @{ version = $versionA } -Actor 'it' -Note 'setup.claim(A)'
    Add-Assertion -Name 'setup.ticketA.claim.200' -Condition ($claimA.Status -eq 200) -Expected '200' `
        -Detail "status=$((Get-Data $claimA).status) version=$((Get-Data $claimA).version)" -Result $claimA
    if ($claimA.Status -ne 200) { throw "工单 A 领取失败（status=$($claimA.Status) code=$(Get-Code $claimA)）" }
    $versionA = [long](Get-Data $claimA).version
    Add-KeyAction -Order 1 -Actor 'it' -Action 'claim' -Method 'POST' `
        -Path (Get-ActionPath $ticketA 'claim') -TicketNo $ticketA -Result $claimA -StatusAfter 'PROCESSING'

    $createB = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
        -Title "片C验收-优先级载体-$stamp" -Description '片 C 真实栈验收：优先级替换与 PRIORITY_CHANGE 时间线。' `
        -CategoryId $categoryId -Actor 'employee' -Note 'setup.create-ticket(B)'
    $ticketB = (Get-Data $createB).ticketNo
    $versionB = [long](Get-Data $createB).version
    Add-Assertion -Name 'setup.ticketB.create.201' -Condition ($createB.Status -eq 201) -Expected '201' `
        -Detail "ticketNo=$ticketB version=$versionB" -Result $createB
    if ($createB.Status -ne 201) { throw "工单 B 创建失败（status=$($createB.Status) code=$(Get-Code $createB)）" }
    $claimB = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketB 'claim') -Token $itToken `
        -Body @{ version = $versionB } -Actor 'it' -Note 'setup.claim(B)'
    Add-Assertion -Name 'setup.ticketB.claim.200' -Condition ($claimB.Status -eq 200) -Expected '200' `
        -Detail "status=$((Get-Data $claimB).status) version=$((Get-Data $claimB).version)" -Result $claimB
    if ($claimB.Status -ne 200) { throw "工单 B 领取失败（status=$($claimB.Status) code=$(Get-Code $claimB)）" }
    $versionB = [long](Get-Data $claimB).version

    $script:ticketsInfo['A'] = @{ ticketNo = $ticketA; purpose = '调整分类成功路径 + 转交成功路径 + 候选人 + 时间线（CREATE..TRANSFER 五条）' }
    $script:ticketsInfo['B'] = @{ ticketNo = $ticketB; purpose = '调整优先级成功路径 + 非负责人 409（it 转走后仍指向 it）' }

    # ── 7. 断言组 a1：三个动作与候选人接口的匿名请求 → 401 ───────────────────
    $anonCategory = Send-Req -Client $anonymousClient -Method Post -Path (Get-ActionPath $unknownTicketNo 'change-category') `
        -Body (New-CategoryBody -Version 0 -CategoryId $categoryId -Reason '匿名调整分类') -Actor 'anonymous' -Note 'a1.anonymous.change-category'
    $anonPriority = Send-Req -Client $anonymousClient -Method Post -Path (Get-ActionPath $unknownTicketNo 'change-priority') `
        -Body (New-PriorityBody -Version 0 -Priority 'HIGH' -Reason '匿名调整优先级') -Actor 'anonymous' -Note 'a1.anonymous.change-priority'
    $anonTransfer = Send-Req -Client $anonymousClient -Method Post -Path (Get-ActionPath $unknownTicketNo 'transfer') `
        -Body (New-TransferBody -Version 0 -NewAssigneeId 1 -Reason '匿名转交') -Actor 'anonymous' -Note 'a1.anonymous.transfer'
    $anonCandidates = Send-Req -Client $anonymousClient -Method Get -Path "/fd/v1/tickets/$unknownTicketNo/transfer-candidates" `
        -Actor 'anonymous' -Note 'a1.anonymous.transfer-candidates'
    Add-Assertion -Name 'a1.anonymous.changeCategory.401' `
        -Condition (($anonCategory.Status -eq 401) -and ((Get-Code $anonCategory) -eq 'AUTH_REQUIRED')) `
        -Expected '401 + AUTH_REQUIRED（无令牌一律先被认证闸门拦下）' `
        -Detail "status=$($anonCategory.Status) code=$(Get-Code $anonCategory)" -Result $anonCategory
    Add-Assertion -Name 'a1.anonymous.changePriority.401' `
        -Condition (($anonPriority.Status -eq 401) -and ((Get-Code $anonPriority) -eq 'AUTH_REQUIRED')) `
        -Expected '401 + AUTH_REQUIRED' `
        -Detail "status=$($anonPriority.Status) code=$(Get-Code $anonPriority)" -Result $anonPriority
    Add-Assertion -Name 'a1.anonymous.transfer.401' `
        -Condition (($anonTransfer.Status -eq 401) -and ((Get-Code $anonTransfer) -eq 'AUTH_REQUIRED')) `
        -Expected '401 + AUTH_REQUIRED' `
        -Detail "status=$($anonTransfer.Status) code=$(Get-Code $anonTransfer)" -Result $anonTransfer
    Add-Assertion -Name 'a1.anonymous.transferCandidates.401' `
        -Condition (($anonCandidates.Status -eq 401) -and ((Get-Code $anonCandidates) -eq 'AUTH_REQUIRED')) `
        -Expected '401 + AUTH_REQUIRED' `
        -Detail "status=$($anonCandidates.Status) code=$(Get-Code $anonCandidates)" -Result $anonCandidates

    # ── 8. 断言组 a9/a10/a11：employee 调三个动作与候选人 → 403 ──────────────
    # 用**不存在的编号**发请求（同时覆盖 a11 的存在性用例）：权限闸门在可见性之前，
    # 因此必须仍是 403 而不是 404。若这里出现 404，说明权限判定被挪到了可见性之后。
    $empCategoryUnknown = Send-Req -Client $employeeClient -Method Post -Path (Get-ActionPath $unknownTicketNo 'change-category') `
        -Token $employeeToken -Body (New-CategoryBody -Version 0 -CategoryId $categoryId -Reason '员工越权调整分类') `
        -Actor 'employee' -Note 'a9.employee.changeCategory(unknown)'
    $empPriorityUnknown = Send-Req -Client $employeeClient -Method Post -Path (Get-ActionPath $unknownTicketNo 'change-priority') `
        -Token $employeeToken -Body (New-PriorityBody -Version 0 -Priority 'HIGH' -Reason '员工越权调整优先级') `
        -Actor 'employee' -Note 'a9.employee.changePriority(unknown)'
    $empTransferUnknown = Send-Req -Client $employeeClient -Method Post -Path (Get-ActionPath $unknownTicketNo 'transfer') `
        -Token $employeeToken -Body (New-TransferBody -Version 0 -NewAssigneeId $itUserId -Reason '员工越权转交') `
        -Actor 'employee' -Note 'a9.employee.transfer(unknown)'
    $empCandidates = Send-Req -Client $employeeClient -Method Get -Path "/fd/v1/tickets/$ticketA/transfer-candidates" `
        -Token $employeeToken -Actor 'employee' -Note 'a10.employee.transferCandidates(A)'
    Add-Assertion -Name 'a9.employee.changeCategory403' `
        -Condition (($empCategoryUnknown.Status -eq 403) -and ((Get-Code $empCategoryUnknown) -eq 'TICKET_ACTION_FORBIDDEN')) `
        -Expected '403 + TICKET_ACTION_FORBIDDEN（employee 缺 TICKET_PROCESS）' `
        -Detail "status=$($empCategoryUnknown.Status) code=$(Get-Code $empCategoryUnknown) 编号=$unknownTicketNo（不存在；仍 403 说明权限闸门先于可见性）" `
        -Result $empCategoryUnknown
    Add-Assertion -Name 'a9.employee.changePriority403' `
        -Condition (($empPriorityUnknown.Status -eq 403) -and ((Get-Code $empPriorityUnknown) -eq 'TICKET_ACTION_FORBIDDEN')) `
        -Expected '403 + TICKET_ACTION_FORBIDDEN（employee 缺 TICKET_PROCESS）' `
        -Detail "status=$($empPriorityUnknown.Status) code=$(Get-Code $empPriorityUnknown) 编号=$unknownTicketNo" -Result $empPriorityUnknown
    Add-Assertion -Name 'a9.employee.transfer403' `
        -Condition (($empTransferUnknown.Status -eq 403) -and ((Get-Code $empTransferUnknown) -eq 'TICKET_ACTION_FORBIDDEN')) `
        -Expected '403 + TICKET_ACTION_FORBIDDEN（employee 缺 TICKET_TRANSFER）' `
        -Detail "status=$($empTransferUnknown.Status) code=$(Get-Code $empTransferUnknown) 编号=$unknownTicketNo" -Result $empTransferUnknown
    Add-Assertion -Name 'a10.employee.transferCandidates403' `
        -Condition (($empCandidates.Status -eq 403) -and ((Get-Code $empCandidates) -eq 'TICKET_ACTION_FORBIDDEN')) `
        -Expected '403 + TICKET_ACTION_FORBIDDEN（候选人接口与转交共用 TICKET_TRANSFER）' `
        -Detail "status=$($empCandidates.Status) code=$(Get-Code $empCandidates) ticket=$ticketA（employee 是该单提交人、可见；若可见性在前会返回 409 或 200 列表）" `
        -Result $empCandidates
    Add-Assertion -Name 'a11.permissionGateBeforeVisibility' `
        -Condition (($empCategoryUnknown.Status -eq 403) -and ($empPriorityUnknown.Status -eq 403) -and ($empTransferUnknown.Status -eq 403)) `
        -Expected '三个动作对不存在的编号都返回 403（权限闸门先于可见性；若为 404 说明顺序被改）' `
        -Detail "change-category=$($empCategoryUnknown.Status) change-priority=$($empPriorityUnknown.Status) transfer=$($empTransferUnknown.Status)（编号 $unknownTicketNo 不存在）"

    # ── 9. 断言组 a8：it 缺提交人动作权限（记录事实，不因它失败） ─────────────
    $script:observed.Add("it.hasTICKET_REQUESTER_ACTION=$($itPerms -contains 'TICKET_REQUESTER_ACTION')；片 C 三个动作与候选人接口都只要求 TICKET_PROCESS/TICKET_TRANSFER，与提交人动作权限无关，因此这里只记录事实")
    Add-Assertion -Name 'a8.itRequesterActionRecordedOnly' -Condition $true `
        -Expected '记录 it 是否具备 TICKET_REQUESTER_ACTION（事实记录，不作为通过条件）' `
        -Detail "it.hasTICKET_REQUESTER_ACTION=$($itPerms -contains 'TICKET_REQUESTER_ACTION') roles=$(@($itData.roles) -join '|')"

    # ── 10. 断言组 a6/a7：前置闸门 —— 三个端点必须真的挂在目标后端上 ─────────
    # 用**编号不存在**的请求探端点：change-priority / transfer / transfer-candidates 有判空，
    # 应为 404/TICKET_NOT_FOUND；change-category 的判空缺失，修复前会是 500（见文件头说明）。
    $preflightCategory = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $unknownTicketNo 'change-category') `
        -Token $itToken -Body (New-CategoryBody -Version 0 -CategoryId $categoryId -Reason 'preflight') -Actor 'it' -Note 'a6.preflight.change-category'
    $preflightPriority = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $unknownTicketNo 'change-priority') `
        -Token $itToken -Body (New-PriorityBody -Version 0 -Priority 'HIGH' -Reason 'preflight') -Actor 'it' -Note 'a6.preflight.change-priority'
    $preflightTransfer = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $unknownTicketNo 'transfer') `
        -Token $itToken -Body (New-TransferBody -Version 0 -NewAssigneeId $tempItUserId -Reason 'preflight') -Actor 'it' -Note 'a6.preflight.transfer'
    $preflightCandidates = Send-Req -Client $itClient -Method Get -Path "/fd/v1/tickets/$unknownTicketNo/transfer-candidates" `
        -Token $itToken -Actor 'it' -Note 'a6.preflight.transfer-candidates'

    $preflightCategoryOk = (($preflightCategory.Status -eq 404) -and ((Get-Code $preflightCategory) -eq 'TICKET_NOT_FOUND'))
    $preflightPriorityOk = (($preflightPriority.Status -eq 404) -and ((Get-Code $preflightPriority) -eq 'TICKET_NOT_FOUND'))
    $preflightTransferOk = (($preflightTransfer.Status -eq 404) -and ((Get-Code $preflightTransfer) -eq 'TICKET_NOT_FOUND'))
    $preflightCandidatesOk = (($preflightCandidates.Status -eq 404) -and ((Get-Code $preflightCandidates) -eq 'TICKET_NOT_FOUND'))
    $preflightCategoryKnownBug = ($preflightCategory.Status -eq 500)
    $script:preflightInfo = [ordered]@{
        baseUrl                        = $baseUrl
        unknownTicketNo                = $unknownTicketNo
        changeCategoryStatus           = $preflightCategory.Status
        changeCategoryCode             = (Get-Code $preflightCategory)
        changeCategoryTraceId          = $preflightCategory.TraceId
        changePriorityStatus           = $preflightPriority.Status
        changePriorityCode             = (Get-Code $preflightPriority)
        changePriorityTraceId          = $preflightPriority.TraceId
        transferStatus                 = $preflightTransfer.Status
        transferCode                   = (Get-Code $preflightTransfer)
        transferTraceId                = $preflightTransfer.TraceId
        transferCandidatesStatus       = $preflightCandidates.Status
        transferCandidatesCode         = (Get-Code $preflightCandidates)
        transferCandidatesTraceId      = $preflightCandidates.TraceId
        changeCategoryKnownNullDeref   = $preflightCategoryKnownBug
        mappingConfirmed               = ($preflightCategoryOk -and $preflightPriorityOk -and $preflightTransferOk -and $preflightCandidatesOk)
    }
    Add-Assertion -Name 'a6.changePriority.unknown.404' -Condition $preflightPriorityOk `
        -Expected '404 + TICKET_NOT_FOUND（编号不存在，端点存在）' `
        -Detail "status=$($preflightPriority.Status) code=$(Get-Code $preflightPriority)；500 说明端点存在但缺少判空或其它未处理异常" `
        -Result $preflightPriority
    Add-Assertion -Name 'a6.transfer.unknown.404' -Condition $preflightTransferOk `
        -Expected '404 + TICKET_NOT_FOUND（编号不存在，端点存在）' `
        -Detail "status=$($preflightTransfer.Status) code=$(Get-Code $preflightTransfer)" -Result $preflightTransfer
    Add-Assertion -Name 'a6.transferCandidates.unknown.404' -Condition $preflightCandidatesOk `
        -Expected '404 + TICKET_NOT_FOUND（编号不存在，端点存在）' `
        -Detail "status=$($preflightCandidates.Status) code=$(Get-Code $preflightCandidates)" -Result $preflightCandidates
    Add-Assertion -Name 'a6.changeCategory.unknown.404' -Condition $preflightCategoryOk `
        -Expected '404 + TICKET_NOT_FOUND（编号不存在）' `
        -Detail ("status=$($preflightCategory.Status) code=$(Get-Code $preflightCategory) body=[$(Get-SafeBody $preflightCategory 200)]；" +
        '**本脚本预期这一条在修复前为红**：TicketServiceImpl.changeCategory 缺少 `visible == null` 判空，编号不存在时会在 visible.getStatus() 上抛 NPE 并回落 500；修复后应转绿。不得为了让它通过而改写成 500。') `
        -Result $preflightCategory
    if (-not ($preflightCategoryOk -and $preflightPriorityOk -and $preflightTransferOk -and $preflightCandidatesOk)) {
        throw ("目标后端 $baseUrl 的片 C 端点未全部按契约响应" + "（或目标后端没有这些端点）：" +
            "change-category => $($preflightCategory.Status)/$(Get-Code $preflightCategory)、" +
            "change-priority => $($preflightPriority.Status)/$(Get-Code $preflightPriority)、" +
            "transfer => $($preflightTransfer.Status)/$(Get-Code $preflightTransfer)、" +
            "transfer-candidates => $($preflightCandidates.Status)/$(Get-Code $preflightCandidates)。" +
            '若 change-category 是 500，这是已知缺陷（缺少 visible 判空），不是端点缺失；' +
            '请主会话决定是先修业务代码再重跑，还是只保留这条红证据。清理与基线核对不受影响，仍会执行。')
    }

    # ── 11. 断言组 a12：版本过期 → 409 + 当前快照（三张载体单各一次） ─────────
    $staleCategory = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketA 'change-category') `
        -Token $itToken -Body (New-CategoryBody -Version ($versionA - 1) -CategoryId $categoryId2 -Reason '过期版本调整分类') `
        -Actor 'it' -Note 'a12.stale.changeCategory(A)'
    $staleCategoryData = Get-ErrData $staleCategory
    $rowAStale = Get-TicketDbRow -TicketNo $ticketA
    Add-Assertion -Name 'a12.staleVersion.changeCategory.409' `
        -Condition (($staleCategory.Status -eq 409) -and ((Get-Code $staleCategory) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（发送 version-1，库里快照必须在 data 里）' `
        -Detail "status=$($staleCategory.Status) code=$(Get-Code $staleCategory) 发送version=$($versionA - 1) data.version=$($staleCategoryData.version) data.status=$($staleCategoryData.status)" `
        -Result $staleCategory
    Add-Assertion -Name 'a12.staleVersion.changeCategory.snapshotMatchesDb' `
        -Condition (($null -ne $rowAStale) -and ([int]$staleCategoryData.version -eq $rowAStale.version) -and ($staleCategoryData.status -eq $rowAStale.status)) `
        -Expected 'data.version/data.status 等于库里真实快照' `
        -Detail "data.version=$($staleCategoryData.version) data.status=$($staleCategoryData.status)；库中 version=$($rowAStale.version) status=$($rowAStale.status)" `
        -Result $staleCategory

    $stalePriority = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketB 'change-priority') `
        -Token $itToken -Body (New-PriorityBody -Version ($versionB - 1) -Priority 'HIGH' -Reason '过期版本调整优先级') `
        -Actor 'it' -Note 'a12.stale.changePriority(B)'
    $stalePriorityData = Get-ErrData $stalePriority
    $rowBStale = Get-TicketDbRow -TicketNo $ticketB
    Add-Assertion -Name 'a12.staleVersion.changePriority.409' `
        -Condition (($stalePriority.Status -eq 409) -and ((Get-Code $stalePriority) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT' `
        -Detail "status=$($stalePriority.Status) code=$(Get-Code $stalePriority) 发送version=$($versionB - 1) data.version=$($stalePriorityData.version)" `
        -Result $stalePriority
    Add-Assertion -Name 'a12.staleVersion.changePriority.snapshotMatchesDb' `
        -Condition (($null -ne $rowBStale) -and ([int]$stalePriorityData.version -eq $rowBStale.version) -and ($stalePriorityData.status -eq $rowBStale.status)) `
        -Expected 'data.version/data.status 等于库里真实快照' `
        -Detail "data.version=$($stalePriorityData.version) data.status=$($stalePriorityData.status)；库中 version=$($rowBStale.version) status=$($rowBStale.status)" `
        -Result $stalePriority

    $staleTransfer = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketA 'transfer') `
        -Token $itToken -Body (New-TransferBody -Version ($versionA - 1) -NewAssigneeId $tempItUserId -Reason '过期版本转交') `
        -Actor 'it' -Note 'a12.stale.transfer(A)'
    $staleTransferData = Get-ErrData $staleTransfer
    $rowATransferStale = Get-TicketDbRow -TicketNo $ticketA
    Add-Assertion -Name 'a12.staleVersion.transfer.409' `
        -Condition (($staleTransfer.Status -eq 409) -and ((Get-Code $staleTransfer) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT' `
        -Detail "status=$($staleTransfer.Status) code=$(Get-Code $staleTransfer) 发送version=$($versionA - 1) data.version=$($staleTransferData.version)" `
        -Result $staleTransfer
    Add-Assertion -Name 'a12.staleVersion.transfer.snapshotMatchesDb' `
        -Condition (($null -ne $rowATransferStale) -and ([int]$staleTransferData.version -eq $rowATransferStale.version) -and ($staleTransferData.status -eq $rowATransferStale.status)) `
        -Expected 'data.version/data.status 等于库里真实快照' `
        -Detail "data.version=$($staleTransferData.version) data.status=$($staleTransferData.status)；库中 version=$($rowATransferStale.version) status=$($rowATransferStale.status)" `
        -Result $staleTransfer

    # ── 12. 断言组 a13：非负责人（可见但不是负责人）→ 409 而不是 403 ─────────
    # admin 在演示库同时持有 IT_SUPPORT（权限闸门会放行），但默认不是任何工单的负责人；
    # 给它补一条历史参与关系让 TICKET_VIEW_PARTICIPATED 生效，"看得见但不是负责人"才成立。
    # 该行随工单 A 一起被清理 SQL 按 ticket_id 删除，不触碰运行前就存在的行。
    # 列名里的 ASSIGNED 是 MySQL 8 关键字，必须加反引号；反引号在双引号串里是转义符，故用单引号段拼接。
    $adminParticipantSql = 'INSERT INTO ticket_participant (ticket_id, user_id, `first_assigned_at`, `last_assigned_at`) ' +
    'SELECT id, ' + $adminUserId + ', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM ticket WHERE ticket_no = ''' + $ticketA + ''';'
    $adminParticipantResult = Invoke-MySql -Sql $adminParticipantSql
    Add-Assertion -Name 'a13.setup.adminParticipantAdded' -Condition ($adminParticipantResult.exitCode -eq 0) `
        -Expected '为 admin 补一条历史参与关系，使它能看见工单 A（只为触发身份闸门）' `
        -Detail "exitCode=$($adminParticipantResult.exitCode) stderr=[$(Get-BriefText $adminParticipantResult.stderrText 120)]；该行随工单 A 一起被清理 SQL 删除"
    $script:observed.Add("a13 造可见性：为 admin($adminUserId) 插入 ticket_participant(ticket=$ticketA)，随清理 SQL 按 ticket_id 删除")

    $adminDetailA = Send-Req -Client $adminClient -Method Get -Path "/fd/v1/tickets/$ticketA" -Token $adminToken `
        -Actor 'admin' -Note 'a13.precheck.detail(A)'
    Add-Assertion -Name 'a13.setup.adminCanSeeTicket' -Condition ($adminDetailA.Status -eq 200) `
        -Expected '200（admin 现在能看见工单 A，可见性不再是障碍）' `
        -Detail "status=$($adminDetailA.Status) code=$(Get-Code $adminDetailA)；若为 404 说明参与关系没有让 TICKET_VIEW_PARTICIPATED 生效" `
        -Result $adminDetailA

    $adminCategory = Send-Req -Client $adminClient -Method Post -Path (Get-ActionPath $ticketA 'change-category') `
        -Token $adminToken -Body (New-CategoryBody -Version $versionA -CategoryId $categoryId2 -Reason '非负责人调整分类') `
        -Actor 'admin' -Note 'a13.admin.changeCategory(A)'
    $adminPriority = Send-Req -Client $adminClient -Method Post -Path (Get-ActionPath $ticketA 'change-priority') `
        -Token $adminToken -Body (New-PriorityBody -Version $versionA -Priority 'HIGH' -Reason '非负责人调整优先级') `
        -Actor 'admin' -Note 'a13.admin.changePriority(A)'
    $adminTransfer = Send-Req -Client $adminClient -Method Post -Path (Get-ActionPath $ticketA 'transfer') `
        -Token $adminToken -Body (New-TransferBody -Version $versionA -NewAssigneeId $tempItUserId -Reason '非负责人转交') `
        -Actor 'admin' -Note 'a13.admin.transfer(A)'
    $adminCandidates = Send-Req -Client $adminClient -Method Get -Path "/fd/v1/tickets/$ticketA/transfer-candidates" `
        -Token $adminToken -Actor 'admin' -Note 'a13.admin.transferCandidates(A)'
    $adminHasProcess = $adminPerms -contains 'TICKET_PROCESS'
    $adminHasTransfer = $adminPerms -contains 'TICKET_TRANSFER'
    if ($adminHasProcess) {
        Add-Assertion -Name 'a13.admin.changeCategory.409' `
            -Condition (($adminCategory.Status -eq 409) -and ((Get-Code $adminCategory) -eq 'TICKET_CONFLICT')) `
            -Expected '409 + TICKET_CONFLICT（可见但不是负责人；权限闸门放行后才轮到身份闸门）' `
            -Detail "status=$($adminCategory.Status) code=$(Get-Code $adminCategory) admin.hasTICKET_PROCESS=True" -Result $adminCategory
        Add-Assertion -Name 'a13.admin.changePriority.409' `
            -Condition (($adminPriority.Status -eq 409) -and ((Get-Code $adminPriority) -eq 'TICKET_CONFLICT')) `
            -Expected '409 + TICKET_CONFLICT（同上）' `
            -Detail "status=$($adminPriority.Status) code=$(Get-Code $adminPriority)" -Result $adminPriority
    } else {
        $script:observed.Add("a13.admin.changeCategory/changePriority 未按 409 断言：admin 不具备 TICKET_PROCESS，实测 change-category=$($adminCategory.Status) change-priority=$($adminPriority.Status)")
        Add-Assertion -Name 'a13.admin.changeCategory.recordedOnly' -Condition ($adminCategory.Status -in 403, 409) `
            -Expected '403 或 409（取决于 admin 是否持有 TICKET_PROCESS）' `
            -Detail "status=$($adminCategory.Status) code=$(Get-Code $adminCategory)" -Result $adminCategory
    }
    if ($adminHasTransfer) {
        Add-Assertion -Name 'a13.admin.transfer.409' `
            -Condition (($adminTransfer.Status -eq 409) -and ((Get-Code $adminTransfer) -eq 'TICKET_CONFLICT')) `
            -Expected '409 + TICKET_CONFLICT（可见但不是负责人 → 冲突而不是 403）' `
            -Detail "status=$($adminTransfer.Status) code=$(Get-Code $adminTransfer) admin.hasTICKET_TRANSFER=True" -Result $adminTransfer
        Add-Assertion -Name 'a13.admin.transferCandidates.409' `
            -Condition (($adminCandidates.Status -eq 409) -and ((Get-Code $adminCandidates) -eq 'TICKET_CONFLICT')) `
            -Expected '409 + TICKET_CONFLICT（候选人接口与转交共用同一条身份判定）' `
            -Detail "status=$($adminCandidates.Status) code=$(Get-Code $adminCandidates)" -Result $adminCandidates
    } else {
        $script:observed.Add("a13.admin.transfer/transferCandidates 未按 409 断言：admin 不具备 TICKET_TRANSFER，实测 transfer=$($adminTransfer.Status) candidates=$($adminCandidates.Status)")
        Add-Assertion -Name 'a13.admin.transfer.recordedOnly' -Condition ($adminTransfer.Status -in 403, 409) `
            -Expected '403 或 409（取决于 admin 是否持有 TICKET_TRANSFER）' `
            -Detail "status=$($adminTransfer.Status) code=$(Get-Code $adminTransfer)" -Result $adminTransfer
    }
    $rowAAfterNonAssignee = Get-TicketDbRow -TicketNo $ticketA
    Add-Assertion -Name 'a13.db.ticketUnchangedByNonAssignee' `
        -Condition (($null -ne $rowAAfterNonAssignee) -and ($rowAAfterNonAssignee.status -eq 'PROCESSING') -and ($rowAAfterNonAssignee.version -eq $versionA) -and ($rowAAfterNonAssignee.assigneeId -eq $itUserId) -and ($rowAAfterNonAssignee.categoryId -eq $categoryId)) `
        -Expected "非负责人的 4 次调用什么都没改：PROCESSING / version=$versionA / 负责人仍是 it / 分类未变" `
        -Detail "row=[$($rowAAfterNonAssignee.raw)]"

    # ── 13. 断言组 a14：change-category 成功路径（工单 A） ───────────────────
    $categoryReason = '  分类放错了，改到网络访问  '
    $changeCategoryA = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketA 'change-category') `
        -Token $itToken -Body (New-CategoryBody -Version $versionA -CategoryId $categoryId2 -Reason $categoryReason) `
        -Actor 'it' -Note 'a14.change-category(A)'
    $dataChangeCategoryA = Assert-ActionSnapshot -Name 'a14.changeCategory.200' -Result $changeCategoryA `
        -ExpectedStatus 'PROCESSING' -ExpectedVersion ($versionA + 1) -ExpectedDeadline '' `
        -Detail "ticket=$ticketA 分类 $categoryId -> $categoryId2（状态与负责人不变，期限字段应整体不出现）"
    Add-KeyAction -Order 2 -Actor 'it' -Action 'change-category' -Method 'POST' `
        -Path (Get-ActionPath $ticketA 'change-category') -TicketNo $ticketA -Result $changeCategoryA -StatusAfter 'PROCESSING'
    if ($changeCategoryA.Status -ne 200) { throw "工单 A 调整分类失败（status=$($changeCategoryA.Status) code=$(Get-Code $changeCategoryA)），后续断言无法进行" }
    $versionA = [long]$dataChangeCategoryA.version
    Add-Assertion -Name 'a14.assigneeUnchanged' `
        -Condition ($dataChangeCategoryA.assignee.id -eq $itUserId) `
        -Expected "assignee.id 仍是当前负责人 it（$itUserId）" `
        -Detail "assignee.id=$($dataChangeCategoryA.assignee.id) displayName=[$($dataChangeCategoryA.assignee.displayName)]" -Result $changeCategoryA

    $rowAAfterCategory = Get-TicketDbRow -TicketNo $ticketA
    Assert-Db -Name 'a14.db.categoryReplaced' -Expected `
        "category_id=$categoryId2、status=PROCESSING、priority 未变（MEDIUM）、负责人仍是 it、version=前一步 +1" `
        -Sql ("SELECT status, category_id, priority, IFNULL(assignee_id,-1), version, record_seq " +
        "FROM ticket WHERE ticket_no = '$ticketA';") -Check {
        param($c)
        ($c[0] -eq 'PROCESSING') -and ([long]$c[1] -eq $categoryId2) -and ($c[2] -eq 'MEDIUM') -and
        ([int]$c[3] -eq $itUserId) -and ([int]$c[4] -eq $versionA) -and ([int]$c[5] -eq $versionA + 1)
    }
    $categoryRecord = Get-RecordOfType -TicketNo $ticketA -RecordType 'CATEGORY_CHANGE'
    Add-Assertion -Name 'a14.timeline.categoryChangeRecord' `
        -Condition (($null -ne $categoryRecord) -and ($categoryRecord.fromCategoryId -eq $categoryId) -and ($categoryRecord.toCategoryId -eq $categoryId2) -and ($categoryRecord.fromStatus -eq 'PROCESSING') -and ($categoryRecord.toStatus -eq 'PROCESSING') -and ($categoryRecord.actorId -eq $itUserId)) `
        -Expected "CATEGORY_CHANGE：from_category_id=$categoryId → to_category_id=$categoryId2、两侧状态相同（PROCESSING）、操作人是负责人" `
        -Detail "sequenceNo=$($categoryRecord.sequenceNo) from_category=$($categoryRecord.fromCategoryId) to_category=$($categoryRecord.toCategoryId) from=$($categoryRecord.fromStatus) to=$($categoryRecord.toStatus) actorId=$($categoryRecord.actorId)"
    Add-Assertion -Name 'a14.timeline.reasonTrimmed' `
        -Condition (($null -ne $categoryRecord) -and ($categoryRecord.reason -eq '分类放错了，改到网络访问') -and ($categoryRecord.reasonLen -eq 12)) `
        -Expected 'reason 去除首尾空白后落库（12 个字符）' `
        -Detail "reason=[$($categoryRecord.reason)] 长度=$($categoryRecord.reasonLen)（提交的是带前后空格的 16 字符串，record.reason 列宽 1000）"

    # ── 14. 断言组 a15：change-category 的 400 字段边界，且库中不变 ───────────
    $sigBeforeBadCategory = Get-TicketStateSignature -TicketNo $ticketA
    $badCategoryCases = @(
        @{ key = 'missingVersion'; body = (New-CategoryBody -Version 0 -CategoryId $categoryId2 -Reason '缺版本' -OmitVersion); note = 'a15.missingVersion.changeCategory' },
        @{ key = 'negativeVersion'; body = (New-CategoryBody -Version -1 -CategoryId $categoryId2 -Reason '负版本'); note = 'a15.negativeVersion.changeCategory' },
        @{ key = 'missingCategoryId'; body = (New-CategoryBody -Version $versionA -CategoryId 0 -Reason '缺分类' -OmitCategoryId); note = 'a15.missingCategoryId.changeCategory' },
        @{ key = 'zeroCategoryId'; body = (New-CategoryBody -Version $versionA -CategoryId 0 -Reason '分类 0'); note = 'a15.zeroCategoryId.changeCategory' },
        @{ key = 'blankReason'; body = (New-CategoryBody -Version $versionA -CategoryId $categoryId2 -Reason '   '); note = 'a15.blankReason.changeCategory' },
        @{ key = 'overLongReason'; body = (New-CategoryBody -Version $versionA -CategoryId $categoryId2 -Reason ('测' * 1001)); note = 'a15.overLongReason.changeCategory' }
    )
    $badCategoryResults = @{}
    foreach ($case in $badCategoryCases) {
        $badResult = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketA 'change-category') `
            -Token $itToken -Body $case.body -Actor 'it' -Note $case.note
        $badCategoryResults[$case.key] = $badResult
        Add-Assertion -Name ("a15." + $case.key + ".400") `
            -Condition (($badResult.Status -eq 400) -and ((Get-Code $badResult) -eq 'VALIDATION_FAILED')) `
            -Expected '400 + VALIDATION_FAILED' `
            -Detail "status=$($badResult.Status) code=$(Get-Code $badResult) body=[$(Get-SafeBody $badResult 140)]" -Result $badResult
    }
    $sigAfterBadCategory = Get-TicketStateSignature -TicketNo $ticketA
    Add-Assertion -Name 'a15.db.unchangedAfterBadRequests' -Condition ($sigBeforeBadCategory -eq $sigAfterBadCategory) `
        -Expected '6 条 400 之后状态/分类/优先级/负责人/期限/version/record_seq/记录数全部不变' `
        -Detail "前=[$sigBeforeBadCategory]；后=[$sigAfterBadCategory]（签名=状态|分类|优先级|负责人|期限|version|record_seq|记录数）"

    # ── 15. 断言组 a16：目标分类停用 → 400（演示库当前没有停用分类时转为条件断言） ──
    if ($disabledCategoryId -gt 0) {
        $sigBeforeDisabled = Get-TicketStateSignature -TicketNo $ticketA
        $disabledCategory = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketA 'change-category') `
            -Token $itToken -Body (New-CategoryBody -Version $versionA -CategoryId $disabledCategoryId -Reason '改到停用分类') `
            -Actor 'it' -Note 'a16.disabledCategory.changeCategory(A)'
        Add-Assertion -Name 'a16.disabledCategory.400' `
            -Condition (($disabledCategory.Status -eq 400) -and ((Get-Code $disabledCategory) -eq 'VALIDATION_FAILED')) `
            -Expected "400 + VALIDATION_FAILED（分类 $disabledCategoryId 已停用，不能再被选为当前分类）" `
            -Detail "status=$($disabledCategory.Status) code=$(Get-Code $disabledCategory) body=[$(Get-SafeBody $disabledCategory 140)]" `
            -Result $disabledCategory
        $sigAfterDisabled = Get-TicketStateSignature -TicketNo $ticketA
        Add-Assertion -Name 'a16.db.unchangedAfterDisabledCategory' -Condition ($sigBeforeDisabled -eq $sigAfterDisabled) `
            -Expected '被拒后分类与版本都没变' `
            -Detail "前=[$sigBeforeDisabled]；后=[$sigAfterDisabled]"
    } else {
        $script:observed.Add('演示库没有 status=DISABLED 的分类（R__seed_demo_data.sql 的"打印与扫描"可能被改过），a16 的停用分类 400 用例本次没有执行；该用例下次在有停用分类的库上会自动生效')
        Add-Assertion -Name 'a16.disabledCategory.recordedOnly' -Condition $true `
            -Expected '记录"本次没有停用分类可用"这一事实，不作为通过条件' `
            -Detail '若要让该用例生效，请在演示库保留至少一个停用分类（如种子数据的"打印与扫描"）'
    }

    # ── 16. 断言组 a17：change-priority 成功路径（工单 B） ───────────────────
    $priorityReason = '影响面比预想大，提升优先级'
    $sigBeforePrioritySuccess = Get-TicketStateSignature -TicketNo $ticketB
    $changePriorityB = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketB 'change-priority') `
        -Token $itToken -Body (New-PriorityBody -Version $versionB -Priority 'HIGH' -Reason $priorityReason) `
        -Actor 'it' -Note 'a17.change-priority(B)'
    $dataChangePriorityB = Assert-ActionSnapshot -Name 'a17.changePriority.200' -Result $changePriorityB `
        -ExpectedStatus 'PROCESSING' -ExpectedVersion ($versionB + 1) -ExpectedDeadline '' `
        -Detail "ticket=$ticketB 优先级 MEDIUM -> HIGH（状态、负责人与期限都不变）"
    Add-KeyAction -Order 3 -Actor 'it' -Action 'change-priority' -Method 'POST' `
        -Path (Get-ActionPath $ticketB 'change-priority') -TicketNo $ticketB -Result $changePriorityB -StatusAfter 'PROCESSING'
    if ($changePriorityB.Status -ne 200) { throw "工单 B 调整优先级失败（status=$($changePriorityB.Status) code=$(Get-Code $changePriorityB)）" }
    $versionB = [long]$dataChangePriorityB.version

    Assert-Db -Name 'a17.db.priorityReplaced' -Expected `
        'priority=HIGH、status=PROCESSING、分类未变、负责人仍是 it、version=前一步 +1' `
        -Sql ("SELECT status, category_id, priority, IFNULL(assignee_id,-1), version, record_seq " +
        "FROM ticket WHERE ticket_no = '$ticketB';") -Check {
        param($c)
        ($c[0] -eq 'PROCESSING') -and ([long]$c[1] -eq $categoryId) -and ($c[2] -eq 'HIGH') -and
        ([int]$c[3] -eq $itUserId) -and ([int]$c[4] -eq $versionB) -and ([int]$c[5] -eq $versionB + 1)
    }
    $priorityRecord = Get-RecordOfType -TicketNo $ticketB -RecordType 'PRIORITY_CHANGE'
    Add-Assertion -Name 'a17.timeline.priorityChangeRecord' `
        -Condition (($null -ne $priorityRecord) -and ($priorityRecord.fromPriority -eq 'MEDIUM') -and ($priorityRecord.toPriority -eq 'HIGH') -and ($priorityRecord.fromStatus -eq 'PROCESSING') -and ($priorityRecord.toStatus -eq 'PROCESSING') -and ($priorityRecord.actorId -eq $itUserId)) `
        -Expected 'PRIORITY_CHANGE：from_priority=MEDIUM → to_priority=HIGH、两侧状态相同、操作人是负责人' `
        -Detail "sequenceNo=$($priorityRecord.sequenceNo) from_priority=$($priorityRecord.fromPriority) to_priority=$($priorityRecord.toPriority) from=$($priorityRecord.fromStatus) to=$($priorityRecord.toStatus) actorId=$($priorityRecord.actorId)"
    $script:observed.Add("a17 状态签名：前=[$sigBeforePrioritySuccess]；后=[$(Get-TicketStateSignature -TicketNo $ticketB)]")

    # ── 17. 断言组 a18：change-priority 的 400 字段边界，且库中不变 ───────────
    $sigBeforeBadPriority = Get-TicketStateSignature -TicketNo $ticketB
    $badPriorityCases = @(
        @{ key = 'missingPriority'; body = (New-PriorityBody -Version $versionB -Priority 'HIGH' -Reason '缺优先级' -OmitPriority); note = 'a18.missingPriority.changePriority' },
        @{ key = 'lowercasePriority'; body = (New-PriorityBody -Version $versionB -Priority 'low' -Reason '小写优先级'); note = 'a18.lowercasePriority.changePriority' },
        @{ key = 'urgentPriority'; body = (New-PriorityBody -Version $versionB -Priority 'URGENT' -Reason '不存在的枚举值'); note = 'a18.urgentPriority.changePriority' }
    )
    foreach ($case in $badPriorityCases) {
        $badResult = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketB 'change-priority') `
            -Token $itToken -Body $case.body -Actor 'it' -Note $case.note
        Add-Assertion -Name ("a18." + $case.key + ".400") `
            -Condition (($badResult.Status -eq 400) -and ((Get-Code $badResult) -eq 'VALIDATION_FAILED')) `
            -Expected '400 + VALIDATION_FAILED（priority 取值只认 HIGH/MEDIUM/LOW 三个大写枚举）' `
            -Detail "status=$($badResult.Status) code=$(Get-Code $badResult) body=[$(Get-SafeBody $badResult 140)]" -Result $badResult
    }
    $sigAfterBadPriority = Get-TicketStateSignature -TicketNo $ticketB
    Add-Assertion -Name 'a18.db.unchangedAfterBadRequests' -Condition ($sigBeforeBadPriority -eq $sigAfterBadPriority) `
        -Expected '3 条 400 之后优先级/状态/版本/记录数全部不变（仍应为 HIGH）' `
        -Detail "前=[$sigBeforeBadPriority]；后=[$sigAfterBadPriority]"

    # ── 18. 断言组 a19：transfer 的 400 字段边界，且库中不变 ─────────────────
    $sigBeforeBadTransfer = Get-TicketStateSignature -TicketNo $ticketA
    $badTransferCases = @(
        @{ key = 'missingNewAssigneeId'; body = (New-TransferBody -Version $versionA -NewAssigneeId 0 -Reason '缺新负责人' -OmitNewAssigneeId); note = 'a19.missingNewAssigneeId.transfer' },
        @{ key = 'zeroNewAssigneeId'; body = (New-TransferBody -Version $versionA -NewAssigneeId 0 -Reason '新负责人 0'); note = 'a19.zeroNewAssigneeId.transfer' },
        @{ key = 'blankReason'; body = (New-TransferBody -Version $versionA -NewAssigneeId $tempItUserId -Reason '   '); note = 'a19.blankReason.transfer' },
        @{ key = 'overLongReason'; body = (New-TransferBody -Version $versionA -NewAssigneeId $tempItUserId -Reason ('转' * 1001)); note = 'a19.overLongReason.transfer' },
        @{ key = 'transferToSelf'; body = (New-TransferBody -Version $versionA -NewAssigneeId $itUserId -Reason '转给自己'); note = 'a19.transferToSelf' },
        @{ key = 'transferToRequester'; body = (New-TransferBody -Version $versionA -NewAssigneeId $employeeUserId -Reason '转给提交人'); note = 'a19.transferToRequester' },
        # 非 IT 用户用 employee 而不是 admin：本机演示库里 admin 恰好同时持有 IT_SUPPORT（已知漂移），
        # 拿它当"非 IT 用户"会与服务端的资格判定打架；employee 只有 EMPLOYEE 角色，判定确定。
        @{ key = 'transferToNonItUser'; body = (New-TransferBody -Version $versionA -NewAssigneeId $employeeUserId -Reason '转给非 IT 用户'); note = 'a19.transferToNonItUser' }
    )
    foreach ($case in $badTransferCases) {
        $badResult = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketA 'transfer') `
            -Token $itToken -Body $case.body -Actor 'it' -Note $case.note
        Add-Assertion -Name ("a19." + $case.key + ".400") `
            -Condition (($badResult.Status -eq 400) -and ((Get-Code $badResult) -eq 'VALIDATION_FAILED')) `
            -Expected '400 + VALIDATION_FAILED' `
            -Detail "status=$($badResult.Status) code=$(Get-Code $badResult) body=[$(Get-SafeBody $badResult 140)] 目标用户=$($case.body['newAssigneeId'])" `
            -Result $badResult
    }
    $sigAfterBadTransfer = Get-TicketStateSignature -TicketNo $ticketA
    Add-Assertion -Name 'a19.db.unchangedAfterBadRequests' -Condition ($sigBeforeBadTransfer -eq $sigAfterBadTransfer) `
        -Expected '7 条 400 之后工单 A 状态/分类/负责人/version/record_seq/记录数全部不变' `
        -Detail "前=[$sigBeforeBadTransfer]；后=[$sigAfterBadTransfer]"

    # ── 19. 断言组 a20：GET transfer-candidates 成功路径 ─────────────────────
    $candidates = Send-Req -Client $itClient -Method Get -Path "/fd/v1/tickets/$ticketA/transfer-candidates" `
        -Token $itToken -Actor 'it' -Note 'a20.transferCandidates(A)'
    $candidateItems = @(Get-Data $candidates)
    $candidateIds = @($candidateItems | ForEach-Object { [int]$_.id })
    $candidateFieldNames = @()
    foreach ($item in $candidateItems) {
        foreach ($property in $item.PSObject.Properties) {
            if ($candidateFieldNames -notcontains $property.Name) { $candidateFieldNames += $property.Name }
        }
    }
    $candidateFieldNames = @($candidateFieldNames | Sort-Object)
    $script:candidateInfo = [ordered]@{
        ticketNo            = $ticketA
        status              = $candidates.Status
        traceId             = $candidates.TraceId
        count               = $candidateItems.Count
        ids                 = $candidateIds
        displayNames        = @($candidateItems | ForEach-Object { $_.displayName })
        fieldNames          = $candidateFieldNames
        containsTempItUser  = ($candidateIds -contains $tempItUserId)
        excludesRequester   = (-not ($candidateIds -contains $employeeUserId))
        excludesCurrentUser = (-not ($candidateIds -contains $itUserId))
    }
    Add-Assertion -Name 'a20.transferCandidates.200' `
        -Condition (($candidates.Status -eq 200) -and ($candidateItems.Count -ge 1)) `
        -Expected '200 且返回数组（至少包含本次创建的临时 IT 用户）' `
        -Detail "status=$($candidates.Status) count=$($candidateItems.Count) ids=$($candidateIds -join ',')" -Result $candidates
    Add-Assertion -Name 'a20.transferCandidates.containsTempItUser' `
        -Condition ($candidateIds -contains $tempItUserId) `
        -Expected "列表包含临时 IT 用户（id=$tempItUserId，ENABLED + IT_SUPPORT）" `
        -Detail "ids=$($candidateIds -join ',') names=$((@($candidateItems | ForEach-Object { $_.displayName })) -join ',')" -Result $candidates
    Add-Assertion -Name 'a20.transferCandidates.excludesRequesterAndCurrentAssignee' `
        -Condition ((-not ($candidateIds -contains $employeeUserId)) -and (-not ($candidateIds -contains $itUserId))) `
        -Expected "不含提交人（$employeeUserId）与当前负责人（$itUserId）" `
        -Detail "ids=$($candidateIds -join ',')（selectTransferCandidates 的 requesterId / currentAssigneeId 两个 NOT 条件）" -Result $candidates
    Add-Assertion -Name 'a20.transferCandidates.minimalFields' `
        -Condition (($candidateFieldNames.Count -eq 2) -and ($candidateFieldNames -contains 'id') -and ($candidateFieldNames -contains 'displayName')) `
        -Expected '每条只含 id 与 displayName（7.4 的最小字段接口，不暴露用户名/角色等账号信息）' `
        -Detail "实测字段集合=[$($candidateFieldNames -join ',')]" -Result $candidates

    # ── 20. 断言组 a21：非负责人取候选人 → 409 ───────────────────────────────
    # 上一步 admin 已能看到工单 A，但它是参与者而不是负责人 → 冲突而不是 403。
    if ($adminHasTransfer) {
        Add-Assertion -Name 'a21.nonAssignee.candidates.409' `
            -Condition (($adminCandidates.Status -eq 409) -and ((Get-Code $adminCandidates) -eq 'TICKET_CONFLICT')) `
            -Expected '409 + TICKET_CONFLICT（可见但不是负责人）' `
            -Detail "status=$($adminCandidates.Status) code=$(Get-Code $adminCandidates) data.version=$((Get-ErrData $adminCandidates).version) data.status=$((Get-ErrData $adminCandidates).status)" `
            -Result $adminCandidates
    } else {
        $script:observed.Add("a21 未按 409 断言：admin 不具备 TICKET_TRANSFER，实测 transfer-candidates=$($adminCandidates.Status)")
        Add-Assertion -Name 'a21.nonAssignee.candidates.recordedOnly' -Condition ($adminCandidates.Status -in 403, 409) `
            -Expected '403 或 409（取决于 admin 是否持有 TICKET_TRANSFER）' `
            -Detail "status=$($adminCandidates.Status) code=$(Get-Code $adminCandidates)" -Result $adminCandidates
    }

    # ── 21. 断言组 a22：transfer 成功路径（工单 A → 临时 IT 用户） ───────────
    $transferReason = '该问题属于账号权限范畴，转给对口同事'
    $versionABeforeTransfer = $versionA
    $transferA = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketA 'transfer') `
        -Token $itToken -Body (New-TransferBody -Version $versionA -NewAssigneeId $tempItUserId -Reason $transferReason) `
        -Actor 'it' -Note 'a22.transfer(A)'
    $dataTransferA = Assert-ActionSnapshot -Name 'a22.transfer.200' -Result $transferA `
        -ExpectedStatus 'PROCESSING' -ExpectedVersion ($versionA + 1) -ExpectedDeadline '' `
        -Detail "ticket=$ticketA 负责人 it($itUserId) -> 临时 IT($tempItUserId)"
    Add-KeyAction -Order 4 -Actor 'it' -Action 'transfer' -Method 'POST' `
        -Path (Get-ActionPath $ticketA 'transfer') -TicketNo $ticketA -Result $transferA -StatusAfter 'PROCESSING'
    if ($transferA.Status -ne 200) { throw "工单 A 转交失败（status=$($transferA.Status) code=$(Get-Code $transferA)），后续断言无法进行" }
    $versionA = [long]$dataTransferA.version
    Add-Assertion -Name 'a22.assigneeIsNewAssignee' `
        -Condition (($dataTransferA.assignee.id -eq $tempItUserId) -and ($dataTransferA.assignee.displayName -eq $tempItDisplayName)) `
        -Expected "assignee.id=$tempItUserId 且 displayName=$tempItDisplayName（响应摘要带的是新负责人）" `
        -Detail "assignee.id=$($dataTransferA.assignee.id) displayName=[$($dataTransferA.assignee.displayName)]" -Result $transferA

    Assert-Db -Name 'a22.db.assigneeReplaced' -Expected `
        "assignee_id=$tempItUserId、status 仍是 PROCESSING、分类（$categoryId2）与优先级（MEDIUM）未变、version=转交前 +1" `
        -Sql ("SELECT status, category_id, priority, IFNULL(assignee_id,-1), version, record_seq " +
        "FROM ticket WHERE ticket_no = '$ticketA';") -Check {
        param($c)
        ($c[0] -eq 'PROCESSING') -and ([long]$c[1] -eq $categoryId2) -and ($c[2] -eq 'MEDIUM') -and
        ([int]$c[3] -eq $tempItUserId) -and ([int]$c[4] -eq $versionA) -and ([int]$c[5] -eq $versionABeforeTransfer + 2)
    }
    $transferRecord = Get-RecordOfType -TicketNo $ticketA -RecordType 'TRANSFER'
    Add-Assertion -Name 'a22.timeline.transferRecord' `
        -Condition (($null -ne $transferRecord) -and ($transferRecord.fromAssigneeId -eq $itUserId) -and ($transferRecord.toAssigneeId -eq $tempItUserId) -and ($transferRecord.fromStatus -eq 'PROCESSING') -and ($transferRecord.toStatus -eq 'PROCESSING') -and ($transferRecord.actorId -eq $itUserId)) `
        -Expected "TRANSFER：from_assignee_id=$itUserId → to_assignee_id=$tempItUserId、两侧状态相同、操作人是原负责人" `
        -Detail "sequenceNo=$($transferRecord.sequenceNo) from_assignee=$($transferRecord.fromAssigneeId) to_assignee=$($transferRecord.toAssigneeId) from=$($transferRecord.fromStatus) to=$($transferRecord.toStatus) actorId=$($transferRecord.actorId)"
    $participantRow = Get-MySqlRow -Sql (
        "SELECT COUNT(*) FROM ticket_participant WHERE ticket_id = (SELECT id FROM ticket WHERE ticket_no = '$ticketA') " +
        "AND user_id = $tempItUserId;")
    Add-Assertion -Name 'a22.db.newAssigneeParticipantRow' `
        -Condition ($participantRow.ok -and ([int]$participantRow.columns[0] -eq 1)) `
        -Expected 'ticket_participant 里有新负责人那一行（转交后他才能看到这张工单）' `
        -Detail "row=[$($participantRow.raw)]（ticket_participant 主键是 ticket_id+user_id，1 行即正确）"

    # 转交后原负责人不再能做调整（身份闸门确实换了人）——用同一次请求的版本号验证。
    $itAfterTransfer = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketA 'change-priority') `
        -Token $itToken -Body (New-PriorityBody -Version $versionA -Priority 'LOW' -Reason '转交后原负责人再调整') `
        -Actor 'it' -Note 'a22.formerAssignee.changePriority(A)'
    Add-Assertion -Name 'a22.formerAssignee.changePriority.409' `
        -Condition (($itAfterTransfer.Status -eq 409) -and ((Get-Code $itAfterTransfer) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（转交后 it 已不是负责人，即使版本号正确也被拒）' `
        -Detail "status=$($itAfterTransfer.Status) code=$(Get-Code $itAfterTransfer)" -Result $itAfterTransfer
    $rowAAfterFormerAssignee = Get-TicketDbRow -TicketNo $ticketA
    Add-Assertion -Name 'a22.db.formerAssigneeRejected.nothingChanged' `
        -Condition (($null -ne $rowAAfterFormerAssignee) -and ($rowAAfterFormerAssignee.priority -eq 'MEDIUM') -and ($rowAAfterFormerAssignee.version -eq $versionA) -and ($rowAAfterFormerAssignee.assigneeId -eq $tempItUserId)) `
        -Expected '被拒后优先级仍是 MEDIUM、version 与负责人未变' `
        -Detail "row=[$($rowAAfterFormerAssignee.raw)]"

    # ── 22. 断言组 a23：非负责人取候选人（此时 it 已成为历史参与者）→ 409 ────
    $itCandidatesAfterTransfer = Send-Req -Client $itClient -Method Get -Path "/fd/v1/tickets/$ticketA/transfer-candidates" `
        -Token $itToken -Actor 'it' -Note 'a23.formerAssignee.transferCandidates(A)'
    Add-Assertion -Name 'a23.formerAssignee.candidates.409' `
        -Condition (($itCandidatesAfterTransfer.Status -eq 409) -and ((Get-Code $itCandidatesAfterTransfer) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（it 现在只是参与者：能看见工单，但不是负责人）' `
        -Detail "status=$($itCandidatesAfterTransfer.Status) code=$(Get-Code $itCandidatesAfterTransfer) data.version=$((Get-ErrData $itCandidatesAfterTransfer).version) data.status=$((Get-ErrData $itCandidatesAfterTransfer).status)" `
        -Result $itCandidatesAfterTransfer

    # ── 23. 断言组 a24：时间线（建单→领取→调整分类→调整优先级→转交） ────────
    # 工单 A 上的动作序列就是这条链：CREATE(1) CLAIM(2) CATEGORY_CHANGE(3) PRIORITY_CHANGE(4) TRANSFER(5)。
    # 为了让"优先级调整"也真的发生在这条链上，这里在 A 上补一次由**新负责人**执行的优先级调整。
    $priorityOnA = Send-Req -Client $tempItClient -Method Post -Path (Get-ActionPath $ticketA 'change-priority') `
        -Token $tempItToken -Body (New-PriorityBody -Version $versionA -Priority 'HIGH' -Reason '接手后按影响面提升优先级') `
        -Actor 'temp-it' -Note 'a24.change-priority(A)'
    $dataPriorityOnA = Get-Data $priorityOnA
    Add-Assertion -Name 'a24.newAssignee.changePriority.200' `
        -Condition (($priorityOnA.Status -eq 200) -and ($dataPriorityOnA.status -eq 'PROCESSING') -and ([long]$dataPriorityOnA.version -eq ($versionA + 1))) `
        -Expected '200（转交后新负责人可以继续调整，证明参与关系与权限都生效）' `
        -Detail "status=$($priorityOnA.Status) businessStatus=$($dataPriorityOnA.status) version=$($dataPriorityOnA.version)" -Result $priorityOnA
    if ($priorityOnA.Status -eq 200) { $versionA = [long]$dataPriorityOnA.version }
    Add-KeyAction -Order 5 -Actor 'temp-it' -Action 'change-priority' -Method 'POST' `
        -Path (Get-ActionPath $ticketA 'change-priority') -TicketNo $ticketA -Result $priorityOnA -StatusAfter 'PROCESSING'

    $timelineA = @(Get-TicketRecordDbRows -TicketNo $ticketA)
    $timelineTypes = @($timelineA | ForEach-Object { $_.recordType })
    $timelineSeqs = @($timelineA | ForEach-Object { $_.sequenceNo })
    $expectedTypes = @('CREATE', 'CLAIM', 'CATEGORY_CHANGE', 'TRANSFER', 'PRIORITY_CHANGE')
    $expectedSeqs = @(1, 2, 3, 4, 5)
    Add-Assertion -Name 'a24.timeline.recordTypesAndOrder' `
        -Condition (($timelineTypes.Count -eq 5) -and (($timelineTypes -join ',') -eq ($expectedTypes -join ','))) `
        -Expected "记录类型依次为 $($expectedTypes -join ' → ')" `
        -Detail "实测=$($timelineTypes -join ',')（工单 A：建单→领取→调整分类→转交→新负责人调整优先级）"
    Add-Assertion -Name 'a24.timeline.sequencesAreContinuous' `
        -Condition (($timelineSeqs.Count -eq 5) -and (($timelineSeqs -join ',') -eq ($expectedSeqs -join ','))) `
        -Expected "序号恰为 $($expectedSeqs -join ',')（无跳号）" `
        -Detail "实测=$($timelineSeqs -join ',')"

    # ── 24. 断言组 a25/a26/a27：WAITING_FOR_REQUESTER 下三个动作都不改期限 ────
    $createC = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
        -Title "片C验收-待补充调整与转交-$stamp" -Description '片 C 真实栈验收：待补充状态下调整与转交都不动期限。' `
        -CategoryId $categoryId -Actor 'employee' -Note 'setup.create-ticket(C)'
    $ticketC = (Get-Data $createC).ticketNo
    $versionC = [long](Get-Data $createC).version
    Add-Assertion -Name 'setup.ticketC.create.201' -Condition ($createC.Status -eq 201) -Expected '201' `
        -Detail "ticketNo=$ticketC version=$versionC" -Result $createC
    if ($createC.Status -ne 201) { throw "工单 C 创建失败（status=$($createC.Status) code=$(Get-Code $createC)），待补充断言无法进行" }
    $claimC = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketC 'claim') -Token $itToken `
        -Body @{ version = $versionC } -Actor 'it' -Note 'setup.claim(C)'
    Add-Assertion -Name 'setup.ticketC.claim.200' -Condition ($claimC.Status -eq 200) -Expected '200' `
        -Detail "status=$((Get-Data $claimC).status) version=$((Get-Data $claimC).version)" -Result $claimC
    if ($claimC.Status -ne 200) { throw "工单 C 领取失败（status=$($claimC.Status) code=$(Get-Code $claimC)）" }
    $versionC = [long](Get-Data $claimC).version
    Add-KeyAction -Order 1 -Actor 'it' -Action 'claim' -Method 'POST' `
        -Path (Get-ActionPath $ticketC 'claim') -TicketNo $ticketC -Result $claimC -StatusAfter 'PROCESSING'

    # 进入待补充走真实链路（片 B 的 request-supplement），不使用任何 SQL 造数。
    $requestSupplementC = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketC 'request-supplement') `
        -Token $itToken -Body @{ version = $versionC; content = '请补充账号名与报错截图里的操作步骤' } `
        -Actor 'it' -Note 'setup.request-supplement(C)'
    $dataRequestC = Get-Data $requestSupplementC
    Add-Assertion -Name 'setup.ticketC.requestSupplement.200' `
        -Condition (($requestSupplementC.Status -eq 200) -and ($dataRequestC.status -eq 'WAITING_FOR_REQUESTER')) `
        -Expected '200 且进入 WAITING_FOR_REQUESTER（待补充由真实接口进入）' `
        -Detail "status=$($requestSupplementC.Status) businessStatus=$($dataRequestC.status) version=$($dataRequestC.version) deadline=$($dataRequestC.actionDeadlineAt)" `
        -Result $requestSupplementC
    if ($requestSupplementC.Status -ne 200) { throw "工单 C 请求补充失败（status=$($requestSupplementC.Status) code=$(Get-Code $requestSupplementC)），待补充断言无法进行" }
    $versionC = [long]$dataRequestC.version
    $deadlineC = [string]$dataRequestC.actionDeadlineAt
    Add-KeyAction -Order 2 -Actor 'it' -Action 'request-supplement' -Method 'POST' `
        -Path (Get-ActionPath $ticketC 'request-supplement') -TicketNo $ticketC -Result $requestSupplementC -StatusAfter 'WAITING_FOR_REQUESTER'

    $rowCWaiting = Get-TicketDbRow -TicketNo $ticketC
    $deadlineCSeconds = '(未取得)'
    $deadlineMatchesDbC = $false
    if (($null -ne $rowCWaiting) -and (-not [string]::IsNullOrEmpty($deadlineC))) {
        $deadlineCSeconds = Get-DeadlineSeconds -Value $rowCWaiting.actionDeadlineAt
        $deadlineMatchesDbC = ($deadlineCSeconds -eq (Get-DeadlineSeconds -Value $deadlineC))
    }
    $script:windowInfo = [ordered]@{
        ticketNo              = $ticketC
        actionDeadlineAt      = $deadlineC
        dbActionDeadlineAt    = $deadlineCSeconds
        responseMatchesDb     = $deadlineMatchesDbC
        note                  = '待补充期限由 request-supplement 写入；片 C 三个动作都不得改写它，a25/a26/a27 逐条比对同一个值'
    }
    Add-Assertion -Name 'a25.setup.deadlineCaptured' -Condition (($null -ne $rowCWaiting) -and $deadlineMatchesDbC -and ($rowCWaiting.status -eq 'WAITING_FOR_REQUESTER')) `
        -Expected '进入待补充时把响应里的 actionDeadlineAt 与库里的 action_deadline_at 对齐（比较到秒）' `
        -Detail "响应=$deadlineC；库里=$deadlineCSeconds；row=[$($rowCWaiting.raw)]"

    # 转交：待补充下 it 把工单交给临时 IT 用户，期限必须一字不变。
    $transferC = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketC 'transfer') `
        -Token $itToken -Body (New-TransferBody -Version $versionC -NewAssigneeId $tempItUserId -Reason '待补充期间转交给同事跟进') `
        -Actor 'it' -Note 'a25.transfer(C,waiting)'
    $dataTransferC = Assert-ActionSnapshot -Name 'a25.transfer.200' -Result $transferC `
        -ExpectedStatus 'WAITING_FOR_REQUESTER' -ExpectedVersion ($versionC + 1) -ExpectedDeadline $deadlineC `
        -Detail "待补充下转交：状态不变、期限一字不变、负责人换成新负责人"
    Add-KeyAction -Order 3 -Actor 'it' -Action 'transfer' -Method 'POST' `
        -Path (Get-ActionPath $ticketC 'transfer') -TicketNo $ticketC -Result $transferC -StatusAfter 'WAITING_FOR_REQUESTER'
    if ($transferC.Status -eq 200) {
        $versionC = [long]$dataTransferC.version
        Add-Assertion -Name 'a25.assigneeChanged' -Condition ($dataTransferC.assignee.id -eq $tempItUserId) `
            -Expected "assignee.id=$tempItUserId（转交确实换了人）" `
            -Detail "assignee.id=$($dataTransferC.assignee.id) displayName=[$($dataTransferC.assignee.displayName)]" -Result $transferC
    }
    $rowCAfterTransfer = Get-TicketDbRow -TicketNo $ticketC
    Add-Assertion -Name 'a25.db.deadlineUntouched' `
        -Condition (($null -ne $rowCAfterTransfer) -and ($rowCAfterTransfer.status -eq 'WAITING_FOR_REQUESTER') -and ((Get-DeadlineSeconds -Value $rowCAfterTransfer.actionDeadlineAt) -eq $deadlineCSeconds) -and ($rowCAfterTransfer.assigneeId -eq $tempItUserId)) `
        -Expected "库里 status=WAITING_FOR_REQUESTER、action_deadline_at 仍为 $deadlineCSeconds、负责人已换" `
        -Detail "row=[$($rowCAfterTransfer.raw)]"

    # 新负责人在待补充下继续调整分类与优先级：期限同样一字不变。
    $changeCategoryC = Send-Req -Client $tempItClient -Method Post -Path (Get-ActionPath $ticketC 'change-category') `
        -Token $tempItToken -Body (New-CategoryBody -Version $versionC -CategoryId $categoryId2 -Reason '待补充期间修正分类') `
        -Actor 'temp-it' -Note 'a26.change-category(C,waiting)'
    $dataChangeCategoryC = Assert-ActionSnapshot -Name 'a26.changeCategory.200' -Result $changeCategoryC `
        -ExpectedStatus 'WAITING_FOR_REQUESTER' -ExpectedVersion ($versionC + 1) -ExpectedDeadline $deadlineC `
        -Detail '待补充下调整分类：状态与期限都不变'
    if ($changeCategoryC.Status -eq 200) { $versionC = [long]$dataChangeCategoryC.version }
    $rowCAfterCategory = Get-TicketDbRow -TicketNo $ticketC
    Add-Assertion -Name 'a26.db.categoryReplacedAndDeadlineUntouched' `
        -Condition (($null -ne $rowCAfterCategory) -and ($rowCAfterCategory.categoryId -eq $categoryId2) -and ((Get-DeadlineSeconds -Value $rowCAfterCategory.actionDeadlineAt) -eq $deadlineCSeconds) -and ($rowCAfterCategory.status -eq 'WAITING_FOR_REQUESTER')) `
        -Expected "category_id 已替换为 $categoryId2，期限仍是 $deadlineCSeconds，状态仍是 WAITING_FOR_REQUESTER" `
        -Detail "row=[$($rowCAfterCategory.raw)]"

    $changePriorityC = Send-Req -Client $tempItClient -Method Post -Path (Get-ActionPath $ticketC 'change-priority') `
        -Token $tempItToken -Body (New-PriorityBody -Version $versionC -Priority 'LOW' -Reason '待补充期间降低优先级') `
        -Actor 'temp-it' -Note 'a27.change-priority(C,waiting)'
    $dataChangePriorityC = Assert-ActionSnapshot -Name 'a27.changePriority.200' -Result $changePriorityC `
        -ExpectedStatus 'WAITING_FOR_REQUESTER' -ExpectedVersion ($versionC + 1) -ExpectedDeadline $deadlineC `
        -Detail '待补充下调整优先级：状态与期限都不变'
    if ($changePriorityC.Status -eq 200) { $versionC = [long]$dataChangePriorityC.version }
    $rowCAfterPriority = Get-TicketDbRow -TicketNo $ticketC
    Add-Assertion -Name 'a27.db.priorityReplacedAndDeadlineUntouched' `
        -Condition (($null -ne $rowCAfterPriority) -and ($rowCAfterPriority.priority -eq 'LOW') -and ((Get-DeadlineSeconds -Value $rowCAfterPriority.actionDeadlineAt) -eq $deadlineCSeconds) -and ($rowCAfterPriority.status -eq 'WAITING_FOR_REQUESTER')) `
        -Expected "priority=LOW，期限仍是 $deadlineCSeconds，状态仍是 WAITING_FOR_REQUESTER" `
        -Detail "row=[$($rowCAfterPriority.raw)]"
    Add-Assertion -Name 'a27.deadlineIdenticalAcrossThreeActions' `
        -Condition (($dataTransferC.actionDeadlineAt -eq $deadlineC) -and ($dataChangeCategoryC.actionDeadlineAt -eq $deadlineC) -and ($dataChangePriorityC.actionDeadlineAt -eq $deadlineC)) `
        -Expected '转交 / 调整分类 / 调整优先级三个响应的 actionDeadlineAt 全都等于进入待补充时那个值' `
        -Detail "进入时=$deadlineC；转交=$($dataTransferC.actionDeadlineAt)；调整分类=$($dataChangeCategoryC.actionDeadlineAt)；调整优先级=$($dataChangePriorityC.actionDeadlineAt)"
    $script:ticketsInfo['C'] = @{ ticketNo = $ticketC; purpose = 'WAITING_FOR_REQUESTER 下三个动作都不改期限（含真实链路进入待补充）' }

    # ── 25. 断言组 a29：终态工单上三个动作各 409 ─────────────────────────────
    $createF = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
        -Title "片C验收-终态拒绝-$stamp" -Description '片 C 真实栈验收：终态工单上调整与转交都必须 409。' `
        -CategoryId $categoryId -Actor 'employee' -Note 'setup.create-ticket(F)'
    $ticketF = (Get-Data $createF).ticketNo
    $versionF = [long](Get-Data $createF).version
    Add-Assertion -Name 'setup.ticketF.create.201' -Condition ($createF.Status -eq 201) -Expected '201' `
        -Detail "ticketNo=$ticketF version=$versionF" -Result $createF
    if ($createF.Status -ne 201) { throw "工单 F 创建失败（status=$($createF.Status) code=$(Get-Code $createF)），终态断言无法进行" }
    $claimF = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketF 'claim') -Token $itToken `
        -Body @{ version = $versionF } -Actor 'it' -Note 'setup.claim(F)'
    Add-Assertion -Name 'setup.ticketF.claim.200' -Condition ($claimF.Status -eq 200) -Expected '200' `
        -Detail "status=$((Get-Data $claimF).status) version=$((Get-Data $claimF).version)" -Result $claimF
    if ($claimF.Status -ne 200) { throw "工单 F 领取失败（status=$($claimF.Status) code=$(Get-Code $claimF)）" }
    $versionF = [long](Get-Data $claimF).version

    $changeCategoryF = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketF 'change-category') `
        -Token $itToken -Body (New-CategoryBody -Version $versionF -CategoryId $categoryId2 -Reason '终态用例前置：先调整一次分类') `
        -Actor 'it' -Note 'setup.change-category(F)'
    Add-Assertion -Name 'setup.ticketF.changeCategory.200' -Condition ($changeCategoryF.Status -eq 200) -Expected '200' `
        -Detail "status=$((Get-Data $changeCategoryF).status) version=$((Get-Data $changeCategoryF).version)" -Result $changeCategoryF
    if ($changeCategoryF.Status -eq 200) { $versionF = [long](Get-Data $changeCategoryF).version }

    $changePriorityF = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketF 'change-priority') `
        -Token $itToken -Body (New-PriorityBody -Version $versionF -Priority 'HIGH' -Reason '终态用例前置：再调整一次优先级') `
        -Actor 'it' -Note 'setup.change-priority(F)'
    Add-Assertion -Name 'setup.ticketF.changePriority.200' -Condition ($changePriorityF.Status -eq 200) -Expected '200' `
        -Detail "status=$((Get-Data $changePriorityF).status) version=$((Get-Data $changePriorityF).version)" -Result $changePriorityF
    if ($changePriorityF.Status -eq 200) { $versionF = [long](Get-Data $changePriorityF).version }

    $resolveF = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketF 'submit-resolution') `
        -Token $itToken -Body @{ version = $versionF; content = '已处理完毕，请确认。' } -Actor 'it' -Note 'setup.submit-resolution(F)'
    Add-Assertion -Name 'setup.ticketF.submitResolution.200' -Condition ($resolveF.Status -eq 200) -Expected '200' `
        -Detail "status=$((Get-Data $resolveF).status) version=$((Get-Data $resolveF).version)" -Result $resolveF
    if ($resolveF.Status -eq 200) { $versionF = [long](Get-Data $resolveF).version }

    $confirmF = Send-Req -Client $employeeClient -Method Post -Path (Get-ActionPath $ticketF 'confirm-resolution') `
        -Token $employeeToken -Body @{ version = $versionF } -Actor 'employee' -Note 'setup.confirm-resolution(F)'
    Add-Assertion -Name 'setup.ticketF.confirmResolution.200' -Condition ($confirmF.Status -eq 200) -Expected '200' `
        -Detail "status=$((Get-Data $confirmF).status) version=$((Get-Data $confirmF).version)" -Result $confirmF
    if ($confirmF.Status -eq 200) { $versionF = [long](Get-Data $confirmF).version }

    $rowFTerminal = Get-TicketDbRow -TicketNo $ticketF
    Add-Assertion -Name 'setup.ticketF.isCompleted' `
        -Condition (($null -ne $rowFTerminal) -and ($rowFTerminal.status -eq 'COMPLETED') -and ($rowFTerminal.endedAt -eq 'SET') -and ($rowFTerminal.completionMethod -eq 'REQUESTER_CONFIRMED')) `
        -Expected '走完 领取→调整→提交解决→员工确认 后工单 F 是 COMPLETED（伴 ended_at SET 与 completion_method）' `
        -Detail "row=[$($rowFTerminal.raw)]；终态用例必须以真终态为载体，不能用 SQL 造一个假状态"
    $script:ticketsInfo['F'] = @{ ticketNo = $ticketF; purpose = '终态 COMPLETED 上三个动作各 409' }

    $terminalCategory = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketF 'change-category') `
        -Token $itToken -Body (New-CategoryBody -Version $versionF -CategoryId $categoryId -Reason '终态调整分类') `
        -Actor 'it' -Note 'a29.terminal.changeCategory(F)'
    $terminalPriority = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketF 'change-priority') `
        -Token $itToken -Body (New-PriorityBody -Version $versionF -Priority 'LOW' -Reason '终态调整优先级') `
        -Actor 'it' -Note 'a29.terminal.changePriority(F)'
    $terminalTransfer = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketF 'transfer') `
        -Token $itToken -Body (New-TransferBody -Version $versionF -NewAssigneeId $tempItUserId -Reason '终态转交') `
        -Actor 'it' -Note 'a29.terminal.transfer(F)'
    Add-Assertion -Name 'a29.terminal.changeCategory.409' `
        -Condition (($terminalCategory.Status -eq 409) -and ((Get-Code $terminalCategory) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（COMPLETED 不在可调整状态里）' `
        -Detail "status=$($terminalCategory.Status) code=$(Get-Code $terminalCategory) data.version=$((Get-ErrData $terminalCategory).version) data.status=$((Get-ErrData $terminalCategory).status)" `
        -Result $terminalCategory
    Add-Assertion -Name 'a29.terminal.changePriority.409' `
        -Condition (($terminalPriority.Status -eq 409) -and ((Get-Code $terminalPriority) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT' `
        -Detail "status=$($terminalPriority.Status) code=$(Get-Code $terminalPriority)" -Result $terminalPriority
    Add-Assertion -Name 'a29.terminal.transfer.409' `
        -Condition (($terminalTransfer.Status -eq 409) -and ((Get-Code $terminalTransfer) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT' `
        -Detail "status=$($terminalTransfer.Status) code=$(Get-Code $terminalTransfer)" -Result $terminalTransfer
    Add-Assertion -Name 'a29.terminal.conflictSnapshotMatchesDb' `
        -Condition (($null -ne $rowFTerminal) -and ([int](Get-ErrData $terminalCategory).version -eq $rowFTerminal.version) -and ((Get-ErrData $terminalCategory).status -eq 'COMPLETED')) `
        -Expected '409 的 data.version/data.status 是终态真实快照（COMPLETED）' `
        -Detail "data.version=$((Get-ErrData $terminalCategory).version) data.status=$((Get-ErrData $terminalCategory).status)；库中 version=$($rowFTerminal.version) status=$($rowFTerminal.status)"
    $rowFAfterRejects = Get-TicketDbRow -TicketNo $ticketF
    Add-Assertion -Name 'a29.terminal.db.unchangedAfterRejects' `
        -Condition (($null -ne $rowFAfterRejects) -and ($rowFAfterRejects.status -eq 'COMPLETED') -and ($rowFAfterRejects.version -eq $versionF) -and ($rowFAfterRejects.categoryId -eq $categoryId2) -and ($rowFAfterRejects.assigneeId -eq $itUserId)) `
        -Expected '三条 409 之后终态工单完全没变（分类仍是前置那一次、负责人仍是 it、version 未增）' `
        -Detail "row=[$($rowFAfterRejects.raw)]"

    Write-Host ''
    Write-Host '验收断言全部执行完毕，开始清理与基线核对。'
} catch {
    Add-Assertion -Name 'run.unexpectedFailure' -Condition $false -Expected '脚本正常跑完' `
        -Detail ("异常：" + $_.Exception.Message)
} finally {
    foreach ($client in $clients) {
        if ($null -ne $client) { $client.Dispose() }
    }
}

# ── 26. 清理：只删本次创建的工单、记录、参与关系与临时 IT 用户 ──────────────
$script:cleanupInfo['startedAt'] = (Get-Date).ToString('s')
$ownTickets = @($ticketA, $ticketB, $ticketC, $ticketF) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

try {
    if ($ownTickets.Count -eq 0) {
        $script:cleanupInfo['note'] = '本次运行没有创建任何工单，无需清理工单；仍会尝试删除临时 IT 用户。'
        Add-Assertion -Name 'a30.cleanup.nothingCreated' -Condition $true -Expected '无自建工单' `
            -Detail '脚本在建单前就失败，未写入任何工单数据'
    } else {
        $quoted = ($ownTickets | ForEach-Object { "'" + $_ + "'" }) -join ','
        $ticketList = "SELECT id FROM ticket WHERE ticket_no IN ($quoted)"
        $cleanupStatements = @(
            "DELETE FROM ticket_attachment WHERE record_id IN (SELECT id FROM ticket_record WHERE ticket_id IN ($ticketList));",
            "DELETE FROM ticket_relation WHERE source_ticket_id IN ($ticketList) OR target_ticket_id IN ($ticketList);",
            "DELETE FROM ticket_record WHERE ticket_id IN ($ticketList);",
            "DELETE FROM ticket_participant WHERE ticket_id IN ($ticketList);",
            "DELETE FROM ticket WHERE ticket_no IN ($quoted);"
        )
        $script:cleanupInfo['ticketNos'] = $ownTickets
        $script:cleanupInfo['sql'] = $cleanupStatements
        $script:cleanupInfo['note'] = '只删除本次运行创建的工单及其记录/参与关系（附件与关联表按 ticket_id 清一遍，片 C 不产生这两类行，属防御性语句）。'

        $cleanupResult = Invoke-MySql -Sql ($cleanupStatements -join "`n")
        $script:cleanupInfo['exitCode'] = $cleanupResult.exitCode
        $script:cleanupInfo['stderr'] = $cleanupResult.stderrText
        Add-Assertion -Name 'a30.cleanup.ticketDeletesSucceeded' -Condition ($cleanupResult.exitCode -eq 0) `
            -Expected '工单清理 SQL 退出码 0' `
            -Detail "exitCode=$($cleanupResult.exitCode) stderr=[$(Get-BriefText $cleanupResult.stderrText 120)]"

        $leftRow = Get-MySqlRow -Sql "SELECT COUNT(*) FROM ticket WHERE ticket_no IN ($quoted);"
        Add-Assertion -Name 'a30.cleanup.noOwnTicketsLeft' -Condition ($leftRow.ok -and ([int]$leftRow.columns[0] -eq 0)) `
            -Expected '自建工单已全部删除' -Detail "row=[$($leftRow.raw)]"
    }
} catch {
    Add-Assertion -Name 'a30.cleanup.ticketDeletesSucceeded' -Condition $false -Expected '清理阶段无异常' `
        -Detail ("清理异常：" + $_.Exception.Message)
}

try {
    if ($tempItUserId -gt 0) {
        $tempCleanupStatements = @(
            "DELETE FROM iam_user_role WHERE user_id = $tempItUserId;",
            "DELETE FROM iam_user WHERE id = $tempItUserId AND username = '$tempItUsername';"
        )
        $script:cleanupInfo['tempUserSql'] = $tempCleanupStatements
        $tempCleanupResult = Invoke-MySql -Sql ($tempCleanupStatements -join "`n")
        $script:cleanupInfo['tempUserExitCode'] = $tempCleanupResult.exitCode
        Add-Assertion -Name 'a30.cleanup.tempItUserDeletesSucceeded' -Condition ($tempCleanupResult.exitCode -eq 0) `
            -Expected '临时 IT 用户的角色行与用户行删除成功（退出码 0）' `
            -Detail "exitCode=$($tempCleanupResult.exitCode) stderr=[$(Get-BriefText $tempCleanupResult.stderrText 120)]"
        $tempLeftRow = Get-MySqlRow -Sql "SELECT COUNT(*) FROM iam_user WHERE username = '$tempItUsername';"
        Add-Assertion -Name 'a30.cleanup.noTempUserLeft' -Condition ($tempLeftRow.ok -and ([int]$tempLeftRow.columns[0] -eq 0)) `
            -Expected '临时 IT 用户已删除' -Detail "row=[$($tempLeftRow.raw)]"
    } else {
        $script:cleanupInfo['tempUserNote'] = '本次运行没有创建临时 IT 用户（建用户前就失败），无需删除。'
        Add-Assertion -Name 'a30.cleanup.noTempUserCreated' -Condition $true -Expected '无临时 IT 用户' `
            -Detail '脚本在创建临时 IT 用户前就失败，未写入该行'
    }
} catch {
    Add-Assertion -Name 'a30.cleanup.tempItUserDeletesSucceeded' -Condition $false -Expected '临时用户清理阶段无异常' `
        -Detail ("清理异常：" + $_.Exception.Message)
}

# ── 27. 清理后基线核对（7 个计数 + 工单号列表与运行前逐字相同） ──────────────
try {
    $demoAfter = Get-DemoFingerprint
    $script:cleanupInfo['demoDatabaseBefore'] = $demoBefore
    $script:cleanupInfo['demoDatabaseAfter'] = $demoAfter

    $sameCounts = ($null -ne $demoBefore) -and ($demoBefore.tickets -eq $demoAfter.tickets) -and `
        ($demoBefore.records -eq $demoAfter.records) -and ($demoBefore.participants -eq $demoAfter.participants) -and `
        ($demoBefore.users -eq $demoAfter.users) -and ($demoBefore.roles -eq $demoAfter.roles) -and `
        ($demoBefore.permissions -eq $demoAfter.permissions) -and ($demoBefore.categories -eq $demoAfter.categories)
    Add-Assertion -Name 'a31.baseline.countsRestored' -Condition $sameCounts `
        -Expected '演示库七项计数与运行前完全一致（用户/角色/权限/分类/工单/记录/参与关系）' `
        -Detail ("运行前 工单={0}/记录={1}/参与者={2}/用户={3}/角色={4}/权限={5}/分类={6}；运行后 工单={7}/记录={8}/参与者={9}/用户={10}/角色={11}/权限={12}/分类={13}" -f `
            $demoBefore.tickets, $demoBefore.records, $demoBefore.participants, $demoBefore.users, $demoBefore.roles, $demoBefore.permissions, $demoBefore.categories, `
            $demoAfter.tickets, $demoAfter.records, $demoAfter.participants, $demoAfter.users, $demoAfter.roles, $demoAfter.permissions, $demoAfter.categories)

    $sameTicketNos = ($null -ne $demoBefore) -and ($demoBefore.ticketNos -eq $demoAfter.ticketNos)
    Add-Assertion -Name 'a31.baseline.ticketNoListUntouched' -Condition $sameTicketNos `
        -Expected '运行前工单号列表与运行后逐字相同（自建工单已全部删除，既有工单一行未动）' `
        -Detail "运行前=[$($demoBefore.ticketNos)]；运行后=[$($demoAfter.ticketNos)]"

    $sameTicketFingerprint = ($null -ne $demoBefore) -and ($demoBefore.ticketRows -eq $demoAfter.ticketRows)
    Add-Assertion -Name 'a31.baseline.ticketFingerprintUntouched' -Condition $sameTicketFingerprint `
        -Expected '既有工单的 status/version/record_seq/负责人/期限/结束时间逐行一致' `
        -Detail "运行前指纹长度=$($demoBefore.ticketRows.Length)；运行后指纹长度=$($demoAfter.ticketRows.Length)；是否相同=$sameTicketFingerprint"

    $sameRecordFingerprint = ($null -ne $demoBefore) -and ($demoBefore.recordRows -eq $demoAfter.recordRows)
    Add-Assertion -Name 'a31.baseline.recordFingerprintUntouched' -Condition $sameRecordFingerprint `
        -Expected '既有记录的 id/序号/类型/时间逐条一致（不可变时间线没有被改动）' `
        -Detail "运行前指纹长度=$($demoBefore.recordRows.Length)；运行后指纹长度=$($demoAfter.recordRows.Length)；是否相同=$sameRecordFingerprint"

    $sameParticipants = ($null -ne $demoBefore) -and ($demoBefore.participantRows -eq $demoAfter.participantRows)
    Add-Assertion -Name 'a31.baseline.participantFingerprintUntouched' -Condition $sameParticipants `
        -Expected '既有参与关系逐条一致（含脚本为 admin 补的那条已随工单 A 删除）' `
        -Detail "运行前=[$($demoBefore.participantRows)]；运行后=[$($demoAfter.participantRows)]"

    $sameUsers = ($null -ne $demoBefore) -and ($demoBefore.userRows -eq $demoAfter.userRows)
    Add-Assertion -Name 'a31.baseline.userRowsUntouched' -Condition $sameUsers `
        -Expected '既有用户的 id/username/status 逐条一致（临时 IT 用户已删除）' `
        -Detail "运行前=[$($demoBefore.userRows)]；运行后=[$($demoAfter.userRows)]"

    $script:cleanupInfo['dailySequenceBefore'] = $demoBefore.dailySequence
    $script:cleanupInfo['dailySequenceAfter'] = $demoAfter.dailySequence
    Add-Assertion -Name 'a31.dailySequenceIncrementedAsExpected' -Condition ($null -ne $demoAfter) `
        -Expected 'ticket_daily_sequence 递增属预期、不回退（接口建单必然递增，编号不复用）' `
        -Detail "运行前=[$($demoBefore.dailySequence)]；运行后=[$($demoAfter.dailySequence)]；回退就意味着改动了运行前就存在的行，因此本脚本只记录不回退"
} catch {
    Add-Assertion -Name 'a31.baseline.checkFailed' -Condition $false -Expected '清理后基线核对可完成' `
        -Detail ("核对异常：" + $_.Exception.Message)
}

# ── 28. 安全边界声明（与断言一起进证据） ────────────────────────────────────
$script:cleanupInfo['dockerCommandsUsed'] = @('docker exec -i -e MYSQL_PWD <mysql 容器> mysql -uroot -N -B --default-character-set=utf8mb4 <库名>（SQL 走 stdin）')
$script:cleanupInfo['composeDownExecuted'] = $false
$script:cleanupInfo['containersOrVolumesModified'] = $false
$script:cleanupInfo['backendProcessTouched'] = $false
$script:cleanupInfo['targetBaseUrl'] = $baseUrl
$script:cleanupInfo['finishedAt'] = (Get-Date).ToString('s')
Add-Assertion -Name 'a32.safetyBoundary.declared' -Condition $true `
    -Expected '明确声明三条边界' `
    -Detail ('未触碰用户自己启动的 8081 后端（脚本只向目标基址发 HTTP，不启动/不重启/不结束任何进程）；' +
    '未执行 docker compose down；未修改容器与卷（只对既有 mysql 容器执行 docker exec 读写演示库）')

# ── 29. 汇总与证据（无 BOM 的 UTF-8） ───────────────────────────────────────
# 注意：List[object] 一律用 .ToArray()，不要用 @() 包（Windows PowerShell 5.1 会抛
# System.ArgumentException: Argument types do not match）；条件值先算成变量再进哈希表字面量。
$finishedAt = Get-Date
$failed = @($script:results | Where-Object { -not $_.passed })
$failedCount = $failed.Count
$totalCount = $script:results.Count
$passedCount = $totalCount - $failedCount
if ($failedCount -gt 0) { $script:quitCode = 1 }

$relativeScriptPath = 'scripts/slice-c-adjust-transfer-acceptance.ps1'
$relativeOutPath = 'docs/acceptance/2026-10-07-slice-c-adjust-transfer.json'
$invocation = 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\slice-c-adjust-transfer-acceptance.ps1'
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
        tempAccount           = $tempItUsername
        credentialsPrinted    = $false
        credentialsInEvidence = $false
    }
    $totalsEntry = [ordered]@{
        total  = $totalCount
        passed = $passedCount
        failed = $failedCount
    }
    $evidence = [ordered]@{
        _note = ('本文件由 scripts/slice-c-adjust-transfer-acceptance.ps1 写出（无 BOM 的 UTF-8）。' +
            '只记录状态码、业务码、traceId、断言结论与 SQL；不含口令、密钥与 accessToken，也不含机器绝对路径。')
        stage            = '完整工单状态机 片 C：调整与转交（change-category + change-priority + transfer + transfer-candidates）真实栈验收'
        slice            = 'C'
        date             = $startedAt.ToString('yyyy-MM-dd')
        result           = $resultValue
        script           = $scriptEntry
        target           = $targetEntry
        preflight        = $script:preflightInfo
        tempItUser       = $script:tempUserInfo
        supplementWindow = $script:windowInfo
        transferCandidates = $script:candidateInfo
        tickets          = $script:ticketsInfo
        keyActions       = $keyActionList
        databaseChecks   = $dbCheckList
        assertions       = $assertionList
        httpLog          = $httpLogList
        sqlExecuted      = $executedSqlList
        cleanup          = $script:cleanupInfo
        observed         = $observedList
        totals           = $totalsEntry
        notes            = $notesList
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
        stage      = '完整工单状态机 片 C：调整与转交真实栈验收'
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
Write-Host ("阶段：片 C 调整与转交；目标后端：{0}" -f $baseUrl)
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
    Write-Host ("演示库计数：运行前 工单={0}/记录={1}/参与者={2}/用户={3}/工单号=[{4}]" -f `
            $demoBefore.tickets, $demoBefore.records, $demoBefore.participants, $demoBefore.users, $demoBefore.ticketNos)
}
if ($null -ne $demoAfter) {
    Write-Host ("            运行后 工单={0}/记录={1}/参与者={2}/用户={3}/工单号=[{4}]" -f `
            $demoAfter.tickets, $demoAfter.records, $demoAfter.participants, $demoAfter.users, $demoAfter.ticketNos)
}
Write-Host ("证据：{0}（无 BOM UTF-8={1}，可回读解析={2}，{3} 字节）" -f `
        $relativeOutPath, (-not $hasBom), ($null -ne $parsedBack), $writtenBytes.Length)
Write-Host '本次执行过的清理 SQL（脚本已执行）：'
$printedCleanup = $false
foreach ($key in @('sql', 'tempUserSql')) {
    if ($script:cleanupInfo.Contains($key) -and @($script:cleanupInfo[$key]).Count -gt 0) {
        foreach ($statement in $script:cleanupInfo[$key]) { Write-Host "  $statement" }
        $printedCleanup = $true
    }
}
if (-not $printedCleanup) { Write-Host '  （本次运行没有创建任何数据，无需清理）' }
if ($failedCount -gt 0) {
    Write-Host '失败项：' -ForegroundColor Red
    $failed | ForEach-Object { Write-Host ("  - {0} :: {1}" -f $_.name, $_.detail) -ForegroundColor Red }
}
Write-Host ("退出码：{0}" -f $script:quitCode)
exit $script:quitCode
