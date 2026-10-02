# ============================================================
#  真 server.jar 切点核对（真机自检）
#
#  为什么需要它：离线自检用的是自己写的替身类，替身写错形状时自检照样全绿 ——
#  v1.0.6 就是这么翻车的：26.3 的 Commands#performPrefixedCommand 返回 void，
#  而切面匹配器要求 int，结果"日志说已改写、命令却全落到原版"。
#  这个脚本直接反编译你的 server.jar，把每个切点的方法名/参数个数/返回类型核一遍。
#
#  用法：
#      pwsh -File verify\realjar.ps1 -ServerJar "C:\path\to\server.jar"
#      pwsh -File verify\realjar.ps1 -ServerJar /srv/mc/server.jar -JavaHome /usr/lib/jvm/java-25-openjdk
#
#  说明：原版 server.jar 是 Bundler，真正的服务端类在内层
#  META-INF/versions/<版本>/server-<版本>.jar 里，脚本会自动找出来并解出来用。
# ============================================================
param(
    [Parameter(Mandatory = $true)][string]$ServerJar,
    [string]$JavaHome = "C:\Users\liu22\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon"
)

$ErrorActionPreference = 'Continue'

if (-not (Test-Path $ServerJar)) { Write-Host "[realjar] 找不到 $ServerJar"; exit 2 }
$javap = if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin\javap.exe"))) {
    Join-Path $JavaHome "bin\javap.exe"
} elseif ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin/javap"))) {
    Join-Path $JavaHome "bin/javap"
} else { "javap" }
$jarTool = if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin\jar.exe"))) {
    Join-Path $JavaHome "bin\jar.exe"
} else { "jar" }

Write-Host "[realjar] javap   = $javap"
Write-Host "[realjar] server  = $ServerJar（$((Get-Item $ServerJar).Length) 字节）"

# ---------- 1. 找到真正装服务端类的那个 jar ----------
$work = Join-Path $env:TEMP ("hyauth-realjar-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
New-Item -ItemType Directory -Force -Path $work | Out-Null

$entries = & $jarTool tf $ServerJar
$inner = ($entries | Where-Object { $_ -match '^META-INF/versions/.*\.jar$' } | Select-Object -First 1)
$target = $ServerJar
if ($inner) {
    Write-Host "[realjar] Bundler 内层 jar = $inner"
    Push-Location $work
    & $jarTool xf $ServerJar $inner
    Pop-Location
    $target = Join-Path $work $inner
}
$classpath = $target
# authlib 不在内层 jar 里，而在 Bundler 的 META-INF/libraries/ 下
$authlib = ($entries | Where-Object { $_ -match '^META-INF/libraries/com/mojang/authlib/.*/authlib-.*\.jar$' } | Select-Object -First 1)
if ($authlib) {
    Write-Host "[realjar] authlib       = $authlib"
    Push-Location $work
    & $jarTool xf $ServerJar $authlib
    Pop-Location
    $sep = if ($env:OS -eq "Windows_NT") { ";" } else { ":" }
    $classpath = "$target$sep$(Join-Path $work $authlib)"
}

function Get-Javap($className) {
    $out = & $javap -p -cp $classpath $className 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    return $out
}

$pass = 0
$fail = 0
function Check($label, $ok, $detail) {
    if ($ok) {
        Write-Host ("  [PASS] " + $label)
        $script:pass++
    } else {
        Write-Host ("  [FAIL] " + $label + "  → " + $detail)
        $script:fail++
    }
}

# 版本差异项：存在与否都正常，只打印信息，不计入失败
function Info($label, $present, $whenMissing) {
    if ($present) {
        Write-Host ("  [INFO] " + $label + "：存在（该版本走这条路径）")
    } else {
        Write-Host ("  [INFO] " + $label + "：不存在（$whenMissing）")
    }
}

function Method-Line($className, $methodName) {
    $dump = Get-Javap $className
    if (-not $dump) { return $null }
    return ($dump | Where-Object { $_ -match ("\s" + [regex]::Escape($methodName) + "\(") } | Select-Object -First 1)
}

function Has-Class($className) {
    return (Get-Javap $className) -ne $null
}

Write-Host ""
Write-Host "=== 1) 管理员命令接管（控制台 / 游戏内 / RCON 的汇聚点） ==="
foreach ($method in @("performPrefixedCommand", "performCommand")) {
    $line = Method-Line "net.minecraft.commands.Commands" $method
    if (-not $line) {
        Check "$method 存在且带 2 个参数" $false "方法不存在（切面会匹配为空 ⇒ 命令全落到原版）"
        continue
    }
    $args2 = ($line -match "\([^)]*,[^)]*\)")
    $ret = "?"
    if ($line -match "^\s*(?:public|private|protected)?\s*(?:static\s+)?(?:final\s+)?([\w\.\$<>\[\]]+)\s+$method\(") {
        $ret = $Matches[1]
    }
    Check "$method(2 个参数) 存在" $args2 $line.Trim()
    $supported = @("void", "int", "boolean") -contains $ret
    Check "$method 返回类型属于 {void,int,boolean}（当前 $ret）" $supported `
        "返回 $ret ⇒ 需要为它补一个对应的切面版本（见 CommandAdviceVoid / CommandAdvice / CommandAdviceBoolean）"
}

Write-Host ""
Write-Host "=== 2) 权限判定（两代 API 至少有一条可用） ==="
$stackHasOld = (Method-Line "net.minecraft.commands.CommandSourceStack" "hasPermission") -ne $null
$permSet = Has-Class "net.minecraft.server.permissions.PermissionSet"
$stackHasNew = (Method-Line "net.minecraft.commands.CommandSourceStack" "permissions") -ne $null
Info "旧 API CommandSourceStack#hasPermission(int)" $stackHasOld "26.x 已删除，改走 PermissionSet 路径"
Info "新 API CommandSourceStack#permissions()" $stackHasNew "老版本没有它，改走 hasPermission 路径"
Check "至少一条权限路径可用" ($stackHasOld -or $stackHasNew) "两代 API 都没有 ⇒ 命令会因为判成 0 级而拒绝所有玩家"
if ($stackHasNew) {
    Check "net.minecraft.server.permissions.PermissionSet#hasPermission 存在" `
        ((Method-Line "net.minecraft.server.permissions.PermissionSet" "hasPermission") -ne $null) "缺它无法判等级"
    foreach ($field in @("COMMANDS_MODERATOR", "COMMANDS_GAMEMASTER", "COMMANDS_ADMIN", "COMMANDS_OWNER")) {
        $dump = Get-Javap "net.minecraft.server.permissions.Permissions"
        $has = $dump -and ($dump | Where-Object { $_ -match ("\s" + $field + "\s*;") } | Select-Object -First 1)
        Check "Permissions.$field 常量存在" ([bool]$has) "缺它则对应等级判不出来"
    }
}

Write-Host ""
Write-Host "=== 3) 区块卡顿勘探切点 ==="
$level = "net.minecraft.server.level.ServerLevel"
foreach ($m in @(@("tickChunk", 2), @("tickNonPassenger", 1), @("tickPassenger", 2), @("tick", 1))) {
    Check "$level#$($m[0])/$($m[1]) 存在" ((Method-Line $level $m[0]) -ne $null) "勘探对应分项会为 0"
}
Check "最后一级实体的计时入口 TickingBlockEntity#tick 存在" `
    ((Method-Line "net.minecraft.world.level.block.entity.TickingBlockEntity" "tick") -ne $null) `
    "方块实体分项会为 0（26.x 用它逐个 tick 方块实体）"
Info "旧版入口 LevelChunk#tickBlockEntities" `
    ((Method-Line "net.minecraft.world.level.chunk.LevelChunk" "tickBlockEntities") -ne $null) `
    "26.3 起已移除，属于正常情况（改走 TickingBlockEntity#tick）"
Check "MinecraftServer#tickServer 存在" ((Method-Line "net.minecraft.server.MinecraftServer" "tickServer") -ne $null) `
    "整服 MSPT 基线会为 0"

Write-Host ""
Write-Host "=== 4) 鉴权 / 聊天切点 ==="
Check "MinecraftServicesSessionService（hasJoinedServer 宿主）" `
    (Has-Class "com.mojang.authlib.services.MinecraftServicesSessionService") "三合一登录会失效"
Check "Services#profileKeySignatureValidator（聊天密钥来源）" `
    ((Method-Line "net.minecraft.server.Services" "profileKeySignatureValidator") -ne $null) "外置账号聊天放宽会失效"
Check "ClientboundHelloPacket#shouldAuthenticate（离线名单握手改写）" `
    ((Method-Line "net.minecraft.network.protocol.login.ClientboundHelloPacket" "shouldAuthenticate") -ne $null) `
    "离线名单会退化为仅服务端放行"
Check "SignedMessageChain\$Decoder（离线玩家豁免安全档案）" `
    (Has-Class 'net.minecraft.network.chat.SignedMessageChain$Decoder') "离线玩家聊天豁免会失效"

Write-Host ""
Write-Host "=========================================================="
Write-Host ("[realjar] 通过 $pass 项，失败 $fail 项")
Write-Host "[realjar] 工作目录: $work"
if ($fail -eq 0) { Write-Host "[realjar] 全部通过：这份 server.jar 上所有切点都能挂上" }
else { Write-Host "[realjar] 有切点对不上：Agent 会有功能静默失效，请对照上面的 FAIL 说明" }
exit ($(if ($fail -eq 0) { 0 } else { 1 }))
