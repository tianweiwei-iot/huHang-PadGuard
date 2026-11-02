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

A6 上网管控 VPN 全链路（需真机 + 一次性授权 + CONSENT 升版）、sceneMode / Kiosk `isAllowed` / autoTimeZone / screenshotIntervalSec 执行点、家长端首页与订阅页三态、解锁工单死代码清理、孩子端深色 30+ 处硬编码、后台 rbac/审计竞态与剩余转义、i18n、God class 拆分。
