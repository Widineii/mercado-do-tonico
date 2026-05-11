@echo off
setlocal
cd /d "%~dp0"

set MERCADO_DB_URL=jdbc:sqlite:data/mercado-tonico.db

if not exist "target\mercado-do-tonico-1.0.0.jar" (
  call mvnw.cmd -q -DskipTests clean package
)

java --enable-native-access=ALL-UNNAMED -jar target\mercado-do-tonico-1.0.0.jar
pause
