@echo off
setlocal
if "%~1"=="--version" (
  type "%~dp0..\VERSION"
  exit /b 0
)
set "AGENT_JAVA=java"
if defined JAVA_HOME set "AGENT_JAVA=%JAVA_HOME%\bin\java.exe"
"%AGENT_JAVA%" -Dfile.encoding=UTF-8 -jar "%~dp0..\lib\agent-cli.jar" %*
exit /b %ERRORLEVEL%
