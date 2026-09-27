# ============================================================
#  Bundler 类加载器隔离回归测试
#
#  复现原版 server.jar 的加载器拓扑（platform 父加载器 + authlib 在子加载器、
#  Agent 与 Byte Buddy 在应用加载器），断言切面能真正挂上、离线名单能接管、
#  非名单玩家能透传。
#
#  用法（项目根目录）：
#      pwsh -File verify\loaderiso.ps1
#      pwsh -File verify\loaderiso.ps1 -JavaHome "C:\Program Files\Java\jdk-25"
# ============================================================
param(
    [string]$JavaHome = "C:\Users\liu22\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon"
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$verify = $PSScriptRoot

$java = if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin\java.exe"))) { Join-Path $JavaHome "bin\java.exe" } else { "java" }
$javac = if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin\javac.exe"))) { Join-Path $JavaHome "bin\javac.exe" } else { "javac" }
Write-Host "[loaderiso] java = $java"

# ---------- 依赖 ----------
$libs = Join-Path $verify "libs"
$deps = @(
    @{ Path = "authlib-10.0.77.jar";   Url = "https://libraries.minecraft.net/com/mojang/authlib/10.0.77/authlib-10.0.77.jar" },
    @{ Path = "gson-2.10.1.jar";       Url = "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar" },
    @{ Path = "guava-33.4.8-jre.jar";  Url = "https://repo1.maven.org/maven2/com/google/guava/guava/33.4.8-jre/guava-33.4.8-jre.jar" },
    @{ Path = "slf4j-api-2.0.16.jar";  Url = "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar" },
    @{ Path = "commons-lang3-3.12.0.jar"; Url = "https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar" },
    @{ Path = "commons-io-2.11.0.jar";  Url = "https://repo1.maven.org/maven2/commons-io/commons-io/2.11.0/commons-io-2.11.0.jar" }
)
New-Item -ItemType Directory -Force -Path $libs | Out-Null
foreach ($dep in $deps) {
    $target = Join-Path $libs $dep.Path
    if (-not (Test-Path $target)) {
        Write-Host "[loaderiso] 下载依赖 $($dep.Path)"
        & $java (Join-Path $verify "tools\Fetch.java") $dep.Url $target
        if ($LASTEXITCODE -ne 0) { Write-Host "[loaderiso] 依赖下载失败: $($dep.Url)"; exit 1 }
    }
}

# ---------- Agent 包 ----------
$agent = Join-Path $root "target\HyAuth-Agent-1.0.0.jar"
if (-not (Test-Path $agent)) { Write-Host "[loaderiso] 未找到 $agent，请先构建"; exit 1 }
Write-Host "[loaderiso] agent = $agent"

# ---------- 编译 ----------
$out = Join-Path $env:TEMP ("hyauth-loaderiso-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$child = Join-Path $out "child"
$app = Join-Path $out "app"
$run = Join-Path $out "run"
New-Item -ItemType Directory -Force -Path $child, $app, $run | Out-Null

Write-Host "[loaderiso] 编译 ..."
& $javac -nowarn -encoding UTF-8 -d $child `
    (Join-Path $verify "loaderiso\IsoTarget.java") `
    (Join-Path $verify "loaderiso\IsoChatListener.java") `
    (Join-Path $verify "loaderiso\IsoPlayer.java") `
    (Join-Path $verify "loaderiso\IsoSignableCommand.java")
if ($LASTEXITCODE -ne 0) { exit 1 }

# 目标加载器里放一个 net.minecraft.util.SignatureValidator 替身（含 NO_VALIDATION 与 from(PublicKey,String)）
& $javac -nowarn -encoding UTF-8 -d $child `
    (Join-Path $verify "loaderiso\net\minecraft\util\SignatureValidator.java") `
    (Join-Path $verify "loaderiso\net\minecraft\util\SignatureUpdater.java")
if ($LASTEXITCODE -ne 0) { exit 1 }

# 以及聊天解码器工厂替身：Decoder.unsigned(UUID, BooleanSupplier)
& $javac -nowarn -encoding UTF-8 -d $child `
    (Join-Path $verify "loaderiso\net\minecraft\network\chat\SignedMessageChain.java")
if ($LASTEXITCODE -ne 0) { exit 1 }

# ★ 关键：应用类路径上【只有】Agent 包，没有 authlib / gson
& $javac -nowarn -encoding UTF-8 -d $app -cp $agent (Join-Path $verify "loaderiso\LoaderIsoMain.java")
if ($LASTEXITCODE -ne 0) { exit 1 }

# 诊断类：编译时给 authlib，运行时故意不给
& $javac -nowarn -encoding UTF-8 -d $app -cp (Join-Path $libs "authlib-10.0.77.jar") `
    (Join-Path $verify "loaderiso\MissingTypeUser.java") (Join-Path $verify "loaderiso\Caller.java")
if ($LASTEXITCODE -ne 0) { exit 1 }

# ---------- 配置 ----------
$config = @"
{
  "description": "loaderiso",
  "littleskin_players": [],
  "offline_players": [
    { "name": "OfflinePlayer", "uuid": "99998888-7777-6666-5555-444433332222" }
  ],
  "api_root": "http://127.0.0.1:25588/api/yggdrasil",
  "debug": true
}
"@
Set-Content -Path "$run\littleskin_config.json" -Value $config -Encoding UTF8

# ---------- 运行 ----------
Write-Host ""
Write-Host "=========================================================="
Write-Host "=== Bundler 类加载器隔离测试"
Write-Host "=========================================================="
Push-Location $run
& $java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" `
    "-javaagent:$agent" `
    "--add-opens" "java.base/java.net=ALL-UNNAMED" `
    "--add-opens" "java.base/java.lang=ALL-UNNAMED" `
    "--add-opens" "java.base/java.util=ALL-UNNAMED" `
    -cp "$app;$agent" loaderiso.LoaderIsoMain $libs $child
$code = $LASTEXITCODE
Pop-Location

Write-Host ""
if ($code -eq 0) { Write-Host "[loaderiso] 通过" } else { Write-Host "[loaderiso] 失败 (exit $code)" }
exit $code
