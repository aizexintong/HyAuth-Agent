#!/usr/bin/env bash
# ============================================================
#  HyAuth-Agent 服务端启动脚本（Linux / macOS）
#
#  用法：
#    1) 把 HyAuth-Agent-1.0.0.jar、server.jar、本脚本放在同一目录
#    2) chmod +x start.sh
#    3) ./start.sh
#
#  可用环境变量覆盖（都有默认值）：
#    JAVA_PATH   java 可执行文件路径（默认用 PATH 上的 java）
#    MIN_RAM     最小堆，默认 4G
#    MAX_RAM     最大堆，默认 8G
#    AGENT_JAR   默认 HyAuth-Agent-1.0.0.jar
#    SERVER_JAR  默认 server.jar
#
#  例：JAVA_PATH=/usr/lib/jvm/java-25-openjdk/bin/java MAX_RAM=6G ./start.sh
# ============================================================
set -u

# 切到脚本所在目录：littleskin_config.json / world / logs 都是「工作目录」相对路径
cd "$(dirname "$0")" || exit 1

AGENT_JAR="${AGENT_JAR:-HyAuth-Agent-1.0.0.jar}"
SERVER_JAR="${SERVER_JAR:-server.jar}"
MIN_RAM="${MIN_RAM:-4G}"
MAX_RAM="${MAX_RAM:-8G}"

# ---------- 1) 定位 Java ----------
if [ -n "${JAVA_PATH:-}" ]; then
    JAVA="$JAVA_PATH"
elif command -v java >/dev/null 2>&1; then
    JAVA="$(command -v java)"
else
    echo "[HyAuth] 错误：找不到 java。请安装 Java 25，或显式指定："
    echo "         JAVA_PATH=/path/to/java ./start.sh"
    exit 1
fi

if ! command -v "$JAVA" >/dev/null 2>&1 && [ ! -x "$JAVA" ]; then
    echo "[HyAuth] 错误：JAVA_PATH 不是可执行文件：$JAVA"
    exit 1
fi

# ---------- 2) Java 版本提示（不阻断，只提醒） ----------
JAVA_MAJOR="$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)"
if [ -n "$JAVA_MAJOR" ]; then
    if [ "$JAVA_MAJOR" -lt 25 ] 2>/dev/null; then
        echo "[HyAuth] 警告：当前 Java 主版本 $JAVA_MAJOR。"
        echo "         Minecraft 26.3 需要 Java 25；1.18~1.21.x 可用 Java 17/21。"
    fi
else
    JAVA_MAJOR="未知"
fi

# ---------- 3) 文件检查 ----------
if [ ! -f "$AGENT_JAR" ]; then
    echo "[HyAuth] 错误：未找到 $AGENT_JAR"
    echo "         在项目根目录执行 ./build.sh（或 mvn -B clean package）后，"
    echo "         把 target/HyAuth-Agent-1.0.0.jar 复制到本目录。"
    exit 1
fi

if [ ! -f "$SERVER_JAR" ]; then
    echo "[HyAuth] 错误：未找到 $SERVER_JAR，请把本脚本放到服务端根目录（server.jar 旁边）。"
    exit 1
fi

echo "[HyAuth] Java   : $JAVA (Java $JAVA_MAJOR)"
echo "[HyAuth] Agent  : $AGENT_JAR"
echo "[HyAuth] 服务端 : $SERVER_JAR"
echo "[HyAuth] 内存   : -Xms$MIN_RAM -Xmx$MAX_RAM"
echo "[HyAuth] 停止   : 在服务端控制台输入 stop（不要用 kill -9）"
echo

# ---------- 4) 启动 ----------
# --add-opens 说明：
#   java.base/java.lang 【必需】原版 server.jar 是 Mojang Bundler：它把服务端类
#     放在「父加载器为 platform 的 URLClassLoader」里，Agent 必须反射调用
#     ClassLoader#defineClass 把自己的辅助类注入该加载器。缺了这个开关，
#     服务端照样能开服，但玩家登录时鉴权会失败。
#   java.base/java.net / java.util：现代 JVM 强封装突破（旧版本可能不识别，见 README）。
#
# exec：让 java 直接接管当前进程，Ctrl+C / systemd stop 能正常触发服务端优雅关服。
exec "$JAVA" \
    -Xms"$MIN_RAM" -Xmx"$MAX_RAM" \
    --add-opens java.base/java.net=ALL-UNNAMED \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-opens java.base/java.util=ALL-UNNAMED \
    -javaagent:"$AGENT_JAR" \
    -jar "$SERVER_JAR" \
    nogui
