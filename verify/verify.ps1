# ============================================================
#  HyAuth-Agent 端到端自检脚本
#
#  作用：在本地起一个「模拟 LittleSkin 服务 + 模拟/真实 Authlib」环境，
#        真实挂载 -javaagent，验证四种鉴权场景：
#          mock-modern / mock-legacy  模拟新旧两种 Authlib 方法签名
#          real-modern / real-legacy  真实 authlib 6.0.54 / 3.11.49
#  断言：正版玩家透传、外置玩家拦截、无会话拒绝、篡改/未签名属性丢弃，
#        以及修改配置文件后无需重启即可热重载生效。
#
#  用法（在项目根目录）：
#      pwsh -File verify\verify.ps1
#      pwsh -File verify\verify.ps1 -JavaHome "C:\Program Files\Java\jdk-21"
# ============================================================
param(
    [string]$JavaHome = "C:\Users\liu22\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon",
    [int]$Port = 25588
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$verify = $PSScriptRoot

# ---------- 1. 定位 java / javac ----------
$java = if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin\java.exe"))) { Join-Path $JavaHome "bin\java.exe" } else { "java" }
$javac = if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin\javac.exe"))) { Join-Path $JavaHome "bin\javac.exe" } else { "javac" }
Write-Host "[verify] java  = $java"

# ---------- 2. 准备依赖 ----------
$libs = Join-Path $verify "libs"
$deps = @(
    @{ Path = "authlib-6.0.54.jar";    Url = "https://libraries.minecraft.net/com/mojang/authlib/6.0.54/authlib-6.0.54.jar" },
    @{ Path = "authlib-3.11.49.jar";   Url = "https://libraries.minecraft.net/com/mojang/authlib/3.11.49/authlib-3.11.49.jar" },
    @{ Path = "authlib-10.0.77.jar";   Url = "https://libraries.minecraft.net/com/mojang/authlib/10.0.77/authlib-10.0.77.jar" },
    @{ Path = "gson-2.10.1.jar";       Url = "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar" },
    @{ Path = "guava-33.4.8-jre.jar";  Url = "https://repo1.maven.org/maven2/com/google/guava/guava/33.4.8-jre/guava-33.4.8-jre.jar" },
    @{ Path = "slf4j-api-2.0.16.jar";  Url = "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar" },
    @{ Path = "commons-lang3-3.12.0.jar"; Url = "https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar" },
    @{ Path = "commons-io-2.11.0.jar";  Url = "https://repo1.maven.org/maven2/commons-io/commons-io/2.11.0/commons-io-2.11.0.jar" }
)
New-Item -ItemType Directory -Force -Path $libs | Out-Null
# 先把所有缺的都下完再统一校验：某个仓库偶发 5xx 时不会让整套自检白挂一次
# （Fetch.java 内部已做 3 次重试 + Mojang 官方仓库失败自动换 Maven Central 镜像）
$need = @($deps | Where-Object { -not (Test-Path (Join-Path $libs $_.Path)) })
foreach ($dep in $need) {
    Write-Host "[verify] 下载依赖 $($dep.Path)"
    & $java (Join-Path $verify "tools\Fetch.java") $dep.Url (Join-Path $libs $dep.Path)
}
$stillMissing = @($deps | Where-Object {
    $f = Join-Path $libs $_.Path
    (-not (Test-Path $f)) -or ((Get-Item $f).Length -eq 0)
})
if ($stillMissing.Count -gt 0) {
    Write-Host "[verify] 依赖下载失败: " + (($stillMissing | ForEach-Object { $_.Path }) -join ', ')
    Write-Host "[verify] 请确认能访问 libraries.minecraft.net / repo1.maven.org，或手动把 jar 放进 verify/libs 后重跑"
    exit 1
}

# ---------- 3. 准备 Agent 包 ----------
$agent = Join-Path $root "target\HyAuth-Agent-1.0.0.jar"
# 写测试配置时用的 config_version 必须等于 jar 里的 CONFIG_VERSION（它跟着编译版本走），
# 否则会被升级策略当成"旧配置"整体重置。这里用 javap -constants 直接读出来。
$configVersion = "10000"
if (Test-Path $agent) {
    $javap = if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin\javap.exe"))) { Join-Path $JavaHome "bin\javap.exe" } else { "javap" }
    $line = & $javap -p -constants -cp $agent com.hyauth.agent.config.ListManager 2>$null |
        Select-String -Pattern "CONFIG_VERSION = (\d+)" | Select-Object -First 1
    if ($line -and $line.Matches.Count -gt 0) { $configVersion = $line.Matches[0].Groups[1].Value }
}
Write-Host "[deps] config_version = $configVersion"
if (-not (Test-Path $agent)) {
    Write-Host "[verify] 未找到 $agent，尝试执行 mvn package ..."
    Push-Location $root
    & mvn -B package -DskipTests
    $mvnCode = $LASTEXITCODE
    Pop-Location
    if ($mvnCode -ne 0 -or -not (Test-Path $agent)) {
        Write-Host "[verify] 请先执行 mvn clean package 生成 Agent 包。"
        exit 1
    }
}

# ---------- 4. 编译脚手架 ----------
$out = Join-Path $env:TEMP ("hyauth-verify-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$run = Join-Path $out "run"
New-Item -ItemType Directory -Force -Path "$out\common", "$out\mockmodern", "$out\mocklegacy", $run | Out-Null

$authNew = Join-Path $libs "authlib-6.0.54.jar"
$authOld = Join-Path $libs "authlib-3.11.49.jar"
$auth10 = Join-Path $libs "authlib-10.0.77.jar"
$guava = Join-Path $libs "guava-33.4.8-jre.jar"
$gson = Join-Path $libs "gson-2.10.1.jar"
$slf4j = Join-Path $libs "slf4j-api-2.0.16.jar"
$extra = "$slf4j;$(Join-Path $libs 'commons-lang3-3.12.0.jar');$(Join-Path $libs 'commons-io-2.11.0.jar')"

Write-Host "[verify] 编译自检脚手架 ..."
& $javac -nowarn -encoding UTF-8 -d "$out\common" -cp "$authNew;$guava;$gson;$agent" (Get-ChildItem "$verify\common" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { exit 1 }
& $javac -nowarn -encoding UTF-8 -d "$out\mockmodern" -cp "$authNew;$guava;$gson;$out\common" (Get-ChildItem "$verify\mockmodern" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { exit 1 }
& $javac -nowarn -encoding UTF-8 -d "$out\mocklegacy" -cp "$authOld;$guava;$gson;$out\common" (Get-ChildItem "$verify\mocklegacy" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { exit 1 }

# ---------- 5. 写入测试配置 ----------
$config = @"
{
  "config_version": -1,
  "description": "HyAuth-Agent 自检配置",
  "littleskin_players": [
    "WhitelistPlayer",
    "TamperedPlayer",
    "UnsignedPlayer",
    "NoSessionPlayer"
  ],
  "offline_players": [
    { "name": "OfflinePlayer", "uuid": "99998888-7777-6666-5555-444433332222" }
  ],
  "api_root": "http://127.0.0.1:$Port/api/yggdrasil",
  "connect_timeout_ms": 3000,
  "read_timeout_ms": 3000,
  "debug": true
}
"@
Set-Content -Path "$run\littleskin_config.json" -Value $config -Encoding UTF8

$agentArgs = @(
    "-Dfile.encoding=UTF-8",
    "-Dstdout.encoding=UTF-8",
    "-Dstderr.encoding=UTF-8",
    "-javaagent:$agent",
    "--add-opens", "java.base/java.net=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    "--add-opens", "java.base/java.util=ALL-UNNAMED"
)

# ---------- 6. 逐个模式运行 ----------
$results = @()
foreach ($mode in @("mock-modern", "mock-legacy", "real-modern", "real-legacy", "real-10")) {
    switch ($mode) {
        "mock-modern" { $cp = "$out\mockmodern;$out\common;$authNew;$guava;$gson;$extra" }
        "mock-legacy" { $cp = "$out\mocklegacy;$out\common;$authOld;$guava;$gson;$extra" }
        "real-modern" { $cp = "$out\common;$authNew;$guava;$gson;$extra" }
        "real-legacy" { $cp = "$out\common;$authOld;$guava;$gson;$extra" }
        "real-10" { $cp = "$out\common;$auth10;$guava;$gson;$extra" }
    }
    Write-Host ""
    Write-Host "=========================================================="
    Write-Host "=== 运行模式: $mode"
    Write-Host "=========================================================="
    Push-Location $run
    & $java @agentArgs -cp $cp harness.HarnessMain $mode $Port
    $code = $LASTEXITCODE
    Pop-Location
    $results += [pscustomobject]@{ Mode = $mode; ExitCode = $code }
}

# ---------- 7. 配置热重载验证 ----------
Write-Host ""
Write-Host "=========================================================="
Write-Host "=== 配置热重载验证"
Write-Host "=========================================================="

$hot = Join-Path $out "hot"
New-Item -ItemType Directory -Force -Path $hot | Out-Null

function Write-HotConfig([string]$players) {
    $json = @"
{
  "config_version": -1,
  "description": "hot reload test",
  "littleskin_players": [ $players ],
  "api_root": "http://127.0.0.1:$Port/api/yggdrasil",
  "debug": true
}
"@
    Set-Content -Path "$hot\littleskin_config.json" -Value $json -Encoding UTF8
}

Write-HotConfig '"Alpha"'
$hotArgs = @(
    "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
    "-javaagent:$agent",
    "--add-opens", "java.base/java.net=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    "--add-opens", "java.base/java.util=ALL-UNNAMED",
    "-cp", "$out\common;$agent;$gson",
    "harness.Sleeper", "Beta", "12000"
)
$proc = Start-Process -FilePath $java -ArgumentList $hotArgs -WorkingDirectory $hot -PassThru -NoNewWindow `
    -RedirectStandardOutput "$hot\out.log" -RedirectStandardError "$hot\err.log"
Start-Sleep -Seconds 4
Write-Host "运行中把 Beta 写入白名单（不重启进程）..."
Write-HotConfig '"Alpha", "Beta"'
Wait-Process -Id $proc.Id -Timeout 90 -ErrorAction SilentlyContinue
$deadline = (Get-Date).AddSeconds(30)
while (-not $proc.HasExited -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 300 }
Start-Sleep -Seconds 1

Get-Content "$hot\out.log"
$hotLog = Get-Content "$hot\out.log" -Raw
$hotOk = ($hotLog -match 'before: Beta -> false') -and ($hotLog -match 'after : Beta -> true') `
    -and ($hotLog -match '配置重载完成')
if ($hotOk) { Write-Host "[verify] 热重载生效" }
$results += [pscustomobject]@{ Mode = "hot-reload"; ExitCode = $(if ($hotOk) { 0 } else { 1 }) }

Write-Host ""
Write-Host "=== 自检汇总 ==="
$results | Format-Table -AutoSize
if (($results | Where-Object { $_.ExitCode -ne 0 }).Count -gt 0) {
    Write-Host "[verify] 存在失败项"
    exit 1
}
Write-Host "[verify] 全部通过"
exit 0
