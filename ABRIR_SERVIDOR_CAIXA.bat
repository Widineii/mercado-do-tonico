@echo off
setlocal
cd /d "%~dp0"

set MERCADO_DB_URL=jdbc:sqlite:data/mercado-tonico.db

set "APP_JAR=mercado-do-tonico-1.0.0.jar"
if not exist "%APP_JAR%" set "APP_JAR=mercado-do-tunico-1.0.0.jar"
if not exist "%APP_JAR%" if exist "target\mercado-do-tunico-1.0.0.jar" set "APP_JAR=target\mercado-do-tunico-1.0.0.jar"
if not exist "%APP_JAR%" if exist "mvnw.cmd" (
  call mvnw.cmd -q -DskipTests clean package
  if exist "target\mercado-do-tunico-1.0.0.jar" set "APP_JAR=target\mercado-do-tunico-1.0.0.jar"
)
if not exist "%APP_JAR%" (
  echo JAR do sistema nao encontrado.
  pause
  exit /b 1
)

java --enable-native-access=ALL-UNNAMED -jar "%APP_JAR%"
pause
