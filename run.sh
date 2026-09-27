#!/usr/bin/env bash
# HyAuth-Agent 启动脚本
# 用法：与本文件和 HyAuth-Agent-1.0.0.jar、server.jar 放在同一目录，然后 ./run.sh

cd "$(dirname "$0")" || exit 1

JAVA="/root/server/data/environment/Java/java25/jdk-25/bin/java"
[ -x "$JAVA" ] || JAVA="$(command -v java)"

AGENT="HyAuth-Agent-1.0.0.jar"
SERVER="server.jar"

[ -f "$AGENT" ]  || { echo "错误：当前目录没有 $AGENT"; exit 1; }
[ -f "$SERVER" ] || { echo "错误：当前目录没有 $SERVER"; exit 1; }

# 需要限制内存就加在这一行（默认不限制，用 JVM 默认值）：
#   -Xms1G -Xmx3G
exec "$JAVA" \
  --add-opens java.base/java.net=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  -javaagent:"$AGENT" \
  -jar "$SERVER" \
  nogui
