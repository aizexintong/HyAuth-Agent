# ============================================================
#  外置账号聊天广播（逐接收者未签名）离线回归测试
#
#  复现原版 26.3 的发送形状：
#    PlayerList#broadcastChatMessage → ServerPlayer#sendChatMessage
#      → OutgoingChatMessage.Player#sendToPlayer
#        → ServerGamePacketListenerImpl#sendPlayerChatMessage(PlayerChatMessage, ChatType.Bound)
#  把 Agent 真正的切面（ExternalChatAdvice）内联进替身，断言：
#    · 外置玩家发给别人 → 原版伪装聊天（不进签名账本、客户端不校验）
#    · 外置玩家发给自己 → 保持原版签名消息（自己看得到自己发的话）
#    · 正版玩家 → 完全不动
#
#  用法（项目根目录）：
#      pwsh -File verify\chat.ps1
#      pwsh -File verify\chat.ps1 -JavaHome "C:\Program Files\Java\jdk-25"
# ============================================================
param(
    [string]$JavaHome = "C:\Users\liu22\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon"
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$verify = $PSScriptRoot

$java = if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin\java.exe"))) { Join-Path $JavaHome "bin\java.exe" } else { "java" }
$javac = if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin\javac.exe"))) { Join-Path $JavaHome "bin\javac.exe" } else { "javac" }
Write-Host "[chat] java = $java"

# ---------- 依赖（ListManager 需要 gson） ----------
$libs = Join-Path $verify "libs"
$gson = Join-Path $libs "gson-2.10.1.jar"
if (-not (Test-Path $gson)) {
    New-Item -ItemType Directory -Force -Path $libs | Out-Null
    Write-Host "[chat] 下载依赖 gson-2.10.1.jar"
    & $java (Join-Path $verify "tools\Fetch.java") `
        "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar" $gson
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $gson)) { Write-Host "[chat] 依赖下载失败"; exit 1 }
}

# ---------- Agent 包 ----------
$agent = Join-Path $root "target\HyAuth-Agent-1.0.0.jar"
if (-not (Test-Path $agent)) { Write-Host "[chat] 未找到 $agent，请先构建"; exit 1 }
Write-Host "[chat] agent = $agent"

# ---------- 编译 ----------
$out = Join-Path $env:TEMP ("hyauth-chat-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$stubs = Join-Path $out "stubs"
$app = Join-Path $out "app"
$run = Join-Path $out "run"
New-Item -ItemType Directory -Force -Path $stubs, $app, $run | Out-Null

Write-Host "[chat] 编译替身 ..."
$stubSources = Get-ChildItem -Recurse -File -Filter *.java (Join-Path $verify "chat\net") |
    Select-Object -ExpandProperty FullName
& $javac -nowarn -encoding UTF-8 -d $stubs @stubSources
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Host "[chat] 编译测试主程序 ..."
& $javac -nowarn -encoding UTF-8 -d $app -cp "$agent;$gson" (Join-Path $verify "chat\ChatAdviceMain.java")
if ($LASTEXITCODE -ne 0) { exit 1 }

# ---------- 运行 ----------
Write-Host ""
Write-Host "=========================================================="
Write-Host "=== 外置账号聊天广播（逐接收者未签名）测试"
Write-Host "=========================================================="
Push-Location $run
& $java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" `
    --add-opens "java.base/java.lang=ALL-UNNAMED" `
    --add-opens "java.base/java.util=ALL-UNNAMED" `
    -cp "$app;$agent;$gson" chat.ChatAdviceMain $stubs
$code = $LASTEXITCODE
Pop-Location

Write-Host ""
if ($code -eq 0) { Write-Host "[chat] 通过" } else { Write-Host "[chat] 失败 (exit $code)" }
exit $code
