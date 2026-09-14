# 护航成长 · 被管控端（PadGuard Child）APK
## 产品功能设计说明书 与 AI 智能开发文档

> 文档版本：v1.0 ｜ 编制日期：2026-09-13 ｜ 编制角色：产品规划（30+ 年）／系统架构
> 关联系统：护航管控端 **PadGuard Parent**（包名 `com.padguard.parent`）、护航服务端（REST `https://api.padguard.cn`、MQTT `ssl://mqtt.padguard.cn:8883`）
> 被管控端包名：`com.padguard.child`（工程名 `PadGuardChild`）
> 目标平台：Android 8.0（API 26）～ Android 15（API 35），`compileSdk=35` `minSdk=26` `targetSdk=35`，JVM/字节码级别 17

---

# 第一部分　产品功能设计说明书

## 0. 文档范围与定位

本说明书定义「被管控端」APK 的完整功能集合与详细参数，作为产品验收与 AI 辅助开发的唯一基线。设计遵循两条硬约束：

1. **Device Owner（设备所有者 / DPC）运行模型**：被管控端以设备所有者身份激活，从系统层获得设备级管控能力（锁屏、应用管控、Kiosk、权限自动授予、防卸载/防恢复出厂），符合《移动互联网未成年人模式建设指南》「未成年人模式不可被卸载、冻结、强制终止」的要求。
2. **与管控端数据互通**：被管控端不直连家长手机，统一经护航服务端中转；管控端下发的策略、被管控端上报的事件使用同一套 MQTT 主题与消息契约（见第二部分 §8）。

---

## 1. 行业调研：主流品牌被管控端能力盘点

### 1.1 能力对比矩阵

| 品牌 / 产品 | 时长管理 | 应用管控 | 上网/内容 | 位置/围栏 | 远程锁屏/关机 | 截屏/录屏 | 远程安装/卸载 | 护眼/姿态 | 防绕过 | Kiosk/学习空间 | 消息/通信 | 报告/日志 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 华为（健康使用平板·教育中心·家长助手） | 每日总时长、分应用时长、停用时段 | 应用限制、下载需密码 | 网站黑名单、安全搜索 | 实时位置 | 远程锁屏 | 家长端实时截屏 | 远程审批安装 | 蓝光、亮度自适应、坐姿提醒（6 类） | 独立家长密码 | 学生桌面系统级隔离 | — | 使用报告 |
| 小米（数字健康与家长控制·家人守护） | 每日上限、可用时段 | 应用限额、封禁 | 网站限制、安全搜索 | — | 远程锁屏 | — | — | 护眼模式 | — | 儿童空间 | — | 使用报告 |
| OPPO（儿童空间/学习空间） | 时长、时段 | 白名单、可用应用 | 网站过滤 | — | 锁屏 | — | — | 护眼 | — | 学习空间 | — | — |
| vivo（健康使用设备·孩子守护） | 时长、时段 | 应用管控 | 网站限制 | 实时位置 | 远程锁屏 | — | 远程管控 | 护眼 | — | 孩子守护 | — | 报告 |
| 步步高家教机 + 家长管理 | 时长、应用使用 | 安装/使用/时段管控 | 网站黑白名单 | 位置 | 远程锁屏/关闭 | 实时截屏 | 一键远程下载应用 | — | 家长身份校验 | — | — | 安全日志 |
| 读书郎 + 家长助手 | 时长 | 应用管控 | 绿网 | 位置 | 锁屏 | — | 远程装应用 | — | — | — | — | 学情 |
| 优学派家长管理 | 时长、时段 | 安装/使用审批、应用隐藏 | 浏览器黑白名单（第三方通用） | 位置 | 远程锁屏/关平板 | 实时截屏 | 远程卸载 | — | 家长指纹、改管控需验证 | — | — | 安全日志 |
| 小度/小课屏 | 时长 | 应用池筛选 | 内容过滤 | — | 锁屏 | — | — | 类纸屏、距离传感 | — | 封闭教育系统 | — | 学情 |
| 青葱守护/依蛋守护 | 时长、学习模式 | 应用+时长 | 黄赌毒拦截 | 实时位置+电子围栏 | 远程锁屏 | 远程截屏 | — | — | — | — | — | 使用报告 |
| AirDroid Parental Control (Kids) | 每日事件、应用限额 | 即时禁用、应用管理 | 浏览器管理、敏感词、不良图像侦测 | 实时位置 | 一键锁屏 | 实时画面/截屏 | — | — | — | — | 通知代收 | 使用报告 |
| Google Family Link | 每日上限、上学/休息时段 | 应用限额、封禁、安装审批 | 网站限制、SafeSearch | 实时位置、到达/离开提醒、响铃 | 远程锁设备 | 不支持屏幕监控 | Play 审批 | — | 监管账户 | — | 通话/短信管理 | 使用报告 |
| Microsoft Intune | 策略时段 | 应用保护/黑白名单 | 浏览器托管 | 合规位置 | 远程锁/擦除 | — | 静默安装/更新/删除 | — | 合规策略、防 root | 多应用 Kiosk | — | 合规报表 |
| VMware Workspace ONE | 策略 | MAM 黑白名单、托管配置 | Web 过滤 | GPS/地理围栏 | 远程锁/擦除/丢失模式 | 远程查看/控制 | 静默分发/更新 | — | 防 root/jailbreak | 单/多应用 Kiosk | — | 资产/合规 |
| SOTI MobiControl | 策略 | 应用生命周期、托管配置 | Web 过滤 | 实时 GPS、地理围栏 | 远程锁/擦除/丢失模式 | 远程查看/控制、黑屏模式 | 静默安装/卸载/更新 | — | 防 root、合规锁 | 单/多应用 Kiosk、锁屏品牌 | 消息广播 | 资产/使用/日志 |
| ManageEngine MDM | 策略 | 应用黑白名单、静默部署 | Web 过滤 | GPS、地理围栏 | 远程锁/擦除 | 远程控制/截屏 | 静默安装/卸载/更新 | — | 防 root、合规 | Kiosk | — | 资产/报表 |
| Scalefusion | 策略 | 应用生命周期、Web 应用 | Web 过滤 | 地理围栏/轨迹 | 远程锁/重启/擦除 | 远程控制/截屏/黑屏 | 静默安装/更新 | — | 防 root、限制 | 单/多应用 Kiosk | 文件分发 | 仪表盘 |
| AirDroid Business | 策略 | AMS 应用管理 | — | GPS、地理围栏 | 远程锁/擦除/重启 | 远程控制、摄像头、截屏 | 静默安装/更新 | — | — | 单/多应用 Kiosk | 文件分发、短信 | 报表/日志 |

### 1.2 调研结论（能力基线）

市场规模覆盖「消费家长控制」与「企业 MDM」两端。消费端强在**时长/应用/上网/位置/护眼**，弱在**远程截屏录屏/静默安装/防绕过**；企业 MDM 强在**远程控制、静默运维、Kiosk、地理围栏、防绕过**。本产品采用 **Device Owner 模型**，可将两端能力统一到一台「学习平板」上，能力基线取二者并集（见 §3、§6）。

---

## 2. 产品定位与运行模型

- **运行身份**：设备所有者（Device Owner / DPC）。激活方式（部署阶段二选一）：
  1. 出厂/首次开机通过 **QR / NFC 配置**（Android Enterprise Device Owner Provisioning）；
  2. 已开机设备通过 `dpm set-device-owner` 或厂商预置。
- **前台守护**：`GuardService`（前台服务，保证常驻与系统优先级），负责 MQTT 长连、策略执行、心跳、事件采集。
- **策略执行**：`DevicePolicyManager` + `UserManager` 限制 + 运行时权限自动授予（DPC 特权）。
- **通信**：MQTT（控制/事件/遥测，已选型 Eclipse Paho mqttv3）+ REST（固件/资源/大文件/绑定注册）。
- **存储**：`Room`（策略/事件/日志）、`DataStore`（偏好/绑定态）、`WorkManager`（定时上报/合规检查）。
- **时间基准**：复用 `TimeProvider`（基于 `SystemClock.elapsedRealtime`，`serverOffsetMs` 与服务端校准，检测本地时间篡改——防「改系统时间绕开锁机」）。

---

## 3. 被管控端能力总览（取行业并集）

| 域 | 一级能力 | 二级能力（节选） |
|---|---|---|
| A 设备与状态 | 设备信息上报 | 硬件型号/系统版本/序列号/IMEI(脱敏)/电池/存储/网络/信号/温度/CPU/内存 |
| B 时间与专注 | 时长与时段 | 每日总时长、分应用时长、连续使用上限+强制休息、上学锁、休息时段、考试超级锁机 |
| C 应用治理 | 应用管控 | 白/黑名单、安装审批、远程静默安装/卸载/更新、应用冻结/隐藏、应用使用时段 |
| D 内容与网络 | 上网管理 | 网址黑白名单、搜索引擎安全搜索、广告/弹窗过滤、应用内功能管控、敏感词/不良图像检测 |
| E 位置与安全 | 位置与围栏 | 实时定位、历史轨迹、电子围栏、到达/离开告警、低电量离线策略 |
| F 远程控制 | 锁屏/重启/关机 | 实时锁屏、远程解锁、远程重启、远程关机、丢失模式(Lost Mode) |
| G 远程可视 | 截屏/录屏 | 远程截屏、实时画面（流式）、远程录屏、黑屏模式 |
| H 远程运维 | 安装/文件/清除 | 远程装/卸/更新应用、文件分发、配置下发、恢复出厂(授权)、远程响铃 |
| I 通信消息 | 消息 | 信息发送、通知/短信代收转发、公告广播滚动、家长留言 |
| J 健康护眼 | 护眼与姿态 | 蓝光过滤、亮度自适应、距离/坐姿监测(摄像头)、使用姿势提醒 |
| K 防绕过 | 加固 | 不可卸载、防强制停止、防恢复出厂(需授权)、禁 USB 调试/未知来源、root 检测、开发者选项禁用、家长生物验证、时间篡改检测 |
| L 空间 | Kiosk/学习空间 | 单/多应用 Kiosk、学习桌面、应用池、品牌壁纸 |
| M 数据 | 报告/日志 | 使用报告、TOP 应用、学习进度、安全日志、异常告警 |
| N 身份 | 多端/验证 | 指纹/人脸验证管控变更、多管控端、子账号 |

---

## 4. 首次启动与绑定流程（详细）

### 4.1 启动状态机

```
[冷启动]
   └─ 是否 Device Owner？ ──否──► 进入「激活引导」(引导走 QR/NFC 部署，详见 §4.4)
        │是
        ▼
   是否已完成绑定？──是──► 进入「主控桌面 / Kiosk 学习空间」
        │否
        ▼
   进入「绑定引导页」── 显示大字号：请绑定管控端
```

### 4.2 绑定引导页（首屏）UI 参数

| 元素 | 规格 |
|---|---|
| 主标题 | 「请绑定管控端」 ，字号 ≥ 28sp，居中 |
| 副标题 | 「扫码或在管控端获取绑定码，绑定后由家长远程守护」 |
| 绑定入口 | ≥ 3 种，并列卡片：① 扫码绑定 ② 输入绑定码 ③ NFC 碰一碰（④ 可选：声波/蓝牙近场） |
| 设备指纹展示 | 设备型号 + 后 4 位 SN（便于家长端核对） |
| 重试/帮助 | 「绑定失败？查看帮助」入口 |

### 4.3 绑定方式（≥3 种，参数级）

**① 扫码绑定（主推）**
- 管控端生成二维码，内容：`padguard://bind?code={BIND_CODE}&device={OPTIONAL}&exp={EXP}`。
- `BIND_CODE`：8 位大写字母数字，有效期 **300s**，单次使用。
- 被管控端调用 `MLKit` / `ZXing` 扫码 → 解析 → 调用 `POST /v1/device/bind` 传 `{bindCode, deviceFingerprint, osVersion, model}`。
- 服务端校验 → 返回 `deviceId`、`accessToken`、`mqttUsername/password`、`policySnapshot`。

**② 输入绑定码**
- 管控端「添加设备」页展示 8 位 `BIND_CODE`；被管控端输入框：正则 `^[A-Z0-9]{8}$`，错误上限 **5 次/10 分钟** 触发限流。
- 提交走同一 `/v1/device/bind`。

**③ NFC 碰一碰**
- 管控端开启 NFC「写标签/对等」模式，写入上述 `padguard://bind?...` NDEF 记录；被管控端前台 `NfcAdapter.enableReaderMode` 读取 → 同 ① 流程。

**④（可选）声波/蓝牙近场**
- 声波：管控端播放 2–4kHz 编码（如 `chirp`）；被管控端 `AudioRecord` 解调 → 取 `BIND_CODE`。蓝牙：管控端广播 `BluetoothLeAdvertiser` 含 `BIND_CODE`；被管控端扫描连接。

### 4.4 设备所有者激活（部署阶段，先于绑定）

- QR 配置：`{ "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.padguard.child/.receiver.PadGuardDeviceAdminReceiver", "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": "...", "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": { "bindCode": "..." } }`。
- 首次开机「设置向导」扫码即完成 Device Owner + 预置 `bindCode`，进入 §4.2 自动完成绑定。

### 4.5 绑定成功后时序

```
被管控端 ──POST /v1/device/bind──► 服务端 ──校验 bindCode──► 创建绑定关系
   ◄── deviceId + tokens + policy ──┘
被管控端 ──MQTT CONNECT(mqtt.padguard.cn:8883, TLS, 双向证书/用户名密码)──► 服务端
   └─ 发布 EVT_BOUND ──► 管控端收到「设备已上线」
   └─ 拉取全量策略(policySnapshot) + 启动 GuardService + 进入 Kiosk
```

### 4.6 绑定状态（DataStore 键）

| 键 | 类型 | 说明 |
|---|---|---|
| `bound` | Boolean | 是否已绑定 |
| `deviceId` | String | 服务端设备 ID |
| `bindToken` | String(加密) | 访问令牌 |
| `mqttUser/mqttPwd` | String(加密) | MQTT 凭据 |
| `pairedParentId` | String | 绑定的管控端用户 ID |
| `activatedAt` | Long | 绑定时间戳（校准后） |

---

## 5. 使用授权协议弹窗（合规）

### 5.1 触发时机
绑定成功后、**自动授予权限前**，模态弹窗（不可后台跳过）。标题：「使用授权协议」。

### 5.2 协议要点（必须明示授权项，节选）
> 为保障家长（管控端）对设备的远程守护能力，您同意后，本应用将**获取并使用被管控端的全部权限**，包括但不限于：
> - 设备信息读取（型号、系统、序列号、存储、电池、网络状态）；
> - 位置信息（GPS/基站/WiFi 实时定位与历史轨迹）；
> - 应用使用统计与管控（安装、卸载、冻结、隐藏、时长与时段限制）；
> - 通信相关（读取/发送短信、通知代收、通话记录读取——按系统授权范围）；
> - 摄像头与麦克风（姿态/距离监测、远程截屏/录屏/实时画面）；
> - 存储读写（日志、截图、下发文件）；
> - 远程控制（锁屏、重启、关机、丢失模式、恢复出厂——需家长二次授权）；
> - 网络与通知（公告广播、消息推送）。
> 所有数据经加密上传至护航服务端，仅绑定之管控端可查看与操作；设备将进入未成年人守护模式，部分系统能力（恢复出厂、USB 调试、未知来源安装）将被限制，需管控端授权方可解除。

### 5.3 交互与状态
| 操作 | 结果 |
|---|---|
| 点击「同意并授权」 | 关闭弹窗 → 触发 §6 权限自动授予流程 → 进入主控/Kiosk |
| 点击「暂不同意」 | 进入「受限预览模式」：仅展示绑定成功，禁用所有远程能力，可再次点开协议；**不授予任何高敏权限** |
| 连续拒绝 3 次 | 提示「未授权将无法提供守护能力」，仍允许稍后授权 |

### 5.4 协议留存
同意记录（版本号、时间戳、设备指纹、脱敏 SN）写入 `Room` 合规表，上报服务端，供管控端可查「已阅已同意」。

---

## 6. 功能模块详细设计（参数级）

> 每个模块含：功能点、详细参数、数据/指令、UI 提示。参数默认值可直接落入策略 JSON。

### 6.1 设备信息上报（域 A）

| 参数 | 默认值 | 范围 | 说明 |
|---|---|---|---|
| `report.intervalSec` | 300 | 30–3600 | 周期上报间隔 |
| `report.onChange` | true | — | 状态变化即时上报（充电/网络切换） |
| 采集项 | — | — | model, brand, osVersion, sdkInt, kernel, **serial(脱敏:仅后4位)**, **imei/meid(脱敏)**, MAC(脱敏), battery%(+,charging), storageTotal/Used, ramTotal/Used, cpuTemp, signalDbm, networkType, wifiSsid(脱敏), uptime, isRoot, isUsbDebug |

- 指令：`CMD_GET_DEVICE_INFO` → 立即上报 `EVT_DEVICE_INFO`。
- 隐私：所有硬件标识脱敏（哈希+盐），仅服务端可还原。

### 6.2 使用时长与时段（域 B）

| 参数 | 默认值 | 范围 | 说明 |
|---|---|---|---|
| `time.dailyLimitMin` | 120 | 0–1440（0=不限） | 每日总可用分钟 |
| `time.perAppLimitMin` | 30 | 0–1440 | 单应用每日上限（0=不限） |
| `time.continuousMaxMin` | 30 | 10–120 | 连续使用上限，到则强制休息 |
| `time.restMin` | 5 | 1–30 | 强制休息分钟 |
| `time.bedtimeStart` | 21:00 | — | 休息/停用开始 |
| `time.bedtimeEnd` | 07:00 | — | 停用结束（设备强制锁定） |
| `time.schoolLock` | 08:00–12:00,14:00–17:00 | — | 上学锁时段（可多段） |
| `time.examSuperLock` | off | — | 考试超级锁机（仅白名单应用+仅本地，断网可用） |
| `time.granularity` | 5min | — | 时长计算粒度（对齐行业 5 分钟） |
| `time.resetAt` | 00:00 | — | 每日计数重置时刻 |
| 提醒阈值 | 剩余 10/5 min + 超时 | — | 强提醒通知 |

- 实现：`UsageStatsManager` 查询用量；到点通过 `DevicePolicyManager.lockNow()` 或 Kiosk 覆盖层锁定。
- 防绕过：本地时间篡改由 `TimeProvider` 检测，漂移超 `DRIFT_TOLERANCE_MS=15min` 即告警并拒绝以墙钟计时。

### 6.3 应用管控（域 C）

| 参数 | 默认值 | 范围 | 说明 |
|---|---|---|---|
| `app.mode` | whitelist | whitelist/blacklist | 白名单=仅允许列表可运行；黑名单=列表禁用 |
| `app.whitelist` | [] | — | 允许包名集合（学习类） |
| `app.blacklist` | [] | — | 禁用包名集合（游戏/短视频） |
| `app.installApproval` | true | — | 新安装需管控端审批 |
| `app.autoUpdate` | false | — | 白名单应用静默更新 |
| `app.freezeApps` | [] | — | 冻结（禁用）列表 |
| `app.hiddenApps` | [] | — | Kiosk 下隐藏列表 |
| `app.perAppSchedule` | {} | — | 包名→可用时段映射 |
| `app.uninstallBlocked` | true | — | 阻止卸载（DPC `setUninstallBlocked`） |

- 指令：`CMD_INSTALL_APP`(payload: apkUrl/playId) → DPC 静默安装；`CMD_UNINSTALL_APP` → 静默卸载；`CMD_SET_APP_MODE` → 切换名单。
- 安装审批：被管控端本地安装意图拦截 → 上报 `EVT_INSTALL_REQUEST` → 管控端批准/拒绝 → `CMD_INSTALL_APPROVE/DENY`。

### 6.4 内容与上网管理（域 D）

| 参数 | 默认值 | 范围 | 说明 |
|---|---|---|---|
| `web.mode` | blacklist | blacklist/whitelist | 网址黑名单/白名单 |
| `web.blacklist`/`whitelist` | 内置库+自定义 | — | 域名/URL 规则，支持通配 `*.xxx.com` |
| `web.safeSearch` | enforce | off/soft/enforce | 搜索引擎安全搜索 |
| `web.adBlock` | true | — | 系统层广告过滤（VPN/本地代理或 DPC 托管浏览器） |
| `web.popupBlock` | true | — | 屏蔽弹窗 |
| `web.appFuncCtrl` | {} | — | 应用内功能管控，如 `com.tencent.mm: miniProgram=false, game=false` |
| `web.sensitiveWord` | 内置词库 | — | 社交/搜索敏感词检测 |
| `web.nsfwDetect` | true | — | 不良图像侦测（端侧 ML 轻模型） |
| 触发动作 | 拦截+上报 `EVT_CONTENT_BLOCKED`；敏感词/不良图 → `EVT_SAFETY_ALERT` |

- 实现：内置合规浏览器（Kiosk Browser，URL 白/黑名单+SafeSearch）；第三方浏览器经 VPN/本地代理统一过滤；`DevicePolicyManager` 可设默认浏览器与禁用非托管浏览器。

### 6.5 位置与电子围栏（域 E）

| 参数 | 默认值 | 范围 | 说明 |
|---|---|---|---|
| `loc.mode` | high | gps/network/high | 定位精度 |
| `loc.intervalSec` | 60 | 5–3600 | 实时上报间隔 |
| `loc.minDistanceM` | 50 | 0–1000 | 位移阈值才上报 |
| `loc.historyDays` | 30 | 1–90 | 轨迹保留天数 |
| `geo.fences` | [] | ≤20 | 围栏：{id,name,lat,lng,radiusM(默认500),enter/exit,notify} |
| `geo.notifyOnViolation` | true | — | 进出围栏告警 |
| `loc.lowBatteryStrategy` | 降频至 300s | — | 电量<15% 降频保活 |
| `loc.offlineCache` | true | — | 离线缓存轨迹，联网补传 |

- 指令：`CMD_GET_LOCATION` → 立即上报；`CMD_SET_GEOFENCE` → 增删围栏。
- 权限：DPC 自动授予 `ACCESS_FINE_LOCATION`；后台定位按系统策略保活。

### 6.6 远程锁屏/重启/关机（域 F）

| 参数 | 默认值 | 说明 |
|---|---|---|
| `lock.now` | — | `CMD_LOCK` → `lockNow()` 立即锁屏 |
| `unlock.now` | — | `CMD_UNLOCK` → 解除（仅白名单/学习空间内） |
| `reboot.now` | — | `CMD_REBOOT` → `DevicePolicyManager.reboot()`(API 24+) |
| `shutdown.now` | — | `CMD_SHUTDOWN` → `PowerManager`(系统特权) |
| `lost.mode` | off | `CMD_LOST_MODE` → 丢失模式：锁屏+定位常开+显示留言+禁用退出 |

- 失败回退：无系统特权时，经 `GuardService` 前台锁屏覆盖层实现等效锁屏。

### 6.7 远程截屏/录屏/实时画面（域 G）

| 参数 | 默认值 | 说明 |
|---|---|---|
| `capture.allow` | true | 协议授权后开启 |
| `screenshot.onDemand` | — | `CMD_SCREENSHOT` → MediaProjection 截一帧 → 上报 `EVT_SCREENSHOT`(图) |
| `screenStream.on` | — | `CMD_START_SCREEN_RECORD` → MediaProjection 流式(WebSocket/WebRTC) → 实时画面 |
| `screenRecord.local` | false | 本地录制留存(需家长二次授权) |
| `blackScreen.mode` | — | `CMD_BLACKSCREEN` → 运维时黑屏，用户不可见 |
| 延迟目标 | ≤800ms(实时画面, WiFi) | — |

- 合规：截屏/录屏经 **MediaProjection 一次性授权**（协议 §5 已涵盖）；DPC 可在学习空间内 `setScreenCaptureDisabled(true)` 防学生截屏。
- 传输：控制信令走 MQTT；实时画面走独立 WebSocket（`wss://api.padguard.cn/screen/{deviceId}`），帧率默认 8fps（可降）。

### 6.8 远程运维（域 H）

| 参数 | 默认值 | 说明 |
|---|---|---|
| 应用静默装/卸/更 | — | DPC `installExistingPackage`/`uninstall`/`setAppUpdatePolicy` |
| 文件分发 | — | `CMD_PUSH_FILE`(url+path) → 下载落 `E:/…/padguard/inbox`（脱敏目录） |
| 配置下发 | — | `CMD_SET_POLICY`(全量/增量) → 本地校验→生效→`EVT_POLICY_APPLIED` |
| 恢复出厂 | 需二次授权 | `CMD_WIPE`+家长确认码 → `wipeData(0)`（仅丢失/违规场景） |
| 响铃 | — | `CMD_RING` → 最大音量响铃 30s |

### 6.9 通信与消息（域 I）

| 参数 | 默认值 | 说明 |
|---|---|---|
| `sms.send` | 管控端代发 | `CMD_SEND_SMS`(to,body) → 经 `SmsManager`（DPC 授权） |
| `sms.forward` | off | 代收短信→上报(脱敏)→管控端可看 |
| `notif.collect` | off | 通知代收（标题/来源，不含正文敏感） |
| `broadcast.msg` | — | `CMD_BROADCAST_MSG` → 锁屏/状态栏滚动公告 |
| `parent.voice` | — | 家长留言→本地语音播报 |

### 6.10 护眼与姿态（域 J）

| 参数 | 默认值 | 范围 | 说明 |
|---|---|---|---|
| `eye.blueLight` | on | — | 蓝光过滤（夜间增强） |
| `eye.brightnessAuto` | on | — | 亮度自适应环境光 |
| `eye.distanceAlert` | 25cm | 20–40cm | 距离过近提醒（摄像头/红外测距） |
| `eye.postureTypes` | 6 类 | — | 歪头/趴卧/平躺/侧躺/过近/抖动 |
| `eye.restAction` | 暂停内容 | — | 严重→暂停，调整后可继续 |
| `eye.restIntervalMin` | 30–40min | — | 连续用眼强制远眺提醒（对齐卫健委指南） |

### 6.11 防绕过与加固（域 K）

| 参数 | 默认值 | 实现（DPC/系统） |
|---|---|---|
| 不可卸载 | true | Device Owner 不可卸；`setUninstallBlocked` 自身 |
| 防强制停止 | true | 前台服务+`setPolicies` 锁活；系统级防止停止 |
| 防恢复出厂 | true | `DISALLOW_FACTORY_RESET`(需管控端授权解除) |
| 禁 USB 调试 | true | `DISALLOW_DEBUGGING_FEATURES` |
| 禁未知来源 | true | `DISALLOW_INSTALL_UNKNOWN_SOURCES` |
| 禁安全模式 | true | `DISALLOW_SAFE_BOOT` |
| 禁 USB 文件传输 | true | `setUsbDataSignalingEnabled(false)`(API 28+) |
| root 检测 | true | SafetyNet/Play Integrity + 文件特征 → `EVT_ROOT_DETECTED`→锁机 |
| 开发者选项禁用 | true | 全局设置 `development_settings_enabled=0` |
| 家长生物验证 | true | 改管控设置需指纹/人脸（管控端下发挑战） |
| 时间篡改检测 | true | 复用 `TimeProvider.detectClockTampering` |
| 应用隐藏/冻结 | 按策略 | `setApplicationHidden`/`DISALLOW` |

### 6.12 报告与日志（域 M）

| 参数 | 默认值 | 说明 |
|---|---|---|
| 日报 | 每日 00:10 | 应用 TOP5、总时长、高频时段、违规次数 |
| 学习进度 | 按应用上报 | 学习类应用进度（如对接应用支持） |
| 安全日志 | 实时 | 安装/卸载/越界/篡改/root 事件 |
| 异常告警 | 实时 | `EVT_*` → 推送管控端 |

### 6.13 Kiosk / 学习空间（域 L）

| 参数 | 默认值 | 说明 |
|---|---|---|
| `kiosk.mode` | multi | single(单应用)/multi(学习桌面) |
| `kiosk.lockTaskPkgs` | 白名单 | `setLockTaskPackages` |
| `kiosk.homeBrand` | 护航 Logo | 锁屏/桌面品牌壁纸 |
| `kiosk.exitGuard` | 家长密码 | 退出 Kiosk 需管控端/生物验证 |
| `appPool` | 内置+白名单 | 封闭应用池，仅教育类可装 |

---

## 7. 权限清单（详细参数）

### 7.1 运行时权限（DPC 自动授予）

| 权限 | 用途 | 授予方式 |
|---|---|---|
| `ACCESS_FINE_LOCATION` | 实时定位/围栏 | `setPermissionGrantState(GRANTED)` |
| `READ_PHONE_STATE` | 设备/信号 | DPC 授予 |
| `SEND_SMS` / `READ_SMS` / `RECEIVE_SMS` | 信息发送/代收 | DPC 授予（按需） |
| `READ_CALL_LOG` | 通话记录(按授权) | DPC 授予（按需） |
| `CAMERA` | 姿态/距离/截屏 | 协议授权+MediaProjection |
| `RECORD_AUDIO` | 姿态辅助/录屏 | 协议授权 |
| `READ_EXTERNAL_STORAGE`/`WRITE_*` | 日志/文件 | DPC 授予 |
| `POST_NOTIFICATIONS` | 公告/提醒 | DPC 授予 |
| `FOREGROUND_SERVICE` | 守护常驻 | 声明 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 保活 | DPC 授予 |

### 7.2 特殊权限 / DPC 特权

| 能力 | 机制 |
|---|---|
| 应用静默安装/卸载 | `DevicePolicyManager` + `DeviceOwner` |
| 锁屏/重启 | `lockNow()` / `reboot()` |
| 禁用功能 | `addUserRestriction(DISALLOW_*)` |
| 截屏禁用(学生侧) | `setScreenCaptureDisabled(true)` |
| 权限自动授予 | `setPermissionGrantState` |
| Kiosk | `setLockTaskPackages` + `startLockTask` |
| 防恢复出厂 | `DISALLOW_FACTORY_RESET` |
| 全局设置 | `setGlobalSetting`(开发选项/自动时间) |

### 7.3 合规边界
- 通话/短信**内容**读取以系统授权范围为限；上传一律脱敏。
- 摄像头/麦克风仅在协议授权后用于姿态与远程可视；端侧处理优先，原始流不出端（远程可视经加密通道且仅绑定管控端可看）。

---

# 第二部分　AI 智能开发文档

## 8. 通信协议与数据互通（与管控端对齐）

### 8.1 通道

| 通道 | 用途 | 端点 |
|---|---|---|
| MQTT(TLS) | 控制指令、事件、遥测、心跳 | `ssl://mqtt.padguard.cn:8883` |
| REST(HTTPS) | 绑定注册、大文件、固件、资源、策略拉取 | `https://api.padguard.cn` |
| WebSocket(WSS) | 实时画面流 | `wss://api.padguard.cn/screen/{deviceId}` |

### 8.2 MQTT 主题规范

| 方向 | 主题 | 说明 |
|---|---|---|
| 上行 | `padguard/{deviceId}/event/{type}` | 事件（位置/用量/安装/安全…） |
| 上行 | `padguard/{deviceId}/status` | 在线/心跳/健康 |
| 上行 | `padguard/{deviceId}/telemetry` | 周期设备信息 |
| 下行 | `padguard/{deviceId}/cmd/{type}` | 管控端指令 |
| 下行 | `padguard/{deviceId}/policy` | 策略全量/增量 |
| 绑定 | `padguard/bind/{bindCode}` | 绑定临时主题（绑定后销毁） |

- QoS：控制=1，事件=1，遥测=0；`CleanSession=false`，持久化订阅。
- 遗嘱（LWT）：`status`=`offline`，便于管控端显示离线。

### 8.3 消息信封（JSON）

```json
{
  "v": 1,
  "msgId": "uuid",
  "deviceId": "dv_xxxx",
  "ts": 1694600000000,
  "serverTime": 1694600000123,
  "type": "CMD_LOCK | EVT_LOCATION | ...",
  "payload": { }
}
```

- `ts`：本地墙钟（已用 `serverOffsetMs` 校准，见 `TimeProvider`）；`serverTime`：服务端下发的标准时间（若已同步）。
- 所有指令需带 `msgId`，被管控端回 `EVT_ACK{msgId, code}`。

### 8.4 指令/事件类型枚举（节选）

**下行指令 CMD_***
`CMD_LOCK, CMD_UNLOCK, CMD_REBOOT, CMD_SHUTDOWN, CMD_LOST_MODE,
CMD_SET_POLICY, CMD_GET_DEVICE_INFO, CMD_GET_LOCATION,
CMD_INSTALL_APP, CMD_UNINSTALL_APP, CMD_UPDATE_APP,
CMD_SCREENSHOT, CMD_START_SCREEN_RECORD, CMD_STOP_SCREEN_RECORD, CMD_BLACKSCREEN,
CMD_SET_KIOSK, CMD_PUSH_FILE, CMD_WIPE, CMD_RING,
CMD_SET_GEOFENCE, CMD_BROADCAST_MSG, CMD_SEND_SMS, CMD_SET_PROFILE`

**上行事件 EVT_**
`EVT_BOUND, EVT_DEVICE_INFO, EVT_LOCATION, EVT_APP_USAGE,
EVT_INSTALL_REQUEST, EVT_APP_INSTALLED, EVT_APP_REMOVED,
EVT_SCREENSHOT, EVT_POLICY_APPLIED, EVT_ACK,
EVT_TAMPER_DETECTED, EVT_ROOT_DETECTED, EVT_BATTERY_LOW,
EVT_GEOFENCE_VIOLATION, EVT_CONTENT_BLOCKED, EVT_SAFETY_ALERT,
EVT_USAGE_REPORT, EVT_SAFETY_LOG, EVT_OFFLINE`

### 8.5 策略快照示例

```json
{
  "time":   { "dailyLimitMin":120, "perAppLimitMin":30, "bedtimeStart":"21:00", "bedtimeEnd":"07:00" },
  "app":    { "mode":"whitelist", "whitelist":["com.padguard.*","com.android.chrome"], "installApproval":true },
  "web":    { "mode":"blacklist", "safeSearch":"enforce", "adBlock":true },
  "loc":    { "intervalSec":60, "historyDays":30 },
  "geo":    { "fences":[ {"id":"school","lat":x,"lng":y,"radiusM":500,"notify":true} ] },
  "kiosk":  { "mode":"multi", "lockTaskPkgs":["com.padguard.child","com.android.chrome"] },
  "eye":    { "blueLight":true, "distanceAlertCm":25 }
}
```

### 8.6 时间校准（复用现有 TimeProvider）
- 绑定后首次 `CMD_GET_DEVICE_INFO` 或心跳携带 `serverTime` → `TimeProvider.syncServerTime(serverTimeMs, roundTripMs)`。
- 任何计时（时长/时段/围栏）一律基于 `elapsedRealtime()` + `serverOffsetMs`，拒绝纯墙钟，防「改时间绕过」。

---

## 9. 模块划分与代码结构（AI 开发脚手架）

沿用现有 PadGuardChild 多模块结构：

```
app/                 # 绑定引导、协议弹窗、Kiosk 桌面、主控 UI（Compose）
core/common/         # TimeProvider、Logger、Crypto、权限/策略常量（已有）
core/data/           # Room（策略/事件/日志/协议）、DataStore、Repository
core/engine/         # 策略引擎（执行 DevicePolicyManager 指令）、UsageTracker、LocTracker、GeoFence
core/transport/      # MQTT(Paho)、REST(Retrofit)、WebSocket(屏幕流)、消息编解码
core/engine/guard/   # GuardService 前台守护、心跳、保活
```

### 9.1 关键类/接口

| 类 | 职责 |
|---|---|
| `PadGuardDeviceAdminReceiver : DeviceAdminReceiver` | DPC 接收器（设备所有者） |
| `GuardService` | 前台守护：MQTT 长连、心跳、指令分发、保活 |
| `PolicyEngine` | 应用/下发的策略到系统 API 的映射与执行 |
| `DeviceController` | 封装 `DevicePolicyManager`：锁屏/重启/应用/限制 |
| `UsageTracker` | `UsageStatsManager` 采集与时长判定 |
| `LocTracker` / `GeoFenceManager` | 定位与围栏 |
| `ScreenStreamer` | MediaProjection → WebSocket 帧 |
| `MqttMsgHandler` | 解析 `CMD_*` → 调对应 Controller → 回 `EVT_ACK` |
| `ProvisioningActivity` | 首次激活/绑定引导（§4） |
| `AgreementDialog` | 使用授权协议弹窗（§5） |

### 9.2 与现有代码衔接
- `com.padguard.child` 命名空间、现有 `DeviceAdminReceiver`、`TimeProvider` 直接复用。
- MQTT：`core/transport` 已选型 Eclipse Paho mqttv3（纯 Java 客户端，前台 `GuardService` 驱动），与管控端共用 `mqtt.padguard.cn`。
- 构建：AGP 8.7.3 / Kotlin 2.0.21 / Hilt 2.52 / Room 2.6.1 / DataStore 1.1.1 / WorkManager 2.10.0 / Compose BOM 2024.12.01；`compileOptions/jvmTarget=17`。

---

## 10. 数据模型（节选）

### 10.1 Room 实体

```kotlin
@Entity table="policy"      data class PolicyEntity(pk: Int=1, snapshot: String, updatedAt: Long)
@Entity table="app_usage"   data class AppUsageEntity(day: String, pkg: String, minutes: Int, launches: Int)
@Entity table="location_log"data class LocationEntity(ts: Long, lat: Double, lng: Double, acc: Float)
@Entity table="safety_log"  data class SafetyLogEntity(ts: Long, type: String, detail: String)
@Entity table="agreement"   data class AgreementEntity(version: String, signedAt: Long, snMask: String)
@Entity table="event_outbox"data class OutboxEntity(msgId: String, topic: String, payload: String, retry: Int)
```

### 10.2 DataStore 键
`bound, deviceId, bindToken(enc), mqttUser(enc), mqttPwd(enc), pairedParentId, activatedAt, agreementVersion`

### 10.3 离线策略
- 指令/事件入 `event_outbox`，网络恢复按 `retry` 指数退避重发（≤5 次）。
- 策略本地缓存，离线按最后已知策略执行；上线后 `CMD_SET_POLICY` 增量合并。

---

## 11. 非功能需求

| 维度 | 要求 |
|---|---|
| 性能 | 守护服务常驻内存 ≤ 80MB；锁屏指令端到端 ≤ 1s；心跳 30s |
| 功耗 | 后台定位 ≤ 1%/h（高精模式除外）；守护服务 Doze 兼容 |
| 稳定 | 崩溃率 ≤ 0.1%；MQTT 自动重连(指数退避)；守护防被杀 |
| 隐私 | 硬件标识脱敏；通信 TLS1.2+；敏感流加密；合规日志可审计 |
| 合规 | 符合《未成年人网络保护条例》《移动互联网未成年人模式建设指南》；不可卸载/不可绕过 |
| 兼容 | API 26–35；厂商 ROM（华为/小米/OPPO/vivo）DPC 差异适配 |
| 离线 | 策略本地执行、事件缓存补传 |

---

## 12. 验收标准（可量化）

| 功能 | 验收点 |
|---|---|
| 绑定 | 扫码/输码/NFC 三种均可在 300s 内完成，错误码明确 |
| 协议 | 未同意不授予任何高敏权限；同意后权限自动到位 |
| 锁屏 | 管控端下发 1s 内锁屏 |
| 应用管控 | 黑名单应用不可启动；白名单外安装被拦截并上报 |
| 时长 | 到点强制锁定；改系统时间无法绕过（TimeProvider 拦截） |
| 位置 | 实时定位误差 ≤ 50m(WiFi)/≤ 20m(GPS)；围栏进出告警 ≤ 5s |
| 截屏 | 远程截屏回传 ≤ 2s；学习空间内学生不可截屏 |
| 防绕过 | 恢复出厂/USB 调试/未知来源被禁；root 触发锁机 |
| Kiosk | 退出需家长验证；仅白名单应用可运行 |
| 互通 | 事件在管控端实时可见；策略下发端到端 ≤ 3s |

---

## 13. 风险与边界

1. **Android 版本差异**：`setUsbDataSignalingEnabled` 需 API 28+；`reboot()` 需 API 24+ 且部分 ROM 限制；实时画面 MediaProjection 需用户一次性授权（协议已涵盖）；Android 14+ 截屏权限收紧，需评估 `DevicePolicyManager` 屏幕捕获扩展。
2. **厂商 ROM**：华为/小米等对 DPC 有额外限制（如静默安装需系统签名或特定通道），需在 §9 做厂商适配层。
3. **合规**：通话/短信内容读取受系统授权与法规约束，默认关闭、按需开启、全程脱敏。
4. **Device Owner 不可中途获得**：必须在部署/首次开机阶段激活（§4.4）；已激活设备不可「降级」为普通应用。

---

## 14. 开发里程碑（建议）

| 阶段 | 交付 |
|---|---|
| M1 基座 | DPC 激活 + 绑定(三种) + 协议弹窗 + MQTT 长连 + TimeProvider 校准 |
| M2 核心管控 | 时长/应用/上网/锁屏/位置围栏 |
| M3 远程可视与运维 | 截屏/录屏/实时画面 + 静默安装/文件/响铃 |
| M4 加固与体验 | 防绕过全套 + 护眼姿态 + Kiosk + 报告日志 |
| M5 互通联调 | 与 PadGuard Parent 全指令/事件联调 + 验收 |

---

> 本说明书即被管控端（PadGuard Child）的需求与开发基线。任何功能增删须同步更新 §3 能力总览与 §12 验收标准，并通知管控端（PadGuard Parent）协议版本对齐。
