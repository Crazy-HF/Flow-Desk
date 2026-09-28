<#
RBAC 阶段验收标准第 4 条：四条手工真实栈链路（docs/modules/rbac.md 第 11 节）。
针对 local profile + 真实 MySQL/Redis 栈执行，每条留 traceId 与响应码。
脚本只做临时对象的增删与可逆的关系变更，结束时把库恢复到与迁移/demo 种子一致的状态。
兼容 Windows PowerShell 5.1（不使用 -SkipHttpErrorCheck），HTTP 走 System.Net.Http.HttpClient。
用法：powershell -NoProfile -File scripts/manual-rbac-acceptance.ps1
#>
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$base = 'http://127.0.0.1:8081'
$stamp = Get-Date -Format 'yyyyMMddHHmmss'
$tempRoleCode1 = "MANUAL_VERIFY_ROLE1_$stamp"
$tempRoleCode2 = "MANUAL_VERIFY_ROLE2_$stamp"
$tempPermCode = "MANUAL_VERIFY_PERM_$stamp"
$results = New-Object System.Collections.ArrayList
$script:failures = 0

function New-FdClient {
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.UseProxy = $false
    $handler.CookieContainer = New-Object System.Net.CookieContainer
    $client = New-Object System.Net.Http.HttpClient($handler)
    $client.BaseAddress = New-Object System.Uri($base)
    return $client
}

# 每个"身份"一个 client，Cookie 容器互相隔离（Refresh Token 走 HttpOnly Cookie）
$adminClient = New-FdClient
$empClient = New-FdClient
$itClient = New-FdClient

function Send {
    param(
        [string]$Name,
        [string]$Method,
        [string]$Path,
        $Body,
        [System.Net.Http.HttpClient]$Client
    )
    $request = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($Method)), $Path)
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 6 -Compress
        $request.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    }
    $response = $Client.SendAsync($request).Result
    $text = $response.Content.ReadAsStringAsync().Result
    $parsed = $null
    if ($text) { try { $parsed = $text | ConvertFrom-Json } catch { $parsed = $null } }
    $trace = $null
    $values = $null
    if ($response.Headers.TryGetValues('X-Trace-Id', [ref]$values)) { $trace = @($values)[0] }
    $record = [pscustomobject]@{
        step    = $Name
        method  = $Method
        path    = $Path
        status  = [int]$response.StatusCode
        code    = if ($parsed) { $parsed.code } else { $null }
        message = if ($parsed) { $parsed.message } else { $null }
        traceId = $trace
        body    = $parsed
    }
    [void]$results.Add($record)
    Write-Host ("{0,-46} {1,-6} {2,-4} {3,-22} {4}" -f $Name, $Method, $record.status, $record.code, $record.traceId)
    $request.Dispose()
    return $record
}

function Login {
    param([string]$Username, [System.Net.Http.HttpClient]$Client)
    $r = Send -Name "login:$Username" -Method POST -Path '/fd/v1/auth/login' -Client $Client `
        -Body @{ username = $Username; password = '123456' }
    if ($r.status -ne 200) { throw "登录失败：$Username -> $($r.status) $($r.code)" }
    return $r.body.data.accessToken
}

function Assert-That {
    param([string]$What, $Actual, $Expected)
    if ("$Actual" -ne "$Expected") {
        $script:failures++
        Write-Host "  !! FAIL $What : expected [$Expected] actual [$Actual]" -ForegroundColor Red
    }
    else {
        Write-Host "  ok  $What = $Actual" -ForegroundColor Green
    }
}

Write-Host "`n=== 前置：admin 登录取令牌 ===" -ForegroundColor Cyan
$adminToken = Login 'admin' $adminClient
$adminClient.DefaultRequestHeaders.Authorization =
    New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $adminToken)

Write-Host "`n=== 准备临时对象（临时角色 R1/R2、临时权限 P） ===" -ForegroundColor Cyan
$r1 = Send -Name 'create:temp-role-R1' -Method POST -Path '/fd/v1/admin/roles' -Client $adminClient `
    -Body @{ code = $tempRoleCode1; name = '手工验收临时角色R1'; description = '阶段验收第4条临时对象，验收后删除' }
$tempRoleId1 = $r1.body.data.id
$r2 = Send -Name 'create:temp-role-R2' -Method POST -Path '/fd/v1/admin/roles' -Client $adminClient `
    -Body @{ code = $tempRoleCode2; name = '手工验收临时角色R2'; description = '阶段验收第4条临时对象，验收后删除' }
$tempRoleId2 = $r2.body.data.id
$p1 = Send -Name 'create:temp-permission-P' -Method POST -Path '/fd/v1/admin/permissions' -Client $adminClient `
    -Body @{ code = $tempPermCode; name = '手工验收临时权限'; description = '阶段验收第4条临时对象，验收后删除' }
$tempPermId = $p1.body.data.id
Assert-That '临时角色 R1 已创建' ($tempRoleId1 -gt 0) 'True'
Assert-That '临时角色 R2 已创建' ($tempRoleId2 -gt 0) 'True'
Assert-That '临时权限 P 已创建' ($tempPermId -gt 0) 'True'

Write-Host "`n=== 链路 1：给 employee 授予角色 -> 旧 Access Token 失效 ===" -ForegroundColor Cyan
$empToken1 = Login 'employee' $empClient
$empClient.DefaultRequestHeaders.Authorization =
    New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $empToken1)
$before1 = Send -Name 'L1:employee旧令牌(授予前)' -Method GET -Path '/fd/v1/auth/me' -Client $empClient
Assert-That '授予前旧令牌可用(200)' $before1.status 200
$grant1 = Send -Name 'L1:admin给employee授予R1' -Method POST -Path '/fd/v1/admin/user-roles' -Client $adminClient `
    -Body @{ userId = 1; roleIds = @($tempRoleId1) }
Assert-That '授予返回 200' $grant1.status 200
$after1 = Send -Name 'L1:employee旧令牌(授予后)' -Method GET -Path '/fd/v1/auth/me' -Client $empClient
Assert-That '授予后旧令牌 401' $after1.status 401
Assert-That '错误码 AUTH_SESSION_INVALID' $after1.code 'AUTH_SESSION_INVALID'
$revokeR1 = Send -Name 'L1:撤销employee的R1' -Method DELETE -Path "/fd/v1/admin/user-roles/1/$tempRoleId1" -Client $adminClient
Assert-That '撤销临时角色 200' $revokeR1.status 200

Write-Host "`n=== 链路 2：变更角色权限 -> 该角色全部用户旧令牌失效 ===" -ForegroundColor Cyan
$grantUsers = Send -Name 'L2:把R2授予employee与it' -Method POST -Path '/fd/v1/admin/user-roles/actions/grant-users' -Client $adminClient `
    -Body @{ roleId = $tempRoleId2; userIds = @(1, 2) }
Assert-That 'grant-users 返回 200' $grantUsers.status 200
$empToken2 = Login 'employee' $empClient
$empClient.DefaultRequestHeaders.Authorization =
    New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $empToken2)
$itToken2 = Login 'it' $itClient
$itClient.DefaultRequestHeaders.Authorization =
    New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $itToken2)
$b1 = Send -Name 'L2:employee旧令牌(变更前)' -Method GET -Path '/fd/v1/auth/me' -Client $empClient
$b2 = Send -Name 'L2:it旧令牌(变更前)' -Method GET -Path '/fd/v1/auth/me' -Client $itClient
Assert-That 'employee 变更前旧令牌 200' $b1.status 200
Assert-That 'it 变更前旧令牌 200' $b2.status 200
$changePerm = Send -Name 'L2:给R2授予临时权限P' -Method POST -Path '/fd/v1/admin/role-permissions' -Client $adminClient `
    -Body @{ roleId = $tempRoleId2; permissionIds = @($tempPermId) }
Assert-That '角色权限授予 200' $changePerm.status 200
$a1 = Send -Name 'L2:employee旧令牌(变更后)' -Method GET -Path '/fd/v1/auth/me' -Client $empClient
$a2 = Send -Name 'L2:it旧令牌(变更后)' -Method GET -Path '/fd/v1/auth/me' -Client $itClient
$adminStill = Send -Name 'L2:操作人admin令牌(变更后)' -Method GET -Path '/fd/v1/auth/me' -Client $adminClient
Assert-That 'employee(持该角色)令牌 401' $a1.status 401
Assert-That 'employee 错误码 AUTH_SESSION_INVALID' $a1.code 'AUTH_SESSION_INVALID'
Assert-That 'it(持该角色)令牌 401' $a2.status 401
Assert-That 'it 错误码 AUTH_SESSION_INVALID' $a2.code 'AUTH_SESSION_INVALID'
Assert-That '非该角色持有者admin令牌仍有效(200)' $adminStill.status 200

Write-Host "`n=== 链路 3：清空 employee 全部角色 -> 零角色合法终态 ===" -ForegroundColor Cyan
$empToken3 = Login 'employee' $empClient
$empClient.DefaultRequestHeaders.Authorization =
    New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $empToken3)
$pre3 = Send -Name 'L3:employee旧令牌(清空前)' -Method GET -Path '/fd/v1/auth/me' -Client $empClient
Assert-That '清空前旧令牌 200' $pre3.status 200
$clear3 = Send -Name 'L3:清空employee全部角色' -Method DELETE -Path '/fd/v1/admin/user-roles/users/1' -Client $adminClient
Assert-That '清空返回 200' $clear3.status 200
Assert-That '清空响应码 OK' $clear3.code 'OK'
$post3 = Send -Name 'L3:employee旧令牌(清空后)' -Method GET -Path '/fd/v1/auth/me' -Client $empClient
Assert-That '清空后旧令牌 401' $post3.status 401
Assert-That '错误码 AUTH_SESSION_INVALID' $post3.code 'AUTH_SESSION_INVALID'
$empToken4 = Login 'employee' $empClient
$empClient.DefaultRequestHeaders.Authorization =
    New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $empToken4)
$me4 = Send -Name 'L3:employee重新登录后/auth/me' -Method GET -Path '/fd/v1/auth/me' -Client $empClient
Assert-That '零角色可重新登录(200)' $me4.status 200
Assert-That '重新登录后角色数为 0' @($me4.body.data.roles).Count 0
Assert-That '重新登录后业务权限数为 0' @($me4.body.data.permissions).Count 0
$deny4 = Send -Name 'L3:零权限访问/fd/v1/admin/roles' -Method GET -Path '/fd/v1/admin/roles' -Client $empClient
Assert-That '受保护接口 403' $deny4.status 403
Assert-That '错误码 ACCESS_DENIED' $deny4.code 'ACCESS_DENIED'

Write-Host "`n=== 链路 4：受保护对象不可破坏（五个入口） ===" -ForegroundColor Cyan
$d1 = Send -Name 'L4:删除SYSTEM_ADMIN角色(3)' -Method DELETE -Path '/fd/v1/admin/roles/3' -Client $adminClient
Assert-That '删除 SYSTEM_ADMIN 409' $d1.status 409
Assert-That '错误码 RBAC_CONFLICT' $d1.code 'RBAC_CONFLICT'
$d2 = Send -Name 'L4:删除RBAC_MANAGE权限(15)' -Method DELETE -Path '/fd/v1/admin/permissions/15' -Client $adminClient
Assert-That '删除 RBAC_MANAGE 409' $d2.status 409
Assert-That '错误码 RBAC_CONFLICT' $d2.code 'RBAC_CONFLICT'
$d3 = Send -Name 'L4:单条撤销3/15授权' -Method DELETE -Path '/fd/v1/admin/role-permissions/3/15' -Client $adminClient
Assert-That '单条撤销 409' $d3.status 409
Assert-That '错误码 RBAC_CONFLICT' $d3.code 'RBAC_CONFLICT'
$d4 = Send -Name 'L4:批量撤销含3/15' -Method POST -Path '/fd/v1/admin/role-permissions/actions/revoke' -Client $adminClient `
    -Body @{ roleId = 3; permissionIds = @(15) }
Assert-That '批量撤销 409' $d4.status 409
Assert-That '错误码 RBAC_CONFLICT' $d4.code 'RBAC_CONFLICT'
$d5 = Send -Name 'L4:清空SYSTEM_ADMIN全部权限' -Method DELETE -Path '/fd/v1/admin/role-permissions/roles/3' -Client $adminClient
Assert-That '清空 409' $d5.status 409
Assert-That '错误码 RBAC_CONFLICT' $d5.code 'RBAC_CONFLICT'
$still1 = Send -Name 'L4:核对角色3仍在' -Method GET -Path '/fd/v1/admin/roles/3' -Client $adminClient
$still2 = Send -Name 'L4:核对权限15仍在' -Method GET -Path '/fd/v1/admin/permissions/15' -Client $adminClient
Assert-That 'SYSTEM_ADMIN 仍存在(200)' $still1.status 200
Assert-That 'RBAC_MANAGE 仍存在(200)' $still2.status 200
$sysPerms = Send -Name 'L4:核对SYSTEM_ADMIN权限数' -Method GET -Path '/fd/v1/admin/role-permissions?roleId=3&pageNo=1&pageSize=50' -Client $adminClient
Assert-That 'SYSTEM_ADMIN 权限数仍为 4' @($sysPerms.body.data.items).Count 4
Assert-That 'SYSTEM_ADMIN 分页 totalElements 仍为 4' $sysPerms.body.data.totalElements 4

Write-Host "`n=== 链路 5：清理与恢复 ===" -ForegroundColor Cyan
$restore = Send -Name 'L5:恢复employee的EMPLOYEE角色' -Method POST -Path '/fd/v1/admin/user-roles' -Client $adminClient `
    -Body @{ userId = 1; roleIds = @(1) }
Assert-That '恢复 EMPLOYEE 200' $restore.status 200
# 注意：employee 的 R2 授权已在链路 3 的"清空全部角色"里一并移除，故此处不再重复撤销
# （这本身也是 clearAll 会清掉该用户全部关系的旁证）。
$c1 = Send -Name 'L5:核对employee已无R2(应404)' -Method DELETE -Path "/fd/v1/admin/user-roles/1/$tempRoleId2" -Client $adminClient
Assert-That 'employee 的 R2 已被链路3清空(404)' $c1.status 404
$c2 = Send -Name 'L5:撤销it的R2' -Method DELETE -Path "/fd/v1/admin/user-roles/2/$tempRoleId2" -Client $adminClient
$c3 = Send -Name 'L5:删除R2的全部权限' -Method DELETE -Path "/fd/v1/admin/role-permissions/roles/$tempRoleId2" -Client $adminClient
$c4 = Send -Name 'L5:删除临时角色R1' -Method DELETE -Path "/fd/v1/admin/roles/$tempRoleId1" -Client $adminClient
$c5 = Send -Name 'L5:删除临时角色R2' -Method DELETE -Path "/fd/v1/admin/roles/$tempRoleId2" -Client $adminClient
$c6 = Send -Name 'L5:删除临时权限P' -Method DELETE -Path "/fd/v1/admin/permissions/$tempPermId" -Client $adminClient
foreach ($c in @($c1, $c2, $c3, $c4, $c5, $c6)) {
    if (@(200, 404) -notcontains $c.status) {
        $script:failures++
        Write-Host "  !! 清理步骤失败：$($c.step) => $($c.status)" -ForegroundColor Red
    }
}

# 证据写到构建目录之外：target/ 会被 mvn clean 整体删除，留证放进去会丢。
$outDir = Join-Path $env:TEMP 'flowdesk-rbac-acceptance'
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }
$outFile = Join-Path $outDir "manual-rbac-acceptance-$stamp.json"
$results | ConvertTo-Json -Depth 8 | Set-Content -Path $outFile -Encoding UTF8

Write-Host ""
if ($script:failures -gt 0) {
    Write-Host "存在 $script:failures 项断言失败。证据：$outFile" -ForegroundColor Red
    exit 1
}
Write-Host "全部断言通过。证据：$outFile" -ForegroundColor Green
exit 0
