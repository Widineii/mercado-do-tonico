@echo off
cd /d "%~dp0"
REM Lancador do Mercado do Tonico (sistema desktop Swing).
REM Compila se necessario e abre a janela do app.
call mvnw.cmd -q -DskipTests exec:java
if errorlevel 1 (
  echo Falha ao iniciar o desktop.
  echo.
  echo Feche outras janelas do sistema e tente novamente.
  echo Se continuar, pause a sincronizacao do OneDrive para esta pasta.
  pause
  exit /b 1
)
