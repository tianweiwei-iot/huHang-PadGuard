# PadGuard UI 功能项实现度核查报告与开发计划

> 核查日期：2026-10-17
> 核查范围：`护航成长`（孩子/被管控端）、`护航控制端`（家长端）、`padguard-admin`（运营管理后台），
> 交叉验证至 `padguard-server`（Kotlin 后端）。
> 判定口径：**已实现**＝点击后链路完整触达服务端或系统 API；**部分实现**＝有 UI 有响应但链路断、
> 缺持久化、或缺后端执行点；**未实现**＝空壳点击 / TODO / 写死假数据 / 策略有字段但引擎不执行。

---

## 一、总体结论

| 端 | UI 交互项（约） | 已实现 | 部分实现 | 未实现 | 主要问题类型 |
|---|---|---|---|---|---|
| 护航控制端（家长端） | 100+ | 大部分 | 2 | 3 | 缺落库、置灰未开发项、示例支付 |
| 护航成长（孩子端） | 90+ | 大部分 | 4 | 8 | 空 lambda、写死状态、策略无执行点 |
| padguard-admin（后台） | 60+ | 大部分 | 6 | 5 | 死按钮、假图表、后端字段写死、无分页 |

**好消息**：三端均不存在大面积"装饰性空壳"。未完成项普遍采取"置灰 + 敬请期待"或如实弹窗说明的
降级处理，欺骗性风险集中在少数几处。**坏消息**：真正危险的是那些"看起来成功了但其实没生效"的项
—— 它们比空白更能造成误导。

---

## 二、本轮已修复（P0，共 7 类 12 处）

### 2.1 孩子端

| # | 问题 | 根因 | 修复 | 文件 |
|---|---|---|---|---|
| 1 | **协议「暂不同意」是死路**——点了没反应、点外部也关不掉，设备永久卡死在弹窗 | `AgreementViewModel.decline()` 空方法体 + `AgreementDialog.onDismissRequest = {}` + 入口挂载条件只看持久化同意位，三者叠加无出口 | 新增会话级状态 `AgreementSessionState`（`@Singleton`，进程内不落库），decline 翻转它，入口挂载条件改为 `agreed != true && !declined`。冷启动自动复位重新征求，符合注释所述语义 | 新增 `agreement/AgreementSessionState.kt`；改 `AgreementViewModel.kt`、`MainActivity.kt` |
| 2 | **首页在线状态恒显"在线"**——断网/Token 失效/MQTT 掉线全部看不出来 | `HomeViewModel.kt:104-105` 写死 `"在线"`/`true` | 接入 `RemoteDataSource.isOnline()` 快照 + `Downlink.ConnectivityChanged` 事件流。因 `combine` 最多支持 5 路上游，将昵称与在线态先合流为 Pair 保持类型安全。默认值改为 `"连接中"/false`，避免未就绪时撒谎 | `ui/home/HomeViewModel.kt` |
| 3 | **我的页三个入口点了没反应**（应用管理 / 帮助与反馈 / 关于），却带右箭头暗示可点 | `SimpleRow` 未传 `onClick`，落到默认空 lambda | ①应用管理 → 新建只读台账页 `AppInventoryScreen`（复用 `AppInventoryCollector` + `PolicyRepository` 策略，展示受限/限额状态）+ 新路由 `Route.AppInventory`；②帮助 → FAQ 弹窗（如实说明本端无在线反馈通道）；③关于 → 读取真实 `PackageInfo` 版本号 | 新增 `ui/apps/AppInventoryViewModel.kt`、`ui/apps/AppInventoryScreen.kt`；改 `ui/profile/ProfileScreen.kt`、`ui/nav/Routes.kt`、`ui/nav/MainScaffold.kt` |

### 2.2 管理后台

| # | 问题 | 根因 | 修复 | 文件 |
|---|---|---|---|---|
| 4 | **设备详情抽屉两个按钮是死按钮**（远程锁屏 / 查看家长） | `openDetail()` 只 `drawer({...})` 渲染，从未 `querySelector().onclick` 绑定；同页列表的同类按钮却是好的 | 绑定事件：锁屏复用列表页同一套 API 与二次确认，"查看家长"携带号码跳 parents 页 | `views/devices.js` |
| 5 | **告警铃铛角标恒为 0** | 取值来自 `PG.Mock.alerts`，去 mock 重构时被清空，此处漏改 | 改为 `await PG.api.alerts()` 真实接口；取不到时隐藏角标而非显示假的 0 | `app.js` |
| 6 | **顶栏全局搜索是伪搜索**——吃掉输入只跳转，关键字不传递 | keydown 里直接 `router.go("parents")` | 关键字写入 `PG.state.search`，目标页 mount 回填搜索框后清空 | `app.js` + `views/parents.js` |
| 7 | **接口报错时页面永久卡"加载中…"**——RBAC 下低权限管理员必然 403，却完全不知道自己没权限 | `router.render` 的 `await v.mount(root)` 无 try/catch，而 api.js 契约是"错误码如实上抛不降级" | 抽出 `mountSafely()`：403 显示"没有访问权限"，其他显示"加载失败 + 原因"，均提供重试按钮 | `router.js` |
| 8 | **系统设置币种不回显**——服务端返回 USD 仍显示 CNY，管理员保存即静默写回 CNY | `<select>` 写死两个 option 未设 selected | 按 `s.currency` 动态生成选中态 | `views/settings.js` |
| 9 | **手动开通订阅未剔除免费试用**——运营可误把付费家长降到试用 | 过滤写的是 `p.id !== "free"`，而实际 key 是 `TRIAL`，条件恒不成立 | 改为 `/^(TRIAL\|FREE)$/i` 匹配真实 key | `views/billing.js` |
| 10 | **XSS 隐患**——`badge()` 未转义 | 映射未命中时把原始 key（DB 字段）直接插入 HTML | 对 class 与文案均转义 | `components.js` |

### 2.3 家长端

| # | 问题 | 根因 | 修复 | 文件 |
|---|---|---|---|---|
| 11 | **护眼开关每次进页面都被显示成"关闭"**，下次保存又把 false 写回服务端 | `ControlPolicyViewModel.load()` 在 `getWebPolicy` 成功后硬编码 `eyeProtectionEnabled=false, level=2` 覆盖 UI 状态 | 停止覆盖（护眼属独立 `device_settings`，不在 getWebPolicy 返回里）；根治需服务端补 GET 端点，见 §4.2 | `viewmodel/ControlPolicyViewModel.kt` |

**验证状态**：后台 7 个 JS 文件已通过 `node --check` 语法校验；孩子端 Kotlin 编译待出结果。

---

### 2.4 全链路打通：定时锁机（原「部分实现」，现已闭环）

**症状**：家长端开关"定时锁机"弹出"已开启"提示，实际开关从未生效。
逐层溯源发现这条链路上断了 **四处**，任何一处没补都无法真正生效：

| 层级 | 原状态 | 修复 |
|---|---|---|
| 家长端 ViewModel | `setSmartShutdown()` 只改内存 `_uiState`，无持久化，离开页面即丢失 | 改为先写服务端、成功才更新 UI；失败如实报错，不再先给成功假象再悄悄回退 |
| 服务端写入端点 | 无 `PUT` 端点，只有读 | 新增 `PUT /v1/policies/{deviceId}/web/smart-shutdown`，含 `HH:mm` 格式校验（非法值会造成"存了但不生效"，必须在入口拦掉） |
| 服务端**下发**给孩子端的策略包 | **组装下发包时漏了这三个字段**（存了库、也回读给家长端，唯独不下发给孩子端） | `PolicyExtensionService` 的 `web` 段补上 `smartShutdownEnabled/StartTime/EndTime` |
| 孩子端策略模型 | `WebPolicy` 根本没有这三个字段，下发过来也被反序列化丢弃 | 补齐字段 |
| 孩子端执行 | 无判定逻辑 | 新增 `SmartShutdownEnforcer`（支持跨零点区间，非法输入一律不锁），在 `PolicyEngine.tick` 中以独立的 `LockReason.SMART_SHUTDOWN` 接入 |

**为什么单独一个 LockReason 而不并入 SCHEDULE**：时段锁是一张规则表（多条、可按周几分天），
定时锁机是一个单区间开关，两者在家长端是互不相关的两个 UI；合并会让其中一个去改写另一个的数据源。

**验证**：新增 `SmartShutdownEnforcerTest` **12 个用例全通过**，覆盖三个最危险的边界 ——
跨零点区间在前半夜是否锁、解除时刻是否落在正确的一天、非法/残缺输入是否一律不锁
（返回 0 或 true 会把设备锁死一整天）。既有 12 个用例无回归。

---

## 三、剩余问题清单（未在本轮修复）

### 3.1 P1 —— 策略明明配了、执行层却空转（孩子端最严重）

这类问题的共同形态是：**协议/DTO 有字段 → 家长端 UI 有开关 → 服务端能存能下发 → 孩子端收得到存得下 → 但没人执行**。
家长以为配了就生效，实际完全不起作用，且无任何错误提示。

| # | 策略项 | 现状 | 证据 | 修复要点 |
|---|---|---|---|---|
| E1 | **`web.*` 整条上网管控**（黑白名单 / 关键词 / 分类拦截 / safeSearch，共 8 字段） | 端上**没有任何 URL 判定通道**。`WebEnforcer` 只在 `applyAll` 编译规则，`match()` / `rewriteSearchUrl()` 全项目零调用；无障碍服务不处理地址栏 | `WebEnforcer.kt:52-101,109-148`；`OperationGuardAccessibilityService` 无 URL 处理 | 方案对比见 §4.1。建议走 `VpnService` 本地拦截（无需 root、不依赖浏览器包名），回调 `policyEngine.matchUrl()` |
| E2 | **`kiosk.watermark.*`（4 字段）** | 策略里的水印永不生效。当前唯一驱动点是 `SET_WATERMARK` **指令**（`GuardService.kt:1278`），与策略包互不相通 → 设备重启后策略水印不恢复 | `KioskEnforcer.kt:68,101`；`PolicyEngine.kioskRuntime():434` 零调用 | `GuardService` 在 `applyAll` 后读 `kioskRuntime().watermark` 驱动 `WatermarkOverlay`；`WatermarkOverlay.show()` 增加 position/opacity 参数（现硬编码 `Gravity.TOP\|START`、`alpha=0.45f`） |
| E3 | **`sceneMode`（NORMAL/LEARNING/FOCUS）** | 存进 StateFlow 后**零消费**，纯粹死字段 | `PolicyEngine.kt:110-111,174` | 二选一：在 `applyAll` 里按 mode 派生策略；或从协议里删掉，避免给家长"配置了就生效"的错觉 |
| E4 | **`app.blacklistAction`（BLOCK_DIALOG / SILENT_CLOSE）** | 仅 Mock 引用。无论家长选哪种，`AppBlockActivity` 都弹拦截页 | `PolicyModels.kt:135,223` | `EngineTick.blockedReason` 附带 action，`GuardService` 分流到拦截页 or 回桌面 |
| E5 | **`monitoring.collectUrlHistory` / `collectHardware`** | 开关收得下但没人读；`collectUrlHistory` 与 E1 同源 | `MonitoringEnforcer.kt:83` | `collectHardware` 接 `DeviceSnapshotCollector`；`collectUrlHistory` 依赖 E1 的 URL 通道 |
| E6 | **`PolicyEngine` 一批只读 API 悬空**（`isKioskAllowed` / `activePeripheralOverrides` / `minorModeVerdict` / `curfewRule` / `skipEyeCareRest` 等 7 个） | 只有定义、零调用方。其中 `isKioskAllowed` 缺失意味着 Kiosk 只在系统 LockTask 层生效，**应用层没有非白名单拦截兜底** | `PolicyEngine.kt:434-462` | 无障碍服务启动判定接 `isKioskAllowed`；心跳上报带 overrides；确认冗余项后删除 |
| E7 | `web.blockPopup` | `WebEnforcer.kt:89-91` 明确 `markUnsupported` | 属如实降级 | 建议同步给家长端把开关置灰，避免家长开了以为生效（需 EnforceReport 上报 UI） |

### 3.2 P1 —— 家长端

| # | 问题 | 现状 | 修复要点 |
|---|---|---|---|
| A1 | **定时锁机（夜间锁机）不落库**——家长看到"已开启定时锁机" toast，实际服务端与孩子端都收不到 | `setSmartShutdown()` 只改内存 `_uiState`，无任何 repository 调用 | **好消息**：后端 `ExtraModels.kt:196-198` 已有 DB 字段、`PolicyExtensionService.kt:259-261` 已下发、家长端 `Dtos.kt:428-430` 已有字段映射。**只缺写入链路**，详见 §4.2 |
| A2 | 护眼状态无读取端点 | 只有 PUT 没有 GET | 服务端补 `GET /policies/{deviceId}/eye-protection`，前端 load 接真值 |
| A3 | 短信登录 / 获取验证码 | UI 主动隐藏（服务端 `sms-login-enabled=false` 且无短信服务商），属诚实下线；但 `LoginModeToggle` / `SmsLoginForm` / VM 死方法仍残留 | 删除死代码或加 `@Deprecated` 说明，避免后续误接 |
| A4 | 家庭成员 / 设置 | UI 置灰 + "敬请期待"，导航 `when` 分支为空 | 要么实现要么彻底移除入口 |
| A5 | 订阅支付是示例支付 | `mockPay` + 本地兜底渠道 `mock=true`，但 UI 只标了小字"示例支付"，而服务端真的开通了订阅 | 正式包 `fallbackChannels()` 加环境判断；接入真实渠道与轮询查单 |
| A6 | 4 条未注册路由 | `Screen.Home / DeviceList / Statistics / Profile` 声明了但根 NavHost 未注册，`navigate(Screen.Home.route)` 会直接抛 `IllegalArgumentException`；同时 `MainScreen` 用裸字符串 `"devices"`，双套并存易误用 | 统一路由常量表 |

### 3.3 P1/P2 —— 管理后台

| # | 问题 | 现状 | 修复要点 |
|---|---|---|---|
| B1 | **设备"今日用量"恒为 0、"区域"恒为空串** | `AdminDeviceService.kt:46-54` 后端写死 `todayUsageMinutes = 0, region = ""`；前端抽屉显示 `0h0m` | 后端聚合当日 usage_sessions；region 取 `owner?.region`；取不到时 UI 显示"—"（不要显示假的 0h0m） |
| B2 | **仪表盘 KPI 走势图与"环比 +12%"是写死数组** | `dashboard.js:16-19` 四条 sparkline 全是字面量数组，趋势文案硬编码且方向自相矛盾（down 配 +12%） | 后端 `DashboardResponse` 增 trend 字段；前端取不到就**不渲染**，而非 fallback 到假数组 |
| B3 | **订阅/订单/审计的状态与领域筛选走前端 filter** | 服务端已支持 `?status=` / `?area=`，前端传空对象全量拉取；审计后端 `take(200)` 硬截断且前端无提示 | 改为带参请求 |
| B4 | **所有列表无分页** | parents/devices/billing/audit 均全表 `findAll()` | 后端加 `page/size` + `total`；前端加 `pager` 组件 |
| B5 | 审计领域下拉漏 `audit` | 前端硬编码 6 项，后端 `AREA_DEFS` 有 7 项 | 改用 `permissionSchema()` 生成（rbac.js 已是正确做法） |
| B6 | **退出登录不做服务端吊销** | `AdminController` 无 logout 端点，JWT 到期前仍有效 | 加 token 黑名单/version 机制 + `POST /v1/admin/logout` |
| B7 | 设备"解绑"无 UI 也无端点 | 后端权限 `devices:unbind` 已定义但未落 endpoint | 补 endpoint + 前端按钮 |
| B8 | 前端无任何权限隐藏 | 所有管理员都能看到并点击"删除角色/退款/改设置"，点了才被后端拒 | 登录后拉 privileges 隐藏无权按钮 |
| B9 | `api.js` 的 `ENDPOINTS` 常量是死代码且严重过期（缺 10+ 条） | 无读取点 | 删除或按实际补齐 |

### 3.4 其他风险

- **`ScheduleEvaluator.DefaultHolidayProvider` 恒返回 false**：`DayType.HOLIDAY` 判定完全失效，节假日/调休场景不生效（`ScheduleEvaluator.kt:161-166`）。
- **`UsageSessionUi` 常驻 1s 心跳协程**：页面不可见时仍运行（`UsageSessionUi.kt:120-125`）。
- **两处独立的未成年人模式派生**：`HomeViewModel.coerceAppLimit` 与 `PolicyEngine.minorModeEnforcer.deriveBaseline` 各算一套，存在口径漂移风险。
- **`LockScreenActivity` 抢占复位 8s 后停止**：非 DO 设备上存在"锁屏页没顶上来"的时间窗。
- **`MessageActivity` 通知 id 生成**：`currentTimeMillis().toInt()` 已溢出为有符号 int，分布不可预测。
- **孩子端 `strings.xml` 仅 4 条**：全部文案硬编码在 Composable 里，多语言与品牌名改造成本高。
- **`OverlayWatermark` 双通道**：指令水印与策略水印互不相通（见 E2）。
- **后台 token 仅存内存**：刷新即被踢回登录页，无过期处理。

---

## 四、分批实施计划

### 批次 1（已完成，本轮）

见 §二：7 类 12 处 P0 修复 + A1 定时锁机全链路打通。

**工具链验证结果**
| 端 | 验证方式 | 结果 |
|---|---|---|
| 护航成长（孩子端） | `./gradlew :app:compileDebugKotlin` | BUILD SUCCESSFUL |
| 护航控制端（家长端） | `./gradlew :app:compileDebugKotlin` | BUILD SUCCESSFUL（仅有一条与本次改动无关的既有 deprecation 警告） |
| padguard-server | `./gradlew compileKotlin` | BUILD SUCCESSFUL |
| padguard-admin | `node --check`（7 个改动文件） | 全部通过 |
| core:engine 单元测试 | `./gradlew :core:engine:testDebugUnitTest` | 24/24 通过（新增 12 例，既有 12 例无回归） |

### 批次 2：策略执行层接线（孩子端，工作量最大）

> 其中 **A1 定时锁机已在本轮全部打通**（见 §2.4），可直接进入验收：
> 家长端开启 22:00–07:00 → 服务端 `smart_shutdown_enabled=true` →
> 策略包下发该字段 → 孩子端到点自动锁屏 → 家长端重进页面仍显示开启。

#### 4.1 E1 上网管控通道：方案对比

| 方案 | 原理 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **A. VpnService 本地拦截** | App 自建本地 VPN，按包名分流做 DNS/HTTP 判定，命中即断开或返回拦截页 | 覆盖**所有**浏览器与 App 内 WebView；不需要 root / DO；不依赖浏览器包名；Android 5+ 可用 | 需要用户同意 VPN 授权（首次弹系统框）；需自行实现 HTTP 解析；有轻微性能开销 | **推荐** |
| B. 无障碍读地址栏 | 用无障碍服务遍历浏览器 URL 控件 | 实现简单、无额外权限 | 只能覆盖少数几个已知浏览器包名；WebView 完全无解；不同浏览器控件 id 各异，维护成本高 | 不推荐单独使用 |
| C. 强制自有浏览器 | 只允许孩子用内置安全浏览器 | 判定完全可控 | 需要自研浏览器，成本极高；破坏用户体验 | 不推荐 |

**实施步骤（方案 A）**
1. 新建 `core/engine/.../WebGuardVpnService.kt`，继承 `VpnService`，establish 本地 tun；
2. 按 uid 查包名（Android 提供 `getPackagesForUid`），只对 `WebPolicy` 管辖范围（默认全部）做解析；
3. HTTP 明文请求解析 Host + Path（HTTPS 用 SNI + DNS 拦截，DOM 级内容不可见这一点要在协议与隐私政策中如实声明）；
4. 命中黑名单/关键词/分类 → 断开连接并回调 `policyEngine.matchUrl(url, title)` 触发拦截页；白名单模式反向判定；
5. 一次性授权流水线增加 VPN 授权项，`ConsentItem` 新增 `VPN`，`CONSENT_VERSION_CURRENT` **必须 +1**（存量设备补跑）；
6. 家长端 `MonitoringScreen` 增加"上网防护未开启"的自检卡（VPN 未授权时如实提示）。

**验收标准**：家长端添加 `example.com` 到黑名单 → 孩子端任意浏览器访问被拦截并出拦截页 → 行为日志出现 `URL_BLOCKED` → 家长端"最近拦截"可见。

#### 4.2 A1 定时锁机写入链路

后端已有存储与下发，**只缺一条写链路**，是全清单里性价比最高的一项。

```
① PolicyApi         新增 PUT policies/{deviceId}/web/smart-shutdown
② Repository        新增 suspend fun setSmartShutdown(deviceId, enabled, start, end): Result<Unit>
③ ApiDataSource     实现（参考同文件已有的 web 策略写入方法）
④ ParentComplianceController  新增 @PutMapping("/policies/{deviceId}/web/smart-shutdown")
⑤ PolicyExtensionService      新增 saveSmartShutdown(userId, deviceId, req)
⑥ ControlPolicyViewModel      setSmartShutdown 改为调用 repository，成功后才更新 UI
⑦ 孩子端 SystemLockEnforcer / PolicyEngine  在 tick 里按 [start,end] 判定并触发锁屏
```

**验收**：家长端开启 22:00–07:00 → 服务端 `smart_shutdown_enabled=true` → 孩子端策略包含该字段 →
到点自动锁屏 → 家长端重新进入页面仍显示开启（不再丢失）。

### 批次 3：后台数据真实性 + 分页

1. **B1** 设备今日用量/区域接真数据（纯后端）；
2. **B2** `DashboardResponse` 增加 `parentTrend7d / deviceTrend7d / subTrend7d / monthRevenueMom`，前端改为无数据不渲染；
3. **B3+B4** 后端各 list 端点加 `page/size/total`，前端把本地 filter 改为带参请求，并引入统一 `pager` 组件（parents / devices / billing-orders / billing-subs / audit 五处）；
4. **B5** 审计下拉改用 `permissionSchema()`。

### 批次 4：收敛与清理

1. **A3/A4/A6** 家长端死代码与未注册路由清理；
2. **E3/E6** `sceneMode` 二选一处理、悬空只读 API 清理；
3. **B9** 后台 `ENDPOINTS` 死常量删除；
4. **曹 Routing/string 治理**：孩子端 UI 文案入 `strings.xml`；两端统一路由常量表；
5. **B8** 后台权限隐藏（体验优化，非安全缺陷——后端闸门已在各 Service 首行）；
6. **A5** 正式包禁用示例支付兜底渠道。

### 批次 5：安全加固

1. **B6** 后台登出吊销 + token version；
2. **B7** 设备解绑 endpoint + UI；
3. 会话存储改 sessionStorage + 启动静默校验；
4. token 过期自动跳登录；
5. `UsageSessionUi` 心跳协程加生命周期感知。

---

## 五、建议的下一步

按当前症状对用户的伤害程度排序：

1. **下一步建议做**：批次 2 的 **E1 上网管控**（8 个协议字段、0 执行）——用户最容易察觉
   "配了不管用"的缺口，也是全清单里唯一"整条策略完全空转"的项；方案见 §4.1，推荐 `VpnService` 方案；
2. **随后**：批次 3 后台数据真实性（今日用量恒 0、KPI 走势图写死）——影响运营决策正确性；
3. 批次 4/5 随版本迭代推进。

> E1 涉及新增 VPN 授权与 `CONSENT_VERSION` 升版（存量设备需补跑一次性授权），属于跨端改动，
> 建议单独排一个迭代。如对以上优先级或 E1 的方案选型有不同意见，告诉我即可调整。
