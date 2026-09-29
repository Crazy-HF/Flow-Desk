<#
阶段 2 收尾验收（docs/implementation-plan.md 6.1 步骤⑧）。

把 ⑧ 要求的四件事按顺序跑一遍，并把证据写成 JSON：
  1. 后端完整 verify（单元/Web + Testcontainers 集成）
  2. 前端 typecheck / lint / build / 单测（逐个串行——PROJECT_STATUS「环境前置」写明
     容器与 vitest 并行会因内存不足互相挤掉）
  3. 真实栈 E2E（playwright 自己拉起 127.0.0.1:4173 的 preview，但需要后端已在 8081 常驻）
  4. 收集 E2E 写出的截图清单

一次运行给出完整的失败面：默认不因某一步失败就中断，最后打印 PASS/FAIL 汇总。

用法：
  powershell -NoProfile -File scripts/stage2-acceptance.ps1
  # 本机 pnpm 的 Windows shim 坏掉时（PROJECT_STATUS 记过这个复发问题），用 node 入口：
  powershell -NoProfile -File scripts/stage2-acceptance.ps1 -PnpmExec 'node','D:\path\to\pnpm.mjs'

前置：
  docker compose up -d mysql redis
  .\scripts\load-env.ps1
  .\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local     # 另开终端常驻
  FLOWDESK_ALLOWED_ORIGINS 需含 http://127.0.0.1:4173（本地 .env 已加）

注意：E2E 会向演示库写入工单，且工单没有删除端点（「撤销」只改状态、行还在）。
脚本结束时会打印清理 SQL；演示前必须手工执行它恢复"工单 0 行"基线。

兼容 Windows PowerShell 5.1：不使用 ?? / ?. / 三元运算符 / -SkipHttpErrorCheck。
#>
[CmdletBinding()]
param(
    # 前端包管理器的调用前缀。默认 'pnpm'；需要绕过坏掉的 shim 时传 'node','<pnpm.mjs 路径>'。
    [string[]]$PnpmExec = @('pnpm'),
    # 后端基地址，E2E 前置健康检查用
    [string]$BaseUrl = 'http://127.0.0.1:8081',
    # 证据文件输出目录（相对仓库根）
    [string]$EvidenceDir = 'docs\acceptance',
    # 截图目录（相对仓库根）
    [string]$ReviewDir = '.ui-craft\reviews\2026-09-29-tickets',
    # 每步保留的输出行数上限，避免证据文件过大
    [int]$OutputTailLines = 40
)

$ErrorActionPreference = 'Stop'

if (-not $PSScriptRoot) { throw '请以脚本文件方式运行，不要直接粘贴内容。' }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$frontend = Join-Path $repo 'frontend'
# 不要写成 Join-Path $x ''：空 ChildPath 在 5.1 会直接抛参数绑定错误
$evidencePath = Join-Path $repo $EvidenceDir
$reviewPath = Join-Path $repo $ReviewDir

$stamp = Get-Date -Format 'yyyyMMddHHmmss'
$startedAt = (Get-Date).ToString('s')
$results = New-Object System.Collections.ArrayList
$script:failedSteps = New-Object System.Collections.ArrayList

if (-not (Test-Path -LiteralPath $evidencePath)) {
    New-Item -ItemType Directory -Path $evidencePath -Force | Out-Null
}

function Get-GitValue {
    param([string[]]$GitArgs)
    # 取全部输出行再用 Out-String 拼：status --short 是多行，只取第一行会把改动范围截断成一条
    $previous = $ErrorActionPreference
    try {
        # 5.1 下把原生命令的 stderr 并进成功流时，ErrorActionPreference=Stop 可能把它的输出
        # 当成终止性错误；这里临时放宽，取完再还原
        $ErrorActionPreference = 'Continue'
        $value = & git -C $repo @GitArgs 2>&1
        if ($null -eq $value) { return $null }
        return (($value | Out-String).Trim())
    } catch {
        return $null
    } finally {
        $ErrorActionPreference = $previous
    }
}

<#
跑一个外部命令并记录结果。

MustContain 是可选的"输出里必须出现"断言：只看退出码会漏掉"命令成功退出但实际没干活"
的情况（例如 verify 打出了 FAILURE 仍返回非零之外的怪状态），所以关键步骤同时断言输出。
#>
function Invoke-Step {
    param(
        [string]$Name,
        [string]$Executable,
        [string[]]$Arguments,
        [string]$WorkingDirectory,
        [string]$MustContain
    )

    Write-Host ''
    Write-Host ("=== {0} ===" -f $Name) -ForegroundColor Cyan

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    Push-Location $WorkingDirectory
    $output = @()
    $exitCode = -1
    $launchError = $null
    try {
        try {
            $output = & $Executable @Arguments 2>&1 | ForEach-Object { [string]$_ }
            $exitCode = $LASTEXITCODE
        } catch {
            # 命令本身起不来（例如 pnpm 的 Windows shim 坏掉）时 CommandNotFound 是终止性错误，
            # 不受 ErrorActionPreference 控制：这里接住它，把它记成一步失败而不是让脚本崩掉，
            # 否则一次运行只能看到第一个问题
            $launchError = $_.Exception.Message
            $exitCode = -1
            $output = @("命令启动失败：$launchError")
        }
    } finally {
        Pop-Location
        $ErrorActionPreference = $previous
    }

    $text = ($output -join "`n")
    $matched = $true
    if ($MustContain) { $matched = $text -match $MustContain }
    $ok = ($exitCode -eq 0) -and $matched -and ($null -eq $launchError)

    $tail = @()
    if ($output.Count -gt $OutputTailLines) {
        $tail = $output[($output.Count - $OutputTailLines)..($output.Count - 1)]
    } else {
        $tail = $output
    }

    # 失败的那一步整段回显，成功的那一步只回显尾部：终端里第一眼要看到真正的问题
    if ($ok) {
        $tail | ForEach-Object { Write-Host $_ }
    } else {
        $output | ForEach-Object { Write-Host $_ }
    }

    $record = [pscustomobject]@{
        name        = $Name
        executable  = $Executable
        arguments   = ($Arguments -join ' ')
        directory   = $WorkingDirectory
        exitCode    = $exitCode
        mustContain = $MustContain
        matched     = $matched
        launchError = $launchError
        ok          = $ok
        outputTail  = $tail
    }
    [void]$results.Add($record)

    if ($ok) {
        Write-Host ("--- {0}: OK" -f $Name) -ForegroundColor Green
    } else {
        Write-Host ("--- {0}: FAILED (exit={1}, matched={2})" -f $Name, $exitCode, $matched) -ForegroundColor Red
        [void]$script:failedSteps.Add($Name)
    }

    return $ok
}

# 组装前端命令：把 PnpmExec 前缀与脚本名之后的参数拼在一起。
# 不写成 $PnpmExec[1..($n-1)]：当数组只有一项时该切片会反向取到 $PnpmExec[0]。
$pnpmExe = $PnpmExec[0]
$pnpmPrefix = @()
if ($PnpmExec.Count -gt 1) {
    $pnpmPrefix = $PnpmExec[1..($PnpmExec.Count - 1)]
}

function Invoke-FrontendStep {
    param(
        [string]$Name,
        [string[]]$ScriptArguments,
        [string]$MustContain
    )
    $all = @()
    $all += $pnpmPrefix
    $all += @('--dir', $frontend)
    $all += $ScriptArguments
    return (Invoke-Step -Name $Name -Executable $pnpmExe -Arguments $all -WorkingDirectory $repo -MustContain $MustContain)
}

Write-Host '阶段 2 收尾验收开始' -ForegroundColor Yellow
Write-Host ("仓库：{0}" -f $repo)
Write-Host ("前端命令前缀：{0}" -f (($PnpmExec) -join ' '))

# ---- 1. 后端完整 verify ----------------------------------------------------
# 两条本机必备参数写在 PROJECT_STATUS「环境前置」：Mockito 自附加需要 allowAttachSelf，
# 集成测试需要能访问 Docker 命名管道——后者要求放宽文件策略的会话。
$null = Invoke-Step `
    -Name '后端 clean verify' `
    -Executable (Join-Path $repo 'mvnw.cmd') `
    -Arguments @('-B', 'clean', 'verify', '-DargLine=-Djdk.attach.allowAttachSelf=true') `
    -WorkingDirectory $repo `
    -MustContain 'BUILD SUCCESS'

# ---- 2. 前端逐个串行 -------------------------------------------------------
$null = Invoke-FrontendStep -Name '前端 typecheck' -ScriptArguments @('typecheck')
$null = Invoke-FrontendStep -Name '前端 lint（含 stylelint 六轴闸门）' -ScriptArguments @('lint')
$null = Invoke-FrontendStep -Name '前端 build' -ScriptArguments @('build')
$null = Invoke-FrontendStep -Name '前端单测' -ScriptArguments @('test:unit', '--run', '--maxWorkers=1')

# ---- 3. 真实栈 E2E ---------------------------------------------------------
$healthOk = $false
Write-Host ''
Write-Host '=== 后端健康检查（E2E 前置）===' -ForegroundColor Cyan
try {
    $health = Invoke-RestMethod -Uri ("{0}/actuator/health" -f $BaseUrl) -TimeoutSec 5
    $healthOk = ($health.status -eq 'UP')
    Write-Host ("{0}/actuator/health -> {1}" -f $BaseUrl, $health.status)
} catch {
    Write-Host ("{0}/actuator/health 不可达：{1}" -f $BaseUrl, $_.Exception.Message) -ForegroundColor Red
    Write-Host 'E2E 需要后端在 8081 常驻（.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local）。' -ForegroundColor Red
    Write-Host '本次跳过 E2E，其余步骤的证据照常记录。' -ForegroundColor Red
    [void]$script:failedSteps.Add('后端健康检查')
}

if ($healthOk) {
    # 截图由 E2E 自己写进 reviewDir；build 已在上面跑过，playwright 的 webServer 只负责 preview
    $null = Invoke-FrontendStep -Name '真实栈 E2E（工单主链路）' -ScriptArguments @('test:e2e')
}

# ---- 4. 截图清单 -----------------------------------------------------------
$screenshots = @()
if (Test-Path -LiteralPath $reviewPath) {
    $screenshots = @(Get-ChildItem -LiteralPath $reviewPath -File -Filter '*.png' | ForEach-Object { $_.Name })
}
Write-Host ''
Write-Host ('截图目录：{0} -> {1} 张 png' -f $reviewPath, $screenshots.Count)

# ---- 5. 证据与汇总 ---------------------------------------------------------
$failed = @($script:failedSteps)
$result = 'PASS'
if ($failed.Count -gt 0) { $result = 'FAIL' }

$evidence = [pscustomobject]@{
    stage           = '阶段 2 收尾验收（docs/implementation-plan.md 6.1 步骤⑧）'
    result          = $result
    startedAt       = $startedAt
    finishedAt      = (Get-Date).ToString('s')
    repo            = $repo
    branch          = (Get-GitValue -GitArgs @('branch', '--show-current'))
    head            = (Get-GitValue -GitArgs @('rev-parse', 'HEAD'))
    workingTree     = (Get-GitValue -GitArgs @('status', '--short'))
    pnpmExec        = ($PnpmExec -join ' ')
    backendHealth   = $healthOk
    steps           = @($results)
    failedSteps     = $failed
    screenshots     = $screenshots
    notes           = @(
        'E2E 会向演示库写入工单，且工单没有删除端点；必须按下面的清理 SQL 手工恢复基线。',
        '清理顺序不能颠倒：参与者与记录先删，最后删工单。',
        "DELETE FROM ticket_participant WHERE ticket_id IN (SELECT id FROM ticket WHERE title LIKE 'E2E 联调工单%');",
        "DELETE FROM ticket_record      WHERE ticket_id IN (SELECT id FROM ticket WHERE title LIKE 'E2E 联调工单%');",
        "DELETE FROM ticket             WHERE title LIKE 'E2E 联调工单%';"
    )
}

$evidenceFile = Join-Path $evidencePath ("stage2-acceptance-{0}.json" -f $stamp)
$json = $evidence | ConvertTo-Json -Depth 8
# 显式写成无 BOM 的 UTF-8：5.1 的 Set-Content -Encoding UTF8 会加 BOM，
# 带 BOM 的 JSON 用 JSON.parse / ConvertFrom-Json 读会直接抛错
[System.IO.File]::WriteAllText($evidenceFile, $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ''
Write-Host '================ 汇总 ================' -ForegroundColor Yellow
foreach ($record in $results) {
    $flag = 'OK  '
    if (-not $record.ok) { $flag = 'FAIL' }
    Write-Host ("  {0} {1}" -f $flag, $record.name)
}
if (-not $healthOk) { Write-Host '  FAIL 后端健康检查（E2E 前置）' }
Write-Host ("结果：{0}" -f $result) -ForegroundColor Yellow
Write-Host ("证据：{0}" -f $evidenceFile)
Write-Host ''
Write-Host '收尾提醒：' -ForegroundColor Yellow
Write-Host '  1. 执行上面的清理 SQL，并用 SQL 八项数量核对演示库回到基线。'
Write-Host '  2. 用证据文件与截图更新 docs/implementation-plan.md 的步骤⑧与 PROJECT_STATUS.md。'
Write-Host '  3. Git 提交/推送/合并仍需用户明确授权。'

if ($result -ne 'PASS') { exit 1 }
exit 0
