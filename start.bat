@echo off
setlocal
title Minecraft Server (HyAuth-Agent)
cd /d "%~dp0"

rem ============================================================
rem  HyAuth-Agent 服务端启动脚本
rem  1) 指定 Java 运行时（默认使用文档中的 Java 25 运行时路径）
rem  2) 通过 -javaagent 挂载鉴权切面
rem  3) --add-opens:
rem     java.base/java.net  / java.util  —— 现代 JVM 强封装突破
rem     java.base/java.lang             —— 【必需】原版 server.jar 是 Mojang Bundler，
rem                                        它用「父加载器为 platform 的 URLClassLoader」加载
rem                                        服务端类；Agent 需要反射调用 ClassLoader#defineClass
rem                                        把自身辅助类注入该加载器，缺此开关登录会失败。
rem  适用版本：Minecraft 1.18 ~ 26.3（原版 server.jar，无需任何 Mod/插件）
rem ============================================================

set "JAVA_PATH=C:\Users\liu22\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon\bin\java.exe"
if not exist "%JAVA_PATH%" set "JAVA_PATH=java"

set "AGENT_JAR=HyAuth-Agent-1.0.0.jar"
if not exist "%AGENT_JAR%" (
    echo [HyAuth] 未找到 %AGENT_JAR%，请先执行 mvn clean package 并把 target\HyAuth-Agent-1.0.0.jar 复制到本目录。
    pause
    exit /b 1
)

if not exist "server.jar" (
    echo [HyAuth] 未找到 server.jar，请将本脚本与 Agent 一起放到服务端根目录。
    pause
    exit /b 1
)

"%JAVA_PATH%" ^
  -Xms4G -Xmx8G ^
  --add-opens java.base/java.net=ALL-UNNAMED ^
  --add-opens java.base/java.lang=ALL-UNNAMED ^
  --add-opens java.base/java.util=ALL-UNNAMED ^
  -javaagent:%AGENT_JAR% ^
  -jar server.jar ^
  nogui

echo.
echo [HyAuth] 服务端已退出，退出码: %ERRORLEVEL%
pause
endlocal
