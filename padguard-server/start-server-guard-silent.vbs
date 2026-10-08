' PadGuard server guard - silent start (no window at all, cannot be closed by accident)
' Keeps the server alive: checks every 15 seconds, restarts it if it stops responding.
Set fso = CreateObject("Scripting.FileSystemObject")
Set ws = CreateObject("WScript.Shell")
ws.CurrentDirectory = fso.GetParentFolderName(WScript.ScriptFullName)
ws.Run "powershell -NoProfile -ExecutionPolicy Bypass -File server-guard.ps1", 0, False
