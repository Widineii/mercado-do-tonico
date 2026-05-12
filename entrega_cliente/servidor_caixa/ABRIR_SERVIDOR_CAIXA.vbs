' Lancador silencioso do Servidor/Caixa principal da Mercearia do Tunico.
Option Explicit

Dim shell, fso, scriptDir, cmd
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)
shell.CurrentDirectory = scriptDir

cmd = "cmd.exe /c " & Chr(34) & Chr(34) & scriptDir & "\ABRIR_SERVIDOR_CAIXA.bat" & Chr(34) & Chr(34)
shell.Run cmd, 0, False
