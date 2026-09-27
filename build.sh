#!/usr/bin/env bash
# ============================================================
#  HyAuth-Agent 一键构建脚本（Linux / macOS）
#
#  用法：
#    chmod +x build.sh && ./build.sh
#
#  Maven 查找顺序：
#    1) PATH 上的 mvn
#    2) 项目内 tools/apache-maven-3.9.9/bin/mvn（免安装副本，可删除）
#  都没有时会打印安装指引。
#
#  产物：target/HyAuth-Agent-1.0.0.jar（可直接 -javaagent 使用）
#
#  注意：首次构建需要联网（Maven Central 与 libraries.minecraft.net 拉依赖）。
# ============================================================
set -u
cd "$(dirname "$0")" || exit 1

# ---------- 1) 找 Java ----------
JAVA_BIN=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
    echo "[HyAuth] 使用 JAVA_HOME=$JAVA_HOME"
elif command -v java >/dev/null 2>&1; then
    JAVA_BIN="$(command -v java)"
    # Maven 的 mvn 脚本需要 JAVA_HOME，这里从 PATH 反推（readlink -f 在 macOS 上会失败，已兜底）
    JAVA_REAL="$(readlink -f "$JAVA_BIN" 2>/dev/null || echo "$JAVA_BIN")"
    JAVA_HOME_DERIVED="$(dirname "$(dirname "$JAVA_REAL")")"
    if [ -x "$JAVA_HOME_DERIVED/bin/java" ]; then
        export JAVA_HOME="$JAVA_HOME_DERIVED"
        echo "[HyAuth] 由 PATH 推导 JAVA_HOME=$JAVA_HOME"
    fi
else
    echo "[HyAuth] 错误：找不到 java，请先安装 JDK 17 或更高版本，"
    echo "         或设置 JAVA_HOME 指向 JDK 安装目录。"
    exit 1
fi

"$JAVA_BIN" -version 2>&1 | head -n 1

# ---------- 2) 找 Maven ----------
if command -v mvn >/dev/null 2>&1; then
    MVN="$(command -v mvn)"
elif [ -x "tools/apache-maven-3.9.9/bin/mvn" ]; then
    MVN="tools/apache-maven-3.9.9/bin/mvn"
    echo "[HyAuth] PATH 上没有 mvn，使用项目内副本：$MVN"
    echo "[HyAuth] 若提示没有执行权限，请先执行：chmod +x $MVN"
else
    echo "[HyAuth] 错误：找不到 Maven。三种解决办法："
    echo "  1) Debian/Ubuntu : sudo apt install maven"
    echo "  2) RHEL/CentOS   : sudo yum install maven"
    echo "  3) 手动安装       : 见 README「4.3 本机没有 Maven 怎么办」"
    echo
    echo "  提示：其实更省事的做法是在已有构建产物的机器上直接复制"
    echo "        target/HyAuth-Agent-1.0.0.jar 到服务端目录，服务端机器无需 Maven。"
    exit 1
fi

# ---------- 3) 构建 ----------
echo "[HyAuth] Maven : $MVN"
echo "[HyAuth] 开始构建（首次会下载依赖，请耐心等待）..."
echo

# -Dmaven.repo.local 不设置则用 ~/.m2/repository（正常情况就用默认值）
exec "$MVN" -B clean package
