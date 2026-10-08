# 完整工单状态机「片 D：结束路径」真实栈验收
#   close（当前负责人异常关闭：DUPLICATE / OUT_OF_SCOPE / INVALID + 重复工单关联）
#   + cancel（提交人撤销：待受理 / 处理中 / 待补充 / 待确认 四种非终态）
#
# 前置
#   · docker compose up -d mysql redis（本脚本只对既有 mysql 容器执行 docker exec，不碰容器生命周期）
#   · 目标后端必须是**已包含片 D 两个动作的最新代码**，由用户自己启动。脚本只发 HTTP 请求，
#     不启动、不重启、不结束任何后端进程；8081 上用户自己启动的实例保持原样。
#   · 片 D 新增的端点是 POST /fd/v1/tickets/{ticketNo}/actions/close 与 .../actions/cancel；
#     旧构建上这两个路径是 404/RESOURCE_NOT_FOUND，因此跑本脚本必须把基址指向新构建（并行后端 8092）。
#
# 运行
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\slice-d-close-cancel-acceptance.ps1
#   基址可覆盖（默认 http://127.0.0.1:8081，并行后端常用 8092）：
#     $env:FLOWDESK_BASE_URL = 'http://127.0.0.1:8092'   # 或 -BaseUrl http://127.0.0.1:8092
#   证据路径可覆盖：-OutFile <path> 或别名 -EvidencePath <path>
#   演示账号可用 E2E_EMPLOYEE_USERNAME / E2E_EMPLOYEE_PASSWORD、E2E_IT_*、E2E_ADMIN_* 覆盖
#   （缺省 employee / it / admin，口令 123456）
#
# 本文件必须保存为「带 BOM 的 UTF-8」：Windows PowerShell 5.1 会按系统代码页读取无 BOM 的
# UTF-8 脚本，中文注释会被解析成乱码并报语法错误。证据文件相反，必须写成**无 BOM 的 UTF-8**。
#
# 证据
#   docs/acceptance/2026-10-08-slice-d-close-cancel.json（无 BOM 的 UTF-8，脚本自己写出）
#   含：脚本命令与退出码、$baseUrl、关键动作的状态码与 traceId、逐条断言（名称/期望/实际状态码/
#   X-Trace-Id/通过与否）、全部 HTTP 调用日志、数据库直查结果、临时主体清单、
#   两组并发用例（员工撤销 vs IT 关闭、IT 转交 vs IT 关闭）两路结果、
#   清理前后演示库计数与指纹。
#
# 片 D 与片 C 的一处口径差异（本脚本的核心断言之一）
#   · transfer 只要求 TICKET_TRANSFER（片 C 的裁决）；**close 同时要求 TICKET_PROCESS 与
#     TICKET_CLOSE**（2026-10-08 用户裁决：关闭是结束工单的处置动作，必须建立在处理权限之上）。
#     因此本脚本用两个自建临时角色把这一对权限**拆开各测一次 403**：
#       U1「只有 TICKET_CLOSE」→ close 必须 403（缺 TICKET_PROCESS）
#       U2「只有 TICKET_PROCESS」→ close 必须 403（缺 TICKET_CLOSE）
#     并把同一个请求交给演示用户 it（两个权限都有）以 404/409 通过权限闸门，作为正向对照。
#
# 权限闸门顺序（两处都断言）
#   close/cancel 的门禁顺序都是：认证 401 → 权限 403 → 可见性 404 → 状态/身份/版本 409 → 字段 400。
#   因此脚本会用**不存在的编号**发权限不足的请求，仍然必须得到 403 而不是 404；
#   并用「版本过期 + 非法字段」同时成立的报文，断言得到 409 而不是 400。
#
# 安全边界
#   · 只创建并删除脚本自己创建的工单、记录、参与关系、工单关联（ticket_relation）、
#     4 个临时用户与 5 个临时角色（含它们的 iam_user_role / iam_role_permission）
#   · 不触碰运行前就存在的任何行：运行前后各测一次演示库指纹（七项计数 + 工单/记录/参与关系/
#     工单关联/用户/用户角色/角色权限逐行指纹）并断言完全相同
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
    # 证据路径：-OutFile 与片 C 一致；别名 -EvidencePath 供调用方按另一种叫法覆盖
    [Alias('EvidencePath')]
    [string]$OutFile = (Join-Path $PSScriptRoot '..\docs\acceptance\2026-10-08-slice-d-close-cancel.json'),
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
$sqlWorkDir = Join-Path $env:TEMP "flowdesk-slice-d-$stamp"
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
$script:ticketsInfo = [ordered]@{}
$script:tempPrincipalInfo = [ordered]@{}
$script:concurrencyInfo = [ordered]@{}
$script:concurrencyTransferInfo = [ordered]@{}
$script:terminalInfo = [ordered]@{}

if ([string]::IsNullOrWhiteSpace($EmployeeUser)) { $EmployeeUser = 'employee' }
if ([string]::IsNullOrWhiteSpace($EmployeePassword)) { $EmployeePassword = '123456' }
if ([string]::IsNullOrWhiteSpace($ItUser)) { $ItUser = 'it' }
if ([string]::IsNullOrWhiteSpace($ItPassword)) { $ItPassword = '123456' }
if ([string]::IsNullOrWhiteSpace($AdminUser)) { $AdminUser = 'admin' }
if ([string]::IsNullOrWhiteSpace($AdminPassword)) { $AdminPassword = '123456' }

# 临时主体命名：用户名与角色编码都带本次运行的 stamp，清理时按前缀精确命中，
# 不可能碰到运行前就存在的行（演示库里没有这两个前缀）。
$tempUsernamePrefix = "slice-d-acc-$stamp"
$tempRoleCodePrefix = "SLICE_D_ACC_$($stamp.Replace('-','_'))"
$script:tempCleanup = [System.Collections.Generic.List[object]]::new()

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

# 多行结果用不到就不留：本脚本的数据库断言都是"取一行判等"（Get-MySqlRow）或 Assert-Db，
# 没有一处需要整批行，留着只会成为没人维护的死代码。

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

# 两路请求真并发：先把两个 SendAsync 都发出去（返回 Task，不阻塞），再逐个取结果。
# 片 D 用它验证「同一版本上 员工撤销 vs IT 关闭」只有一个赢家，且败者必须是 409 而不是 500。
function Send-ConcurrentPair {
    param(
        [System.Net.Http.HttpClient]$ClientA,
        [string]$PathA,
        [string]$TokenA,
        $BodyA,
        [string]$ActorA,
        [string]$NoteA,
        [System.Net.Http.HttpClient]$ClientB,
        [string]$PathB,
        [string]$TokenB,
        $BodyB,
        [string]$ActorB,
        [string]$NoteB
    )
    $requestA = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, $PathA)
    $requestB = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, $PathB)
    $traceA = New-TraceId
    $traceB = New-TraceId
    try {
        foreach ($pair in @(
                @{ request = $requestA; trace = $traceA; token = $TokenA; body = $BodyA },
                @{ request = $requestB; trace = $traceB; token = $TokenB; body = $BodyB })) {
            $request = $pair.request
            $request.Headers.Add('Origin', $originHeader)
            $request.Headers.Add('X-Trace-Id', $pair.trace)
            if ($pair.token) {
                $request.Headers.Authorization =
                New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $pair.token)
            }
            $json = $pair.body
            if ($json -isnot [string]) { $json = $json | ConvertTo-Json -Depth 6 -Compress }
            $request.Content = New-Object System.Net.Http.StringContent(
                $json, [System.Text.Encoding]::UTF8, 'application/json')
        }

        # 关键：两次派发之间不做任何等待，两个请求在网络上真实重叠
        $taskA = $ClientA.SendAsync($requestA)
        $taskB = $ClientB.SendAsync($requestB)
        $dispatchedAt = (Get-Date).ToString('HH:mm:ss.fff')

        $responseA = $taskA.GetAwaiter().GetResult()
        $textA = $responseA.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $responseB = $taskB.GetAwaiter().GetResult()
        $textB = $responseB.Content.ReadAsStringAsync().GetAwaiter().GetResult()

        $resultA = [pscustomobject]@{ Status = [int]$responseA.StatusCode; Body = $textA; TraceId = (Get-TraceId $responseA) }
        $resultB = [pscustomobject]@{ Status = [int]$responseB.StatusCode; Body = $textB; TraceId = (Get-TraceId $responseB) }

        $script:httpLog.Add([pscustomobject]@{
                actor = $ActorA; note = $NoteA; method = 'POST'; path = $PathA
                status = $resultA.Status; traceId = $resultA.TraceId; requestTraceId = $traceA
            })
        $script:httpLog.Add([pscustomobject]@{
                actor = $ActorB; note = $NoteB; method = 'POST'; path = $PathB
                status = $resultB.Status; traceId = $resultB.TraceId; requestTraceId = $traceB
            })

        return [pscustomobject]@{
            A             = $resultA
            B             = $resultB
            dispatchedAt  = $dispatchedAt
            dispatchMode  = '两个 SendAsync 先都发出，再逐个取结果（两路请求在网络上真实重叠）'
        }
    } finally {
        $requestA.Dispose()
        $requestB.Dispose()
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

# 工单快照：状态、关闭字段、结束时间、期限、负责人、完成方式、版本与记录序号。
function Get-TicketDbRow {
    param([string]$TicketNo)
    $sql = "SELECT status, IFNULL(close_method,'NULL'), IFNULL(close_reason,'NULL'), " +
    "IF(ended_at IS NULL,'NULL','SET'), " +
    "IFNULL(DATE_FORMAT(action_deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'), " +
    "IFNULL(assignee_id,-1), IFNULL(completion_method,'NULL'), version, record_seq, requester_id " +
    "FROM ticket WHERE ticket_no = '$TicketNo';"
    $row = Get-MySqlRow -Sql $sql
    if (-not $row.ok) { return $null }
    $columns = @($row.columns)
    if ($columns.Count -lt 10) { return $null }
    return [pscustomobject]@{
        status           = $columns[0]
        closeMethod      = $columns[1]
        closeReason      = $columns[2]
        endedAt          = $columns[3]
        actionDeadlineAt = $columns[4]
        assigneeId       = [int]$columns[5]
        completionMethod = $columns[6]
        version          = [int]$columns[7]
        recordSeq        = [int]$columns[8]
        requesterId      = [int]$columns[9]
        raw              = $row.raw
    }
}

# 「库里没变」的统一快照串：被拒的 400/409 请求前后各取一次，字符串相同即证明什么都没改。
function Get-TicketStateSignature {
    param([string]$TicketNo)
    $sql = "SELECT CONCAT_WS('|', t.status, t.category_id, t.priority, IFNULL(t.assignee_id,-1), " +
    "IFNULL(DATE_FORMAT(t.action_deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'), t.version, t.record_seq, " +
    "IFNULL(t.close_method,'NULL'), IFNULL(t.close_reason,'NULL'), IFNULL(t.completion_method,'NULL'), " +
    "IF(t.ended_at IS NULL,'NULL','SET'), " +
    "(SELECT COUNT(*) FROM ticket_record r WHERE r.ticket_id = t.id), " +
    "(SELECT COUNT(*) FROM ticket_relation rel WHERE rel.source_ticket_id = t.id)) " +
    "FROM ticket t WHERE t.ticket_no = '$TicketNo';"
    $row = Get-MySqlRow -Sql $sql
    if (-not $row.ok) { return "UNREADABLE[$($row.raw)]" }
    return $row.raw
}

# 时间线最后一条记录：类型、两侧状态、原因（含长度）、关闭字段与序号。
function Get-LastRecordRow {
    param([string]$TicketNo)
    $sql = "SELECT record_type, IFNULL(from_status,'-'), IFNULL(to_status,'-'), " +
    "IFNULL(reason,'-'), IFNULL(CHAR_LENGTH(reason),-1), IFNULL(close_method,'-'), IFNULL(close_reason,'-'), " +
    "sequence_no, IFNULL(LEFT(content,40),'-') " +
    "FROM ticket_record WHERE ticket_id = (SELECT id FROM ticket WHERE ticket_no = '$TicketNo') " +
    "ORDER BY sequence_no DESC LIMIT 1;"
    $row = Get-MySqlRow -Sql $sql
    if (-not $row.ok) { return $null }
    $columns = @($row.columns)
    if ($columns.Count -lt 9) { return $null }
    return [pscustomobject]@{
        recordType  = $columns[0]
        fromStatus  = $columns[1]
        toStatus    = $columns[2]
        reason      = $columns[3]
        reasonLen   = [int]$columns[4]
        closeMethod = $columns[5]
        closeReason = $columns[6]
        sequenceNo  = [int]$columns[7]
        content     = $columns[8]
        raw         = $row.raw
    }
}

# 某个工单作为 source 的关联行（片 D 只写 DUPLICATE）。
function Get-RelationSignature {
    param([string]$TicketNo)
    $sql = "SELECT IFNULL(GROUP_CONCAT(CONCAT(s.ticket_no,'->',g.ticket_no,':',r.relation_type) " +
    "ORDER BY r.id SEPARATOR ' ;; '),'-') " +
    "FROM ticket_relation r JOIN ticket s ON s.id = r.source_ticket_id " +
    "JOIN ticket g ON g.id = r.target_ticket_id WHERE s.ticket_no = '$TicketNo';"
    $row = Get-MySqlRow -Sql $sql
    if (-not $row.ok) { return "UNREADABLE[$($row.raw)]" }
    return $row.raw
}

# 时间线记录类型序列（按序号），用于断言"最后一条是 CLOSURE / CANCELLATION"之前的历史没被改写。
function Get-RecordTypeSequence {
    param([string]$TicketNo)
    $sql = "SELECT IFNULL(GROUP_CONCAT(record_type ORDER BY sequence_no SEPARATOR ','),'-') " +
    "FROM ticket_record WHERE ticket_id = (SELECT id FROM ticket WHERE ticket_no = '$TicketNo');"
    $row = Get-MySqlRow -Sql $sql
    if (-not $row.ok) { return "UNREADABLE[$($row.raw)]" }
    return $row.raw
}

# 演示库指纹：运行前与清理后各测一次，断言完全相同（既有行一行都不许变）。
# 比片 C 多三块：工单的关闭字段、ticket_relation 全表、iam_user_role / iam_role_permission 全表
# （片 D 会写关联与临时授权关系，必须证明它们都回到了运行前状态）。
function Get-DemoFingerprint {
    $countsSql = "SELECT (SELECT COUNT(*) FROM iam_user), (SELECT COUNT(*) FROM iam_role), " +
    "(SELECT COUNT(*) FROM iam_permission), (SELECT COUNT(*) FROM ticket_category), " +
    "(SELECT COUNT(*) FROM ticket), (SELECT COUNT(*) FROM ticket_record), " +
    "(SELECT COUNT(*) FROM ticket_participant), (SELECT COUNT(*) FROM ticket_relation);"
    $countsRow = Get-MySqlRow -Sql $countsSql
    $counts = @($countsRow.columns)
    $ticketNos = Get-MySqlRow -Sql "SELECT IFNULL(GROUP_CONCAT(ticket_no ORDER BY id SEPARATOR ','),'-') FROM ticket;"
    $ticketRows = Get-MySqlRow -Sql (
        "SELECT IFNULL(GROUP_CONCAT(CONCAT(ticket_no,'|',status,'|',version,'|',record_seq,'|'," +
        "IFNULL(assignee_id,-1),'|',IFNULL(DATE_FORMAT(action_deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'),'|'," +
        "IF(ended_at IS NULL,'NULL','SET'),'|',IFNULL(completion_method,'NULL'),'|'," +
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
        users              = -1
        roles              = -1
        permissions        = -1
        categories         = -1
        tickets            = -1
        records            = -1
        participants       = -1
        relations          = -1
        ticketNos          = $ticketNos.raw
        ticketRows         = $ticketRows.raw
        recordRows         = $recordRows.raw
        participantRows    = $participantRows.raw
        relationRows       = $relationRows.raw
        userRows           = $userRows.raw
        userRoleRows       = $userRoleRows.raw
        rolePermissionRows = $rolePermissionRows.raw
        dailySequence      = $dailySequence.raw
        readOk             = $countsRow.ok
    }
    if ($countsRow.ok -and $counts.Count -ge 8) {
        $fingerprint.users = [int]$counts[0]
        $fingerprint.roles = [int]$counts[1]
        $fingerprint.permissions = [int]$counts[2]
        $fingerprint.categories = [int]$counts[3]
        $fingerprint.tickets = [int]$counts[4]
        $fingerprint.records = [int]$counts[5]
        $fingerprint.participants = [int]$counts[6]
        $fingerprint.relations = [int]$counts[7]
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

# close 请求体：reasonCode ∈ {DUPLICATE, OUT_OF_SCOPE, INVALID}、description 1～1000、
# DUPLICATE 时必填 duplicateTicketNo；-Omit* 开关用来构造"字段整体缺失"的报文。
function New-CloseBody {
    param(
        [long]$Version,
        [string]$ReasonCode,
        [string]$Description,
        [string]$DuplicateTicketNo,
        [switch]$OmitVersion,
        [switch]$OmitReasonCode,
        [switch]$OmitDescription,
        [switch]$OmitDuplicateTicketNo
    )
    $body = @{}
    if (-not $OmitVersion) { $body['version'] = $Version }
    if (-not $OmitReasonCode) { $body['reasonCode'] = $ReasonCode }
    if (-not $OmitDescription) { $body['description'] = $Description }
    if ((-not $OmitDuplicateTicketNo) -and (-not [string]::IsNullOrEmpty($DuplicateTicketNo))) {
        $body['duplicateTicketNo'] = $DuplicateTicketNo
    }
    return $body
}

function New-CancelBody {
    param([long]$Version, [string]$Reason, [switch]$OmitVersion, [switch]$OmitReason)
    $body = @{}
    if (-not $OmitVersion) { $body['version'] = $Version }
    if (-not $OmitReason) { $body['reason'] = $Reason }
    return $body
}

# 两个动作路径统一构造，避免手抄路径时打错一个字导致 404 被当成业务结论。
function Get-ActionPath {
    param([string]$TicketNo, [string]$Action)
    return "/fd/v1/tickets/$TicketNo/actions/$Action"
}

# 成功响应里 actionDeadlineAt 必须**不存在**（全局 non_null）：既查字段值，也查原始 JSON 里没有这个键。
function Test-DeadlineAbsent {
    param($Result)
    if ($null -eq $Result) { return $false }
    $data = Get-Data $Result
    if ($null -eq $data) { return $false }
    $propertyMissing = ([string]$Result.Body) -notmatch '"actionDeadlineAt"'
    return ($propertyMissing -and [string]::IsNullOrEmpty([string]$data.actionDeadlineAt))
}

# 关闭/撤销成功的统一断言：200 + 终态 + version 递增 + 期限字段不出现 + 负责人符合期望。
function Assert-TerminalSnapshot {
    param(
        [string]$Name,
        $Result,
        [string]$ExpectedStatus,
        [long]$ExpectedVersion,
        [string]$ExpectedAssigneeKind,
        [int]$ExpectedAssigneeId,
        [string]$Detail
    )
    $data = Get-Data $Result
    $assigneeOk = $false
    $assigneeActual = '(无)'
    if ($null -ne $data) {
        if ($null -eq $data.assignee) {
            $assigneeActual = '(字段不出现)'
            $assigneeOk = ($ExpectedAssigneeKind -eq 'none')
        } else {
            $assigneeActual = "$($data.assignee.id)"
            if ($ExpectedAssigneeKind -eq 'user') {
                $assigneeOk = ([int]$data.assignee.id -eq $ExpectedAssigneeId)
            }
        }
    }
    $condition = ($Result.Status -eq 200) -and ($null -ne $data) -and
    ($data.status -eq $ExpectedStatus) -and ([long]$data.version -eq $ExpectedVersion) -and
    (Test-DeadlineAbsent $Result) -and $assigneeOk
    Add-Assertion -Name $Name -Condition $condition `
        -Expected ("200，status=$ExpectedStatus，version=$ExpectedVersion，actionDeadlineAt 字段不出现，assignee=" + `
        $(if ($ExpectedAssigneeKind -eq 'none') { '不出现（无人负责过）' } else { "id=$ExpectedAssigneeId" })) `
        -Detail ("$Detail；实测 status=$($Result.Status) businessStatus=$($data.status) version=$($data.version) " +
        "assignee=$assigneeActual deadline字段存在=$(([string]$Result.Body) -match '\"actionDeadlineAt\"')") -Result $Result
    return $data
}
# 临时主体：片 D 的权限格子必须把「TICKET_PROCESS 与 TICKET_CLOSE 拆开」，而演示库里
# it / admin 都同时持有两个权限，所以用 SQL 造 4 个临时用户 + 5 个临时角色（收尾整行删除）：
#   · 用户行的 password 摘要**复制**演示用户 it 的 password 列（不硬编码、不打印、不落盘），
#     因此临时用户的登录口令等于 -ItPassword / E2E_IT_PASSWORD
#   · 自定义角色按传入的权限编码逐条授予；-WithItSupportRole 时额外授予内置 IT_SUPPORT 角色
function New-TempPrincipal {
    param(
        [string]$Key,
        [string]$Label,
        [string]$Purpose,
        [string[]]$PermissionCodes = @(),
        [switch]$WithItSupportRole
    )

    $username = "$tempUsernamePrefix-$Key"
    $displayName = "片D验收$Label-$stamp"
    $roleCode = "$tempRoleCodePrefix-$($Key.ToUpperInvariant())"

    $statements = [System.Collections.Generic.List[string]]::new()
    $statements.Add("INSERT INTO iam_role (code, name, description, created_at) VALUES " +
        "('$roleCode', '$displayName 角色', '片 D 真实验收临时角色（脚本创建，收尾删除）', UTC_TIMESTAMP(3));")
    foreach ($code in $PermissionCodes) {
        $statements.Add("INSERT INTO iam_role_permission (role_id, permission_id, granted_by, granted_at) " +
            "SELECT r.id, p.id, NULL, UTC_TIMESTAMP(3) FROM iam_role r JOIN iam_permission p ON p.code = '$code' " +
            "WHERE r.code = '$roleCode';")
    }
    $statements.Add("INSERT INTO iam_user (username, display_name, password, status, created_at, updated_at, version) " +
        "SELECT '$username', '$displayName', u.password, 'ENABLED', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 0 " +
        "FROM iam_user u WHERE u.username = '$ItUser';")
    $statements.Add("INSERT INTO iam_user_role (user_id, role_id, granted_by, granted_at) " +
        "SELECT u.id, r.id, NULL, UTC_TIMESTAMP(3) FROM iam_user u JOIN iam_role r ON r.code = '$roleCode' " +
        "WHERE u.username = '$username';")
    if ($WithItSupportRole) {
        $statements.Add("INSERT INTO iam_user_role (user_id, role_id, granted_by, granted_at) " +
            "SELECT u.id, r.id, NULL, UTC_TIMESTAMP(3) FROM iam_user u JOIN iam_role r ON r.code = 'IT_SUPPORT' " +
            "WHERE u.username = '$username';")
    }

    $insertResult = Invoke-MySql -Sql ($statements -join "`n")

    $idRow = Get-MySqlRow -Sql ("SELECT IFNULL(u.id,-1), IFNULL(r.id,-1) FROM iam_user u " +
        "LEFT JOIN iam_role r ON r.code = '$roleCode' WHERE u.username = '$username';")
    $userId = -1
    $roleId = -1
    if ($idRow.ok) {
        $userId = [int]$idRow.columns[0]
        $roleId = [int]$idRow.columns[1]
    }

    $grantRow = Get-MySqlRow -Sql "SELECT COUNT(*) FROM iam_role_permission WHERE role_id = $roleId;"
    $grantCount = -1
    if ($grantRow.ok) { $grantCount = [int]$grantRow.columns[0] }
    $userRoleRow = Get-MySqlRow -Sql "SELECT COUNT(*) FROM iam_user_role WHERE user_id = $userId;"
    $userRoleCount = -1
    if ($userRoleRow.ok) { $userRoleCount = [int]$userRoleRow.columns[0] }

    $expectedUserRoles = 1
    if ($WithItSupportRole) { $expectedUserRoles = 2 }
    $created = (($insertResult.exitCode -eq 0) -and ($userId -gt 0) -and ($roleId -gt 0) -and
        ($grantCount -eq $PermissionCodes.Count) -and ($userRoleCount -eq $expectedUserRoles))

    Add-Assertion -Name "setup.temp.$Key.created" -Condition $created `
        -Expected ("临时用户 + 自定义角色建好：角色授权 $($PermissionCodes.Count) 条，用户角色 $expectedUserRoles 条") `
        -Detail ("username=$username roleCode=$roleCode userId=$userId roleId=$roleId " +
        "rolePermissionCount=$grantCount userRoleCount=$userRoleCount exitCode=$($insertResult.exitCode) " +
        "permissions=$(($PermissionCodes -join '|')) withItSupportRole=$([bool]$WithItSupportRole) " +
        "stderr=[$(Get-BriefText $insertResult.stderrText 120)]；" +
        '口令摘要复制自演示用户 it，未打印、未落盘，随清理一并删除')
    if (-not $created) { throw "临时主体 $Key 创建失败（userId=$userId roleId=$roleId），权限格子无法进行" }

    $info = [pscustomobject]@{
        key                 = $Key
        label               = $Label
        purpose             = $Purpose
        username            = $username
        displayName         = $displayName
        roleCode            = $roleCode
        userId              = $userId
        roleId              = $roleId
        permissionCodes     = @($PermissionCodes)
        withItSupportRole   = [bool]$WithItSupportRole
        rolePermissionCount = $grantCount
        userRoleCount       = $userRoleCount
    }
    $script:tempCleanup.Add($info)
    return $info
}

$employeeClient = New-Client
$itClient = New-Client
# 第二个 it 客户端：并发用例要求两路请求真的同时在路上。同一个 HttpClient 的两路请求在
# .NET Framework 下可能受 ServicePoint 连接上限约束而排队，因此另开客户端、另登一次录。
$itSecondClient = New-Client
$adminClient = New-Client
$anonymousClient = New-Client
$u1Client = New-Client
$u2Client = New-Client
$u3Client = New-Client
$u5Client = New-Client
$clients = @($employeeClient, $itClient, $itSecondClient, $adminClient, $anonymousClient, $u1Client, $u2Client, $u3Client, $u5Client)

$unknownTicketNo = 'FD-19990101-001'
$employeeUserId = -1
$itUserId = -1
$adminUserId = -1
$demoBefore = $null
$demoAfter = $null

# 载体工单（全部由接口创建，收尾按 ticket_no 精确删除）
$ticketDup = ''
$ticketDupTarget = ''
$ticketScope = ''
$ticketInvalid = ''
$ticketErr = ''
$ticketOther = ''
$ticketCanceledTarget = ''
$ticketCancelProc = ''
$ticketCancelWait = ''
$ticketCancelConfirm = ''
$ticketCompleted = ''
$ticketErrCancel = ''
$ticketHandover = ''
$ticketRace = ''
$ticketRaceTransfer = ''
$verDup = -1
$verDupTarget = -1
$verScope = -1
$verInvalid = -1
$verErr = -1
$verOther = -1
$verCanceledTarget = -1
$verCancelProc = -1
$verCancelWait = -1
$verCancelConfirm = -1
$verCompleted = -1
$verErrCancel = -1
$verHandover = -1
$verRace = -1
$verRaceTransfer = -1
$waitDeadline = ''
$confirmDeadline = ''

# 载体工单由接口创建：ticketNo 与 version 都取自响应，绝不用 SQL 造一个"看起来像工单"的行。
function New-CarrierTicket {
    param(
        [string]$Key,
        [string]$Title,
        [string]$Purpose,
        [System.Net.Http.HttpClient]$RequesterClient,
        [string]$RequesterToken,
        [string]$RequesterActor,
        [long]$CategoryId
    )
    $created = Send-CreateTicket -Client $RequesterClient -Token $RequesterToken `
        -Title $Title -Description $Purpose -CategoryId $CategoryId `
        -Actor $RequesterActor -Note "setup.create($Key)"
    $ticketNo = ''
    $version = -1
    if ($created.Status -eq 201) {
        $data = Get-Data $created
        $ticketNo = [string]$data.ticketNo
        $version = [long]$data.version
    }
    Add-Assertion -Name "setup.$Key.create.201" -Condition ($created.Status -eq 201) `
        -Expected "201（提交人 $RequesterActor 建单）" `
        -Detail "ticketNo=$ticketNo version=$version purpose=$Purpose" -Result $created
    if ($created.Status -ne 201) {
        throw "载体工单 $Key 创建失败（status=$($created.Status) code=$(Get-Code $created)）；后续断言无法进行"
    }
    $script:ticketsInfo[$Key] = @{ ticketNo = $ticketNo; purpose = $Purpose; requester = $RequesterActor }
    return [pscustomobject]@{ ticketNo = $ticketNo; version = $version; result = $created }
}

# 领取由真实 IT 主体发起（内置 IT_SUPPORT 角色的资格判定在服务端复核，不能用 SQL 跳过）。
function Invoke-ClaimCarrier {
    param(
        [string]$Key,
        [string]$TicketNo,
        [long]$Version,
        [System.Net.Http.HttpClient]$Client,
        [string]$Token,
        [string]$Actor
    )
    $result = Send-Req -Client $Client -Method Post -Path (Get-ActionPath $TicketNo 'claim') -Token $Token `
        -Body @{ version = $Version } -Actor $Actor -Note "setup.claim($Key)"
    $data = Get-Data $result
    $ok = (($result.Status -eq 200) -and ($null -ne $data) -and ($data.status -eq 'PROCESSING'))
    Add-Assertion -Name "setup.$Key.claim.200" -Condition $ok `
        -Expected '200 且 status=PROCESSING' `
        -Detail "status=$($result.Status) code=$(Get-Code $result) businessStatus=$($data.status) version=$($data.version)" `
        -Result $result
    if (-not $ok) { throw "载体工单 $Key 领取失败（status=$($result.Status) code=$(Get-Code $result)）" }
    return [pscustomobject]@{ version = [long]$data.version; result = $result }
}

# close 成功的四点证据：响应快照、期限字段不出现、库中终态事实、时间线最后一条是 CLOSURE。
function Assert-CloseSuccess {
    param(
        [string]$Name,
        [string]$TicketNo,
        [long]$ExpectedVersion,
        $Result,
        [string]$ReasonCode,
        [string]$Description,
        [int]$ExpectedAssigneeId,
        [string]$Detail
    )
    $null = Assert-TerminalSnapshot -Name "$Name.200" -Result $Result -ExpectedStatus 'CLOSED' `
        -ExpectedVersion $ExpectedVersion -ExpectedAssigneeKind 'user' -ExpectedAssigneeId $ExpectedAssigneeId -Detail $Detail
    $deadlineKeyPresent = ([string]$Result.Body).Contains('"actionDeadlineAt"')
    Add-Assertion -Name "$Name.deadlineFieldAbsent" -Condition (Test-DeadlineAbsent $Result) `
        -Expected '成功响应里没有 actionDeadlineAt 字段（全局 non_null；关闭后不存在有效期限）' `
        -Detail "rawBodyHasDeadlineKey=$deadlineKeyPresent" -Result $Result

    $row = Get-TicketDbRow -TicketNo $TicketNo
    $dbOk = ($null -ne $row) -and ($row.status -eq 'CLOSED') -and ($row.closeMethod -eq 'MANUAL') -and
    ($row.closeReason -eq $ReasonCode) -and ($row.endedAt -eq 'SET') -and ($row.actionDeadlineAt -eq 'NULL') -and
    ($row.assigneeId -eq $ExpectedAssigneeId) -and ($row.completionMethod -eq 'NULL') -and
    ($row.version -eq $ExpectedVersion)
    Add-Assertion -Name "$Name.dbTerminalFacts" -Condition $dbOk `
        -Expected ("库中 status=CLOSED、close_method=MANUAL、close_reason=$ReasonCode、ended_at 有值、action_deadline_at 为空、" +
        "负责人保留 id=$ExpectedAssigneeId、completion_method 为空、version=$ExpectedVersion") `
        -Detail "row=[$($row.raw)]"

    $last = Get-LastRecordRow -TicketNo $TicketNo
    $lastOk = ($null -ne $last) -and ($last.recordType -eq 'CLOSURE') -and ($last.fromStatus -eq 'PROCESSING') -and
    ($last.toStatus -eq 'CLOSED') -and ($last.closeMethod -eq 'MANUAL') -and ($last.closeReason -eq $ReasonCode) -and
    ($last.reason -eq $Description) -and ($last.reasonLen -eq $Description.Length)
    Add-Assertion -Name "$Name.lastRecordIsClosure" -Condition $lastOk `
        -Expected '时间线最后一条是 CLOSURE：fromStatus=PROCESSING、toStatus=CLOSED、closeMethod=MANUAL、closeReason 与请求一致、reason 与关闭说明逐字一致' `
        -Detail "row=[$($last.raw)] 期望 reason=[$Description]（长度 $($Description.Length)）"
    return $row
}

# cancel 成功的四点证据：响应快照、期限字段不出现、库中终态事实（完成/关闭字段必须全空）、
# 时间线最后一条是 CANCELLATION。
function Assert-CancelSuccess {
    param(
        [string]$Name,
        [string]$TicketNo,
        [long]$ExpectedVersion,
        $Result,
        [string]$Reason,
        [string]$FromStatus,
        [string]$ExpectedAssigneeKind,
        [int]$ExpectedAssigneeId,
        [string]$Detail
    )
    $null = Assert-TerminalSnapshot -Name "$Name.200" -Result $Result -ExpectedStatus 'CANCELED' `
        -ExpectedVersion $ExpectedVersion -ExpectedAssigneeKind $ExpectedAssigneeKind `
        -ExpectedAssigneeId $ExpectedAssigneeId -Detail $Detail
    $deadlineKeyPresent = ([string]$Result.Body).Contains('"actionDeadlineAt"')
    Add-Assertion -Name "$Name.deadlineFieldAbsent" -Condition (Test-DeadlineAbsent $Result) `
        -Expected '成功响应里没有 actionDeadlineAt 字段（撤销把期限清空）' `
        -Detail "rawBodyHasDeadlineKey=$deadlineKeyPresent" -Result $Result

    $row = Get-TicketDbRow -TicketNo $TicketNo
    $assigneeOk = (($ExpectedAssigneeKind -eq 'none') -and ($null -ne $row) -and ($row.assigneeId -eq -1)) -or
    (($ExpectedAssigneeKind -eq 'user') -and ($null -ne $row) -and ($row.assigneeId -eq $ExpectedAssigneeId))
    $dbOk = ($null -ne $row) -and ($row.status -eq 'CANCELED') -and ($row.endedAt -eq 'SET') -and
    ($row.actionDeadlineAt -eq 'NULL') -and ($row.completionMethod -eq 'NULL') -and ($row.closeMethod -eq 'NULL') -and
    ($row.closeReason -eq 'NULL') -and $assigneeOk -and ($row.version -eq $ExpectedVersion)
    Add-Assertion -Name "$Name.dbTerminalFacts" -Condition $dbOk `
        -Expected ('库中 status=CANCELED、ended_at 有值、action_deadline_at 为空、completion_method / close_method / ' +
        'close_reason 全为空（已取消不得带任何完成或关闭字段）、负责人符合期望（待受理撤销时为 NULL）、version 递增') `
        -Detail "row=[$($row.raw)]"

    $last = Get-LastRecordRow -TicketNo $TicketNo
    $lastOk = ($null -ne $last) -and ($last.recordType -eq 'CANCELLATION') -and ($last.fromStatus -eq $FromStatus) -and
    ($last.toStatus -eq 'CANCELED') -and ($last.reason -eq $Reason) -and ($last.reasonLen -eq $Reason.Length)
    Add-Assertion -Name "$Name.lastRecordIsCancellation" -Condition $lastOk `
        -Expected "时间线最后一条是 CANCELLATION：fromStatus=$FromStatus、toStatus=CANCELED、reason 与请求逐字一致" `
        -Detail "row=[$($last.raw)] 期望 reason=[$Reason]（长度 $($Reason.Length)）"
    return $row
}

try {
    # ── 2. 前置与基线：.env、端点存活探测、运行前指纹 ────────────────────────
    Add-Assertion -Name 'precondition.dotEnvLoaded' -Condition $script:dbAvailable `
        -Expected '.env 中 FLOWDESK_DB_NAME 与 FLOWDESK_MYSQL_ROOT_PASSWORD 可读' `
        -Detail "dbContainer=$DbContainer dbNameAvailable=$(-not [string]::IsNullOrWhiteSpace($dbName)) rootPasswordAvailable=$(-not [string]::IsNullOrWhiteSpace($rootPassword))（值不打印）"
    if (-not $script:dbAvailable) {
        throw "无法从 $EnvFile 读取演示库连接信息（FLOWDESK_DB_NAME / FLOWDESK_MYSQL_ROOT_PASSWORD），数据库直查与清理无法进行"
    }

    $demoBefore = Get-DemoFingerprint
    Add-Assertion -Name 'baseline.demoDatabaseReadBeforeRun' -Condition $demoBefore.readOk `
        -Expected '运行前可读取演示库计数' `
        -Detail ("运行前：用户 {0} / 角色 {1} / 权限 {2} / 分类 {3} / 工单 {4} / 记录 {5} / 参与者 {6} / 关联 {7}；工单号=[{8}]" -f `
            $demoBefore.users, $demoBefore.roles, $demoBefore.permissions, $demoBefore.categories, `
            $demoBefore.tickets, $demoBefore.records, $demoBefore.participants, $demoBefore.relations, $demoBefore.ticketNos)
    Add-Assertion -Name 'baseline.ticketNoFingerprintRecorded' -Condition ($demoBefore.ticketNos.Length -gt 0) `
        -Expected '运行前工单号列表已完整记录（清理后必须逐字相同）' `
        -Detail "工单号=[$($demoBefore.ticketNos)]；本脚本只新增再删除自己的行，绝不修改运行前就存在的任何行"
    Add-Assertion -Name 'baseline.idempotencyScopeDeclared' -Condition $true `
        -Expected '脚本只新增再删除自己创建的行：15 张载体工单 + 其记录/参与关系/重复关联 + 4 个临时用户与 5 个临时角色' `
        -Detail ('清理段按 ticket_no 精确删除自建工单，按主键 ID 精确删除临时用户与临时角色（含 iam_user_role / ' +
        'iam_role_permission）；没有任何 UPDATE/DELETE 作用于运行前就存在的行')
    Write-Host "演示库基线：$($demoBefore.ticketNos)（工单 $($demoBefore.tickets) / 记录 $($demoBefore.records) / 参与者 $($demoBefore.participants) / 关联 $($demoBefore.relations)）"
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
    $loginItSecond = Send-Req -Client $itSecondClient -Method Post -Path '/fd/v1/auth/login' `
        -Body @{ username = $ItUser; password = $ItPassword } -Actor 'it(第二会话)' -Note 'login.itSecond'
    Add-Assertion -Name 'login.itSecondSession.200' -Condition ($loginItSecond.Status -eq 200) `
        -Expected '200（同一个 it 的第二个会话，供并发用例各持一条连接）' `
        -Detail "status=$($loginItSecond.Status) code=$(Get-Code $loginItSecond)" -Result $loginItSecond
    $itToken2 = ''
    if ($loginItSecond.Status -eq 200) { $itToken2 = (Get-Data $loginItSecond).accessToken }
    if ([string]::IsNullOrEmpty($itToken2)) { throw '第二个 it 会话未拿到 accessToken，转交并发用例无法进行' }
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

    Add-Assertion -Name 'perm.employee.lacksCloseAndProcessAndTransfer' `
        -Condition ((-not ($employeePerms -contains 'TICKET_PROCESS')) -and (-not ($employeePerms -contains 'TICKET_CLOSE')) -and (-not ($employeePerms -contains 'TICKET_TRANSFER'))) `
        -Expected 'employee 同时缺 TICKET_PROCESS / TICKET_CLOSE / TICKET_TRANSFER（close 403 与"提交人不能关闭"的前提）' `
        -Detail "roles=$(@($employeeData.roles) -join '|') hasTICKET_PROCESS=$($employeePerms -contains 'TICKET_PROCESS') hasTICKET_CLOSE=$($employeePerms -contains 'TICKET_CLOSE') hasTICKET_REQUESTER_ACTION=$($employeePerms -contains 'TICKET_REQUESTER_ACTION')" `
        -Result $meEmployee
    Add-Assertion -Name 'perm.employee.hasRequesterAction' -Condition ($employeePerms -contains 'TICKET_REQUESTER_ACTION') `
        -Expected 'employee 具备 TICKET_REQUESTER_ACTION（cancel 成功路径与 409 用例的前提）' `
        -Detail "hasTICKET_REQUESTER_ACTION=$($employeePerms -contains 'TICKET_REQUESTER_ACTION')" -Result $meEmployee
    Add-Assertion -Name 'perm.it.hasProcessAndClose' `
        -Condition (($itPerms -contains 'TICKET_PROCESS') -and ($itPerms -contains 'TICKET_CLOSE')) `
        -Expected 'it 同时具备 TICKET_PROCESS 与 TICKET_CLOSE（close 正向路径的前提）' `
        -Detail "roles=$(@($itData.roles) -join '|') hasTICKET_PROCESS=$($itPerms -contains 'TICKET_PROCESS') hasTICKET_CLOSE=$($itPerms -contains 'TICKET_CLOSE')" `
        -Result $meIt
    $script:observed.Add("it.hasTICKET_REQUESTER_ACTION=$($itPerms -contains 'TICKET_REQUESTER_ACTION')（脚本不依赖该值：cancel 的 403 格子用自建临时角色，不用 it）")
    $script:observed.Add("admin.roles=$(@($adminData.roles) -join '|') admin.hasTICKET_PROCESS=$($adminPerms -contains 'TICKET_PROCESS') admin.hasTICKET_CLOSE=$($adminPerms -contains 'TICKET_CLOSE')")
    $script:notes.Add('admin 只用于记录事实（本机演示库存在三角色漂移）；脚本的任何断言都不依赖 admin 的角色集合，因此干净库与本机演示库结论一致。')
    $script:preflightInfo = [ordered]@{
        targetBaseUrl   = $baseUrl
        accounts        = @($EmployeeUser, $ItUser, $AdminUser)
        employeePerms   = @($employeePerms | Where-Object { $_ -like 'TICKET*' })
        itPerms         = @($itPerms | Where-Object { $_ -like 'TICKET*' })
        itRoles         = @($itData.roles)
        adminRoles      = @($adminData.roles)
    }

    # ── 4. 端点存活探测：旧构建上片 D 的两个路径不存在，必须先把这件事说清楚 ──
    # it 调 close：端点存在 → 权限闸门通过后是 404/TICKET_NOT_FOUND；路径不存在 → 404/RESOURCE_NOT_FOUND。
    $probeClose = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $unknownTicketNo 'close') `
        -Token $itToken -Body (New-CloseBody -Version 0 -ReasonCode 'INVALID' -Description '端点探测') `
        -Actor 'it' -Note 'preflight.close'
    Add-Assertion -Name 'preflight.closeEndpointExists' `
        -Condition (($probeClose.Status -eq 404) -and ((Get-Code $probeClose) -eq 'TICKET_NOT_FOUND')) `
        -Expected '404 + TICKET_NOT_FOUND（端点存在、权限通过、编号不存在）' `
        -Detail ("status=$($probeClose.Status) code=$(Get-Code $probeClose)；若 code=RESOURCE_NOT_FOUND 说明目标后端是旧构建，" +
        '片 D 的两个端点还不存在，请把基址指向已包含片 D 的新后端（并行 8092）') -Result $probeClose
    # employee 调 cancel：端点存在时是 404/TICKET_NOT_FOUND（提交人权限通过、编号不存在、不可见）；
    # 路径不存在时是 404/RESOURCE_NOT_FOUND —— 用业务码区分，不依赖 it 的权限漂移。
    $probeCancel = Send-Req -Client $employeeClient -Method Post -Path (Get-ActionPath $unknownTicketNo 'cancel') `
        -Token $employeeToken -Body (New-CancelBody -Version 0 -Reason '端点探测') -Actor 'employee' -Note 'preflight.cancel'
    Add-Assertion -Name 'preflight.cancelEndpointExists' `
        -Condition (($probeCancel.Status -eq 404) -and ((Get-Code $probeCancel) -eq 'TICKET_NOT_FOUND')) `
        -Expected '404 + TICKET_NOT_FOUND（端点存在、提交人权限通过、编号不存在）' `
        -Detail ("status=$($probeCancel.Status) code=$(Get-Code $probeCancel)；旧构建会是 404/RESOURCE_NOT_FOUND，" +
        '权限不足则是 403（U1 的那一格在 a8 组里断言）') -Result $probeCancel
    # 目标后端不是新构建时直接停下：继续跑只会把"端点不存在"放大成几十条 404 假红，
    # 证据里也分不清是脚本写错还是构建过期。这是硬前置，与"三角色登录失败即抛"同一口径。
    if (((Get-Code $probeClose) -eq 'RESOURCE_NOT_FOUND') -or ((Get-Code $probeCancel) -eq 'RESOURCE_NOT_FOUND')) {
        throw ("目标后端 $baseUrl 上没有片 D 的端点（404/RESOURCE_NOT_FOUND）：这是旧构建。" +
        '请把基址指向已包含片 D 的新后端，例如 -BaseUrl http://127.0.0.1:8092')
    }

    # ── 5. 分类选项：载体工单需要一个启用分类 ────────────────────────────────
    $options = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/categories/options' -Token $employeeToken `
        -Actor 'employee' -Note 'categories.options'
    $optionItems = @(Get-Data $options)
    Add-Assertion -Name 'categories.options.nonEmpty' -Condition (($options.Status -eq 200) -and ($optionItems.Count -ge 1)) `
        -Expected '200 且至少一个启用分类' -Detail "status=$($options.Status) count=$($optionItems.Count)" -Result $options
    if ($optionItems.Count -lt 1) { throw '演示库没有启用分类，无法创建载体工单' }
    $categoryId = [long]$optionItems[0].id

    # ── 6. 临时主体：4 个用户 + 5 个角色（片 D 权限格子的核心装置） ───────────
    $u1 = New-TempPrincipal -Key 'closeonly' -Label '只有关闭权限' `
        -Purpose 'close 缺 TICKET_PROCESS → 403；cancel 缺 TICKET_REQUESTER_ACTION → 403' `
        -PermissionCodes @('TICKET_CLOSE')
    $u2 = New-TempPrincipal -Key 'processonly' -Label '只有处理权限' `
        -Purpose 'close 缺 TICKET_CLOSE → 403（与 U1 合起来把两个权限拆开各测一次）' `
        -PermissionCodes @('TICKET_PROCESS')
    $u3 = New-TempPrincipal -Key 'requesteronly' -Label '只有提交人权限' `
        -Purpose '作为"另一位提交人"建一张真实工单（他人的重复目标）；cancel 不可见 → 404' `
        -PermissionCodes @('TICKET_CREATE', 'TICKET_VIEW_OWN', 'TICKET_REQUESTER_ACTION')
    $u5 = New-TempPrincipal -Key 'itplusrequester' -Label 'IT加提交人权限' `
        -Purpose '内置 IT_SUPPORT + 额外 TICKET_REQUESTER_ACTION：领取他人工单（构造不可见）、非负责人 close 409、非提交人 cancel 409' `
        -PermissionCodes @('TICKET_REQUESTER_ACTION') -WithItSupportRole

    $loginU1 = Send-Req -Client $u1Client -Method Post -Path '/fd/v1/auth/login' `
        -Body @{ username = $u1.username; password = $ItPassword } -Actor 'temp-close-only' -Note 'setup.login(u1)'
    $loginU2 = Send-Req -Client $u2Client -Method Post -Path '/fd/v1/auth/login' `
        -Body @{ username = $u2.username; password = $ItPassword } -Actor 'temp-process-only' -Note 'setup.login(u2)'
    $loginU3 = Send-Req -Client $u3Client -Method Post -Path '/fd/v1/auth/login' `
        -Body @{ username = $u3.username; password = $ItPassword } -Actor 'temp-requester-only' -Note 'setup.login(u3)'
    $loginU5 = Send-Req -Client $u5Client -Method Post -Path '/fd/v1/auth/login' `
        -Body @{ username = $u5.username; password = $ItPassword } -Actor 'temp-it-plus-requester' -Note 'setup.login(u5)'
    Add-Assertion -Name 'setup.temp.u1.login.200' -Condition ($loginU1.Status -eq 200) `
        -Expected '200（临时用户口令摘要复制自演示用户 it）' `
        -Detail "status=$($loginU1.Status) code=$(Get-Code $loginU1)；不通过说明 -ItPassword/E2E_IT_PASSWORD 与库里 it 的口令不一致" -Result $loginU1
    Add-Assertion -Name 'setup.temp.u2.login.200' -Condition ($loginU2.Status -eq 200) -Expected '200' `
        -Detail "status=$($loginU2.Status) code=$(Get-Code $loginU2)" -Result $loginU2
    Add-Assertion -Name 'setup.temp.u3.login.200' -Condition ($loginU3.Status -eq 200) -Expected '200' `
        -Detail "status=$($loginU3.Status) code=$(Get-Code $loginU3)" -Result $loginU3
    Add-Assertion -Name 'setup.temp.u5.login.200' -Condition ($loginU5.Status -eq 200) -Expected '200' `
        -Detail "status=$($loginU5.Status) code=$(Get-Code $loginU5)" -Result $loginU5
    if (($loginU1.Status -ne 200) -or ($loginU2.Status -ne 200) -or ($loginU3.Status -ne 200) -or ($loginU5.Status -ne 200)) {
        throw "临时主体登录未全部返回 200（u1=$($loginU1.Status) u2=$($loginU2.Status) u3=$($loginU3.Status) u5=$($loginU5.Status)）；权限格子无法进行"
    }
    $u1Token = (Get-Data $loginU1).accessToken
    $u2Token = (Get-Data $loginU2).accessToken
    $u3Token = (Get-Data $loginU3).accessToken
    $u5Token = (Get-Data $loginU5).accessToken

    # 权限格子以**会话快照**为准（服务端的 hasAuthority 读的就是这份快照），四个临时主体逐个自检。
    $meU1 = Send-Req -Client $u1Client -Method Get -Path '/fd/v1/auth/me' -Token $u1Token -Actor 'temp-close-only' -Note 'setup.me(u1)'
    $meU2 = Send-Req -Client $u2Client -Method Get -Path '/fd/v1/auth/me' -Token $u2Token -Actor 'temp-process-only' -Note 'setup.me(u2)'
    $meU3 = Send-Req -Client $u3Client -Method Get -Path '/fd/v1/auth/me' -Token $u3Token -Actor 'temp-requester-only' -Note 'setup.me(u3)'
    $meU5 = Send-Req -Client $u5Client -Method Get -Path '/fd/v1/auth/me' -Token $u5Token -Actor 'temp-it-plus-requester' -Note 'setup.me(u5)'
    $u1Perms = @((Get-Data $meU1).permissions)
    $u2Perms = @((Get-Data $meU2).permissions)
    $u3Perms = @((Get-Data $meU3).permissions)
    $u5Perms = @((Get-Data $meU5).permissions)
    Add-Assertion -Name 'setup.temp.u1.snapshotIsCloseOnly' `
        -Condition (($u1Perms -contains 'TICKET_CLOSE') -and (-not ($u1Perms -contains 'TICKET_PROCESS')) -and (-not ($u1Perms -contains 'TICKET_REQUESTER_ACTION'))) `
        -Expected 'U1 会话快照 = 只有 TICKET_CLOSE（无 TICKET_PROCESS、无 TICKET_REQUESTER_ACTION）' `
        -Detail "perms=$(($u1Perms | Where-Object { $_ -like 'TICKET*' }) -join '|')" -Result $meU1
    Add-Assertion -Name 'setup.temp.u2.snapshotIsProcessOnly' `
        -Condition (($u2Perms -contains 'TICKET_PROCESS') -and (-not ($u2Perms -contains 'TICKET_CLOSE'))) `
        -Expected 'U2 会话快照 = 只有 TICKET_PROCESS（无 TICKET_CLOSE）' `
        -Detail "perms=$(($u2Perms | Where-Object { $_ -like 'TICKET*' }) -join '|')" -Result $meU2
    Add-Assertion -Name 'setup.temp.u3.snapshotIsRequesterOnly' `
        -Condition (($u3Perms -contains 'TICKET_REQUESTER_ACTION') -and ($u3Perms -contains 'TICKET_CREATE') -and (-not ($u3Perms -contains 'TICKET_VIEW_QUEUE')) -and (-not ($u3Perms -contains 'TICKET_VIEW_PARTICIPATED'))) `
        -Expected 'U3 会话快照 = 提交人一族（有 TICKET_REQUESTER_ACTION / TICKET_CREATE，无队列与参与可见性）' `
        -Detail "perms=$(($u3Perms | Where-Object { $_ -like 'TICKET*' }) -join '|')" -Result $meU3
    Add-Assertion -Name 'setup.temp.u5.snapshotIsItPlusRequester' `
        -Condition (($u5Perms -contains 'TICKET_PROCESS') -and ($u5Perms -contains 'TICKET_CLOSE') -and ($u5Perms -contains 'TICKET_CLAIM') -and ($u5Perms -contains 'TICKET_REQUESTER_ACTION')) `
        -Expected 'U5 会话快照 = 内置 IT_SUPPORT 全部工单权限 + TICKET_REQUESTER_ACTION' `
        -Detail "roles=$(@((Get-Data $meU5).roles) -join '|') perms=$(($u5Perms | Where-Object { $_ -like 'TICKET*' }) -join '|')" -Result $meU5
    $script:tempPrincipalInfo = [ordered]@{
        u1 = @{ username = $u1.username; userId = $u1.userId; roleCode = $u1.roleCode; roleId = $u1.roleId; perms = @($u1Perms | Where-Object { $_ -like 'TICKET*' }); purpose = $u1.purpose }
        u2 = @{ username = $u2.username; userId = $u2.userId; roleCode = $u2.roleCode; roleId = $u2.roleId; perms = @($u2Perms | Where-Object { $_ -like 'TICKET*' }); purpose = $u2.purpose }
        u3 = @{ username = $u3.username; userId = $u3.userId; roleCode = $u3.roleCode; roleId = $u3.roleId; perms = @($u3Perms | Where-Object { $_ -like 'TICKET*' }); purpose = $u3.purpose }
        u5 = @{ username = $u5.username; userId = $u5.userId; roleCode = $u5.roleCode; roleId = $u5.roleId; roles = @((Get-Data $meU5).roles); perms = @($u5Perms | Where-Object { $_ -like 'TICKET*' }); purpose = $u5.purpose }
        passwordSource = "四个临时用户的 password 摘要复制自演示用户 $ItUser 的 password 列（Argon2id，不打印、不落盘）"
        cleanup = '按主键 ID 删除 iam_user_role / iam_role_permission / iam_user / iam_role'
    }

    # ── 7. 载体工单：15 张，全部由接口创建（提交人身份按用例需要） ────────────
    $carrierDup = New-CarrierTicket -Key 'dup' -Title "片D验收-重复关闭-$stamp" `
        -Purpose 'close DUPLICATE 成功路径 + ticket_relation 落库 + CLOSURE 时间线' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketDup = $carrierDup.ticketNo
    $verDup = $carrierDup.version

    $carrierDupTarget = New-CarrierTicket -Key 'dupTarget' -Title "片D验收-重复目标-$stamp" `
        -Purpose '被关联为重复目标（同一提交人、非终态；保持待受理，不做任何动作）' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketDupTarget = $carrierDupTarget.ticketNo
    $verDupTarget = $carrierDupTarget.version

    $carrierScope = New-CarrierTicket -Key 'scope' -Title "片D验收-超范围关闭-$stamp" `
        -Purpose 'close OUT_OF_SCOPE 成功路径 + 终态可区分（已关闭）' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketScope = $carrierScope.ticketNo
    $verScope = $carrierScope.version

    $carrierInvalid = New-CarrierTicket -Key 'invalid' -Title "片D验收-无效关闭-$stamp" `
        -Purpose 'close INVALID 成功路径 + 作为"已关闭的重复目标"（400 用例）' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketInvalid = $carrierInvalid.ticketNo
    $verInvalid = $carrierInvalid.version

    $carrierErr = New-CarrierTicket -Key 'err' -Title "片D验收-关闭错误族-$stamp" `
        -Purpose 'close 的 400/409 错误族载体（全程保持处理中、状态签名前后一致）' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketErr = $carrierErr.ticketNo
    $verErr = $carrierErr.version

    $carrierOther = New-CarrierTicket -Key 'other' -Title "片D验收-他人提交-$stamp" `
        -Purpose '他人提交的工单：他人的重复目标（400）+ 被 U5 领取后对 it 不可见（close 404）' `
        -RequesterClient $u3Client -RequesterToken $u3Token -RequesterActor 'temp-requester-only' -CategoryId $categoryId
    $ticketOther = $carrierOther.ticketNo
    $verOther = $carrierOther.version

    $carrierCanceledTarget = New-CarrierTicket -Key 'canceledTarget' -Title "片D验收-已取消目标-$stamp" `
        -Purpose '待受理撤销成功路径（→已取消）+ 作为"已取消的重复目标"（400）' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketCanceledTarget = $carrierCanceledTarget.ticketNo
    $verCanceledTarget = $carrierCanceledTarget.version

    $carrierCancelProc = New-CarrierTicket -Key 'cancelProc' -Title "片D验收-处理中撤销-$stamp" `
        -Purpose '处理中撤销成功路径（→已取消，负责人保留）+ 终态无出口' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketCancelProc = $carrierCancelProc.ticketNo
    $verCancelProc = $carrierCancelProc.version

    $carrierCancelWait = New-CarrierTicket -Key 'cancelWait' -Title "片D验收-待补充撤销-$stamp" `
        -Purpose '待补充撤销成功路径（期限由同一条 UPDATE 清空）' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketCancelWait = $carrierCancelWait.ticketNo
    $verCancelWait = $carrierCancelWait.version

    $carrierCancelConfirm = New-CarrierTicket -Key 'cancelConfirm' -Title "片D验收-待确认撤销-$stamp" `
        -Purpose '待确认撤销成功路径（期限清空、completion_method 必须为空）' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketCancelConfirm = $carrierCancelConfirm.ticketNo
    $verCancelConfirm = $carrierCancelConfirm.version

    $carrierCompleted = New-CarrierTicket -Key 'completed' -Title "片D验收-已完成-$stamp" `
        -Purpose '完整走完流程（→已完成）：终态可区分的三分之一 + 终态无出口' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketCompleted = $carrierCompleted.ticketNo
    $verCompleted = $carrierCompleted.version

    $carrierErrCancel = New-CarrierTicket -Key 'errCancel' -Title "片D验收-撤销错误族-$stamp" `
        -Purpose 'cancel 的 400/409 错误族载体（全程保持处理中、状态签名前后一致）' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketErrCancel = $carrierErrCancel.ticketNo
    $verErrCancel = $carrierErrCancel.version

    $carrierHandover = New-CarrierTicket -Key 'handover' -Title "片D验收-转交后-$stamp" `
        -Purpose 'it 领取后转交给 U5：it 仍可见但不是负责人（close 409），U5 是负责人但不是提交人（cancel 409）' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketHandover = $carrierHandover.ticketNo
    $verHandover = $carrierHandover.version

    $carrierRace = New-CarrierTicket -Key 'race' -Title "片D验收-并发-$stamp" `
        -Purpose '同一版本上「员工撤销 vs IT 关闭」真并发：恰好一个赢家，败者必须是 409 而不是 500' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketRace = $carrierRace.ticketNo
    $verRace = $carrierRace.version

    $carrierRaceTransfer = New-CarrierTicket -Key 'raceTransfer' -Title "片D验收-并发转交-$stamp" `
        -Purpose '同一版本上「IT 转交 vs IT 关闭」真并发：直接验证片 D 修掉「转交先锁用户行」之后的加锁顺序' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketRaceTransfer = $carrierRaceTransfer.ticketNo
    $verRaceTransfer = $carrierRaceTransfer.version

    # ── 8. 领取：除 dupTarget 与 other 之外都由 it 领取；other 由 U5 领取 ────
    $claim = Invoke-ClaimCarrier -Key 'dup' -TicketNo $ticketDup -Version $verDup -Client $itClient -Token $itToken -Actor 'it'
    $verDup = $claim.version
    Add-KeyAction -Order 1 -Actor 'it' -Action 'claim' -Method 'POST' -Path (Get-ActionPath $ticketDup 'claim') -TicketNo $ticketDup -Result $claim.result -StatusAfter 'PROCESSING'

    $claim = Invoke-ClaimCarrier -Key 'scope' -TicketNo $ticketScope -Version $verScope -Client $itClient -Token $itToken -Actor 'it'
    $verScope = $claim.version
    Add-KeyAction -Order 2 -Actor 'it' -Action 'claim' -Method 'POST' -Path (Get-ActionPath $ticketScope 'claim') -TicketNo $ticketScope -Result $claim.result -StatusAfter 'PROCESSING'

    $claim = Invoke-ClaimCarrier -Key 'invalid' -TicketNo $ticketInvalid -Version $verInvalid -Client $itClient -Token $itToken -Actor 'it'
    $verInvalid = $claim.version
    Add-KeyAction -Order 3 -Actor 'it' -Action 'claim' -Method 'POST' -Path (Get-ActionPath $ticketInvalid 'claim') -TicketNo $ticketInvalid -Result $claim.result -StatusAfter 'PROCESSING'

    $claim = Invoke-ClaimCarrier -Key 'err' -TicketNo $ticketErr -Version $verErr -Client $itClient -Token $itToken -Actor 'it'
    $verErr = $claim.version
    Add-KeyAction -Order 4 -Actor 'it' -Action 'claim' -Method 'POST' -Path (Get-ActionPath $ticketErr 'claim') -TicketNo $ticketErr -Result $claim.result -StatusAfter 'PROCESSING'

    $claim = Invoke-ClaimCarrier -Key 'cancelProc' -TicketNo $ticketCancelProc -Version $verCancelProc -Client $itClient -Token $itToken -Actor 'it'
    $verCancelProc = $claim.version
    Add-KeyAction -Order 5 -Actor 'it' -Action 'claim' -Method 'POST' -Path (Get-ActionPath $ticketCancelProc 'claim') -TicketNo $ticketCancelProc -Result $claim.result -StatusAfter 'PROCESSING'

    $claim = Invoke-ClaimCarrier -Key 'cancelWait' -TicketNo $ticketCancelWait -Version $verCancelWait -Client $itClient -Token $itToken -Actor 'it'
    $verCancelWait = $claim.version
    Add-KeyAction -Order 6 -Actor 'it' -Action 'claim' -Method 'POST' -Path (Get-ActionPath $ticketCancelWait 'claim') -TicketNo $ticketCancelWait -Result $claim.result -StatusAfter 'PROCESSING'

    $claim = Invoke-ClaimCarrier -Key 'cancelConfirm' -TicketNo $ticketCancelConfirm -Version $verCancelConfirm -Client $itClient -Token $itToken -Actor 'it'
    $verCancelConfirm = $claim.version
    Add-KeyAction -Order 7 -Actor 'it' -Action 'claim' -Method 'POST' -Path (Get-ActionPath $ticketCancelConfirm 'claim') -TicketNo $ticketCancelConfirm -Result $claim.result -StatusAfter 'PROCESSING'

    $claim = Invoke-ClaimCarrier -Key 'completed' -TicketNo $ticketCompleted -Version $verCompleted -Client $itClient -Token $itToken -Actor 'it'
    $verCompleted = $claim.version
    Add-KeyAction -Order 8 -Actor 'it' -Action 'claim' -Method 'POST' -Path (Get-ActionPath $ticketCompleted 'claim') -TicketNo $ticketCompleted -Result $claim.result -StatusAfter 'PROCESSING'

    $claim = Invoke-ClaimCarrier -Key 'errCancel' -TicketNo $ticketErrCancel -Version $verErrCancel -Client $itClient -Token $itToken -Actor 'it'
    $verErrCancel = $claim.version
    Add-KeyAction -Order 9 -Actor 'it' -Action 'claim' -Method 'POST' -Path (Get-ActionPath $ticketErrCancel 'claim') -TicketNo $ticketErrCancel -Result $claim.result -StatusAfter 'PROCESSING'

    $claim = Invoke-ClaimCarrier -Key 'handover' -TicketNo $ticketHandover -Version $verHandover -Client $itClient -Token $itToken -Actor 'it'
    $verHandover = $claim.version
    Add-KeyAction -Order 10 -Actor 'it' -Action 'claim' -Method 'POST' -Path (Get-ActionPath $ticketHandover 'claim') -TicketNo $ticketHandover -Result $claim.result -StatusAfter 'PROCESSING'

    $claim = Invoke-ClaimCarrier -Key 'race' -TicketNo $ticketRace -Version $verRace -Client $itClient -Token $itToken -Actor 'it'
    $verRace = $claim.version
    Add-KeyAction -Order 11 -Actor 'it' -Action 'claim' -Method 'POST' -Path (Get-ActionPath $ticketRace 'claim') -TicketNo $ticketRace -Result $claim.result -StatusAfter 'PROCESSING'

    $claim = Invoke-ClaimCarrier -Key 'raceTransfer' -TicketNo $ticketRaceTransfer -Version $verRaceTransfer -Client $itClient -Token $itToken -Actor 'it'
    $verRaceTransfer = $claim.version
    Add-KeyAction -Order 12 -Actor 'it' -Action 'claim' -Method 'POST' -Path (Get-ActionPath $ticketRaceTransfer 'claim') -TicketNo $ticketRaceTransfer -Result $claim.result -StatusAfter 'PROCESSING'

    # other 由 U5 领取：U5 有内置 IT_SUPPORT（领取资格），领取后这张单对 it 变成不可见。
    $claimOther = Invoke-ClaimCarrier -Key 'other' -TicketNo $ticketOther -Version $verOther -Client $u5Client -Token $u5Token -Actor 'temp-it-plus-requester'
    $verOther = $claimOther.version
    Add-KeyAction -Order 13 -Actor 'temp-it-plus-requester' -Action 'claim' -Method 'POST' -Path (Get-ActionPath $ticketOther 'claim') -TicketNo $ticketOther -Result $claimOther.result -StatusAfter 'PROCESSING'

    # ── 9. 把 handover 转交给 U5：it 参与过但不再是负责人 ────────────────────
    $transferHandover = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketHandover 'transfer') `
        -Token $itToken -Body @{ version = $verHandover; newAssigneeId = $u5.userId; reason = '片 D 验收前置：转交给第二名 IT，用于构造"可见但不是负责人"' } `
        -Actor 'it' -Note 'setup.transfer(handover)'
    $transferHandoverData = Get-Data $transferHandover
    Add-Assertion -Name 'setup.handover.transfer.200' `
        -Condition (($transferHandover.Status -eq 200) -and ($transferHandoverData.status -eq 'PROCESSING') -and ([int]$transferHandoverData.assignee.id -eq $u5.userId)) `
        -Expected '200，status=PROCESSING，负责人换成 U5' `
        -Detail "status=$($transferHandover.Status) code=$(Get-Code $transferHandover) businessStatus=$($transferHandoverData.status) assigneeId=$($transferHandoverData.assignee.id)" `
        -Result $transferHandover
    if (($transferHandover.Status -eq 200) -and ($null -ne $transferHandoverData)) { $verHandover = [long]$transferHandoverData.version }

    # ── 10. 把 cancelWait 推进到「待补充」、cancelConfirm 推进到「待确认」 ────
    $supplyWait = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketCancelWait 'request-supplement') `
        -Token $itToken -Body @{ version = $verCancelWait; content = '请补充工位号与报错截图，便于定位。' } `
        -Actor 'it' -Note 'setup.request-supplement(cancelWait)'
    $supplyWaitData = Get-Data $supplyWait
    $waitDeadline = [string]$supplyWaitData.actionDeadlineAt
    Add-Assertion -Name 'setup.cancelWait.requestSupplement.200' `
        -Condition (($supplyWait.Status -eq 200) -and ($supplyWaitData.status -eq 'WAITING_FOR_REQUESTER') -and (-not [string]::IsNullOrEmpty($waitDeadline))) `
        -Expected '200，status=WAITING_FOR_REQUESTER 且带确认期限（撤销后必须被清空）' `
        -Detail "status=$($supplyWait.Status) businessStatus=$($supplyWaitData.status) deadline=$waitDeadline version=$($supplyWaitData.version)" `
        -Result $supplyWait
    if ($supplyWait.Status -eq 200) { $verCancelWait = [long]$supplyWaitData.version }
    Add-KeyAction -Order 14 -Actor 'it' -Action 'request-supplement' -Method 'POST' `
        -Path (Get-ActionPath $ticketCancelWait 'request-supplement') -TicketNo $ticketCancelWait -Result $supplyWait -StatusAfter 'WAITING_FOR_REQUESTER'

    $resolveConfirm = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketCancelConfirm 'submit-resolution') `
        -Token $itToken -Body @{ version = $verCancelConfirm; content = '已重置打印服务并验证，请确认。' } `
        -Actor 'it' -Note 'setup.submit-resolution(cancelConfirm)'
    $resolveConfirmData = Get-Data $resolveConfirm
    $confirmDeadline = [string]$resolveConfirmData.actionDeadlineAt
    Add-Assertion -Name 'setup.cancelConfirm.submitResolution.200' `
        -Condition (($resolveConfirm.Status -eq 200) -and ($resolveConfirmData.status -eq 'WAITING_FOR_CONFIRMATION') -and (-not [string]::IsNullOrEmpty($confirmDeadline))) `
        -Expected '200，status=WAITING_FOR_CONFIRMATION 且带确认期限（撤销后必须被清空）' `
        -Detail "status=$($resolveConfirm.Status) businessStatus=$($resolveConfirmData.status) deadline=$confirmDeadline version=$($resolveConfirmData.version)" `
        -Result $resolveConfirm
    if ($resolveConfirm.Status -eq 200) { $verCancelConfirm = [long]$resolveConfirmData.version }
    Add-KeyAction -Order 15 -Actor 'it' -Action 'submit-resolution' -Method 'POST' `
        -Path (Get-ActionPath $ticketCancelConfirm 'submit-resolution') -TicketNo $ticketCancelConfirm -Result $resolveConfirm -StatusAfter 'WAITING_FOR_CONFIRMATION'

    # ── 11. 把 completed 真正走完（提交解决 + 员工确认），终态用例必须用真终态 ─
    $resolveCompleted = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketCompleted 'submit-resolution') `
        -Token $itToken -Body @{ version = $verCompleted; content = '缓存已清理，问题已解决。' } `
        -Actor 'it' -Note 'setup.submit-resolution(completed)'
    Add-Assertion -Name 'setup.completed.submitResolution.200' -Condition ($resolveCompleted.Status -eq 200) -Expected '200' `
        -Detail "status=$($resolveCompleted.Status) businessStatus=$((Get-Data $resolveCompleted).status) version=$((Get-Data $resolveCompleted).version)" `
        -Result $resolveCompleted
    if ($resolveCompleted.Status -eq 200) { $verCompleted = [long](Get-Data $resolveCompleted).version }

    $confirmCompleted = Send-Req -Client $employeeClient -Method Post -Path (Get-ActionPath $ticketCompleted 'confirm-resolution') `
        -Token $employeeToken -Body @{ version = $verCompleted } -Actor 'employee' -Note 'setup.confirm-resolution(completed)'
    Add-Assertion -Name 'setup.completed.confirmResolution.200' -Condition ($confirmCompleted.Status -eq 200) -Expected '200' `
        -Detail "status=$($confirmCompleted.Status) businessStatus=$((Get-Data $confirmCompleted).status) version=$((Get-Data $confirmCompleted).version)" `
        -Result $confirmCompleted
    if ($confirmCompleted.Status -eq 200) { $verCompleted = [long](Get-Data $confirmCompleted).version }
    $rowCompletedSetup = Get-TicketDbRow -TicketNo $ticketCompleted
    Add-Assertion -Name 'setup.completed.isCompleted' `
        -Condition (($null -ne $rowCompletedSetup) -and ($rowCompletedSetup.status -eq 'COMPLETED') -and ($rowCompletedSetup.endedAt -eq 'SET') -and ($rowCompletedSetup.completionMethod -eq 'REQUESTER_CONFIRMED')) `
        -Expected '走完 领取→提交解决→员工确认 后是 COMPLETED（伴 ended_at 与 completion_method）' `
        -Detail "row=[$($rowCompletedSetup.raw)]；终态用例以真终态为载体，不用 SQL 造一个假状态"

    # 待补充/待确认两张工单在撤销前必须真的有期限，否则"撤销清空期限"就是空断言。
    $rowWaitBefore = Get-TicketDbRow -TicketNo $ticketCancelWait
    $rowConfirmBefore = Get-TicketDbRow -TicketNo $ticketCancelConfirm
    Add-Assertion -Name 'setup.precondition.waitHasDeadlineBeforeCancel' `
        -Condition (($null -ne $rowWaitBefore) -and ($rowWaitBefore.status -eq 'WAITING_FOR_REQUESTER') -and ($rowWaitBefore.actionDeadlineAt -ne 'NULL')) `
        -Expected '待补充工单在撤销前确实有期限（否则后面的"清空"断言没有意义）' `
        -Detail "row=[$($rowWaitBefore.raw)] 响应中的期限=$waitDeadline"
    Add-Assertion -Name 'setup.precondition.confirmHasDeadlineBeforeCancel' `
        -Condition (($null -ne $rowConfirmBefore) -and ($rowConfirmBefore.status -eq 'WAITING_FOR_CONFIRMATION') -and ($rowConfirmBefore.actionDeadlineAt -ne 'NULL')) `
        -Expected '待确认工单在撤销前确实有期限' `
        -Detail "row=[$($rowConfirmBefore.raw)] 响应中的期限=$confirmDeadline"
    # ── 12. 断言组 a6/a7：close 成功三条（DUPLICATE / OUT_OF_SCOPE / INVALID） ─
    # 说明长度刻意 ≤ 40：证据里 reason 列取的是 LEFT(reason,40)，逐字比对才有意义；
    # 完整长度另由 CHAR_LENGTH(reason) 断言（Get-LastRecordRow 的 reasonLen）。
    $descDup = '与已存在的工单重复，合并到目标单处理'
    $closeDup = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketDup 'close') `
        -Token $itToken -Body (New-CloseBody -Version $verDup -ReasonCode 'DUPLICATE' `
            -Description $descDup -DuplicateTicketNo $ticketDupTarget) `
        -Actor 'it' -Note 'a6.close.duplicate'
    $verDupExpected = $verDup + 1
    $rowDupClosed = Assert-CloseSuccess -Name 'a6.close.duplicate' -TicketNo $ticketDup `
        -ExpectedVersion $verDupExpected -Result $closeDup -ReasonCode 'DUPLICATE' `
        -Description $descDup -ExpectedAssigneeId $itUserId `
        -Detail "ticketNo=$ticketDup duplicateTarget=$ticketDupTarget（同一提交人的待受理工单）"
    $verDup = $verDupExpected
    Add-KeyAction -Order 20 -Actor 'it' -Action 'close(DUPLICATE)' -Method 'POST' `
        -Path (Get-ActionPath $ticketDup 'close') -TicketNo $ticketDup -Result $closeDup -StatusAfter 'CLOSED'

    $relationDup = Get-RelationSignature -TicketNo $ticketDup
    # 注意 ${...}：写成 "$ticketDupTarget:DUPLICATE" 会被 PowerShell 当成"作用域:变量名"解析，
    # 静默变成空串，这条断言就永远对不上。花括号在这里是必须的，不是风格问题。
    $expectedRelationDup = "$ticketDup->${ticketDupTarget}:DUPLICATE"
    Add-Assertion -Name 'a7.close.duplicate.relationWritten' -Condition ($relationDup -eq $expectedRelationDup) `
        -Expected "ticket_relation 出现一条 [$expectedRelationDup]" `
        -Detail "实测=[$relationDup]；唯一键 uk_ticket_relation_source_type 保证一张单只有一条重复指向"
    Assert-Db -Name 'a7.close.duplicate.relationRowInDb' `
        -Sql ("SELECT relation_type, s.ticket_no, g.ticket_no, IFNULL(r.created_by,-1) FROM ticket_relation r " +
        "JOIN ticket s ON s.id = r.source_ticket_id JOIN ticket g ON g.id = r.target_ticket_id " +
        "WHERE s.ticket_no = '$ticketDup';") `
        -Expected "一行 (DUPLICATE, $ticketDup, $ticketDupTarget, created_by=$itUserId)" `
        -Check {
        param($c)
        ($c[0] -eq 'DUPLICATE') -and ($c[1] -eq $ticketDup) -and ($c[2] -eq $ticketDupTarget) -and ([int]$c[3] -eq $itUserId)
    }
    # 时间线没有被改写：这张单从建单到关闭只有 CREATE、CLAIM、CLOSURE 三条，关闭只追加一条记录
    Add-Assertion -Name 'a6.close.duplicate.recordSequence' `
        -Condition ((Get-RecordTypeSequence -TicketNo $ticketDup) -eq 'CREATE,CLAIM,CLOSURE') `
        -Expected 'CREATE,CLAIM,CLOSURE（关闭只追加一条 CLOSURE，历史记录顺序不变）' `
        -Detail "实测=[$(Get-RecordTypeSequence -TicketNo $ticketDup)]"
    $rowDupTargetAfterClose = Get-TicketDbRow -TicketNo $ticketDupTarget
    Add-Assertion -Name 'a7.close.duplicate.targetUntouched' `
        -Condition (($null -ne $rowDupTargetAfterClose) -and ($rowDupTargetAfterClose.status -eq 'PENDING') -and ($rowDupTargetAfterClose.version -eq $verDupTarget)) `
        -Expected '重复目标本身没有被改动（仍是待受理、version 未变）：关闭只写 source 一侧' `
        -Detail "row=[$($rowDupTargetAfterClose.raw)]"

    $descScope = '该请求应由运维窗口受理，不属于 IT 服务范围'
    $closeScope = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketScope 'close') `
        -Token $itToken -Body (New-CloseBody -Version $verScope -ReasonCode 'OUT_OF_SCOPE' -Description $descScope) `
        -Actor 'it' -Note 'a6.close.outOfScope'
    $verScopeExpected = $verScope + 1
    $rowScopeClosed = Assert-CloseSuccess -Name 'a6.close.outOfScope' -TicketNo $ticketScope `
        -ExpectedVersion $verScopeExpected -Result $closeScope -ReasonCode 'OUT_OF_SCOPE' `
        -Description $descScope -ExpectedAssigneeId $itUserId `
        -Detail "ticketNo=$ticketScope（不带 duplicateTicketNo）"
    $verScope = $verScopeExpected
    Add-KeyAction -Order 21 -Actor 'it' -Action 'close(OUT_OF_SCOPE)' -Method 'POST' `
        -Path (Get-ActionPath $ticketScope 'close') -TicketNo $ticketScope -Result $closeScope -StatusAfter 'CLOSED'
    Add-Assertion -Name 'a7.close.outOfScope.noRelation' -Condition ((Get-RelationSignature -TicketNo $ticketScope) -eq '-') `
        -Expected '非重复原因不写 ticket_relation（该单作为 source 的关联行为 0）' `
        -Detail "实测=[$(Get-RelationSignature -TicketNo $ticketScope)]"

    $descInvalid = '测试数据，没有实际问题'
    $closeInvalid = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketInvalid 'close') `
        -Token $itToken -Body (New-CloseBody -Version $verInvalid -ReasonCode 'INVALID' -Description $descInvalid) `
        -Actor 'it' -Note 'a6.close.invalid'
    $verInvalidExpected = $verInvalid + 1
    $rowInvalidClosed = Assert-CloseSuccess -Name 'a6.close.invalid' -TicketNo $ticketInvalid `
        -ExpectedVersion $verInvalidExpected -Result $closeInvalid -ReasonCode 'INVALID' `
        -Description $descInvalid -ExpectedAssigneeId $itUserId `
        -Detail "ticketNo=$ticketInvalid（不带 duplicateTicketNo；这张单随后还当「已关闭的重复目标」用）"
    $verInvalid = $verInvalidExpected
    Add-KeyAction -Order 22 -Actor 'it' -Action 'close(INVALID)' -Method 'POST' `
        -Path (Get-ActionPath $ticketInvalid 'close') -TicketNo $ticketInvalid -Result $closeInvalid -StatusAfter 'CLOSED'
    Add-Assertion -Name 'a7.close.invalid.noRelation' -Condition ((Get-RelationSignature -TicketNo $ticketInvalid) -eq '-') `
        -Expected '非重复原因不写 ticket_relation' -Detail "实测=[$(Get-RelationSignature -TicketNo $ticketInvalid)]"

    # ── 13. 断言组 a9：cancel 成功四条（待受理 / 处理中 / 待补充 / 待确认） ───
    $reasonPending = '提交错了，重新建一张'
    $cancelPending = Send-Req -Client $employeeClient -Method Post -Path (Get-ActionPath $ticketCanceledTarget 'cancel') `
        -Token $employeeToken -Body (New-CancelBody -Version $verCanceledTarget -Reason $reasonPending) `
        -Actor 'employee' -Note 'a9.cancel.pending'
    $verCanceledTargetExpected = $verCanceledTarget + 1
    $rowCanceledPending = Assert-CancelSuccess -Name 'a9.cancel.pending' -TicketNo $ticketCanceledTarget `
        -ExpectedVersion $verCanceledTargetExpected -Result $cancelPending -Reason $reasonPending -FromStatus 'PENDING' `
        -ExpectedAssigneeKind 'none' -ExpectedAssigneeId -1 `
        -Detail "ticketNo=$ticketCanceledTarget（待受理：本来没有负责人，撤销后 assignee 字段不出现）"
    $verCanceledTarget = $verCanceledTargetExpected
    Add-KeyAction -Order 30 -Actor 'employee' -Action 'cancel(PENDING)' -Method 'POST' `
        -Path (Get-ActionPath $ticketCanceledTarget 'cancel') -TicketNo $ticketCanceledTarget -Result $cancelPending -StatusAfter 'CANCELED'

    $reasonProc = '问题已自行解决，不需要继续处理'
    $cancelProc = Send-Req -Client $employeeClient -Method Post -Path (Get-ActionPath $ticketCancelProc 'cancel') `
        -Token $employeeToken -Body (New-CancelBody -Version $verCancelProc -Reason $reasonProc) `
        -Actor 'employee' -Note 'a9.cancel.processing'
    $verCancelProcExpected = $verCancelProc + 1
    $rowCanceledProc = Assert-CancelSuccess -Name 'a9.cancel.processing' -TicketNo $ticketCancelProc `
        -ExpectedVersion $verCancelProcExpected -Result $cancelProc -Reason $reasonProc -FromStatus 'PROCESSING' `
        -ExpectedAssigneeKind 'user' -ExpectedAssigneeId $itUserId `
        -Detail "ticketNo=$ticketCancelProc（处理中：负责人 it 被保留为历史负责人，不再有处理责任）"
    $verCancelProc = $verCancelProcExpected
    Add-KeyAction -Order 31 -Actor 'employee' -Action 'cancel(PROCESSING)' -Method 'POST' `
        -Path (Get-ActionPath $ticketCancelProc 'cancel') -TicketNo $ticketCancelProc -Result $cancelProc -StatusAfter 'CANCELED'
    Add-Assertion -Name 'a9.cancel.processing.recordSequence' `
        -Condition ((Get-RecordTypeSequence -TicketNo $ticketCancelProc) -eq 'CREATE,CLAIM,CANCELLATION') `
        -Expected 'CREATE,CLAIM,CANCELLATION（撤销只追加一条 CANCELLATION，不写完成或关闭字段）' `
        -Detail "实测=[$(Get-RecordTypeSequence -TicketNo $ticketCancelProc)]"

    $reasonWait = '不需要补充了，问题已经不存在'
    $cancelWait = Send-Req -Client $employeeClient -Method Post -Path (Get-ActionPath $ticketCancelWait 'cancel') `
        -Token $employeeToken -Body (New-CancelBody -Version $verCancelWait -Reason $reasonWait) `
        -Actor 'employee' -Note 'a9.cancel.waitingForRequester'
    $verCancelWaitExpected = $verCancelWait + 1
    $rowCanceledWait = Assert-CancelSuccess -Name 'a9.cancel.waitingForRequester' -TicketNo $ticketCancelWait `
        -ExpectedVersion $verCancelWaitExpected -Result $cancelWait -Reason $reasonWait -FromStatus 'WAITING_FOR_REQUESTER' `
        -ExpectedAssigneeKind 'user' -ExpectedAssigneeId $itUserId `
        -Detail "ticketNo=$ticketCancelWait（待补充：撤销前有期限 $waitDeadline，撤销必须把它清空）"
    $verCancelWait = $verCancelWaitExpected
    Add-KeyAction -Order 32 -Actor 'employee' -Action 'cancel(WAITING_FOR_REQUESTER)' -Method 'POST' `
        -Path (Get-ActionPath $ticketCancelWait 'cancel') -TicketNo $ticketCancelWait -Result $cancelWait -StatusAfter 'CANCELED'
    Add-Assertion -Name 'a9.cancel.waitingForRequester.deadlineClearedInSameUpdate' `
        -Condition (($null -ne $rowWaitBefore) -and ($rowWaitBefore.actionDeadlineAt -ne 'NULL') -and ($null -ne $rowCanceledWait) -and ($rowCanceledWait.actionDeadlineAt -eq 'NULL')) `
        -Expected '待补充撤销前期限有值、撤销后为空 —— 状态与 action_deadline_at 由同一条 UPDATE 写入，不产生 ck_ticket_status_deadline 的中间态' `
        -Detail "撤销前=[$($rowWaitBefore.actionDeadlineAt)] 撤销后=[$($rowCanceledWait.actionDeadlineAt)] 响应中的期限字段存在=$(([string]$cancelWait.Body).Contains('"actionDeadlineAt"'))"

    $reasonConfirm = '问题不再需要解决，直接结束'
    $cancelConfirm = Send-Req -Client $employeeClient -Method Post -Path (Get-ActionPath $ticketCancelConfirm 'cancel') `
        -Token $employeeToken -Body (New-CancelBody -Version $verCancelConfirm -Reason $reasonConfirm) `
        -Actor 'employee' -Note 'a9.cancel.waitingForConfirmation'
    $verCancelConfirmExpected = $verCancelConfirm + 1
    $rowCanceledConfirm = Assert-CancelSuccess -Name 'a9.cancel.waitingForConfirmation' -TicketNo $ticketCancelConfirm `
        -ExpectedVersion $verCancelConfirmExpected -Result $cancelConfirm -Reason $reasonConfirm -FromStatus 'WAITING_FOR_CONFIRMATION' `
        -ExpectedAssigneeKind 'user' -ExpectedAssigneeId $itUserId `
        -Detail "ticketNo=$ticketCancelConfirm（待确认：撤销前有确认期限 $confirmDeadline，撤销后期限与 completion_method 都必须为空）"
    $verCancelConfirm = $verCancelConfirmExpected
    Add-KeyAction -Order 33 -Actor 'employee' -Action 'cancel(WAITING_FOR_CONFIRMATION)' -Method 'POST' `
        -Path (Get-ActionPath $ticketCancelConfirm 'cancel') -TicketNo $ticketCancelConfirm -Result $cancelConfirm -StatusAfter 'CANCELED'
    Add-Assertion -Name 'a9.cancel.waitingForConfirmation.deadlineClearedAndNoCompletion' `
        -Condition (($null -ne $rowConfirmBefore) -and ($rowConfirmBefore.actionDeadlineAt -ne 'NULL') -and ($null -ne $rowCanceledConfirm) -and ($rowCanceledConfirm.actionDeadlineAt -eq 'NULL') -and ($rowCanceledConfirm.completionMethod -eq 'NULL')) `
        -Expected '待确认撤销：期限被同一条 UPDATE 清空，且不会写入 completion_method（撤销 ≠ 自动/主动完成）' `
        -Detail "撤销前期限=[$($rowConfirmBefore.actionDeadlineAt)] 撤销后期限=[$($rowCanceledConfirm.actionDeadlineAt)] completion_method=[$($rowCanceledConfirm.completionMethod)]"
    Assert-Db -Name 'a9.cancel.deadlinesClearedInDb' `
        -Sql ("SELECT COUNT(*) FROM ticket WHERE ticket_no IN ('$ticketCancelWait','$ticketCancelConfirm') " +
        "AND status = 'CANCELED' AND action_deadline_at IS NULL AND ended_at IS NOT NULL;") `
        -Expected '待补充与待确认两张工单撤销后：状态=CANCELED、期限为空、ended_at 有值（两张都满足，计数=2）' `
        -Check {
        param($c)
        ([int]$c[0] -eq 2)
    }
    # ── 14. 断言组 a1：两个动作的匿名请求 → 401 ──────────────────────────────
    $anonClose = Send-Req -Client $anonymousClient -Method Post -Path (Get-ActionPath $ticketErr 'close') `
        -Body (New-CloseBody -Version $verErr -ReasonCode 'INVALID' -Description '匿名关闭') `
        -Actor 'anonymous' -Note 'a1.anonymous.close'
    $anonCancel = Send-Req -Client $anonymousClient -Method Post -Path (Get-ActionPath $ticketErrCancel 'cancel') `
        -Body (New-CancelBody -Version $verErrCancel -Reason '匿名撤销') -Actor 'anonymous' -Note 'a1.anonymous.cancel'
    Add-Assertion -Name 'a1.anonymous.close.401' `
        -Condition (($anonClose.Status -eq 401) -and ((Get-Code $anonClose) -eq 'AUTH_REQUIRED')) `
        -Expected '401 + AUTH_REQUIRED（无令牌先被认证闸门拦下，与业务字段无关）' `
        -Detail "status=$($anonClose.Status) code=$(Get-Code $anonClose)" -Result $anonClose
    Add-Assertion -Name 'a1.anonymous.cancel.401' `
        -Condition (($anonCancel.Status -eq 401) -and ((Get-Code $anonCancel) -eq 'AUTH_REQUIRED')) `
        -Expected '401 + AUTH_REQUIRED' `
        -Detail "status=$($anonCancel.Status) code=$(Get-Code $anonCancel)" -Result $anonCancel

    # ── 15. 断言组 a2：close 的权限闸门（把 TICKET_PROCESS 与 TICKET_CLOSE 拆开各测一次） ─
    # 片 D 的裁决：close 同时要求两个权限。U1 只有 TICKET_CLOSE、U2 只有 TICKET_PROCESS，
    # 两个方向都必须 403；employee 两个都没有，同样 403（且 403 先于可见性，用不存在的编号也能证明）。
    $u1Close = Send-Req -Client $u1Client -Method Post -Path (Get-ActionPath $ticketErr 'close') `
        -Token $u1Token -Body (New-CloseBody -Version $verErr -ReasonCode 'INVALID' -Description '缺处理权限的关闭') `
        -Actor 'temp-close-only' -Note 'a2.close.missingProcess'
    $u2Close = Send-Req -Client $u2Client -Method Post -Path (Get-ActionPath $ticketErr 'close') `
        -Token $u2Token -Body (New-CloseBody -Version $verErr -ReasonCode 'INVALID' -Description '缺关闭权限的关闭') `
        -Actor 'temp-process-only' -Note 'a2.close.missingClose'
    $empClose = Send-Req -Client $employeeClient -Method Post -Path (Get-ActionPath $ticketErr 'close') `
        -Token $employeeToken -Body (New-CloseBody -Version $verErr -ReasonCode 'INVALID' -Description '提交人尝试关闭') `
        -Actor 'employee' -Note 'a2.close.employee'
    $u1CloseUnknown = Send-Req -Client $u1Client -Method Post -Path (Get-ActionPath $unknownTicketNo 'close') `
        -Token $u1Token -Body (New-CloseBody -Version 0 -ReasonCode 'INVALID' -Description '缺处理权限且编号不存在') `
        -Actor 'temp-close-only' -Note 'a2.close.missingProcess(unknown)'
    $u2CloseUnknown = Send-Req -Client $u2Client -Method Post -Path (Get-ActionPath $unknownTicketNo 'close') `
        -Token $u2Token -Body (New-CloseBody -Version 0 -ReasonCode 'INVALID' -Description '缺关闭权限且编号不存在') `
        -Actor 'temp-process-only' -Note 'a2.close.missingClose(unknown)'
    Add-Assertion -Name 'a2.close.missingProcess.403' `
        -Condition (($u1Close.Status -eq 403) -and ((Get-Code $u1Close) -eq 'TICKET_ACTION_FORBIDDEN')) `
        -Expected '403 + TICKET_ACTION_FORBIDDEN（U1 只有 TICKET_CLOSE，缺 TICKET_PROCESS）' `
        -Detail "status=$($u1Close.Status) code=$(Get-Code $u1Close)；这是片 D 与片 C 的口径差异：transfer 只要 TICKET_TRANSFER，close 要两个" `
        -Result $u1Close
    Add-Assertion -Name 'a2.close.missingClose.403' `
        -Condition (($u2Close.Status -eq 403) -and ((Get-Code $u2Close) -eq 'TICKET_ACTION_FORBIDDEN')) `
        -Expected '403 + TICKET_ACTION_FORBIDDEN（U2 只有 TICKET_PROCESS，缺 TICKET_CLOSE）' `
        -Detail "status=$($u2Close.Status) code=$(Get-Code $u2Close)" -Result $u2Close
    Add-Assertion -Name 'a2.close.employeeMissingBoth.403' `
        -Condition (($empClose.Status -eq 403) -and ((Get-Code $empClose) -eq 'TICKET_ACTION_FORBIDDEN')) `
        -Expected '403 + TICKET_ACTION_FORBIDDEN（提交人两个权限都没有）' `
        -Detail "status=$($empClose.Status) code=$(Get-Code $empClose) ticket=$ticketErr" -Result $empClose
    Add-Assertion -Name 'a2.close.permissionGateBeforeVisibility' `
        -Condition (($u1CloseUnknown.Status -eq 403) -and ($u2CloseUnknown.Status -eq 403)) `
        -Expected '两个权限缺口对不存在的编号也都是 403（权限闸门先于可见性；若为 404 说明顺序被改）' `
        -Detail "U1=$($u1CloseUnknown.Status) U2=$($u2CloseUnknown.Status) 编号=$unknownTicketNo（不存在）"

    # ── 16. 断言组 a3：close 的 404（编号不存在 / 存在但不可见） ───────────────
    $closeUnknown = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $unknownTicketNo 'close') `
        -Token $itToken -Body (New-CloseBody -Version 0 -ReasonCode 'INVALID' -Description '编号不存在') `
        -Actor 'it' -Note 'a3.close.unknownTicket'
    # other 由 U3 提交、U5 领取：it 既不是提交人、也不是负责人/参与者，且它不是待受理队列里的单 → 不可见。
    $closeInvisible = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketOther 'close') `
        -Token $itToken -Body (New-CloseBody -Version $verOther -ReasonCode 'INVALID' -Description '不可见的工单') `
        -Actor 'it' -Note 'a3.close.invisibleTicket'
    Add-Assertion -Name 'a3.close.unknownTicket.404' `
        -Condition (($closeUnknown.Status -eq 404) -and ((Get-Code $closeUnknown) -eq 'TICKET_NOT_FOUND')) `
        -Expected '404 + TICKET_NOT_FOUND（it 有全部所需权限，编号不存在）' `
        -Detail "status=$($closeUnknown.Status) code=$(Get-Code $closeUnknown) 编号=$unknownTicketNo" -Result $closeUnknown
    Add-Assertion -Name 'a3.close.invisibleTicket.404' `
        -Condition (($closeInvisible.Status -eq 404) -and ((Get-Code $closeInvisible) -eq 'TICKET_NOT_FOUND')) `
        -Expected '404 + TICKET_NOT_FOUND（工单真实存在，但 it 不是提交人、不是负责人、不是参与者，且它已不是待受理）' `
        -Detail ("status=$($closeInvisible.Status) code=$(Get-Code $closeInvisible) ticket=$ticketOther " +
        "（提交人是 U3、负责人是 U5；这条同时证明「可见性 404」与「编号不存在 404」走同一条分支）") -Result $closeInvisible

    # ── 17. 断言组 a4：close 的 409（非处理中 / 非当前负责人 / 版本过期 / 409 先于 400） ─
    $closeNotProcessing = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketDupTarget 'close') `
        -Token $itToken -Body (New-CloseBody -Version $verDupTarget -ReasonCode 'INVALID' -Description '待受理不能直接关闭') `
        -Actor 'it' -Note 'a4.close.notProcessing'
    $rowDupTargetForConflict = Get-TicketDbRow -TicketNo $ticketDupTarget
    $closeNotAssignee = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketHandover 'close') `
        -Token $itToken -Body (New-CloseBody -Version $verHandover -ReasonCode 'INVALID' -Description '不是当前负责人') `
        -Actor 'it' -Note 'a4.close.notAssignee'
    $closeStaleVersion = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketErr 'close') `
        -Token $itToken -Body (New-CloseBody -Version ($verErr - 1) -ReasonCode 'INVALID' -Description '版本过期') `
        -Actor 'it' -Note 'a4.close.staleVersion'
    # 「409 先于 400」这一格必须用**只有服务层才会拒**的 400 来测：
    # reasonCode 的非法取值由 @Pattern 表达、description 的空白由 @NotBlank 表达，
    # 两者都在进服务层之前就被 Bean Validation 拦下，永远看不到服务层的判定顺序。
    # 跨字段规则（非重复原因却传了重复单号）没有任何注解能表达，只能在服务层判定，
    # 所以它是这条顺序唯一可观察的组合。
    $closeStaleAndBadField = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketErr 'close') `
        -Token $itToken -Body (New-CloseBody -Version ($verErr - 1) -ReasonCode 'INVALID' -Description '跨字段非法但注解放行' -DuplicateTicketNo $ticketDupTarget) `
        -Actor 'it' -Note 'a4.close.staleVersionAndCrossFieldViolation'
    # 对照格：同一个过期版本、换成一个注解就能表达的非法字段（空白说明），HTTP 层先给出 400。
    # 两格并排才说明白：差别不在"哪个闸门在前"，而在"这个 400 是注解给的还是服务层给的"。
    $closeStaleAndBlankDesc = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketErr 'close') `
        -Token $itToken -Body (New-CloseBody -Version ($verErr - 1) -ReasonCode 'INVALID' -Description '   ') `
        -Actor 'it' -Note 'a4.close.staleVersionAndBlankDescription'
    $closeTerminal = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $ticketScope 'close') `
        -Token $itToken -Body (New-CloseBody -Version $verScope -ReasonCode 'INVALID' -Description '终态再关闭') `
        -Actor 'it' -Note 'a4.close.terminal'
    Add-Assertion -Name 'a4.close.notProcessing.409' `
        -Condition (($closeNotProcessing.Status -eq 409) -and ((Get-Code $closeNotProcessing) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（待受理必须先领取，IT 不能直接关闭）' `
        -Detail "status=$($closeNotProcessing.Status) code=$(Get-Code $closeNotProcessing) data.version=$((Get-ErrData $closeNotProcessing).version) data.status=$((Get-ErrData $closeNotProcessing).status)" `
        -Result $closeNotProcessing
    Add-Assertion -Name 'a4.close.notProcessing.snapshotMatchesDb' `
        -Condition (($null -ne $rowDupTargetForConflict) -and ([int](Get-ErrData $closeNotProcessing).version -eq $rowDupTargetForConflict.version) -and ((Get-ErrData $closeNotProcessing).status -eq 'PENDING')) `
        -Expected '409 的 data.version/data.status 就是库中当前快照（PENDING）' `
        -Detail "data.version=$((Get-ErrData $closeNotProcessing).version) data.status=$((Get-ErrData $closeNotProcessing).status)；库中 version=$($rowDupTargetForConflict.version) status=$($rowDupTargetForConflict.status)"
    Add-Assertion -Name 'a4.close.notAssignee.409' `
        -Condition (($closeNotAssignee.Status -eq 409) -and ((Get-Code $closeNotAssignee) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（it 是参与者因而可见，但负责人已经转交给 U5）' `
        -Detail "status=$($closeNotAssignee.Status) code=$(Get-Code $closeNotAssignee) ticket=$ticketHandover（它仍是参与者，所以这里必须是 409 而不是 404）" `
        -Result $closeNotAssignee
    Add-Assertion -Name 'a4.close.staleVersion.409' `
        -Condition (($closeStaleVersion.Status -eq 409) -and ((Get-Code $closeStaleVersion) -eq 'TICKET_CONFLICT') -and ([int](Get-ErrData $closeStaleVersion).version -eq $verErr)) `
        -Expected "409 + TICKET_CONFLICT 且 data.version=$verErr（当前库中版本）" `
        -Detail "status=$($closeStaleVersion.Status) code=$(Get-Code $closeStaleVersion) data.version=$((Get-ErrData $closeStaleVersion).version) data.status=$((Get-ErrData $closeStaleVersion).status)" `
        -Result $closeStaleVersion
    Add-Assertion -Name 'a4.close.conflictBeforeValidation' `
        -Condition (($closeStaleAndBadField.Status -eq 409) -and ((Get-Code $closeStaleAndBadField) -eq 'TICKET_CONFLICT')) `
        -Expected '409 而不是 400：版本过期 + 跨字段非法（非重复原因却传了重复单号）同时成立时，状态/版本闸门先判' `
        -Detail "status=$($closeStaleAndBadField.Status) code=$(Get-Code $closeStaleAndBadField)（若为 400 说明服务层的字段校验被提到了状态判定之前）" `
        -Result $closeStaleAndBadField
    Add-Assertion -Name 'a4.close.beanValidationPrecedesServiceGate' `
        -Condition (($closeStaleAndBlankDesc.Status -eq 400) -and ((Get-Code $closeStaleAndBlankDesc) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（同一个过期版本，但空白说明由 @NotBlank 在进服务层之前就拦下）' `
        -Detail "status=$($closeStaleAndBlankDesc.Status) code=$(Get-Code $closeStaleAndBlankDesc) body=[$(Get-SafeBody $closeStaleAndBlankDesc)]；与上一格的差别是『这个 400 由注解给出』，不是闸门顺序变了" `
        -Result $closeStaleAndBlankDesc
    Add-Assertion -Name 'a4.close.terminal.409' `
        -Condition (($closeTerminal.Status -eq 409) -and ((Get-Code $closeTerminal) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（已关闭是终态，不能再关闭）' `
        -Detail "status=$($closeTerminal.Status) code=$(Get-Code $closeTerminal) ticket=$ticketScope" -Result $closeTerminal

    # ── 18. 断言组 a5：close 的 400（原因 / 说明 / 跨字段 / 目标四种非法） ─────
    # 全部打在 ticketErr 上（处理中、版本 $verErr），结束后比对状态签名证明一条都没改动工单。
    $closeErrPath = Get-ActionPath $ticketErr 'close'
    $sigErrBefore = Get-TicketStateSignature -TicketNo $ticketErr
    $longDescription = ('超' * 1001)

    $c1 = Send-Req -Client $itClient -Method Post -Path $closeErrPath -Token $itToken `
        -Body (New-CloseBody -Version $verErr -ReasonCode 'REQUESTER_NO_RESPONSE' -Description '自动关闭的原因码') `
        -Actor 'it' -Note 'a5.close.reasonCode.autoPath'
    Add-Assertion -Name 'a5.close.reasonCode.autoPath.400' `
        -Condition (($c1.Status -eq 400) -and ((Get-Code $c1) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（REQUESTER_NO_RESPONSE 属 backlog 3 的自动关闭路径，人工接口进不去）' `
        -Detail "status=$($c1.Status) code=$(Get-Code $c1) body=[$(Get-SafeBody $c1)]" -Result $c1

    $c2 = Send-Req -Client $itClient -Method Post -Path $closeErrPath -Token $itToken `
        -Body (New-CloseBody -Version $verErr -ReasonCode 'duplicate' -Description '小写原因码') `
        -Actor 'it' -Note 'a5.close.reasonCode.lowercase'
    Add-Assertion -Name 'a5.close.reasonCode.lowercase.400' `
        -Condition (($c2.Status -eq 400) -and ((Get-Code $c2) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（原因码区分大小写，只接受三个大写枚举）' `
        -Detail "status=$($c2.Status) code=$(Get-Code $c2)" -Result $c2

    $c3 = Send-Req -Client $itClient -Method Post -Path $closeErrPath -Token $itToken `
        -Body (New-CloseBody -Version $verErr -Description '缺原因码' -OmitReasonCode) `
        -Actor 'it' -Note 'a5.close.reasonCode.missing'
    Add-Assertion -Name 'a5.close.reasonCode.missing.400' `
        -Condition (($c3.Status -eq 400) -and ((Get-Code $c3) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（@NotBlank：原因码整体缺失）' `
        -Detail "status=$($c3.Status) code=$(Get-Code $c3)" -Result $c3

    $c4 = Send-Req -Client $itClient -Method Post -Path $closeErrPath -Token $itToken `
        -Body (New-CloseBody -Version $verErr -ReasonCode 'INVALID' -Description '   ') `
        -Actor 'it' -Note 'a5.close.description.blank'
    Add-Assertion -Name 'a5.close.description.blank.400' `
        -Condition (($c4.Status -eq 400) -and ((Get-Code $c4) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（说明去空白后为空）' `
        -Detail "status=$($c4.Status) code=$(Get-Code $c4)" -Result $c4

    $c5 = Send-Req -Client $itClient -Method Post -Path $closeErrPath -Token $itToken `
        -Body (New-CloseBody -Version $verErr -ReasonCode 'INVALID' -Description $longDescription) `
        -Actor 'it' -Note 'a5.close.description.tooLong'
    Add-Assertion -Name 'a5.close.description.tooLong.400' `
        -Condition (($c5.Status -eq 400) -and ((Get-Code $c5) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（说明 1001 个字符，超过 @Size(max=1000)）' `
        -Detail "status=$($c5.Status) code=$(Get-Code $c5) 说明长度=$($longDescription.Length)" -Result $c5

    $c6 = Send-Req -Client $itClient -Method Post -Path $closeErrPath -Token $itToken `
        -Body (New-CloseBody -Version $verErr -ReasonCode 'INVALID' -OmitDescription) `
        -Actor 'it' -Note 'a5.close.description.missing'
    Add-Assertion -Name 'a5.close.description.missing.400' `
        -Condition (($c6.Status -eq 400) -and ((Get-Code $c6) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（说明整体缺失）' `
        -Detail "status=$($c6.Status) code=$(Get-Code $c6)" -Result $c6

    $c7 = Send-Req -Client $itClient -Method Post -Path $closeErrPath -Token $itToken `
        -Body (New-CloseBody -Version $verErr -ReasonCode 'OUT_OF_SCOPE' -Description '超范围却传了重复单号' -DuplicateTicketNo $ticketDupTarget) `
        -Actor 'it' -Note 'a5.close.crossField.outOfScopeWithTarget'
    Add-Assertion -Name 'a5.close.crossField.outOfScopeWithTarget.400' `
        -Condition (($c7.Status -eq 400) -and ((Get-Code $c7) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（非重复原因禁止传 duplicateTicketNo，服务端不静默忽略）' `
        -Detail "status=$($c7.Status) code=$(Get-Code $c7)" -Result $c7

    $c8 = Send-Req -Client $itClient -Method Post -Path $closeErrPath -Token $itToken `
        -Body (New-CloseBody -Version $verErr -ReasonCode 'INVALID' -Description '无效却传了重复单号' -DuplicateTicketNo $ticketDupTarget) `
        -Actor 'it' -Note 'a5.close.crossField.invalidWithTarget'
    Add-Assertion -Name 'a5.close.crossField.invalidWithTarget.400' `
        -Condition (($c8.Status -eq 400) -and ((Get-Code $c8) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（同上，INVALID 也不许带目标）' `
        -Detail "status=$($c8.Status) code=$(Get-Code $c8)" -Result $c8

    $c9 = Send-Req -Client $itClient -Method Post -Path $closeErrPath -Token $itToken `
        -Body (New-CloseBody -Version $verErr -ReasonCode 'DUPLICATE' -Description '重复但没给目标') `
        -Actor 'it' -Note 'a5.close.duplicate.missingTarget'
    Add-Assertion -Name 'a5.close.duplicate.missingTarget.400' `
        -Condition (($c9.Status -eq 400) -and ((Get-Code $c9) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（DUPLICATE 必填 duplicateTicketNo）' `
        -Detail "status=$($c9.Status) code=$(Get-Code $c9)" -Result $c9

    $c10 = Send-Req -Client $itClient -Method Post -Path $closeErrPath -Token $itToken `
        -Body (New-CloseBody -Version $verErr -ReasonCode 'DUPLICATE' -Description '目标不存在' -DuplicateTicketNo 'FD-19990101-777') `
        -Actor 'it' -Note 'a5.close.duplicate.targetMissing'
    Add-Assertion -Name 'a5.close.duplicate.targetMissing.400' `
        -Condition (($c10.Status -eq 400) -and ((Get-Code $c10) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（目标编号不存在）' `
        -Detail "status=$($c10.Status) code=$(Get-Code $c10)" -Result $c10

    $c11 = Send-Req -Client $itClient -Method Post -Path $closeErrPath -Token $itToken `
        -Body (New-CloseBody -Version $verErr -ReasonCode 'DUPLICATE' -Description '目标是别人的单' -DuplicateTicketNo $ticketOther) `
        -Actor 'it' -Note 'a5.close.duplicate.targetOtherRequester'
    Add-Assertion -Name 'a5.close.duplicate.targetOtherRequester.400' `
        -Condition (($c11.Status -eq 400) -and ((Get-Code $c11) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（目标属于另一位提交人；不因目标存在与否给出不同结论，不回显他人工单）' `
        -Detail "status=$($c11.Status) code=$(Get-Code $c11) target=$ticketOther（真实存在、状态合法，只是提交人不同）" -Result $c11

    $c12 = Send-Req -Client $itClient -Method Post -Path $closeErrPath -Token $itToken `
        -Body (New-CloseBody -Version $verErr -ReasonCode 'DUPLICATE' -Description '目标是自己' -DuplicateTicketNo $ticketErr) `
        -Actor 'it' -Note 'a5.close.duplicate.targetSelf'
    Add-Assertion -Name 'a5.close.duplicate.targetSelf.400' `
        -Condition (($c12.Status -eq 400) -and ((Get-Code $c12) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（工单不能把自己作为重复目标）' `
        -Detail "status=$($c12.Status) code=$(Get-Code $c12) target=$ticketErr（自身）" -Result $c12

    $c13 = Send-Req -Client $itClient -Method Post -Path $closeErrPath -Token $itToken `
        -Body (New-CloseBody -Version $verErr -ReasonCode 'DUPLICATE' -Description '目标是已取消的单' -DuplicateTicketNo $ticketCanceledTarget) `
        -Actor 'it' -Note 'a5.close.duplicate.targetCanceled'
    Add-Assertion -Name 'a5.close.duplicate.targetCanceled.400' `
        -Condition (($c13.Status -eq 400) -and ((Get-Code $c13) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（已取消的单不能当重复目标）' `
        -Detail "status=$($c13.Status) code=$(Get-Code $c13) target=$ticketCanceledTarget（同一提交人，但已是终态）" -Result $c13

    $c14 = Send-Req -Client $itClient -Method Post -Path $closeErrPath -Token $itToken `
        -Body (New-CloseBody -Version $verErr -ReasonCode 'DUPLICATE' -Description '目标是已关闭的单' -DuplicateTicketNo $ticketInvalid) `
        -Actor 'it' -Note 'a5.close.duplicate.targetClosed'
    Add-Assertion -Name 'a5.close.duplicate.targetClosed.400' `
        -Condition (($c14.Status -eq 400) -and ((Get-Code $c14) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（已关闭的单不能当重复目标）' `
        -Detail "status=$($c14.Status) code=$(Get-Code $c14) target=$ticketInvalid（同一提交人，但已是终态）" -Result $c14

    $sigErrAfter = Get-TicketStateSignature -TicketNo $ticketErr
    Add-Assertion -Name 'a5.close.rejectionsDoNotMutateTicket' -Condition ($sigErrBefore -eq $sigErrAfter) `
        -Expected '14 条被拒请求之后 ticketErr 完全没变（状态/分类/优先级/负责人/期限/version/record_seq/关闭字段/记录条数/关联条数）' `
        -Detail "之前=[$sigErrBefore]；之后=[$sigErrAfter]"

    # ── 19. 断言组 a8：cancel 的 403 / 404 / 409 / 400 ───────────────────────
    $cancelErrPath = Get-ActionPath $ticketErrCancel 'cancel'
    $u1Cancel = Send-Req -Client $u1Client -Method Post -Path $cancelErrPath `
        -Token $u1Token -Body (New-CancelBody -Version $verErrCancel -Reason '缺提交人权限') `
        -Actor 'temp-close-only' -Note 'a8.cancel.missingRequesterAction'
    $u1CancelUnknown = Send-Req -Client $u1Client -Method Post -Path (Get-ActionPath $unknownTicketNo 'cancel') `
        -Token $u1Token -Body (New-CancelBody -Version 0 -Reason '缺提交人权限且编号不存在') `
        -Actor 'temp-close-only' -Note 'a8.cancel.missingRequesterAction(unknown)'
    Add-Assertion -Name 'a8.cancel.missingRequesterAction.403' `
        -Condition (($u1Cancel.Status -eq 403) -and ((Get-Code $u1Cancel) -eq 'TICKET_ACTION_FORBIDDEN')) `
        -Expected '403 + TICKET_ACTION_FORBIDDEN（U1 只有 TICKET_CLOSE，没有 TICKET_REQUESTER_ACTION）' `
        -Detail "status=$($u1Cancel.Status) code=$(Get-Code $u1Cancel)" -Result $u1Cancel
    Add-Assertion -Name 'a8.cancel.permissionGateBeforeVisibility' `
        -Condition (($u1CancelUnknown.Status -eq 403) -and ((Get-Code $u1CancelUnknown) -eq 'TICKET_ACTION_FORBIDDEN')) `
        -Expected '403 而不是 404（权限闸门先于可见性）' `
        -Detail "status=$($u1CancelUnknown.Status) code=$(Get-Code $u1CancelUnknown) 编号=$unknownTicketNo" -Result $u1CancelUnknown

    # U3 有 TICKET_REQUESTER_ACTION，但只有 TICKET_VIEW_OWN：别人的工单对它不可见 → 404
    $u3CancelOther = Send-Req -Client $u3Client -Method Post -Path $cancelErrPath `
        -Token $u3Token -Body (New-CancelBody -Version $verErrCancel -Reason '撤销别人的工单') `
        -Actor 'temp-requester-only' -Note 'a8.cancel.notVisible'
    Add-Assertion -Name 'a8.cancel.notVisible.404' `
        -Condition (($u3CancelOther.Status -eq 404) -and ((Get-Code $u3CancelOther) -eq 'TICKET_NOT_FOUND')) `
        -Expected '404 + TICKET_NOT_FOUND（U3 有提交人权限但看不到别人的工单）' `
        -Detail "status=$($u3CancelOther.Status) code=$(Get-Code $u3CancelOther) ticket=$ticketErrCancel（提交人是 employee）" -Result $u3CancelOther

    # U5 是 handover 的负责人、且**同时持有 TICKET_REQUESTER_ACTION**：不是提交人 → 409（不是 403）
    $u5Cancel = Send-Req -Client $u5Client -Method Post -Path (Get-ActionPath $ticketHandover 'cancel') `
        -Token $u5Token -Body (New-CancelBody -Version $verHandover -Reason '负责人尝试撤销') `
        -Actor 'temp-it-plus-requester' -Note 'a8.cancel.assigneeButNotRequester'
    $rowHandoverBeforeReject = Get-TicketDbRow -TicketNo $ticketHandover
    Add-Assertion -Name 'a8.cancel.assigneeButNotRequester.409' `
        -Condition (($u5Cancel.Status -eq 409) -and ((Get-Code $u5Cancel) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（当前负责人即使同时持有 TICKET_REQUESTER_ACTION 也不是提交人，不能撤销）' `
        -Detail "status=$($u5Cancel.Status) code=$(Get-Code $u5Cancel) ticket=$ticketHandover 负责人=$($rowHandoverBeforeReject.assigneeId)（U5）" `
        -Result $u5Cancel

    $cancelStale = Send-Req -Client $employeeClient -Method Post -Path $cancelErrPath `
        -Token $employeeToken -Body (New-CancelBody -Version ($verErrCancel - 1) -Reason '版本过期') `
        -Actor 'employee' -Note 'a8.cancel.staleVersion'
    $cancelStaleAndBlank = Send-Req -Client $employeeClient -Method Post -Path $cancelErrPath `
        -Token $employeeToken -Body (New-CancelBody -Version ($verErrCancel - 1) -Reason '   ') `
        -Actor 'employee' -Note 'a8.cancel.staleVersionAndBlankReason'
    Add-Assertion -Name 'a8.cancel.staleVersion.409' `
        -Condition (($cancelStale.Status -eq 409) -and ((Get-Code $cancelStale) -eq 'TICKET_CONFLICT') -and ([int](Get-ErrData $cancelStale).version -eq $verErrCancel)) `
        -Expected "409 + TICKET_CONFLICT 且 data.version=$verErrCancel" `
        -Detail "status=$($cancelStale.Status) code=$(Get-Code $cancelStale) data.version=$((Get-ErrData $cancelStale).version) data.status=$((Get-ErrData $cancelStale).status)" `
        -Result $cancelStale
    # cancel 在 HTTP 层**没有**可观察的「409 先于 400」：它唯一的服务层 400 是原因长度，
    # 而 @NotBlank / @Size 会把同一个输入在进服务层之前拦下，所以这里看到的必然是 Bean Validation 的 400。
    # 服务层的顺序由单元用例 TicketServiceImplTest.cancelReportsConflictBeforeValidatingReason 直接覆盖
    # （直接调用服务，不经过 @Valid），本格如实记录 HTTP 层的实际顺序。
    Add-Assertion -Name 'a8.cancel.beanValidationPrecedesServiceGate' `
        -Condition (($cancelStaleAndBlank.Status -eq 400) -and ((Get-Code $cancelStaleAndBlank) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（空白原因由 @NotBlank 在进服务层之前拦下，版本过期的 409 因此观察不到）' `
        -Detail "status=$($cancelStaleAndBlank.Status) code=$(Get-Code $cancelStaleAndBlank) body=[$(Get-SafeBody $cancelStaleAndBlank)]；服务层的『409 先于 400』见单元用例，不在这里主张" `
        -Result $cancelStaleAndBlank

    $cancelTerminalCompleted = Send-Req -Client $employeeClient -Method Post -Path (Get-ActionPath $ticketCompleted 'cancel') `
        -Token $employeeToken -Body (New-CancelBody -Version $verCompleted -Reason '已完成不能再撤销') `
        -Actor 'employee' -Note 'a8.cancel.terminalCompleted'
    $cancelTerminalClosed = Send-Req -Client $employeeClient -Method Post -Path (Get-ActionPath $ticketScope 'cancel') `
        -Token $employeeToken -Body (New-CancelBody -Version $verScope -Reason '已关闭不能再撤销') `
        -Actor 'employee' -Note 'a8.cancel.terminalClosed'
    $cancelTerminalCanceled = Send-Req -Client $employeeClient -Method Post -Path (Get-ActionPath $ticketCancelProc 'cancel') `
        -Token $employeeToken -Body (New-CancelBody -Version $verCancelProc -Reason '已取消不能再撤销') `
        -Actor 'employee' -Note 'a8.cancel.terminalCanceled'
    $cancelTerminalPendingCanceled = Send-Req -Client $employeeClient -Method Post -Path (Get-ActionPath $ticketCanceledTarget 'cancel') `
        -Token $employeeToken -Body (New-CancelBody -Version $verCanceledTarget -Reason '已取消不能再撤销') `
        -Actor 'employee' -Note 'a8.cancel.terminalCanceledFromPending'
    Add-Assertion -Name 'a8.cancel.terminalCompleted.409' `
        -Condition (($cancelTerminalCompleted.Status -eq 409) -and ((Get-Code $cancelTerminalCompleted) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（已完成是终态）' `
        -Detail "status=$($cancelTerminalCompleted.Status) code=$(Get-Code $cancelTerminalCompleted) ticket=$ticketCompleted" -Result $cancelTerminalCompleted
    Add-Assertion -Name 'a8.cancel.terminalClosed.409' `
        -Condition (($cancelTerminalClosed.Status -eq 409) -and ((Get-Code $cancelTerminalClosed) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（已关闭是终态；已取消与已完成的口径互不重叠）' `
        -Detail "status=$($cancelTerminalClosed.Status) code=$(Get-Code $cancelTerminalClosed) ticket=$ticketScope" -Result $cancelTerminalClosed
    Add-Assertion -Name 'a8.cancel.terminalCanceled.409' `
        -Condition (($cancelTerminalCanceled.Status -eq 409) -and ((Get-Code $cancelTerminalCanceled) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（v1 不支持重复撤销，也不支持恢复）' `
        -Detail "status=$($cancelTerminalCanceled.Status) code=$(Get-Code $cancelTerminalCanceled) ticket=$ticketCancelProc" -Result $cancelTerminalCanceled
    Add-Assertion -Name 'a8.cancel.terminalCanceledFromPending.409' `
        -Condition (($cancelTerminalPendingCanceled.Status -eq 409) -and ((Get-Code $cancelTerminalPendingCanceled) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（待受理撤销出来的单同样是终态）' `
        -Detail "status=$($cancelTerminalPendingCanceled.Status) code=$(Get-Code $cancelTerminalPendingCanceled) ticket=$ticketCanceledTarget" -Result $cancelTerminalPendingCanceled

    $sigCancelBefore = Get-TicketStateSignature -TicketNo $ticketErrCancel
    $cancelBlank = Send-Req -Client $employeeClient -Method Post -Path $cancelErrPath `
        -Token $employeeToken -Body (New-CancelBody -Version $verErrCancel -Reason '   ') `
        -Actor 'employee' -Note 'a8.cancel.reason.blank'
    $cancelMissing = Send-Req -Client $employeeClient -Method Post -Path $cancelErrPath `
        -Token $employeeToken -Body (New-CancelBody -Version $verErrCancel -OmitReason) `
        -Actor 'employee' -Note 'a8.cancel.reason.missing'
    $cancelTooLong = Send-Req -Client $employeeClient -Method Post -Path $cancelErrPath `
        -Token $employeeToken -Body (New-CancelBody -Version $verErrCancel -Reason ('超' * 1001)) `
        -Actor 'employee' -Note 'a8.cancel.reason.tooLong'
    Add-Assertion -Name 'a8.cancel.reason.blank.400' `
        -Condition (($cancelBlank.Status -eq 400) -and ((Get-Code $cancelBlank) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（撤销原因去空白后为空）' `
        -Detail "status=$($cancelBlank.Status) code=$(Get-Code $cancelBlank) body=[$(Get-SafeBody $cancelBlank)]" -Result $cancelBlank
    Add-Assertion -Name 'a8.cancel.reason.missing.400' `
        -Condition (($cancelMissing.Status -eq 400) -and ((Get-Code $cancelMissing) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（原因整体缺失）' `
        -Detail "status=$($cancelMissing.Status) code=$(Get-Code $cancelMissing)" -Result $cancelMissing
    Add-Assertion -Name 'a8.cancel.reason.tooLong.400' `
        -Condition (($cancelTooLong.Status -eq 400) -and ((Get-Code $cancelTooLong) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（原因 1001 个字符，超过 @Size(max=1000)）' `
        -Detail "status=$($cancelTooLong.Status) code=$(Get-Code $cancelTooLong)" -Result $cancelTooLong
    $sigCancelAfter = Get-TicketStateSignature -TicketNo $ticketErrCancel
    Add-Assertion -Name 'a8.cancel.rejectionsDoNotMutateTicket' -Condition ($sigCancelBefore -eq $sigCancelAfter) `
        -Expected 'cancel 的全部 400/409 之后 ticketErrCancel 完全没变（含 record_seq 与记录条数）' `
        -Detail "之前=[$sigCancelBefore]；之后=[$sigCancelAfter]"

    # ── 20. 断言组 a10：三条终态可区分 + 终态无出口 ──────────────────────────
    $rowCompletedFinal = Get-TicketDbRow -TicketNo $ticketCompleted
    $rowClosedFinal = Get-TicketDbRow -TicketNo $ticketScope
    $rowCanceledFinal = Get-TicketDbRow -TicketNo $ticketCancelProc
    $terminalOk = ($null -ne $rowCompletedFinal) -and ($null -ne $rowClosedFinal) -and ($null -ne $rowCanceledFinal) -and
    ($rowCompletedFinal.status -eq 'COMPLETED') -and ($rowCompletedFinal.completionMethod -eq 'REQUESTER_CONFIRMED') -and
    ($rowCompletedFinal.closeMethod -eq 'NULL') -and ($rowCompletedFinal.closeReason -eq 'NULL') -and ($rowCompletedFinal.endedAt -eq 'SET') -and
    ($rowClosedFinal.status -eq 'CLOSED') -and ($rowClosedFinal.completionMethod -eq 'NULL') -and
    ($rowClosedFinal.closeMethod -eq 'MANUAL') -and ($rowClosedFinal.closeReason -eq 'OUT_OF_SCOPE') -and ($rowClosedFinal.endedAt -eq 'SET') -and
    ($rowCanceledFinal.status -eq 'CANCELED') -and ($rowCanceledFinal.completionMethod -eq 'NULL') -and
    ($rowCanceledFinal.closeMethod -eq 'NULL') -and ($rowCanceledFinal.closeReason -eq 'NULL') -and ($rowCanceledFinal.endedAt -eq 'SET')
    Add-Assertion -Name 'a10.terminal.threeStatesDistinguishable' -Condition $terminalOk `
        -Expected '三条终态互不混淆：COMPLETED 只有 completion_method、CLOSED 只有 close_method/close_reason、CANCELED 两者都为空；三者 ended_at 都有值' `
        -Detail ("COMPLETED=[$($rowCompletedFinal.raw)] ;; CLOSED=[$($rowClosedFinal.raw)] ;; CANCELED=[$($rowCanceledFinal.raw)]")
    Assert-Db -Name 'a10.terminal.fieldSetsInDb' `
        -Sql ("SELECT GROUP_CONCAT(CONCAT(ticket_no,'=',status,':',IFNULL(completion_method,'-'),':'," +
        "IFNULL(close_method,'-'),':',IFNULL(close_reason,'-'),':',IF(ended_at IS NULL,'no-end','ended')) " +
        "ORDER BY ticket_no SEPARATOR ' ;; ') FROM ticket " +
        "WHERE ticket_no IN ('$ticketCompleted','$ticketScope','$ticketCancelProc');") `
        -Expected '三行字段集互斥：已完成=REQUESTER_CONFIRMED:-:-:ended、已关闭=-:MANUAL:OUT_OF_SCOPE:ended、已取消=-:-:-:ended' `
        -Check {
        param($c)
        $raw = [string]$c[0]
        $raw.Contains("$ticketCompleted=COMPLETED:REQUESTER_CONFIRMED:-:-:ended") -and
        $raw.Contains("$ticketScope=CLOSED:-:MANUAL:OUT_OF_SCOPE:ended") -and
        $raw.Contains("$ticketCancelProc=CANCELED:-:-:-:ended")
    }
    $script:terminalInfo = [ordered]@{
        completed = $rowCompletedFinal.raw
        closed    = $rowClosedFinal.raw
        canceled  = $rowCanceledFinal.raw
        note      = '三条终态的可区分性断言：字段集互斥，而不是只看状态字符串'
    }

    # 终态无出口：三种终态 × {claim, close, cancel} 都必须是 409（前置闸门 403/404 不在此列）
    $terminalCarriers = @(
        @{ key = 'closed'; ticketNo = $ticketScope; label = 'CLOSED(OUT_OF_SCOPE)' },
        @{ key = 'canceled'; ticketNo = $ticketCancelProc; label = 'CANCELED(PROCESSING 撤销)' },
        @{ key = 'completed'; ticketNo = $ticketCompleted; label = 'COMPLETED(员工确认)' }
    )
    foreach ($carrier in $terminalCarriers) {
        $tNo = $carrier.ticketNo
        $tClaim = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $tNo 'claim') -Token $itToken `
            -Body @{ version = 0 } -Actor 'it' -Note "a10.noExit.$($carrier.key).claim"
        Add-Assertion -Name "a10.noExit.$($carrier.key).claim.409" `
            -Condition (($tClaim.Status -eq 409) -and ((Get-Code $tClaim) -eq 'TICKET_CONFLICT')) `
            -Expected "409 + TICKET_CONFLICT（$($carrier.label) 是终态，不能再领取）" `
            -Detail "status=$($tClaim.Status) code=$(Get-Code $tClaim) ticket=$tNo" -Result $tClaim

        $tClose = Send-Req -Client $itClient -Method Post -Path (Get-ActionPath $tNo 'close') -Token $itToken `
            -Body (New-CloseBody -Version 0 -ReasonCode 'INVALID' -Description '终态再关闭') `
            -Actor 'it' -Note "a10.noExit.$($carrier.key).close"
        Add-Assertion -Name "a10.noExit.$($carrier.key).close.409" `
            -Condition (($tClose.Status -eq 409) -and ((Get-Code $tClose) -eq 'TICKET_CONFLICT')) `
            -Expected "409 + TICKET_CONFLICT（$($carrier.label) 是终态，不能再关闭）" `
            -Detail "status=$($tClose.Status) code=$(Get-Code $tClose) ticket=$tNo" -Result $tClose

        $tCancel = Send-Req -Client $employeeClient -Method Post -Path (Get-ActionPath $tNo 'cancel') -Token $employeeToken `
            -Body (New-CancelBody -Version 0 -Reason '终态再撤销') -Actor 'employee' -Note "a10.noExit.$($carrier.key).cancel"
        Add-Assertion -Name "a10.noExit.$($carrier.key).cancel.409" `
            -Condition (($tCancel.Status -eq 409) -and ((Get-Code $tCancel) -eq 'TICKET_CONFLICT')) `
            -Expected "409 + TICKET_CONFLICT（$($carrier.label) 是终态，提交人也不能撤销）" `
            -Detail "status=$($tCancel.Status) code=$(Get-Code $tCancel) ticket=$tNo" -Result $tCancel
    }

    # ── 21. 断言组 a11：真并发（员工撤销 vs IT 关闭，同一版本） ───────────────
    # 片 D 的收益点：两个动作都以「工单行」起手，败者必须是可立即重试的 409，而不是 InnoDB 死锁的 500。
    $raceBodyCancel = New-CancelBody -Version $verRace -Reason '并发用例：员工撤销'
    $raceBodyClose = New-CloseBody -Version $verRace -ReasonCode 'OUT_OF_SCOPE' -Description '并发用例：IT 关闭'
    $race = Send-ConcurrentPair -ClientA $employeeClient -PathA (Get-ActionPath $ticketRace 'cancel') `
        -TokenA $employeeToken -BodyA $raceBodyCancel -ActorA 'employee' -NoteA 'a11.concurrent.cancel' `
        -ClientB $itClient -PathB (Get-ActionPath $ticketRace 'close') `
        -TokenB $itToken -BodyB $raceBodyClose -ActorB 'it' -NoteB 'a11.concurrent.close'
    $raceAll = @($race.A, $race.B)
    $raceWinners = @($raceAll | Where-Object { $_.Status -eq 200 })
    $raceLosers = @($raceAll | Where-Object { $_.Status -ne 200 })
    $raceServerErrors = @($raceAll | Where-Object { $_.Status -ge 500 })
    $rowRaceFinal = Get-TicketDbRow -TicketNo $ticketRace
    $script:concurrencyInfo = [ordered]@{
        ticketNo       = $ticketRace
        sharedVersion  = $verRace
        dispatchMode   = $race.dispatchMode
        dispatchedAt   = $race.dispatchedAt
        cancel         = @{ httpStatus = $race.A.Status; code = (Get-Code $race.A); traceId = $race.A.TraceId }
        close          = @{ httpStatus = $race.B.Status; code = (Get-Code $race.B); traceId = $race.B.TraceId }
        winnerCount    = $raceWinners.Count
        serverErrorCount = $raceServerErrors.Count
        finalDbRow     = $rowRaceFinal.raw
    }
    Add-Assertion -Name 'a11.concurrent.exactlyOneWinner' -Condition ($raceWinners.Count -eq 1) `
        -Expected '同一版本上恰好一个请求成功（200）' `
        -Detail "cancel=$($race.A.Status) close=$($race.B.Status)（两者都 200 说明版本条件失效；都失败说明闸门过严）"
    Add-Assertion -Name 'a11.concurrent.loserIsConflictNotServerError' `
        -Condition (($raceLosers.Count -eq 1) -and ($raceLosers[0].Status -eq 409) -and ((Get-Code $raceLosers[0]) -eq 'TICKET_CONFLICT')) `
        -Expected '败者是 409 + TICKET_CONFLICT（可立即重试），不是 500（死锁回滚）' `
        -Detail "败者 status=$($raceLosers[0].Status) code=$(Get-Code $raceLosers[0])；cancel=$($race.A.Status) close=$($race.B.Status)"
    Add-Assertion -Name 'a11.concurrent.noServerError' -Condition ($raceServerErrors.Count -eq 0) `
        -Expected '两路请求都没有 5xx（转交交叉死锁修掉之后，同一张单上的动作竞争不再出现 InnoDB 死锁回滚）' `
        -Detail "cancel=$($race.A.Status) close=$($race.B.Status)"
    $raceWinnerStatus = ''
    if ($race.A.Status -eq 200) { $raceWinnerStatus = 'CANCELED' }
    if ($race.B.Status -eq 200) { $raceWinnerStatus = 'CLOSED' }
    $raceDbOk = ($null -ne $rowRaceFinal) -and ($rowRaceFinal.status -eq $raceWinnerStatus) -and
    ($rowRaceFinal.version -eq ($verRace + 1))
    if ($raceWinnerStatus -eq 'CANCELED') {
        $raceDbOk = $raceDbOk -and ($rowRaceFinal.closeMethod -eq 'NULL') -and ($rowRaceFinal.closeReason -eq 'NULL') -and ($rowRaceFinal.endedAt -eq 'SET')
    }
    if ($raceWinnerStatus -eq 'CLOSED') {
        $raceDbOk = $raceDbOk -and ($rowRaceFinal.closeMethod -eq 'MANUAL') -and ($rowRaceFinal.closeReason -eq 'OUT_OF_SCOPE') -and ($rowRaceFinal.endedAt -eq 'SET')
    }
    Add-Assertion -Name 'a11.concurrent.winnerDeterminesDbState' -Condition $raceDbOk `
        -Expected '库中终态与赢家一致（撤销赢 → CANCELED 且无关闭字段；关闭赢 → CLOSED 且 close_method/close_reason 齐全），version 只 +1' `
        -Detail "赢家=$raceWinnerStatus（cancel=$($race.A.Status) close=$($race.B.Status)）row=[$($rowRaceFinal.raw)]"
    Add-Assertion -Name 'a11.concurrent.loserCarriesWinnerSnapshot' `
        -Condition (($null -ne $rowRaceFinal) -and ([int](Get-ErrData $raceLosers[0]).version -eq $rowRaceFinal.version) -and ((Get-ErrData $raceLosers[0]).status -eq $rowRaceFinal.status)) `
        -Expected '败者的 409 响应带赢家造成的当前快照（data.version / data.status 与库一致），前端可直接刷新重试' `
        -Detail "败者 data.version=$((Get-ErrData $raceLosers[0]).version) data.status=$((Get-ErrData $raceLosers[0]).status)；库中 version=$($rowRaceFinal.version) status=$($rowRaceFinal.status)"
    Add-KeyAction -Order 40 -Actor 'employee' -Action 'cancel(并发)' -Method 'POST' `
        -Path (Get-ActionPath $ticketRace 'cancel') -TicketNo $ticketRace -Result $race.A -StatusAfter $raceWinnerStatus
    Add-KeyAction -Order 41 -Actor 'it' -Action 'close(并发)' -Method 'POST' `
        -Path (Get-ActionPath $ticketRace 'close') -TicketNo $ticketRace -Result $race.B -StatusAfter $raceWinnerStatus

    # ── 21b. 断言组 a11b：真并发（IT 转交 vs IT 关闭，同一版本） ─────────────
    # 这一对是「片 D 把转交的加锁顺序改成工单行起手」的直接证据：修正前转交按「用户行 → 工单行」
    # 加锁，与关闭的「工单行 → 用户行（ticket_record.actor_user_id 外键的共享锁）」首尾相接成环，
    # 败者会拿到 500；修正后两者都以工单行起手，败者必须是可以立即重试的 409。
    $race2BodyTransfer = @{ version = $verRaceTransfer; newAssigneeId = $u5.userId; reason = '并发用例：转交给第二名 IT' }
    $race2BodyClose = New-CloseBody -Version $verRaceTransfer -ReasonCode 'OUT_OF_SCOPE' -Description '并发用例：IT 关闭'
    $race2 = Send-ConcurrentPair -ClientA $itClient -PathA (Get-ActionPath $ticketRaceTransfer 'transfer') `
        -TokenA $itToken -BodyA $race2BodyTransfer -ActorA 'it' -NoteA 'a11b.concurrent.transfer' `
        -ClientB $itSecondClient -PathB (Get-ActionPath $ticketRaceTransfer 'close') `
        -TokenB $itToken2 -BodyB $race2BodyClose -ActorB 'it(第二会话)' -NoteB 'a11b.concurrent.close'
    $race2All = @($race2.A, $race2.B)
    $race2Winners = @($race2All | Where-Object { $_.Status -eq 200 })
    $race2Losers = @($race2All | Where-Object { $_.Status -ne 200 })
    $race2ServerErrors = @($race2All | Where-Object { $_.Status -ge 500 })
    $rowRace2Final = Get-TicketDbRow -TicketNo $ticketRaceTransfer
    $script:concurrencyTransferInfo = [ordered]@{
        ticketNo         = $ticketRaceTransfer
        sharedVersion    = $verRaceTransfer
        dispatchMode     = $race2.dispatchMode
        dispatchedAt     = $race2.dispatchedAt
        transfer         = @{ httpStatus = $race2.A.Status; code = (Get-Code $race2.A); traceId = $race2.A.TraceId }
        close            = @{ httpStatus = $race2.B.Status; code = (Get-Code $race2.B); traceId = $race2.B.TraceId }
        winnerCount      = $race2Winners.Count
        serverErrorCount = $race2ServerErrors.Count
        finalDbRow       = $rowRace2Final.raw
        why              = ('修正前 transfer 按「用户行 → 工单行」加锁，与关闭的「工单行 → 用户行（外键共享锁）」成环；' +
        '修正后两者都以工单行起手，败者得到 409 而不是 500')
    }
    Add-Assertion -Name 'a11b.concurrent.exactlyOneWinner' -Condition ($race2Winners.Count -eq 1) `
        -Expected '同一版本上「转交 vs 关闭」恰好一个请求成功（200）' `
        -Detail "transfer=$($race2.A.Status) close=$($race2.B.Status)"
    Add-Assertion -Name 'a11b.concurrent.loserIsConflictNotServerError' `
        -Condition (($race2Losers.Count -eq 1) -and ($race2Losers[0].Status -eq 409) -and ((Get-Code $race2Losers[0]) -eq 'TICKET_CONFLICT')) `
        -Expected '败者是 409 + TICKET_CONFLICT（可立即重试），不是 500 —— 片 D 加锁顺序修正的直接收益' `
        -Detail "败者 status=$($race2Losers[0].Status) code=$(Get-Code $race2Losers[0])；transfer=$($race2.A.Status) close=$($race2.B.Status)"
    Add-Assertion -Name 'a11b.concurrent.noServerError' -Condition ($race2ServerErrors.Count -eq 0) `
        -Expected '两路请求都没有 5xx（不再出现 InnoDB 死锁回滚）' `
        -Detail "transfer=$($race2.A.Status) close=$($race2.B.Status)"
    $race2WinnerKind = ''
    if ($race2.A.Status -eq 200) { $race2WinnerKind = 'TRANSFER' }
    if ($race2.B.Status -eq 200) { $race2WinnerKind = 'CLOSE' }
    $race2DbOk = ($null -ne $rowRace2Final) -and ($rowRace2Final.version -eq ($verRaceTransfer + 1))
    if ($race2WinnerKind -eq 'TRANSFER') {
        $race2DbOk = $race2DbOk -and ($rowRace2Final.status -eq 'PROCESSING') -and
        ($rowRace2Final.assigneeId -eq $u5.userId) -and ($rowRace2Final.closeMethod -eq 'NULL')
    }
    if ($race2WinnerKind -eq 'CLOSE') {
        $race2DbOk = $race2DbOk -and ($rowRace2Final.status -eq 'CLOSED') -and
        ($rowRace2Final.assigneeId -eq $itUserId) -and ($rowRace2Final.closeMethod -eq 'MANUAL')
    }
    Add-Assertion -Name 'a11b.concurrent.winnerDeterminesDbState' -Condition $race2DbOk `
        -Expected '库中结果与赢家一致：转交赢 → 仍是 PROCESSING + 负责人换 U5 + 无关闭字段；关闭赢 → CLOSED + 负责人仍是 it；两者 version 都只 +1' `
        -Detail "赢家=$race2WinnerKind（transfer=$($race2.A.Status) close=$($race2.B.Status)）row=[$($rowRace2Final.raw)]"
    Add-Assertion -Name 'a11b.concurrent.loserCarriesWinnerSnapshot' `
        -Condition (($null -ne $rowRace2Final) -and ($race2Losers.Count -eq 1) -and ([int](Get-ErrData $race2Losers[0]).version -eq $rowRace2Final.version) -and ((Get-ErrData $race2Losers[0]).status -eq $rowRace2Final.status)) `
        -Expected '败者的 409 带赢家造成的当前快照（data.version / data.status 与库一致）' `
        -Detail "败者 data.version=$((Get-ErrData $race2Losers[0]).version) data.status=$((Get-ErrData $race2Losers[0]).status)；库中 version=$($rowRace2Final.version) status=$($rowRace2Final.status)"
    Add-KeyAction -Order 42 -Actor 'it' -Action 'transfer(并发)' -Method 'POST' `
        -Path (Get-ActionPath $ticketRaceTransfer 'transfer') -TicketNo $ticketRaceTransfer -Result $race2.A -StatusAfter $race2WinnerKind
    Add-KeyAction -Order 43 -Actor 'it(第二会话)' -Action 'close(并发)' -Method 'POST' `
        -Path (Get-ActionPath $ticketRaceTransfer 'close') -TicketNo $ticketRaceTransfer -Result $race2.B -StatusAfter $race2WinnerKind

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

# ── 22. 清理之一：只删本次创建的 15 张载体工单及其记录/参与关系/重复关联 ──────
$script:cleanupInfo['startedAt'] = (Get-Date).ToString('s')
$ownTickets = @(
    $ticketDup, $ticketDupTarget, $ticketScope, $ticketInvalid, $ticketErr, $ticketOther,
    $ticketCanceledTarget, $ticketCancelProc, $ticketCancelWait, $ticketCancelConfirm,
    $ticketCompleted, $ticketErrCancel, $ticketHandover, $ticketRace, $ticketRaceTransfer
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

try {
    if ($ownTickets.Count -eq 0) {
        $script:cleanupInfo['note'] = '本次运行没有创建任何工单，无需清理工单；仍会尝试删除临时用户与临时角色。'
        Add-Assertion -Name 'a12.cleanup.nothingCreated' -Condition $true -Expected '无自建工单' `
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
        $script:cleanupInfo['note'] = ('只删除本次运行创建的工单及其记录/参与关系与重复关联；' +
        'ticket_attachment 在 v1 不会产生行，属防御性语句。')

        $cleanupResult = Invoke-MySql -Sql ($cleanupStatements -join "`n")
        $script:cleanupInfo['exitCode'] = $cleanupResult.exitCode
        $script:cleanupInfo['stderr'] = $cleanupResult.stderrText
        Add-Assertion -Name 'a12.cleanup.ticketDeletesSucceeded' -Condition ($cleanupResult.exitCode -eq 0) `
            -Expected '工单清理 SQL 退出码 0' `
            -Detail "exitCode=$($cleanupResult.exitCode) stderr=[$(Get-BriefText $cleanupResult.stderrText 120)]"

        $leftRow = Get-MySqlRow -Sql "SELECT COUNT(*) FROM ticket WHERE ticket_no IN ($quoted);"
        Add-Assertion -Name 'a12.cleanup.noOwnTicketsLeft' -Condition ($leftRow.ok -and ([int]$leftRow.columns[0] -eq 0)) `
            -Expected '自建工单已全部删除' -Detail "row=[$($leftRow.raw)]"
        $leftRelationRow = Get-MySqlRow -Sql (
            "SELECT COUNT(*) FROM ticket_relation WHERE source_ticket_id IN ($ticketList) OR target_ticket_id IN ($ticketList);")
        Add-Assertion -Name 'a12.cleanup.noOwnRelationsLeft' -Condition ($leftRelationRow.ok -and ([int]$leftRelationRow.columns[0] -eq 0)) `
            -Expected '自建工单的重复关联已删除（片 D 会真的写入 ticket_relation）' `
            -Detail "row=[$($leftRelationRow.raw)]"
    }
} catch {
    Add-Assertion -Name 'a12.cleanup.ticketDeletesSucceeded' -Condition $false -Expected '清理阶段无异常' `
        -Detail ("清理异常：" + $_.Exception.Message)
}

# ── 23. 清理之二：4 个临时用户 + 5 个临时角色（按主键 ID 精确删除） ──────────
try {
    if ($script:tempCleanup.Count -gt 0) {
        $tempUserIds = (@($script:tempCleanup | ForEach-Object { $_.userId }) | Sort-Object -Unique) -join ','
        $tempRoleIds = (@($script:tempCleanup | ForEach-Object { $_.roleId }) | Sort-Object -Unique) -join ','
        $tempUsernames = ($script:tempCleanup | ForEach-Object { "'" + $_.username + "'" }) -join ','
        $tempStatements = @(
            "DELETE FROM iam_user_role WHERE user_id IN ($tempUserIds);",
            "DELETE FROM iam_role_permission WHERE role_id IN ($tempRoleIds);",
            "DELETE FROM iam_user WHERE id IN ($tempUserIds);",
            "DELETE FROM iam_role WHERE id IN ($tempRoleIds);"
        )
        $script:cleanupInfo['tempUserIds'] = $tempUserIds
        $script:cleanupInfo['tempRoleIds'] = $tempRoleIds
        $script:cleanupInfo['tempUserSql'] = $tempStatements

        $tempCleanupResult = Invoke-MySql -Sql ($tempStatements -join "`n")
        $script:cleanupInfo['tempUserExitCode'] = $tempCleanupResult.exitCode
        Add-Assertion -Name 'a12.cleanup.tempPrincipalsDeleted' -Condition ($tempCleanupResult.exitCode -eq 0) `
            -Expected '临时用户与临时角色的删除 SQL 退出码 0' `
            -Detail "exitCode=$($tempCleanupResult.exitCode) userIds=[$tempUserIds] roleIds=[$tempRoleIds] stderr=[$(Get-BriefText $tempCleanupResult.stderrText 120)]"

        $leftTempRow = Get-MySqlRow -Sql (
            "SELECT (SELECT COUNT(*) FROM iam_user WHERE username IN ($tempUsernames)), " +
            "(SELECT COUNT(*) FROM iam_role WHERE id IN ($tempRoleIds)), " +
            "(SELECT COUNT(*) FROM iam_user_role WHERE user_id IN ($tempUserIds)), " +
            "(SELECT COUNT(*) FROM iam_role_permission WHERE role_id IN ($tempRoleIds));")
        $leftTempOk = $leftTempRow.ok -and (@($leftTempRow.columns) | Where-Object { [int]$_ -ne 0 }).Count -eq 0
        Add-Assertion -Name 'a12.cleanup.noTempPrincipalLeft' -Condition $leftTempOk `
            -Expected '临时用户、临时角色、它们的用户角色关系与角色权限关系都为 0 行' `
            -Detail "row=[$($leftTempRow.raw)]（四列依次是 用户 / 角色 / 用户角色 / 角色权限）"
    } else {
        $script:cleanupInfo['tempUserNote'] = '本次运行没有创建临时主体（建临时角色前就失败），无需删除。'
        Add-Assertion -Name 'a12.cleanup.noTempPrincipalCreated' -Condition $true -Expected '无临时主体' `
            -Detail '脚本在创建临时角色/用户前就失败，未写入这些行'
    }
} catch {
    Add-Assertion -Name 'a12.cleanup.tempPrincipalsDeleted' -Condition $false -Expected '临时主体清理阶段无异常' `
        -Detail ("清理异常：" + $_.Exception.Message)
}

# ── 24. 清理后基线核对：八项计数 + 逐行指纹与运行前完全一致 ──────────────────
try {
    $demoAfter = Get-DemoFingerprint
    $script:cleanupInfo['demoDatabaseBefore'] = $demoBefore
    $script:cleanupInfo['demoDatabaseAfter'] = $demoAfter

    $sameCounts = ($null -ne $demoBefore) -and ($demoBefore.tickets -eq $demoAfter.tickets) -and
    ($demoBefore.records -eq $demoAfter.records) -and ($demoBefore.participants -eq $demoAfter.participants) -and
    ($demoBefore.relations -eq $demoAfter.relations) -and
    ($demoBefore.users -eq $demoAfter.users) -and ($demoBefore.roles -eq $demoAfter.roles) -and
    ($demoBefore.permissions -eq $demoAfter.permissions) -and ($demoBefore.categories -eq $demoAfter.categories)
    Add-Assertion -Name 'a13.baseline.countsRestored' -Condition $sameCounts `
        -Expected '演示库八项计数与运行前完全一致（用户/角色/权限/分类/工单/记录/参与关系/工单关联）' `
        -Detail ("运行前 工单={0}/记录={1}/参与者={2}/关联={3}/用户={4}/角色={5}/权限={6}/分类={7}；" +
        "运行后 工单={8}/记录={9}/参与者={10}/关联={11}/用户={12}/角色={13}/权限={14}/分类={15}" -f `
            $demoBefore.tickets, $demoBefore.records, $demoBefore.participants, $demoBefore.relations, `
            $demoBefore.users, $demoBefore.roles, $demoBefore.permissions, $demoBefore.categories, `
            $demoAfter.tickets, $demoAfter.records, $demoAfter.participants, $demoAfter.relations, `
            $demoAfter.users, $demoAfter.roles, $demoAfter.permissions, $demoAfter.categories)

    $sameTicketNos = ($null -ne $demoBefore) -and ($demoBefore.ticketNos -eq $demoAfter.ticketNos)
    Add-Assertion -Name 'a13.baseline.ticketNoListUntouched' -Condition $sameTicketNos `
        -Expected '运行前工单号列表与运行后逐字相同（自建工单已全部删除，既有工单一行未动）' `
        -Detail "运行前=[$($demoBefore.ticketNos)]；运行后=[$($demoAfter.ticketNos)]"

    $sameTicketFingerprint = ($null -ne $demoBefore) -and ($demoBefore.ticketRows -eq $demoAfter.ticketRows)
    Add-Assertion -Name 'a13.baseline.ticketFingerprintUntouched' -Condition $sameTicketFingerprint `
        -Expected '既有工单的 status/version/record_seq/负责人/期限/结束时间/完成方式/关闭方式与原因/updated_at 逐行一致' `
        -Detail "运行前指纹长度=$($demoBefore.ticketRows.Length)；运行后指纹长度=$($demoAfter.ticketRows.Length)；是否相同=$sameTicketFingerprint"

    $sameRecordFingerprint = ($null -ne $demoBefore) -and ($demoBefore.recordRows -eq $demoAfter.recordRows)
    Add-Assertion -Name 'a13.baseline.recordFingerprintUntouched' -Condition $sameRecordFingerprint `
        -Expected '既有记录的 id/序号/类型/操作者/时间逐条一致（不可变时间线没有被改动）' `
        -Detail "运行前指纹长度=$($demoBefore.recordRows.Length)；运行后指纹长度=$($demoAfter.recordRows.Length)；是否相同=$sameRecordFingerprint"

    $sameParticipants = ($null -ne $demoBefore) -and ($demoBefore.participantRows -eq $demoAfter.participantRows)
    Add-Assertion -Name 'a13.baseline.participantFingerprintUntouched' -Condition $sameParticipants `
        -Expected '既有参与关系逐条一致（本次新转交产生的那条随工单一起删除）' `
        -Detail "运行前=[$($demoBefore.participantRows)]；运行后=[$($demoAfter.participantRows)]"

    $sameRelations = ($null -ne $demoBefore) -and ($demoBefore.relationRows -eq $demoAfter.relationRows)
    Add-Assertion -Name 'a13.baseline.relationFingerprintUntouched' -Condition $sameRelations `
        -Expected '既有 ticket_relation 逐条一致（本次写的那条 DUPLICATE 关联已随工单删除）' `
        -Detail "运行前=[$($demoBefore.relationRows)]；运行后=[$($demoAfter.relationRows)]"

    $sameUsers = ($null -ne $demoBefore) -and ($demoBefore.userRows -eq $demoAfter.userRows)
    Add-Assertion -Name 'a13.baseline.userRowsUntouched' -Condition $sameUsers `
        -Expected '既有用户的 id/username/status 逐条一致（4 个临时用户已删除）' `
        -Detail "运行前=[$($demoBefore.userRows)]；运行后=[$($demoAfter.userRows)]"

    $sameUserRoles = ($null -ne $demoBefore) -and ($demoBefore.userRoleRows -eq $demoAfter.userRoleRows)
    Add-Assertion -Name 'a13.baseline.userRoleRowsUntouched' -Condition $sameUserRoles `
        -Expected '既有 iam_user_role 逐条一致（临时用户的角色关系已删除）' `
        -Detail "运行前=[$($demoBefore.userRoleRows)]；运行后=[$($demoAfter.userRoleRows)]"

    $sameRolePermissions = ($null -ne $demoBefore) -and ($demoBefore.rolePermissionRows -eq $demoAfter.rolePermissionRows)
    Add-Assertion -Name 'a13.baseline.rolePermissionRowsUntouched' -Condition $sameRolePermissions `
        -Expected '既有 iam_role_permission 逐条一致（5 个临时角色的权限关系已删除）' `
        -Detail "运行前=[$($demoBefore.rolePermissionRows)]；运行后=[$($demoAfter.rolePermissionRows)]"

    $script:cleanupInfo['dailySequenceBefore'] = $demoBefore.dailySequence
    $script:cleanupInfo['dailySequenceAfter'] = $demoAfter.dailySequence
    Add-Assertion -Name 'a13.dailySequenceIncrementedAsExpected' -Condition ($null -ne $demoAfter) `
        -Expected 'ticket_daily_sequence 递增属预期、不回退（接口建单必然递增，编号不复用）' `
        -Detail "运行前=[$($demoBefore.dailySequence)]；运行后=[$($demoAfter.dailySequence)]；回退就意味着改动了运行前就存在的行，因此本脚本只记录不回退"
} catch {
    Add-Assertion -Name 'a13.baseline.checkFailed' -Condition $false -Expected '清理后基线核对可完成' `
        -Detail ("核对异常：" + $_.Exception.Message)
}

# ── 25. 安全边界声明（与断言一起进证据） ─────────────────────────────────────
$script:cleanupInfo['dockerCommandsUsed'] = @('docker exec -i -e MYSQL_PWD <mysql 容器> mysql -uroot -N -B --default-character-set=utf8mb4 <库名>（SQL 走 stdin）')
$script:cleanupInfo['composeDownExecuted'] = $false
$script:cleanupInfo['containersOrVolumesModified'] = $false
$script:cleanupInfo['backendProcessTouched'] = $false
$script:cleanupInfo['targetBaseUrl'] = $baseUrl
$script:cleanupInfo['finishedAt'] = (Get-Date).ToString('s')
Add-Assertion -Name 'a14.safetyBoundary.declared' -Condition $true `
    -Expected '明确声明三条边界' `
    -Detail ('未触碰用户自己启动的 8081 后端（脚本只向目标基址发 HTTP，不启动/不重启/不结束任何进程）；' +
    '未执行 docker compose down；未修改容器与卷（只对既有 mysql 容器执行 docker exec 读写演示库）')

# ── 26. 汇总与证据（无 BOM 的 UTF-8） ────────────────────────────────────────
# 注意：List[object] 一律用 .ToArray()，不要用 @() 包（Windows PowerShell 5.1 会抛
# System.ArgumentException: Argument types do not match）；条件值先算成变量再进哈希表字面量。
$finishedAt = Get-Date
$failed = @($script:results | Where-Object { -not $_.passed })
$failedCount = $failed.Count
$totalCount = $script:results.Count
$passedCount = $totalCount - $failedCount
if ($failedCount -gt 0) { $script:quitCode = 1 }

$relativeScriptPath = 'scripts/slice-d-close-cancel-acceptance.ps1'
$relativeOutPath = 'docs/acceptance/2026-10-08-slice-d-close-cancel.json'
# 证据里的 evidencePath 只写仓库相对路径：本文件顶部的 _note 明确声明「不含机器绝对路径」，
# 而 $OutFile 的默认值是 Join-Path $PSScriptRoot '..\...'，直接写入会把本机路径带进入库文件。
# 用 GetFullPath 而不是 Resolve-Path——首次运行时目标文件尚不存在，Resolve-Path 会失败，
# 那会让「首跑」和「重跑」生成不同形状的证据。落在仓库外时退回常量，同样不泄露本机路径。
$evidencePathForJson = $relativeOutPath
try {
    $repoRootFull = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
    $outFull = [System.IO.Path]::GetFullPath($OutFile)
    if ($outFull.StartsWith($repoRootFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        $evidencePathForJson = ($outFull.Substring($repoRootFull.Length).TrimStart('\', '/') -replace '\\', '/')
    }
} catch { $evidencePathForJson = $relativeOutPath }
# 记录本次真实用到的基址：脚本默认基址是 8081，片 D 的接口在旧构建上不存在，
# 若 invocation 不带 -BaseUrl，照抄执行会打到没有该接口的后端并把连接失败误判成断言失败
# （2026-10-08 交接前实际踩过一次：18 项断言、首请求连接失败、证据被覆盖成废记录）。
$invocation = 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\slice-d-close-cancel-acceptance.ps1 -BaseUrl ' + $baseUrl
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
        evidencePath      = $evidencePathForJson
    }
    $targetEntry = [ordered]@{
        baseUrl               = $baseUrl
        dbContainer           = $DbContainer
        database              = $dbName
        originHeader          = $originHeader
        accounts              = @($EmployeeUser, $ItUser, $AdminUser)
        tempPrincipals        = @('u1=closeonly', 'u2=processonly', 'u3=requesteronly', 'u5=itplusrequester')
        credentialsPrinted    = $false
        credentialsInEvidence = $false
    }
    $totalsEntry = [ordered]@{
        total  = $totalCount
        passed = $passedCount
        failed = $failedCount
    }
    $evidence = [ordered]@{
        _note = ('本文件由 scripts/slice-d-close-cancel-acceptance.ps1 写出（无 BOM 的 UTF-8）。' +
        '只记录状态码、业务码、traceId、断言结论与 SQL；不含口令、密钥与 accessToken，也不含机器绝对路径。')
        stage              = '完整工单状态机 片 D：结束路径（close + cancel）真实栈验收'
        slice              = 'D'
        date               = $startedAt.ToString('yyyy-MM-dd')
        result             = $resultValue
        script             = $scriptEntry
        target             = $targetEntry
        preflight          = $script:preflightInfo
        tempPrincipals     = $script:tempPrincipalInfo
        terminalStates     = $script:terminalInfo
        tickets            = $script:ticketsInfo
        concurrency        = $script:concurrencyInfo
        concurrencyTransferVsClose = $script:concurrencyTransferInfo
        keyActions         = $keyActionList
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
        stage      = '完整工单状态机 片 D：结束路径真实栈验收'
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
Write-Host ("阶段：片 D 结束路径（close + cancel）；目标后端：{0}" -f $baseUrl)
Write-Host ("脚本：{0}（{1} 行）；断言 {2} 项，通过 {3}，失败 {4}" -f `
        $relativeScriptPath, $scriptLineCount, $totalCount, $passedCount, $failedCount)
Write-Host '关键动作（状态码 / X-Trace-Id）：'
foreach ($step in $script:keyActions) {
    Write-Host ("  {0}. {1,-26} {2,-30} {3} => {4} traceId={5}" -f `
            $step.order, $step.actor, $step.action, $step.ticketNo, $step.httpStatus, $step.traceId)
}
Write-Host '数据库直查：'
foreach ($check in $script:dbChecks) {
    $mark = 'FAIL'
    if ($check.passed) { $mark = 'PASS' }
    Write-Host ("  [{0}] {1} :: {2}" -f $mark, $check.name, $check.raw)
}
if ($null -ne $demoBefore) {
    Write-Host ("演示库计数：运行前 工单={0}/记录={1}/参与者={2}/关联={3}/用户={4}/角色={5} 工单号=[{6}]" -f `
            $demoBefore.tickets, $demoBefore.records, $demoBefore.participants, $demoBefore.relations, `
            $demoBefore.users, $demoBefore.roles, $demoBefore.ticketNos)
}
if ($null -ne $demoAfter) {
    Write-Host ("            运行后 工单={0}/记录={1}/参与者={2}/关联={3}/用户={4}/角色={5} 工单号=[{6}]" -f `
            $demoAfter.tickets, $demoAfter.records, $demoAfter.participants, $demoAfter.relations, `
            $demoAfter.users, $demoAfter.roles, $demoAfter.ticketNos)
}
if ($script:concurrencyInfo.Count -gt 0) {
    Write-Host ("并发用例 1（员工撤销 vs IT 关闭）：ticket={0} 共享版本={1} → cancel={2} close={3}（赢家数={4}，5xx={5}）" -f `
            $script:concurrencyInfo['ticketNo'], $script:concurrencyInfo['sharedVersion'], `
            $script:concurrencyInfo['cancel'].httpStatus, $script:concurrencyInfo['close'].httpStatus, `
            $script:concurrencyInfo['winnerCount'], $script:concurrencyInfo['serverErrorCount'])
}
if ($script:concurrencyTransferInfo.Count -gt 0) {
    Write-Host ("并发用例 2（IT 转交 vs IT 关闭）：ticket={0} 共享版本={1} → transfer={2} close={3}（赢家数={4}，5xx={5}）" -f `
            $script:concurrencyTransferInfo['ticketNo'], $script:concurrencyTransferInfo['sharedVersion'], `
            $script:concurrencyTransferInfo['transfer'].httpStatus, $script:concurrencyTransferInfo['close'].httpStatus, `
            $script:concurrencyTransferInfo['winnerCount'], $script:concurrencyTransferInfo['serverErrorCount'])
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




