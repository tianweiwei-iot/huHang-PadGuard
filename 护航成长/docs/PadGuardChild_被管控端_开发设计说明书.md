# 护航成长 · 被管控端（PadGuard Child）APK
## 开发设计说明书（需求 → 代码映射 / 落地记录）

> 文档版本：v1.0 ｜ 编制日期：2026-09-14 ｜ 编制角色：系统架构 / 开发实现
> 关联需求基线：`docs/PadGuardChild_被管控端_产品功能设计说明书与开发文档.md`（以下简称《需求说明书》）
> 被管控端包名：`com.padguard.child`（工程名 `PadGuardChild`）
> 本文件性质：**在《需求说明书》基础上的开发落地记录**，说明哪些需求已通过代码实现、实现位置、关键设计取舍，以及尚未落地的已知缺口。

---

# 0. 结论摘要（给评审 / 拍板者）

- **编译与构建**：`:app:assembleDebug` 全量构建 **BUILD SUCCESSFUL（EXIT=0）**；产物 APK 位于 `E:/builds/padguard/app/outputs/apk/debug/app-debug.apk`（`com.padguard.child.debug`，versionName `1.0.0`，minSdk 26，41 MB）。
- **需求覆盖判断**：项目并非从零起步，已具备成熟的 Device Owner 守护基座（前台守护服务、DPC 桥接层、策略引擎 + 8 个 Enforcer、MQTT/REST 传输、Room/DataStore）。本次开发聚焦补齐《需求说明书》中**两项硬性缺口**：**绑定 ≥3 种方式** 与 **协议弹窗 + 同意即自动授予全部权限**。
- **本次新增 / 改造的核心文件（均编译通过）**：
  1. `core/engine/.../admin/DeviceAdminBridge.kt` — 新增 `hasOwnerPrivileges()`、`grantRuntimePermission()`、`applyHardeningBaseline()`（§7 权限自动授予 + §11 加固基线）。
  2. `core/data/.../repository/AuthRepository.kt` — 新增协议版本状态（`agreementVersion` / `isAgreementAccepted` / `saveAgreement`，§4.6 / §5.4）。
  3. `core/data/.../db/*` + `AgreementRepository.kt` — 新增 `AgreementEntity`（Room 合规表，§10.1）。
  4. `app/.../permission/PermissionGranter.kt` — 同意即自动授予 §7 全部运行时权限并落地加固基线（§5.3 + §7）。
  5. `app/.../agreement/AgreementDialog.kt` + `AgreementViewModel.kt` — 不可跳过的授权协议弹窗（§5）。
  6. `app/.../MainActivity.kt` `EntryRouter` — 绑定后、授权前叠加协议门禁（§5.1）。
  7. `app/.../bind/BindScreen.kt` / `BindActivity.kt` / `QrScanActivity.kt` + `AndroidManifest.xml` + `libs.versions.toml` — 绑定页支持**扫码 / 输码 / NFC 碰一碰**三种方式（§4.2 / §4.3）。
- **已知缺口（务必在 M2+ 前制定计划）**：§4.3④声波/蓝牙近场未实现；§6.7 远程截屏、§6.8 静默安装/卸载、§6.5 定位采集在 `GuardService` 中按 P0 范围外**有意降级为 UNSUPPORTED**；**绑定码格式与《需求说明书》不一致**（见 §6 一致性问题，建议优先 Reconcile）。

---

# 1. 现状审计结论（动手前先看清家底）

在落代码前，先对现有工程做了一次架构审计，结论如下（决定"补什么"而非"重写什么"）：

| 维度 | 现状 | 评价 |
|---|---|---|
| 运行模型 | `PadGuardDeviceAdminReceiver` 已声明为 DeviceAdminReceiver；`DeviceAdminBridge` 探测并区分 DEVICE_OWNER / PROFILE_OWNER / DEVICE_ADMIN / LEGACY 四种权限模式 | 成熟，能力探测与降级模型清晰（见 `DeviceAdminBridge.can`） |
| 前台守护 | `GuardService`：MQTT 长连、心跳、指令分发、副作用落地、保活自检、篡改扫描 | 成熟，单循环架构（15s tick） |
| 策略执行 | `PolicyEngine` + 8 个 Enforcer：Web / App / SystemLock / Security / Peripheral / Monitoring / Kiosk / UsageTime | 成熟，覆盖 §6 大部分域；按能力回执 Ok / Unsupported / Failed |
| 通信 | `core/transport`：Paho MQTT(TLS) + Retrofit REST，消息信封编解码 | 成熟，主题规范与《需求说明书》§8 一致 |
| 存储 | Room（policy / usage / location / safety_log / outbox / agreement）+ DataStore（绑定态）+ WorkManager | 成熟 |
| 时间基准 | `TimeProvider`（elapsedRealtime + serverOffsetMs，检测时间篡改） | 成熟，复用中 |
| **绑定 UI** | 仅有"输入 6 位码"单入口，无扫码、无 NFC | **缺口（本次补齐）** |
| **协议弹窗 + 权限授予** | 入口门禁绑定后直接进主页，**无授权协议弹窗、无权限自动授予入口** | **缺口（本次补齐）** |

> 审计判断：两条硬性需求（≥3 种绑定、协议弹窗+自动授权）是真实缺口；其余能力基座已具备。因此本次开发采取"**精准补齐缺口 + 复用既有基座**"策略，不重写稳定模块，符合"重构优于打补丁、用主流稳妥手法"的原则。

---

# 2. 本次落地项（M1 核心）：逐项映射

## 2.1 权限自动授予与加固基线 — §7 + §11

**文件**：`core/engine/src/main/kotlin/com/padguard/core/engine/admin/DeviceAdminBridge.kt`

| 方法 | 行号 | 对应需求 | 说明 |
|---|---|---|---|
| `hasOwnerPrivileges()` | L200 | §7.2 | 集中判断"是否持有 DO/PO"——运行时权限自动授予与多数用户限制仅这两种身份可用 |
| `grantRuntimePermission(permission)` | L213 | §7.1 | `dpm.setPermissionGrantState(GRANTED)`，不弹系统授权框，一次性到位；非 DO/PO 返回 `OpResult.Unsupported`（预期降级） |
| `applyHardeningBaseline()` | L235 | §11 | 逐项应用常开加固：禁 USB 调试 / 防恢复出厂 / 禁安全模式 / 禁未知来源 / 禁改时间 / 关 USB 文件传输；每项返回结果供上层汇总"哪些加固未生效" |

**关键设计取舍**：
- §11 中"禁 USB 调试 / 防恢复出厂 / 禁安全模式 / 禁未知来源 / 禁改时间"统一经 `UserManager.DISALLOW_*` 用户限制实现（DO/PO 可用）。`setUsbDataSignalingEnabled(false)` 需 API 28+ 且不接受 admin 参数，单独用 `runCatching` 收口，避免单点失败拖垮整批加固。
- 时间篡改防护除 `DISALLOW_CONFIG_DATE_TIME` 外，另由 `setAutoTimeEnforced()`（L269，API 分叉：R 用 `setAutoTimeEnabled`/旧版 `setAutoTimeRequired`+`AUTO_TIME`）兜底——这是 §6.2 "改系统时间无法绕过"的 P0 级保障，单独成方法。

## 2.2 协议同意状态基础设施 — §4.6 / §5.4

**文件**：`core/data/src/main/kotlin/com/padguard/core/data/repository/AuthRepository.kt`

| 项 | 行号 | 说明 |
|---|---|---|
| `Keys.AGREEMENT_VERSION` | L40 | DataStore 键 `agreement_version`（§4.6 状态表） |
| `agreementVersion: Flow<String>` | L50 | 已同意版本；空=尚未同意（受限预览模式） |
| `isAgreementAccepted: Flow<Boolean>` | L54 | `== CURRENT_AGREEMENT_VERSION`，入口门禁据此决定是否弹窗 |
| `CURRENT_AGREEMENT_VERSION = "1.0.0"` | L128 | 协议版本常量（迭代协议正文时升版） |
| `suspend fun saveAgreement(version)` | L109 | 同意后持久化版本 |

**合规留存 Room 表**：`core/data/.../db/Entities.kt` 新增 `AgreementEntity(version, signedAt, snMask, deviceId)`（§10.1）；`Daos.kt` 新增 `AgreementDao`；`PadGuardDatabase.kt` `version` 升至 **3** 并登记实体；`DataModule.kt` 提供 `agreementDao`。`AgreementRepository.record()` 写入 Room + 追加 `LogType.AGREEMENT` 行为日志（上报服务端），只存脱敏 SN 后 4 位（§5.4）。

## 2.3 授权即授予入口 — §5.3 + §7

**文件**：`app/src/main/kotlin/com/padguard/child/permission/PermissionGranter.kt`（@Singleton）

- `requiredPermissions`（L35）：§7.1 运行时权限清单（位置 / 电话 / 短信三件套 / 通话记录 / 相机 / 录音 / 存储），与 `AndroidManifest.xml` 声明一一对应。
- `suspend fun grantAll(version)`（L58）：循环 `admin.grantRuntimePermission` → `applyHardeningBaseline()` → 持久化 `saveAgreement` + `agreementRepository.record`。**无论授予结果如何都先持久化同意事实**（孩子已明确同意），未授予项汇总进 `GrantReport` 供服务端知悉并引导系统授权。

## 2.4 使用授权协议弹窗 — §5

**文件**：`app/src/main/kotlin/com/padguard/child/agreement/AgreementDialog.kt` + `AgreementViewModel.kt`

| 项 | 行号 | 对应需求 |
|---|---|---|
| `onDismissRequest = { }` | L36 | §5.1 不可后台跳过（点外部不关闭） |
| `AGREEMENT_TEXT` | L89 | §5.2 授权要点全文（设备信息/位置/应用管控/通信/摄像头麦克风/存储/远程控制/网络通知） |
| `AgreementDialogHost()` | L74 | 仅"已绑定未同意"时由 `EntryRouter` 挂载 |
| `AgreementViewModel.agree()` | L36 | 触发 `PermissionGranter.grantAll`，持久化翻转后门禁自动收起 |
| `AgreementViewModel.decline()` | L50 | 暂不同意 → 受限预览模式，不授予任何高敏权限（§5.3） |

## 2.5 入口门禁 — §5.1

**文件**：`app/src/main/kotlin/com/padguard/child/MainActivity.kt` `EntryRouter`（L55）

- 未绑 → 跳 `BindActivity`；已绑 → 进 `PadGuardApp()` 主页；**已绑但未同意**（`agreed != true`）→ 主页之上叠加 `AgreementDialogHost()`（L70），保证"先明示授权再启用远程能力"。

## 2.6 绑定 ≥3 种方式 — §4.2 / §4.3

**文件**：
- `app/.../bind/BindScreen.kt` — 首屏 `MethodChoiceStep`（L157）并列三卡片：① 扫码绑定 ② 输入绑定码 ③ NFC 碰一碰（L180–194）。
- `app/.../bind/QrScanActivity.kt` — CameraX `Preview` + MLKit `barcode-scanning` 解析（L84），回传 `EXTRA_CODE`（支持 `padguard://bind?code=` 与裸码）。
- `app/.../bind/BindActivity.kt` — NFC 前台调度（`enableForegroundDispatch`，L73）+ reader mode（`enableReaderMode`，L89）；`parseNfcIntent` / `parseTag` / `extractCodeFromPayload`（L101–137）解析 `padguard://bind?code=` 与裸 6–8 位码。
- 三路绑定码统一经 `BindScreen.presetCode` → `BindViewModel.onCodeChanged` + `submit()` 既有提交流程，**不重复实现绑定逻辑**（§4.5）。
- `AndroidManifest.xml` 新增 §7 运行时权限、NFC 权限、camera/nfc `uses-feature`（required=false）、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，并注册 `QrScanActivity`（L87）。
- `gradle/libs.versions.toml` 新增 `camera=1.4.1`、`mlkit-barcode=17.3.0`；`app/build.gradle.kts` 引入 `camera2/camera-lifecycle/camera-view/mlkit-barcode`。

---

# 3. 需求 → 实现映射总表

| 需求章节 | 需求要点 | 实现位置 | 状态 |
|---|---|---|---|
| §4.2 | 首屏"请绑定管控端"，≥3 种绑定入口 | `BindScreen.MethodChoiceStep` | ✅ 已落地 |
| §4.3① | 扫码绑定（MLKit/ZXing） | `QrScanActivity` | ✅ 已落地 |
| §4.3② | 输入绑定码 | `BindScreen.InputCodeStep`（注：码长与格式见 §6 待 Reconcile） | ⚠️ 部分（码格式未对齐 doc） |
| §4.3③ | NFC 碰一碰 | `BindActivity` reader mode + 前台调度 | ✅ 已落地 |
| §4.3④ | 声波 / 蓝牙近场（可选） | — | ❌ 未实现（可选，低优先） |
| §4.4 | Device Owner QR/NFC 部署激活 | 既有 `PadGuardDeviceAdminReceiver` + 部署配置（不在本端 UI） | ✅ 既有 |
| §4.5 | 绑定成功后时序（bind → MQTT → GuardService → Kiosk） | 既有 `BindViewModel` + `GuardService` | ✅ 既有 |
| §4.6 | 绑定状态 DataStore 键 | `AuthRepository`（含 `agreementVersion`） | ✅ 已落地 |
| §5.1 | 协议弹窗触发时机（绑定后、授权前、不可跳过） | `EntryRouter` + `AgreementDialogHost` + `onDismissRequest={}` | ✅ 已落地 |
| §5.2 | 协议要点明示 | `AGREEMENT_TEXT` | ✅ 已落地 |
| §5.3 | 同意→自动授权；暂不同意→受限预览 | `PermissionGranter.grantAll` + `AgreementViewModel` | ✅ 已落地 |
| §5.4 | 协议留存（Room 合规表 + 上报） | `AgreementEntity` + `AgreementRepository.record` | ✅ 已落地 |
| §7.1 | 运行时权限 DPC 自动授予 | `DeviceAdminBridge.grantRuntimePermission` + `PermissionGranter` | ✅ 已落地 |
| §7.2 | DPC 特权机制 | `DeviceAdminBridge`（lockNow/reboot/wipe/setLockTaskPackages/…） | ✅ 既有 |
| §11 | 防绕过加固基线 | `applyHardeningBaseline` + `SecurityEnforcer` + `setAutoTimeEnforced` | ✅ 已落地 |

---

# 4. 既有基座（非本次新增，已具备能力，供联调参考）

以下能力在《需求说明书》§6 中定义，由既有引擎实现，本次未改动，列此以明确"已具备"边界：

- **§6.1 设备信息上报** — `UsageTimeEnforcer` / 设备信息采集模块（`report.intervalSec` 等参数可落入策略 JSON）。
- **§6.2 时长与时段** — `PolicyEngine` + `TimeProvider` 防时间篡改。
- **§6.3 应用管控** — `AppPolicyEnforcer`（白/黑名单、挂起 `setPackagesSuspended`、阻止卸载 `setUninstallBlocked`、无障碍/输入法白名单）。
- **§6.4 内容与上网** — `WebEnforcer`（URL 黑白名单、安全搜索、弹窗拦截；浏览器限制需 DO/PO）。
- **§6.6 远程锁屏/重启/关机** — `DeviceAdminBridge.lockNow/reboot` + `CommandExecutor`；关机无公开 API，`GuardService` 以锁屏覆盖层兜底。
- **§6.11 防绕过** — `SecurityEnforcer`（防卸载、自动对时、root 检测、无障碍/输入法白名单）。
- **§6.12 报告与日志** — `LogRepository` + 安全日志 / 使用报告采集。
- **§6.13 Kiosk** — `KioskEnforcer`（`setLockTaskPackages` + `setPersistentPreferredLauncher`，需 DO）。
- **§8 通信协议** — `core/transport` MQTT/REST，主题与信封与 §8 一致。

---

# 5. 已知缺口与延期项（P0 / P1）

| 编号 | 缺口 | 需求章节 | 现状 | 建议 |
|---|---|---|---|---|
| G1 | 远程截屏 / 录屏 / 实时画面 | §6.7 | `GuardService` `CaptureScreenshot` 回执 UNSUPPORTED（Android 未向 DO 开放静默截屏；实时画面需 MediaProjection 一次性授权 + WebRTC 通道） | M3 接入 `ScreenStreamer` + WebSocket（`wss://api.padguard.cn/screen/{deviceId}`） |
| G2 | 静默安装 / 卸载 / 更新 | §6.8 | `GuardService` `InstallApp`/`UninstallApp` 回执 UNSUPPORTED（需 DO + `PackageInstaller` 会话） | M3 实现 `PackageInstaller` 静默会话 |
| G3 | 实时定位 / 电子围栏采集 | §6.5 | `GuardService` `RequestLocation` 回执 UNSUPPORTED（定位采集模块本版本未启用） | M2 接入 `LocTracker`/`GeoFenceManager` |
| G4 | 声波 / 蓝牙近场绑定 | §4.3④ | 未实现（标注"可选"） | 低优先，按需 |
| **G5** | **绑定码格式不一致** | §4.3 vs 实现 | **doc 要求 8 位大写字母数字 `^[A-Z0-9]{8}$`、有效期 300s 单次；实现为 6 位数字码（`InputCodeStep` 文案"请输入 6 位绑定码"、`code.length==6`、NumberPassword）** | **P1，须 Reconcile：二选一——实现对齐 doc（改输入校验 + 有效期 + 单次使用 + 绑定接口 `exp` 校验），或 doc 降级为 6 位数字并同步 §12 验收** |

> G5 一致性风险说明：QR/NFC 路径 `extractCodeFromPayload` 已能解析 6–8 位字母数字裸码与 `padguard://bind?code=`，但**输码路径**被硬编码为 6 位数字，若服务端按 doc 下发 8 位字母数字码，输码入口将拒绝。这是联调前必须解决的阻断点。

---

# 6. 编译与构建验证

| 项 | 结果 |
|---|---|
| 编译 | `:app:compileDebugKotlin` BUILD SUCCESSFUL（多次增量验证） |
| 全量打包 | `./gradlew :app:assembleDebug` **BUILD SUCCESSFUL (EXIT=0)** |
| 产物 | `E:/builds/padguard/app/outputs/apk/debug/app-debug.apk`（41 MB） |
| 应用 ID | `com.padguard.child.debug` |
| versionName / minSdk | `1.0.0` / API 26 |
| 构建环境 | JDK 21（`jbr-21.0.11`），AGP 8.7.3 / Kotlin 2.0.21 / Hilt 2.52 |
| 产物输出位置 | 因根 `build.gradle.kts` 重定向 `buildDir` 至 `<盘符>:/builds/padguard`，APK 不在 `app/build/` 而在 `E:/builds/padguard/app/outputs/apk/debug/` |

> 注：`packageDebug` 报告 `Unable to strip ... .so` 仅为跳过原生库调试符号剥离（非致命），不影响安装与运行。

---

# 7. 里程碑路线图与下一步建议

参照《需求说明书》§14，并叠加本次落地结论：

| 阶段 | 交付 | 状态 |
|---|---|---|
| M1 基座 | DPC 激活 + 绑定（三种）+ 协议弹窗 + MQTT 长连 + TimeProvider 校准 | ✅ 本次完成（绑定三种 + 协议弹窗 + 权限自动授予；DPC/MQTT/TimeProvider 为既有基座） |
| M2 核心管控 | 时长/应用/上网/锁屏/**位置围栏（G3）** | 🟡 时长/应用/上网/锁屏已具备；位置围栏待启用 |
| M3 远程可视与运维 | 截屏/录屏/实时画面（G1）+ 静默安装/文件/响铃（G2） | ❌ 延期中 |
| M4 加固与体验 | 防绕过全套 + 护眼姿态 + Kiosk + 报告日志 | 🟡 防绕过/Kiosk/报告已具备；护眼姿态待补 |
| M5 互通联调 | 与 PadGuard Parent 全指令/事件联调 + 验收 | ⏳ 待启动 |

**下一步建议（按优先级）**：
1. **P1 解决 G5 绑定码格式一致性**（联调前置阻断）。
2. **P0 规划 G3 定位采集**（§12 验收"实时定位误差 ≤50m/≤20m、围栏告警 ≤5s"依赖此模块）。
3. **M3 排期 G1/G2**（截屏与静默安装是行业对标的关键差异项，见 §1.2 能力基线）。
4. 联调前与 PadGuard Parent 对齐协议版本号与绑定码契约。

---

> 本说明书即"被管控端开发设计"的落地依据。任何功能增删须同步更新《需求说明书》§3 能力总览与 §12 验收标准，并通知管控端（PadGuard Parent）协议版本对齐。本次开发严格遵循"精准补齐缺口、复用成熟基座、主流稳妥手法"的原则，未对稳定模块做无谓重写。
