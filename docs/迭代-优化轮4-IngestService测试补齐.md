# 迭代优化轮 4 —— 遥测采集入口（IngestService）测试补齐

> 承接优化轮 3（地理围栏服务测试补齐）。本轮继续对**信任关键路径**做复审与门禁补齐。
> 通过测试清单盘点发现：`IngestService`（孩子端全部遥测——行为日志 `saveLogs` / 定位轨迹 `saveLocations` 的**服务端 ingestion 入口**）
> **全仓零单测**，而它喂给今日用量看板缓存、地理围栏越界检测、临时解锁工单、实时 WS 推送与全部统计聚合。
> 一旦回归改坏，会静默污染统计 / 围栏 / 时长核算，且难以从 UI 复现。

## 一、发现的问题与风险

| 编号 | 位置 | 问题 | 严重度 | 处置 |
|------|------|------|--------|------|
| R1 | `IngestService`（`saveLogs` / `saveLocations`） | 整类**零单测**：PIPL 撤回授权停采、logId 批内+跨库双重幂等、UNLOCK_REQUEST 工单去重、按 ts 最大点更新设备坐标、逆地理回填开关、今日用量实时推送触发条件等不变量均无回归守护 | 中（信任关键入口，回归门禁缺口） | 已补 10 例单测 |
| R2 | 测试侧 `newDevice()` fixture | **Kotlin `apply` 阴影陷阱**：`Device().apply { this.userId = userId }` 中 `this` 是 `Device`，右侧 `userId` 被解析为 `Device.userId`（默认 `null`），造成"自赋值"，造出**无主设备**。生产代码 `dev.userId ?: return` 因此提前返回、今日实时推送永不触发——测试本身缺陷，非生产缺陷 | 中（会导致推送路径用例误判为失效） | 已改为 `this@IngestServiceTest.userId` 显式指向测试类字段，并补注释警示 |

## 二、改动位置（纯测试，未动生产代码）

新增 `padguard-server/src/test/kotlin/com/padguard/server/service/IngestServiceTest.kt`（10 例，全绿）：

**saveLogs（行为日志入口）**
1. **空批**：直接返回 `{accepted=0, duplicated=0}`，无任何落库。
2. **撤回授权（PIPL）**：`consentRevoked=true` → 停止采集，返回 `{accepted=0, duplicated=N, revoked=1}`，零落库。
3. **新用量日志入库 + 实时推送**：两条 `USAGE_TOTAL`/`APP_USAGE` 入库，`saveAll` 被调用；
   且 `pushTodayIfNeeded` 触发——先 `invalidateToday(deviceId)` 失效缓存，再 `today(userId, deviceId)` 取最新快照，最后 `pushToUser(userId, snapshot)` 只向该孩子家长推送。
4. **批内+跨库双重幂等**：批内重复 `a`（`distinctBy` 后只剩一个）+ 跨库已存在 `b`（`findExistingLogIds` 返回）→ `accepted=2, duplicated=2`，计数准确。
5. **UNLOCK_REQUEST 工单去重**：两条不同 `logId` 的新鲜解锁请求 → `createFromRequest` 仅对**新行**调用 `times(2)`；该类型不在用量推送集合，故**不触发**今日推送（验证类型白名单精确作用）。

**saveLocations（定位轨迹入口）**
6. **空轨迹**：返回 0，零落库。
7. **撤回授权（PIPL）**：停止采集位置，零落库。
8. **按 ts 最大点更新坐标**：乱序上报（末项 ts 最小、中间 ts 最大）→ 设备最新坐标取 **ts 最大点**而非列表末项，防乱序错位；`detectTransitions` 与 `saveAll` 均被调用；未配置逆地理 Key 则不发起回填。
9. **配置逆地理 Key 才回填**：`geoCoderService.isConfigured()=true` → `LocationAddressBackfill.backfill` 被调用。
10. **未配置逆地理 Key 零开销**：`isConfigured()=false` → `backfill` 永不调用（`verify(never())`）。

## 三、验证

- `:padguard-server:compileTestKotlin` 通过；`IngestServiceTest` **10/10 PASSED**（见 `build/test-results/test/TEST-com.padguard.server.service.IngestServiceTest.xml`：`tests="10" failures="0"`）。
- 路线纪律：Mockito 隔离全部 10 个依赖 + 真实 `LogItem`/`LocationPoint`/`UsageStatsDto`/`Device` 构造，**不加载 Spring 上下文**（规避全量集成 OOM）。
- **踩坑闭环（铁律复用并强化，本轮新增一条）**：
  - Kotlin 非空参数 + Mockito 占位 matcher 必须「带类型 matcher `?:` 同类型默认值」：`any(Pageable::class.java) ?: PageRequest.of(0,1)`、`anyList() ?: emptyList<X>()`、`eq(x) ?: x`；
    裸 `any()` / 缺 `?:` 会触发 Kotlin 非空检查 NPE 并污染 Mockito Matcher 栈，级联 `InvalidUseOfMatchersException` / `UnfinishedVerificationException`。
  - **`apply` 块内 `this` 阴影陷阱（本轮新发现并记入）**：`Device().apply { this.userId = userId }` 的右侧 `userId` 会被解析为 `Device.userId`（默认 `null`），等于自赋值，造出"无主设备"使依赖 `dev.userId ?: return` 的推送路径静默不触发。务必显式 `this@TestClass.field` 指向外层字段，或在 fixture 构造时避免同名遮蔽。

## 四、未做项（候选，待拍板）

- `saveLogs` 的 `DataIntegrityViolationException` 降级逐行 `insertIgnoreConflict` 分支：本轮未构造并发撞 unique 约束的失败场景，可补 1 例验证降级去重。
- `buildUsageTodayPayload` 对 `appUsages` 非空时的字段映射：本轮 `todaySnap.appUsages=null`，未覆盖 `appUsages` 展开分支。
- `saveLocations` 的 `prevPoint`（批前最后一点）参与围栏起点判定的精确语义：本轮只验证 `detectTransitions` 被调用，未断言 `prevPoint` 取值正确。
- 孩子端/家长端 release 接入点与真机回归仍按既定计划待办（见 `MEMORY.md` 待办项）。

## 五、结论

本轮把信任关键且零覆盖的 `IngestService` 纳入回归门禁，10 例单测全绿，覆盖 PIPL 撤回停采、logId 双重幂等、解锁工单去重、坐标更新防乱序、逆地理回填开关与今日实时推送触发条件。
过程中发现并修复的是**测试侧** fixture 的 `apply` 阴影缺陷（非生产缺陷），生产 `IngestService` 行为正确：无主设备本就不该触发推送。
未改动任何生产代码，无回归风险。全部改动在当前工作树、**未提交 git**（遵守并行会话改码约定）。
