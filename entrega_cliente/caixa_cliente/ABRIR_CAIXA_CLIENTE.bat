@echo off
setlocal
cd /d "%~dp0"

if not exist "config" mkdir config

if not exist "config\desktop.properties" (
  (
    echo # Exemplo: mercado.db.url=jdbc:sqlite:\\\\SERVIDOR\mercado\data\mercado-tonico.db
    echo mercado.db.url=
  ) > "config\desktop.properties"
  echo Configure o arquivo config\desktop.properties com o caminho de rede do banco.
  echo Exemplo: mercado.db.url=jdbc:sqlite:\\\\SERVIDOR\mercado\data\mercado-tonico.db
  pause
  exit /b 1
)

for /f "tokens=1,* delims==" %%A in ('findstr /b /i "mercado.db.url=" "config\desktop.properties"') do set MERCADO_DB_URL=%%B
if "%MERCADO_DB_URL%"=="" (
  echo mercado.db.url vazio em config\desktop.properties
  pause
  exit /b 1
)

set "APP_JAR=mercado-do-tonico-1.0.0.jar"
if not exist "%APP_JAR%" set "APP_JAR=mercado-do-tunico-1.0.0.jar"
if not exist "%APP_JAR%" if exist "target\mercado-do-tunico-1.0.0.jar" set "APP_JAR=target\mercado-do-tunico-1.0.0.jar"
if not exist "%APP_JAR%" (
  echo JAR do sistema nao encontrado.
  pause
  exit /b 1
)

java --enable-native-access=ALL-UNNAMED -jar "%APP_JAR%"
pause
