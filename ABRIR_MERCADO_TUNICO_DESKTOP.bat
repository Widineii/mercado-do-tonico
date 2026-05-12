@echo off
cd /d "%~dp0"
REM Lancador da Mercearia do Tunico (sistema desktop Swing).
REM Compila se necessario e abre a janela do app.
REM exec:java sozinho nao compila o projeto; sem compile ocorre ClassNotFoundException no DesktopApp.
call mvnw.cmd -q -DskipTests compile exec:java
if errorlevel 1 (
  echo Falha ao iniciar o desktop.
  echo.
  echo Feche outras janelas do sistema e tente novamente.
  echo Se continuar, pause a sincronizacao do OneDrive para esta pasta.
  pause
  exit /b 1
)
