# 阶段 3 步骤①②③ 真实栈验收：领取 / 追加处理记录 / 提交解决结果 / 员工确认
#
# 前置：docker compose up -d mysql redis
#       .\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local   （保持运行）
# 运行：powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stage3-ticket-actions-acceptance.ps1
#
# 本文件必须保存为带 BOM 的 UTF-8：Windows PowerShell 5.1 会按系统代码页读取无 BOM 的
# UTF-8 脚本，中文注释会被解析成乱码并报语法错误。
#
# 说明：连接信息由脚本自己从仓库根 .env 加载，不依赖调用方先执行 load-env.ps1。
#       只新增数据，不修改历史数据；结尾打印清理 SQL，需自行执行以回到演示基线。
#       admin 在演示库中可能被额外授予 EMPLOYEE/IT_SUPPORT 角色（环境漂移），
#       因此脚本不对 admin 的动作结果做绝对值断言，只记录实际状态与错误码。

param(
    [string]$BaseUrl = 'http://127.0.0.1:8081',
    [string]$OutDir = (Join-Path $PSScriptRoot '..\docs\acceptance'),
    # 数据库直查断言需要演示库连接信息；默认从仓库根的 .env 读取。
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'),
    [string]$DbContainer = 'flowdesk-mysql-1'
)

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Net.Http

# .env 由本进程自己加载：调用方可能没先执行 load-env.ps1，而数据库直查断言依赖它。
if (Test-Path -LiteralPath $EnvFile) {
    foreach ($line in Get-Content -LiteralPath $EnvFile) {
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

$script:results = [System.Collections.Generic.List[object]]::new()
$script:observed = [System.Collections.Generic.List[string]]::new()

function New-Client {
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.UseCookies = $false
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.BaseAddress = [Uri]$BaseUrl
    $client.Timeout = [TimeSpan]::FromSeconds(60)
    return $client
}

function Get-TraceId($response) {
    $values = $null
    if ($response.Headers.TryGetValues('X-Trace-Id', [ref]$values)) {
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
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::$Method, $Path)
    if ($Token) {
        $request.Headers.Authorization =
            [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
    }
    if ($null -ne $Body) {
        $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 6 -Compress }
        $request.Content = [System.Net.Http.StringContent]::new(
            $json, [System.Text.Encoding]::UTF8, 'application/json')
    }
    try {
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        return @{ Status = [int]$response.StatusCode; Body = $text; TraceId = (Get-TraceId $response) }
    } finally {
        $request.Dispose()
    }
}

function Send-CreateTicket {
    param([System.Net.Http.HttpClient]$Client, [string]$Token, [string]$Title)
    $content = [System.Net.Http.MultipartFormDataContent]::new()
    $content.Add([System.Net.Http.StringContent]::new(
            (@{
                    submissionKey = [guid]::NewGuid().ToString()
                    title         = $Title
                    description   = '阶段 3 真实栈验收：claim 与 add-processing-record。'
                    categoryId    = $script:categoryId
                    priority      = 'MEDIUM'
                } | ConvertTo-Json -Compress),
            [System.Text.Encoding]::UTF8, 'application/json'), 'ticket')
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Post, '/fd/v1/tickets')
    $request.Headers.Authorization =
        [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
    $request.Content = $content
    try {
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        return @{ Status = [int]$response.StatusCode; Body = $text; TraceId = (Get-TraceId $response) }
    } finally {
        $request.Dispose()
    }
}

function Assert-That {
    param([string]$Name, [bool]$Condition, [string]$Detail)
    $script:results.Add([pscustomobject]@{ name = $Name; passed = $Condition; detail = $Detail })
    $mark = if ($Condition) { 'PASS' } else { 'FAIL' }
    Write-Host ("[{0}] {1} :: {2}" -f $mark, $Name, $Detail)
}

function Get-Data($result) {
    return ($result.Body | ConvertFrom-Json).data
}

function Get-Code($result) {
    if ($result.Status -ge 200 -and $result.Status -lt 300) { return $null }
    try { return ($result.Body | ConvertFrom-Json).code } catch { return $null }
}

# ── 1. 登录（每用户独立客户端、禁用 Cookie，避免会话串号） ────────────────
$employeeClient = New-Client
$itClient = New-Client
$adminClient = New-Client

$loginEmployee = Send-Req -Client $employeeClient -Method Post -Path '/fd/v1/auth/login' `
    -Body @{ username = 'employee'; password = '123456' }
Assert-That 'login.employee' ($loginEmployee.Status -eq 200) "status=$($loginEmployee.Status)"
$employeeToken = (Get-Data $loginEmployee).accessToken

$loginIt = Send-Req -Client $itClient -Method Post -Path '/fd/v1/auth/login' `
    -Body @{ username = 'it'; password = '123456' }
Assert-That 'login.it' ($loginIt.Status -eq 200) "status=$($loginIt.Status)"
$itToken = (Get-Data $loginIt).accessToken

$loginAdmin = Send-Req -Client $adminClient -Method Post -Path '/fd/v1/auth/login' `
    -Body @{ username = 'admin'; password = '123456' }
Assert-That 'login.admin' ($loginAdmin.Status -eq 200) "status=$($loginAdmin.Status)"
$adminToken = (Get-Data $loginAdmin).accessToken

# ── 2. 身份自检 ──────────────────────────────────────────────────────────
$meIt = Send-Req -Client $itClient -Method Get -Path '/fd/v1/auth/me' -Token $itToken
$meItData = Get-Data $meIt
$itPerms = @($meItData.permissions)
Assert-That 'it.isIT_SUPPORT' (@($meItData.roles) -contains 'IT_SUPPORT') `
    "roles=$(@($meItData.roles) -join '|')"
Assert-That 'it.hasClaimPermission' ($itPerms -contains 'TICKET_CLAIM') `
    "ticketPerms=$((@($itPerms | Where-Object { $_ -like 'TICKET*' })) -join '|')"
Assert-That 'it.hasProcessPermission' ($itPerms -contains 'TICKET_PROCESS') `
    "hasProcess=$($itPerms -contains 'TICKET_PROCESS')"

$meAdmin = Send-Req -Client $adminClient -Method Get -Path '/fd/v1/auth/me' -Token $adminToken
$adminPerms = @((Get-Data $meAdmin).permissions)
$script:observed.Add("admin.roles=$(@((Get-Data $meAdmin).roles) -join '|')")
$script:observed.Add("admin.hasTicketClaim=$($adminPerms -contains 'TICKET_CLAIM')")

# ── 3. 分类选项 ──────────────────────────────────────────────────────────
$options = Send-Req -Client $employeeClient -Method Get -Path '/fd/v1/categories/options' -Token $employeeToken
Assert-That 'categories.options' ($options.Status -eq 200) "status=$($options.Status)"
$optionsData = @(Get-Data $options)
Assert-That 'categories.options.nonEmpty' ($optionsData.Count -ge 1) "count=$($optionsData.Count)"
$script:categoryId = $optionsData[0].id

# ── 4. 创建两张工单：A 走完整链路，B 作为 admin 对照 ─────────────────────
$createA = Send-CreateTicket -Client $employeeClient -Token $employeeToken -Title '阶段3验收-领取与处理记录'
Assert-That 'ticket.A.create' ($createA.Status -eq 201) "status=$($createA.Status) traceId=$($createA.TraceId)"
$ticketA = (Get-Data $createA).ticketNo
$versionA = (Get-Data $createA).version
Assert-That 'ticket.A.number' ($ticketA -match '^FD-\d{8}-\d{3}$') "ticketNo=$ticketA version=$versionA"

$createB = Send-CreateTicket -Client $employeeClient -Token $employeeToken -Title '阶段3验收-领取负例对照'
Assert-That 'ticket.B.create' ($createB.Status -eq 201) "status=$($createB.Status) traceId=$($createB.TraceId)"
$ticketB = (Get-Data $createB).ticketNo
$versionB = (Get-Data $createB).version
Write-Host "ticketA=$ticketA ticketB=$ticketB"

# ── 5. 领取负例 ──────────────────────────────────────────────────────────
$anonClient = New-Client
$anon = Send-Req -Client $anonClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/claim" -Body @{ version = $versionA }
Assert-That 'claim.anonymous.401' ($anon.Status -eq 401) "status=$($anon.Status) traceId=$($anon.TraceId)"

$empClaim = Send-Req -Client $employeeClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/claim" -Token $employeeToken -Body @{ version = $versionA }
Assert-That 'claim.employee.403' (($empClaim.Status -eq 403) -and ((Get-Code $empClaim) -eq 'TICKET_ACTION_FORBIDDEN')) `
    "status=$($empClaim.Status) code=$(Get-Code $empClaim) traceId=$($empClaim.TraceId)"

$missing = Send-Req -Client $itClient -Method Post `
    -Path '/fd/v1/tickets/FD-19990101-001/actions/claim' -Token $itToken -Body @{ version = 0 }
Assert-That 'claim.unknownTicket.404' (($missing.Status -eq 404) -and ((Get-Code $missing) -eq 'TICKET_NOT_FOUND')) `
    "status=$($missing.Status) code=$(Get-Code $missing) traceId=$($missing.TraceId)"

$stale = Send-Req -Client $itClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/claim" -Token $itToken -Body @{ version = ($versionA + 9) }
Assert-That 'claim.staleVersion.409' (($stale.Status -eq 409) -and ((Get-Code $stale) -eq 'TICKET_CONFLICT')) `
    "status=$($stale.Status) code=$(Get-Code $stale) traceId=$($stale.TraceId)"

$nullVersion = Send-Req -Client $itClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/claim" -Token $itToken -Body '{}'
Assert-That 'claim.missingVersion.400' ($nullVersion.Status -eq 400) `
    "status=$($nullVersion.Status) traceId=$($nullVersion.TraceId)"

# ── 6. admin 对照观察（演示库已漂移，不做绝对值断言） ────────────────────
$adminClaimB = Send-Req -Client $adminClient -Method Post `
    -Path "/fd/v1/tickets/$ticketB/actions/claim" -Token $adminToken -Body @{ version = $versionB }
$script:observed.Add("admin.claim.otherTicket.status=$($adminClaimB.Status) code=$(Get-Code $adminClaimB)")
Write-Host "[OBSERVE] admin claim $ticketB => status=$($adminClaimB.Status) code=$(Get-Code $adminClaimB) traceId=$($adminClaimB.TraceId)"

# ── 7. 领取正例 ──────────────────────────────────────────────────────────
$claim = Send-Req -Client $itClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/claim" -Token $itToken -Body @{ version = $versionA }
Assert-That 'claim.success.200' ($claim.Status -eq 200) "status=$($claim.Status) traceId=$($claim.TraceId)"
$claimData = Get-Data $claim
Assert-That 'claim.status.PROCESSING' ($claimData.status -eq 'PROCESSING') "status=$($claimData.status)"
Assert-That 'claim.version.incremented' ($claimData.version -eq ($versionA + 1)) `
    "expected=$($versionA + 1) actual=$($claimData.version)"
Assert-That 'claim.assignee.isIt' ($claimData.assignee.id -eq $meItData.id) `
    "assigneeId=$($claimData.assignee.id) itId=$($meItData.id)"

$reClaim = Send-Req -Client $itClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/claim" -Token $itToken -Body @{ version = $versionA }
Assert-That 'claim.repeat.409' (($reClaim.Status -eq 409) -and ((Get-Code $reClaim) -eq 'TICKET_CONFLICT')) `
    "status=$($reClaim.Status) code=$(Get-Code $reClaim) traceId=$($reClaim.TraceId)"
$reClaimData = Get-Data $reClaim
Assert-That 'claim.repeat.conflictSnapshot' ($reClaimData.status -eq 'PROCESSING') `
    "conflictStatus=$($reClaimData.status) conflictVersion=$($reClaimData.version)"

# ── 8. 详情与 allowedActions ─────────────────────────────────────────────
$detail = Send-Req -Client $itClient -Method Get -Path "/fd/v1/tickets/$ticketA" -Token $itToken
Assert-That 'detail.it.200' ($detail.Status -eq 200) "status=$($detail.Status) traceId=$($detail.TraceId)"
$detailData = Get-Data $detail
$actions = @($detailData.allowedActions)
Assert-That 'detail.allowedActions.hasProcess' ($actions -contains 'add-processing-record') `
    "allowedActions=$($actions -join ',')"
Assert-That 'detail.allowedActions.noClaim' (-not ($actions -contains 'claim')) `
    "allowedActions=$($actions -join ',')"
$currentVersion = $detailData.version

# ── 9. 追加处理记录：负例与正例 ──────────────────────────────────────────
$empProcess = Send-Req -Client $employeeClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/add-processing-record" -Token $employeeToken `
    -Body @{ version = $currentVersion; content = '员工越权尝试' }
Assert-That 'process.employee.notAllowed' (($empProcess.Status -eq 403) -or ($empProcess.Status -eq 404)) `
    "status=$($empProcess.Status) code=$(Get-Code $empProcess) traceId=$($empProcess.TraceId)"

$blank = Send-Req -Client $itClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/add-processing-record" -Token $itToken `
    -Body @{ version = $currentVersion; content = '   ' }
Assert-That 'process.blankContent.400' ($blank.Status -eq 400) `
    "status=$($blank.Status) code=$(Get-Code $blank) traceId=$($blank.TraceId)"

$staleProcess = Send-Req -Client $itClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/add-processing-record" -Token $itToken `
    -Body @{ version = ($currentVersion + 9); content = '过期版本' }
Assert-That 'process.staleVersion.409' (($staleProcess.Status -eq 409) -and ((Get-Code $staleProcess) -eq 'TICKET_CONFLICT')) `
    "status=$($staleProcess.Status) code=$(Get-Code $staleProcess) traceId=$($staleProcess.TraceId)"

$process = Send-Req -Client $itClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/add-processing-record" -Token $itToken `
    -Body @{ version = $currentVersion; content = '  已联系机房，等待重启窗口  ' }
Assert-That 'process.success.200' ($process.Status -eq 200) "status=$($process.Status) traceId=$($process.TraceId)"
$processData = Get-Data $process
Assert-That 'process.status.unchanged' ($processData.status -eq 'PROCESSING') "status=$($processData.status)"
Assert-That 'process.version.incremented' ($processData.version -eq ($currentVersion + 1)) `
    "expected=$($currentVersion + 1) actual=$($processData.version)"
Assert-That 'process.assignee.unchanged' ($processData.assignee.id -eq $meItData.id) `
    "assigneeId=$($processData.assignee.id)"

$repeatProcess = Send-Req -Client $itClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/add-processing-record" -Token $itToken `
    -Body @{ version = $currentVersion; content = '同版本重复提交' }
Assert-That 'process.repeat.409' (($repeatProcess.Status -eq 409) -and ((Get-Code $repeatProcess) -eq 'TICKET_CONFLICT')) `
    "status=$($repeatProcess.Status) code=$(Get-Code $repeatProcess) traceId=$($repeatProcess.TraceId)"

# ── 10. 内容长度边界 ─────────────────────────────────────────────────────
$afterRepeat = Send-Req -Client $itClient -Method Get -Path "/fd/v1/tickets/$ticketA" -Token $itToken
$boundaryVersion = (Get-Data $afterRepeat).version
$maxLen = Send-Req -Client $itClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/add-processing-record" -Token $itToken `
    -Body @{ version = $boundaryVersion; content = ('测' * 10000) }
Assert-That 'process.maxLength.200' ($maxLen.Status -eq 200) `
    "status=$($maxLen.Status) traceId=$($maxLen.TraceId)"

$afterMax = Send-Req -Client $itClient -Method Get -Path "/fd/v1/tickets/$ticketA" -Token $itToken
$tooLongVersion = (Get-Data $afterMax).version
$tooLong = Send-Req -Client $itClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/add-processing-record" -Token $itToken `
    -Body @{ version = $tooLongVersion; content = ('测' * 10001) }
Assert-That 'process.tooLong.400' ($tooLong.Status -eq 400) `
    "status=$($tooLong.Status) code=$(Get-Code $tooLong) traceId=$($tooLong.TraceId)"

# ── 11. 并发：同一版本两个请求只能成功一个 ───────────────────────────────
$before = Send-Req -Client $itClient -Method Get -Path "/fd/v1/tickets/$ticketA" -Token $itToken
$raceVersion = (Get-Data $before).version
$raceBody = @{ version = $raceVersion; content = '并发写入检查' } | ConvertTo-Json -Compress

$jobs = 1..2 | ForEach-Object {
    Start-Job -ScriptBlock {
        param($BaseUrl, $TicketNo, $Token, $Body)
        Add-Type -AssemblyName System.Net.Http
        $client = [System.Net.Http.HttpClient]::new()
        $client.BaseAddress = [Uri]$BaseUrl
        $request = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::Post,
            "/fd/v1/tickets/$TicketNo/actions/add-processing-record")
        $request.Headers.Authorization =
            [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
        $request.Content = [System.Net.Http.StringContent]::new(
            $Body, [System.Text.Encoding]::UTF8, 'application/json')
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        [int]$response.StatusCode
    } -ArgumentList $BaseUrl, $ticketA, $itToken, $raceBody
}
$raceStatuses = @($jobs | Wait-Job | Receive-Job)
$jobs | Remove-Job
$raceOk = @($raceStatuses | Where-Object { $_ -eq 200 }).Count
$raceConflict = @($raceStatuses | Where-Object { $_ -eq 409 }).Count
Assert-That 'process.concurrent.oneWinner' (($raceOk -eq 1) -and ($raceConflict -eq 1)) `
    "statuses=$($raceStatuses -join ',')"

# ── 12. 步骤③ 提交解决结果 ──────────────────────────────────────────────
$afterRace = Send-Req -Client $itClient -Method Get -Path "/fd/v1/tickets/$ticketA" -Token $itToken
$resolutionVersion = (Get-Data $afterRace).version

$empResolution = Send-Req -Client $employeeClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/submit-resolution" -Token $employeeToken `
    -Body @{ version = $resolutionVersion; content = '员工越权提交解决结果' }
Assert-That 'resolution.employee.notAllowed' (($empResolution.Status -eq 403) -or ($empResolution.Status -eq 404)) `
    "status=$($empResolution.Status) code=$(Get-Code $empResolution) traceId=$($empResolution.TraceId)"

$blankResolution = Send-Req -Client $itClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/submit-resolution" -Token $itToken `
    -Body @{ version = $resolutionVersion; content = '   ' }
Assert-That 'resolution.blankContent.400' ($blankResolution.Status -eq 400) `
    "status=$($blankResolution.Status) code=$(Get-Code $blankResolution) traceId=$($blankResolution.TraceId)"

$staleResolution = Send-Req -Client $itClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/submit-resolution" -Token $itToken `
    -Body @{ version = ($resolutionVersion + 9); content = '过期版本提交' }
Assert-That 'resolution.staleVersion.409' `
    (($staleResolution.Status -eq 409) -and ((Get-Code $staleResolution) -eq 'TICKET_CONFLICT')) `
    "status=$($staleResolution.Status) code=$(Get-Code $staleResolution) traceId=$($staleResolution.TraceId)"

$resolution = Send-Req -Client $itClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/submit-resolution" -Token $itToken `
    -Body @{ version = $resolutionVersion; content = '  已重置密码，请用新密码登录后确认  ' }
Assert-That 'resolution.success.200' ($resolution.Status -eq 200) `
    "status=$($resolution.Status) traceId=$($resolution.TraceId)"
$resolutionData = Get-Data $resolution
Assert-That 'resolution.status.waitingConfirmation' ($resolutionData.status -eq 'WAITING_FOR_CONFIRMATION') `
    "status=$($resolutionData.status)"
Assert-That 'resolution.version.incremented' ($resolutionData.version -eq ($resolutionVersion + 1)) `
    "expected=$($resolutionVersion + 1) actual=$($resolutionData.version)"
Assert-That 'resolution.deadline.present' ($null -ne $resolutionData.actionDeadlineAt) `
    "actionDeadlineAt=$($resolutionData.actionDeadlineAt)"

$deadlineOffsetDays = $null
if ($resolutionData.actionDeadlineAt -and $resolutionData.actionTime) {
    $deadlineOffsetDays = ([datetime]$resolutionData.actionDeadlineAt - [datetime]$resolutionData.actionTime).TotalDays
}
Assert-That 'resolution.deadline.is7Days' `
    (($null -ne $deadlineOffsetDays) -and ($deadlineOffsetDays -gt 6.99) -and ($deadlineOffsetDays -lt 7.01)) `
    "offsetDays=$deadlineOffsetDays"

$repeatResolution = Send-Req -Client $itClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/submit-resolution" -Token $itToken `
    -Body @{ version = $resolutionVersion; content = '同版本重复提交' }
Assert-That 'resolution.repeat.409' `
    (($repeatResolution.Status -eq 409) -and ((Get-Code $repeatResolution) -eq 'TICKET_CONFLICT')) `
    "status=$($repeatResolution.Status) code=$(Get-Code $repeatResolution) traceId=$($repeatResolution.TraceId)"

# 待确认已不是 PROCESSING：条件更新应拦住处理记录，证明状态守卫有效
$processAfterResolution = Send-Req -Client $itClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/add-processing-record" -Token $itToken `
    -Body @{ version = $resolutionData.version; content = '待确认状态不应允许追加处理记录' }
Assert-That 'process.afterResolution.409' `
    (($processAfterResolution.Status -eq 409) -and ((Get-Code $processAfterResolution) -eq 'TICKET_CONFLICT')) `
    "status=$($processAfterResolution.Status) code=$(Get-Code $processAfterResolution) traceId=$($processAfterResolution.TraceId)"

# ── 13. 步骤③ 员工确认 ──────────────────────────────────────────────────
$waitingDetail = Send-Req -Client $employeeClient -Method Get -Path "/fd/v1/tickets/$ticketA" -Token $employeeToken
Assert-That 'detail.requester.200' ($waitingDetail.Status -eq 200) "status=$($waitingDetail.Status)"
$waitingData = Get-Data $waitingDetail
$waitingActions = @($waitingData.allowedActions)
Assert-That 'detail.allowedActions.hasConfirm' ($waitingActions -contains 'confirm-resolution') `
    "allowedActions=$($waitingActions -join ',')"
Assert-That 'detail.requester.deadlineVisible' ($null -ne $waitingData.actionDeadlineAt) `
    "actionDeadlineAt=$($waitingData.actionDeadlineAt)"
$confirmVersion = $waitingData.version

# 当前负责人确认：他只有 IT_SUPPORT，没有 TICKET_REQUESTER_ACTION，服务端先按权限拒绝
# （不是按提交人关系拒绝）。这一条断言的是权限闸门，不是身份闸门。
$itConfirm = Send-Req -Client $itClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/confirm-resolution" -Token $itToken `
    -Body @{ version = $confirmVersion }
Assert-That 'confirm.assignee.403' `
    (($itConfirm.Status -eq 403) -and ((Get-Code $itConfirm) -eq 'TICKET_ACTION_FORBIDDEN')) `
    "status=$($itConfirm.Status) code=$(Get-Code $itConfirm) traceId=$($itConfirm.TraceId)"

# 非提交人身份闸门：需要一个"有提交人动作权限但不是提交人"的账号。演示库把 admin 漂移成
# EMPLOYEE + IT_SUPPORT + SYSTEM_ADMIN，因此它具备该权限。它在待确认状态对这张工单不可见
# （可见性只放行提交人 / 待受理队列 / 参与者），所以失败码由权限与可见性共同决定：
# 403（无权限）、404（不可见）或 409（可见但不是提交人）。这里不断言具体码，而是断言
# "非提交人无法让工单完成"——这才是身份闸门真正要保证的事。
$adminPerms = @((Get-Data (Send-Req -Client $adminClient -Method Get -Path '/fd/v1/auth/me' -Token $adminToken)).permissions)
if ($adminPerms -contains 'TICKET_REQUESTER_ACTION') {
    $adminConfirm = Send-Req -Client $adminClient -Method Post `
        -Path "/fd/v1/tickets/$ticketA/actions/confirm-resolution" -Token $adminToken `
        -Body @{ version = $confirmVersion }
    $script:observed.Add("confirm.nonRequester.status=$($adminConfirm.Status) code=$(Get-Code $adminConfirm)")
    Assert-That 'confirm.nonRequester.cannotComplete' `
        (($adminConfirm.Status -eq 403) -or ($adminConfirm.Status -eq 404) -or ($adminConfirm.Status -eq 409)) `
        "status=$($adminConfirm.Status) code=$(Get-Code $adminConfirm) traceId=$($adminConfirm.TraceId)"

    # 决定性证据：非提交人尝试之后，工单仍未完成
    $stillWaiting = Get-Data (Send-Req -Client $employeeClient -Method Get -Path "/fd/v1/tickets/$ticketA" -Token $employeeToken)
    Assert-That 'confirm.nonRequester.stateUnchanged' ($stillWaiting.status -eq 'WAITING_FOR_CONFIRMATION') `
        "status=$($stillWaiting.status) version=$($stillWaiting.version)"
} else {
    $script:observed.Add('confirm.nonRequester=SKIPPED（admin 缺少 TICKET_REQUESTER_ACTION）')
    Write-Host '[OBSERVE] admin 缺少 TICKET_REQUESTER_ACTION，跳过非提交人身份闸门断言'
}

$staleConfirm = Send-Req -Client $employeeClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/confirm-resolution" -Token $employeeToken `
    -Body @{ version = ($confirmVersion + 9) }
Assert-That 'confirm.staleVersion.409' `
    (($staleConfirm.Status -eq 409) -and ((Get-Code $staleConfirm) -eq 'TICKET_CONFLICT')) `
    "status=$($staleConfirm.Status) code=$(Get-Code $staleConfirm) traceId=$($staleConfirm.TraceId)"

$confirm = Send-Req -Client $employeeClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/confirm-resolution" -Token $employeeToken `
    -Body @{ version = $confirmVersion }
Assert-That 'confirm.success.200' ($confirm.Status -eq 200) "status=$($confirm.Status) traceId=$($confirm.TraceId)"
$confirmData = Get-Data $confirm
Assert-That 'confirm.status.COMPLETED' ($confirmData.status -eq 'COMPLETED') "status=$($confirmData.status)"
Assert-That 'confirm.version.incremented' ($confirmData.version -eq ($confirmVersion + 1)) `
    "expected=$($confirmVersion + 1) actual=$($confirmData.version)"
Assert-That 'confirm.deadline.cleared' ($null -eq $confirmData.actionDeadlineAt) `
    "actionDeadlineAt=[$($confirmData.actionDeadlineAt)]"
Assert-That 'confirm.assignee.retained' ($confirmData.assignee.id -eq $meItData.id) `
    "assigneeId=$($confirmData.assignee.id)"

# ── 14. 终态不可重开 ────────────────────────────────────────────────────
$completedDetail = Send-Req -Client $employeeClient -Method Get -Path "/fd/v1/tickets/$ticketA" -Token $employeeToken
$completedData = Get-Data $completedDetail
Assert-That 'detail.completed.completionMethod' ($completedData.completionMethod -eq 'REQUESTER_CONFIRMED') `
    "completionMethod=$($completedData.completionMethod)"
Assert-That 'detail.completed.endedAtPresent' ($null -ne $completedData.endedAt) `
    "endedAt=$($completedData.endedAt)"
Assert-That 'detail.completed.noActions' (@($completedData.allowedActions).Count -eq 0) `
    "allowedActions=$(@($completedData.allowedActions) -join ',')"

$reConfirm = Send-Req -Client $employeeClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/confirm-resolution" -Token $employeeToken `
    -Body @{ version = $confirmData.version }
Assert-That 'terminal.reConfirm.409' ($reConfirm.Status -eq 409) `
    "status=$($reConfirm.Status) code=$(Get-Code $reConfirm) traceId=$($reConfirm.TraceId)"

$reClaimTerminal = Send-Req -Client $itClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/claim" -Token $itToken -Body @{ version = $confirmData.version }
Assert-That 'terminal.reClaim.409' ($reClaimTerminal.Status -eq 409) `
    "status=$($reClaimTerminal.Status) code=$(Get-Code $reClaimTerminal) traceId=$($reClaimTerminal.TraceId)"

$reProcessTerminal = Send-Req -Client $itClient -Method Post `
    -Path "/fd/v1/tickets/$ticketA/actions/add-processing-record" -Token $itToken `
    -Body @{ version = $confirmData.version; content = '终态不应允许追加处理记录' }
Assert-That 'terminal.reProcess.409' ($reProcessTerminal.Status -eq 409) `
    "status=$($reProcessTerminal.Status) code=$(Get-Code $reProcessTerminal) traceId=$($reProcessTerminal.TraceId)"

# ── 15. 时间线（完整主链） ───────────────────────────────────────────────
$records = Send-Req -Client $itClient -Method Get `
    -Path "/fd/v1/tickets/$ticketA/records?page=1&size=50" -Token $itToken
Assert-That 'records.200' ($records.Status -eq 200) "status=$($records.Status) traceId=$($records.TraceId)"
$recordItems = @((Get-Data $records).items)
$types = @($recordItems | ForEach-Object { $_.recordType })
$expectedTypes = 'CREATE,CLAIM,PROCESS,PROCESS,PROCESS,RESOLUTION,COMPLETION'
Assert-That 'records.mainChainOrder' (($types -join ',') -eq $expectedTypes) "types=$($types -join ',')"
$sequences = @($recordItems | ForEach-Object { $_.sequenceNo })
$sorted = @($sequences | Sort-Object)
Assert-That 'records.sequenceStrictlyIncreasing' (($sequences -join ',') -eq ($sorted -join ',')) `
    "sequenceNos=$($sequences -join ',')"
Assert-That 'records.sequenceStartsAtOne' ($sequences[0] -eq 1) "sequenceNos=$($sequences -join ',')"
$firstProcess = $recordItems | Where-Object { $_.recordType -eq 'PROCESS' } | Select-Object -First 1
Assert-That 'records.process.contentTrimmed' ($firstProcess.context.content -eq '已联系机房，等待重启窗口') `
    "content=[$($firstProcess.context.content)]"
Assert-That 'records.process.actorIsIt' ($firstProcess.actor.id -eq $meItData.id) `
    "actorId=$($firstProcess.actor.id)"
$claimRecord = $recordItems | Where-Object { $_.recordType -eq 'CLAIM' } | Select-Object -First 1
Assert-That 'records.claim.transition' `
    (($claimRecord.context.fromStatus -eq 'PENDING') -and ($claimRecord.context.toStatus -eq 'PROCESSING')) `
    "from=$($claimRecord.context.fromStatus) to=$($claimRecord.context.toStatus)"
$resolutionRecord = $recordItems | Where-Object { $_.recordType -eq 'RESOLUTION' } | Select-Object -First 1
Assert-That 'records.resolution.transition' `
    (($resolutionRecord.context.fromStatus -eq 'PROCESSING') -and ($resolutionRecord.context.toStatus -eq 'WAITING_FOR_CONFIRMATION')) `
    "from=$($resolutionRecord.context.fromStatus) to=$($resolutionRecord.context.toStatus)"
Assert-That 'records.resolution.contentTrimmed' ($resolutionRecord.context.content -eq '已重置密码，请用新密码登录后确认') `
    "content=[$($resolutionRecord.context.content)]"
Assert-That 'records.resolution.deadlineExposed' ($null -ne $resolutionRecord.context.deadlineAt) `
    "deadlineAt=$($resolutionRecord.context.deadlineAt)"
$completionRecord = $recordItems | Where-Object { $_.recordType -eq 'COMPLETION' } | Select-Object -First 1
Assert-That 'records.completion.method' ($completionRecord.context.completionMethod -eq 'REQUESTER_CONFIRMED') `
    "completionMethod=$($completionRecord.context.completionMethod)"
Assert-That 'records.completion.actorIsRequester' ($completionRecord.actor.id -eq 1) `
    "actorId=$($completionRecord.actor.id)"

# ── 16. 数据库直查：终态快照字段满足 CHECK 约束 ─────────────────────────
# mysql 客户端把"命令行传密码"的警告写到 stderr；在 $ErrorActionPreference='Stop' 下
# 原生命令的 stderr 会变成终止性错误，因此这里临时把偏好设为 Continue 并显式合并 stderr，
# 再从输出里挑出真正的数据行（含制表符的那一行）。
$dbState = $null
$previousPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $raw = docker exec $DbContainer mysql -N -B `
        -u"$env:FLOWDESK_DB_USERNAME" -p"$env:FLOWDESK_DB_PASSWORD" "$env:FLOWDESK_DB_NAME" `
        -e "SELECT status, IFNULL(completion_method,'NULL'), IFNULL(action_deadline_at,'NULL'), IF(ended_at IS NULL,'NULL','SET'), assignee_id, version FROM ticket WHERE ticket_no='$ticketA';" 2>&1
    $dataLine = @($raw | Where-Object { $_ -match "`t" } | Select-Object -Last 1)
    $dbState = if ($dataLine.Count -gt 0) { ($dataLine[0] -replace "`t", ' | ').Trim() } else { "NO_DATA_ROW: $($raw -join ' / ')" }
} catch {
    $dbState = "QUERY_FAILED: $($_.Exception.Message)"
} finally {
    $ErrorActionPreference = $previousPreference
}
Assert-That 'db.snapshot.terminalFieldsConsistent' `
    ($dbState -match '^COMPLETED\s*\|\s*REQUESTER_CONFIRMED\s*\|\s*NULL\s*\|\s*SET') `
    "row=[$dbState]"

# ── 17. 汇总与证据 ───────────────────────────────────────────────────────
$failed = @($script:results | Where-Object { -not $_.passed })
$stamp = Get-Date -Format 'yyyyMMddHHmmss'
$outFile = Join-Path $OutDir "stage3-ticket-actions-$stamp.json"

$summary = [ordered]@{
    stage      = '阶段 3 IT 处理闭环'
    slices     = '步骤1 claim、步骤2 add-processing-record、步骤3 submit-resolution 与 confirm-resolution'
    date       = (Get-Date -Format 'yyyy-MM-dd')
    baseUrl    = $BaseUrl
    tickets    = @{ ticketA = $ticketA; ticketB = $ticketB }
    observed   = @($script:observed)
    total      = $script:results.Count
    passed     = ($script:results.Count - $failed.Count)
    failed     = $failed.Count
    assertions = @($script:results)
    cleanupSql = @(
        "DELETE FROM ticket_record WHERE ticket_id IN (SELECT id FROM ticket WHERE ticket_no IN ('$ticketA','$ticketB'));",
        "DELETE FROM ticket_participant WHERE ticket_id IN (SELECT id FROM ticket WHERE ticket_no IN ('$ticketA','$ticketB'));",
        "DELETE FROM ticket WHERE ticket_no IN ('$ticketA','$ticketB');"
    )
}
$json = $summary | ConvertTo-Json -Depth 8
# 无 BOM UTF-8：5.1 的 Set-Content -Encoding UTF8 会加 BOM，JSON 解析会失败。
[System.IO.File]::WriteAllText($outFile, $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ''
Write-Host ("总计 {0} 项，通过 {1}，失败 {2}" -f $summary.total, $summary.passed, $summary.failed)
Write-Host "证据：$outFile"
Write-Host '清理 SQL（回到演示基线）：'
$summary.cleanupSql | ForEach-Object { Write-Host "  $_" }

if ($failed.Count -gt 0) {
    Write-Host '失败项：'
    $failed | ForEach-Object { Write-Host "  - $($_.name) :: $($_.detail)" }
    exit 1
}
exit 0
