' PadGuard server - silent background start (no console window, cannot be closed by accident)
Set fso = CreateObject("Scripting.FileSystemObject")
Set ws = CreateObject("WScript.Shell")
ws.CurrentDirectory = fso.GetParentFolderName(WScript.ScriptFullName)
ws.Run "cmd /c start-server.bat", 0, False
