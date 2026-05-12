@echo off
setlocal
cd /d "%~dp0"
set "SRC=data\mercado-tonico.db"
if not exist "%SRC%" (
  echo Banco nao encontrado em data\mercado-tonico.db
  pause
  exit /b 1
)
if not exist "backups" mkdir backups
set stamp=%date:~6,4%-%date:~3,2%-%date:~0,2%_%time:~0,2%-%time:~3,2%-%time:~6,2%
set stamp=%stamp: =0%
copy "%SRC%" "backups\mercado-tonico_%stamp%.db" >nul
echo Backup criado em backups\mercado-tonico_%stamp%.db
pause
