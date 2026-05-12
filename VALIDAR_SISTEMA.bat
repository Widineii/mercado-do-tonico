@echo off
setlocal
cd /d "%~dp0"

echo [1/4] Rodando testes automatizados...
call .\mvnw.cmd -q test
if errorlevel 1 (
  echo Falha nos testes.
  pause
  exit /b 1
)

echo [2/4] Gerando build final (desktop)...
call .\mvnw.cmd -q -DskipTests clean package
if errorlevel 1 (
  echo Falha no build.
  pause
  exit /b 1
)

echo [3/4] Garantindo pastas operacionais...
if not exist "data" mkdir data
if not exist "data\logs" mkdir data\logs
if not exist "backups" mkdir backups

echo [4/4] Verificacao concluida.
echo - Testes: OK
echo - Build: OK
echo - Pastas operacionais: OK
echo.
echo Proximo passo: abrir ABRIR_SERVIDOR_CAIXA.vbs para validacao manual da interface.
pause
