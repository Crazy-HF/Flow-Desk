# 在独立 demo 栈运行超时批次验收；需 PowerShell 7、Docker MySQL 和已启动的后端。
# 建议将 flowdesk.ticket.timeout-scan-cron 临时设为 "*/10 * * * * *"。
# 只改动脚本创建的三张工单，收尾按外键顺序清理；不停止服务或删除卷。
param(
    [string]$BaseUrl = 'http://127.0.0.1:8092',
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'),
    [string]$DbContainer = 'flowdesk-mysql-1',
    [string]$OutFile = (Join-Path $PSScriptRoot '..\docs\acceptance\2026-10-20-timeout-batch.json')
)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw '需要 PowerShell 7。' }
. (Join-Path $PSScriptRoot 'load-env.ps1') -Path $EnvFile
$env:MYSQL_PWD = $env:FLOWDESK_MYSQL_ROOT_PASSWORD
$dbName = $env:FLOWDESK_DB_NAME
$client = [System.Net.Http.HttpClient]::new()
$client.BaseAddress = [uri]$BaseUrl
$client.Timeout = [TimeSpan]::FromSeconds(40)
$checks = [System.Collections.Generic.List[object]]::new()
$requests = [System.Collections.Generic.List[object]]::new()
$created = [System.Collections.Generic.List[string]]::new()
$failure = $null
$started = Get-Date

function Check($name, $condition, $expected, $actual) {
    $checks.Add([ordered]@{ name = $name; expected = $expected; actual = "$actual"; passed = [bool]$condition })
    if (-not $condition) { throw "$name`: expected $expected; actual $actual" }
}

function Sql([string]$statement) {
    $output = & docker exec -e MYSQL_PWD $DbContainer mysql -uroot -N -B `
        --default-character-set=utf8mb4 $dbName -e $statement 2>&1
    if ($LASTEXITCODE -ne 0) { throw "MySQL 查询失败：$output" }
    return @($output)
}

function Request([string]$method, [string]$path, $body = $null, [string]$token = '', [bool]$multipart = $false) {
    $message = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($method), $path)
    $message.Headers.Add('Origin', 'http://127.0.0.1:4173')
    $message.Headers.Add('X-Trace-Id', [guid]::NewGuid().ToString())
    if ($token) { $message.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token) }
    if ($null -ne $body) {
        $json = ConvertTo-Json -InputObject $body -Depth 8 -Compress
        if ($multipart) {
            $form = [System.Net.Http.MultipartFormDataContent]::new()
            $form.Add([System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, 'application/json'), 'ticket')
            $message.Content = $form
        } else {
            $message.Content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, 'application/json')
        }
    }
    try {
        $response = $client.Send($message)
        $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $parsed = if ($raw) { $raw | ConvertFrom-Json } else { $null }
        $traceId = if ($response.Headers.Contains('X-Trace-Id')) { ($response.Headers.GetValues('X-Trace-Id') | Select-Object -First 1) } else { $null }
        $requests.Add([ordered]@{ method = $method; path = $path; status = [int]$response.StatusCode; traceId = $traceId })
        return [pscustomobject]@{ status = [int]$response.StatusCode; data = $parsed.data; code = $parsed.code; traceId = $traceId }
    } finally { $message.Dispose() }
}

function WaitFor([scriptblock]$condition, [string]$name, [int]$seconds = 65) {
    $end = (Get-Date).AddSeconds($seconds)
    do {
        if (& $condition) { Check $name $true '在期限内达成' '达成'; return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $end)
    Check $name $false '在期限内达成' '超时'
}

try {
    Check 'health' ((Request 'GET' '/actuator/health').status -eq 200) '200' 'health response'
    $employeeLogin = Request 'POST' '/fd/v1/auth/login' @{ username = 'employee'; password = '123456' }
    $itLogin = Request 'POST' '/fd/v1/auth/login' @{ username = 'it'; password = '123456' }
    Check 'login.employee' ($employeeLogin.status -eq 200) '200' $employeeLogin.status
    Check 'login.it' ($itLogin.status -eq 200) '200' $itLogin.status
    $employeeToken = $employeeLogin.data.accessToken
    $itToken = $itLogin.data.accessToken
    $categories = Request 'GET' '/fd/v1/categories/options' $null $employeeToken
    Check 'categories' (($categories.status -eq 200) -and (@($categories.data).Count -gt 0)) '非空分类列表' $categories.status
    $categoryId = [long]$categories.data[0].id
    $employeeId = [long]((Sql "SELECT id FROM iam_user WHERE username='employee'"))
    $itId = [long]((Sql "SELECT id FROM iam_user WHERE username='it'"))
    $before = (Sql 'SELECT (SELECT COUNT(*) FROM ticket),(SELECT COUNT(*) FROM ticket_record),(SELECT COUNT(*) FROM ticket_participant),(SELECT COUNT(*) FROM notification)')

    $ticketIds = @{}
    foreach ($kind in @('confirmation', 'supplement', 'cancel')) {
        $create = Request 'POST' '/fd/v1/tickets' @{
            submissionKey = [guid]::NewGuid().ToString(); title = "TimeoutAcceptance-$kind-$([guid]::NewGuid().ToString('N').Substring(0,8))"
            description = '超时批次真实栈验收临时工单'; categoryId = $categoryId; priority = 'MEDIUM'
        } $employeeToken $true
        Check "create.$kind" ($create.status -eq 201) '201' $create.status
        $ticketNo = [string]$create.data.ticketNo
        Check "ticketNo.$kind" ($ticketNo -match '^FD-\d{8}-\d+$') '合法工单编号' $ticketNo
        $created.Add($ticketNo)
        $ticketIds[$kind] = [long]((Sql "SELECT id FROM ticket WHERE ticket_no='$ticketNo'"))
        $claim = Request 'POST' "/fd/v1/tickets/$ticketNo/actions/claim" @{ version = 0 } $itToken
        Check "claim.$kind" ($claim.status -eq 200) '200' $claim.status
        if ($kind -eq 'confirmation') {
            $action = Request 'POST' "/fd/v1/tickets/$ticketNo/actions/submit-resolution" @{ version = 1; content = '已处理，等待确认' } $itToken
        } elseif ($kind -eq 'supplement') {
            $action = Request 'POST' "/fd/v1/tickets/$ticketNo/actions/request-supplement" @{ version = 1; content = '请补充复现步骤' } $itToken
        } else {
            $action = Request 'POST' "/fd/v1/tickets/$ticketNo/actions/request-cancel" @{ version = 1; reason = '验收到期请求' } $employeeToken
        }
        Check "prepare.$kind" ($action.status -eq 200) '200' $action.status
    }

    $confirmId = $ticketIds.confirmation
    $supplementId = $ticketIds.supplement
    $cancelId = $ticketIds.cancel
    Sql "UPDATE ticket SET action_deadline_at=DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 30 SECOND) WHERE id IN ($confirmId,$supplementId)" | Out-Null
    Sql "UPDATE ticket SET cancel_request_deadline_at=DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 30 SECOND) WHERE id=$cancelId" | Out-Null
    WaitFor { [int]((Sql "SELECT COUNT(*) FROM notification WHERE category IN ('CONFIRMATION_DUE_SOON','SUPPLEMENT_DUE_SOON','CANCEL_REQUEST_DUE_SOON') AND ticket_id IN ($confirmId,$supplementId,$cancelId)")) -eq 3 } 'three.dueSoon.reminders'
    $reminderRecipients = (Sql "SELECT GROUP_CONCAT(CONCAT(category,':',recipient_id) ORDER BY category) FROM notification WHERE category LIKE '%DUE_SOON' AND ticket_id IN ($confirmId,$supplementId,$cancelId)")
    Check 'reminder.recipients' (($reminderRecipients -like "*CONFIRMATION_DUE_SOON:$employeeId*") -and ($reminderRecipients -like "*SUPPLEMENT_DUE_SOON:$employeeId*") -and ($reminderRecipients -like "*CANCEL_REQUEST_DUE_SOON:$itId*")) '员工/员工/当前负责人' $reminderRecipients
    Start-Sleep -Seconds 12
    $dedupCount = [int]((Sql "SELECT COUNT(*) FROM notification WHERE category LIKE '%DUE_SOON' AND ticket_id IN ($confirmId,$supplementId,$cancelId)"))
    Check 'reminder.dedup' ($dedupCount -eq 3) '重复扫描仍为 3 条' $dedupCount

    Sql "UPDATE ticket SET action_deadline_at=DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id IN ($confirmId,$supplementId)" | Out-Null
    Sql "UPDATE ticket SET cancel_request_deadline_at=DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id=$cancelId" | Out-Null
    WaitFor { [int]((Sql "SELECT COUNT(*) FROM ticket WHERE id=$confirmId AND status='COMPLETED' AND completion_method='AUTO_CONFIRM_TIMEOUT'")) -eq 1 } 'confirmation.autoComplete'
    WaitFor { [int]((Sql "SELECT COUNT(*) FROM ticket WHERE id=$supplementId AND status='CLOSED' AND close_method='AUTO_SUPPLEMENT_TIMEOUT' AND close_reason='REQUESTER_NO_RESPONSE'")) -eq 1 } 'supplement.autoClose'
    WaitFor { [int]((Sql "SELECT COUNT(*) FROM notification WHERE ticket_id=$cancelId AND category='CANCEL_REQUEST_DUE_SOON'")) -eq 0 } 'cancel.reminder.recalled'
    Check 'cancel.ticket.unchanged' ([int]((Sql "SELECT COUNT(*) FROM ticket WHERE id=$cancelId AND status='PROCESSING'")) -eq 1) '处理中' '数据库状态'
    $systemRecords = [int]((Sql "SELECT COUNT(*) FROM ticket_record WHERE ticket_id IN ($confirmId,$supplementId) AND actor_type='SYSTEM' AND actor_user_id IS NULL AND record_type IN ('COMPLETION','CLOSURE')"))
    Check 'system.records' ($systemRecords -eq 2) '两条系统留痕' $systemRecords
    $timeoutNotifications = [int]((Sql "SELECT COUNT(*) FROM notification WHERE ticket_id IN ($confirmId,$supplementId) AND category IN ('CONFIRMATION_TIMEOUT','SUPPLEMENT_TIMEOUT')"))
    Check 'timeout.notifications' ($timeoutNotifications -eq 4) '两种事件各发给两人，共四条' $timeoutNotifications
    $staleReminders = [int]((Sql "SELECT COUNT(*) FROM notification WHERE ticket_id IN ($confirmId,$supplementId,$cancelId) AND category LIKE '%DUE_SOON'"))
    Check 'stale.reminders' ($staleReminders -eq 0) '三个旧提醒均回收' $staleReminders
} catch {
    $failure = $_.Exception.Message
} finally {
    foreach ($ticketNo in $created) {
        if ($ticketNo -notmatch '^FD-\d{8}-\d+$') { continue }
        $id = [long]((Sql "SELECT id FROM ticket WHERE ticket_no='$ticketNo'"))
        if ($id -le 0) { continue }
        Sql "DELETE FROM notification WHERE ticket_id=$id" | Out-Null
        Sql "DELETE FROM ticket_attachment WHERE record_id IN (SELECT id FROM ticket_record WHERE ticket_id=$id)" | Out-Null
        Sql "DELETE FROM ticket_relation WHERE source_ticket_id=$id OR target_ticket_id=$id" | Out-Null
        Sql "DELETE FROM ticket_record WHERE ticket_id=$id" | Out-Null
        Sql "DELETE FROM ticket_participant WHERE ticket_id=$id" | Out-Null
        Sql "DELETE FROM ticket WHERE id=$id" | Out-Null
    }
    if ($null -ne $before) {
        $after = (Sql 'SELECT (SELECT COUNT(*) FROM ticket),(SELECT COUNT(*) FROM ticket_record),(SELECT COUNT(*) FROM ticket_participant),(SELECT COUNT(*) FROM notification)')
        $checks.Add([ordered]@{ name = 'cleanup.fingerprint'; expected = $before; actual = $after; passed = ($before -eq $after) })
        if ($before -ne $after -and -not $failure) { $failure = '清理后四表计数与运行前不同' }
    }
    $client.Dispose()
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    $report = [ordered]@{
        slice = 'timeout-batch'; startedAt = $started.ToString('s'); finishedAt = (Get-Date).ToString('s')
        baseUrl = $BaseUrl; branch = 'flow-desk/timeout-batch-verify'; tickets = @($created)
        checks = @($checks); requests = @($requests); failure = $failure
        totals = [ordered]@{ total = $checks.Count; passed = @($checks | Where-Object passed).Count; failed = @($checks | Where-Object { -not $_.passed }).Count }
    }
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutFile -Encoding utf8NoBOM
}
if ($failure) { Write-Error $failure; exit 1 }
Write-Output "Timeout acceptance passed: $($checks.Count) checks. Evidence: $OutFile"
