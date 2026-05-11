' Lancador silencioso do Mercado do Tonico (Desktop Swing).
' Equivalente a ABRIR_MERCADO_TONICO_DESKTOP.bat, porem sem abrir
' a janela do cmd. Use este .vbs no lugar do .bat quando NAO quiser
' ver o terminal.
Option Explicit

Dim shell, fso, scriptDir, cmd
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)
shell.CurrentDirectory = scriptDir

' Executa mvnw.cmd com janela oculta (0) e sem esperar o termino (False).
' Quando o app Swing fechar, o processo do mvnw tambem termina sozinho.
cmd = "cmd.exe /c " & Chr(34) & Chr(34) & scriptDir & "\mvnw.cmd" & Chr(34) & _
      " -q -DskipTests exec:java" & Chr(34)
shell.Run cmd, 0, False
