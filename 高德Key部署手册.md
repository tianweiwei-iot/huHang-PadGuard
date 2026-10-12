# 高德 Key 申请 · 注入 · 部署手册（PadGuard 定位功能 P-L1/P-L2）

> 适用范围：`E:\PCS\V1.0.261012` 仓库（远程 `huHang-PadGuard@main`）。
> 目的：把高德「Web 服务 Key（逆地理）」与「Android 地图 Key（底图）」申请、注入、部署成一份可复用的操作手册。
> 原则：Key 不进代码仓库；服务端 Key 由环境变量注入，客户端 Key 由 Gradle 属性注入；无 Key 时全部回退占位/坐标，功能不中断。

---

## 〇、现状与前置结论（已实证）

- 服务端注入点：`padguard-server/src/main/resources/application.yml`
  `padguard.amap.web-key: ${PADGUARD_AMAP_WEB_KEY:}` ✅
- 家长端注入点：`护航控制端/app/build.gradle.kts`
  `val amapKey = findProperty("AMAP_KEY") ?: findProperty("amapKey")` → `manifestPlaceholders["AMAP_KEY"]`；默认空串即回退占位底图 ✅
- 当前 `padguard-server/deploy/app.jar` 不存在，需从 P-L1/P-L2 源码重新 `bootJar` 产出。
- 本机构建环境：JDK 17 位于
  `C:/Users/Administrator/.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.20.1+1`
- 检测到一个 `java.exe` 进程（部署前需先确认是否为当前服务端实例，再决定是否覆盖）。

---

## 一、申请高德 Key（手动，约 10 分钟）

入口：高德开放平台 `https://console.amap.com`。**第一步必须完成实名认证**，否则 Web 服务 Key 无法放行。
在「应用管理 → 我的应用」下创建一个应用，再为它添加两个 Key。

### Key 1 — Web 服务 Key（逆地理编码，服务端调用）

- 类型选 **「Web 服务」**，服务平台选「Web 服务」。
- 用途：服务端 `GeoCoderService` 调用高德逆地理编码，把经纬度反查为文字地址（"XX 路 XX 号"）。
- 安全（重要）：该 Key 在后端调用，**务必设置「IP 白名单」= 服务端公网出口 IP**。泄露后会被他人刷配额/计费。

### Key 2 — Android 地图 Key（真实底图，家长端用）

- 类型选 **「Android 平台」**。
- 绑定：**包名 `com.padguard.parent`** + **签名 SHA1**。
- 取 SHA1（debug 自测用默认签名）：

```bash
export JAVA_HOME="C:/Users/Administrator/.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.20.1+1"
"$JAVA_HOME/bin/keytool.exe" -list -v \
  -keystore "$USERPROFILE/.android/debug.keystore" \
  -alias androiddebugkey -storepass android
# 在"证书指纹"段找到 SHA1: XX:XX:XX... 整串复制填入控制台
```

- 发布版：必须用你正式签名 keystore 的 SHA1（alias/密码换成自有值）。
- 高德允许同一 Key 绑定多个 SHA1，**建议把 debug + release 两个 SHA1 都加进 Key 2**，避免调试/发包切换时底图突然不加载。

---

## 二、注入与构建部署

### 2.1 服务端（构建 + 注入 + 启动）

```bash
export JAVA_HOME="C:/Users/Administrator/.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.20.1+1"
export GRADLE_OPTS="-Dfile.encoding=UTF-8"
cd "/e/PCS/V1.0.261012/padguard-server"
./gradlew bootJar -x test -Dfile.encoding=UTF-8 --console=plain
mkdir -p deploy && cp build/libs/app.jar deploy/app.jar
```

**注入 Key（进程启动时读取，不能热注入；改 Key 必须重启）：**

```bash
# 方式 A（推荐，符合方案约定）：OS 环境变量
export PADGUARD_AMAP_WEB_KEY="你的Web服务Key"
"$JAVA_HOME/bin/java.exe" -jar deploy/app.jar --spring.profiles.active=home

# 方式 B（临时验证）：直接用 Spring 参数覆盖
"$JAVA_HOME/bin/java.exe" -jar deploy/app.jar --spring.profiles.active=home \
  --padguard.amap.web-key="你的Web服务Key"
```

### 2.2 家长端（构建 + 注入）

```bash
export JAVA_HOME="C:/Users/Administrator/.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.20.1+1"
export GRADLE_OPTS="-Dfile.encoding=UTF-8"
cd "/e/PCS/V1.0.261012/护航控制端"
./gradlew :app:assembleDebug -PAMAP_KEY="你的AndroidKey" -Dfile.encoding=UTF-8 --console=plain
# 产物：app/build/outputs/apk/debug/app-debug.apk → 安装到平板/手机验证
```

> 备选：在 `gradle.properties` 写 `amapKey=xxx`（小写亦识别），可免去每次传 `-PAMAP_KEY`。
> 注意属性名必须是 `AMAP_KEY` 或 `amapKey`，`build.gradle.kts` 内 `findProperty("AMAP_KEY")` 区分大小写。

### 2.3 覆盖运行实例（高风险，需显式确认）

```powershell
# 1) 先确认目标 PID 确为服务端（看命令行是否含 app.jar）
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Select-Object ProcessId, CommandLine
```

确认后停止旧实例 → 拷新 jar → 重启 → 轮询健康：

```bash
# 2) 停止旧实例（仅在你确认 PID 是服务端后）
taskkill /F /PID <旧PID>

# 3) 用新 jar 覆盖
#    务必从 deploy/app.jar 启动，勿直接跑 build/libs/app.jar
#    （否则 Gradle 重建会覆盖运行中的 jar，形成僵尸实例）
"$JAVA_HOME/bin/java.exe" -jar deploy/app.jar --spring.profiles.active=home > server.log 2>&1 &

# 4) 轮询直到返回 UP
curl -s http://127.0.0.1:8090/health
```

---

## 三、无 Key 回退验证（功能不中断）

- **服务端无 `PADGUARD_AMAP_WEB_KEY`**：`web-key` 为空 → `GeoCoderService` 直接返回 `null`，落库 `address` 为空；UI 显示"未知地址"，**经纬度、精度、时间、围栏、轨迹照常展示**。
- **家长端未传 `-PAMAP_KEY`**：`readAmapKey()` 读到空串 → `LocationScreen` 回退 `GeofenceMapCanvas` 等距投影占位图，**不加载真实底图、不崩溃**。
- 验证手段：不设任何 Key 各起一次，定位页应正常显示坐标与占位圈；设 Key 后真机显示真实地图 + 文字地址。

---

## 四、关键风险点

1. **环境变量在进程启动时读取**，运行时改值不生效——换 Key 或首次注入都必须重启服务端。
2. **Android Key 绑 SHA1**：debug 与 release 签名不同；用 debug 自测、release 发包却只绑了 debug SHA1，会导致正式包底图不加载。按「双 SHA1」处理最稳。
3. **Web 服务 Key 配额/IP 白名单**：服务端调用，建议限 IP + 关注日调用额度，避免被刷。
4. **覆盖运行实例属高风险操作**：执行前须确认目标 PID 就是服务端，并确认允许停机窗口。

---

## 五、责任边界

- 申请高德 Key 必须由本人登录控制台完成（涉及账号实名与密钥归属）。
- 构建/部署动作可由运维在获得 Key 与覆盖授权后执行。
- 本手册不含任何真实 Key 值，所有 `xxx` 占位均需在操作时替换。
