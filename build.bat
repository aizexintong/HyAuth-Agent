@echo off
setlocal
cd /d "%~dp0"

rem ============================================================
rem  HyAuth-Agent 一键构建脚本
rem  依次尝试：
rem    1) PATH 中的 mvn
rem    2) 项目内的 tools\apache-maven-3.9.9（免安装、免改 PATH）
rem    3) 打印手动安装指引
rem  用法: build.bat          等价于 mvn clean package
rem        build.bat -DskipTests
rem ============================================================

set "JAVA_PATH=C:\Users\liu22\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon\bin\java.exe"
if not exist "%JAVA_PATH%" set "JAVA_PATH=java"

set "LOCAL_MVN=%~dp0tools\apache-maven-3.9.9"

where mvn >nul 2>nul
if not errorlevel 1 goto :use_path_mvn
if exist "%LOCAL_MVN%\bin\m2.conf" goto :use_local_mvn
goto :no_maven

:use_path_mvn
echo [build] 使用 PATH 中的 Maven 构建...
call mvn clean package %*
goto :result

:use_local_mvn
echo [build] 使用项目内 Maven: %LOCAL_MVN%
"%JAVA_PATH%" ^
  "-Dclassworlds.conf=%LOCAL_MVN%\bin\m2.conf" ^
  "-Dmaven.home=%LOCAL_MVN%" ^
  "-Dmaven.multiModuleProjectDirectory=%CD%" ^
  -classpath "%LOCAL_MVN%\boot\plexus-classworlds-2.8.0.jar" ^
  org.codehaus.plexus.classworlds.launcher.Launcher clean package %*
goto :result

:no_maven
echo.
echo [build] 未找到可用的 Maven，请任选一种方式：
echo.
echo   方式 1^(推荐^)：安装 Maven 并加入 PATH
echo       https://maven.apache.org/download.cgi
echo.
echo   方式 2：把 Maven 解压到本项目的 tools\ 目录（免安装、免改 PATH）
echo       下载 https://repo1.maven.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip
echo       解压后应得到： tools\apache-maven-3.9.9\bin\m2.conf
echo       然后重新运行本脚本即可。
echo.
pause
exit /b 1

:result
echo.
if exist "target\HyAuth-Agent-1.0.0.jar" (
    echo [build] 构建成功，产物: %CD%\target\HyAuth-Agent-1.0.0.jar
    echo [build] 把该 jar 与 start.bat 一起复制到服务端根目录即可。
) else (
    echo [build] 构建失败，请查看上方 Maven 输出。
)
echo.
pause
endlocal
