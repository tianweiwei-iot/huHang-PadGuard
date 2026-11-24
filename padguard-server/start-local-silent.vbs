' PadGuard server - silent, session-independent start (local profile / 8090 / H2 file DB)
'
' 为什么需要这个脚本：
'   从对话/终端的后台任务里拉起 java 时，进程归该会话管；会话结束或任务被回收，
'   8090 就没了（表现为 background task failed、/health 连不上）。这里用 WScript.Shell
'   以隐藏窗口直接拉起一个独立进程，不依附任何终端会话，关掉对话/IDE 也继续跑。
'
' 与 start-server-silent.vbs 的区别：
'   那个走 start-server.bat = home 发布档（每次启动会把测试号口令重置成随机强口令）；
'   这个固定走 local 联调档 + deploy/app.jar 稳定快照，与当前运行实例参数一致，
'   演示账号/测试账号可用，适合真机联调。
'
' 用法：双击本文件（或 wscript start-local-silent.vbs）。重复运行不会起第二个实例。
' 停止：任务管理器结束 java.exe，或 netstat -ano 找到 8090 的 PID 后 taskkill /PID <pid> /F
Option Explicit

Dim fso, ws, root, q, javaExe, jar, logFile, cmd
Set fso = CreateObject("Scripting.FileSystemObject")
Set ws  = CreateObject("WScript.Shell")
root = fso.GetParentFolderName(WScript.ScriptFullName)
q = Chr(34)

If ServerIsUp() Then
    WScript.Quit 0
End If

' 优先用与当前部署一致的 JDK 17；换机时回退到 PATH 里的 java
javaExe = "C:\Java\jdk-17\bin\java.exe"
If Not fso.FileExists(javaExe) Then javaExe = "java"

' 刻意跑 deploy/app.jar 而不是 build/libs/app.jar：后者会被下一次构建覆盖/占用，
' 前者是停服后复制出来的稳定快照（项目约定的部署方式）。
jar = root & "\deploy\app.jar"
If Not fso.FileExists(jar) Then
    MsgBox "未找到 " & jar & vbCrLf & "请先执行 gradlew bootJar 并复制到 deploy/app.jar", _
           16, "PadGuard 启动失败"
    WScript.Quit 1
End If

logFile = root & "\server-run-local.log"

' cmd /c 的最外层引号由 cmd 自行剥除；内层引号保护 java 路径、jar 路径与日志路径。
cmd = "cmd /c " & q & q & javaExe & q & _
      " -Xmx512m -XX:+ExitOnOutOfMemoryError" & _
      " -Dlogging.register-bridge-handler=false -Duser.timezone=Asia/Shanghai" & _
      " -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8" & _
      " -jar " & q & jar & q & _
      " --spring.profiles.active=local --server.port=8090" & _
      " --padguard.base-url=" & _
      " --padguard.demo-accounts-enabled=true" & _
      " --padguard.mqtt.enabled=false" & _
      " > " & q & logFile & q & " 2>&1" & q

ws.CurrentDirectory = root
ws.Run cmd, 0, False

' 已在监听就直接退出，避免重复实例抢 8090 且把 H2 文件库锁冲突写进日志
Function ServerIsUp()
    Dim http
    On Error Resume Next
    Set http = CreateObject("MSXML2.ServerXMLHTTP.6.0")
    If Err.Number <> 0 Then
        ServerIsUp = False
        On Error GoTo 0
        Exit Function
    End If
    http.setTimeouts 400, 400, 400, 400
    http.open "GET", "http://127.0.0.1:8090/health", False
    http.send
    ServerIsUp = (Err.Number = 0 And http.status = 200)
    On Error GoTo 0
End Function
