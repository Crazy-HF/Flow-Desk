# 完整工单状态机「片 E：两阶段撤销」真实栈验收
#   request-cancel（提交人发起撤销请求，工单状态不变）
#   + approve-cancel / reject-cancel（当前负责人批准或拒绝，只需 TICKET_PROCESS）
#   + withdraw-cancel-request（提交人撤回自己的请求）
#   + cancel 收窄（四种非终态里只剩「待受理」可以直接撤销）
#
# 前置
#   · docker compose up -d mysql redis（本脚本只对既有 mysql 容器执行 docker exec，不碰容器生命周期）
#   · 目标后端必须是**已包含两阶段撤销（含迁移 V7）的最新代码**，由调用方自己启动。脚本只发 HTTP 请求，
#     不启动、不重启、不结束任何后端进程；8081 上调用方自己启动的实例保持原样。
#   · 四个新端点是 POST /fd/v1/tickets/{ticketNo}/actions/{request-cancel|approve-cancel|reject-cancel|
#     withdraw-cancel-request}；旧构建（片 D 及以前）上这些路径是 404/RESOURCE_NOT_FOUND，因此跑本脚本
#     必须把基址指向新构建（并行后端 8092 是项目惯例）。
#
# 运行
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\slice-e-two-phase-cancel-acceptance.ps1
#   基址可覆盖（默认 http://127.0.0.1:8081，并行后端常用 8092）：
#     $env:FLOWDESK_BASE_URL = 'http://127.0.0.1:8092'   # 或 -BaseUrl http://127.0.0.1:8092
#   证据路径可覆盖：-OutFile <path> 或别名 -EvidencePath <path>
#   演示账号可用 E2E_EMPLOYEE_USERNAME / E2E_EMPLOYEE_PASSWORD、E2E_IT_* 覆盖
#   （缺省 employee / it，口令 123456）
#
# 本文件必须保存为「带 BOM 的 UTF-8」：Windows PowerShell 5.1 会按系统代码页读取无 BOM 的
# UTF-8 脚本，中文注释会被解析成乱码并报语法错误。证据文件相反，必须写成**无 BOM 的 UTF-8**。
#
# 证据
#   docs/acceptance/2026-10-08-slice-e-two-phase-cancel.json（无 BOM 的 UTF-8，脚本自己写出）
#   含：_note、invocation（**带 -BaseUrl**）、script.evidencePath（仓库根相对路径）、script / target /
#   preflight / tempPrincipals / cancelRequestFacts / fingerprints{before,after} / keyActions（每条带
#   X-Trace-Id）/ httpLog（step / method / path / status / traceId / detail）/ dbChecks / assertions
#   （name / expected / actual / why / passed）/ sqlExecuted / cleanup.sql / concurrency（三组）/ totals。
#
# 本片与片 D 的口径差异（本脚本的核心断言）
#   · 片 D：四种非终态下提交人都能直接撤销（cancel）。
#   · 片 E（2026-10-08 规则变更，docs/kickoff.md 4.7）：待受理没有负责人，提交人**直接撤销**；
#     处理中 / 待补充 / 待确认下提交人只能**发起撤销请求**，由**当前负责人批准或拒绝**，
#     提交人也可以**撤回**自己的请求。批准/拒绝只要 TICKET_PROCESS，**不要求** TICKET_CLOSE。
#   · 撤销请求不改变工单状态：三列（cancel_requested_at / cancel_request_reason /
#     cancel_request_deadline_at）落在 ticket 上，终态一律不带待决请求（V7 的两条 CHECK 强制）。
#
# 权限闸门顺序（三处都断言）
#   认证 401 → 权限 403 → 可见性 404 → 状态/身份/版本 409 → 字段 400。
#   · 「403 先于 404」用**不存在的编号**发权限不足的请求，仍必须得到 403 而不是 404；
#   · 「409 先于 400」在 HTTP 层**不可观察**（Bean Validation 在进服务层之前就把非法字段拦成 400），
#     本脚本如实记录这一口径，服务层顺序由 TicketServiceImplTest 直接覆盖（与片 D 同一处理方式）。
#
# 安全边界
#   · 只创建并删除脚本自己创建的工单、记录、参与关系、2 个临时用户与 2 个临时角色
#     （含它们的 iam_user_role / iam_role_permission）
#   · 不触碰运行前就存在的任何行：运行前后各测一次演示库指纹（八项计数 + 工单/记录/参与关系/
#     用户/用户角色/角色权限逐行指纹）并断言完全相同
#   · 全程只有一条写库 SQL 不是通过接口发出的（把自建工单的请求期限改成过去时间，用于证明
#     「到期不自动处置」），它按 ticket_no 精确命中本脚本自建的工单，且只改 cancel_request_deadline_at
#     一列；另有两条约束探针 SQL 会被数据库拒绝（探针若意外成功，立即回滚并记账）
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
    # 证据路径：-OutFile 与片 D 一致；别名 -EvidencePath 供调用方按另一种叫法覆盖
    [Alias('EvidencePath')]
    [string]$OutFile = (Join-Path $PSScriptRoot '..\docs\acceptance\2026-10-08-slice-e-two-phase-cancel.json'),
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
$sqlWorkDir = Join-Path $env:TEMP "flowdesk-slice-e-$stamp"
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
$script:cancelRequestInfo = [ordered]@{}
$script:deadlineProbeInfo = [ordered]@{}
$script:concurrencyDecideInfo = [ordered]@{}
$script:concurrencyWithdrawInfo = [ordered]@{}
$script:concurrencyCloseInfo = [ordered]@{}
$script:constraintProbeInfo = [ordered]@{}
$script:terminalInfo = [ordered]@{}

if ([string]::IsNullOrWhiteSpace($EmployeeUser)) { $EmployeeUser = 'employee' }
if ([string]::IsNullOrWhiteSpace($EmployeePassword)) { $EmployeePassword = '123456' }
if ([string]::IsNullOrWhiteSpace($ItUser)) { $ItUser = 'it' }
if ([string]::IsNullOrWhiteSpace($ItPassword)) { $ItPassword = '123456' }

# 临时主体命名：用户名与角色编码都带本次运行的 stamp，清理时按主键精确命中，
# 不可能碰到运行前就存在的行（演示库里没有这两个前缀）。
$tempUsernamePrefix = "slice-e-acc-$stamp"
$tempRoleCodePrefix = "SLICE_E_ACC_$($stamp.Replace('-','_'))"
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

# $Step 是断言编号式的短标签（如 a1.requestCancel），进 httpLog 的 step 字段；
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
        $contentType = '(none)'
        if ($null -ne $request.Content) { $contentType = 'application/json' }
        $script:httpLog.Add([pscustomobject]@{
                step           = $Step
                actor          = $Actor
                note           = $Note
                method         = $Method
                path           = $Path
                status         = $result.Status
                traceId        = $result.TraceId
                requestTraceId = $requestTraceId
                detail         = ("actor=" + $Actor + "；" + $Note + "；contentType=" + $contentType)
            })
        return $result
    } finally {
        $request.Dispose()
    }
}

# 两路请求真并发：先把两个 SendAsync 都发出去（返回 Task，不阻塞），再逐个取结果。
# 本脚本用它验证三组竞争（approve vs reject、approve vs withdraw、close vs approve）都是
# 「唯一胜者 + 败者必为 409」，而不是 InnoDB 死锁回滚出来的 500。
# PowerShell 5.1 没有 ForEach-Object -Parallel / Start-Job 的可用语义（后者另起进程、拿不到本进程的
# HttpClient），因此沿用片 D 已验证过的 [System.Net.Http.HttpClient]::SendAsync 双路派发写法。
function Send-ConcurrentPair {
    param(
        [System.Net.Http.HttpClient]$ClientA,
        [string]$PathA,
        [string]$TokenA,
        $BodyA,
        [string]$ActorA,
        [string]$StepA,
        [string]$NoteA,
        [System.Net.Http.HttpClient]$ClientB,
        [string]$PathB,
        [string]$TokenB,
        $BodyB,
        [string]$ActorB,
        [string]$StepB,
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
                step = $StepA; actor = $ActorA; note = $NoteA; method = 'POST'; path = $PathA
                status = $resultA.Status; traceId = $resultA.TraceId; requestTraceId = $traceA
                detail = ("并发 A 路：actor=" + $ActorA + "；" + $NoteA)
            })
        $script:httpLog.Add([pscustomobject]@{
                step = $StepB; actor = $ActorB; note = $NoteB; method = 'POST'; path = $PathB
                status = $resultB.Status; traceId = $resultB.TraceId; requestTraceId = $traceB
                detail = ("并发 B 路：actor=" + $ActorB + "；" + $NoteB)
            })

        return [pscustomobject]@{
            A            = $resultA
            B            = $resultB
            traceA       = $traceA
            traceB       = $traceB
            dispatchedAt = $dispatchedAt
            dispatchMode = '两个 SendAsync 先都发出，再逐个取结果（两路请求在网络上真实重叠）'
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
                detail         = ("actor=" + $Actor + "；" + $Note + "；contentType=multipart/form-data（ticket part 携 JSON）")
            })
        return $result
    } finally {
        $request.Dispose()
    }
}

# 员工补充是 multipart/form-data（`ticket` part 携 JSON，与建单同形，片 B 起的契约）：
# 这里单开一个函数而不是复用 Send-Req，因为后者只发 application/json。补充请求在
# `TicketController` 里是 multipart 端点，用 JSON 发会被判成缺 part。
function Send-SupplementAction {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Token,
        [string]$TicketNo,
        [long]$Version,
        [string]$Content,
        [string]$Step = '-',
        [string]$Actor = 'employee',
        [string]$Note = 'supplement'
    )
    $payload = @{ version = $Version; content = $Content } | ConvertTo-Json -Compress
    $path = "/fd/v1/tickets/$TicketNo/actions/supplement"
    $form = New-Object System.Net.Http.MultipartFormDataContent
    $form.Add((New-Object System.Net.Http.StringContent(
                $payload, [System.Text.Encoding]::UTF8, 'application/json')), 'ticket')
    $request = New-Object System.Net.Http.HttpRequestMessage(
        [System.Net.Http.HttpMethod]::Post, $path)
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
                path           = $path
                status         = $result.Status
                traceId        = $result.TraceId
                requestTraceId = $requestTraceId
                detail         = ("actor=" + $Actor + "；" + $Note + "；contentType=multipart/form-data（ticket part 携 JSON）")
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

# 409 的 data 里带当前快照，供前端刷新后重试。字段名是 ErrorDetails 的
# version / status（docs/api-design.md 10.2 把它描述为「当前 version 与 status 的最小冲突信息」）。
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

# 逐条断言：名称、期望、实际、为什么重要、X-Trace-Id、通过与否。$Result 可空（非 HTTP 断言）。
# 失败时把「期望 / 实际 / 为什么重要」三行都打出来，避免只看到一个布尔值而不知道错在哪。
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

# 工单快照：状态、关闭字段、结束时间、期限、负责人、完成方式、版本与记录序号、请求三列。
function Get-TicketDbRow {
    param([string]$TicketNo)
    $sql = "SELECT status, IFNULL(close_method,'NULL'), IFNULL(close_reason,'NULL'), " +
    "IF(ended_at IS NULL,'NULL','SET'), " +
    "IFNULL(DATE_FORMAT(action_deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'), " +
    "IFNULL(assignee_id,-1), IFNULL(completion_method,'NULL'), version, record_seq, requester_id, " +
    "IFNULL(DATE_FORMAT(cancel_requested_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'), " +
    "IFNULL(cancel_request_reason,'NULL'), " +
    "IFNULL(DATE_FORMAT(cancel_request_deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'), " +
    "IF(cancel_requested_at IS NULL,'NO_REQUEST','HAS_REQUEST') " +
    "FROM ticket WHERE ticket_no = '$TicketNo';"
    $row = Get-MySqlRow -Sql $sql
    if (-not $row.ok) { return $null }
    $columns = @($row.columns)
    if ($columns.Count -lt 14) { return $null }
    return [pscustomobject]@{
        status                  = $columns[0]
        closeMethod             = $columns[1]
        closeReason             = $columns[2]
        endedAt                 = $columns[3]
        actionDeadlineAt        = $columns[4]
        assigneeId              = [int]$columns[5]
        completionMethod        = $columns[6]
        version                 = [int]$columns[7]
        recordSeq               = [int]$columns[8]
        requesterId             = [int]$columns[9]
        cancelRequestedAt       = $columns[10]
        cancelRequestReason     = $columns[11]
        cancelRequestDeadlineAt = $columns[12]
        cancelRequestState      = $columns[13]
        raw                     = $row.raw
    }
}

# 「库里没变」的统一快照串：被拒的 400/403/409 请求前后各取一次，字符串相同即证明什么都没改。
# 请求三列也在串里——两阶段撤销的新增列必须同样受"被拒的请求不留痕"约束。
function Get-TicketStateSignature {
    param([string]$TicketNo)
    $sql = "SELECT CONCAT_WS('|', t.status, t.category_id, t.priority, IFNULL(t.assignee_id,-1), " +
    "IFNULL(DATE_FORMAT(t.action_deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'), t.version, t.record_seq, " +
    "IFNULL(t.close_method,'NULL'), IFNULL(t.close_reason,'NULL'), IFNULL(t.completion_method,'NULL'), " +
    "IF(t.ended_at IS NULL,'NULL','SET'), " +
    "IFNULL(DATE_FORMAT(t.cancel_requested_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'), " +
    "IFNULL(t.cancel_request_reason,'NULL'), " +
    "IFNULL(DATE_FORMAT(t.cancel_request_deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'), " +
    "(SELECT COUNT(*) FROM ticket_record r WHERE r.ticket_id = t.id), " +
    "(SELECT COUNT(*) FROM ticket_relation rel WHERE rel.source_ticket_id = t.id)) " +
    "FROM ticket t WHERE t.ticket_no = '$TicketNo';"
    $row = Get-MySqlRow -Sql $sql
    if (-not $row.ok) { return "UNREADABLE[$($row.raw)]" }
    return $row.raw
}

# 时间线最后一条记录：类型、两侧状态、原因（含长度）、期限、关闭字段与序号。
function Get-LastRecordRow {
    param([string]$TicketNo)
    $sql = "SELECT record_type, IFNULL(from_status,'-'), IFNULL(to_status,'-'), " +
    "IFNULL(reason,'-'), IFNULL(CHAR_LENGTH(reason),-1), IFNULL(close_method,'-'), IFNULL(close_reason,'-'), " +
    "sequence_no, IFNULL(DATE_FORMAT(deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL') " +
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
        deadlineAt  = $columns[8]
        raw         = $row.raw
    }
}

# 某个工单上全部撤销相关记录（CANCELLATION / CANCELLATION_REQUEST / ..._APPROVED / ..._REJECTED /
# ..._REQUEST_WITHDRAWN），按序号升序，用于断言"哪几条记录、什么顺序、各自的 from/to 与原因"。
function Get-CancelRecordRows {
    param([string]$TicketNo)
    $sql = "SELECT IFNULL(GROUP_CONCAT(CONCAT(sequence_no,':',record_type,':',IFNULL(from_status,'-'),'>'," +
    "IFNULL(to_status,'-'),':',IFNULL(reason,'-'),':',IFNULL(CHAR_LENGTH(reason),-1),':'," +
    "IFNULL(DATE_FORMAT(deadline_at,'%Y-%m-%d %H:%i:%s'),'NULL')) ORDER BY sequence_no SEPARATOR ' ;; '),'-') " +
    "FROM ticket_record WHERE ticket_id = (SELECT id FROM ticket WHERE ticket_no = '$TicketNo') " +
    "AND record_type LIKE 'CANCELLATION%';"
    $row = Get-MySqlRow -Sql $sql
    if (-not $row.ok) { return "UNREADABLE[$($row.raw)]" }
    return $row.raw
}

# 某一类记录在本工单上的条数（例如"驳回后再次发起"必须累计两条 CANCELLATION_REQUEST）。
function Get-RecordCountByType {
    param([string]$TicketNo, [string]$RecordType)
    $sql = "SELECT COUNT(*) FROM ticket_record WHERE record_type = '$RecordType' " +
    "AND ticket_id = (SELECT id FROM ticket WHERE ticket_no = '$TicketNo');"
    $row = Get-MySqlRow -Sql $sql
    if (-not $row.ok) { return -1 }
    return [int]$row.columns[0]
}

# 时间线记录类型序列（按序号），用于断言历史没有被改写、以及新记录追加在末尾。
function Get-RecordTypeSequence {
    param([string]$TicketNo)
    $sql = "SELECT IFNULL(GROUP_CONCAT(record_type ORDER BY sequence_no SEPARATOR ','),'-') " +
    "FROM ticket_record WHERE ticket_id = (SELECT id FROM ticket WHERE ticket_no = '$TicketNo');"
    $row = Get-MySqlRow -Sql $sql
    if (-not $row.ok) { return "UNREADABLE[$($row.raw)]" }
    return $row.raw
}

# 演示库指纹：运行前与清理后各测一次，断言完全相同（既有行一行都不许变）。
# 八项计数按验收要求逐项记录：ticket / ticket_record / ticket_participant / ticket_relation /
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
        "IFNULL(assignee_id,-1),'|',IFNULL(DATE_FORMAT(action_deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'),'|'," +
        "IF(ended_at IS NULL,'NULL','SET'),'|',IFNULL(completion_method,'NULL'),'|'," +
        "IFNULL(close_method,'NULL'),'|',IFNULL(close_reason,'NULL'),'|'," +
        "IFNULL(DATE_FORMAT(cancel_requested_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'),'|'," +
        "IFNULL(cancel_request_reason,'NULL'),'|'," +
        "IFNULL(DATE_FORMAT(cancel_request_deadline_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'),'|'," +
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

function Add-KeyAction {
    param(
        [int]$Order,
        [string]$Actor,
        [string]$Action,
        [string]$Method,
        [string]$Path,
        [string]$TicketNo,
        $Result,
        [string]$StatusAfter,
        [string]$Step = '-'
    )
    $version = $null
    if ($null -ne $Result) {
        $data = Get-Data $Result
        if ($null -ne $data) { $version = $data.version }
    }
    $script:keyActions.Add([pscustomobject]@{
            order        = $Order
            step         = $Step
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

function Get-ActionPath {
    param([string]$TicketNo, [string]$Action)
    return "/fd/v1/tickets/$TicketNo/actions/$Action"
}

# 请求体构造：四种动作的字段各不相同，统一在这里收口，避免手抄字段名打错字。
function New-RequestCancelBody {
    param([long]$Version, [string]$Reason, [switch]$OmitVersion, [switch]$OmitReason)
    $body = @{}
    if (-not $OmitVersion) { $body['version'] = $Version }
    if (-not $OmitReason) { $body['reason'] = $Reason }
    return $body
}

function New-RejectCancelBody {
    param([long]$Version, [string]$Reason, [switch]$OmitVersion, [switch]$OmitReason)
    $body = @{}
    if (-not $OmitVersion) { $body['version'] = $Version }
    if (-not $OmitReason) { $body['reason'] = $Reason }
    return $body
}

# approve-cancel 与 withdraw-cancel-request 都只携带版本（批注在各自 Command 的 javadoc 里）。
function New-VersionOnlyBody {
    param([long]$Version, [switch]$OmitVersion)
    $body = @{}
    if (-not $OmitVersion) { $body['version'] = $Version }
    return $body
}

function New-CancelBody {
    param([long]$Version, [string]$Reason, [switch]$OmitVersion, [switch]$OmitReason)
    $body = @{}
    if (-not $OmitVersion) { $body['version'] = $Version }
    if (-not $OmitReason) { $body['reason'] = $Reason }
    return $body
}

function New-ContentBody {
    param([long]$Version, [string]$Content)
    return @{ version = $Version; content = $Content }
}

function New-CloseBody {
    param([long]$Version, [string]$ReasonCode, [string]$Description)
    return @{ version = $Version; reasonCode = $ReasonCode; description = $Description }
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

# 详情里没有待决请求时，cancelRequest 这个键必须**不出现**（spring.jackson non_null）。
function Test-CancelRequestAbsent {
    param($Result)
    if ($null -eq $Result) { return $false }
    return (([string]$Result.Body) -notmatch '"cancelRequest"')
}

# 时间串比较统一在**秒级**做：MySQL 返回 "2026-10-11 02:14:27.027000"，响应是
# "2026-10-11T02:14:27.027Z"，直接 Parse 会带上本机时区；截到秒既排除时区，
# 也容纳 DATETIME(3) 与 ISO 之间的微秒表示差异（与片 B 的既有做法一致）。
function ConvertTo-SecondString {
    param([string]$Value)
    if ([string]::IsNullOrEmpty($Value)) { return '' }
    $normalized = $Value.Replace('T', ' ')
    if ($normalized.Length -lt 19) { return $normalized }
    return $normalized.Substring(0, 19)
}

# 撤销相关动作的统一入口：路径、方法、traceId 收口在一处。
function Send-CancelAction {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Token,
        [string]$TicketNo,
        [string]$Action,
        $Body,
        [string]$Step,
        [string]$Actor,
        [string]$Note
    )
    return Send-Req -Client $Client -Method Post -Path (Get-ActionPath $TicketNo $Action) -Token $Token `
        -Body $Body -Step $Step -Actor $Actor -Note $Note
}

# 临时主体：本脚本的权限与身份格子需要两类演示库里不存在的账号
#   · u1「只持 EMPLOYEE 的三条权限码」——作为"另一位提交人"，用来构造他人的工单与不可见口径
#   · u5「内置 IT_SUPPORT + 额外 TICKET_REQUESTER_ACTION」——既是当前负责人、又持有提交人动作权限，
#     用来构造「有权限但不是提交人」的 409（这是唯一能真正走到身份闸门的组合）
# 用户行的 password 摘要**复制**演示用户 it 的 password 列（不硬编码、不打印、不落盘），
# 因此临时用户的登录口令等于 -ItPassword / E2E_IT_PASSWORD。
function New-TempPrincipal {
    param(
        [string]$Key,
        [string]$Label,
        [string]$Purpose,
        [string[]]$PermissionCodes = @(),
        [switch]$WithItSupportRole
    )

    $username = "$tempUsernamePrefix-$Key"
    $displayName = "片E验收$Label-$stamp"
    $roleCode = "$tempRoleCodePrefix-$($Key.ToUpperInvariant())"

    $statements = [System.Collections.Generic.List[string]]::new()
    $statements.Add("INSERT INTO iam_role (code, name, description, created_at) VALUES " +
        "('$roleCode', '$displayName 角色', '片 E 真实验收临时角色（脚本创建，收尾删除）', UTC_TIMESTAMP(3));")
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
        -Actual ("username=$username roleCode=$roleCode userId=$userId roleId=$roleId " +
        "rolePermissionCount=$grantCount userRoleCount=$userRoleCount exitCode=$($insertResult.exitCode) " +
        "permissions=$(($PermissionCodes -join '|')) withItSupportRole=$([bool]$WithItSupportRole) " +
        "stderr=[$(Get-BriefText $insertResult.stderrText 120)]") `
        -Why '临时主体是本脚本权限与身份格子的唯一装置；建不出来后面的 403/404/409 格子无法进行，必须就地失败' `
        -Result $null
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
$anonymousClient = New-Client
$u1Client = New-Client
$u5Client = New-Client
$clients = @($employeeClient, $itClient, $itSecondClient, $anonymousClient, $u1Client, $u5Client)

$unknownTicketNo = 'FD-19990101-001'
$employeeUserId = -1
$itUserId = -1
$demoBefore = $null
$demoAfter = $null
$cancelWindowSeconds = 3 * 24 * 3600

# 载体工单（全部由接口创建，收尾按 ticket_no 精确删除）
$ticketMain = ''          # 待补充主链：发起 → 批准
$ticketReject = ''        # 处理中：拒绝 → 再次发起 → 撤回
$ticketPending = ''       # 待受理：request-cancel 409、cancel 200
$ticketRaceClose = ''     # 处理中：挂请求后 IT 仍可写处理记录，再跑 close vs approve 并发
$ticketIdentity = ''      # 处理中（负责人是 u5）：身份不符 409 两格
$ticketRaceDecide = ''    # 处理中：approve vs reject 并发
$ticketRaceWithdraw = ''  # 待确认：approve vs withdraw 并发
$ticketCloseClears = ''   # 处理中：挂请求后直接 close，证明 close 会清空请求三列
$ticketConfirmClears = '' # 待确认：挂请求后 confirm-resolution，证明确认也会清空请求三列
$ticketOtherEmployee = '' # u1 提交的工单：404 用
$ticketConstraint = ''    # 处理中且无请求：约束探针用

$verMain = -1
$verReject = -1
$verPending = -1
$verRaceClose = -1
$verIdentity = -1
$verRaceDecide = -1
$verRaceWithdraw = -1
$verCloseClears = -1
$verConfirmClears = -1
$verConstraint = -1

$mainSupplementDeadline = ''
$mainRequestDeadline = ''
$mainRequestedAt = ''

# 载体工单由接口创建：ticketNo 与 version 都取自响应，绝不用 SQL 造一个"看起来像工单"的行。
function New-CarrierTicket {
    param(
        [string]$Key,
        [string]$Step,
        [string]$Title,
        [string]$Purpose,
        [System.Net.Http.HttpClient]$RequesterClient,
        [string]$RequesterToken,
        [string]$RequesterActor,
        [long]$CategoryId
    )
    $created = Send-CreateTicket -Client $RequesterClient -Token $RequesterToken `
        -Title $Title -Description $Purpose -CategoryId $CategoryId `
        -Step "$Step.create" -Actor $RequesterActor -Note "建载体工单 $Key：$Purpose"
    $ticketNo = ''
    $version = -1
    if ($created.Status -eq 201) {
        $data = Get-Data $created
        $ticketNo = [string]$data.ticketNo
        $version = [long]$data.version
    }
    Add-Assertion -Name "$Step.create.201" -Condition ($created.Status -eq 201) `
        -Expected "201（提交人 $RequesterActor 建单）" `
        -Actual "status=$($created.Status) code=$(Get-Code $created) ticketNo=$ticketNo version=$version" `
        -Why "载体工单建不出来，本组后续所有断言都无从谈起（创建是唯一不携带 version 的写操作）" `
        -Result $created
    if ($created.Status -ne 201) {
        throw "载体工单 $Key 创建失败（status=$($created.Status) code=$(Get-Code $created)）；后续断言无法进行"
    }
    $script:ticketsInfo[$Key] = @{ ticketNo = $ticketNo; purpose = $Purpose; requester = $RequesterActor }
    return [pscustomobject]@{ ticketNo = $ticketNo; version = $version; result = $created }
}

# 领取由真实 IT 主体发起（内置 IT_SUPPORT 角色的资格判定在服务端复核，不能用 SQL 跳过）。
function Invoke-ClaimCarrier {
    param(
        [string]$Step,
        [string]$TicketNo,
        [long]$Version,
        [System.Net.Http.HttpClient]$Client,
        [string]$Token,
        [string]$Actor
    )
    $result = Send-CancelAction -Client $Client -Token $Token -TicketNo $TicketNo -Action 'claim' `
        -Body (New-VersionOnlyBody -Version $Version) -Step "$Step.claim" -Actor $Actor `
        -Note '领取：让工单进入处理中，后续请求才有负责人可以批准/拒绝'
    $data = Get-Data $result
    $ok = (($result.Status -eq 200) -and ($null -ne $data) -and ($data.status -eq 'PROCESSING'))
    Add-Assertion -Name "$Step.claim.200" -Condition $ok -Expected '200 且 status=PROCESSING' `
        -Actual "status=$($result.Status) code=$(Get-Code $result) businessStatus=$($data.status) version=$($data.version)" `
        -Why '两阶段撤销只在「有人负责」的状态上成立；领取不成功说明载体工单没进入可验证的前置状态' `
        -Result $result
    if (-not $ok) { throw "载体工单 $TicketNo 领取失败（status=$($result.Status) code=$(Get-Code $result)）" }
    return [pscustomobject]@{ version = [long]$data.version; result = $result }
}

# 断言"详情里带着一份与库一致的待批请求"：三个字段逐个比对（时间比较到秒）。
function Assert-CancelRequestPresent {
    param(
        [string]$Name,
        $Result,
        [string]$ExpectedRequestedAt,
        [string]$ExpectedDeadlineAt,
        [string]$ExpectedReason,
        [string]$Detail
    )
    $data = Get-Data $Result
    $cancelRequest = $null
    if ($null -ne $data) { $cancelRequest = $data.cancelRequest }
    $present = $null -ne $cancelRequest
    Add-Assertion -Name "$Name.present" -Condition $present `
        -Expected '详情返回 cancelRequest（requestedAt / deadlineAt / reason）' `
        -Actual ("cancelRequest=" + $(if ($present) { 'present' } else { 'absent' }) +
        " reason=[$(if ($present) { $cancelRequest.reason } else { '-' })]") `
        -Why '请求期间工单状态不变，界面只能靠 cancelRequest 渲染待批准提示与三个决策入口；缺了它 IT 根本不知道有人在等批准' `
        -Result $Result
    if (-not $present) { return }

    $requestedOk = ((ConvertTo-SecondString $cancelRequest.requestedAt) -eq (ConvertTo-SecondString $ExpectedRequestedAt))
    $deadlineOk = ((ConvertTo-SecondString $cancelRequest.deadlineAt) -eq (ConvertTo-SecondString $ExpectedDeadlineAt))
    $reasonOk = ($cancelRequest.reason -eq $ExpectedReason)
    Add-Assertion -Name "$Name.matchesDb" -Condition ($requestedOk -and $deadlineOk -and $reasonOk) `
        -Expected ("requestedAt / deadlineAt 与库内 cancel_requested_at / cancel_request_deadline_at 秒级一致，" +
        "reason 与库内 cancel_request_reason 逐字一致") `
        -Actual ("requestedAt=$($cancelRequest.requestedAt) vs db=$ExpectedRequestedAt（$requestedOk）；" +
        "deadlineAt=$($cancelRequest.deadlineAt) vs db=$ExpectedDeadlineAt（$deadlineOk）；" +
        "reason=[$(Get-BriefText $cancelRequest.reason)] vs db=[$(Get-BriefText $ExpectedReason)]（$reasonOk）") `
        -Why '详情是 IT 唯一能看到"为什么被要求撤销、什么时候到期"的入口；与库不一致会让批准决定建立在错误信息上' `
        -Result $Result
    $script:cancelRequestInfo[$Detail] = [ordered]@{
        requestedAt = $cancelRequest.requestedAt
        deadlineAt  = $cancelRequest.deadlineAt
        reason      = $cancelRequest.reason
    }
}

# ── 2. 主体流程 ─────────────────────────────────────────────────────────────
try {
    # ── 2.1 前置与基线 ──────────────────────────────────────────────────────
    Add-Assertion -Name 'precondition.dotEnvLoaded' -Condition $script:dbAvailable `
        -Expected '.env 中 FLOWDESK_DB_NAME 与 FLOWDESK_MYSQL_ROOT_PASSWORD 可读' `
        -Actual "dbContainer=$DbContainer dbNameAvailable=$(-not [string]::IsNullOrWhiteSpace($dbName)) rootPasswordAvailable=$(-not [string]::IsNullOrWhiteSpace($rootPassword))（值不打印）" `
        -Why '数据库直查、约束探针与收尾清理都依赖它；读不到就没法证明"被拒的请求没留痕"与"演示库回到基线"' `
        -Result $null
    if (-not $script:dbAvailable) {
        throw "无法从 $EnvFile 读取演示库连接信息（FLOWDESK_DB_NAME / FLOWDESK_MYSQL_ROOT_PASSWORD），数据库直查与清理无法进行"
    }

    $demoBefore = Get-DemoFingerprint
    Add-Assertion -Name 'baseline.demoDatabaseReadBeforeRun' -Condition $demoBefore.readOk `
        -Expected '运行前可读取演示库计数与逐行指纹' `
        -Actual ("运行前：工单 {0} / 记录 {1} / 参与者 {2} / 关联 {3} / 用户 {4} / 用户角色 {5} / 角色 {6} / 角色权限 {7}；工单号=[{8}]" -f `
            $demoBefore.tickets, $demoBefore.records, $demoBefore.participants, $demoBefore.relations, `
            $demoBefore.users, $demoBefore.userRoles, $demoBefore.roles, $demoBefore.rolePermissions, $demoBefore.ticketNos) `
        -Why '运行前基线是"只新增再删除自己的行"这一承诺的比较对象；取不到基线就无法证明没有污染演示库' `
        -Result $null
    Add-Assertion -Name 'baseline.ticketNoFingerprintRecorded' -Condition ($demoBefore.ticketNos.Length -gt 0) `
        -Expected '运行前工单号列表已完整记录（收尾清理后必须逐字相同）' `
        -Actual "工单号=[$($demoBefore.ticketNos)]" `
        -Why '工单号列表是最直观的"既有数据没被动过"的证据；本脚本只新增再删除自己创建的行' `
        -Result $null
    Add-Assertion -Name 'baseline.idempotencyScopeDeclared' -Condition $true `
        -Expected '脚本只新增再删除自己创建的行：11 张载体工单 + 其记录/参与关系 + 2 个临时用户与 2 个临时角色' `
        -Actual '清理段按 ticket_no 精确删除自建工单，按主键 ID 精确删除临时用户与临时角色（含 iam_user_role / iam_role_permission）' `
        -Why '片 D 曾因按前缀批量清理而误删一个运行前就存在的孤儿角色；本脚本不接受任何"看起来像自建"的模糊匹配' `
        -Result $null
    Write-Host "演示库基线：$($demoBefore.ticketNos)（工单 $($demoBefore.tickets) / 记录 $($demoBefore.records) / 参与者 $($demoBefore.participants) / 关联 $($demoBefore.relations) / 用户 $($demoBefore.users)）"
    Write-Host "目标后端：$baseUrl（脚本只发 HTTP，不启动/不重启/不结束任何后端进程）"

    # ── 2.2 未认证与旧构建探测 ──────────────────────────────────────────────
    $anonDetail = Send-Req -Client $anonymousClient -Method Get -Path "/fd/v1/tickets/$unknownTicketNo" `
        -Token $null -Step 'preflight.anonymous.401' -Actor 'anonymous' -Note '不带令牌读取工单详情'
    Add-Assertion -Name 'preflight.anonymous.getTicket.401' `
        -Condition (($anonDetail.Status -eq 401) -and ((Get-Code $anonDetail) -eq 'AUTH_REQUIRED')) `
        -Expected '401 + AUTH_REQUIRED' `
        -Actual "status=$($anonDetail.Status) code=$(Get-Code $anonDetail)" `
        -Why '认证闸门必须排在权限与可见性之前；带着匿名身份能读到工单说明整条过滤链失效' `
        -Result $anonDetail

    $itProbeUnknown = Send-CancelAction -Client $itClient -Token $null -TicketNo $unknownTicketNo `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version 0 -Reason '未认证探测') `
        -Step 'preflight.unauth.requestCancel' -Actor 'anonymous' -Note '不带令牌发起撤销请求'
    Add-Assertion -Name 'preflight.unauth.requestCancel.401' `
        -Condition (($itProbeUnknown.Status -eq 401) -and ((Get-Code $itProbeUnknown) -eq 'AUTH_REQUIRED')) `
        -Expected '401 + AUTH_REQUIRED' `
        -Actual "status=$($itProbeUnknown.Status) code=$(Get-Code $itProbeUnknown)" `
        -Why '新端点同样受统一安全链保护；若返回 403/404 说明它被放进了匿名可及的路径' `
        -Result $itProbeUnknown

    # ── 2.3 三角色登录 ──────────────────────────────────────────────────────
    $loginEmployee = Send-Req -Client $employeeClient -Method Post -Path '/fd/v1/auth/login' `
        -Body @{ username = $EmployeeUser; password = $EmployeePassword } -Step 'login.employee' `
        -Actor 'employee' -Note '演示员工登录'
    $loginIt = Send-Req -Client $itClient -Method Post -Path '/fd/v1/auth/login' `
        -Body @{ username = $ItUser; password = $ItPassword } -Step 'login.it' -Actor 'it' -Note '演示 IT 登录'
    Add-Assertion -Name 'login.employee.200' -Condition ($loginEmployee.Status -eq 200) -Expected '200' `
        -Actual "status=$($loginEmployee.Status) code=$(Get-Code $loginEmployee)" `
        -Why '提交人侧动作（发起/撤回）全部由这个身份执行；登录失败说明演示账号口令与 .env/默认值不一致' `
        -Result $loginEmployee
    Add-Assertion -Name 'login.it.200' -Condition ($loginIt.Status -eq 200) -Expected '200' `
        -Actual "status=$($loginIt.Status) code=$(Get-Code $loginIt)" `
        -Why '负责人侧动作（批准/拒绝）全部由这个身份执行；登录失败则 403/409 格子无从区分' `
        -Result $loginIt
    if (($loginEmployee.Status -ne 200) -or ($loginIt.Status -ne 200)) {
        throw "演示账号登录未全部返回 200（employee=$($loginEmployee.Status) it=$($loginIt.Status)）；口令错误或账号被改，请用 E2E_* 覆盖或在演示库核对账号"
    }
    $employeeToken = (Get-Data $loginEmployee).accessToken
    $itToken = (Get-Data $loginIt).accessToken

    $loginItSecond = Send-Req -Client $itSecondClient -Method Post -Path '/fd/v1/auth/login' `
        -Body @{ username = $ItUser; password = $ItPassword } -Step 'login.itSecond' `
        -Actor 'it(第二会话)' -Note '同一个 IT 的第二个会话，供"同一负责人并发批准/拒绝"使用'
    Add-Assertion -Name 'login.itSecondSession.200' -Condition ($loginItSecond.Status -eq 200) `
        -Expected '200（第二个会话）' `
        -Actual "status=$($loginItSecond.Status) code=$(Get-Code $loginItSecond)" `
        -Why '「同一负责人并发 approve + reject」需要两路真并发；同一 HttpClient 的两路请求可能被连接上限排队，因此另开会话' `
        -Result $loginItSecond
    $itToken2 = ''
    if ($loginItSecond.Status -eq 200) { $itToken2 = (Get-Data $loginItSecond).accessToken }

    # 旧构建探测：两个新端点在片 D 及以前的构建上不存在，会以 404/RESOURCE_NOT_FOUND 落地。
    # 这一格不通过时先换基址，不要把它当成业务结论。
    $probeRequestCancel = Send-CancelAction -Client $employeeClient -Token $employeeToken `
        -TicketNo $unknownTicketNo -Action 'request-cancel' `
        -Body (New-RequestCancelBody -Version 0 -Reason '端点存活探测') `
        -Step 'preflight.endpointAlive.requestCancel' -Actor 'employee' -Note '用不存在的编号探测 request-cancel 端点是否存在'
    Add-Assertion -Name 'preflight.endpointAlive.requestCancel' `
        -Condition (($probeRequestCancel.Status -eq 404) -and ((Get-Code $probeRequestCancel) -eq 'TICKET_NOT_FOUND')) `
        -Expected '404 + TICKET_NOT_FOUND（编号不存在，但端点本身必须存在）' `
        -Actual ("status=$($probeRequestCancel.Status) code=$(Get-Code $probeRequestCancel)；" +
        "若 code=RESOURCE_NOT_FOUND 说明目标后端是旧构建（片 D 及以前），请把 -BaseUrl 指向已包含两阶段撤销的新后端") `
        -Why '新端点不存在与业务 404 在状态码上都是 404，只有业务码能区分；混在一起会把"打错后端"报成业务断言失败' `
        -Result $probeRequestCancel
    $probeApproveCancel = Send-CancelAction -Client $itClient -Token $itToken -TicketNo $unknownTicketNo `
        -Action 'approve-cancel' -Body (New-VersionOnlyBody -Version 0) `
        -Step 'preflight.endpointAlive.approveCancel' -Actor 'it' -Note '用不存在的编号探测 approve-cancel 端点是否存在'
    Add-Assertion -Name 'preflight.endpointAlive.approveCancel' `
        -Condition (($probeApproveCancel.Status -eq 404) -and ((Get-Code $probeApproveCancel) -eq 'TICKET_NOT_FOUND')) `
        -Expected '404 + TICKET_NOT_FOUND' `
        -Actual "status=$($probeApproveCancel.Status) code=$(Get-Code $probeApproveCancel)" `
        -Why '同上；approve-cancel 与 reject-cancel 是片 E 的负责人侧入口，缺席说明后端没有 V7 之后的代码' `
        -Result $probeApproveCancel
    if (((Get-Code $probeRequestCancel) -eq 'RESOURCE_NOT_FOUND') -or ((Get-Code $probeApproveCancel) -eq 'RESOURCE_NOT_FOUND')) {
        throw "目标后端 $baseUrl 上没有两阶段撤销端点（RESOURCE_NOT_FOUND）；请用 -BaseUrl 指向已包含片 E 代码的新构建"
    }

    # 权限闸门先于可见性：it 没有 TICKET_REQUESTER_ACTION，对**不存在的编号**也必须 403 而不是 404。
    $permBeforeVisibility = Send-CancelAction -Client $itClient -Token $itToken -TicketNo $unknownTicketNo `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version 0 -Reason '权限先于可见性探测') `
        -Step 'preflight.permissionBeforeVisibility' -Actor 'it' -Note 'IT 对不存在的编号发起撤销请求：应因缺权限 403'
    Add-Assertion -Name 'preflight.permissionBeforeVisibility.requestCancel.403' `
        -Condition (($permBeforeVisibility.Status -eq 403) -and ((Get-Code $permBeforeVisibility) -eq 'TICKET_ACTION_FORBIDDEN')) `
        -Expected '403 + TICKET_ACTION_FORBIDDEN（而不是 404）' `
        -Actual "status=$($permBeforeVisibility.Status) code=$(Get-Code $permBeforeVisibility)" `
        -Why '门禁顺序是「权限 → 可见性 → 状态/身份」；若这里返回 404，说明服务先把编号解析成了不可见，顺序反了' `
        -Result $permBeforeVisibility

    # ── 2.4 分类选项与临时主体 ──────────────────────────────────────────────
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

    $u1 = New-TempPrincipal -Key 'employeeonly' -Label '只持员工权限' `
        -Purpose '作为"另一位提交人"建一张真实工单（供 404 与不可见口径使用）' `
        -PermissionCodes @('TICKET_CREATE', 'TICKET_VIEW_OWN', 'TICKET_REQUESTER_ACTION')
    $u5 = New-TempPrincipal -Key 'itplusrequester' -Label 'IT加提交人权限' `
        -Purpose '内置 IT_SUPPORT + 额外 TICKET_REQUESTER_ACTION：领取他人工单后既是当前负责人、又持有提交人动作权限，用来构造"有权限但不是提交人"的 409' `
        -PermissionCodes @('TICKET_REQUESTER_ACTION') -WithItSupportRole

    $loginU1 = Send-Req -Client $u1Client -Method Post -Path '/fd/v1/auth/login' `
        -Body @{ username = $u1.username; password = $ItPassword } -Step 'setup.login.u1' `
        -Actor 'temp-employee-only' -Note '临时员工登录（口令摘要复制自 it）'
    $loginU5 = Send-Req -Client $u5Client -Method Post -Path '/fd/v1/auth/login' `
        -Body @{ username = $u5.username; password = $ItPassword } -Step 'setup.login.u5' `
        -Actor 'temp-it-plus-requester' -Note '临时 IT（含提交人权限）登录'
    Add-Assertion -Name 'setup.temp.u1.login.200' -Condition ($loginU1.Status -eq 200) -Expected '200' `
        -Actual "status=$($loginU1.Status) code=$(Get-Code $loginU1)" `
        -Why '口令摘要复制自 it，因此必须能用 -ItPassword 登录；不通过说明 E2E_IT_PASSWORD 与库里 it 的口令不一致' `
        -Result $loginU1
    Add-Assertion -Name 'setup.temp.u5.login.200' -Condition ($loginU5.Status -eq 200) -Expected '200' `
        -Actual "status=$($loginU5.Status) code=$(Get-Code $loginU5)" `
        -Why '同上；u5 是身份 409 两格的唯一装置' `
        -Result $loginU5
    if (($loginU1.Status -ne 200) -or ($loginU5.Status -ne 200)) {
        throw "临时主体登录未全部返回 200（u1=$($loginU1.Status) u5=$($loginU5.Status)）；权限与身份格子无法进行"
    }
    $u1Token = (Get-Data $loginU1).accessToken
    $u5Token = (Get-Data $loginU5).accessToken
    $employeeUserId = [int](Get-MySqlRow -Sql "SELECT IFNULL(id,-1) FROM iam_user WHERE username = '$EmployeeUser';").columns[0]
    $itUserId = [int](Get-MySqlRow -Sql "SELECT IFNULL(id,-1) FROM iam_user WHERE username = '$ItUser';").columns[0]
    $script:tempPrincipalInfo = [ordered]@{
        u1 = @{ username = $u1.username; userId = $u1.userId; roleCode = $u1.roleCode; purpose = $u1.purpose; permissions = $u1.permissionCodes }
        u5 = @{ username = $u5.username; userId = $u5.userId; roleCode = $u5.roleCode; purpose = $u5.purpose; permissions = $u5.permissionCodes; withItSupportRole = $true }
        passwordNote = '临时用户的 password 摘要由 SQL 从演示用户 it 复制，未硬编码、未打印、未落盘；收尾按主键删除'
    }

    # ── 3. 断言组 a1：主链（待补充路径）发起 → 批准 ─────────────────────────
    $carrierMain = New-CarrierTicket -Key 'main' -Step 'a1.main' `
        -Title "E2E 片E主链 $stamp" -Purpose '片 E 主链：待补充 → 发起撤销请求 → 批准 → 已取消' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketMain = $carrierMain.ticketNo
    $verMain = $carrierMain.version
    $claimMain = Invoke-ClaimCarrier -Step 'a1.main' -TicketNo $ticketMain -Version $verMain `
        -Client $itClient -Token $itToken -Actor 'it'
    $verMain = $claimMain.version

    $supplementMain = Send-CancelAction -Client $itClient -Token $itToken -TicketNo $ticketMain `
        -Action 'request-supplement' -Body (New-ContentBody -Version $verMain -Content '请补充打印机型号与完整报错信息。') `
        -Step 'a1.main.requestSupplement' -Actor 'it' -Note 'IT 请求补充：让工单进入「待补充」，这是唯一"既有负责人又带期限"的起点'
    $supplementData = Get-Data $supplementMain
    $supplementOk = (($supplementMain.Status -eq 200) -and ($null -ne $supplementData) -and
        ($supplementData.status -eq 'WAITING_FOR_REQUESTER'))
    Add-Assertion -Name 'a1.main.requestSupplement.200' -Condition $supplementOk `
        -Expected '200 且 status=WAITING_FOR_REQUESTER' `
        -Actual "status=$($supplementMain.Status) code=$(Get-Code $supplementMain) businessStatus=$($supplementData.status) deadline=$($supplementData.actionDeadlineAt)" `
        -Why '待补充是"状态与期限都必须原样保留"这一条的最强样本：批准时才需要把期限清掉' `
        -Result $supplementMain
    if (-not $supplementOk) { throw "主链工单未能进入待补充（status=$($supplementMain.Status)）" }
    $verMain = [long]$supplementData.version
    $mainSupplementDeadline = [string]$supplementData.actionDeadlineAt
    Add-KeyAction -Order 3 -Actor 'it' -Action 'request-supplement' -Method 'POST' `
        -Path (Get-ActionPath $ticketMain 'request-supplement') -TicketNo $ticketMain -Result $supplementMain `
        -StatusAfter 'WAITING_FOR_REQUESTER' -Step 'a1.main.requestSupplement'

    # 403 格子：四种错配各一格（权限闸门在状态/身份之前）
    $sigBeforePerm = Get-TicketStateSignature -TicketNo $ticketMain
    $itRequestCancel = Send-CancelAction -Client $itClient -Token $itToken -TicketNo $ticketMain `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version $verMain -Reason 'IT 试图发起撤销请求') `
        -Step 'a1.perm.itRequestCancel' -Actor 'it' -Note 'IT 缺 TICKET_REQUESTER_ACTION'
    Add-Assertion -Name 'a1.perm.itRequestCancel.403' `
        -Condition (($itRequestCancel.Status -eq 403) -and ((Get-Code $itRequestCancel) -eq 'TICKET_ACTION_FORBIDDEN')) `
        -Expected '403 + TICKET_ACTION_FORBIDDEN（IT 没有 TICKET_REQUESTER_ACTION）' `
        -Actual "status=$($itRequestCancel.Status) code=$(Get-Code $itRequestCancel)" `
        -Why '发起与撤回是提交人侧动作；若 IT 也能发起，等于把"谁能终止请求"这条规则交给了负责人自己' `
        -Result $itRequestCancel

    $itWithdrawBefore = Send-CancelAction -Client $itClient -Token $itToken -TicketNo $ticketMain `
        -Action 'withdraw-cancel-request' -Body (New-VersionOnlyBody -Version $verMain) `
        -Step 'a1.perm.itWithdrawCancelRequest' -Actor 'it' -Note 'IT 缺 TICKET_REQUESTER_ACTION，且此刻还没有任何请求'
    Add-Assertion -Name 'a1.perm.itWithdrawCancelRequest.403' `
        -Condition (($itWithdrawBefore.Status -eq 403) -and ((Get-Code $itWithdrawBefore) -eq 'TICKET_ACTION_FORBIDDEN')) `
        -Expected '403 + TICKET_ACTION_FORBIDDEN（权限先于"有没有请求"这一状态判定）' `
        -Actual "status=$($itWithdrawBefore.Status) code=$(Get-Code $itWithdrawBefore)" `
        -Why '负责人撤不掉别人的申请，第一道闸门就是权限；这一格把"权限先于状态"钉死' `
        -Result $itWithdrawBefore

    $employeeApprove = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketMain `
        -Action 'approve-cancel' -Body (New-VersionOnlyBody -Version $verMain) `
        -Step 'a1.perm.employeeApproveCancel' -Actor 'employee' -Note '提交人自己批准自己的请求'
    Add-Assertion -Name 'a1.perm.employeeApproveCancel.403' `
        -Condition (($employeeApprove.Status -eq 403) -and ((Get-Code $employeeApprove) -eq 'TICKET_ACTION_FORBIDDEN')) `
        -Expected '403 + TICKET_ACTION_FORBIDDEN（员工没有 TICKET_PROCESS）' `
        -Actual "status=$($employeeApprove.Status) code=$(Get-Code $employeeApprove)" `
        -Why '两阶段的意义就在这里：提交人不能自己批准自己；这一格失效等于整个规则退回片 D 的单方面撤销' `
        -Result $employeeApprove

    $employeeReject = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketMain `
        -Action 'reject-cancel' -Body (New-RejectCancelBody -Version $verMain -Reason '提交人自己驳回自己的请求') `
        -Step 'a1.perm.employeeRejectCancel' -Actor 'employee' -Note '提交人缺 TICKET_PROCESS'
    Add-Assertion -Name 'a1.perm.employeeRejectCancel.403' `
        -Condition (($employeeReject.Status -eq 403) -and ((Get-Code $employeeReject) -eq 'TICKET_ACTION_FORBIDDEN')) `
        -Expected '403 + TICKET_ACTION_FORBIDDEN' `
        -Actual "status=$($employeeReject.Status) code=$(Get-Code $employeeReject)" `
        -Why '拒绝与批准同权限；否则提交人可以用"拒绝"把自己的请求作废，等于绕开负责人' `
        -Result $employeeReject

    $sigAfterPerm = Get-TicketStateSignature -TicketNo $ticketMain
    Add-Assertion -Name 'a1.perm.rejectedRequestsChangedNothing' -Condition ($sigBeforePerm -eq $sigAfterPerm) `
        -Expected '四次被拒请求前后，工单快照串逐字相同' `
        -Actual "before=[$sigBeforePerm]；after=[$sigAfterPerm]" `
        -Why '被拒的请求绝不允许留下部分写入（版本、记录序号、请求三列都必须原样）；403 尤其不该改任何东西' `
        -Result $null

    # ── 4. 断言组 a1 续：发起撤销请求（状态与期限不变） ──────────────────────
    $mainReason = '问题已经自行解决（主链：发起撤销请求）。'
    $requestMain = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketMain `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version $verMain -Reason $mainReason) `
        -Step 'a1.requestCancel' -Actor 'employee' -Note '提交人在待补充上发起撤销请求'
    $requestMainData = Get-Data $requestMain
    $requestMainOk = (($requestMain.Status -eq 200) -and ($null -ne $requestMainData) -and
        ($requestMainData.status -eq 'WAITING_FOR_REQUESTER'))
    Add-Assertion -Name 'a1.requestCancel.200' -Condition $requestMainOk `
        -Expected '200 且 status 仍是 WAITING_FOR_REQUESTER（请求不迁移状态）' `
        -Actual "status=$($requestMain.Status) code=$(Get-Code $requestMain) businessStatus=$($requestMainData.status) version=$($requestMainData.version)" `
        -Why '两阶段的核心是"请求期间工单停在原状态"；若这里变成某个新状态，说明中间态被建模成了第 8 个状态' `
        -Result $requestMain
    $verMainAfterRequest = -1
    if ($requestMainOk) { $verMainAfterRequest = [long]$requestMainData.version }
    Add-Assertion -Name 'a1.requestCancel.actionDeadlinePreserved' `
        -Condition ($requestMainOk -and ([string]$requestMainData.actionDeadlineAt -eq $mainSupplementDeadline)) `
        -Expected "响应里的 actionDeadlineAt 仍是工单自己的补充期限（$mainSupplementDeadline）" `
        -Actual "actionDeadlineAt=$($requestMainData.actionDeadlineAt)" `
        -Why '请求自带的是"响应期限"，不是工单的 action_deadline_at；两者混写会让待补充的 7d 期限被 3d 覆盖，直接改变超时语义' `
        -Result $requestMain
    Add-KeyAction -Order 4 -Actor 'employee' -Action 'request-cancel' -Method 'POST' `
        -Path (Get-ActionPath $ticketMain 'request-cancel') -TicketNo $ticketMain -Result $requestMain `
        -StatusAfter 'WAITING_FOR_REQUESTER' -Step 'a1.requestCancel'

    $rowMainAfterRequest = Get-TicketDbRow -TicketNo $ticketMain
    $requestColumnsOk = ($null -ne $rowMainAfterRequest) -and
    ($rowMainAfterRequest.cancelRequestState -eq 'HAS_REQUEST') -and
    ($rowMainAfterRequest.cancelRequestReason -eq $mainReason) -and
    ($rowMainAfterRequest.cancelRequestedAt -ne 'NULL') -and
    ($rowMainAfterRequest.cancelRequestDeadlineAt -ne 'NULL')
    Add-Assertion -Name 'a1.db.requestColumnsWritten' -Condition $requestColumnsOk `
        -Expected 'cancel_requested_at / cancel_request_reason / cancel_request_deadline_at 三列都非空，说明逐字一致' `
        -Actual "row=[$($rowMainAfterRequest.raw)]" `
        -Why '三列同生同灭由 ck_ticket_cancel_request_pair 强制；只写一半在库里根本存不下来' `
        -Result $null
    $untouchedOk = ($null -ne $rowMainAfterRequest) -and
    ($rowMainAfterRequest.status -eq 'WAITING_FOR_REQUESTER') -and
    ((ConvertTo-SecondString $rowMainAfterRequest.actionDeadlineAt) -eq (ConvertTo-SecondString $mainSupplementDeadline))
    Add-Assertion -Name 'a1.db.statusAndDeadlineUntouched' -Condition $untouchedOk `
        -Expected 'status 仍是 WAITING_FOR_REQUESTER，action_deadline_at 与请求前逐秒一致' `
        -Actual "status=$($rowMainAfterRequest.status) action_deadline_at=$($rowMainAfterRequest.actionDeadlineAt)（请求前=$mainSupplementDeadline）" `
        -Why '这一条把「请求不是状态迁移、也不动工单期限」钉在库层面；只靠响应断言无法排除"响应没写但库里改了"' `
        -Result $null
    Add-Assertion -Name 'a1.db.versionIncrementedOnce' `
        -Condition (($null -ne $rowMainAfterRequest) -and ($rowMainAfterRequest.version -eq ($verMain + 1))) `
        -Expected "version = $($verMain + 1)（恰好 +1）" `
        -Actual "db.version=$($rowMainAfterRequest.version) 请求前=$verMain" `
        -Why '发起请求也要递增版本，否则并发的批准/拒绝拿旧版本就能命中，唯一胜者判定失效' `
        -Result $null
    # 直查汇总（dbChecks）：把"三列同生同灭"写成一条可独立复算的标志位 SQL。
    Assert-Db -Name 'a1.db.requestTripleFlags' `
        -Sql ("SELECT CONCAT(IF(cancel_requested_at IS NULL,'0','1'), IF(cancel_request_reason IS NULL,'0','1'), " +
        "IF(cancel_request_deadline_at IS NULL,'0','1')) FROM ticket WHERE ticket_no = '$ticketMain';") `
        -Expected '标志位 "111"：三列同时非空（ck_ticket_cancel_request_pair 要求的同生同灭）' `
        -Why '这是"请求是一个整体"的库层证据；任何一列缺失都会让详情与时间线少一块信息' `
        -Check { param($c) $c[0] -eq '111' }
    Assert-Db -Name 'a1.db.statusAndDeadlineUnchanged' `
        -Sql ("SELECT CONCAT(status,'|',IFNULL(DATE_FORMAT(action_deadline_at,'%Y-%m-%d %H:%i:%s'),'NULL')) " +
        "FROM ticket WHERE ticket_no = '$ticketMain';") `
        -Expected ('WAITING_FOR_REQUESTER|' + (ConvertTo-SecondString $mainSupplementDeadline)) `
        -Why '请求既不迁移状态、也不动工单期限；这一条独立于响应断言，直接从库里读' `
        -Check { param($c) $c[0] -eq ('WAITING_FOR_REQUESTER|' + (ConvertTo-SecondString $mainSupplementDeadline)) }
    $requestRecord = Get-LastRecordRow -TicketNo $ticketMain
    $requestRecordOk = ($null -ne $requestRecord) -and ($requestRecord.recordType -eq 'CANCELLATION_REQUEST') -and
    ($requestRecord.fromStatus -eq 'WAITING_FOR_REQUESTER') -and ($requestRecord.toStatus -eq 'WAITING_FOR_REQUESTER') -and
    ($requestRecord.reason -eq $mainReason) -and ($requestRecord.reasonLen -eq $mainReason.Length) -and
    ($requestRecord.deadlineAt -ne 'NULL')
    Add-Assertion -Name 'a1.db.recordIsCancellationRequest' -Condition $requestRecordOk `
        -Expected '时间线最后一条是 CANCELLATION_REQUEST：from=to=WAITING_FOR_REQUESTER、reason 逐字一致、deadline_at 有值' `
        -Actual "row=[$($requestRecord.raw)] 期望 reason=[$mainReason]（长度 $($mainReason.Length)）" `
        -Why '请求说明与响应期限必须留在不可变时间线上；负责人拒绝或提交人撤回之后，这段说明仍然是唯一的历史依据' `
        -Result $null

    # ── 5. 断言组 a2：详情暴露待批请求，且期限来自配置 ──────────────────────
    $detailEmployee = Send-Req -Client $employeeClient -Method Get -Path "/fd/v1/tickets/$ticketMain" `
        -Token $employeeToken -Step 'a2.detail.employee' -Actor 'employee' -Note '提交人视角详情：应看到待批准提示与撤回入口'
    Add-Assertion -Name 'a2.detail.employee.200' -Condition ($detailEmployee.Status -eq 200) -Expected '200' `
        -Actual "status=$($detailEmployee.Status)" `
        -Why '详情是待批请求唯一的展示入口' -Result $detailEmployee
    $null = Assert-CancelRequestPresent -Name 'a2.detail.employee.cancelRequest' -Result $detailEmployee `
        -ExpectedRequestedAt $rowMainAfterRequest.cancelRequestedAt `
        -ExpectedDeadlineAt $rowMainAfterRequest.cancelRequestDeadlineAt `
        -ExpectedReason $mainReason -Detail 'main'

    $detailEmployeeData = Get-Data $detailEmployee
    $employeeActions = @($detailEmployeeData.allowedActions)
    Add-Assertion -Name 'a2.detail.employee.allowedActions' `
        -Condition (($employeeActions -contains 'withdraw-cancel-request') -and
        ($employeeActions -notcontains 'request-cancel') -and
        ($employeeActions -notcontains 'approve-cancel') -and
        ($employeeActions -notcontains 'reject-cancel')) `
        -Expected '提交人视角恰好含 withdraw-cancel-request，且不含 request-cancel / approve-cancel / reject-cancel' `
        -Actual "allowedActions=$($employeeActions -join ',')" `
        -Why '「已有请求」与「没有请求」是两个互斥入口；两格同时出现会让界面同时给出"发起"和"撤回"两个相反动作' `
        -Result $detailEmployee

    $detailIt = Send-Req -Client $itClient -Method Get -Path "/fd/v1/tickets/$ticketMain" `
        -Token $itToken -Step 'a2.detail.it' -Actor 'it' -Note '负责人视角详情：待补充期间不应出现批准/拒绝两格'
    $detailItData = Get-Data $detailIt
    $itActions = @($detailItData.allowedActions)
    Add-Assertion -Name 'a2.detail.it.allowedActionsWhileWaiting' `
        -Condition (($itActions -notcontains 'approve-cancel') -and ($itActions -notcontains 'reject-cancel') -and
        ($itActions -contains 'withdraw-supplement-request') -and ($itActions -contains 'transfer') -and
        ($itActions -notcontains 'request-cancel') -and ($itActions -notcontains 'withdraw-cancel-request')) `
        -Expected '待补充期间不裁决：不含 approve-cancel / reject-cancel，但保留 withdraw-supplement-request 与 transfer' `
        -Actual "allowedActions=$($itActions -join ',')" `
        -Why '2026-10-10 用户裁决：IT 把球交给员工后不允许在信息不全时终止工单；冻结的只是裁决，4.11 已确认的转交与撤回补充请求必须照旧（缺任何一格都说明收窄收过头了）' `
        -Result $detailIt
    $null = Assert-CancelRequestPresent -Name 'a2.detail.it.cancelRequest' -Result $detailIt `
        -ExpectedRequestedAt $rowMainAfterRequest.cancelRequestedAt `
        -ExpectedDeadlineAt $rowMainAfterRequest.cancelRequestDeadlineAt `
        -ExpectedReason $mainReason -Detail 'main.itView'

    $requestedAtRaw = [string]$detailEmployeeData.cancelRequest.requestedAt
    $deadlineAtRaw = [string]$detailEmployeeData.cancelRequest.deadlineAt
    $requestOffsetOk = ($requestedAtRaw.EndsWith('Z') -or $requestedAtRaw.Contains('+00:00'))
    Add-Assertion -Name 'a2.cancelRequest.utcOffset' -Condition $requestOffsetOk `
        -Expected 'requestedAt 以 UTC 表示（Z 或 +00:00 偏移）' `
        -Actual "requestedAt=$requestedAtRaw deadlineAt=$deadlineAtRaw" `
        -Why '数据库存 UTC；响应若不是 UTC，前端会按本机时区显示，3 天的期限会凭空差 8 小时' `
        -Result $detailEmployee

    $windowSeconds = -1
    if (($requestedAtRaw.Length -gt 0) -and ($deadlineAtRaw.Length -gt 0)) {
        $requestedOffset = [datetimeoffset]::Parse($requestedAtRaw)
        $deadlineOffset = [datetimeoffset]::Parse($deadlineAtRaw)
        $windowSeconds = ($deadlineOffset - $requestedOffset).TotalSeconds
    }
    $windowOk = ($windowSeconds -ge ($cancelWindowSeconds - 5)) -and ($windowSeconds -le ($cancelWindowSeconds + 5))
    Add-Assertion -Name 'a2.windowEqualsThreeDays' -Condition $windowOk `
        -Expected "deadlineAt - requestedAt = $cancelWindowSeconds 秒（flowdesk.ticket.cancel-request-window 默认 3d，容差 ±5 秒）" `
        -Actual "实测差=$windowSeconds 秒（requestedAt=$requestedAtRaw deadlineAt=$deadlineAtRaw）" `
        -Why '期限由服务端按配置计算；差得离谱说明配置没被读到（例如 application.yml 写错键名），界面会按错误的期限提醒' `
        -Result $detailEmployee
    $script:cancelRequestInfo['window'] = [ordered]@{
        requestedAt            = $requestedAtRaw
        deadlineAt             = $deadlineAtRaw
        windowSeconds          = $windowSeconds
        expectedWindowSeconds  = $cancelWindowSeconds
        toleranceSeconds       = 5
        configKey              = 'flowdesk.ticket.cancel-request-window'
        configDefault          = '3d'
        autoProcessingOnExpiry = '不做（本版本没有定时任务；期限只用于展示与提醒，docs/kickoff.md 4.7）'
    }
    $recordDeadlineMatches = $false
    if ($null -ne $requestRecord) {
        $recordDeadlineMatches = ((ConvertTo-SecondString $requestRecord.deadlineAt) -eq
        (ConvertTo-SecondString $rowMainAfterRequest.cancelRequestDeadlineAt))
    }
    Add-Assertion -Name 'a2.recordDeadlineMatchesTicketColumn' -Condition $recordDeadlineMatches `
        -Expected 'CANCELLATION_REQUEST 记录的 deadline_at 与 ticket.cancel_request_deadline_at 秒级一致' `
        -Actual "record.deadline=$($requestRecord.deadlineAt) ticket.cancel_request_deadline_at=$($rowMainAfterRequest.cancelRequestDeadlineAt)" `
        -Why '时间线要能独立回答"当时给的是什么时候到期"；两处不一致时历史记录会失去解释力' `
        -Result $null

    # ── 6. 断言组 a3：字段校验与版本/幂等 ───────────────────────────────────
    # 基线必须取在**成功的 request-cancel 之后**：a1 的权限组快照是"请求写入之前"的状态，
    # 拿它来比 a3 会把这中间那次合法写入也算成"被拒请求留下的改动"（首轮实跑就是这样误报的）。
    $sigBeforeA3 = Get-TicketStateSignature -TicketNo $ticketMain
    $blankReason = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketMain `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version $verMainAfterRequest -Reason '   ') `
        -Step 'a3.blankReason' -Actor 'employee' -Note '空白说明：Bean Validation 在进服务层之前拦下'
    Add-Assertion -Name 'a3.blankReason.400' `
        -Condition (($blankReason.Status -eq 400) -and ((Get-Code $blankReason) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED' `
        -Actual "status=$($blankReason.Status) code=$(Get-Code $blankReason)" `
        -Why '撤销说明是负责人判断的唯一依据，不能允许空白；这一格同时证明 @NotBlank 真的在生效' `
        -Result $blankReason
    $longReason = ('长' * 1001)
    $tooLongReason = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketMain `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version $verMainAfterRequest -Reason $longReason) `
        -Step 'a3.tooLongReason' -Actor 'employee' -Note '1001 字符说明：@Size(max=1000) 拦下'
    Add-Assertion -Name 'a3.tooLongReason.400' `
        -Condition (($tooLongReason.Status -eq 400) -and ((Get-Code $tooLongReason) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（1001 > 1000）' `
        -Actual "status=$($tooLongReason.Status) code=$(Get-Code $tooLongReason) reasonLength=$($longReason.Length)" `
        -Why '长度上限是契约的一部分（1000 与其它原因类动作一致）；放宽会让 ticket_record.reason 溢出' `
        -Result $tooLongReason

    $repeatMain = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketMain `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version $verMainAfterRequest -Reason '同版本重复发起') `
        -Step 'a3.repeatSameVersion' -Actor 'employee' -Note '同一张单上重复发起：同时撞版本与"已有待决请求"两个条件'
    Add-Assertion -Name 'a3.repeatSameVersion.409' `
        -Condition (($repeatMain.Status -eq 409) -and ((Get-Code $repeatMain) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT' `
        -Actual "status=$($repeatMain.Status) code=$(Get-Code $repeatMain)" `
        -Why '一张单同时只能有一个待决请求；若这里返回 200，前一次的说明会被静默覆盖，负责人看到的理由就不是最初那条' `
        -Result $repeatMain
    Add-Assertion -Name 'a3.repeatSameVersion.snapshot' `
        -Condition (($null -ne $rowMainAfterRequest) -and
        ([int](Get-ErrData $repeatMain).version -eq $rowMainAfterRequest.version) -and
        ((Get-ErrData $repeatMain).status -eq $rowMainAfterRequest.status)) `
        -Expected '409 的 data.version / data.status 与库中当前快照一致' `
        -Actual "data.version=$((Get-ErrData $repeatMain).version) data.status=$((Get-ErrData $repeatMain).status)；库中 version=$($rowMainAfterRequest.version) status=$($rowMainAfterRequest.status)" `
        -Why '项目统一用 ErrorDetails 的 version/status 携带冲突快照（docs/api-design.md 10.2 称之为"当前 version 与 status 的最小冲突信息"），前端靠它刷新重试' `
        -Result $repeatMain

    $staleMain = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketMain `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version $verMain -Reason '过期版本发起') `
        -Step 'a3.staleVersion' -Actor 'employee' -Note '用请求前的旧版本再发一次'
    Add-Assertion -Name 'a3.staleVersion.409' `
        -Condition (($staleMain.Status -eq 409) -and ((Get-Code $staleMain) -eq 'TICKET_CONFLICT') -and
        ([int](Get-ErrData $staleMain).version -eq $rowMainAfterRequest.version)) `
        -Expected "409 + TICKET_CONFLICT，且 data.version = $($rowMainAfterRequest.version)" `
        -Actual "status=$($staleMain.Status) code=$(Get-Code $staleMain) data.version=$((Get-ErrData $staleMain).version)" `
        -Why '版本是并发唯一胜者判定的载体；过期版本必须被拒并带回新快照，而不是静默按最新状态执行' `
        -Result $staleMain
    $sigAfterA3 = Get-TicketStateSignature -TicketNo $ticketMain
    Add-Assertion -Name 'a3.rejectedRequestsChangedNothing' -Condition ($sigBeforeA3 -eq $sigAfterA3) `
        -Expected 'a3 的四个被拒请求（空白、超长、同版本、过期版本）都没改动工单' `
        -Actual "a3 前（成功发起请求之后）=[$sigBeforeA3]；a3 后=[$sigAfterA3]" `
        -Why '四个请求分别落在 400 与 409 上，两种失败都不允许留下任何写入；基线取在成功请求之后，才能把这次合法写入排除在比较之外' `
        -Result $null

    # ── 6b. 断言组 a3b：待补充期间不裁决 → 员工补充 → 回到处理中（2026-10-10 裁决） ──
    # 这一组是把"到期语义以外"的第二条规则变更钉在 HTTP 与库两层：工单在待补充时，
    # 负责人既不能批准也不能拒绝；请求仍留在工单上；员工补充完、工单回到处理中，两格回来。
    $sigBeforeFrozen = Get-TicketStateSignature -TicketNo $ticketMain
    $approveWhileWaiting = Send-CancelAction -Client $itClient -Token $itToken -TicketNo $ticketMain `
        -Action 'approve-cancel' -Body (New-VersionOnlyBody -Version $verMainAfterRequest) `
        -Step 'a3b.frozen.approveWhileWaiting' -Actor 'it' -Note '待补充期间尝试批准撤销请求'
    Add-Assertion -Name 'a3b.frozen.approveWhileWaiting.409' `
        -Condition (($approveWhileWaiting.Status -eq 409) -and ((Get-Code $approveWhileWaiting) -eq 'TICKET_CONFLICT') -and
        ([int](Get-ErrData $approveWhileWaiting).version -eq $rowMainAfterRequest.version) -and
        ((Get-ErrData $approveWhileWaiting).status -eq 'WAITING_FOR_REQUESTER')) `
        -Expected '409 + TICKET_CONFLICT，且带回当前快照（version 与 status 都是待补充）' `
        -Actual "status=$($approveWhileWaiting.Status) code=$(Get-Code $approveWhileWaiting) data.version=$((Get-ErrData $approveWhileWaiting).version) data.status=$((Get-ErrData $approveWhileWaiting).status)" `
        -Why '2026-10-10 用户裁决：待补充期间不裁决。状态判定必须发生在条件更新之前——若 SQL 先跑，版本就会被推着走，员工补充时反而撞上 409' `
        -Result $approveWhileWaiting
    $rejectWhileWaiting = Send-CancelAction -Client $itClient -Token $itToken -TicketNo $ticketMain `
        -Action 'reject-cancel' -Body (New-RejectCancelBody -Version $verMainAfterRequest -Reason '待补充期间试图驳回') `
        -Step 'a3b.frozen.rejectWhileWaiting' -Actor 'it' -Note '待补充期间尝试驳回撤销请求'
    Add-Assertion -Name 'a3b.frozen.rejectWhileWaiting.409' `
        -Condition (($rejectWhileWaiting.Status -eq 409) -and ((Get-Code $rejectWhileWaiting) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（批准与拒绝共用一条判定，必须一起挡住）' `
        -Actual "status=$($rejectWhileWaiting.Status) code=$(Get-Code $rejectWhileWaiting)" `
        -Why '只挡批准不挡拒绝会留下更难解释的口子：IT 不能在待补充时终止工单，却能在待补充时否掉提交人的请求' `
        -Result $rejectWhileWaiting
    $sigAfterFrozen = Get-TicketStateSignature -TicketNo $ticketMain
    Add-Assertion -Name 'a3b.frozen.rejectedDecisionsChangedNothing' -Condition ($sigBeforeFrozen -eq $sigAfterFrozen) `
        -Expected '两次被挡下的裁决前后，工单快照串逐字相同' `
        -Actual "before=[$sigBeforeFrozen]；after=[$sigAfterFrozen]" `
        -Why '被挡下的裁决不允许改版本、记录序号或请求三列；否则"冻结"就成了"半执行"，员工补充时会拿到一个已经变过的版本' `
        -Result $null

    $supplementBackContent = '型号是 L3153，完整报错见补充说明。'
    $supplementBack = Send-SupplementAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketMain `
        -Version $verMainAfterRequest -Content $supplementBackContent `
        -Step 'a3b.main.supplement' -Actor 'employee' -Note '提交人补充信息：工单回到处理中，撤销请求仍挂在工单上'
    $supplementBackData = Get-Data $supplementBack
    $supplementBackOk = (($supplementBack.Status -eq 200) -and ($null -ne $supplementBackData) -and
        ($supplementBackData.status -eq 'PROCESSING'))
    Add-Assertion -Name 'a3b.main.supplement.200' -Condition $supplementBackOk `
        -Expected '200 且 status=PROCESSING（补充后回到处理中）' `
        -Actual "status=$($supplementBack.Status) code=$(Get-Code $supplementBack) businessStatus=$($supplementBackData.status) version=$($supplementBackData.version)" `
        -Why '待补充期间不裁决，所以主链必须由提交人先把工单推回处理中；这一步同时是"请求不因工单被推进而失效"的接口层证据' `
        -Result $supplementBack
    if (-not $supplementBackOk) { throw "主链工单未能通过补充回到处理中（status=$($supplementBack.Status)）" }
    $verMainAfterSupplement = [long]$supplementBackData.version
    $script:observed.Add("a3b.main.supplement=status=$($supplementBackData.status),version=$verMainAfterSupplement")

    $rowAfterSupplement = Get-TicketDbRow -TicketNo $ticketMain
    $supplementKeepsRequestOk = ($null -ne $rowAfterSupplement) -and
    ($rowAfterSupplement.status -eq 'PROCESSING') -and
    ($rowAfterSupplement.actionDeadlineAt -eq 'NULL') -and
    ($rowAfterSupplement.cancelRequestState -eq 'HAS_REQUEST') -and
    ($rowAfterSupplement.cancelRequestReason -eq $mainReason) -and
    ($rowAfterSupplement.cancelRequestedAt -ne 'NULL')
    Add-Assertion -Name 'a3b.db.requestSurvivesAdvancement' -Condition $supplementKeepsRequestOk `
        -Expected 'status=PROCESSING、action_deadline_at 清空，但请求三列（发起时间 / 说明 / 响应期限）原样保留' `
        -Actual "row=[$($rowAfterSupplement.raw)]" `
        -Why '补充只让工单版本 +1；请求不因工单被推进而失效，这正是"待补充不裁决"之后仍然能裁决的前提' `
        -Result $null

    $detailItAfterSupplement = Send-Req -Client $itClient -Method Get -Path "/fd/v1/tickets/$ticketMain" `
        -Token $itToken -Step 'a3b.detail.it' -Actor 'it' -Note '回到处理中之后：批准与拒绝两格必须回来'
    $itActionsAfterSupplement = @((Get-Data $detailItAfterSupplement).allowedActions)
    Add-Assertion -Name 'a3b.detail.it.allowedActionsAfterSupplement' `
        -Condition (($itActionsAfterSupplement -contains 'approve-cancel') -and
        ($itActionsAfterSupplement -contains 'reject-cancel')) `
        -Expected '含 approve-cancel 与 reject-cancel（回到处理中后裁决权恢复）' `
        -Actual "allowedActions=$($itActionsAfterSupplement -join ',')" `
        -Why '冻结的只是待补充那一段；若补充之后两格不回来，请求就永远无法被裁决，提交人的出口也被堵死了' `
        -Result $detailItAfterSupplement

    # ── 7. 断言组 a4：期限到点不自动处置（唯一一条非接口写库 SQL） ──────────
    $rowBeforeDeadlineProbe = Get-TicketDbRow -TicketNo $ticketMain
    $beforeExpired = $false
    if ($null -ne $rowBeforeDeadlineProbe -and $rowBeforeDeadlineProbe.cancelRequestDeadlineAt -ne 'NULL') {
        $beforeOffset = [datetimeoffset]::Parse(($rowBeforeDeadlineProbe.cancelRequestDeadlineAt.Replace(' ', 'T') + 'Z'))
        $beforeExpired = ($beforeOffset -le [datetimeoffset]::UtcNow)
    }
    Add-Assertion -Name 'a4.beforeProbe.notExpiredYet' -Condition (-not $beforeExpired) `
        -Expected '探针之前，请求期限还在未来（默认 3d）' `
        -Actual "cancel_request_deadline_at=$($rowBeforeDeadlineProbe.cancelRequestDeadlineAt) utcNow=$([datetimeoffset]::UtcNow.ToString('u'))" `
        -Why '先证明它本来没过期，才能让"改成过去时间后依然可以批准"这一格有意义' `
        -Result $null

    $deadlineProbeSql = "UPDATE ticket SET cancel_request_deadline_at = DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 5 MINUTE) " +
    "WHERE ticket_no = '$ticketMain';"
    Add-Assertion -Name 'a4.probe.scopeDeclared' -Condition $true `
        -Expected '这条 SQL 只命中脚本自建的工单，且只改 cancel_request_deadline_at 一列' `
        -Actual "SQL=[$deadlineProbeSql]；目标工单 $ticketMain 由本脚本在本次运行中创建，收尾按 ticket_no 删除" `
        -Why '这是全程唯一一条绕过接口的写库语句，必须能一句话说清它动了什么、没动什么（片 D 的教训是清理也要能自证）' `
        -Result $null
    $deadlineProbeResult = Invoke-MySql -Sql $deadlineProbeSql
    Add-Assertion -Name 'a4.probe.sqlSucceeded' -Condition ($deadlineProbeResult.exitCode -eq 0) `
        -Expected 'UPDATE 退出码 0（三列仍同生同灭，因此约束不会拦它）' `
        -Actual "exitCode=$($deadlineProbeResult.exitCode) stderr=[$(Get-BriefText $deadlineProbeResult.stderrText 120)]" `
        -Why '只改期限一列、说明与发起时间保持不变，所以 ck_ticket_cancel_request_pair 仍然成立' `
        -Result $null
    $rowAfterDeadlineProbe = Get-TicketDbRow -TicketNo $ticketMain
    $probeExpired = $false
    if ($null -ne $rowAfterDeadlineProbe -and $rowAfterDeadlineProbe.cancelRequestDeadlineAt -ne 'NULL') {
        $probeOffset = [datetimeoffset]::Parse(($rowAfterDeadlineProbe.cancelRequestDeadlineAt.Replace(' ', 'T') + 'Z'))
        $probeExpired = ($probeOffset -lt [datetimeoffset]::UtcNow)
    }
    $probeStateOk = $probeExpired -and ($null -ne $rowAfterDeadlineProbe) -and
    ($rowAfterDeadlineProbe.status -eq 'PROCESSING') -and
    ($rowAfterDeadlineProbe.cancelRequestState -eq 'HAS_REQUEST') -and
    ($rowAfterDeadlineProbe.version -eq $rowBeforeDeadlineProbe.version)
    Add-Assertion -Name 'a4.afterProbe.expiredButNothingElseChanged' -Condition $probeStateOk `
        -Expected '期限已过：状态仍是 PROCESSING（主链此刻在处理中）、请求仍挂在这张单上、version 未变' `
        -Actual "expired=$probeExpired status=$($rowAfterDeadlineProbe.status) cancelRequestState=$($rowAfterDeadlineProbe.cancelRequestState) version=$($rowAfterDeadlineProbe.version)" `
        -Why '本版本没有定时任务，"到期"不改变任何库里的事实；如果这里状态或请求被自动改了，说明有人偷偷加了第二套真相' `
        -Result $null
    $script:deadlineProbeInfo = [ordered]@{
        ticketNo                 = $ticketMain
        sql                      = $deadlineProbeSql
        deadlineBefore           = $rowBeforeDeadlineProbe.cancelRequestDeadlineAt
        deadlineAfter            = $rowAfterDeadlineProbe.cancelRequestDeadlineAt
        expectedBehaviourOnExpiry = '到期不自动处置：不自动取消、请求也不自动失效，回到处理中之后负责人仍可批准（docs/kickoff.md 4.7；待补充期间不裁决是 2026-10-10 的另一条裁决，主链因此先由员工补充把工单推回处理中）'
    }

    # ── 8. 断言组 a5：批准（在"期限已过"的请求上批准，同时证明不自动处置） ──
    $approveMain = Send-CancelAction -Client $itClient -Token $itToken -TicketNo $ticketMain `
        -Action 'approve-cancel' -Body (New-VersionOnlyBody -Version $verMainAfterSupplement) `
        -Step 'a5.approve' -Actor 'it' -Note '当前负责人批准撤销请求（工单已被员工补充推回处理中；请求期限已被探针改成过去时间）'
    $approveMainData = Get-Data $approveMain
    $approveOk = (($approveMain.Status -eq 200) -and ($null -ne $approveMainData) -and
        ($approveMainData.status -eq 'CANCELED'))
    Add-Assertion -Name 'a5.approve.200' -Condition $approveOk `
        -Expected '200 且 status=CANCELED' `
        -Actual "status=$($approveMain.Status) code=$(Get-Code $approveMain) businessStatus=$($approveMainData.status) version=$($approveMainData.version)" `
        -Why '这是两阶段撤销的终点：批准必须真的把工单推进终态，而不是只把请求标记为已决' `
        -Result $approveMain
    Add-Assertion -Name 'a5.approve.expiredRequestStillApprovable' -Condition $approveOk `
        -Expected '请求期限已过（探针改过去时间）仍然可以批准 —— 到期不自动处置的行为证据' `
        -Actual "expired=$probeExpired approveStatus=$($approveMain.Status)" `
        -Why '这是"期限只用于展示"唯一的正向证据；若实现成"过期即失效"，这一格会变成 409，界面上的文案就是错的' `
        -Result $approveMain
    Add-Assertion -Name 'a5.approve.deadlineFieldAbsent' -Condition (Test-DeadlineAbsent $approveMain) `
        -Expected '成功响应里没有 actionDeadlineAt 字段（终态没有有效期限）' `
        -Actual "rawBodyHasDeadlineKey=$(([string]$approveMain.Body).Contains('"actionDeadlineAt"'))" `
        -Why '终态不允许有期限。注意主链此刻的来源状态是"处理中"（待补充期间不裁决，员工以补充把工单推回处理中），本来就没有期限可清；"批准必须在同一条 UPDATE 里清掉待补充/待确认的期限"由 TicketServiceIT.approveCancelClearsDeadlineAndKeepsTerminalStatesDistinguishable 在真实 MySQL 上覆盖' `
        -Result $approveMain
    $verMainApproved = -1
    if ($approveOk) { $verMainApproved = [long]$approveMainData.version }
    Add-KeyAction -Order 5 -Actor 'it' -Action 'approve-cancel' -Method 'POST' `
        -Path (Get-ActionPath $ticketMain 'approve-cancel') -TicketNo $ticketMain -Result $approveMain `
        -StatusAfter 'CANCELED' -Step 'a5.approve'

    $rowMainFinal = Get-TicketDbRow -TicketNo $ticketMain
    $mainTerminalOk = ($null -ne $rowMainFinal) -and ($rowMainFinal.status -eq 'CANCELED') -and
    ($rowMainFinal.actionDeadlineAt -eq 'NULL') -and ($rowMainFinal.cancelRequestState -eq 'NO_REQUEST') -and
    ($rowMainFinal.cancelRequestReason -eq 'NULL') -and ($rowMainFinal.cancelRequestDeadlineAt -eq 'NULL') -and
    ($rowMainFinal.endedAt -eq 'SET') -and ($rowMainFinal.completionMethod -eq 'NULL') -and
    ($rowMainFinal.closeMethod -eq 'NULL') -and ($rowMainFinal.closeReason -eq 'NULL') -and
    ($rowMainFinal.assigneeId -eq $itUserId) -and ($rowMainFinal.version -eq $verMainApproved)
    Add-Assertion -Name 'a5.db.terminalFacts' -Condition $mainTerminalOk `
        -Expected ('status=CANCELED、action_deadline_at 清空、请求三列全清空、ended_at 有值、' +
        'completion_method / close_method / close_reason 全为空、负责人保留 it、version 递增') `
        -Actual "row=[$($rowMainFinal.raw)]" `
        -Why '四条约束同时生效：终态不许挂待决请求（ck_ticket_cancel_request_status）、终态期限必须为空（ck_ticket_status_deadline）、终态必须有结束时间（ck_ticket_status_ended）、三条终态必须可区分' `
        -Result $null
    Assert-Db -Name 'a5.db.terminalRequestColumnsCleared' `
        -Sql ("SELECT CONCAT(IF(cancel_requested_at IS NULL,'NULL','SET'),'|'," +
        "IF(cancel_request_reason IS NULL,'NULL','SET'),'|',IF(cancel_request_deadline_at IS NULL,'NULL','SET'),'|'," +
        "IF(action_deadline_at IS NULL,'NULL','SET'),'|',IF(ended_at IS NULL,'NULL','SET')) " +
        "FROM ticket WHERE ticket_no = '$ticketMain';") `
        -Expected 'NULL|NULL|NULL|NULL|SET（请求三列与工单期限都清空，同时终态必须有结束时间）' `
        -Why '批准必须一条 UPDATE 里同时清请求三列、清 action_deadline_at、写 ended_at；漏掉任何一项都会撞 CHECK 约束或留下脏状态' `
        -Check { param($c) $c[0] -eq 'NULL|NULL|NULL|NULL|SET' }
    Assert-Db -Name 'a5.db.threeTerminalStatesDistinguishable' `
        -Sql ("SELECT CONCAT(IFNULL(completion_method,'-'),'|',IFNULL(close_method,'-'),'|',IFNULL(close_reason,'-')) " +
        "FROM ticket WHERE ticket_no = '$ticketMain';") `
        -Expected '-|-|-（已取消不带完成方式与关闭字段，因此与「已完成」「已关闭」在库层面可区分）' `
        -Why 'docs/kickoff.md 4.7：已取消不等于 IT 解决了问题；三条终态若在字段上无法区分，统计与展示都会把撤销算成解决' `
        -Check { param($c) $c[0] -eq '-|-|-' }
    $script:terminalInfo['main'] = [ordered]@{
        ticketNo         = $ticketMain
        fromStatus       = 'PROCESSING'
        toStatus         = 'CANCELED'
        version          = $rowMainFinal.version
        assigneeKept     = $rowMainFinal.assigneeId
        completionMethod = $rowMainFinal.completionMethod
        closeMethod      = $rowMainFinal.closeMethod
        closeReason      = $rowMainFinal.closeReason
    }
    $mainApprovedRecord = Get-LastRecordRow -TicketNo $ticketMain
    $mainApprovedRecordOk = ($null -ne $mainApprovedRecord) -and
    ($mainApprovedRecord.recordType -eq 'CANCELLATION_APPROVED') -and
    ($mainApprovedRecord.fromStatus -eq 'PROCESSING') -and
    ($mainApprovedRecord.toStatus -eq 'CANCELED') -and ($mainApprovedRecord.reason -eq '-')
    Add-Assertion -Name 'a5.db.recordIsCancellationApproved' -Condition $mainApprovedRecordOk `
        -Expected '时间线最后一条是 CANCELLATION_APPROVED：from=PROCESSING → to=CANCELED、无原因（批准发生在员工补充之后）' `
        -Actual "row=[$($mainApprovedRecord.raw)]" `
        -Why '批准不写原因（批准本身就是决定）；与 CANCELLATION_REJECTED 必须可区分，否则时间线读不出"谁不同意、为什么"' `
        -Result $null
    $cancelRecordsMain = Get-CancelRecordRows -TicketNo $ticketMain
    Add-Assertion -Name 'a5.db.cancelRecordSequence' `
        -Condition ($cancelRecordsMain -match 'CANCELLATION_REQUEST' -and $cancelRecordsMain -match 'CANCELLATION_APPROVED') `
        -Expected '时间线上按顺序留下 CANCELLATION_REQUEST 与 CANCELLATION_APPROVED 两条' `
        -Actual "撤销相关记录=[$cancelRecordsMain]" `
        -Why '两阶段各留一条记录，才能回答"谁在什么时候要求撤销、谁批准的"' `
        -Result $null

    $detailAfterApprove = Send-Req -Client $employeeClient -Method Get -Path "/fd/v1/tickets/$ticketMain" `
        -Token $employeeToken -Step 'a5.detail.afterApprove' -Actor 'employee' -Note '批准后详情：不应再有待批请求'
    Add-Assertion -Name 'a5.detail.cancelRequestAbsentAfterApprove' `
        -Condition ((Test-CancelRequestAbsent $detailAfterApprove) -and ((Get-Data $detailAfterApprove).status -eq 'CANCELED')) `
        -Expected 'cancelRequest 键不出现，且 status=CANCELED' `
        -Actual "bodyHasCancelRequestKey=$(([string]$detailAfterApprove.Body).Contains('"cancelRequest"')) status=$((Get-Data $detailAfterApprove).status)" `
        -Why '批准后请求已经结束；字段若还在，界面会继续显示"等待批准"，让人以为还能再点一次' `
        -Result $detailAfterApprove

    $timelineMain = Send-Req -Client $employeeClient -Method Get `
        -Path "/fd/v1/tickets/$ticketMain/records?page=1&size=50" -Token $employeeToken `
        -Step 'a5.timeline' -Actor 'employee' -Note '读取时间线：四个新记录类型必须有 context 分支，否则整条时间线 500'
    $timelineItems = @((Get-Data $timelineMain).items)
    Add-Assertion -Name 'a5.timeline.200' -Condition ($timelineMain.Status -eq 200) `
        -Expected '200（新记录类型在 toRecordContext 里都有 case）' `
        -Actual "status=$($timelineMain.Status) count=$($timelineItems.Count)" `
        -Why 'toRecordContext 的 default 分支是刻意抛异常的守卫；漏一个 case 不是少一个字段，而是整条时间线 500' `
        -Result $timelineMain
    $timelineTypes = @($timelineItems | ForEach-Object { $_.recordType })
    $timelineLastTwo = @($timelineTypes | Select-Object -Last 2)
    Add-Assertion -Name 'a5.timeline.lastTwoTypes' `
        -Condition (($timelineLastTwo.Count -eq 2) -and ($timelineLastTwo[0] -eq 'CANCELLATION_REQUEST') -and ($timelineLastTwo[1] -eq 'CANCELLATION_APPROVED')) `
        -Expected '最后两条依次是 CANCELLATION_REQUEST、CANCELLATION_APPROVED' `
        -Actual "types=$($timelineTypes -join ',')" `
        -Why '顺序就是业务事实（先请求后批准）；倒置或缺失说明记录序号或插入点错了' `
        -Result $timelineMain
    $timelineRequestItem = $timelineItems | Where-Object { $_.recordType -eq 'CANCELLATION_REQUEST' } | Select-Object -First 1
    $timelineRequestContextOk = ($null -ne $timelineRequestItem) -and
    ($timelineRequestItem.context.reason -eq $mainReason) -and
    ($timelineRequestItem.context.fromStatus -eq 'WAITING_FOR_REQUESTER') -and
    ($timelineRequestItem.context.toStatus -eq 'WAITING_FOR_REQUESTER') -and
    (-not [string]::IsNullOrEmpty([string]$timelineRequestItem.context.deadlineAt))
    Add-Assertion -Name 'a5.timeline.requestContextShape' -Condition $timelineRequestContextOk `
        -Expected 'CANCELLATION_REQUEST 的 context 带 reason、deadlineAt、fromStatus、toStatus' `
        -Actual ("context.reason=[$(Get-BriefText ([string]$timelineRequestItem.context.reason))] " +
        "deadlineAt=$($timelineRequestItem.context.deadlineAt) from=$($timelineRequestItem.context.fromStatus) to=$($timelineRequestItem.context.toStatus)") `
        -Why '时间线要能独立解释这条记录：没有 deadlineAt 就看不出当时的响应期限，没有 from/to 就看不出状态其实没变' `
        -Result $timelineMain
    $script:observed.Add("a5.timeline.main=$($timelineTypes -join ',')")

    # ── 9. 断言组 a6：驳回 → 再次发起 → 撤回（处理中） ──────────────────────
    $carrierReject = New-CarrierTicket -Key 'reject' -Step 'a6.reject' `
        -Title "E2E 片E驳回 $stamp" -Purpose '片 E：处理中拒绝 → 再次发起 → 提交人撤回' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketReject = $carrierReject.ticketNo
    $verReject = $carrierReject.version
    $claimReject = Invoke-ClaimCarrier -Step 'a6.reject' -TicketNo $ticketReject -Version $verReject `
        -Client $itClient -Token $itToken -Actor 'it'
    $verReject = $claimReject.version

    $cancelOnProcessing = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketReject `
        -Action 'cancel' -Body (New-CancelBody -Version $verReject -Reason '处理中试图直接撤销') `
        -Step 'a6.narrowed.cancelOnProcessing' -Actor 'employee' -Note 'cancel 已收窄：处理中不再允许直接撤销'
    Add-Assertion -Name 'a6.narrowed.cancelOnProcessing.409' `
        -Condition (($cancelOnProcessing.Status -eq 409) -and ((Get-Code $cancelOnProcessing) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（处理中只能发起撤销请求，不能直接撤销）' `
        -Actual "status=$($cancelOnProcessing.Status) code=$(Get-Code $cancelOnProcessing)" `
        -Why 'cancel 收窄是本片最容易漏掉的一处：若这里仍返回 200，两阶段就形同虚设（提交人绕开负责人）' `
        -Result $cancelOnProcessing

    $requestReject1 = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketReject `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version $verReject -Reason '第一轮：问题貌似解决了') `
        -Step 'a6.request1' -Actor 'employee' -Note '处理中发起第一轮撤销请求'
    Add-Assertion -Name 'a6.request1.200' -Condition ($requestReject1.Status -eq 200) `
        -Expected '200（处理中也可以发起请求）' `
        -Actual "status=$($requestReject1.Status) code=$(Get-Code $requestReject1) businessStatus=$((Get-Data $requestReject1).status)" `
        -Why '三种"有人负责"的状态都必须支持发起；少了处理中，提交人在 IT 正在处理时就没有任何出口' `
        -Result $requestReject1
    $verRejectAfterRequest1 = [long](Get-Data $requestReject1).version
    $rowRejectBeforeReject = Get-TicketDbRow -TicketNo $ticketReject

    $rejectReason = 'IT 认为还需要继续排查，问题没有消失。'
    $rejectResult = Send-CancelAction -Client $itClient -Token $itToken -TicketNo $ticketReject `
        -Action 'reject-cancel' -Body (New-RejectCancelBody -Version $verRejectAfterRequest1 -Reason $rejectReason) `
        -Step 'a6.reject' -Actor 'it' -Note '当前负责人拒绝撤销请求并写明理由'
    $rejectData = Get-Data $rejectResult
    $rejectOk = (($rejectResult.Status -eq 200) -and ($null -ne $rejectData) -and ($rejectData.status -eq 'PROCESSING'))
    Add-Assertion -Name 'a6.reject.200' -Condition $rejectOk `
        -Expected '200 且 status 仍是 PROCESSING' `
        -Actual "status=$($rejectResult.Status) code=$(Get-Code $rejectResult) businessStatus=$($rejectData.status) version=$($rejectData.version)" `
        -Why '拒绝不改状态：工单继续由这名负责人处理，处理责任不能被一次拒绝改变' `
        -Result $rejectResult
    $verRejectAfterReject = -1
    if ($rejectOk) { $verRejectAfterReject = [long]$rejectData.version }
    Add-KeyAction -Order 6 -Actor 'it' -Action 'reject-cancel' -Method 'POST' `
        -Path (Get-ActionPath $ticketReject 'reject-cancel') -TicketNo $ticketReject -Result $rejectResult `
        -StatusAfter 'PROCESSING' -Step 'a6.reject'

    $rowRejectAfterReject = Get-TicketDbRow -TicketNo $ticketReject
    $rejectDbOk = ($null -ne $rowRejectAfterReject) -and ($rowRejectAfterReject.status -eq 'PROCESSING') -and
    ($rowRejectAfterReject.cancelRequestState -eq 'NO_REQUEST') -and
    ($rowRejectAfterReject.actionDeadlineAt -eq $rowRejectBeforeReject.actionDeadlineAt) -and
    ($rowRejectAfterReject.assigneeId -eq $itUserId) -and
    ($rowRejectAfterReject.version -eq $verRejectAfterReject)
    Add-Assertion -Name 'a6.reject.dbStateUnchangedExceptVersion' -Condition $rejectDbOk `
        -Expected '请求三列清空、状态与期限不变、负责人不变、version +1' `
        -Actual "before=[$($rowRejectBeforeReject.raw)]；after=[$($rowRejectAfterReject.raw)]" `
        -Why '拒绝只是让请求失效；状态、期限、负责人都必须原样，否则 IT 的一次拒绝会顺带改变工单处境' `
        -Result $null
    Assert-Db -Name 'a6.db.rejectClearedRequestKeptState' `
        -Sql ("SELECT CONCAT(status,'|',IF(action_deadline_at IS NULL,'NULL','SET'),'|'," +
        "IF(cancel_requested_at IS NULL,'NULL','SET'),'|',IFNULL(assignee_id,-1)) " +
        "FROM ticket WHERE ticket_no = '$ticketReject';") `
        -Expected "PROCESSING|NULL|NULL|$itUserId（状态与期限不变、请求清空、负责人不变）" `
        -Why '拒绝是"不同意撤销"，不是"改变工单"；库里必须只剩版本变化' `
        -Check { param($c) $c[0] -eq ("PROCESSING|NULL|NULL|" + $itUserId) }
    $rejectRecord = Get-LastRecordRow -TicketNo $ticketReject
    $rejectRecordOk = ($null -ne $rejectRecord) -and ($rejectRecord.recordType -eq 'CANCELLATION_REJECTED') -and
    ($rejectRecord.fromStatus -eq 'PROCESSING') -and ($rejectRecord.toStatus -eq 'PROCESSING') -and
    ($rejectRecord.reason -eq $rejectReason) -and ($rejectRecord.reasonLen -eq $rejectReason.Length)
    Add-Assertion -Name 'a6.reject.recordHasReason' -Condition $rejectRecordOk `
        -Expected 'CANCELLATION_REJECTED 记录：from=to=PROCESSING、reason 与拒绝原因逐字一致' `
        -Actual "row=[$($rejectRecord.raw)] 期望 reason=[$rejectReason]" `
        -Why '没有拒绝原因，提交人只看到"被拒绝"而无从调整；这是把拒绝做成可用动作的最低要求' `
        -Result $null

    $emptyRejectReason = Send-CancelAction -Client $itClient -Token $itToken -TicketNo $ticketReject `
        -Action 'reject-cancel' -Body (New-RejectCancelBody -Version $verRejectAfterReject -Reason '  ') `
        -Step 'a6.reject.blankReason' -Actor 'it' -Note '拒绝原因空白（此时已无待决请求）'
    Add-Assertion -Name 'a6.reject.blankReason.400' `
        -Condition (($emptyRejectReason.Status -eq 400) -and ((Get-Code $emptyRejectReason) -eq 'VALIDATION_FAILED')) `
        -Expected '400 + VALIDATION_FAILED（Bean Validation 先于服务层）' `
        -Actual "status=$($emptyRejectReason.Status) code=$(Get-Code $emptyRejectReason)" `
        -Why '与片 D 同一口径：HTTP 层不存在可观察的「409 先于 400」，注解先把同一输入拦下了；服务层顺序由 TicketServiceImplTest 覆盖' `
        -Result $emptyRejectReason

    $requestReject2 = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketReject `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version $verRejectAfterReject -Reason '第二轮：又觉得不用处理了') `
        -Step 'a6.request2' -Actor 'employee' -Note '驳回之后再次发起撤销请求'
    Add-Assertion -Name 'a6.request2.200' -Condition ($requestReject2.Status -eq 200) `
        -Expected '200（驳回不是终点，提交人可以再次发起）' `
        -Actual "status=$($requestReject2.Status) code=$(Get-Code $requestReject2) businessStatus=$((Get-Data $requestReject2).status)" `
        -Why '若驳回之后不能再发起，IT 的一次拒绝就永久堵死了提交人的出口——这正是两阶段被设计成"请求"而不是"申请一次"的原因' `
        -Result $requestReject2
    $verRejectAfterRequest2 = [long](Get-Data $requestReject2).version
    $requestCountReject = Get-RecordCountByType -TicketNo $ticketReject -RecordType 'CANCELLATION_REQUEST'
    Add-Assertion -Name 'a6.db.requestRecordCountIsTwo' -Condition ($requestCountReject -eq 2) `
        -Expected 'CANCELLATION_REQUEST 记录累计 2 条' `
        -Actual "count=$requestCountReject rows=[$(Get-CancelRecordRows -TicketNo $ticketReject)]" `
        -Why '每一轮请求都必须独立留痕；只有一条说明第二轮覆盖或复用了第一条记录，历史就不完整了' `
        -Result $null

    $withdrawResult = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketReject `
        -Action 'withdraw-cancel-request' -Body (New-VersionOnlyBody -Version $verRejectAfterRequest2) `
        -Step 'a6.withdraw' -Actor 'employee' -Note '提交人撤回自己的撤销请求（不需填写理由）'
    $withdrawData = Get-Data $withdrawResult
    $withdrawOk = (($withdrawResult.Status -eq 200) -and ($null -ne $withdrawData) -and
        ($withdrawData.status -eq 'PROCESSING'))
    Add-Assertion -Name 'a6.withdraw.200' -Condition $withdrawOk `
        -Expected '200 且 status 仍是 PROCESSING' `
        -Actual "status=$($withdrawResult.Status) code=$(Get-Code $withdrawResult) businessStatus=$($withdrawData.status) version=$($withdrawData.version)" `
        -Why '撤回是"我改主意了"，不改工单处境；这也是提交人在请求期间唯一的自服务出口' `
        -Result $withdrawResult
    $verRejectAfterWithdraw = -1
    if ($withdrawOk) { $verRejectAfterWithdraw = [long]$withdrawData.version }
    Add-KeyAction -Order 7 -Actor 'employee' -Action 'withdraw-cancel-request' -Method 'POST' `
        -Path (Get-ActionPath $ticketReject 'withdraw-cancel-request') -TicketNo $ticketReject -Result $withdrawResult `
        -StatusAfter 'PROCESSING' -Step 'a6.withdraw'
    $rowRejectAfterWithdraw = Get-TicketDbRow -TicketNo $ticketReject
    $withdrawDbOk = ($null -ne $rowRejectAfterWithdraw) -and ($rowRejectAfterWithdraw.status -eq 'PROCESSING') -and
    ($rowRejectAfterWithdraw.cancelRequestState -eq 'NO_REQUEST') -and
    ($rowRejectAfterWithdraw.cancelRequestReason -eq 'NULL') -and
    ($rowRejectAfterWithdraw.actionDeadlineAt -eq $rowRejectAfterReject.actionDeadlineAt) -and
    ($rowRejectAfterWithdraw.assigneeId -eq $itUserId) -and
    ($rowRejectAfterWithdraw.version -eq $verRejectAfterWithdraw)
    Add-Assertion -Name 'a6.withdraw.dbStateUnchangedExceptVersion' -Condition $withdrawDbOk `
        -Expected '请求三列清空、状态与期限不变、负责人不变、version +1' `
        -Actual "row=[$($rowRejectAfterWithdraw.raw)]" `
        -Why '撤回与拒绝在库层面的效果必须一致（只让请求失效）；唯一的区别留在时间线记录类型与原因上' `
        -Result $null
    Assert-Db -Name 'a6.db.withdrawClearedRequestKeptState' `
        -Sql ("SELECT CONCAT(status,'|',IF(cancel_requested_at IS NULL,'NULL','SET'),'|'," +
        "IF(cancel_request_deadline_at IS NULL,'NULL','SET'),'|'," +
        "(SELECT COUNT(*) FROM ticket_record r WHERE r.ticket_id = t.id AND r.record_type = 'CANCELLATION_REQUEST')) " +
        "FROM ticket t WHERE ticket_no = '$ticketReject';") `
        -Expected 'PROCESSING|NULL|NULL|2（请求清空，且两轮 CANCELLATION_REQUEST 记录仍在）' `
        -Why '撤回只让当前请求失效，历史记录不能被删；这一条同时证明"驳回后可以再次发起"留下了两条独立痕迹' `
        -Check { param($c) $c[0] -eq 'PROCESSING|NULL|NULL|2' }
    $withdrawRecord = Get-LastRecordRow -TicketNo $ticketReject
    $withdrawRecordOk = ($null -ne $withdrawRecord) -and
    ($withdrawRecord.recordType -eq 'CANCELLATION_REQUEST_WITHDRAWN') -and
    ($withdrawRecord.fromStatus -eq 'PROCESSING') -and ($withdrawRecord.toStatus -eq 'PROCESSING') -and
    ($withdrawRecord.reason -eq '-')
    Add-Assertion -Name 'a6.withdraw.recordReasonEmpty' -Condition $withdrawRecordOk `
        -Expected 'CANCELLATION_REQUEST_WITHDRAWN 记录：from=to=PROCESSING、reason 为空' `
        -Actual "row=[$($withdrawRecord.raw)]" `
        -Why '撤回不写原因（请求里原本的说明仍留在 CANCELLATION_REQUEST 上）；若这里冒出原因，说明实现把两个动作的参数混用了' `
        -Result $null
    $detailAfterWithdraw = Send-Req -Client $employeeClient -Method Get -Path "/fd/v1/tickets/$ticketReject" `
        -Token $employeeToken -Step 'a6.detail.afterWithdraw' -Actor 'employee' -Note '撤回后详情：待批请求应消失'
    Add-Assertion -Name 'a6.detail.cancelRequestAbsentAfterWithdraw' -Condition (Test-CancelRequestAbsent $detailAfterWithdraw) `
        -Expected 'cancelRequest 键不出现（撤回后不再有待批准提示）' `
        -Actual "bodyHasCancelRequestKey=$(([string]$detailAfterWithdraw.Body).Contains('"cancelRequest"')) status=$((Get-Data $detailAfterWithdraw).status)" `
        -Why '撤回后界面必须回到"可以再次发起"的状态；字段仍在会让提交人以为自己还没撤回' `
        -Result $detailAfterWithdraw
    $withdrawRepeat = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketReject `
        -Action 'withdraw-cancel-request' -Body (New-VersionOnlyBody -Version $verRejectAfterWithdraw) `
        -Step 'a6.withdraw.repeat' -Actor 'employee' -Note '同一版本重复撤回'
    Add-Assertion -Name 'a6.withdraw.repeatSameVersion.409' `
        -Condition (($withdrawRepeat.Status -eq 409) -and ((Get-Code $withdrawRepeat) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（已经没有待决请求可撤）' `
        -Actual "status=$($withdrawRepeat.Status) code=$(Get-Code $withdrawRepeat)" `
        -Why '撤回必须幂等地失败而不是幂等地成功：若第二次仍 200，版本会白涨一次，并可能把别人刚发起的请求撤掉' `
        -Result $withdrawRepeat
    $detailAfterWithdrawData = Get-Data $detailAfterWithdraw
    $actionsAfterWithdraw = @($detailAfterWithdrawData.allowedActions)
    Add-Assertion -Name 'a6.detail.employee.canRequestAgain' -Condition ($actionsAfterWithdraw -contains 'request-cancel') `
        -Expected '撤回后提交人重新看到 request-cancel' `
        -Actual "allowedActions=$($actionsAfterWithdraw -join ',')" `
        -Why '两格互斥判定的另一半：撤回后必须换回"发起"，否则提交人再也发不出请求' `
        -Result $detailAfterWithdraw

    # ── 10. 断言组 a7：cancel 收窄的另一面（待受理仍可直接撤销） ────────────
    $carrierPending = New-CarrierTicket -Key 'pending' -Step 'a7.pending' `
        -Title "E2E 片E待受理 $stamp" -Purpose '片 E：待受理没有负责人，request-cancel 被拒、cancel 直接成功' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketPending = $carrierPending.ticketNo
    $verPending = $carrierPending.version
    $sigPendingBefore = Get-TicketStateSignature -TicketNo $ticketPending
    $pendingRequestCancel = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketPending `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version $verPending -Reason '待受理上试图发起请求') `
        -Step 'a7.pending.requestCancel' -Actor 'employee' -Note '待受理没有负责人，不需要谁批准'
    Add-Assertion -Name 'a7.pending.requestCancel.409' `
        -Condition (($pendingRequestCancel.Status -eq 409) -and ((Get-Code $pendingRequestCancel) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（待受理不走两阶段）' `
        -Actual "status=$($pendingRequestCancel.Status) code=$(Get-Code $pendingRequestCancel)" `
        -Why '这一格是"待受理直接撤销"的规则反面：没有负责人可批准，所以不给请求这条路径' `
        -Result $pendingRequestCancel
    $pendingApprove = Send-CancelAction -Client $itClient -Token $itToken -TicketNo $ticketPending `
        -Action 'approve-cancel' -Body (New-VersionOnlyBody -Version $verPending) `
        -Step 'a7.pending.approveCancel' -Actor 'it' -Note '待受理上没有任何请求可批准'
    Add-Assertion -Name 'a7.pending.approveCancel.409' `
        -Condition (($pendingApprove.Status -eq 409) -and ((Get-Code $pendingApprove) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（没有待决请求，且待受理不在允许状态里）' `
        -Actual "status=$($pendingApprove.Status) code=$(Get-Code $pendingApprove)" `
        -Why '批准必须要求"确有待决请求"（cancel_requested_at IS NOT NULL 进 WHERE），否则任何人都能批准一个不存在的请求' `
        -Result $pendingApprove
    $pendingReject = Send-CancelAction -Client $itClient -Token $itToken -TicketNo $ticketPending `
        -Action 'reject-cancel' -Body (New-RejectCancelBody -Version $verPending -Reason '待受理上没有请求可拒绝') `
        -Step 'a7.pending.rejectCancel' -Actor 'it' -Note '同上，改用拒绝入口'
    Add-Assertion -Name 'a7.pending.rejectCancel.409' `
        -Condition (($pendingReject.Status -eq 409) -and ((Get-Code $pendingReject) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT' `
        -Actual "status=$($pendingReject.Status) code=$(Get-Code $pendingReject)" `
        -Why '批准与拒绝共用同一判定；只测一个入口会漏掉"另一条 SQL 忘了加 cancel_requested_at 条件"的情况' `
        -Result $pendingReject
    $sigPendingAfterRejects = Get-TicketStateSignature -TicketNo $ticketPending
    Add-Assertion -Name 'a7.pending.rejectedRequestsChangedNothing' -Condition ($sigPendingBefore -eq $sigPendingAfterRejects) `
        -Expected '三次被拒（request-cancel / approve-cancel / reject-cancel）前后工单快照串逐字相同' `
        -Actual "before=[$sigPendingBefore]；after=[$sigPendingAfterRejects]" `
        -Why '待受理上被拒的三个请求都不许留下痕迹（尤其不许把请求三列写成半截状态）' `
        -Result $null
    $pendingCancel = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketPending `
        -Action 'cancel' -Body (New-CancelBody -Version $verPending -Reason '问题已经不需要处理了（待受理直接撤销）。') `
        -Step 'a7.pending.cancel' -Actor 'employee' -Note '待受理直接撤销：cancel 收窄之后仍保留的唯一一条直接路径'
    $pendingCancelData = Get-Data $pendingCancel
    $pendingCancelOk = (($pendingCancel.Status -eq 200) -and ($null -ne $pendingCancelData) -and
        ($pendingCancelData.status -eq 'CANCELED'))
    Add-Assertion -Name 'a7.pending.cancel.200' -Condition $pendingCancelOk `
        -Expected '200 且 status=CANCELED' `
        -Actual "status=$($pendingCancel.Status) code=$(Get-Code $pendingCancel) businessStatus=$($pendingCancelData.status)" `
        -Why '收窄不等于取消：待受理没有负责人，若连这里也要求批准，提交人对误报的工单就彻底没有出口' `
        -Result $pendingCancel
    Add-KeyAction -Order 8 -Actor 'employee' -Action 'cancel(待受理)' -Method 'POST' `
        -Path (Get-ActionPath $ticketPending 'cancel') -TicketNo $ticketPending -Result $pendingCancel `
        -StatusAfter 'CANCELED' -Step 'a7.pending.cancel'
    $rowPendingFinal = Get-TicketDbRow -TicketNo $ticketPending
    $pendingTerminalOk = ($null -ne $rowPendingFinal) -and ($rowPendingFinal.status -eq 'CANCELED') -and
    ($rowPendingFinal.endedAt -eq 'SET') -and ($rowPendingFinal.actionDeadlineAt -eq 'NULL') -and
    ($rowPendingFinal.assigneeId -eq -1) -and ($rowPendingFinal.completionMethod -eq 'NULL') -and
    ($rowPendingFinal.closeMethod -eq 'NULL') -and ($rowPendingFinal.cancelRequestState -eq 'NO_REQUEST')
    Add-Assertion -Name 'a7.pending.dbTerminalFacts' -Condition $pendingTerminalOk `
        -Expected 'status=CANCELED、ended_at 有值、无负责人、完成/关闭字段与请求三列全空' `
        -Actual "row=[$($rowPendingFinal.raw)]" `
        -Why '三条终态必须可区分：直接撤销不写 completion_method / close_method / close_reason；待受理本来就没有负责人' `
        -Result $null
    Assert-Db -Name 'a7.pending.terminalDistinguishableFromOthers' `
        -Sql ("SELECT CONCAT(status,'|',IF(assignee_id IS NULL,'NULL','SET'),'|',IF(ended_at IS NULL,'NULL','SET'),'|'," +
        "IFNULL(completion_method,'-'),'|',IFNULL(close_method,'-'),'|',IF(cancel_requested_at IS NULL,'NULL','SET')) " +
        "FROM ticket WHERE ticket_no = '$ticketPending';") `
        -Expected 'CANCELED|NULL|SET|-|-|NULL（直接撤销：无负责人、有结束时间、不带完成/关闭字段）' `
        -Why '待受理撤销售后与"批准撤销"在库层面必须同形（都是已取消且无完成/关闭字段），差别只在时间线记录类型' `
        -Check { param($c) $c[0] -eq 'CANCELED|NULL|SET|-|-|NULL' }
    $script:terminalInfo['pending'] = [ordered]@{
        ticketNo     = $ticketPending
        fromStatus   = 'PENDING'
        toStatus     = 'CANCELED'
        version      = $rowPendingFinal.version
        assigneeKept = $rowPendingFinal.assigneeId
    }
    $pendingRecord = Get-LastRecordRow -TicketNo $ticketPending
    Add-Assertion -Name 'a7.pending.recordIsCancellation' `
        -Condition (($null -ne $pendingRecord) -and ($pendingRecord.recordType -eq 'CANCELLATION') -and
        ($pendingRecord.fromStatus -eq 'PENDING') -and ($pendingRecord.toStatus -eq 'CANCELED')) `
        -Expected '时间线最后一条是 CANCELLATION：from=PENDING → to=CANCELED' `
        -Actual "row=[$($pendingRecord.raw)]" `
        -Why 'CANCELLATION 与 CANCELLATION_APPROVED 必须可区分：前者是"没人需要同意"，后者是"负责人同意了"' `
        -Result $null
    $cancelOnTerminal = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketPending `
        -Action 'cancel' -Body (New-CancelBody -Version $rowPendingFinal.version -Reason '终态再撤销') `
        -Step 'a7.terminal.cancel' -Actor 'employee' -Note '终态上再撤销'
    Add-Assertion -Name 'a7.terminal.cancel.409' `
        -Condition (($cancelOnTerminal.Status -eq 409) -and ((Get-Code $cancelOnTerminal) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（终态不可再撤销）' `
        -Actual "status=$($cancelOnTerminal.Status) code=$(Get-Code $cancelOnTerminal)" `
        -Why '终态不可重开是全局规则；撤销路径也不例外' `
        -Result $cancelOnTerminal

    # ── 11. 断言组 a8：权限与身份（403 / 404 / 409） ────────────────────────
    $carrierOther = New-CarrierTicket -Key 'otherEmployee' -Step 'a8.other' `
        -Title "E2E 片E他人单 $stamp" -Purpose 'u1（只持员工权限）提交的工单：演示员工对它必须 404' `
        -RequesterClient $u1Client -RequesterToken $u1Token -RequesterActor 'temp-employee-only' -CategoryId $categoryId
    $ticketOtherEmployee = $carrierOther.ticketNo

    $otherDetail = Send-Req -Client $employeeClient -Method Get -Path "/fd/v1/tickets/$ticketOtherEmployee" `
        -Token $employeeToken -Step 'a8.notFound.otherDetail' -Actor 'employee' -Note 'employee 读取 u1 提交的工单'
    Add-Assertion -Name 'a8.notFound.otherEmployeeDetail.404' `
        -Condition (($otherDetail.Status -eq 404) -and ((Get-Code $otherDetail) -eq 'TICKET_NOT_FOUND')) `
        -Expected '404 + TICKET_NOT_FOUND' `
        -Actual "status=$($otherDetail.Status) code=$(Get-Code $otherDetail) ticket=$ticketOtherEmployee（属于 u1）" `
        -Why '员工只能看自己的工单（TICKET_VIEW_OWN 按提交人关系）；不可见与不存在统一 404，不泄露编号是否存在' `
        -Result $otherDetail
    $otherRequestCancel = Send-CancelAction -Client $employeeClient -Token $employeeToken `
        -TicketNo $ticketOtherEmployee -Action 'request-cancel' `
        -Body (New-RequestCancelBody -Version 0 -Reason '试图撤销别人的工单') `
        -Step 'a8.notFound.otherRequestCancel' -Actor 'employee' -Note '对他人工单发起撤销请求'
    Add-Assertion -Name 'a8.notFound.otherRequestCancel.404' `
        -Condition (($otherRequestCancel.Status -eq 404) -and ((Get-Code $otherRequestCancel) -eq 'TICKET_NOT_FOUND')) `
        -Expected '404 + TICKET_NOT_FOUND' `
        -Actual "status=$($otherRequestCancel.Status) code=$(Get-Code $otherRequestCancel)" `
        -Why '新动作必须复用同一条可见性 SQL；若这里变成 409/403，就能通过响应差异探测出"这个编号存在"' `
        -Result $otherRequestCancel
    # IT 持 TICKET_VIEW_QUEUE，而可见性 SQL 把它限定为 `canViewQueue = TRUE AND t.status = 'PENDING'`，
    # 因此 IT 对"别人的待受理工单"是**可见**的：这一格必然走到状态闸门，得到 409 而不是 404。
    # 要拿到"IT 看不见的工单"，必须用一张非 PENDING 且他未参与的工单（见下面 identity 那张）。
    $otherApprove = Send-CancelAction -Client $itClient -Token $itToken -TicketNo $ticketOtherEmployee `
        -Action 'approve-cancel' -Body (New-VersionOnlyBody -Version 0) `
        -Step 'a8.visibleByQueue.otherApprove' -Actor 'it' -Note 'IT 对他人待受理工单批准撤销请求：队列可见，但既无请求也不是处理中'
    Add-Assertion -Name 'a8.visibleByQueue.otherApproveCancel.409' `
        -Condition (($otherApprove.Status -eq 409) -and ((Get-Code $otherApprove) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（队列可见 → 不再是 404；但 PENDING 不在允许状态且没有待决请求）' `
        -Actual "status=$($otherApprove.Status) code=$(Get-Code $otherApprove)" `
        -Why ('这一格如实记录可见性口径：IT 的队列可见性只覆盖 status=PENDING，所以"别人的待受理单"他看得见；' +
        '真正被 404 挡住的是"非 PENDING 且他未参与"的工单（见 a8.notFound.itOnOthersProcessing）') `
        -Result $otherApprove
    Add-Assertion -Name 'a8.visibleByQueue.otherApproveCancel.changedNothing' `
        -Condition ($otherApprove.Status -ne 200 -and $otherApprove.Status -lt 500) `
        -Expected '不是 200 也不是 5xx（被拒即可，不容许任何写入）' `
        -Actual "status=$($otherApprove.Status) code=$(Get-Code $otherApprove)" `
        -Why '能看见不等于能操作；这一格必须停在 4xx，否则 IT 可以批准一张根本没有请求的工单' `
        -Result $otherApprove

    $unknownRequest = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $unknownTicketNo `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version 0 -Reason '不存在的编号') `
        -Step 'a8.notFound.unknownRequestCancel' -Actor 'employee' -Note '编号不存在'
    Add-Assertion -Name 'a8.notFound.unknownRequestCancel.404' `
        -Condition (($unknownRequest.Status -eq 404) -and ((Get-Code $unknownRequest) -eq 'TICKET_NOT_FOUND')) `
        -Expected '404 + TICKET_NOT_FOUND' `
        -Actual "status=$($unknownRequest.Status) code=$(Get-Code $unknownRequest)" `
        -Why '"编号不存在"与"无权查看"必须同码同文案，否则可以用它枚举工单号' `
        -Result $unknownRequest
    $unknownWithdraw = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $unknownTicketNo `
        -Action 'withdraw-cancel-request' -Body (New-VersionOnlyBody -Version 0) `
        -Step 'a8.notFound.unknownWithdraw' -Actor 'employee' -Note '编号不存在（撤回入口）'
    Add-Assertion -Name 'a8.notFound.unknownWithdraw.404' `
        -Condition (($unknownWithdraw.Status -eq 404) -and ((Get-Code $unknownWithdraw) -eq 'TICKET_NOT_FOUND')) `
        -Expected '404 + TICKET_NOT_FOUND' `
        -Actual "status=$($unknownWithdraw.Status) code=$(Get-Code $unknownWithdraw)" `
        -Why '四个端点都必须先过可见性；漏掉一个就留下一个可枚举编号的入口' `
        -Result $unknownWithdraw

    # u1 只持 EMPLOYEE 的三条权限（没有 TICKET_VIEW_QUEUE / TICKET_VIEW_PARTICIPATED），
    # 因此它对 employee 的工单不可见——可见性闸门排在身份闸门之前，实测应为 404 而不是 409。
    $u1OnEmployeeTicket = Send-CancelAction -Client $u1Client -Token $u1Token -TicketNo $ticketReject `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version $verRejectAfterWithdraw -Reason 'u1 试图撤销别人的工单') `
        -Step 'a8.identity.u1OnOthersTicket' -Actor 'temp-employee-only' -Note '有提交人动作权限但不是提交人，且看不到这张单'
    Add-Assertion -Name 'a8.identity.u1OnOthersTicket.honestObservation' `
        -Condition ($u1OnEmployeeTicket.Status -eq 404) `
        -Expected '404 + TICKET_NOT_FOUND（实测口径：u1 看不到这张单，可见性闸门先于身份闸门）' `
        -Actual "status=$($u1OnEmployeeTicket.Status) code=$(Get-Code $u1OnEmployeeTicket)" `
        -Why ('如实记录：只持 EMPLOYEE 权限的账号看不到他人工单，因此"不是提交人"这一条在它身上表现为 404 而不是 409；' +
        '真正的 409 身份格子必须用一个"看得见但这张单不是他提交的"主体，见下面 u5 的两格') `
        -Result $u1OnEmployeeTicket

    $carrierIdentity = New-CarrierTicket -Key 'identity' -Step 'a8.identity' `
        -Title "E2E 片E身份 $stamp" -Purpose 'u5（IT + 提交人动作权限）领取后既可见又有权限，用来构造"有权限但不是提交人"的 409' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketIdentity = $carrierIdentity.ticketNo
    $verIdentity = $carrierIdentity.version
    $claimIdentity = Invoke-ClaimCarrier -Step 'a8.identity' -TicketNo $ticketIdentity -Version $verIdentity `
        -Client $u5Client -Token $u5Token -Actor 'temp-it-plus-requester'
    $verIdentity = $claimIdentity.version
    $requestIdentity = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketIdentity `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version $verIdentity -Reason '提交人发起请求（负责人是 u5）') `
        -Step 'a8.identity.requestByRealRequester' -Actor 'employee' -Note '让这张单挂上一个属于 employee 的待决请求'
    Add-Assertion -Name 'a8.identity.requestByRealRequester.200' -Condition ($requestIdentity.Status -eq 200) `
        -Expected '200（为身份格子准备一个真实的待决请求）' `
        -Actual "status=$($requestIdentity.Status) code=$(Get-Code $requestIdentity) businessStatus=$((Get-Data $requestIdentity).status)" `
        -Why '没有待决请求时，身份判定与"没有请求"的 409 会混在一起，无法证明是哪一条条件在起作用' `
        -Result $requestIdentity
    $verIdentity = [long](Get-Data $requestIdentity).version

    # 这张单是 PROCESSING、负责人是 u5，而 it 既不是负责人也不是历史参与者（TICKET_VIEW_QUEUE 只覆盖
    # status=PENDING），所以 it 对它**不可见**：即使单上真的挂着一个待决请求，也必须先被 404 挡住。
    $itOnOthersProcessing = Send-CancelAction -Client $itClient -Token $itToken -TicketNo $ticketIdentity `
        -Action 'approve-cancel' -Body (New-VersionOnlyBody -Version $verIdentity) `
        -Step 'a8.notFound.itOnOthersProcessing' -Actor 'it' -Note 'IT 对他人处理中的工单（他未参与）批准撤销请求'
    Add-Assertion -Name 'a8.notFound.itOnOthersProcessing.404' `
        -Condition (($itOnOthersProcessing.Status -eq 404) -and ((Get-Code $itOnOthersProcessing) -eq 'TICKET_NOT_FOUND')) `
        -Expected '404 + TICKET_NOT_FOUND（IT 的可见范围是"待受理队列 + 自己参与过的"，不含他人处理中的工单）' `
        -Actual "status=$($itOnOthersProcessing.Status) code=$(Get-Code $itOnOthersProcessing) ticket=$ticketIdentity（负责人是 u5，单上确有请求）" `
        -Why ('这是"可见性先于状态/身份"的强证据：单上确实挂着一个真请求，若实现先判请求再判可见性，' +
        '这里就会变成 409/200，等于让任何人拿一个编号去试探并操作别人的工单') `
        -Result $itOnOthersProcessing

    $u5RequestCancel = Send-CancelAction -Client $u5Client -Token $u5Token -TicketNo $ticketIdentity `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version $verIdentity -Reason 'u5 试图发起撤销请求') `
        -Step 'a8.identity.u5RequestCancel' -Actor 'temp-it-plus-requester' -Note 'u5 是当前负责人且持 TICKET_REQUESTER_ACTION，但不是提交人'
    Add-Assertion -Name 'a8.identity.u5RequestCancel.409' `
        -Condition (($u5RequestCancel.Status -eq 409) -and ((Get-Code $u5RequestCancel) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（有权限、看得见，但不是提交人）' `
        -Actual "status=$($u5RequestCancel.Status) code=$(Get-Code $u5RequestCancel)" `
        -Why '这是身份闸门唯一真正可达的 409：条件更新里的 requester_id = actorId 必须生效，否则持有 TICKET_REQUESTER_ACTION 的 IT 就能替提交人发起请求' `
        -Result $u5RequestCancel
    $u5Withdraw = Send-CancelAction -Client $u5Client -Token $u5Token -TicketNo $ticketIdentity `
        -Action 'withdraw-cancel-request' -Body (New-VersionOnlyBody -Version $verIdentity) `
        -Step 'a8.identity.u5WithdrawCancelRequest' -Actor 'temp-it-plus-requester' -Note 'u5 是负责人且持提交人权限，但请求不是他发的'
    Add-Assertion -Name 'a8.identity.u5WithdrawCancelRequest.409' `
        -Condition (($u5Withdraw.Status -eq 409) -and ((Get-Code $u5Withdraw) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（当前负责人撤不掉别人的请求）' `
        -Actual "status=$($u5Withdraw.Status) code=$(Get-Code $u5Withdraw)" `
        -Why '撤回那条 SQL 的身份列必须是 requester_id 而不是 assignee_id；写成 assignee_id 时这一格会变成 200，等于负责人可以替提交人反悔' `
        -Result $u5Withdraw
    $rowIdentityAfterRejects = Get-TicketDbRow -TicketNo $ticketIdentity
    $identityIntactOk = ($null -ne $rowIdentityAfterRejects) -and
    ($rowIdentityAfterRejects.cancelRequestState -eq 'HAS_REQUEST') -and
    ($rowIdentityAfterRejects.cancelRequestReason -eq '提交人发起请求（负责人是 u5）') -and
    ($rowIdentityAfterRejects.assigneeId -eq $u5.userId) -and
    ($rowIdentityAfterRejects.version -eq $verIdentity)
    Add-Assertion -Name 'a8.identity.rejectedRequestsChangedNothing' -Condition $identityIntactOk `
        -Expected 'u5 的两格 409 之后，请求仍在、说明逐字未变、负责人仍是 u5、version 未变' `
        -Actual "row=[$($rowIdentityAfterRejects.raw)]" `
        -Why '被拒的身份尝试绝不能碰到别人的请求；这是"负责人也撤不掉"这条规则的可观察后果' `
        -Result $null

    # ── 12. 断言组 a9：库层约束不是摆设 ─────────────────────────────────────
    $carrierConstraint = New-CarrierTicket -Key 'constraint' -Step 'a9.constraint' `
        -Title "E2E 片E约束 $stamp" -Purpose '约束探针：处理中且没有任何请求，用来撞两条新 CHECK 与记录类型白名单' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketConstraint = $carrierConstraint.ticketNo
    $verConstraint = $carrierConstraint.version
    $claimConstraint = Invoke-ClaimCarrier -Step 'a9.constraint' -TicketNo $ticketConstraint -Version $verConstraint `
        -Client $itClient -Token $itToken -Actor 'it'
    $verConstraint = $claimConstraint.version

    $pairProbeSql = "UPDATE ticket SET cancel_requested_at = UTC_TIMESTAMP(3) WHERE ticket_no = '$ticketConstraint';"
    $pairProbe = Invoke-MySql -Sql $pairProbeSql
    $pairRejected = ($pairProbe.exitCode -ne 0) -and ($pairProbe.stderrText -match 'ck_ticket_cancel_request_pair')
    Add-Assertion -Name 'a9.constraint.cancelRequestPairRejected' -Condition $pairRejected `
        -Expected 'MySQL 拒绝：ck_ticket_cancel_request_pair（只写发起时间、不写说明与期限）' `
        -Actual "exitCode=$($pairProbe.exitCode) stderr=[$(Get-BriefText $pairProbe.stderrText 160)]" `
        -Why '三列同生同灭是"请求不变量"的兜底：应用层写漏一列时，库必须直接拒绝，而不是留下半截请求' `
        -Result $null
    $rowConstraintAfterPair = Get-TicketDbRow -TicketNo $ticketConstraint
    Add-Assertion -Name 'a9.constraint.pairProbeLeftRowUntouched' `
        -Condition (($null -ne $rowConstraintAfterPair) -and ($rowConstraintAfterPair.cancelRequestState -eq 'NO_REQUEST') -and
        ($rowConstraintAfterPair.status -eq 'PROCESSING') -and ($rowConstraintAfterPair.version -eq $verConstraint)) `
        -Expected '探针被拒后，工单仍是 PROCESSING、无请求、version 未变' `
        -Actual "row=[$($rowConstraintAfterPair.raw)]" `
        -Why '探针本身也不能改数据；如果它"意外成功"，必须在证据里记账并回滚（见下一格的处理）' `
        -Result $null
    if (-not $pairRejected) {
        $rollbackPair = Invoke-MySql -Sql "UPDATE ticket SET cancel_requested_at = NULL WHERE ticket_no = '$ticketConstraint';"
        $script:observed.Add("a9 的 pair 探针未被约束拒绝（exitCode=$($pairProbe.exitCode)），已把工单 $ticketConstraint 的 cancel_requested_at 改回 NULL 以免留下非法行；回滚 exitCode=$($rollbackPair.exitCode)")
    }

    $statusProbeSql = "UPDATE ticket SET cancel_requested_at = UTC_TIMESTAMP(3), " +
    "cancel_request_reason = '约束探针：终态上挂待决请求', " +
    "cancel_request_deadline_at = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 3 DAY) " +
    "WHERE ticket_no = '$ticketPending';"
    $statusProbe = Invoke-MySql -Sql $statusProbeSql
    $statusRejected = ($statusProbe.exitCode -ne 0) -and ($statusProbe.stderrText -match 'ck_ticket_cancel_request_status')
    Add-Assertion -Name 'a9.constraint.cancelRequestStatusRejected' -Condition $statusRejected `
        -Expected 'MySQL 拒绝：ck_ticket_cancel_request_status（已取消的工单上挂待决请求）' `
        -Actual "exitCode=$($statusProbe.exitCode) stderr=[$(Get-BriefText $statusProbe.stderrText 160)]；目标工单=$ticketPending（本脚本自建，已是 CANCELED）" `
        -Why '终态不允许残留待决请求；这条约束正是 close / confirm-resolution / approve-cancel 都必须清空请求三列的原因' `
        -Result $null
    $rowPendingAfterStatusProbe = Get-TicketDbRow -TicketNo $ticketPending
    Add-Assertion -Name 'a9.constraint.statusProbeLeftRowUntouched' `
        -Condition (($null -ne $rowPendingAfterStatusProbe) -and ($rowPendingAfterStatusProbe.status -eq 'CANCELED') -and
        ($rowPendingAfterStatusProbe.cancelRequestState -eq 'NO_REQUEST')) `
        -Expected '探针被拒后，终态行仍是 CANCELED 且无请求' `
        -Actual "row=[$($rowPendingAfterStatusProbe.raw)]" `
        -Why '同上：探针不允许改动运行中的事实（这张单在 a7 已撤销，收尾会按 ticket_no 删除）' `
        -Result $null
    if (-not $statusRejected) {
        $rollbackStatus = Invoke-MySql -Sql ("UPDATE ticket SET cancel_requested_at = NULL, cancel_request_reason = NULL, " +
            "cancel_request_deadline_at = NULL WHERE ticket_no = '$ticketPending';")
        $script:observed.Add("a9 的 status 探针未被约束拒绝（exitCode=$($statusProbe.exitCode)），已把工单 $ticketPending 的请求三列改回 NULL；回滚 exitCode=$($rollbackStatus.exitCode)")
    }

    $badTypeProbeSql = "INSERT INTO ticket_record (ticket_id, sequence_no, record_type, actor_type, actor_user_id, created_at) " +
    "SELECT id, record_seq + 1, 'FUTURE_RECORD', 'SYSTEM', NULL, UTC_TIMESTAMP(3) FROM ticket WHERE ticket_no = '$ticketConstraint';"
    $badTypeProbe = Invoke-MySql -Sql $badTypeProbeSql
    $badTypeRejected = ($badTypeProbe.exitCode -ne 0) -and ($badTypeProbe.stderrText -match 'ck_ticket_record_type')
    Add-Assertion -Name 'a9.constraint.recordTypeRejected' -Condition $badTypeRejected `
        -Expected 'MySQL 拒绝：ck_ticket_record_type（未登记的类型 FUTURE_RECORD）' `
        -Actual "exitCode=$($badTypeProbe.exitCode) stderr=[$(Get-BriefText $badTypeProbe.stderrText 160)]" `
        -Why '记录类型白名单防止"代码写了一个没人认识的类型、时间线随后 500"；新增类型必须走迁移（V7 就是这样扩的）' `
        -Result $null
    $goodTypeProbeSql = "INSERT INTO ticket_record (ticket_id, sequence_no, record_type, actor_type, actor_user_id, created_at) " +
    "SELECT id, record_seq + 1, 'CANCELLATION_REQUEST', 'SYSTEM', NULL, UTC_TIMESTAMP(3) FROM ticket WHERE ticket_no = '$ticketConstraint';"
    $goodTypeProbe = Invoke-MySql -Sql $goodTypeProbeSql
    Add-Assertion -Name 'a9.constraint.recordTypeAcceptedAfterV7' -Condition ($goodTypeProbe.exitCode -eq 0) `
        -Expected 'MySQL 接受：CANCELLATION_REQUEST（V7 重建后的白名单里包含它）' `
        -Actual "exitCode=$($goodTypeProbe.exitCode) stderr=[$(Get-BriefText $goodTypeProbe.stderrText 120)]" `
        -Why '反向对照：如果白名单没被 V7 重建，新类型会被拒，接口写入 CANCELLATION_REQUEST 就会 500' `
        -Result $null
    $probeRecordCleanupSql = "DELETE FROM ticket_record WHERE ticket_id = (SELECT id FROM ticket WHERE ticket_no = '$ticketConstraint') " +
    "AND record_type = 'CANCELLATION_REQUEST';"
    $probeRecordCleanup = Invoke-MySql -Sql $probeRecordCleanupSql
    $leftoverProbeRows = Get-MySqlRow -Sql ("SELECT COUNT(*) FROM ticket_record WHERE record_type = 'CANCELLATION_REQUEST' " +
        "AND ticket_id = (SELECT id FROM ticket WHERE ticket_no = '$ticketConstraint');")
    Add-Assertion -Name 'a9.constraint.recordTypeProbeRowRemoved' `
        -Condition (($probeRecordCleanup.exitCode -eq 0) -and $leftoverProbeRows.ok -and ([int]$leftoverProbeRows.columns[0] -eq 0)) `
        -Expected '探针插入的那条记录已按主键条件删除（该工单上 CANCELLATION_REQUEST 计数回到 0）' `
        -Actual "cleanupExitCode=$($probeRecordCleanup.exitCode) leftCount=$($leftoverProbeRows.raw)；SQL=[$probeRecordCleanupSql]" `
        -Why '探针必须自己收拾干净：它插的是"看起来合法"的记录，留下的唯一后果只能是证据里的这一段说明' `
        -Result $null
    $script:constraintProbeInfo = [ordered]@{
        pairProbeSql        = $pairProbeSql
        pairProbeRejected   = $pairRejected
        pairProbeStderr     = (Get-BriefText $pairProbe.stderrText 200)
        statusProbeSql      = $statusProbeSql
        statusProbeRejected = $statusRejected
        statusProbeStderr   = (Get-BriefText $statusProbe.stderrText 200)
        recordTypeBadSql    = $badTypeProbeSql
        recordTypeBadRejected = $badTypeRejected
        recordTypeGoodSql   = $goodTypeProbeSql
        recordTypeGoodAccepted = ($goodTypeProbe.exitCode -eq 0)
        cleanupSql          = $probeRecordCleanupSql
        expectedConstraints = @('ck_ticket_cancel_request_pair', 'ck_ticket_cancel_request_status', 'ck_ticket_record_type')
    }

    # ── 13. 断言组 a10：请求期间工单不冻结（IT 其它动作照常可用） ───────────
    $carrierRaceClose = New-CarrierTicket -Key 'raceClose' -Step 'a10.freeze' `
        -Title "E2E 片E并发关闭 $stamp" -Purpose '片 E：请求待批准期间 IT 仍可写处理记录，随后跑 close vs approve 并发' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketRaceClose = $carrierRaceClose.ticketNo
    $verRaceClose = $carrierRaceClose.version
    $claimRaceClose = Invoke-ClaimCarrier -Step 'a10.freeze' -TicketNo $ticketRaceClose -Version $verRaceClose `
        -Client $itClient -Token $itToken -Actor 'it'
    $verRaceClose = $claimRaceClose.version
    $requestRaceClose = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketRaceClose `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version $verRaceClose -Reason '提交人要求撤销（并发组准备）') `
        -Step 'a10.freeze.requestCancel' -Actor 'employee' -Note '先挂上一个待决请求'
    Add-Assertion -Name 'a10.freeze.requestCancel.200' -Condition ($requestRaceClose.Status -eq 200) `
        -Expected '200（请求已挂上）' `
        -Actual "status=$($requestRaceClose.Status) businessStatus=$((Get-Data $requestRaceClose).status)" `
        -Why '下面要证明的正是"挂着请求时 IT 依然能干活"，所以先把请求挂上' `
        -Result $requestRaceClose
    $verRaceClose = [long](Get-Data $requestRaceClose).version

    $processWhilePending = Send-CancelAction -Client $itClient -Token $itToken -TicketNo $ticketRaceClose `
        -Action 'add-processing-record' -Body (New-ContentBody -Version $verRaceClose -Content '请求待批准期间继续排查（不冻结工单）。') `
        -Step 'a10.freeze.addProcessingRecord' -Actor 'it' -Note '请求待批准期间写处理记录'
    $processWhilePendingData = Get-Data $processWhilePending
    Add-Assertion -Name 'a10.freeze.addProcessingRecordWhilePending.200' -Condition ($processWhilePending.Status -eq 200) `
        -Expected '200（请求期间不冻结工单，设计点④）' `
        -Actual "status=$($processWhilePending.Status) code=$(Get-Code $processWhilePending) businessStatus=$($processWhilePendingData.status) version=$($processWhilePendingData.version)" `
        -Why ('若请求期间冻结工单，就必须引入一个所有动作都要判断的隐形状态；现在的取舍是"不冻结 + 版本裁决"，' +
        '代价是 IT 推进后请求的版本条件失效、提交人需按新版本重新发起') `
        -Result $processWhilePending
    $rowRaceCloseAfterProcess = Get-TicketDbRow -TicketNo $ticketRaceClose
    $requestSurvivedOk = ($null -ne $rowRaceCloseAfterProcess) -and
    ($rowRaceCloseAfterProcess.cancelRequestState -eq 'HAS_REQUEST') -and
    ($rowRaceCloseAfterProcess.status -eq 'PROCESSING') -and
    ($rowRaceCloseAfterProcess.version -eq ($verRaceClose + 1))
    Add-Assertion -Name 'a10.freeze.requestSurvivedItActions' -Condition $requestSurvivedOk `
        -Expected '写处理记录之后，待决请求仍在、状态仍是 PROCESSING、version +1' `
        -Actual "row=[$($rowRaceCloseAfterProcess.raw)]" `
        -Why 'IT 的正常处理动作不该顺手把提交人的请求弄丢；请求只随批准/拒绝/撤回/终态而消失' `
        -Result $null
    $staleRequestAfterProcess = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketRaceClose `
        -Action 'withdraw-cancel-request' -Body (New-VersionOnlyBody -Version $verRaceClose) `
        -Step 'a10.freeze.staleRequestAfterItAction' -Actor 'employee' -Note 'IT 推进后用旧版本撤回请求'
    Add-Assertion -Name 'a10.freeze.staleVersionAfterItAction.409' `
        -Condition (($staleRequestAfterProcess.Status -eq 409) -and ((Get-Code $staleRequestAfterProcess) -eq 'TICKET_CONFLICT')) `
        -Expected '409 + TICKET_CONFLICT（版本已被 IT 的动作推进）' `
        -Actual "status=$($staleRequestAfterProcess.Status) code=$(Get-Code $staleRequestAfterProcess) data.version=$((Get-ErrData $staleRequestAfterProcess).version)" `
        -Why '不冻结的代价必须被显式记录：提交人拿旧版本撤回会被拒，需要刷新后重试（这正是"版本裁决"的可见后果）' `
        -Result $staleRequestAfterProcess
    $verRaceClose = $rowRaceCloseAfterProcess.version

    $carrierCloseClears = New-CarrierTicket -Key 'closeClears' -Step 'a10.closeClears' `
        -Title "E2E 片E关单清请求 $stamp" -Purpose '片 E：挂着待决请求时直接关闭，证明 closeManually 会清空请求三列' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketCloseClears = $carrierCloseClears.ticketNo
    $verCloseClears = $carrierCloseClears.version
    $claimCloseClears = Invoke-ClaimCarrier -Step 'a10.closeClears' -TicketNo $ticketCloseClears -Version $verCloseClears `
        -Client $itClient -Token $itToken -Actor 'it'
    $verCloseClears = $claimCloseClears.version
    $requestCloseClears = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketCloseClears `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version $verCloseClears -Reason '提交人要求撤销（随后被 IT 关闭）') `
        -Step 'a10.closeClears.requestCancel' -Actor 'employee' -Note '挂上待决请求，等 IT 直接关单'
    Add-Assertion -Name 'a10.closeClears.requestCancel.200' -Condition ($requestCloseClears.Status -eq 200) `
        -Expected '200' -Actual "status=$($requestCloseClears.Status)" `
        -Why '准备条件；下面要证明关单会把请求一并清掉（否则撞 ck_ticket_cancel_request_status）' `
        -Result $requestCloseClears
    $verCloseClears = [long](Get-Data $requestCloseClears).version
    $closeWhilePending = Send-CancelAction -Client $itClient -Token $itToken -TicketNo $ticketCloseClears `
        -Action 'close' -Body (New-CloseBody -Version $verCloseClears -ReasonCode 'OUT_OF_SCOPE' -Description '不属于 IT 服务范围（请求待批准期间直接关闭）。') `
        -Step 'a10.closeClears.close' -Actor 'it' -Note '挂着待决请求时直接关闭'
    $closeWhilePendingData = Get-Data $closeWhilePending
    Add-Assertion -Name 'a10.closeClears.200' -Condition ($closeWhilePending.Status -eq 200) `
        -Expected '200 且 status=CLOSED' `
        -Actual "status=$($closeWhilePending.Status) code=$(Get-Code $closeWhilePending) businessStatus=$($closeWhilePendingData.status)" `
        -Why '关闭也要求处理中 + 双权限；它与待决请求并存，正是"请求期间不冻结"的第二个证据' `
        -Result $closeWhilePending
    $rowCloseClears = Get-TicketDbRow -TicketNo $ticketCloseClears
    $closeClearsOk = ($null -ne $rowCloseClears) -and ($rowCloseClears.status -eq 'CLOSED') -and
    ($rowCloseClears.cancelRequestState -eq 'NO_REQUEST') -and ($rowCloseClears.cancelRequestReason -eq 'NULL') -and
    ($rowCloseClears.closeMethod -eq 'MANUAL') -and ($rowCloseClears.closeReason -eq 'OUT_OF_SCOPE')
    Add-Assertion -Name 'a10.closeClears.requestColumnsCleared' -Condition $closeClearsOk `
        -Expected 'CLOSED + close_method=MANUAL + close_reason=OUT_OF_SCOPE，且请求三列全部清空' `
        -Actual "row=[$($rowCloseClears.raw)]" `
        -Why ('这是三条约束陷阱里"closeManually 必须一并清空请求三列"的确定性证据（不靠并发撞运气）；' +
        '漏了这一步的实现会在这里撞 ck_ticket_cancel_request_status 而报 500') `
        -Result $null
    Assert-Db -Name 'a10.db.closeClearedRequestsAndSetCloseFields' `
        -Sql ("SELECT CONCAT(status,'|',IF(cancel_requested_at IS NULL,'NULL','SET'),'|'," +
        "IF(cancel_request_deadline_at IS NULL,'NULL','SET'),'|',IFNULL(close_method,'-'),'|',IFNULL(close_reason,'-'),'|'," +
        "IF(ended_at IS NULL,'NULL','SET')) FROM ticket WHERE ticket_no = '$ticketCloseClears';") `
        -Expected 'CLOSED|NULL|NULL|MANUAL|OUT_OF_SCOPE|SET（关单清请求三列，并写下关闭方式与原因）' `
        -Why '人工关闭与两阶段撤销是两条不同的终态路径；这一格把"关闭也必须清请求列"钉在库层' `
        -Check { param($c) $c[0] -eq 'CLOSED|NULL|NULL|MANUAL|OUT_OF_SCOPE|SET' }
    Add-KeyAction -Order 9 -Actor 'it' -Action 'close(挂请求)' -Method 'POST' `
        -Path (Get-ActionPath $ticketCloseClears 'close') -TicketNo $ticketCloseClears -Result $closeWhilePending `
        -StatusAfter 'CLOSED' -Step 'a10.closeClears.close'

    $carrierConfirmClears = New-CarrierTicket -Key 'confirmClears' -Step 'a10.confirmClears' `
        -Title "E2E 片E确认清请求 $stamp" -Purpose '片 E：待确认上挂请求后员工确认完成，证明 confirmResolution 也会清空请求三列' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketConfirmClears = $carrierConfirmClears.ticketNo
    $verConfirmClears = $carrierConfirmClears.version
    $claimConfirmClears = Invoke-ClaimCarrier -Step 'a10.confirmClears' -TicketNo $ticketConfirmClears -Version $verConfirmClears `
        -Client $itClient -Token $itToken -Actor 'it'
    $verConfirmClears = $claimConfirmClears.version
    $resolveConfirmClears = Send-CancelAction -Client $itClient -Token $itToken -TicketNo $ticketConfirmClears `
        -Action 'submit-resolution' -Body (New-ContentBody -Version $verConfirmClears -Content '已更换硒鼓并测试通过。') `
        -Step 'a10.confirmClears.submitResolution' -Actor 'it' -Note '提交解决结果，进入待确认（带 7d 确认期限）'
    $resolveData = Get-Data $resolveConfirmClears
    Add-Assertion -Name 'a10.confirmClears.submitResolution.200' `
        -Condition (($resolveConfirmClears.Status -eq 200) -and ($resolveData.status -eq 'WAITING_FOR_CONFIRMATION')) `
        -Expected '200 且 status=WAITING_FOR_CONFIRMATION' `
        -Actual "status=$($resolveConfirmClears.Status) businessStatus=$($resolveData.status) deadline=$($resolveData.actionDeadlineAt)" `
        -Why '待确认是第三个允许发起请求的状态，也是"批准/确认都要清期限"的另一个来源状态' `
        -Result $resolveConfirmClears
    $verConfirmClears = [long]$resolveData.version
    $confirmDeadline = [string]$resolveData.actionDeadlineAt
    $requestConfirmClears = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketConfirmClears `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version $verConfirmClears -Reason '提交人想撤销（但随后自己确认了解决）') `
        -Step 'a10.confirmClears.requestCancel' -Actor 'employee' -Note '在待确认上发起撤销请求'
    Add-Assertion -Name 'a10.confirmClears.requestCancel.200' -Condition ($requestConfirmClears.Status -eq 200) `
        -Expected '200 且 status 仍是 WAITING_FOR_CONFIRMATION' `
        -Actual "status=$($requestConfirmClears.Status) businessStatus=$((Get-Data $requestConfirmClears).status) actionDeadlineAt=$((Get-Data $requestConfirmClears).actionDeadlineAt)" `
        -Why '请求不改状态；且确认期限必须原样保留（与主链的待补充期限同一口径）' `
        -Result $requestConfirmClears
    Add-Assertion -Name 'a10.confirmClears.confirmationDeadlinePreserved' `
        -Condition ([string](Get-Data $requestConfirmClears).actionDeadlineAt -eq $confirmDeadline) `
        -Expected "actionDeadlineAt 仍是确认期限（$confirmDeadline）" `
        -Actual "actionDeadlineAt=$((Get-Data $requestConfirmClears).actionDeadlineAt)" `
        -Why '请求自带的响应期限不能覆盖工单的确认期限；两者是不同的期限，混用会让 7d 的确认超时语义被改掉' `
        -Result $requestConfirmClears
    $verConfirmClears = [long](Get-Data $requestConfirmClears).version
    $confirmWhilePending = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketConfirmClears `
        -Action 'confirm-resolution' -Body (New-VersionOnlyBody -Version $verConfirmClears) `
        -Step 'a10.confirmClears.confirmResolution' -Actor 'employee' -Note '员工确认解决：终态化，必须一并清空请求三列与确认期限'
    $confirmWhilePendingData = Get-Data $confirmWhilePending
    Add-Assertion -Name 'a10.confirmClears.confirmResolution.200' `
        -Condition (($confirmWhilePending.Status -eq 200) -and ($confirmWhilePendingData.status -eq 'COMPLETED')) `
        -Expected '200 且 status=COMPLETED' `
        -Actual "status=$($confirmWhilePending.Status) code=$(Get-Code $confirmWhilePending) businessStatus=$($confirmWhilePendingData.status)" `
        -Why '确认完成是另一条进入终态的路径；它与待决请求并存时同样必须清空请求三列' `
        -Result $confirmWhilePending
    $rowConfirmClears = Get-TicketDbRow -TicketNo $ticketConfirmClears
    $confirmClearsOk = ($null -ne $rowConfirmClears) -and ($rowConfirmClears.status -eq 'COMPLETED') -and
    ($rowConfirmClears.cancelRequestState -eq 'NO_REQUEST') -and ($rowConfirmClears.actionDeadlineAt -eq 'NULL') -and
    ($rowConfirmClears.completionMethod -eq 'REQUESTER_CONFIRMED') -and ($rowConfirmClears.endedAt -eq 'SET') -and
    ($rowConfirmClears.closeMethod -eq 'NULL') -and ($rowConfirmClears.assigneeId -eq $itUserId)
    Add-Assertion -Name 'a10.confirmClears.requestColumnsCleared' -Condition $confirmClearsOk `
        -Expected 'COMPLETED + completion_method=REQUESTER_CONFIRMED + 请求三列清空 + 确认期限清空 + 负责人保留' `
        -Actual "row=[$($rowConfirmClears.raw)]" `
        -Why ('这是"confirmResolution 也必须清请求三列"的确定性证据，也是三条终态可区分的一次正面样本' +
        '（已完成带 completion_method、无关闭字段）') `
        -Result $null
    Assert-Db -Name 'a10.db.confirmClearedRequestsAndKeptCompletion' `
        -Sql ("SELECT CONCAT(status,'|',IF(cancel_requested_at IS NULL,'NULL','SET'),'|'," +
        "IF(action_deadline_at IS NULL,'NULL','SET'),'|',IFNULL(completion_method,'-'),'|'," +
        "IFNULL(close_method,'-'),'|',IF(ended_at IS NULL,'NULL','SET')) " +
        "FROM ticket WHERE ticket_no = '$ticketConfirmClears';") `
        -Expected 'COMPLETED|NULL|NULL|REQUESTER_CONFIRMED|-|SET（确认完成清请求列与确认期限，且不带关闭字段）' `
        -Why '第三条进入终态的路径（员工确认）同样必须清请求三列；它的"已完成"身份由 completion_method 单独表达' `
        -Check { param($c) $c[0] -eq 'COMPLETED|NULL|NULL|REQUESTER_CONFIRMED|-|SET' }
    Add-KeyAction -Order 10 -Actor 'employee' -Action 'confirm-resolution(挂请求)' -Method 'POST' `
        -Path (Get-ActionPath $ticketConfirmClears 'confirm-resolution') -TicketNo $ticketConfirmClears `
        -Result $confirmWhilePending -StatusAfter 'COMPLETED' -Step 'a10.confirmClears.confirmResolution'

    # ── 14. 断言组 a11：三组真并发 ──────────────────────────────────────────
    # 11a：同一负责人（两个会话）在同一版本上并发 approve + reject
    $carrierRaceDecide = New-CarrierTicket -Key 'raceDecide' -Step 'a11a.raceDecide' `
        -Title "E2E 片E并发决策 $stamp" -Purpose '并发：approve-cancel vs reject-cancel（同一负责人，同一版本）' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketRaceDecide = $carrierRaceDecide.ticketNo
    $verRaceDecide = $carrierRaceDecide.version
    $claimRaceDecide = Invoke-ClaimCarrier -Step 'a11a.raceDecide' -TicketNo $ticketRaceDecide -Version $verRaceDecide `
        -Client $itClient -Token $itToken -Actor 'it'
    $verRaceDecide = $claimRaceDecide.version
    $requestRaceDecide = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketRaceDecide `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version $verRaceDecide -Reason '并发组：批准 vs 拒绝') `
        -Step 'a11a.raceDecide.requestCancel' -Actor 'employee' -Note '准备待决请求'
    Add-Assertion -Name 'a11a.raceDecide.setup.200' -Condition ($requestRaceDecide.Status -eq 200) `
        -Expected '200（待决请求就位）' -Actual "status=$($requestRaceDecide.Status)" `
        -Why '并发用例必须有真实的共同起点（同一版本上存在一个待决请求）' -Result $requestRaceDecide
    $verRaceDecide = [long](Get-Data $requestRaceDecide).version

    $raceDecide = Send-ConcurrentPair -ClientA $itClient -PathA (Get-ActionPath $ticketRaceDecide 'approve-cancel') `
        -TokenA $itToken -BodyA (New-VersionOnlyBody -Version $verRaceDecide) -ActorA 'it' `
        -StepA 'a11a.concurrent.approve' -NoteA '会话一：批准' `
        -ClientB $itSecondClient -PathB (Get-ActionPath $ticketRaceDecide 'reject-cancel') `
        -TokenB $itToken2 -BodyB (New-RejectCancelBody -Version $verRaceDecide -Reason '并发组：拒绝') `
        -ActorB 'it(第二会话)' -StepB 'a11a.concurrent.reject' -NoteB '会话二：拒绝'
    $raceDecideAll = @($raceDecide.A, $raceDecide.B)
    $raceDecideWinners = @($raceDecideAll | Where-Object { $_.Status -eq 200 })
    $raceDecideLosers = @($raceDecideAll | Where-Object { $_.Status -ne 200 })
    $raceDecideServerErrors = @($raceDecideAll | Where-Object { $_.Status -ge 500 })
    $rowRaceDecideFinal = Get-TicketDbRow -TicketNo $ticketRaceDecide
    $script:concurrencyDecideInfo = [ordered]@{
        ticketNo         = $ticketRaceDecide
        sharedVersion    = $verRaceDecide
        dispatchMode     = $raceDecide.dispatchMode
        dispatchedAt     = $raceDecide.dispatchedAt
        approve          = @{ httpStatus = $raceDecide.A.Status; code = (Get-Code $raceDecide.A); traceId = $raceDecide.A.TraceId }
        reject           = @{ httpStatus = $raceDecide.B.Status; code = (Get-Code $raceDecide.B); traceId = $raceDecide.B.TraceId }
        winnerCount      = $raceDecideWinners.Count
        serverErrorCount = $raceDecideServerErrors.Count
        finalDbRow       = $rowRaceDecideFinal.raw
        why              = '批准与拒绝形状相同、只有状态迁移那一行不同；同一版本并发时只能有一条 SQL 命中'
    }
    Add-Assertion -Name 'a11a.concurrent.exactlyOneWinner' -Condition ($raceDecideWinners.Count -eq 1) `
        -Expected '同一版本上「批准 vs 拒绝」恰好一个请求成功（200）' `
        -Actual "approve=$($raceDecide.A.Status) reject=$($raceDecide.B.Status)" `
        -Why '两者都 200 说明版本条件失效（工单会被批准又被拒绝）；都失败说明闸门过严' `
        -Result $null
    Add-Assertion -Name 'a11a.concurrent.loserIsConflictNotServerError' `
        -Condition (($raceDecideLosers.Count -eq 1) -and ($raceDecideLosers[0].Status -eq 409) -and
        ((Get-Code $raceDecideLosers[0]) -eq 'TICKET_CONFLICT')) `
        -Expected '败者是 409 + TICKET_CONFLICT（可立即重试），不是 500' `
        -Actual "败者 status=$($raceDecideLosers[0].Status) code=$(Get-Code $raceDecideLosers[0])；approve=$($raceDecide.A.Status) reject=$($raceDecide.B.Status)" `
        -Why '并发失败的失败模式必须可重试；500 只会让用户报障（片 D 的交叉死锁就是这么暴露的）' `
        -Result $null
    Add-Assertion -Name 'a11a.concurrent.noServerError' -Condition ($raceDecideServerErrors.Count -eq 0) `
        -Expected '两路都没有 5xx' `
        -Actual "approve=$($raceDecide.A.Status) reject=$($raceDecide.B.Status)" `
        -Why '两个动作都以同一条工单行的条件更新为唯一胜者判定，不应出现锁等待超时或死锁回滚' `
        -Result $null
    $raceDecideWinnerKind = ''
    if ($raceDecide.A.Status -eq 200) { $raceDecideWinnerKind = 'APPROVE' }
    if ($raceDecide.B.Status -eq 200) { $raceDecideWinnerKind = 'REJECT' }
    $raceDecideDbOk = ($null -ne $rowRaceDecideFinal) -and ($rowRaceDecideFinal.version -eq ($verRaceDecide + 1))
    if ($raceDecideWinnerKind -eq 'APPROVE') {
        $raceDecideDbOk = $raceDecideDbOk -and ($rowRaceDecideFinal.status -eq 'CANCELED') -and
        ($rowRaceDecideFinal.cancelRequestState -eq 'NO_REQUEST') -and ($rowRaceDecideFinal.endedAt -eq 'SET')
    }
    if ($raceDecideWinnerKind -eq 'REJECT') {
        $raceDecideDbOk = $raceDecideDbOk -and ($rowRaceDecideFinal.status -eq 'PROCESSING') -and
        ($rowRaceDecideFinal.cancelRequestState -eq 'NO_REQUEST') -and ($rowRaceDecideFinal.endedAt -eq 'NULL')
    }
    Add-Assertion -Name 'a11a.concurrent.winnerDeterminesDbState' -Condition $raceDecideDbOk `
        -Expected '库中状态与赢家一致（批准赢 → CANCELED + 请求清空 + 有结束时间；拒绝赢 → PROCESSING + 请求清空 + 无结束时间），version 只 +1' `
        -Actual "赢家=$raceDecideWinnerKind row=[$($rowRaceDecideFinal.raw)]" `
        -Why '两种结局都必须把请求清空（ck_ticket_cancel_request_status 只允许请求存在于三个未终结状态），差别只在状态与结束时间' `
        -Result $null
    $raceDecideExpectedStatus = 'PROCESSING'
    if ($raceDecideWinnerKind -eq 'APPROVE') { $raceDecideExpectedStatus = 'CANCELED' }
    Assert-Db -Name 'a11a.db.versionPlusOneAndRequestGone' `
        -Sql ("SELECT CONCAT(version,'|',IF(cancel_requested_at IS NULL,'NULL','SET'),'|',status) " +
        "FROM ticket WHERE ticket_no = '$ticketRaceDecide';") `
        -Expected "$($verRaceDecide + 1)|NULL|$raceDecideExpectedStatus（版本恰好 +1、请求已被清空、状态与赢家一致）" `
        -Why '并发下"版本只 +1"是唯一胜者的硬指标：若 +2，说明批准与拒绝两条 SQL 都命中了，工单被写了两遍' `
        -Check { param($c) $c[0] -eq ("$($verRaceDecide + 1)|NULL|" + $raceDecideExpectedStatus) }
    Add-Assertion -Name 'a11a.concurrent.loserCarriesWinnerSnapshot' `
        -Condition (($null -ne $rowRaceDecideFinal) -and ([int](Get-ErrData $raceDecideLosers[0]).version -eq $rowRaceDecideFinal.version) -and
        ((Get-ErrData $raceDecideLosers[0]).status -eq $rowRaceDecideFinal.status)) `
        -Expected '败者的 409 带赢家造成的当前快照（data.version / data.status 与库一致）' `
        -Actual "败者 data.version=$((Get-ErrData $raceDecideLosers[0]).version) data.status=$((Get-ErrData $raceDecideLosers[0]).status)；库中 version=$($rowRaceDecideFinal.version) status=$($rowRaceDecideFinal.status)" `
        -Why '前端只有拿到冲突快照才能一键刷新并正确重试' `
        -Result $null
    Add-KeyAction -Order 11 -Actor 'it' -Action 'approve-cancel(并发)' -Method 'POST' `
        -Path (Get-ActionPath $ticketRaceDecide 'approve-cancel') -TicketNo $ticketRaceDecide -Result $raceDecide.A `
        -StatusAfter $raceDecideWinnerKind -Step 'a11a.concurrent.approve'
    Add-KeyAction -Order 12 -Actor 'it(第二会话)' -Action 'reject-cancel(并发)' -Method 'POST' `
        -Path (Get-ActionPath $ticketRaceDecide 'reject-cancel') -TicketNo $ticketRaceDecide -Result $raceDecide.B `
        -StatusAfter $raceDecideWinnerKind -Step 'a11a.concurrent.reject'

    # 11b：负责人批准 vs 提交人撤回（同一版本）
    $carrierRaceWithdraw = New-CarrierTicket -Key 'raceWithdraw' -Step 'a11b.raceWithdraw' `
        -Title "E2E 片E并发撤回 $stamp" -Purpose '并发：approve-cancel vs withdraw-cancel-request（负责人 vs 提交人），待确认状态' `
        -RequesterClient $employeeClient -RequesterToken $employeeToken -RequesterActor 'employee' -CategoryId $categoryId
    $ticketRaceWithdraw = $carrierRaceWithdraw.ticketNo
    $verRaceWithdraw = $carrierRaceWithdraw.version
    $claimRaceWithdraw = Invoke-ClaimCarrier -Step 'a11b.raceWithdraw' -TicketNo $ticketRaceWithdraw -Version $verRaceWithdraw `
        -Client $itClient -Token $itToken -Actor 'it'
    $verRaceWithdraw = $claimRaceWithdraw.version
    $resolveRaceWithdraw = Send-CancelAction -Client $itClient -Token $itToken -TicketNo $ticketRaceWithdraw `
        -Action 'submit-resolution' -Body (New-ContentBody -Version $verRaceWithdraw -Content '并发组：先提交解决结果，进入待确认。') `
        -Step 'a11b.raceWithdraw.submitResolution' -Actor 'it' -Note '让并发发生在「待确认」上（第三个允许发起请求的状态）'
    Add-Assertion -Name 'a11b.raceWithdraw.setup.resolve.200' `
        -Condition (($resolveRaceWithdraw.Status -eq 200) -and ((Get-Data $resolveRaceWithdraw).status -eq 'WAITING_FOR_CONFIRMATION')) `
        -Expected '200 且 status=WAITING_FOR_CONFIRMATION' `
        -Actual "status=$($resolveRaceWithdraw.Status) businessStatus=$((Get-Data $resolveRaceWithdraw).status)" `
        -Why '三组并发分别落在处理中与待确认上，避免只在一个状态上验证' -Result $resolveRaceWithdraw
    $verRaceWithdraw = [long](Get-Data $resolveRaceWithdraw).version
    $raceWithdrawDeadline = [string](Get-Data $resolveRaceWithdraw).actionDeadlineAt
    $requestRaceWithdraw = Send-CancelAction -Client $employeeClient -Token $employeeToken -TicketNo $ticketRaceWithdraw `
        -Action 'request-cancel' -Body (New-RequestCancelBody -Version $verRaceWithdraw -Reason '并发组：提交人随后又撤回了请求') `
        -Step 'a11b.raceWithdraw.requestCancel' -Actor 'employee' -Note '准备待决请求'
    Add-Assertion -Name 'a11b.raceWithdraw.setup.request.200' -Condition ($requestRaceWithdraw.Status -eq 200) `
        -Expected '200（待决请求就位）' -Actual "status=$($requestRaceWithdraw.Status)" `
        -Why '并发用例的共同起点' -Result $requestRaceWithdraw
    $verRaceWithdraw = [long](Get-Data $requestRaceWithdraw).version

    $raceWithdraw = Send-ConcurrentPair -ClientA $itClient -PathA (Get-ActionPath $ticketRaceWithdraw 'approve-cancel') `
        -TokenA $itToken -BodyA (New-VersionOnlyBody -Version $verRaceWithdraw) -ActorA 'it' `
        -StepA 'a11b.concurrent.approve' -NoteA '负责人：批准' `
        -ClientB $employeeClient -PathB (Get-ActionPath $ticketRaceWithdraw 'withdraw-cancel-request') `
        -TokenB $employeeToken -BodyB (New-VersionOnlyBody -Version $verRaceWithdraw) -ActorB 'employee' `
        -StepB 'a11b.concurrent.withdraw' -NoteB '提交人：撤回自己的请求'
    $raceWithdrawAll = @($raceWithdraw.A, $raceWithdraw.B)
    $raceWithdrawWinners = @($raceWithdrawAll | Where-Object { $_.Status -eq 200 })
    $raceWithdrawLosers = @($raceWithdrawAll | Where-Object { $_.Status -ne 200 })
    $raceWithdrawServerErrors = @($raceWithdrawAll | Where-Object { $_.Status -ge 500 })
    $rowRaceWithdrawFinal = Get-TicketDbRow -TicketNo $ticketRaceWithdraw
    $script:concurrencyWithdrawInfo = [ordered]@{
        ticketNo         = $ticketRaceWithdraw
        sharedVersion    = $verRaceWithdraw
        dispatchMode     = $raceWithdraw.dispatchMode
        dispatchedAt     = $raceWithdraw.dispatchedAt
        approve          = @{ httpStatus = $raceWithdraw.A.Status; code = (Get-Code $raceWithdraw.A); traceId = $raceWithdraw.A.TraceId }
        withdraw         = @{ httpStatus = $raceWithdraw.B.Status; code = (Get-Code $raceWithdraw.B); traceId = $raceWithdraw.B.TraceId }
        winnerCount      = $raceWithdrawWinners.Count
        serverErrorCount = $raceWithdrawServerErrors.Count
        finalDbRow       = $rowRaceWithdrawFinal.raw
        why              = '负责人与提交人同时动手：批准与撤回共用同一组条件（版本 + 当事人 + 请求存在），只能有一条命中'
    }
    Add-Assertion -Name 'a11b.concurrent.exactlyOneWinner' -Condition ($raceWithdrawWinners.Count -eq 1) `
        -Expected '同一版本上「批准 vs 撤回」恰好一个请求成功（200）' `
        -Actual "approve=$($raceWithdraw.A.Status) withdraw=$($raceWithdraw.B.Status)" `
        -Why '若都成功，会出现"工单已取消但请求显示被撤回"这种自相矛盾的历史' `
        -Result $null
    Add-Assertion -Name 'a11b.concurrent.loserIsConflictNotServerError' `
        -Condition (($raceWithdrawLosers.Count -eq 1) -and ($raceWithdrawLosers[0].Status -eq 409) -and
        ((Get-Code $raceWithdrawLosers[0]) -eq 'TICKET_CONFLICT')) `
        -Expected '败者是 409 + TICKET_CONFLICT' `
        -Actual "败者 status=$($raceWithdrawLosers[0].Status) code=$(Get-Code $raceWithdrawLosers[0])" `
        -Why '跨身份并发（负责人 vs 提交人）同样必须收敛到可重试的 409' `
        -Result $null
    Add-Assertion -Name 'a11b.concurrent.noServerError' -Condition ($raceWithdrawServerErrors.Count -eq 0) `
        -Expected '两路都没有 5xx' `
        -Actual "approve=$($raceWithdraw.A.Status) withdraw=$($raceWithdraw.B.Status)" `
        -Why '两条 SQL 都只碰工单行，不存在与 IAM 行交叉加锁的环' `
        -Result $null
    $raceWithdrawWinnerKind = ''
    if ($raceWithdraw.A.Status -eq 200) { $raceWithdrawWinnerKind = 'APPROVE' }
    if ($raceWithdraw.B.Status -eq 200) { $raceWithdrawWinnerKind = 'WITHDRAW' }
    $raceWithdrawDbOk = ($null -ne $rowRaceWithdrawFinal) -and ($rowRaceWithdrawFinal.version -eq ($verRaceWithdraw + 1))
    if ($raceWithdrawWinnerKind -eq 'APPROVE') {
        $raceWithdrawDbOk = $raceWithdrawDbOk -and ($rowRaceWithdrawFinal.status -eq 'CANCELED') -and
        ($rowRaceWithdrawFinal.cancelRequestState -eq 'NO_REQUEST') -and ($rowRaceWithdrawFinal.actionDeadlineAt -eq 'NULL')
    }
    if ($raceWithdrawWinnerKind -eq 'WITHDRAW') {
        $raceWithdrawDbOk = $raceWithdrawDbOk -and ($rowRaceWithdrawFinal.status -eq 'WAITING_FOR_CONFIRMATION') -and
        ($rowRaceWithdrawFinal.cancelRequestState -eq 'NO_REQUEST') -and
        ($rowRaceWithdrawFinal.actionDeadlineAt -ne 'NULL')
    }
    Add-Assertion -Name 'a11b.concurrent.winnerDeterminesDbState' -Condition $raceWithdrawDbOk `
        -Expected ('批准赢 → CANCELED + 请求清空 + 确认期限清空；撤回赢 → 仍是 WAITING_FOR_CONFIRMATION + 请求清空 + ' +
        '确认期限保留（这条正是"批准才清期限、撤回不清期限"的分水岭）') `
        -Actual "赢家=$raceWithdrawWinnerKind（确认期限原值=$raceWithdrawDeadline）row=[$($rowRaceWithdrawFinal.raw)]" `
        -Why '撤回只让请求失效，不该顺手动工单的期限；批准进入终态则必须把期限清掉' `
        -Result $null
    Add-Assertion -Name 'a11b.concurrent.loserCarriesWinnerSnapshot' `
        -Condition (($null -ne $rowRaceWithdrawFinal) -and ([int](Get-ErrData $raceWithdrawLosers[0]).version -eq $rowRaceWithdrawFinal.version)) `
        -Expected '败者的 409 带赢家造成的当前快照' `
        -Actual "败者 data.version=$((Get-ErrData $raceWithdrawLosers[0]).version) 库中 version=$($rowRaceWithdrawFinal.version)" `
        -Why '同上：冲突响应必须自解释' `
        -Result $null
    Add-KeyAction -Order 13 -Actor 'it' -Action 'approve-cancel(并发)' -Method 'POST' `
        -Path (Get-ActionPath $ticketRaceWithdraw 'approve-cancel') -TicketNo $ticketRaceWithdraw -Result $raceWithdraw.A `
        -StatusAfter $raceWithdrawWinnerKind -Step 'a11b.concurrent.approve'
    Add-KeyAction -Order 14 -Actor 'employee' -Action 'withdraw-cancel-request(并发)' -Method 'POST' `
        -Path (Get-ActionPath $ticketRaceWithdraw 'withdraw-cancel-request') -TicketNo $ticketRaceWithdraw `
        -Result $raceWithdraw.B -StatusAfter $raceWithdrawWinnerKind -Step 'a11b.concurrent.withdraw'

    # 11c：关闭 vs 批准（同一版本）——证明请求待批准期间工单不冻结，且两条终态路径互斥
    $raceClose = Send-ConcurrentPair -ClientA $itClient -PathA (Get-ActionPath $ticketRaceClose 'approve-cancel') `
        -TokenA $itToken -BodyA (New-VersionOnlyBody -Version $verRaceClose) -ActorA 'it' `
        -StepA 'a11c.concurrent.approve' -NoteA '负责人：批准撤销请求' `
        -ClientB $itSecondClient -PathB (Get-ActionPath $ticketRaceClose 'close') `
        -TokenB $itToken2 -BodyB (New-CloseBody -Version $verRaceClose -ReasonCode 'INVALID' -Description '并发组：IT 直接关闭') `
        -ActorB 'it(第二会话)' -StepB 'a11c.concurrent.close' -NoteB '同一负责人（第二会话）：关闭'
    $raceCloseAll = @($raceClose.A, $raceClose.B)
    $raceCloseWinners = @($raceCloseAll | Where-Object { $_.Status -eq 200 })
    $raceCloseLosers = @($raceCloseAll | Where-Object { $_.Status -ne 200 })
    $raceCloseServerErrors = @($raceCloseAll | Where-Object { $_.Status -ge 500 })
    $rowRaceCloseFinal = Get-TicketDbRow -TicketNo $ticketRaceClose
    $script:concurrencyCloseInfo = [ordered]@{
        ticketNo         = $ticketRaceClose
        sharedVersion    = $verRaceClose
        dispatchMode     = $raceClose.dispatchMode
        dispatchedAt     = $raceClose.dispatchedAt
        approve          = @{ httpStatus = $raceClose.A.Status; code = (Get-Code $raceClose.A); traceId = $raceClose.A.TraceId }
        close            = @{ httpStatus = $raceClose.B.Status; code = (Get-Code $raceClose.B); traceId = $raceClose.B.TraceId }
        winnerCount      = $raceCloseWinners.Count
        serverErrorCount = $raceCloseServerErrors.Count
        finalDbRow       = $rowRaceCloseFinal.raw
        why              = ('请求待批准期间不冻结工单：批准（进已取消）与关闭（进已关闭）都能提交，' +
        '由同一条 version 条件更新裁决唯一胜者；两条终态路径不能同时成立')
    }
    Add-Assertion -Name 'a11c.concurrent.exactlyOneWinner' -Condition ($raceCloseWinners.Count -eq 1) `
        -Expected '同一版本上「批准 vs 关闭」恰好一个请求成功（200）' `
        -Actual "approve=$($raceClose.A.Status) close=$($raceClose.B.Status)" `
        -Why '若都成功，工单会同时是"已取消"和"已关闭"，两条终态语义互相覆盖' `
        -Result $null
    Add-Assertion -Name 'a11c.concurrent.loserIsConflictNotServerError' `
        -Condition (($raceCloseLosers.Count -eq 1) -and ($raceCloseLosers[0].Status -eq 409) -and
        ((Get-Code $raceCloseLosers[0]) -eq 'TICKET_CONFLICT')) `
        -Expected '败者是 409 + TICKET_CONFLICT' `
        -Actual "败者 status=$($raceCloseLosers[0].Status) code=$(Get-Code $raceCloseLosers[0])" `
        -Why '关闭与批准都会写终态；败者必须是可重试的 409 而不是 500（片 D 修掉的交叉死锁在这里也必须不再出现）' `
        -Result $null
    Add-Assertion -Name 'a11c.concurrent.noServerError' -Condition ($raceCloseServerErrors.Count -eq 0) `
        -Expected '两路都没有 5xx' `
        -Actual "approve=$($raceClose.A.Status) close=$($raceClose.B.Status)" `
        -Why '两个动作都以工单行起手，加锁顺序一致（片 D 的统一加锁起点）' `
        -Result $null
    $raceCloseWinnerKind = ''
    if ($raceClose.A.Status -eq 200) { $raceCloseWinnerKind = 'APPROVE' }
    if ($raceClose.B.Status -eq 200) { $raceCloseWinnerKind = 'CLOSE' }
    $raceCloseDbOk = ($null -ne $rowRaceCloseFinal) -and ($rowRaceCloseFinal.version -eq ($verRaceClose + 1)) -and
    ($rowRaceCloseFinal.cancelRequestState -eq 'NO_REQUEST') -and ($rowRaceCloseFinal.endedAt -eq 'SET')
    if ($raceCloseWinnerKind -eq 'APPROVE') {
        $raceCloseDbOk = $raceCloseDbOk -and ($rowRaceCloseFinal.status -eq 'CANCELED') -and
        ($rowRaceCloseFinal.closeMethod -eq 'NULL') -and ($rowRaceCloseFinal.actionDeadlineAt -eq 'NULL')
    }
    if ($raceCloseWinnerKind -eq 'CLOSE') {
        $raceCloseDbOk = $raceCloseDbOk -and ($rowRaceCloseFinal.status -eq 'CLOSED') -and
        ($rowRaceCloseFinal.closeMethod -eq 'MANUAL') -and ($rowRaceCloseFinal.closeReason -eq 'INVALID')
    }
    Add-Assertion -Name 'a11c.concurrent.winnerDeterminesDbState' -Condition $raceCloseDbOk `
        -Expected '库中终态与赢家一致（批准赢 → CANCELED 且无关闭字段；关闭赢 → CLOSED + close_method/close_reason 齐全），请求三列都被清空' `
        -Actual "赢家=$raceCloseWinnerKind row=[$($rowRaceCloseFinal.raw)]" `
        -Why '无论哪条路径赢，终态都不允许残留待决请求——两条 UPDATE 都各自负责清空，谁赢都能过 ck_ticket_cancel_request_status' `
        -Result $null
    $raceCloseExpectedStatus = 'CLOSED'
    if ($raceCloseWinnerKind -eq 'APPROVE') { $raceCloseExpectedStatus = 'CANCELED' }
    Assert-Db -Name 'a11c.db.versionPlusOneAndRequestGone' `
        -Sql ("SELECT CONCAT(version,'|',IF(cancel_requested_at IS NULL,'NULL','SET'),'|',status,'|'," +
        "IF(ended_at IS NULL,'NULL','SET')) FROM ticket WHERE ticket_no = '$ticketRaceClose';") `
        -Expected "$($verRaceClose + 1)|NULL|$raceCloseExpectedStatus|SET（版本 +1、请求清空、终态有结束时间）" `
        -Why '「批准 vs 关闭」是同一条工单上的两条终态路径：版本 +1 与结束时间都成立，才能证明只有一条 UPDATE 生效' `
        -Check { param($c) $c[0] -eq ("$($verRaceClose + 1)|NULL|" + $raceCloseExpectedStatus + '|SET') }
    Add-Assertion -Name 'a11c.concurrent.loserCarriesWinnerSnapshot' `
        -Condition (($null -ne $rowRaceCloseFinal) -and ([int](Get-ErrData $raceCloseLosers[0]).version -eq $rowRaceCloseFinal.version) -and
        ((Get-ErrData $raceCloseLosers[0]).status -eq $rowRaceCloseFinal.status)) `
        -Expected '败者的 409 带赢家造成的当前快照' `
        -Actual "败者 data.version=$((Get-ErrData $raceCloseLosers[0]).version) data.status=$((Get-ErrData $raceCloseLosers[0]).status)；库中 version=$($rowRaceCloseFinal.version) status=$($rowRaceCloseFinal.status)" `
        -Why '两条终态路径的冲突响应同样要能自解释' `
        -Result $null
    Add-KeyAction -Order 15 -Actor 'it' -Action 'approve-cancel(并发)' -Method 'POST' `
        -Path (Get-ActionPath $ticketRaceClose 'approve-cancel') -TicketNo $ticketRaceClose -Result $raceClose.A `
        -StatusAfter $raceCloseWinnerKind -Step 'a11c.concurrent.approve'
    Add-KeyAction -Order 16 -Actor 'it(第二会话)' -Action 'close(并发)' -Method 'POST' `
        -Path (Get-ActionPath $ticketRaceClose 'close') -TicketNo $ticketRaceClose -Result $raceClose.B `
        -StatusAfter $raceCloseWinnerKind -Step 'a11c.concurrent.close'

    Write-Host ''
    Write-Host '验收断言全部执行完毕，开始清理与基线核对。'
} catch {
    Add-Assertion -Name 'run.unexpectedFailure' -Condition $false -Expected '脚本正常跑完' `
        -Actual ("异常：" + $_.Exception.Message) `
        -Why '脚本中途抛异常意味着后面的断言没有执行；证据里必须能一眼看出是哪一步中断的' `
        -Result $null
} finally {
    foreach ($client in $clients) {
        if ($null -ne $client) { $client.Dispose() }
    }
}

# ── 15. 清理之一：只删本次创建的 11 张载体工单及其记录/参与关系 ──────────────
$script:cleanupInfo['startedAt'] = (Get-Date).ToString('s')
$ownTickets = @(
    $ticketMain, $ticketReject, $ticketPending, $ticketRaceClose, $ticketIdentity,
    $ticketRaceDecide, $ticketRaceWithdraw, $ticketCloseClears, $ticketConfirmClears,
    $ticketOtherEmployee, $ticketConstraint
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

try {
    if ($ownTickets.Count -eq 0) {
        $script:cleanupInfo['note'] = '本次运行没有创建任何工单，无需清理工单；仍会尝试删除临时用户与临时角色。'
        Add-Assertion -Name 'a12.cleanup.nothingCreated' -Condition $true -Expected '无自建工单' `
            -Actual '脚本在建单前就失败，未写入任何工单数据' `
            -Why '建单前失败时清理段仍要正常收尾，不能因为"没有东西可删"而报错' -Result $null
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
        $script:cleanupInfo['note'] = ('只删除本次运行创建的 11 张工单及其记录/参与关系与关联；' +
        'ticket_attachment 在 v1 不会产生行，属防御性语句。')

        $cleanupResult = Invoke-MySql -Sql ($cleanupStatements -join "`n")
        $script:cleanupInfo['exitCode'] = $cleanupResult.exitCode
        $script:cleanupInfo['stderr'] = $cleanupResult.stderrText
        Add-Assertion -Name 'a12.cleanup.ticketDeletesSucceeded' -Condition ($cleanupResult.exitCode -eq 0) `
            -Expected '工单清理 SQL 退出码 0' `
            -Actual "exitCode=$($cleanupResult.exitCode) stderr=[$(Get-BriefText $cleanupResult.stderrText 120)]" `
            -Why '清理失败会把自建工单留在演示库里，直接破坏"运行前后指纹一致"这条承诺' -Result $null

        $leftRow = Get-MySqlRow -Sql "SELECT COUNT(*) FROM ticket WHERE ticket_no IN ($quoted);"
        Add-Assertion -Name 'a12.cleanup.noOwnTicketsLeft' `
            -Condition ($leftRow.ok -and ([int]$leftRow.columns[0] -eq 0)) `
            -Expected '自建工单已全部删除（按 ticket_no 精确命中）' `
            -Actual "row=[$($leftRow.raw)] tickets=[$($ownTickets -join ',')]" `
            -Why '只删自己创建的行；这里若不为 0，说明删除条件写错了' -Result $null
        $leftRecordRow = Get-MySqlRow -Sql "SELECT COUNT(*) FROM ticket_record WHERE ticket_id IN ($ticketList);"
        Add-Assertion -Name 'a12.cleanup.noOwnRecordsLeft' `
            -Condition ($leftRecordRow.ok -and ([int]$leftRecordRow.columns[0] -eq 0)) `
            -Expected '自建工单的记录已全部删除（含约束探针插入又删掉的那条）' `
            -Actual "row=[$($leftRecordRow.raw)]" `
            -Why '记录表是只增不改的时间线；残留会让 after 指纹与 before 不同' -Result $null
    }
} catch {
    Add-Assertion -Name 'a12.cleanup.ticketDeletesSucceeded' -Condition $false -Expected '清理阶段无异常' `
        -Actual ("清理异常：" + $_.Exception.Message) `
        -Why '清理段自身异常必须显式失败，否则会静默留下数据' -Result $null
}

# ── 16. 清理之二：2 个临时用户 + 2 个临时角色（按主键 ID 精确删除） ──────────
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
        $script:cleanupInfo['tempUserNote'] = '按主键 ID 精确删除，不按用户名/角色编码前缀批量删（片 D 的反例）'

        $tempCleanupResult = Invoke-MySql -Sql ($tempStatements -join "`n")
        $script:cleanupInfo['tempUserExitCode'] = $tempCleanupResult.exitCode
        Add-Assertion -Name 'a12.cleanup.tempPrincipalsDeleted' -Condition ($tempCleanupResult.exitCode -eq 0) `
            -Expected '临时用户与临时角色的删除 SQL 退出码 0' `
            -Actual "exitCode=$($tempCleanupResult.exitCode) userIds=[$tempUserIds] roleIds=[$tempRoleIds] stderr=[$(Get-BriefText $tempCleanupResult.stderrText 120)]" `
            -Why '临时主体带自定义角色与授权关系，删不干净会永久污染演示库的计数与指纹' -Result $null

        $leftTempRow = Get-MySqlRow -Sql (
            "SELECT (SELECT COUNT(*) FROM iam_user WHERE username IN ($tempUsernames)), " +
            "(SELECT COUNT(*) FROM iam_role WHERE id IN ($tempRoleIds)), " +
            "(SELECT COUNT(*) FROM iam_user_role WHERE user_id IN ($tempUserIds)), " +
            "(SELECT COUNT(*) FROM iam_role_permission WHERE role_id IN ($tempRoleIds));")
        $leftTempOk = $leftTempRow.ok -and (@($leftTempRow.columns) | Where-Object { [int]$_ -ne 0 }).Count -eq 0
        Add-Assertion -Name 'a12.cleanup.noTempPrincipalLeft' -Condition $leftTempOk `
            -Expected '临时用户、临时角色、它们的用户角色关系与角色权限关系都为 0 行' `
            -Actual "row=[$($leftTempRow.raw)]（四列依次是 用户 / 角色 / 用户角色 / 角色权限）" `
            -Why '四张表都要回到 0 行：只删用户不删角色会留下孤儿角色（片 D 的教训）' -Result $null
    } else {
        $script:cleanupInfo['tempUserNote'] = '本次运行没有创建临时主体（建临时角色前就失败），无需删除。'
        Add-Assertion -Name 'a12.cleanup.noTempPrincipalCreated' -Condition $true -Expected '无临时主体' `
            -Actual '脚本在创建临时角色/用户前就失败，未写入这些行' `
            -Why '同上：清理段必须在任何失败形态下都能正常收尾' -Result $null
    }
} catch {
    Add-Assertion -Name 'a12.cleanup.tempPrincipalsDeleted' -Condition $false -Expected '临时主体清理阶段无异常' `
        -Actual ("清理异常：" + $_.Exception.Message) `
        -Why '临时主体清理异常必须显式失败' -Result $null
}

# ── 17. 清理后基线核对：八项计数 + 逐行指纹与运行前完全一致 ──────────────────
try {
    $demoAfter = Get-DemoFingerprint
    $script:cleanupInfo['demoDatabaseBefore'] = $demoBefore
    $script:cleanupInfo['demoDatabaseAfter'] = $demoAfter

    $sameCounts = ($null -ne $demoBefore) -and ($demoBefore.tickets -eq $demoAfter.tickets) -and
    ($demoBefore.records -eq $demoAfter.records) -and ($demoBefore.participants -eq $demoAfter.participants) -and
    ($demoBefore.relations -eq $demoAfter.relations) -and ($demoBefore.users -eq $demoAfter.users) -and
    ($demoBefore.userRoles -eq $demoAfter.userRoles) -and ($demoBefore.roles -eq $demoAfter.roles) -and
    ($demoBefore.rolePermissions -eq $demoAfter.rolePermissions)
    Add-Assertion -Name 'a13.baseline.countsRestored' -Condition $sameCounts `
        -Expected '八项计数与运行前完全一致（ticket / ticket_record / ticket_participant / ticket_relation / iam_user / iam_user_role / iam_role / iam_role_permission）' `
        -Actual (("运行前 工单={0}/记录={1}/参与者={2}/关联={3}/用户={4}/用户角色={5}/角色={6}/角色权限={7}；" +
        "运行后 工单={8}/记录={9}/参与者={10}/关联={11}/用户={12}/用户角色={13}/角色={14}/角色权限={15}") -f `
            $demoBefore.tickets, $demoBefore.records, $demoBefore.participants, $demoBefore.relations, `
            $demoBefore.users, $demoBefore.userRoles, $demoBefore.roles, $demoBefore.rolePermissions, `
            $demoAfter.tickets, $demoAfter.records, $demoAfter.participants, $demoAfter.relations, `
            $demoAfter.users, $demoAfter.userRoles, $demoAfter.roles, $demoAfter.rolePermissions) `
        -Why '本脚本会写工单、记录、参与关系与临时授权；八项计数是"完全回到基线"的第一层证据' -Result $null

    Add-Assertion -Name 'a13.baseline.ticketNoListUntouched' `
        -Condition (($null -ne $demoBefore) -and ($demoBefore.ticketNos -eq $demoAfter.ticketNos)) `
        -Expected '运行前工单号列表与运行后逐字相同' `
        -Actual "运行前=[$($demoBefore.ticketNos)]；运行后=[$($demoAfter.ticketNos)]" `
        -Why '自建工单按 ticket_no 精确删除；列表不同就意味着要么没删干净、要么碰了别人的行' -Result $null

    Add-Assertion -Name 'a13.baseline.ticketFingerprintUntouched' `
        -Condition (($null -ne $demoBefore) -and ($demoBefore.ticketRows -eq $demoAfter.ticketRows)) `
        -Expected '既有工单的 status/version/record_seq/负责人/期限/结束时间/完成方式/关闭字段/请求三列/updated_at 逐行一致' `
        -Actual "运行前指纹长度=$($demoBefore.ticketRows.Length)；运行后指纹长度=$($demoAfter.ticketRows.Length)" `
        -Why '这一行指纹里包含 V7 的三列请求字段，因此能抓住"待决请求没清干净"这类污染' -Result $null

    Add-Assertion -Name 'a13.baseline.recordFingerprintUntouched' `
        -Condition (($null -ne $demoBefore) -and ($demoBefore.recordRows -eq $demoAfter.recordRows)) `
        -Expected '既有记录的 id/序号/类型/操作者/时间逐条一致（不可变时间线没有被改动）' `
        -Actual "运行前指纹长度=$($demoBefore.recordRows.Length)；运行后指纹长度=$($demoAfter.recordRows.Length)" `
        -Why '时间线是只增不改的；指纹相同证明本脚本没有改写任何历史记录' -Result $null

    Add-Assertion -Name 'a13.baseline.participantFingerprintUntouched' `
        -Condition (($null -ne $demoBefore) -and ($demoBefore.participantRows -eq $demoAfter.participantRows)) `
        -Expected '既有参与关系逐条一致' `
        -Actual "运行前=[$($demoBefore.participantRows)]；运行后=[$($demoAfter.participantRows)]" `
        -Why '领取会写参与关系；自建工单的那几行必须随工单一起删除' -Result $null

    Add-Assertion -Name 'a13.baseline.relationFingerprintUntouched' `
        -Condition (($null -ne $demoBefore) -and ($demoBefore.relationRows -eq $demoAfter.relationRows)) `
        -Expected '既有 ticket_relation 逐条一致' `
        -Actual "运行前=[$($demoBefore.relationRows)]；运行后=[$($demoAfter.relationRows)]" `
        -Why '片 E 不写关联，但仍要证明没有连带影响' -Result $null

    Add-Assertion -Name 'a13.baseline.userRowsUntouched' `
        -Condition (($null -ne $demoBefore) -and ($demoBefore.userRows -eq $demoAfter.userRows)) `
        -Expected '既有用户的 id/username/status 逐条一致（2 个临时用户已删除）' `
        -Actual "运行前=[$($demoBefore.userRows)]；运行后=[$($demoAfter.userRows)]" `
        -Why '临时用户必须整行消失；只停用不删除会留下垃圾账号' -Result $null

    Add-Assertion -Name 'a13.baseline.userRoleRowsUntouched' `
        -Condition (($null -ne $demoBefore) -and ($demoBefore.userRoleRows -eq $demoAfter.userRoleRows)) `
        -Expected '既有 iam_user_role 逐条一致（临时用户的角色关系已删除）' `
        -Actual "运行前=[$($demoBefore.userRoleRows)]；运行后=[$($demoAfter.userRoleRows)]" `
        -Why 'u5 额外持有内置 IT_SUPPORT 角色，两行都要删干净' -Result $null

    Add-Assertion -Name 'a13.baseline.roleRowsUntouched' `
        -Condition (($null -ne $demoBefore) -and ($demoBefore.roleRows -eq $demoAfter.roleRows)) `
        -Expected '既有角色逐条一致（2 个临时角色已删除，且不误删运行前就存在的任何角色）' `
        -Actual "运行前=[$($demoBefore.roleRows)]；运行后=[$($demoAfter.roleRows)]" `
        -Why '片 D 曾误删一个运行前就存在的孤儿角色；按主键删除才能保证这一格永远成立' -Result $null

    Add-Assertion -Name 'a13.baseline.rolePermissionRowsUntouched' `
        -Condition (($null -ne $demoBefore) -and ($demoBefore.rolePermissionRows -eq $demoAfter.rolePermissionRows)) `
        -Expected '既有 iam_role_permission 逐条一致（2 个临时角色的权限关系已删除）' `
        -Actual "运行前=[$($demoBefore.rolePermissionRows)]；运行后=[$($demoAfter.rolePermissionRows)]" `
        -Why '临时角色带自定义权限；授权行不清会留下悬空引用' -Result $null

    $script:cleanupInfo['dailySequenceBefore'] = $demoBefore.dailySequence
    $script:cleanupInfo['dailySequenceAfter'] = $demoAfter.dailySequence
    Add-Assertion -Name 'a13.dailySequenceIncrementedAsExpected' -Condition ($null -ne $demoAfter) `
        -Expected 'ticket_daily_sequence 递增属预期、不回退（接口建单必然递增，编号不复用）' `
        -Actual "运行前=[$($demoBefore.dailySequence)]；运行后=[$($demoAfter.dailySequence)]" `
        -Why '回退就意味着改动了运行前就存在的行，因此本脚本只记录不回退' -Result $null
} catch {
    Add-Assertion -Name 'a13.baseline.checkFailed' -Condition $false -Expected '清理后基线核对可完成' `
        -Actual ("核对异常：" + $_.Exception.Message) `
        -Why '基线核对失败等于无法证明演示库被还原' -Result $null
}

# ── 18. 安全边界声明（与断言一起进证据） ─────────────────────────────────────
$script:cleanupInfo['dockerCommandsUsed'] = @('docker exec -i -e MYSQL_PWD <mysql 容器> mysql -uroot -N -B --default-character-set=utf8mb4 <库名>（SQL 走 stdin）')
$script:cleanupInfo['composeDownExecuted'] = $false
$script:cleanupInfo['containersOrVolumesModified'] = $false
$script:cleanupInfo['backendProcessTouched'] = $false
$script:cleanupInfo['backendProcessesStartedOrStopped'] = 0
$script:cleanupInfo['targetBaseUrl'] = $baseUrl
$script:cleanupInfo['nonApiWrites'] = @(
    'UPDATE ticket SET cancel_request_deadline_at = DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 5 MINUTE) WHERE ticket_no = <本次自建工单>（a4，证明到期不自动处置）',
    '两条约束探针 UPDATE/INSERT（a9，预期被数据库拒绝；若意外成功则立即回滚并记账）',
    '约束探针成功插入的那条 CANCELLATION_REQUEST 记录按工单 + 类型删除（a9）'
)
$script:cleanupInfo['finishedAt'] = (Get-Date).ToString('s')
Add-Assertion -Name 'a14.safetyBoundary.declared' -Condition $true `
    -Expected '明确声明边界：不碰进程、不碰容器、只新增再删除自己的行' `
    -Actual ('未触碰调用方的 8081 后端（脚本只向目标基址发 HTTP，不启动/不重启/不结束任何进程）；' +
    '未执行 docker compose down；未修改容器与卷（只对既有 mysql 容器执行 docker exec 读写演示库）；' +
    '非接口写库语句仅限于自建工单的请求期限探针与两条预期被拒的约束探针（见 cleanup.nonApiWrites）') `
    -Why '验收脚本本身也要能自证没有越界；片 D 的教训是"证据的元数据同样要对齐库内既有规则"' `
    -Result $null

# ── 19. 汇总与证据（无 BOM 的 UTF-8） ────────────────────────────────────────
# 注意：List[object] 一律用 .ToArray()，不要用 @() 包（Windows PowerShell 5.1 会抛
# System.ArgumentException: Argument types do not match）；条件值先算成变量再进哈希表字面量。
$finishedAt = Get-Date
$failed = @($script:results | Where-Object { -not $_.passed })
$failedCount = $failed.Count
$totalCount = $script:results.Count
$passedCount = $totalCount - $failedCount
if ($failedCount -gt 0) { $script:quitCode = 1 }

$relativeScriptPath = 'scripts/slice-e-two-phase-cancel-acceptance.ps1'
$relativeOutPath = 'docs/acceptance/2026-10-08-slice-e-two-phase-cancel.json'
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
# 记录本次真实用到的基址：脚本默认基址是 8081，片 E 的四个端点在不含 V7 的旧构建上不存在，
# 若 invocation 不带 -BaseUrl，照抄执行会打到没有该接口的后端并把连接失败误判成断言失败
# （片 D 交接前实际踩过一次：18 项断言、首请求连接失败、证据被覆盖成废记录）。
$invocation = 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\slice-e-two-phase-cancel-acceptance.ps1 -BaseUrl ' + $baseUrl
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
        accounts              = @($EmployeeUser, $ItUser)
        tempPrincipals        = @('u1=只持员工三条权限码', 'u5=内置 IT_SUPPORT + TICKET_REQUESTER_ACTION')
        endpointsUnderTest    = @(
            'POST /fd/v1/tickets/{ticketNo}/actions/request-cancel',
            'POST /fd/v1/tickets/{ticketNo}/actions/approve-cancel',
            'POST /fd/v1/tickets/{ticketNo}/actions/reject-cancel',
            'POST /fd/v1/tickets/{ticketNo}/actions/withdraw-cancel-request',
            'POST /fd/v1/tickets/{ticketNo}/actions/cancel（收窄：只剩待受理）',
            'GET  /fd/v1/tickets/{ticketNo}（cancelRequest 字段）',
            'GET  /fd/v1/tickets/{ticketNo}/records（四个新记录类型的 context）'
        )
        migrationUnderTest     = 'V7__add_cancel_request.sql（ticket 三列 + ck_ticket_cancel_request_pair / ck_ticket_cancel_request_status + ck_ticket_record_type 扩 4 个类型）'
        credentialsPrinted    = $false
        credentialsInEvidence = $false
    }
    $totalsEntry = [ordered]@{
        total  = $totalCount
        passed = $passedCount
        failed = $failedCount
    }
    $fingerprintsEntry = [ordered]@{
        before = $demoBefore
        after  = $demoAfter
    }
    $evidence = [ordered]@{
        _note = ('本文件由 scripts/slice-e-two-phase-cancel-acceptance.ps1 写出（无 BOM 的 UTF-8）。' +
        '验收范围：完整工单状态机「片 E：两阶段撤销」——request-cancel / approve-cancel / reject-cancel / ' +
        'withdraw-cancel-request 四个端点、cancel 收窄到待受理、详情 cancelRequest 字段、四个新记录类型的时间线 context、' +
        'V7 的两条新 CHECK 与重建后的记录类型白名单、三组真并发、权限/身份/版本/幂等与"到期不自动处置"。' +
        '只记录状态码、业务码、traceId、断言结论与 SQL；不含口令、密钥与 accessToken，也不含机器绝对路径。')
        stage              = '完整工单状态机 片 E：两阶段撤销真实栈验收'
        slice              = 'E'
        date               = $startedAt.ToString('yyyy-MM-dd')
        result             = $resultValue
        invocation         = $invocation
        script             = $scriptEntry
        target             = $targetEntry
        preflight          = $script:preflightInfo
        tempPrincipals     = $script:tempPrincipalInfo
        cancelRequestFacts = $script:cancelRequestInfo
        deadlineProbe      = $script:deadlineProbeInfo
        constraintProbes   = $script:constraintProbeInfo
        terminalStates     = $script:terminalInfo
        tickets            = $script:ticketsInfo
        concurrency        = [ordered]@{
            approveVsReject  = $script:concurrencyDecideInfo
            approveVsWithdraw = $script:concurrencyWithdrawInfo
            approveVsClose   = $script:concurrencyCloseInfo
        }
        fingerprints       = $fingerprintsEntry
        keyActions         = $keyActionList
        dbChecks           = $dbCheckList
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
        stage      = '完整工单状态机 片 E：两阶段撤销真实栈验收'
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
Write-Host ("阶段：片 E 两阶段撤销；目标后端：{0}" -f $baseUrl)
Write-Host ("脚本：{0}（{1} 行）；断言 {2} 项，通过 {3}，失败 {4}" -f `
        $relativeScriptPath, $scriptLineCount, $totalCount, $passedCount, $failedCount)
Write-Host '关键动作（状态码 / X-Trace-Id）：'
foreach ($step in $script:keyActions) {
    Write-Host ("  {0}. {1,-22} {2,-30} {3} => {4} traceId={5}" -f `
            $step.order, $step.actor, $step.action, $step.ticketNo, $step.httpStatus, $step.traceId)
}
Write-Host '数据库直查：'
foreach ($check in $script:dbChecks) {
    $mark = 'FAIL'
    if ($check.passed) { $mark = 'PASS' }
    Write-Host ("  [{0}] {1} :: {2}" -f $mark, $check.name, $check.raw)
}
if ($null -ne $demoBefore) {
    Write-Host ("演示库计数：运行前 工单={0}/记录={1}/参与者={2}/关联={3}/用户={4}/用户角色={5}/角色={6}/角色权限={7} 工单号=[{8}]" -f `
            $demoBefore.tickets, $demoBefore.records, $demoBefore.participants, $demoBefore.relations, `
            $demoBefore.users, $demoBefore.userRoles, $demoBefore.roles, $demoBefore.rolePermissions, $demoBefore.ticketNos)
}
if ($null -ne $demoAfter) {
    Write-Host ("            运行后 工单={0}/记录={1}/参与者={2}/关联={3}/用户={4}/用户角色={5}/角色={6}/角色权限={7} 工单号=[{8}]" -f `
            $demoAfter.tickets, $demoAfter.records, $demoAfter.participants, $demoAfter.relations, `
            $demoAfter.users, $demoAfter.userRoles, $demoAfter.roles, $demoAfter.rolePermissions, $demoAfter.ticketNos)
}
if ($script:concurrencyDecideInfo.Count -gt 0) {
    Write-Host ("并发用例 1（批准 vs 拒绝）：ticket={0} 共享版本={1} → approve={2} reject={3}（赢家数={4}，5xx={5}）" -f `
            $script:concurrencyDecideInfo['ticketNo'], $script:concurrencyDecideInfo['sharedVersion'], `
            $script:concurrencyDecideInfo['approve'].httpStatus, $script:concurrencyDecideInfo['reject'].httpStatus, `
            $script:concurrencyDecideInfo['winnerCount'], $script:concurrencyDecideInfo['serverErrorCount'])
}
if ($script:concurrencyWithdrawInfo.Count -gt 0) {
    Write-Host ("并发用例 2（批准 vs 撤回）：ticket={0} 共享版本={1} → approve={2} withdraw={3}（赢家数={4}，5xx={5}）" -f `
            $script:concurrencyWithdrawInfo['ticketNo'], $script:concurrencyWithdrawInfo['sharedVersion'], `
            $script:concurrencyWithdrawInfo['approve'].httpStatus, $script:concurrencyWithdrawInfo['withdraw'].httpStatus, `
            $script:concurrencyWithdrawInfo['winnerCount'], $script:concurrencyWithdrawInfo['serverErrorCount'])
}
if ($script:concurrencyCloseInfo.Count -gt 0) {
    Write-Host ("并发用例 3（批准 vs 关闭）：ticket={0} 共享版本={1} → approve={2} close={3}（赢家数={4}，5xx={5}）" -f `
            $script:concurrencyCloseInfo['ticketNo'], $script:concurrencyCloseInfo['sharedVersion'], `
            $script:concurrencyCloseInfo['approve'].httpStatus, $script:concurrencyCloseInfo['close'].httpStatus, `
            $script:concurrencyCloseInfo['winnerCount'], $script:concurrencyCloseInfo['serverErrorCount'])
}
Write-Host ("证据：{0}（无 BOM UTF-8={1}，可回读解析={2}，{3} 字节）" -f `
        $relativeOutPath, (-not $hasBom), ($null -ne $parsedBack), $writtenBytes.Length)
Write-Host ("证据字段：invocation={0}；fingerprints.before/after 已写入；httpLog {1} 条；dbChecks {2} 条" -f `
        $invocation, $httpLogList.Count, $dbCheckList.Count)
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
    foreach ($item in $failed) {
        Write-Host ("  - {0}" -f $item.name) -ForegroundColor Red
        Write-Host ("      期望：{0}" -f $item.expected) -ForegroundColor Red
        Write-Host ("      实际：{0}" -f $item.actual) -ForegroundColor Red
        Write-Host ("      为什么重要：{0}" -f $item.why) -ForegroundColor Red
    }
}
Write-Host ("退出码：{0}" -f $script:quitCode)
exit $script:quitCode
