# 第九轮 UI 全面审查与优化实施计划

> 审查日期：2026-11-02
> 范围：护航控制端（家长端 Compose）、护航成长（孩子端）、padguard-admin（运营后台）
> 方法：三端独立静态审查（调用点全量 grep 判据）+ 证据抽样复核（行号级验证）
> 基线：2026-10-17 UI 核查报告 + 第八轮全面评估（均已修复项不重复报告）

## 一、总体结论

| 端 | P0 | P1 | P2 | 核心症结 |
|---|---|---|---|---|
| 家长端 | 0 | 5 | 13 | 失败静默（error 不渲染）+ 深色令牌未贯彻 + 定时锁机输入框 |
| 管理后台 | 4 | 6 | 10 | 计费页契约断裂 + 401 无出口 + 假走势图 + 异步无兜底 |
| 孩子端 | 1 | 7 | 10 | 上网管控零执行（需独立迭代）+ 策略层有字段执行层没接 |

三端均无新崩溃路径；**最危险的是"看起来成功但没生效"**：管控策略保存失败零提示、统计假 0、计费页卡加载中、第二条霸屏消息不更新、桌面空态绕过白名单。

## 二、P0 清单（本轮必修）

### A1. 后台计费页契约断裂（功能性宕机）
`billing.js:43-46,53-57` 把 `listSubscriptions/listOrders` 返回的 `{items,page,total}` 信封当数组 `.map()` → TypeError → 订阅/订单 tab 永久"加载中"；即使不崩也无分页器，第 20 条后不可见。
**修复**：照 `parents.js` 模板重写（page/size + `resp.items` + `U.pager` + loadSeq 竞态 + try/catch 错误态 + 空态）。

### A2. 后台 401 无全局出口
`api.js:109-114` 把 401 当普通错误抛；`router.mountSafely` 只认 403。令牌过期后管理员困在坏页面，每个动作弹 "HTTP 401"。
**修复**：`http()` 统一识别 401 → `PG.onUnauthorized()`（清 token + toast"登录已过期" + 回登录屏）；`app.js` 暴露该钩子。

### A3. 后台服务端监控两条编造 sparkline
`server.js:20-21` 运行时长曲线是凭空递增数列，堆内存前 6 点硬编码。与仪表盘已修的同类缺陷同源。
**修复**：无真实序列不渲染 spark（`s.qpmHistory` 有真值才画）。

### A4. 后台 ~15 处异步 handler 无错误兜底
parents 新建/保存/激活、devices 解锁、audit 告警渲染/关闭、billing 四 tab —— 失败零反馈，与死按钮等价。
**修复**：统一 try/catch + `U.toast("err", ...)`，tab 级兜底空态。

### A5. 家长端管控策略写失败静默
`ControlPolicyViewModel.fail()` 写 `error`，`ControlPolicyScreen:47-48` 只消费 `toast` → 每日时长/时段/应用禁用/限时/浏览器/网址/护眼/模式切换 9 类写操作全部静默失败，家长误以为已下发。
**修复**：Screen 增加 `LaunchedEffect(uiState.error)` 弹 Snackbar，弹后清除。

### A6. 孩子端上网管控零执行（独立迭代，本轮只做设计）
Manifest 未注册 `WebGuardVpnService`、`WebGuardVpnController.start()` 零调用 → `mode/blacklist/whitelist/keywords/blockedCategories/safeSearch` 全字段无效。
**方案**：注册 BIND_VPN_SERVICE + GuardService 按 `web.mode != OFF` 启停 + DNS 改读系统链路（弃 8.8.8.8）+ `matchUrl` 接入 VPN 包处理。需真机验证与一次性授权弹窗（CONSENT_VERSION 升版），单独排期。

## 三、P1 清单（本轮实施）

| # | 端 | 问题 | 修法 |
|---|---|---|---|
| B1 | 家长端 | SoftCard 硬编码白底 + 多个 Color.White → 深色模式全卡片不可读 | 走 `PadGuardColors.CardWhite` / `colorScheme.surface` |
| B2 | 家长端 | 定时锁机输入框每键一次网络写 + 服务端回写覆盖导致丢字 + 无 HH:mm 校验 | 本地草稿 + 失焦提交 + 正则校验 |
| B3 | 家长端 | 统计页接口失败显示"0 分钟" | 补 loading/error 三态 |
| B4 | 家长端 | 护眼滑条 LaunchedEffect 每进 Tab 重复下发 | 改 onValueChangeFinished 提交 |
| B5 | 孩子端 | MessageActivity singleTask 无 onNewIntent → 第二条霸屏消息不更新 | 实现 onNewIntent 重解析 + 重排关闭 |
| B6 | 孩子端 | AppBlockActivity 无 onNewIntent → 拦截页显示上一个应用、申请解锁提交错包 | 同上，pkg/reason 用 mutableStateOf |
| B7 | 孩子端 | 桌面空态 fallback 列出全部应用 → 绕过白名单 | 仅"策略未镜像"才 fallback，合法空名单显示提示 |
| B8 | 孩子端 | 水印 position/opacity 被丢弃（正确实现是死代码） | reconcileWatermark 改传 position/alpha，删死函数 |
| B9 | 后台 | 计费手动开通/退款按钮未接 RBAC guard（资金操作任何人可见可点） | `PG.guard("billing","manual"/"refund", …)` |
| B10 | 后台 | 告警级别未知值渲染 "undefined危" + 处理后角标不刷新 | `|| "未知"` 兜底 + 调 `PG.refreshAlertBadge` |
| B11 | 后台 | mock/fallback 里仍埋真实生产 IP 8.148.68.183 | 改空串 + "未配置"占位 |
| B12 | 孩子端 | 申请解锁提交失败仍显示"已提交" | try/catch → errorMessage |
| B13 | 孩子端 | blocking=true + durationSec=0 无任何退出路径（设备变砖） | 客户端兜底 30s + 服务端 400 校验 |

## 四、P2 清单（下轮清理）

家长端：首页/设备详情/订阅页三态缺失；解锁工单死代码（UnlockRequestCard/ignoreUnlockTicket/loadUnlockTickets 白轮询）；双层 clickable；usesCleartextTraffic 全局放开；高德 Key 入库；文案指向漂移。
孩子端：深色模式 30+ 处硬编码；消息页/我的页首帧闪错误态；霸屏倒计时用总时长；EnforceReport.unsupported 不上行（家长看不到"本机不支持"）；autoTimeZone / screenshotIntervalSec / collectUrlHistory 零执行；sceneMode 死字段；Kiosk 白名单应用层无兜底。
后台：server 页字段未转义；可选数组空值；筛选竞态；解锁无二次确认；数值输入静默吞 0；金额硬编码 ¥；死代码；reference 控制器与前端契约脱节。

## 五、实施顺序

1. **批次 A（后台，风险最低）**：A1-A4 + B9-B11 → `node --check` 校验
2. **批次 B（家长端）**：A5 + B1-B4 → `assembleDebug` 编译
3. **批次 C（孩子端）**：B5-B8 + B12-B13 → `assembleDebug` 编译
4. **批次 D（回归）**：服务端测试 + 三端编译 + 文档更新
5. **独立迭代**：A6 上网管控（VPN 全链路）、sceneMode/Kiosk/自动时区执行点、深色全量与 i18n

## 六、实施记录（2026-11-02）

### 批次 A —— 管理后台（11 个文件，node --check 全通过）

| 项 | 状态 | 改动 |
|---|---|---|
| A1 计费页契约断裂 | ✅ | `views/billing.js` 重写：page/size + `resp.items` + `U.pager` + loadSeq 竞态 + 筛选重置页码 + tab 级 try/catch 错误态 |
| A2 401 无出口 | ✅ | `api.js` http() 识别 401（仅带令牌请求）→ 清 token + `PG.onUnauthorized`；`app.js` 新增钩子（已在登录页不重复提示） |
| A3 假 sparkline | ✅ | `views/server.js` 删除两条编造曲线，仅保留真实 `qpmHistory`；顺带补 esc |
| A4 异步无兜底 | ✅ | parents 新建/保存/激活、devices 锁屏/解锁/解绑、audit 告警渲染/关闭、billing 四 tab 全部 try/catch + 可见错误 |
| B9 资金按钮无 RBAC | ✅ | `billing.js` 手动开通/退款按钮包 `PG.guard("billing","manual"/"refund")` |
| B10 告警级别/角标 | ✅ | `audit.js`/`dashboard.js` 级别兜底 + 关闭后 `PG.refreshAlertBadge()`（本地置 closed 避免重载丢滚动） |
| B11 生产 IP 残留 | ✅ | `api.js`/`server-profile.js` fallback 改空串 + "未配置"占位；placeholder 去 IP |
| 额外 | ✅ | 全局搜索按形态分流（手机号→家长、其余→设备），设备页消费 `PG.state.search`；placeholder 不再承诺订单搜索；解锁补二次确认；手动开通时长 1~3650 校验 |

### 批次 B —— 家长端（编译通过）

| 项 | 状态 | 改动 |
|---|---|---|
| B1 深色模式白底 | ✅ | `SoftCard` 改 `PadGuardColors.CardWhite`；MainScreen 导航栏、AlertCenter 3 处卡片 + 1 处顶栏、HomeScreen 铃铛/设备胶囊同步 |
| B2 定时锁机输入 | ✅ | 新增 `SmartShutdownTimeEditor`：本地草稿 + 失焦提交 + `HH:mm` 正则 + 内联错误；不再每键 POST |
| B3 统计假 0 | ✅ | VM 增 `hasData`/error（两份都失败才算整页失败）；Screen 补 loading/错误重试页，部分失败保留数据 |
| B4 护眼重复下发 | ✅ | 删 `LaunchedEffect(level)`，改 `onValueChangeFinished` 且仅在值变化时提交 |
| A5 策略失败静默 | ✅ | Screen 统一消费 `toast ?: error` 弹 Snackbar；VM 增 `clearError()` |

### 批次 C —— 孩子端（编译通过）

| 项 | 状态 | 改动 |
|---|---|---|
| B5 霸屏消息不更新 | ✅ | `MessageActivity` 抽出 `applyIntent` + 实现 `onNewIntent`（刷内容/重置会话/同步霸屏与返回拦截/版本号驱动重组） |
| B6 拦截页显示旧应用 | ✅ | `AppBlockActivity` pkg/reason/label 改 `mutableStateOf` + `onNewIntent`（并收起旧包的申请表单） |
| B7 桌面空态绕过 | ✅ | `KidHomeViewModel` 区分"读取异常/策略未镜像/合法空名单"，合法空名单不再平铺全部应用 |
| B8 水印位置透明度 | ✅ | `reconcileWatermark` 统一走 `syncWatermarkFromPolicy`（唯一实现，含 position/opacity），消除死代码双路径 |
| B12 解锁假提交 | ✅ | VM 增 `errorMessage`，失败保留理由并提示；Screen 渲染错误 |
| B13 霸屏变砖 | ✅ | 客户端 `blocking && sec<=0` 兜底 30s；服务端入口校验（霸屏必须 1~3600s、总时长 0~3600s） |

### 验证结果

- 管理后台：11 个 JS 文件 `node --check` 全部通过
- 家长端：`:app:compileDebugKotlin` BUILD SUCCESSFUL
- 孩子端：`:app:compileDebugKotlin` BUILD SUCCESSFUL
- 服务端：全量测试回归（见 commit 记录）

### 未纳入本轮（保持计划内独立迭代）

## 七、追加迭代（2026-11-02 晚）：A6 上网管控 VPN 全链路落地

原计划把 A6 列为"需真机 + 升版 CONSENT 的独立迭代"，本轮把**代码侧全部打通**（真机回归仍是上线前置条件）。

| 环节 | 状态 | 实现 |
|---|---|---|
| 服务注册 | ✅ | `AndroidManifest` 新增 `.guard.WebGuardVpnService`：`BIND_VPN_SERVICE` 权限 + `android.net.VpnService` intent-filter（缺它 `prepare()` 永远找不到客户端）+ `specialUse` FGS 类型（API 34+ 无类型直接崩） |
| 策略驱动启停 | ✅ | `WebGuardVpnController.sync(policy)` 幂等对账，`GuardService.reconcileWebGuard()` 在 5 个策略落地点调用（开机复位 / 外部 kick / 周期自检 / 策略刷新 / 启动初始化） |
| "是否需要 VPN"口径 | ✅ | 按**实际规则**判定（分类/安全搜索/黑名单非空/白名单模式），不按 `mode != OFF` —— 服务端组包 `mode` 恒为 `BLACKLIST`，按 mode 判会让每台设备常驻一个空转 VPN |
| DP 代授权 | ✅ | 新增 `DeviceAdminBridge.setAlwaysOnVpn()`：DO/PO 机型 `setAlwaysOnVpnPackage` 代授权（系统不弹框）+ `DISALLOW_CONFIG_VPN` 锁死设置，孩子无法关闭 |
| 一次性授权 | ✅ | `ConsentItem.Source.VPN` + `ConsentLaunch.VpnPrepare` + `OneTimeConsentViewModel` 第 ③ 步（拒绝则记 SKIPPED 并如实告知"之后需补授权"） |
| 补授权入口 | ✅ | 权限引导页新增 `web_guard` 类型；「我的 → 隐私与安全」新增「上网防护（网址拦截）」行，`active` 取**真实运行态** |
| DNS 上行 | ✅ | 弃写死 `8.8.8.8`（国内不可达 → 起来就断网），改读系统 `LinkProperties.dnsServers`（排除隧道自身地址防环），60s 缓存，兜底 223.5.5.5 |
| IP 校验和 | ✅ | 原 `IpPacket.build` 头部校验和填 0（注释"tun 一般不过校验"是错的），内核 `ip_rcv` 会丢弃 → 所有回灌应答被丢。已按 RFC1071 计算 |
| 并发 | ✅ | DNS 处理从"读循环内同步转发（单条上游超时 5s 阻塞全部查询）"改为 8 线程有界池 + CallerRunsPolicy 反压 |

**仍未覆盖（如实声明）**：仅域名级拦截，URL 路径 / 页面正文 / 关键词看不到（无 MITM）；DoH/DoT 可绕过；VPN 通道必须真机验证。

### 7.2 同批次的其余修复

| 端 | 问题 | 改动 |
|---|---|---|
| 家长端 | 首页把"加载中/加载失败"都渲染成「还没有绑定的孩子设备」 | `HomeUiState.hasLoadedOnce` + 三态渲染（加载中 / 失败可重试 / 真空态）；非空列表刷新失败改挂窄提示"显示上次数据" |
| 家长端 | `UnlockRequestCard` 从未被调用（解锁已改根层全局弹窗），`pendingUnlockTicket` 每次刷新白打一次接口 | 删除组件 + 传参 + VM 的 ticket 加载/忽略逻辑 |
| 孩子端 | `ProtectionLevelCard` 硬编码浅绿/浅黄底，深色模式下是"发光"浅卡 | 改 `primaryContainer` / `errorContainer` |
| 引擎 | `systemLock.autoTimeZone` 无执行点（被 autoTime 取值覆盖） | 新增 `setAutoTimeZoneEnforced()` 并在 `SystemLockEnforcer` 单独对齐 |

## 八、后续（仍未开始）

### sceneMode 的现状（核实结论，不做臆测实现）

`sceneMode`（NORMAL / LEARNING / FOCUS）由家长端切换、服务端 `applyMode` 落库并随策略下发，
但**两端都没有派生任何管控动作**：服务端只改字符串，孩子端把它原样反序列化后再没人读。
也就是说「正常 / 学习 / 专注」三个 chip 目前是纯装饰。

不本轮硬做的原因：孩子端没有任何"应用分类"数据源，LEARNING 无法确定性判定"哪些算学习应用"；
FOCUS 若拍成"仅白名单可开"，在家长未配白名单时会直接变成开不了任何应用（变砖风险）。
这属于产品语义决策，不适合由实现方替产品拍板。**建议**在服务端把 sceneMode 派生为具体策略
（FOCUS → app.mode=WHITELIST 且名单非空才生效；LEARNING → 提高时长/屏蔽娱乐分类），
再让孩子端只做执行，避免两端各自解释。

### 其余待办

Kiosk `isAllowed` 死函数（`KioskEnforcer.isAllowed` 无调用点，非 DO 下 Kiosk 仅上报 UNSUPPORTED）、
家长端订阅页之外的列表三态、孩子端 UnlockRequest 状态色（深色下对比度）、后台 rbac/审计竞态与剩余转义、i18n、God class 拆分。

## 九、第三批（2026-11-02 深夜）：剩余 P2 清理

本轮只做「不改产品语义、可静态验证」的项；需要真机或需要产品拍板的仍然留空并注明原因。

### 9.1 孩子端

| 问题 | 改动 | 为什么值得修 |
|---|---|---|
| `EnforceReport.unsupported` 从不上行（家长看不到"本机不支持"） | 新增 `RiskType.POLICY_DEGRADED` + `PolicyEngine.applyAll` 在 `hasDegradation` 时 `raiseOnce`；服务端 `EVENT_TITLES` 补映射；级别用 **INFO** 不用 WARNING | 报告里"降级"只进本地日志，家长只能看到"已下发/在线"。但降级不能与失败同级：无 DO 的存量机天天一条 WARNING，会让真正的 HIGH（锁屏失效/权限被撤销）一起被无视。去重键用「降级项签名」而非策略版本，否则家长每改一次策略就重报一次 |
| 霸屏倒计时按**总时长**数，不按截止时刻 | `MessageActivity` 增 `deadlineMs` 状态，`beginSession`/`extendSession`/`onNewIntent` 同步；`BlockingFooter` 改按墙钟截止时刻计算 | re-arm 与进程重建复用同一会话，`beginSession` 返回的是剩余时间；音视频延长后差距更大（显示 30 秒、实际 3 分钟）。显示与真实关闭时机必须同源 |
| 消息页首帧闪「没有新消息」 | `MessageListUiState.loaded`；未读完显示 `CircularProgressIndicator` | 空列表有两种含义（还没读到 / 确实没有），此前都渲染成空态 |
| 我的页首帧闪「未绑定 + 软管控（可被卸载）」 | `ProfileUiState.loaded`；`ProtectionLevelCard` 未读完时给中性「正在检测…」，`SoftControlRiskBanner` 加 `loaded` 前置 | DO 设备冷启动会先红一下再翻牌成硬管控，属于吓人的误报 |
| 解锁申请状态色在浅色主题下对比度不足 | `StatusPill` 改 `tertiary/primary/error/onSurfaceVariant` + 12% 同色底胶囊 | 原写死 Amber300/Green400/Red300/Gray500 是"为深色背景调的亮色"，浅色卡片上仅 2.5~2.8:1 |
| 授权页 `DONE`/`NEED_MANUAL` 写死 green800/amber800 | 改 `primary` / `tertiary` | green800 在深色主题上几乎与背景同深浅 |
| `screenshotIntervalSec` 配了完全没执行 | `MonitoringEnforcer` 在 `screenshot > 0` 时 `markUnsupported` | 本端确实没有周期截屏执行器（只有家长主动"看屏"），静默空转比如实告知更糟 |
| `collectUrlHistory` 零执行 | 仅补文档（`PolicyModels`）：本端无 `URL_VISIT` 生产者，且家长端没有"上网记录"页面 | 默认值 true，若据此报降级会给每台设备刷无用告警；当前不存在"界面有、数据无"的欺骗性展示，故不伪造 |
| Kiosk 白名单在非 DO 机型无任何应用层兜底 | `KioskRuntime.userAppCount` + `PolicyEngine.decideKioskBlock`（纯函数）+ `tick` 接入；新增 6 条单测 | 非 DO 时 LockTask 与常驻桌面都不可用，Kiosk 只剩一句"已开启"。三个前置条件锁死防变砖：未开启 / 非降级态 / 白名单 0 个应用 → 一律不拦 |

### 9.2 管理后台

| 问题 | 改动 |
|---|---|
| 服务端监控页字段未转义、可选数组直接取 `.length` | `server.js`：`num()` 归一化 + `instances/qpmHistory/logs` 做 `Array.isArray` 兜底 + 等待失败时给「加载失败/重试」 |
| 设置页数值输入静默吞 0 | `settings.js`：套餐价 `parseInt(v) || 0` → 显式整数域校验（0~1e8），试用期 `|| 14` → 0~365 校验（**填 0 不再被悄悄改成 14**） |
| 设置页 `plans`/`features` 缺失即 TypeError | `settings.js`：数组兜底 + 空态行 + 转义 |
| 审计页筛选竞态 | `audit.js`：`draw(area)` 加 `seq` 序号，慢响应不覆盖新筛选 |
| RBAC 页三 tab 空白无解释 | `rbac.js`：schema/roles/privileges 归一化；`show()` try/catch + 重试；管理员列表改 `await`（此前异常成为未捕获 rejection） |

### 9.3 家长端

| 问题 | 改动 |
|---|---|
| 设备详情加载失败时只剩半张空页 | `DeviceDetailScreen`：非 loading 且 `device == null` → 「加载失败 + 原因 + 重试」 |
| 订阅页首屏失败只有一条 Snackbar，页面剩「选择套餐」标题 + 空白 | `SubscriptionUiState.firstLoadFailed`（订阅与套餐**双双**失败才置位）+ 整页错误态与重试 |
| `usesCleartextTraffic=true` 无任何说明 | Manifest 补注释（与孩子端同口径：局域网部署必须允许明文；公网走 Nginx TLS），避免后续被"顺手修"成连不上 |

### 9.4 本轮明确不做

- **sceneMode 派生**：属产品语义（LEARNING 无分类数据源、FOCUS 空名单会变砖），需服务端先行派生，客户端只做执行。
- **周期截屏执行器**：涉及 MediaProjection 常驻与隐私成本，需独立授权与真机验证。
- **`collectUrlHistory` 采集器**：无障碍读地址栏极脆弱，且家长端无消费页面。
- **i18n / God class 拆分**：结构性改造，与 UI 缺陷不同批次。

### 9.5 验证

- 孩子端：`:core:engine:testDebugUnitTest` 通过（含新增 `PolicyEngineKioskBlockTest` 6 例）、`:app:compileDebugKotlin` BUILD SUCCESSFUL
- 家长端：`:app:compileDebugKotlin` BUILD SUCCESSFUL
- 管理后台：11 个 JS 文件 `node --check` 全通过
- 服务端：`testDirect` 全量回归
