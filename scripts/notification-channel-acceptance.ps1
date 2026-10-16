# 站内信（应用内通知通道）真实栈验收（2026-10-14）
#   本轮契约见 docs/api-design.md 7.7（模块 com.flowdesk.notification）；若与文件冲突以文件为准。
#     GET  /fd/v1/notifications                     本人通知列表（page/size/unreadOnly，id 倒序）
#     GET  /fd/v1/notifications/unread-count        本人未读条数
#     POST /fd/v1/notifications/{notificationId}/read  标记单条已读（幂等；他人或不存在统一 404）
#     POST /fd/v1/notifications/actions/read-all    全部标记已读（幂等）
#   发布语义（5 类动作与工单写事务同事务，动作回滚则通知一并回滚）：
#     request-supplement → 提交人 SUPPLEMENT_REQUESTED
#     submit-resolution  → 提交人 RESOLUTION_SUBMITTED
#     request-cancel     → 当前负责人 CANCEL_REQUESTED
#     approve-cancel     → 提交人 CANCEL_APPROVED
#     reject-cancel      → 提交人 CANCEL_REJECTED
#   其余动作（claim / supplement / withdraw-supplement-request / transfer / close / 追加处理记录 ...）
#   本轮明确不发通知——脚本用「claim 前后接收方未读数不变」做一条反向断言。
#
# 前置
#   · docker compose up -d mysql redis（本脚本只对既有 mysql 容器执行 docker exec，不碰容器生命周期）
#   · 目标后端必须是**包含站内信（2026-10-14，迁移 V10）的最新代码**，由调用方自己启动。脚本只发 HTTP
#     请求，不启动、不重启、不结束任何后端进程；8081 上调用方自己启动的实例保持原样。
#   · 换基址：$env:FLOWDESK_BASE_URL = 'http://127.0.0.1:8092' 或 -BaseUrl http://127.0.0.1:8092
#   · 演示账号可用 E2E_EMPLOYEE_USERNAME / E2E_EMPLOYEE_PASSWORD、E2E_IT_* 覆盖（缺省 employee/it，口令 123456）
#   · 通知表是本轮新增（V10）；脚本假定演示库里**没有**运行前既有的未读通知：
#     read-all 会对收件人的全部通知生效，若库中原本就有未读通知，收尾指纹（逐行 read_at）会把它记成差异。
#     这正是指纹断言要暴露的情况——本脚本只负责删自己创建的通知，不回滚既有行。
#
# 运行
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\notification-channel-acceptance.ps1 -BaseUrl http://127.0.0.1:8081
#   证据路径可覆盖：-OutFile <path> 或别名 -EvidencePath <path>
#
# 本文件必须保存为「带 BOM 的 UTF-8」：Windows PowerShell 5.1 会按系统代码页读取无 BOM 的
# UTF-8 脚本，中文注释会被解析成乱码并报语法错误。证据文件相反，必须写成**无 BOM 的 UTF-8**。
#
# 证据
#   docs/acceptance/2026-10-14-notification-channel.json（无 BOM 的 UTF-8，脚本自己写出）
#   含：_note、invocation（带 -BaseUrl）、script.evidencePath（仓库根相对路径）、script / target /
#   preflight / fingerprints{before,after} / keyActions（每条带 X-Trace-Id）/ httpLog / dbChecks /
#   assertions（name / expected / actual / why / passed）/ sqlExecuted / cleanup.sql / totals。
#
# 权限闸门顺序（断言）
#   认证 401（不设权限编码，只要求已认证）→ 参数校验 400 → 归属隔离 404（他人/不存在统一 404）。
#
# 安全边界
#   · 只创建并删除**脚本自己创建**的工单（含它的 ticket_record / ticket_participant / notification）；
#     按主键/票据编号精确删除，绝不触碰运行前就存在的任何行。通知行也必须删干净——notification 的
#     recipient_id 与 ticket_id 都是限制删除外键，因此**先删通知、再删记录/参与/工单**。
#   · 运行前后各测一次演示库指纹（至少 notification / ticket / ticket_record / ticket_participant /
#     iam_user / iam_user_role 六项计数 + 通知与工单逐行指纹）并断言完全相同。
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
    [string]$OutFile = (Join-Path $PSScriptRoot '..\docs\acceptance\2026-10-14-notification-channel.json'),
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
$sqlWorkDir = Join-Path $env:TEMP "flowdesk-notif-acc-$stamp"
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
$script:notificationFactsInfo = [ordered]@{}
$script:readSemanticsInfo = [ordered]@{}

if ([string]::IsNullOrWhiteSpace($EmployeeUser)) { $EmployeeUser = 'employee' }
if ([string]::IsNullOrWhiteSpace($EmployeePassword)) { $EmployeePassword = '123456' }
if ([string]::IsNullOrWhiteSpace($ItUser)) { $ItUser = 'it' }
if ([string]::IsNullOrWhiteSpace($ItPassword)) { $ItPassword = '123456' }

# 本次运行的工单标题前缀：清理按它精确命中本脚本自建的工单（演示库里没有这个前缀）。
$ticketTitlePrefix = "NC-ACC-$stamp"
$unknownNotificationId = 999999999

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

# $Step 是断言编号式的短标签（如 c2.requestSupplement），进 httpLog 的 step 字段；
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
function Send-CreateTicket {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Token,
        [string]$Title,
        [string]$Description,
        [long]$CategoryId,
        [string]$SubmissionKey,
        [string]$Step = '-',
        [string]$Actor = 'employee',
        [string]$Note = 'create-ticket'
    )
    if ([string]::IsNullOrWhiteSpace($SubmissionKey)) {
        $SubmissionKey = [guid]::NewGuid().ToString()
    }
    $payload = ([ordered]@{
            submissionKey = $SubmissionKey
            title         = $Title
            description   = $Description
            categoryId    = $CategoryId
            priority      = 'MEDIUM'
        }) | ConvertTo-Json -Compress
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
                "；contentType=multipart/form-data（ticket part 携 JSON；submissionKey=" + $SubmissionKey + "）")
            })
        return $result
    } finally {
        $request.Dispose()
    }
}

function Get-ActionPath {
    param([string]$TicketNo, [string]$Action)
    return "/fd/v1/tickets/$TicketNo/actions/$Action"
}

# 工单动作的统一入口：路径、方法固定，traceId 收口在一处。
function Send-Action {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Token,
        [string]$TicketNo,
        [string]$Action,
        $Body,
        [string]$Step = '-',
        [string]$Actor = '-',
        [string]$Note = '-'
    )
    return Send-Req -Client $Client -Method Post -Path (Get-ActionPath $TicketNo $Action) -Token $Token `
        -Body $Body -Step $Step -Actor $Actor -Note $Note
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

# 通知列表里的条目（items 恒为数组）。
function Get-NotificationItems {
    param($Result)
    if ($null -eq $Result) { return @() }
    $data = Get-Data $Result
    if ($null -eq $data) { return @() }
    return @($data.items)
}

# 同一张工单 + 同一 category 的条目（id 倒序，第一条即最新）。
function Select-Notification {
    param($Result, [string]$TicketNo, [string]$Category)
    return ,@(Get-NotificationItems $Result | Where-Object { $_.ticketNo -eq $TicketNo -and $_.category -eq $Category })
}

# 列表里 readAt 为空的条数（= 列表口径的未读）。
function Get-UnreadCountFromList {
    param($Result)
    return @(Get-NotificationItems $Result | Where-Object { $null -eq $_.readAt }).Count
}

# unread-count 响应的 unreadCount；不可读时返回 -1。
function Get-UnreadCount {
    param($Result)
    $data = Get-Data $Result
    if ($null -eq $data) { return -1 }
    return [int]$data.unreadCount
}

# 未读计数一致性：unread-count 与列表里 readAt 为空的条数必须相等；返回 unread-count。
function Assert-UnreadConsistency {
    param(
        [string]$Name,
        $Client,
        [string]$Token,
        [string]$Actor,
        [string]$Reason
    )
    $countResp = Send-Req -Client $Client -Method Get -Path '/fd/v1/notifications/unread-count' `
        -Token $Token -Step "$Name.count" -Actor $Actor -Note "$Reason（未读条数）"
    $listResp = Send-Req -Client $Client -Method Get -Path '/fd/v1/notifications?page=1&size=100' `
        -Token $Token -Step "$Name.list" -Actor $Actor -Note "$Reason（列表：readAt 为空的条数）"
    $reported = Get-UnreadCount $countResp
    $fromList = Get-UnreadCountFromList $listResp
    Add-Assertion -Name $Name `
        -Condition (($countResp.Status -eq 200) -and ($listResp.Status -eq 200) -and
        ($reported -ge 0) -and ($reported -eq $fromList)) `
        -Expected '200 且 unread-count == 列表里 readAt 为空的条数' `
        -Actual "unreadCount=$reported listUnread=$fromList countStatus=$($countResp.Status) listStatus=$($listResp.Status)" `
        -Why $Reason -Result $countResp
    return $reported
}

# 演示库指纹：运行前与清理后各测一次，断言完全相同（既有行一行都不许变）。
# 六项计数按验收要求：notification / ticket / ticket_record / ticket_participant / iam_user / iam_user_role；
# 另附两种逐行指纹——通知行（id|category|recipient_id|read_at）与工单行（ticket_no|status）。
function Get-DemoFingerprint {
    $countsSql = "SELECT (SELECT COUNT(*) FROM notification), (SELECT COUNT(*) FROM ticket), " +
    "(SELECT COUNT(*) FROM ticket_record), (SELECT COUNT(*) FROM ticket_participant), " +
    "(SELECT COUNT(*) FROM iam_user), (SELECT COUNT(*) FROM iam_user_role);"
    $countsRow = Get-MySqlRow -Sql $countsSql
    $counts = @($countsRow.columns)
    $ticketRows = Get-MySqlRow -Sql (
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(ticket_no,'|',status) ORDER BY id SEPARATOR ' ;; '),'-') FROM ticket;")
    $notificationRows = Get-MySqlRow -Sql (
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(id,'|',category,'|',recipient_id,'|'," +
        "IFNULL(DATE_FORMAT(read_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL')) ORDER BY id SEPARATOR ' ;; '),'-') FROM notification;")

    $fingerprint = [ordered]@{
        at               = (Get-Date).ToString('s')
        notifications    = -1
        tickets          = -1
        records          = -1
        participants     = -1
        users            = -1
        userRoles        = -1
        ticketRows       = $ticketRows.raw
        notificationRows = $notificationRows.raw
        readOk           = $countsRow.ok
    }
    if ($countsRow.ok -and $counts.Count -ge 6) {
        $fingerprint.notifications = [int]$counts[0]
        $fingerprint.tickets = [int]$counts[1]
        $fingerprint.records = [int]$counts[2]
        $fingerprint.participants = [int]$counts[3]
        $fingerprint.users = [int]$counts[4]
        $fingerprint.userRoles = [int]$counts[5]
    }
    return [pscustomobject]$fingerprint
}

# ── 2. 客户端与状态 ─────────────────────────────────────────────────────────

$employeeClient = New-Client
$itClient = New-Client
$anonymousClient = New-Client
$clients = @($employeeClient, $itClient, $anonymousClient)

$employeeUserId = -1
$itUserId = -1
$categoryId = -1
$demoBefore = $null
$demoAfter = $null

# 载体工单（全部由接口创建，收尾按标题前缀精确删除）
$ticketMain = ''       # 主链：request-supplement → submit-resolution → request-cancel → reject-cancel
$ticketApprove = ''    # 第二条：request-cancel → approve-cancel
$ticketReverse = ''    # 反向断言：claim 不发通知

Write-Host ("目标后端：{0}；证据：{1}" -f $baseUrl, $OutFile)

# ── 3. 预检与登录 ───────────────────────────────────────────────────────────

# 匿名访问四个端点：统一安全链必须拦在鉴权之前（401 + AUTH_REQUIRED）。
$anonList = Send-Req -Client $anonymousClient -Method Get -Path '/fd/v1/notifications?page=1&size=20' `
    -Token '' -Step 'b1.anon.list' -Actor 'anonymous' -Note '无令牌读取通知列表'
Add-Assertion -Name 'b1.anon.list.401' `
    -Condition (($anonList.Status -eq 401) -and ((Get-Code $anonList) -eq 'AUTH_REQUIRED')) `
    -Expected '401 + AUTH_REQUIRED' -Actual "status=$($anonList.Status) code=$(Get-Code $anonList)" `
    -Why '列表只按当前登录用户过滤；匿名可读意味着任何人的收件箱都能被拉取' -Result $anonList

$anonUnread = Send-Req -Client $anonymousClient -Method Get -Path '/fd/v1/notifications/unread-count' `
    -Token '' -Step 'b2.anon.unreadCount' -Actor 'anonymous' -Note '无令牌读取未读数'
Add-Assertion -Name 'b2.anon.unreadCount.401' `
    -Condition (($anonUnread.Status -eq 401) -and ((Get-Code $anonUnread) -eq 'AUTH_REQUIRED')) `
    -Expected '401 + AUTH_REQUIRED' -Actual "status=$($anonUnread.Status) code=$(Get-Code $anonUnread)" `
    -Why '未读数是收件人本人的聚合；匿名可读意味着身份与收件人绑定失守' -Result $anonUnread

$anonRead = Send-Req -Client $anonymousClient -Method Post -Path "/fd/v1/notifications/$unknownNotificationId/read" `
    -Token '' -Step 'b3.anon.markRead' -Actor 'anonymous' -Note '无令牌标记已读'
Add-Assertion -Name 'b3.anon.markRead.401' `
    -Condition (($anonRead.Status -eq 401) -and ((Get-Code $anonRead) -eq 'AUTH_REQUIRED')) `
    -Expected '401 + AUTH_REQUIRED' -Actual "status=$($anonRead.Status) code=$(Get-Code $anonRead)" `
    -Why '标记已读是写操作；匿名可写意味着可以替任何人改动收件箱状态' -Result $anonRead

$anonReadAll = Send-Req -Client $anonymousClient -Method Post -Path '/fd/v1/notifications/actions/read-all' `
    -Token '' -Step 'b4.anon.readAll' -Actor 'anonymous' -Note '无令牌全部标记已读'
Add-Assertion -Name 'b4.anon.readAll.401' `
    -Condition (($anonReadAll.Status -eq 401) -and ((Get-Code $anonReadAll) -eq 'AUTH_REQUIRED')) `
    -Expected '401 + AUTH_REQUIRED' -Actual "status=$($anonReadAll.Status) code=$(Get-Code $anonReadAll)" `
    -Why '全部已读是收件人本人的清空动作；匿名可调用意味着可以静默清掉别人的未读' -Result $anonReadAll

$loginEmployee = Send-Req -Client $employeeClient -Method Post -Path '/fd/v1/auth/login' `
    -Body @{ username = $EmployeeUser; password = $EmployeePassword } -Step 'login.employee' `
    -Actor 'employee' -Note '演示员工登录'
$loginIt = Send-Req -Client $itClient -Method Post -Path '/fd/v1/auth/login' `
    -Body @{ username = $ItUser; password = $ItPassword } -Step 'login.it' -Actor 'it' `
    -Note '演示 IT 登录'
Add-Assertion -Name 'login.employee.200' -Condition ($loginEmployee.Status -eq 200) -Expected '200' `
    -Actual "status=$($loginEmployee.Status) code=$(Get-Code $loginEmployee)" `
    -Why '主链的提交人与收件人都是这个身份；登录失败说明演示账号口令与 .env/默认值不一致' `
    -Result $loginEmployee
Add-Assertion -Name 'login.it.200' -Condition ($loginIt.Status -eq 200) -Expected '200' `
    -Actual "status=$($loginIt.Status) code=$(Get-Code $loginIt)" `
    -Why 'IT 是当前负责人一侧的收件人；登录失败则该侧断言无法进行' -Result $loginIt
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
    -Expected '200 且至少一个启用分类' -Actual "status=$($options.Status) count=$($optionItems.Count)" `
    -Why '接口建单必须带 categoryId；没有启用分类就没法造出真实的载体工单' -Result $options
if ($optionItems.Count -lt 1) { throw '演示库没有启用分类，无法创建载体工单' }
$categoryId = [long]$optionItems[0].id

# 数据库直连预检：没有 .env 或容器不在时，指纹与直查断言会是 FAIL 而不是静默跳过
$pingRow = Get-MySqlRow -Sql "SELECT 1;"
Add-Assertion -Name 'preflight.db.available' -Condition ($script:dbAvailable -and $pingRow.ok) `
    -Expected '1（.env 加载且 docker exec 可查库）' `
    -Actual ("dbAvailable=$($script:dbAvailable) pingOk=$($pingRow.ok) raw=[$($pingRow.raw)]") `
    -Why '指纹、通知行直查与清理都依赖数据库直连；不可用时后面所有直查断言都失去意义' -Result $null
if (-not ($script:dbAvailable -and $pingRow.ok)) {
    throw "无法从 $EnvFile 读取演示库连接信息（FLOWDESK_DB_NAME / FLOWDESK_MYSQL_ROOT_PASSWORD），或容器 $DbContainer 不可直连"
}

# 演示库账号 id（收件人核对用）
$empIdRow = Get-MySqlRow -Sql "SELECT IFNULL(id,-1) FROM iam_user WHERE username = '$EmployeeUser';"
if ($empIdRow.ok) { $employeeUserId = [int]$empIdRow.columns[0] }
$itIdRow = Get-MySqlRow -Sql "SELECT IFNULL(id,-1) FROM iam_user WHERE username = '$ItUser';"
if ($itIdRow.ok) { $itUserId = [int]$itIdRow.columns[0] }
Add-Assertion -Name 'preflight.demoUserIds.available' `
    -Condition (($employeeUserId -gt 0) -and ($itUserId -gt 0)) `
    -Expected '演示库 employee / it 的 id 都可读' -Actual "employeeId=$employeeUserId itId=$itUserId" `
    -Why '通知行的 recipient_id 要与收件人一一核对；读不到 id 时归属断言无法判定' -Result $null

$demoBefore = Get-DemoFingerprint
$script:preflightInfo = [ordered]@{
    baseUrl              = $baseUrl
    dbAvailable          = $script:dbAvailable
    demoFingerprintAt    = $demoBefore.at
    demoCountsBefore     = @($demoBefore.notifications, $demoBefore.tickets, $demoBefore.records, $demoBefore.participants, $demoBefore.users, $demoBefore.userRoles)
    categoryId           = $categoryId
    employeeUserId       = $employeeUserId
    itUserId             = $itUserId
    unknownNotificationId = $unknownNotificationId
}

# ── 4. c 组：主链发布与可见性（ticketMain） ─────────────────────────────────
# 员工建单 → IT 领取 → IT request-supplement（提交人收 SUPPLEMENT_REQUESTED）
#   → IT withdraw-supplement-request（回到 PROCESSING，不发通知）→ IT submit-resolution（提交人收 RESOLUTION_SUBMITTED）
#   → 员工 request-cancel（当前负责人收 CANCEL_REQUESTED）→ IT reject-cancel（提交人收 CANCEL_REJECTED）

$createMain = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
    -Title "$ticketTitlePrefix-主链" -Description '站内信验收：主链载体工单。' `
    -CategoryId $categoryId -Step 'c1.create.main' -Actor 'employee' -Note '员工创建主链工单'
Add-Assertion -Name 'c1.create.main.201' -Condition ($createMain.Status -eq 201) -Expected '201' `
    -Actual "status=$($createMain.Status) code=$(Get-Code $createMain) body=[$(Get-SafeBody $createMain)]" `
    -Why '主链的全部通知都挂在这张单上；建单失败后面没有可发布事件的载体' -Result $createMain
if ($createMain.Status -ne 201) { throw "主链工单创建失败（status=$($createMain.Status)）" }
$ticketMain = (Get-Data $createMain).ticketNo
Add-KeyAction -Order 1 -Actor 'employee' -Action 'create.main' -Method 'POST' -Path '/fd/v1/tickets' `
    -TicketNo $ticketMain -Result $createMain -Step 'c1.create.main'

$claimMain = Send-Action -Client $itClient -Token $itToken -TicketNo $ticketMain -Action 'claim' `
    -Body @{ version = 0 } -Step 'c1.claim.main' -Actor 'it' -Note 'IT 领取主链工单'
Add-Assertion -Name 'c1.claim.main.200' -Condition ($claimMain.Status -eq 200) -Expected '200' `
    -Actual "status=$($claimMain.Status) code=$(Get-Code $claimMain)" `
    -Why 'request-supplement 要求本人是当前负责人；领取失败则后续 IT 动作都无法进行' -Result $claimMain
Add-KeyAction -Order 2 -Actor 'it' -Action 'claim' -Method 'POST' `
    -Path (Get-ActionPath $ticketMain 'claim') -TicketNo $ticketMain -Result $claimMain -Step 'c1.claim.main'
if ($claimMain.Status -ne 200) { throw "主链工单领取失败（status=$($claimMain.Status)）" }

# request-supplement → 提交人 SUPPLEMENT_REQUESTED
$reqSupp = Send-Action -Client $itClient -Token $itToken -TicketNo $ticketMain -Action 'request-supplement' `
    -Body @{ version = 1; content = '请补充复现步骤与完整报错信息。' } -Step 'c2.requestSupplement' `
    -Actor 'it' -Note 'IT 请求补充：应给提交人发 SUPPLEMENT_REQUESTED'
Add-Assertion -Name 'c2.requestSupplement.200' -Condition ($reqSupp.Status -eq 200) -Expected '200' `
    -Actual "status=$($reqSupp.Status) code=$(Get-Code $reqSupp) body=[$(Get-SafeBody $reqSupp)]" `
    -Why '这是第 1 类发布事件；失败则该 event 与通知无法核对' -Result $reqSupp
Add-KeyAction -Order 3 -Actor 'it' -Action 'request-supplement' -Method 'POST' `
    -Path (Get-ActionPath $ticketMain 'request-supplement') -TicketNo $ticketMain -Result $reqSupp -Step 'c2.requestSupplement'
$vAfterSupp = [long](Get-Data $reqSupp).version

$empListAfterSupp = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/notifications?page=1&size=100' `
    -Token $employeeToken -Step 'c2.employee.list' -Actor 'employee' -Note '员工列表：应命中 SUPPLEMENT_REQUESTED'
$suppItems = Select-Notification $empListAfterSupp -TicketNo $ticketMain -Category 'SUPPLEMENT_REQUESTED'
$suppItem = $null
if ($suppItems.Count -ge 1) { $suppItem = $suppItems[0] }
Add-Assertion -Name 'c2.employee.supplementFound' `
    -Condition (($suppItems.Count -eq 1) -and ($null -ne $suppItem) -and ($suppItem.ticketNo -eq $ticketMain) -and
    ($suppItem.category -eq 'SUPPLEMENT_REQUESTED')) `
    -Expected "1 条：category=SUPPLEMENT_REQUESTED ticketNo=$ticketMain（收件人是本人=员工）" `
    -Actual "count=$($suppItems.Count) category=$($suppItem.category) ticketNo=$($suppItem.ticketNo) id=$($suppItem.id)" `
    -Why 'request-supplement 只通知提交人；命中且恰一条才说明收件人与 category 都对' -Result $empListAfterSupp

$itListAfterSupp = Send-Req -Client $itClient -Method Get -Path '/fd/v1/notifications?page=1&size=100' `
    -Token $itToken -Step 'c2.it.list' -Actor 'it' -Note 'IT 列表：不应出现发给员工的 SUPPLEMENT_REQUESTED（跨用户不可见）'
$suppInIt = Select-Notification $itListAfterSupp -TicketNo $ticketMain -Category 'SUPPLEMENT_REQUESTED'
Add-Assertion -Name 'c2.it.crossUserInvisible' -Condition ($suppInIt.Count -eq 0) `
    -Expected '0 条（发给员工的通知不出现在 IT 的收件箱里）' -Actual "count=$($suppInIt.Count)" `
    -Why '通知只按收件人本人范围读写；跨用户可见意味着收件箱串号' -Result $itListAfterSupp

$empUnreadAfterSupp = Assert-UnreadConsistency -Name 'c2.employee.unreadConsistency' -Client $employeeClient `
    -Token $employeeToken -Actor 'employee' -Reason '发布 SUPPLEMENT_REQUESTED 后员工未读计数与列表口径一致'

# withdraw-supplement-request → 回到 PROCESSING，且不发布通知（为 submit-resolution 创造合法状态）
$withdrawSupp = Send-Action -Client $itClient -Token $itToken -TicketNo $ticketMain -Action 'withdraw-supplement-request' `
    -Body @{ version = $vAfterSupp; reason = '信息已足够，撤回补充请求。' } -Step 'c3.withdrawSupplement' `
    -Actor 'it' -Note 'IT 撤回补充请求：回到处理中，本动作本轮不发通知'
Add-Assertion -Name 'c3.withdrawSupplement.200' -Condition ($withdrawSupp.Status -eq 200) -Expected '200' `
    -Actual "status=$($withdrawSupp.Status) code=$(Get-Code $withdrawSupp)" `
    -Why 'submit-resolution 只允许处理中；撤回补充请求是回到处理中的最轻路径（不发通知）' -Result $withdrawSupp
Add-KeyAction -Order 4 -Actor 'it' -Action 'withdraw-supplement-request' -Method 'POST' `
    -Path (Get-ActionPath $ticketMain 'withdraw-supplement-request') -TicketNo $ticketMain -Result $withdrawSupp `
    -Step 'c3.withdrawSupplement'
if ($withdrawSupp.Status -ne 200) { throw "主链撤回补充请求失败（status=$($withdrawSupp.Status)）" }
$vAfterWithdraw = [long](Get-Data $withdrawSupp).version

# submit-resolution → 提交人 RESOLUTION_SUBMITTED
$submitRes = Send-Action -Client $itClient -Token $itToken -TicketNo $ticketMain -Action 'submit-resolution' `
    -Body @{ version = $vAfterWithdraw; content = '已按报错修复并回归验证。' } -Step 'c4.submitResolution' `
    -Actor 'it' -Note 'IT 提交解决结果：应给提交人发 RESOLUTION_SUBMITTED'
Add-Assertion -Name 'c4.submitResolution.200' -Condition ($submitRes.Status -eq 200) -Expected '200' `
    -Actual "status=$($submitRes.Status) code=$(Get-Code $submitRes) body=[$(Get-SafeBody $submitRes)]" `
    -Why '这是第 2 类发布事件' -Result $submitRes
Add-KeyAction -Order 5 -Actor 'it' -Action 'submit-resolution' -Method 'POST' `
    -Path (Get-ActionPath $ticketMain 'submit-resolution') -TicketNo $ticketMain -Result $submitRes -Step 'c4.submitResolution'
$vAfterSubmitRes = [long](Get-Data $submitRes).version

$empListAfterRes = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/notifications?page=1&size=100' `
    -Token $employeeToken -Step 'c4.employee.list' -Actor 'employee' -Note '员工列表：应新增 RESOLUTION_SUBMITTED'
$resItems = Select-Notification $empListAfterRes -TicketNo $ticketMain -Category 'RESOLUTION_SUBMITTED'
Add-Assertion -Name 'c4.employee.resolutionFound' `
    -Condition (($resItems.Count -eq 1) -and ($resItems[0].ticketNo -eq $ticketMain) -and
    ($resItems[0].category -eq 'RESOLUTION_SUBMITTED')) `
    -Expected "1 条：category=RESOLUTION_SUBMITTED ticketNo=$ticketMain" `
    -Actual "count=$($resItems.Count) category=$($resItems[0].category) ticketNo=$($resItems[0].ticketNo)" `
    -Why 'submit-resolution 只通知提交人' -Result $empListAfterRes

# request-cancel（员工）→ 当前负责人 IT 收 CANCEL_REQUESTED
$reqCancelMain = Send-Action -Client $employeeClient -Token $employeeToken -TicketNo $ticketMain `
    -Action 'request-cancel' -Body @{ version = $vAfterSubmitRes; reason = '问题其实已经自行解决。' } `
    -Step 'c5.requestCancel' -Actor 'employee' -Note '员工在待确认上发起撤销请求：应给当前负责人发 CANCEL_REQUESTED'
Add-Assertion -Name 'c5.requestCancel.200' -Condition ($reqCancelMain.Status -eq 200) -Expected '200' `
    -Actual "status=$($reqCancelMain.Status) code=$(Get-Code $reqCancelMain) body=[$(Get-SafeBody $reqCancelMain)]" `
    -Why '这是第 3 类发布事件，且收件人是当前负责人而非提交人' -Result $reqCancelMain
Add-KeyAction -Order 6 -Actor 'employee' -Action 'request-cancel' -Method 'POST' `
    -Path (Get-ActionPath $ticketMain 'request-cancel') -TicketNo $ticketMain -Result $reqCancelMain -Step 'c5.requestCancel'
$vAfterReqCancel = [long](Get-Data $reqCancelMain).version

$itListAfterReqCancel = Send-Req -Client $itClient -Method Get -Path '/fd/v1/notifications?page=1&size=100' `
    -Token $itToken -Step 'c5.it.list' -Actor 'it' -Note 'IT 列表：应命中发给负责人的 CANCEL_REQUESTED'
$cancelReqItems = Select-Notification $itListAfterReqCancel -TicketNo $ticketMain -Category 'CANCEL_REQUESTED'
$cancelReqItem = $null
if ($cancelReqItems.Count -ge 1) { $cancelReqItem = $cancelReqItems[0] }
Add-Assertion -Name 'c5.it.cancelRequestedFound' `
    -Condition (($cancelReqItems.Count -eq 1) -and ($null -ne $cancelReqItem) -and
    ($cancelReqItem.category -eq 'CANCEL_REQUESTED') -and ($cancelReqItem.ticketNo -eq $ticketMain)) `
    -Expected "1 条：category=CANCEL_REQUESTED ticketNo=$ticketMain（收件人=当前负责人 IT）" `
    -Actual "count=$($cancelReqItems.Count) category=$($cancelReqItem.category) id=$($cancelReqItem.id)" `
    -Why 'request-cancel 的收件人是当前负责人；它出现在 IT 列表而不是员工列表才说明收件人正确' -Result $itListAfterReqCancel

$empListCancelAbsent = Select-Notification $empListAfterRes -TicketNo $ticketMain -Category 'CANCEL_REQUESTED'
Add-Assertion -Name 'c5.employee.cancelRequestedAbsent' -Condition ($empListCancelAbsent.Count -eq 0) `
    -Expected '0 条（CANCEL_REQUESTED 发给负责人，不发给提交人）' -Actual "count=$($empListCancelAbsent.Count)（对比的是 c4 时点的员工列表）" `
    -Why '若提交人也收到同一事件，收件人判定就会失去区分度' -Result $empListAfterRes

# reject-cancel（IT）→ 提交人员工收 CANCEL_REJECTED
$rejectMain = Send-Action -Client $itClient -Token $itToken -TicketNo $ticketMain -Action 'reject-cancel' `
    -Body @{ version = $vAfterReqCancel; reason = '问题尚未复现，请先按修复验证。' } -Step 'c6.rejectCancel' `
    -Actor 'it' -Note 'IT 拒绝撤销请求：应给提交人发 CANCEL_REJECTED'
Add-Assertion -Name 'c6.rejectCancel.200' -Condition ($rejectMain.Status -eq 200) -Expected '200' `
    -Actual "status=$($rejectMain.Status) code=$(Get-Code $rejectMain) body=[$(Get-SafeBody $rejectMain)]" `
    -Why '这是第 4 类发布事件' -Result $rejectMain
Add-KeyAction -Order 7 -Actor 'it' -Action 'reject-cancel' -Method 'POST' `
    -Path (Get-ActionPath $ticketMain 'reject-cancel') -TicketNo $ticketMain -Result $rejectMain -Step 'c6.rejectCancel'

$empListAfterReject = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/notifications?page=1&size=100' `
    -Token $employeeToken -Step 'c6.employee.list' -Actor 'employee' -Note '员工列表：应新增 CANCEL_REJECTED'
$rejItems = Select-Notification $empListAfterReject -TicketNo $ticketMain -Category 'CANCEL_REJECTED'
Add-Assertion -Name 'c6.employee.rejectedFound' `
    -Condition (($rejItems.Count -eq 1) -and ($rejItems[0].ticketNo -eq $ticketMain) -and
    ($rejItems[0].category -eq 'CANCEL_REJECTED')) `
    -Expected "1 条：category=CANCEL_REJECTED ticketNo=$ticketMain" `
    -Actual "count=$($rejItems.Count) category=$($rejItems[0].category) ticketNo=$($rejItems[0].ticketNo)" `
    -Why 'reject-cancel 只通知提交人' -Result $empListAfterReject

$empUnreadAfterReject = Assert-UnreadConsistency -Name 'c6.employee.unreadConsistency' -Client $employeeClient `
    -Token $employeeToken -Actor 'employee' -Reason '发布 RESOLUTION_SUBMITTED / CANCEL_REJECTED 后员工未读计数与列表口径一致'

$script:notificationFactsInfo = [ordered]@{
    mainTicket             = $ticketMain
    supplementNotification = ("id=" + $suppItem.id + " category=" + $suppItem.category)
    resolutionNotification = ("id=" + $resItems[0].id + " category=" + $resItems[0].category)
    cancelRequestedToIt    = ("id=" + $cancelReqItem.id + " category=" + $cancelReqItem.category)
    rejectedToEmployee     = ("id=" + $rejItems[0].id + " category=" + $rejItems[0].category)
    employeeUnreadAfterSupp   = $empUnreadAfterSupp
    employeeUnreadAfterReject = $empUnreadAfterReject
}

# ── 5. d 组：第二条工单的批准路径（approve-cancel → CANCEL_APPROVED） ─────────
# 员工建单 → IT 领取（处理中）→ 员工 request-cancel（负责人收 CANCEL_REQUESTED）
#   → IT approve-cancel（提交人收 CANCEL_APPROVED）

$createApprove = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
    -Title "$ticketTitlePrefix-批准路径" -Description '站内信验收：批准撤销的载体工单。' `
    -CategoryId $categoryId -Step 'd1.create.approve' -Actor 'employee' -Note '员工创建批准路径工单'
Add-Assertion -Name 'd1.create.approve.201' -Condition ($createApprove.Status -eq 201) -Expected '201' `
    -Actual "status=$($createApprove.Status) code=$(Get-Code $createApprove)" `
    -Why '批准路径是第 5 类发布事件的唯一载体；建单失败则该格无法进行' -Result $createApprove
if ($createApprove.Status -ne 201) { throw "批准路径工单创建失败（status=$($createApprove.Status)）" }
$ticketApprove = (Get-Data $createApprove).ticketNo
Add-KeyAction -Order 8 -Actor 'employee' -Action 'create.approve' -Method 'POST' -Path '/fd/v1/tickets' `
    -TicketNo $ticketApprove -Result $createApprove -Step 'd1.create.approve'

$claimApprove = Send-Action -Client $itClient -Token $itToken -TicketNo $ticketApprove -Action 'claim' `
    -Body @{ version = 0 } -Step 'd2.claim.approve' -Actor 'it' -Note 'IT 领取批准路径工单'
Add-Assertion -Name 'd2.claim.approve.200' -Condition ($claimApprove.Status -eq 200) -Expected '200' `
    -Actual "status=$($claimApprove.Status) code=$(Get-Code $claimApprove)" `
    -Why 'request-cancel / approve-cancel 都要求工单有人负责；领取失败则后续无法进行' -Result $claimApprove
Add-KeyAction -Order 9 -Actor 'it' -Action 'claim' -Method 'POST' `
    -Path (Get-ActionPath $ticketApprove 'claim') -TicketNo $ticketApprove -Result $claimApprove -Step 'd2.claim.approve'
if ($claimApprove.Status -ne 200) { throw "批准路径工单领取失败（status=$($claimApprove.Status)）" }
$vApproveAfterClaim = [long](Get-Data $claimApprove).version

$reqCancelApprove = Send-Action -Client $employeeClient -Token $employeeToken -TicketNo $ticketApprove `
    -Action 'request-cancel' -Body @{ version = $vApproveAfterClaim; reason = '重复提交了，需要撤销。' } `
    -Step 'd3.requestCancel' -Actor 'employee' -Note '员工在处理中发起撤销请求'
Add-Assertion -Name 'd3.requestCancel.200' -Condition ($reqCancelApprove.Status -eq 200) -Expected '200' `
    -Actual "status=$($reqCancelApprove.Status) code=$(Get-Code $reqCancelApprove)" `
    -Why '请求先写入工单，随后才有可批准的待决请求' -Result $reqCancelApprove
Add-KeyAction -Order 10 -Actor 'employee' -Action 'request-cancel' -Method 'POST' `
    -Path (Get-ActionPath $ticketApprove 'request-cancel') -TicketNo $ticketApprove -Result $reqCancelApprove -Step 'd3.requestCancel'
$vApproveAfterReqCancel = [long](Get-Data $reqCancelApprove).version

$approveCancel = Send-Action -Client $itClient -Token $itToken -TicketNo $ticketApprove -Action 'approve-cancel' `
    -Body @{ version = $vApproveAfterReqCancel } -Step 'd4.approveCancel' -Actor 'it' `
    -Note 'IT 批准撤销请求：应给提交人发 CANCEL_APPROVED'
Add-Assertion -Name 'd4.approveCancel.200' -Condition ($approveCancel.Status -eq 200) -Expected '200' `
    -Actual "status=$($approveCancel.Status) code=$(Get-Code $approveCancel) body=[$(Get-SafeBody $approveCancel)]" `
    -Why '这是第 5 类发布事件，进入终态 CANCELED' -Result $approveCancel
Add-KeyAction -Order 11 -Actor 'it' -Action 'approve-cancel' -Method 'POST' `
    -Path (Get-ActionPath $ticketApprove 'approve-cancel') -TicketNo $ticketApprove -Result $approveCancel -Step 'd4.approveCancel'

$empListAfterApprove = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/notifications?page=1&size=100' `
    -Token $employeeToken -Step 'd4.employee.list' -Actor 'employee' -Note '员工列表：应新增 CANCEL_APPROVED'
$apprItems = Select-Notification $empListAfterApprove -TicketNo $ticketApprove -Category 'CANCEL_APPROVED'
Add-Assertion -Name 'd4.employee.approvedFound' `
    -Condition (($apprItems.Count -eq 1) -and ($apprItems[0].ticketNo -eq $ticketApprove) -and
    ($apprItems[0].category -eq 'CANCEL_APPROVED')) `
    -Expected "1 条：category=CANCEL_APPROVED ticketNo=$ticketApprove" `
    -Actual "count=$($apprItems.Count) category=$($apprItems[0].category) ticketNo=$($apprItems[0].ticketNo)" `
    -Why 'approve-cancel 只通知提交人' -Result $empListAfterApprove

# unreadOnly=true 只返回未读（所有条目 readAt 为空），且条数 == unread-count
$empUnreadOnly = Send-Req -Client $employeeClient -Method Get `
    -Path '/fd/v1/notifications?page=1&size=100&unreadOnly=true' -Token $employeeToken `
    -Step 'd5.employee.unreadOnly' -Actor 'employee' -Note 'unreadOnly=true 只看未读'
$unreadOnlyItems = @(Get-NotificationItems $empUnreadOnly)
$unreadOnlyAllUnread = $true
foreach ($it in $unreadOnlyItems) { if ($null -ne $it.readAt) { $unreadOnlyAllUnread = $false } }
Add-Assertion -Name 'd5.unreadOnly.filter' `
    -Condition (($empUnreadOnly.Status -eq 200) -and $unreadOnlyAllUnread -and ($unreadOnlyItems.Count -ge 1)) `
    -Expected '200 且返回的每条 readAt 都为空（未读过滤生效）' `
    -Actual "status=$($empUnreadOnly.Status) count=$($unreadOnlyItems.Count) allUnread=$unreadOnlyAllUnread" `
    -Why 'unreadOnly 是列表契约的一部分；若混入已读条目，界面「只看未读」会给出错误结果' -Result $empUnreadOnly

# ── 6. r 组：反向断言 —— claim 这类本批不发布的动作不产生通知 ────────────────
# 在主链已累计了未读之后做，delta=0 才有区分度（不是"本来就是 0"）。
$createReverse = Send-CreateTicket -Client $employeeClient -Token $employeeToken `
    -Title "$ticketTitlePrefix-反向断言" -Description '站内信验收：claim 不发通知。' `
    -CategoryId $categoryId -Step 'r1.create.reverse' -Actor 'employee' -Note '创建反向断言载体工单'
Add-Assertion -Name 'r1.create.reverse.201' -Condition ($createReverse.Status -eq 201) -Expected '201' `
    -Actual "status=$($createReverse.Status) code=$(Get-Code $createReverse)" `
    -Why '反向断言需要一个可领取的待受理工单' -Result $createReverse
if ($createReverse.Status -ne 201) { throw "反向断言工单创建失败（status=$($createReverse.Status)）" }
$ticketReverse = (Get-Data $createReverse).ticketNo

$itUnreadBeforeClaim = Send-Req -Client $itClient -Method Get -Path '/fd/v1/notifications/unread-count' `
    -Token $itToken -Step 'r2.it.unreadBeforeClaim' -Actor 'it' -Note 'claim 前 IT 未读数'
$empUnreadBeforeClaim = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/notifications/unread-count' `
    -Token $employeeToken -Step 'r2.employee.unreadBeforeClaim' -Actor 'employee' -Note 'claim 前员工未读数'
$itBefore = Get-UnreadCount $itUnreadBeforeClaim
$empBefore = Get-UnreadCount $empUnreadBeforeClaim

$claimReverse = Send-Action -Client $itClient -Token $itToken -TicketNo $ticketReverse -Action 'claim' `
    -Body @{ version = 0 } -Step 'r3.claim.reverse' -Actor 'it' -Note 'IT 领取（本批不发布通知的动作）'
Add-Assertion -Name 'r3.claim.reverse.200' -Condition ($claimReverse.Status -eq 200) -Expected '200' `
    -Actual "status=$($claimReverse.Status) code=$(Get-Code $claimReverse)" `
    -Why '只有领取成功，反向断言才有意义（动作确实执行了、但确实没发通知）' -Result $claimReverse
Add-KeyAction -Order 12 -Actor 'it' -Action 'claim' -Method 'POST' `
    -Path (Get-ActionPath $ticketReverse 'claim') -TicketNo $ticketReverse -Result $claimReverse -Step 'r3.claim.reverse'

$itUnreadAfterClaim = Send-Req -Client $itClient -Method Get -Path '/fd/v1/notifications/unread-count' `
    -Token $itToken -Step 'r4.it.unreadAfterClaim' -Actor 'it' -Note 'claim 后 IT 未读数'
$empUnreadAfterClaim = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/notifications/unread-count' `
    -Token $employeeToken -Step 'r4.employee.unreadAfterClaim' -Actor 'employee' -Note 'claim 后员工未读数'
$itAfter = Get-UnreadCount $itUnreadAfterClaim
$empAfter = Get-UnreadCount $empUnreadAfterClaim
Add-Assertion -Name 'r4.claim.noNotification' `
    -Condition (($claimReverse.Status -eq 200) -and ($itAfter -eq $itBefore) -and ($empAfter -eq $empBefore)) `
    -Expected 'IT 与员工未读数都与 claim 前完全相同（delta = 0）' `
    -Actual "itBefore=$itBefore itAfter=$itAfter employeeBefore=$empBefore employeeAfter=$empAfter" `
    -Why 'claim 不在本轮 5 类发布事件里；未读数变化说明后端把不该发的事件也发了' -Result $claimReverse
Assert-Db -Name 'r4.db.claim.noNotificationRow' `
    -Sql "SELECT COUNT(*) FROM notification WHERE ticket_no = '$ticketReverse';" `
    -Expected '0' -Why 'claim 不落任何通知行（库层复核，防止计数差为零但库里有行）' `
    -Check { param($columns) ([int]$columns[0] -eq 0) }

# ── 7. g 组：标记已读（幂等 + 归属隔离） ─────────────────────────────────────
# 选一条脚本自己创建、且属于员工的通知（主链的 SUPPLEMENT_REQUESTED）来标已读——
# 只用自建行，绝不触碰运行前既有通知。

$targetNotificationId = [long]$suppItem.id
$empUnreadBeforeRead = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/notifications/unread-count' `
    -Token $employeeToken -Step 'g1.employee.unreadBeforeRead' -Actor 'employee' -Note '标记已读前的未读数'
$unreadBeforeRead = Get-UnreadCount $empUnreadBeforeRead

$markRead = Send-Req -Client $employeeClient -Method Post -Path "/fd/v1/notifications/$targetNotificationId/read" `
    -Token $employeeToken -Step 'g2.markRead' -Actor 'employee' -Note '员工标记自己的一条通知已读'
Add-Assertion -Name 'g2.markRead.200' -Condition ($markRead.Status -eq 200) -Expected '200' `
    -Actual "status=$($markRead.Status) code=$(Get-Code $markRead) body=[$(Get-SafeBody $markRead)]" `
    -Why '标记已读是收件人本人的写操作；200 才说明收件人归属判定通过' -Result $markRead

$empUnreadAfterRead = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/notifications/unread-count' `
    -Token $employeeToken -Step 'g3.employee.unreadAfterRead' -Actor 'employee' -Note '标记已读后的未读数'
$unreadAfterRead = Get-UnreadCount $empUnreadAfterRead
Add-Assertion -Name 'g3.markRead.decrementByOne' `
    -Condition (($unreadAfterRead -eq $unreadBeforeRead - 1) -and ($unreadAfterRead -ge 0)) `
    -Expected "$($unreadBeforeRead - 1)（比标记前少 1）" `
    -Actual "before=$unreadBeforeRead after=$unreadAfterRead" `
    -Why '标记已读必须恰好减 1 条未读；减多减少都说明状态写错' -Result $empUnreadAfterRead

$empListAfterRead = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/notifications?page=1&size=100' `
    -Token $employeeToken -Step 'g3.employee.listAfterRead' -Actor 'employee' -Note '确认该条 readAt 非空'
$markedItem = @(Get-NotificationItems $empListAfterRead | Where-Object { [long]$_.id -eq $targetNotificationId } | Select-Object -First 1)
$markedReadAt = $null
if ($markedItem.Count -ge 1) { $markedReadAt = $markedItem[0].readAt }
Add-Assertion -Name 'g3.markRead.readAtNotNull' `
    -Condition (($markedItem.Count -eq 1) -and ($null -ne $markedReadAt)) `
    -Expected "该条（id=$targetNotificationId）readAt 非空" `
    -Actual "found=$($markedItem.Count) readAt=$markedReadAt" `
    -Why '已读的判据是 readAt 非空；未读列表与界面勾选都依赖它' -Result $empListAfterRead

$markAgain = Send-Req -Client $employeeClient -Method Post -Path "/fd/v1/notifications/$targetNotificationId/read" `
    -Token $employeeToken -Step 'g4.markRead.again' -Actor 'employee' -Note '重复标记同一条（幂等）'
Add-Assertion -Name 'g4.markRead.idempotent' -Condition ($markAgain.Status -eq 200) -Expected '200（幂等）' `
    -Actual "status=$($markAgain.Status) code=$(Get-Code $markAgain)" `
    -Why '重复标记已读必须仍返回 200；非 200 或 404 会让界面重试逻辑失效' -Result $markAgain

$markByOther = Send-Req -Client $itClient -Method Post -Path "/fd/v1/notifications/$targetNotificationId/read" `
    -Token $itToken -Step 'g5.markRead.byOther' -Actor 'it' -Note 'IT 用自己令牌标记员工的通知'
Add-Assertion -Name 'g5.markRead.byOtherToken.404' `
    -Condition (($markByOther.Status -eq 404) -and ((Get-Code $markByOther) -eq 'NOTIFICATION_NOT_FOUND')) `
    -Expected '404 + NOTIFICATION_NOT_FOUND' `
    -Actual "status=$($markByOther.Status) code=$(Get-Code $markByOther)" `
    -Why '他人标记必须按不存在统一 404，不暴露该通知的存在性与归属' -Result $markByOther

$markUnknown = Send-Req -Client $employeeClient -Method Post -Path "/fd/v1/notifications/$unknownNotificationId/read" `
    -Token $employeeToken -Step 'g6.markRead.unknownId' -Actor 'employee' -Note '标记不存在的 id'
Add-Assertion -Name 'g6.markRead.unknownId.404' `
    -Condition (($markUnknown.Status -eq 404) -and ((Get-Code $markUnknown) -eq 'NOTIFICATION_NOT_FOUND')) `
    -Expected '404 + NOTIFICATION_NOT_FOUND' `
    -Actual "status=$($markUnknown.Status) code=$(Get-Code $markUnknown)" `
    -Why '不存在的 id 与"他人的通知"必须同一种 404，不区别回显' -Result $markUnknown

# ── 8. j 组：数据库直查（通知行与约束） ─────────────────────────────────────
# 在 read-all 之前做，因此 SUPPLEMENT_REQUESTED 行 read_at 非空、RESOLUTION_SUBMITTED 行仍为空。

Assert-Db -Name 'j1.db.notification.categoryConstraint' `
    -Sql ("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = '$dbName' " +
    "AND table_name = 'notification' AND constraint_name = 'ck_notification_category' AND constraint_type = 'CHECK';") `
    -Expected '1（ck_notification_category 存在）' `
    -Why 'category 是固定枚举；约束缺席意味着可以写入契约外的类型' `
    -Check { param($columns) ([int]$columns[0] -eq 1) }

Assert-Db -Name 'j2.db.notification.supplementRowMatches' `
    -Sql ("SELECT category, recipient_id, read_at FROM notification WHERE ticket_no = '$ticketMain' " +
    "AND category = 'SUPPLEMENT_REQUESTED' ORDER BY id DESC LIMIT 1;") `
    -Expected "SUPPLEMENT_REQUESTED / recipient=$employeeUserId / read_at 非空（已在 g 组标记已读）" `
    -Why '库层核对 category 与 recipient_id 与 HTTP 结果一致，且 read_at 已被标记写入' `
    -Check { param($columns)
        ($columns[0] -eq 'SUPPLEMENT_REQUESTED') -and ([int]$columns[1] -eq $employeeUserId) -and ($columns[2] -ne 'NULL') }

Assert-Db -Name 'j2.db.notification.resolutionRowUnread' `
    -Sql ("SELECT category, recipient_id, read_at FROM notification WHERE ticket_no = '$ticketMain' " +
    "AND category = 'RESOLUTION_SUBMITTED' ORDER BY id DESC LIMIT 1;") `
    -Expected "RESOLUTION_SUBMITTED / recipient=$employeeUserId / read_at 为空（未读）" `
    -Why '同一条 ticket_no 上两条不同 category 的行，read_at 必须各自独立；也验证 recipient 一致' `
    -Check { param($columns)
        ($columns[0] -eq 'RESOLUTION_SUBMITTED') -and ([int]$columns[1] -eq $employeeUserId) -and ($columns[2] -eq 'NULL') }

Assert-Db -Name 'j3.db.notification.cancelRequestedRecipientIsAssignee' `
    -Sql ("SELECT category, recipient_id FROM notification WHERE ticket_no = '$ticketMain' " +
    "AND category = 'CANCEL_REQUESTED' ORDER BY id DESC LIMIT 1;") `
    -Expected "CANCEL_REQUESTED / recipient=$itUserId（当前负责人）" `
    -Why 'request-cancel 的收件人是当前负责人；这条 SQL 直接证明库层写的收件人是 IT 而不是提交人' `
    -Check { param($columns) ($columns[0] -eq 'CANCEL_REQUESTED') -and ([int]$columns[1] -eq $itUserId) }

# ── 9. h 组：全部标记已读 ───────────────────────────────────────────────────
# 收尾前置：此刻员工的全部通知都是脚本自建的，read-all 只作用于这些行。

$readAll = Send-Req -Client $employeeClient -Method Post -Path '/fd/v1/notifications/actions/read-all' `
    -Token $employeeToken -Step 'h1.readAll' -Actor 'employee' -Note '员工全部标记已读'
Add-Assertion -Name 'h1.readAll.200' -Condition ($readAll.Status -eq 200) -Expected '200' `
    -Actual "status=$($readAll.Status) code=$(Get-Code $readAll) body=[$(Get-SafeBody $readAll)]" `
    -Why '全部已读是收件人本人的清空入口' -Result $readAll

$empUnreadAfterAll = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/notifications/unread-count' `
    -Token $employeeToken -Step 'h2.employee.unreadAfterAll' -Actor 'employee' -Note '全部已读后未读数'
$unreadAfterAll = Get-UnreadCount $empUnreadAfterAll
Add-Assertion -Name 'h2.readAll.unreadZero' -Condition ($unreadAfterAll -eq 0) -Expected '0' `
    -Actual "unreadCount=$unreadAfterAll" `
    -Why '全部已读后未读数必须归零；非零说明还有未读没被覆盖' -Result $empUnreadAfterAll

$readAllAgain = Send-Req -Client $employeeClient -Method Post -Path '/fd/v1/notifications/actions/read-all' `
    -Token $employeeToken -Step 'h3.readAll.again' -Actor 'employee' -Note '无未读时再次全部已读（幂等）'
Add-Assertion -Name 'h3.readAll.idempotent' -Condition ($readAllAgain.Status -eq 200) -Expected '200（幂等）' `
    -Actual "status=$($readAllAgain.Status) code=$(Get-Code $readAllAgain)" `
    -Why '对没有未读的调用方同样幂等成功；非 200 会让"清空按钮"在空箱时报错' -Result $readAllAgain

$script:readSemanticsInfo = [ordered]@{
    targetNotificationId        = $targetNotificationId
    unreadBeforeRead            = $unreadBeforeRead
    unreadAfterRead             = $unreadAfterRead
    unreadAfterReadAll          = $unreadAfterAll
    unknownIdProbe              = $unknownNotificationId
    idempotentMarkReadStatus    = $markAgain.Status
    idempotentReadAllStatus     = $readAllAgain.Status
}

# ── 10. i 组：参数校验（400 / VALIDATION_FAILED） ───────────────────────────

$paramPageZero = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/notifications?page=0' `
    -Token $employeeToken -Step 'i1.pageZero' -Actor 'employee' -Note 'page=0：分页下限'
Add-Assertion -Name 'i1.pageZero.400' `
    -Condition (($paramPageZero.Status -eq 400) -and ((Get-Code $paramPageZero) -eq 'VALIDATION_FAILED')) `
    -Expected '400 + VALIDATION_FAILED' -Actual "status=$($paramPageZero.Status) code=$(Get-Code $paramPageZero)" `
    -Why 'page 从 1 开始；放行 0 会让分页偏移出现负数' -Result $paramPageZero

$paramSizeOver = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/notifications?page=1&size=101' `
    -Token $employeeToken -Step 'i2.sizeOverflow' -Actor 'employee' -Note 'size=101：超过上限 100'
Add-Assertion -Name 'i2.sizeOverflow.400' `
    -Condition (($paramSizeOver.Status -eq 400) -and ((Get-Code $paramSizeOver) -eq 'VALIDATION_FAILED')) `
    -Expected '400 + VALIDATION_FAILED' -Actual "status=$($paramSizeOver.Status) code=$(Get-Code $paramSizeOver)" `
    -Why 'size 上限 100 照工单列表口径；放行会让单次拉取过大' -Result $paramSizeOver

$paramOrderBy = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/notifications?page=1&size=20&orderBy=created_at' `
    -Token $employeeToken -Step 'i3.orderBy' -Actor 'employee' -Note 'orderBy=created_at：不接受客户端自选排序'
Add-Assertion -Name 'i3.orderBy.400' `
    -Condition (($paramOrderBy.Status -eq 400) -and ((Get-Code $paramOrderBy) -eq 'VALIDATION_FAILED')) `
    -Expected '400 + VALIDATION_FAILED' -Actual "status=$($paramOrderBy.Status) code=$(Get-Code $paramOrderBy)" `
    -Why '排序固定为 id 倒序；接受 orderBy 会让排序到处漂移，破坏"新通知在上"的稳定预期' -Result $paramOrderBy

# ── 11. k 组：清理与指纹 ─────────────────────────────────────────────────────

$script:cleanupInfo['startedAt'] = (Get-Date).ToString('s')
# 顺序：notification（recipient_id / ticket_id 都是限制删除外键）→ ticket_record → ticket_participant → ticket。
# 本脚本不创建临时用户/角色，因此不删 iam_user / iam_role 行。
$cleanupSql = @(
    "DELETE n FROM notification n JOIN ticket t ON t.id = n.ticket_id WHERE t.title LIKE '$ticketTitlePrefix-%';",
    "DELETE r FROM ticket_record r JOIN ticket t ON t.id = r.ticket_id WHERE t.title LIKE '$ticketTitlePrefix-%';",
    "DELETE p FROM ticket_participant p JOIN ticket t ON t.id = p.ticket_id WHERE t.title LIKE '$ticketTitlePrefix-%';",
    "DELETE FROM ticket WHERE title LIKE '$ticketTitlePrefix-%';"
)
$cleanupResult = Invoke-MySql -Sql ($cleanupSql -join "`n")
Add-Assertion -Name 'k1.cleanup.sqlApplied' -Condition ($cleanupResult.exitCode -eq 0) `
    -Expected '退出码 0' -Actual "exitCode=$($cleanupResult.exitCode) stderr=[$(Get-BriefText $cleanupResult.stderrText 160)]" `
    -Why '本脚本创建的行必须由自己删干净（指纹对照的前提）' -Result $null

Assert-Db -Name 'k2.cleanup.noLeftoverTickets' `
    -Sql "SELECT COUNT(*) FROM ticket WHERE title LIKE '$ticketTitlePrefix-%';" `
    -Expected '0' -Why '自建工单必须全部删除' `
    -Check { param($columns) ([int]$columns[0] -eq 0) }
Assert-Db -Name 'k3.cleanup.noLeftoverNotifications' `
    -Sql "SELECT COUNT(*) FROM notification WHERE ticket_no LIKE '$ticketTitlePrefix-%';" `
    -Expected '0' -Why '自建工单上的通知必须全部删除（先删通知再删工单，外键顺序才对）' `
    -Check { param($columns) ([int]$columns[0] -eq 0) }

$demoAfter = Get-DemoFingerprint
$fingerprintEqual = $false
if (($null -ne $demoBefore) -and ($null -ne $demoAfter)) {
    $fingerprintEqual = (($demoBefore.notifications -eq $demoAfter.notifications) -and
        ($demoBefore.tickets -eq $demoAfter.tickets) -and
        ($demoBefore.records -eq $demoAfter.records) -and
        ($demoBefore.participants -eq $demoAfter.participants) -and
        ($demoBefore.users -eq $demoAfter.users) -and
        ($demoBefore.userRoles -eq $demoAfter.userRoles) -and
        ($demoBefore.ticketRows -eq $demoAfter.ticketRows) -and
        ($demoBefore.notificationRows -eq $demoAfter.notificationRows))
}
Add-Assertion -Name 'k4.fingerprint.unchanged' -Condition $fingerprintEqual `
    -Expected '运行前与清理后逐项相同（6 项计数 + 通知/工单逐行指纹）' `
    -Actual ("before(notifications={0} tickets={1}) after(notifications={2} tickets={3}) notificationsEqual={4} ticketRowsEqual={5}" -f `
        $demoBefore.notifications, $demoBefore.tickets, $demoAfter.notifications, $demoAfter.tickets, `
        ($demoBefore.notificationRows -eq $demoAfter.notificationRows), ($demoBefore.ticketRows -eq $demoAfter.ticketRows)) `
    -Why ('本脚本只允许动自己创建的行；指纹不同意味着动了运行前就存在的行——' +
    'read-all 若命中了运行前就有的未读通知，也会在这里显形') -Result $null

$script:cleanupInfo['sql'] = @($cleanupSql)
$script:cleanupInfo['finishedAt'] = (Get-Date).ToString('s')

$script:ticketsInfo = [ordered]@{
    main      = $ticketMain
    approve   = $ticketApprove
    reverse   = $ticketReverse
}

# ── 12. 证据与汇总 ──────────────────────────────────────────────────────────

$finishedAt = Get-Date
$failed = @($script:results | Where-Object { -not $_.passed })
$failedCount = $failed.Count
$totalCount = $script:results.Count
$passedCount = $totalCount - $failedCount
if ($failedCount -gt 0) { $script:quitCode = 1 }

$relativeScriptPath = 'scripts/notification-channel-acceptance.ps1'
$relativeOutPath = 'docs/acceptance/2026-10-14-notification-channel.json'
$evidencePathForJson = $relativeOutPath
try {
    $repoRootFull = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
    $outFull = [System.IO.Path]::GetFullPath($OutFile)
    if ($outFull.StartsWith($repoRootFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        $evidencePathForJson = ($outFull.Substring($repoRootFull.Length).TrimStart('\', '/') -replace '\\', '/')
    }
} catch { $evidencePathForJson = $relativeOutPath }
$invocation = 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\notification-channel-acceptance.ps1 -BaseUrl ' + $baseUrl
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
        endpointsUnderTest = @(
            'GET  /fd/v1/notifications（page/size/unreadOnly，id 倒序）',
            'GET  /fd/v1/notifications/unread-count',
            'POST /fd/v1/notifications/{notificationId}/read（幂等；他人或不存在统一 404）',
            'POST /fd/v1/notifications/actions/read-all（幂等）'
        )
        publishEvents = @(
            'request-supplement → 提交人 SUPPLEMENT_REQUESTED',
            'submit-resolution  → 提交人 RESOLUTION_SUBMITTED',
            'request-cancel     → 当前负责人 CANCEL_REQUESTED',
            'approve-cancel     → 提交人 CANCEL_APPROVED',
            'reject-cancel      → 提交人 CANCEL_REJECTED'
        )
        nonPublishingActionsProbed = @('claim（+ withdraw-supplement-request 作为回退到处理中的路径）')
        credentialsPrinted    = $false
        credentialsInEvidence = $false
    }
    $evidence = [ordered]@{
        _note = ('本文件由 scripts/notification-channel-acceptance.ps1 写出（无 BOM 的 UTF-8）。' +
        '验收范围：完整版「站内信（应用内通知通道）」（docs/api-design.md 7.7）——四个本人范围端点的' +
        '未认证 401、发布语义（5 类动作与工单同事务）、跨用户不可见、未读计数一致性、标记已读的幂等与' +
        '归属隔离 404、全部已读与参数校验 400，以及「claim 等本批不发布的动作不发通知」的反向断言；' +
        '库层核对通知行 category / recipient_id / read_at 与 ck_notification_category 约束。' +
        '只记录状态码、业务码、traceId、断言结论与 SQL；不含口令、密钥与 accessToken，也不含机器绝对路径。')
        stage              = '完整版：站内信（应用内通知通道）真实栈验收'
        slice              = 'notification-channel'
        date               = $startedAt.ToString('yyyy-MM-dd')
        result             = $resultValue
        invocation         = $invocation
        script             = $scriptEntry
        target             = $targetEntry
        preflight          = $script:preflightInfo
        notificationFacts  = $script:notificationFactsInfo
        readSemantics      = $script:readSemanticsInfo
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
        stage      = '完整版：站内信（应用内通知通道）真实栈验收'
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
Write-Host ("阶段：站内信（应用内通知通道）；目标后端：{0}" -f $baseUrl)
Write-Host ("脚本：{0}（{1} 行）；断言 {2} 项，通过 {3}，失败 {4}" -f `
        $relativeScriptPath, $scriptLineCount, $totalCount, $passedCount, $failedCount)
Write-Host '关键动作（状态码 / X-Trace-Id）：'
foreach ($step in $script:keyActions) {
    Write-Host ("  {0}. {1,-16} {2,-26} {3,-24} => {4} traceId={5}" -f `
            $step.order, $step.actor, $step.action, $step.ticketNo, $step.httpStatus, $step.traceId)
}
Write-Host '数据库直查：'
foreach ($check in $script:dbChecks) {
    $mark = 'FAIL'
    if ($check.passed) { $mark = 'PASS' }
    Write-Host ("  [{0}] {1} :: {2}" -f $mark, $check.name, $check.raw)
}
if ($null -ne $demoBefore) {
    Write-Host ("演示库计数：运行前 通知={0}/工单={1}/记录={2}/参与={3}/用户={4}/用户角色={5}" -f `
            $demoBefore.notifications, $demoBefore.tickets, $demoBefore.records, `
            $demoBefore.participants, $demoBefore.users, $demoBefore.userRoles)
}
if ($null -ne $demoAfter) {
    Write-Host ("            运行后 通知={0}/工单={1}/记录={2}/参与={3}/用户={4}/用户角色={5}" -f `
            $demoAfter.notifications, $demoAfter.tickets, $demoAfter.records, `
            $demoAfter.participants, $demoAfter.users, $demoAfter.userRoles)
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
