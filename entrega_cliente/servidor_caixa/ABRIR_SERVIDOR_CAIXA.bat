@echo off
setlocal
cd /d "%~dp0"

set MERCADO_DB_URL=jdbc:sqlite:data/mercado-tunico.db

if not exist "target\mercado-do-tunico-1.0.0.jar" (
  call mvnw.cmd -q -DskipTests clean package -Pweb
)

java --enable-native-access=ALL-UNNAMED -jar target\mercado-do-tunico-1.0.0.jar
pause
