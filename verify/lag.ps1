# ============================================================
#  区块卡顿勘探 + 管理员命令 离线回归测试
#
#  复现 26.x 的调用形状，把 Agent 真正的切面（ChunkTickAdvice / EntityTickAdvice /
#  PassengerTickAdvice / BlockEntityTickAdvice / ServerTickAdvice / CommandAdvice / LevelTickAdvice）
#  用 Byte Buddy 内联进替身，然后跑真正的辅助类，断言：
#    · 切面字节码里没有 net/minecraft（README §8.4 硬约束，字节码扫描回归）
#    · 相邻异常区块被合并成"坐标范围"，报告行带可点击传送
#    · tp 落点交给原版 /tp，y 来自地表高度图
#    · 实体（含乘客）耗时按实体所在区块归因
#    · 方块实体是否嵌在区块 tick 内能被运行期探测（口径自动切换）
#    · 命令接管边界：非本命令根不拦、权限不足拒绝
#    · /hy off 自动生成 UUIDv7、先扫已有 UUID 查重、已有身份默认拦下、force 才换新
#    · 空置域挖掘：preview 不动世界、每条 fill ≤32768 且分层连续无重叠、三阶段与 forceload
#      加卸载、节流生效、start 必须 confirm、更高权限门槛、y 范围按建筑高度夹取、stop 立即中止
#
#  用法（项目根目录）：
#      pwsh -File verify\lag.ps1
#      pwsh -File verify\lag.ps1 -JavaHome "C:\Program Files\Java\jdk-25"
# ============================================================
param(
    [string]$JavaHome = "C:\Users\liu22\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon"
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$verify = $PSScriptRoot

$java = if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin\java.exe"))) { Join-Path $JavaHome "bin\java.exe" } else { "java" }
$javac = if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin\javac.exe"))) { Join-Path $JavaHome "bin\javac.exe" } else { "javac" }
Write-Host "[lag] PowerShell = $($PSVersionTable.PSVersion) （$($PSVersionTable.PSEdition)）"
Write-Host "[lag] java  = $java"
Write-Host "[lag] javac = $javac"
# 环境自曝：失败时日志里就能直接看出用的哪个 JDK / 哪个 PowerShell（CI 与本机都适用）
try { Write-Host "[lag] java 版本 = $((& $java -version 2>&1 | Select-Object -First 1))" } catch { Write-Host "[lag] 无法执行 java：$($_.Exception.Message)" }
try { Write-Host "[lag] javac 版本 = $((& $javac -version 2>&1 | Select-Object -First 1))" } catch { Write-Host "[lag] 无法执行 javac：$($_.Exception.Message)" }

# ---------- 依赖（ListManager / ConfigWriter 需要 gson） ----------
$libs = Join-Path $verify "libs"
$gson = Join-Path $libs "gson-2.10.1.jar"
if (-not (Test-Path $gson)) {
    New-Item -ItemType Directory -Force -Path $libs | Out-Null
    Write-Host "[lag] 下载依赖 gson-2.10.1.jar"
    & $java (Join-Path $verify "tools\Fetch.java") `
        "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar" $gson
    # Fetch.java 内部已重试 3 次并自动换镜像；这里再确认一次产物非空
    if (-not (Test-Path $gson) -or (Get-Item $gson).Length -eq 0) {
        Write-Host "[lag] 依赖下载失败（gson）；请检查网络或手动放进 verify/libs"
        exit 1
    }
}

# ---------- Agent 包 ----------
$agent = Join-Path $root "target\HyAuth-Agent-1.0.0.jar"
if (-not (Test-Path $agent)) { Write-Host "[lag] 未找到 $agent，请先构建（build.bat 或 mvn clean package）"; exit 1 }
Write-Host "[lag] agent = $agent（$((Get-Item $agent).Length) 字节）"

# ---------- 编译 ----------
$out = Join-Path $env:TEMP ("hyauth-lag-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$stubs = Join-Path $out "stubs"
$app = Join-Path $out "app"
$run = Join-Path $out "run"
New-Item -ItemType Directory -Force -Path $stubs, $app, $run | Out-Null
Write-Host "[lag] 工作目录 = $out"

Write-Host "[lag] 编译 26.x 形状的服务端替身 ..."
# 替身 = verify\lag 下的全部 .java（net\** 是服务端类替身，com\mojang\brigadier\** 是 Brigadier 替身），
# 排除测试主程序自己（它单独编译，需要 Agent 包在类路径上）
$stubSources = Get-ChildItem -Recurse -File -Filter *.java (Join-Path $verify "lag") |
    Where-Object { $_.Name -ne "LagAdviceMain.java" } |
    Select-Object -ExpandProperty FullName
if (-not $stubSources -or $stubSources.Count -eq 0) {
    Write-Host "[lag] 没找到替身源码（verify\lag\net\**\*.java）—— checkout 是否完整？"
    exit 1
}
& $javac -nowarn -encoding UTF-8 -d $stubs @stubSources 2>&1 | Tee-Object -Variable stubOut | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "[lag] 替身编译失败（javac 退出码 $LASTEXITCODE），前若干行输出："
    $stubOut | Select-Object -First 30 | ForEach-Object { Write-Host "    $_" }
    exit 1
}

Write-Host "[lag] 编译测试主程序 ..."
& $javac -nowarn -encoding UTF-8 -d $app -cp "$agent;$gson" (Join-Path $verify "lag\LagAdviceMain.java") 2>&1 |
    Tee-Object -Variable appOut | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "[lag] 测试主程序编译失败（javac 退出码 $LASTEXITCODE），前若干行输出："
    $appOut | Select-Object -First 30 | ForEach-Object { Write-Host "    $_" }
    exit 1
}

# ---------- 运行 ----------
Write-Host ""
Write-Host "=========================================================="
Write-Host "=== 区块卡顿勘探 + 管理员命令 回归测试"
Write-Host "=========================================================="
Push-Location $run
& $java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" `
    --add-opens "java.base/java.lang=ALL-UNNAMED" `
    --add-opens "java.base/java.util=ALL-UNNAMED" `
    -cp "$app;$agent;$gson" lag.LagAdviceMain $stubs $agent
$code = $LASTEXITCODE
Write-Host ""
Write-Host "[lag] 工作目录（生成的配置/查重样本都在这）: $run"
Pop-Location

Write-Host ""
if ($code -eq 0) { Write-Host "[lag] 通过" } else { Write-Host "[lag] 失败 (exit $code)" }
exit $code
