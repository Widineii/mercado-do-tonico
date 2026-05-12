@echo off
setlocal
cd /d "%~dp0"

if not exist "backups" (
  echo Pasta backups nao encontrada.
  pause
  exit /b 1
)

set "latest="
for /f "delims=" %%F in ('dir /b /a-d /o-d "backups\mercado-tunico_*.db" 2^>nul') do (
  set "latest=%%F"
  goto :found
)
for /f "delims=" %%F in ('dir /b /a-d /o-d "backups\mercado-tonico_*.db" 2^>nul') do (
  set "latest=%%F"
  goto :found
)

echo Nenhum backup encontrado em backups\mercado-tunico_*.db nem mercado-tonico_*.db
pause
exit /b 1

:found
echo Backup mais recente: %latest%
set /p confirm="Confirmar restore para data\mercado-tunico.db? (S/N): "
if /I not "%confirm%"=="S" (
  echo Operacao cancelada.
  pause
  exit /b 0
)

if not exist "data" mkdir data
copy /y "backups\%latest%" "data\mercado-tunico.db" >nul
if errorlevel 1 (
  echo Falha no restore.
  pause
  exit /b 1
)

echo Restore concluido com sucesso.
pause
