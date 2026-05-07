@echo off
cd /d "%~dp0"
if not exist "target\mercado-do-tonico-1.0.0.jar" (
  call mvnw.cmd -q -DskipTests package
)
java --enable-native-access=ALL-UNNAMED -jar target\mercado-do-tonico-1.0.0.jar
pause
