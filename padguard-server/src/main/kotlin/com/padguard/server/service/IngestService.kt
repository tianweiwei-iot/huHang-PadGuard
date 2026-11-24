package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.LocationTrack
import com.padguard.server.repository.LocationTrackRepository
import com.padguard.server.domain.UsageLog
import com.padguard.server.dto.LocationPoint
import com.padguard.server.dto.LogItem
import com.padguard.server.dto.LogsRequest
import com.padguard.server.dto.LocationsRequest
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.dto.UsageStatsDto
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UsageLogRepository
import com.padguard.server.ws.WebSocketPushService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class IngestService(
    private val usageLogRepository: UsageLogRepository,
    private val deviceRepository: DeviceRepository,
    private val objectMapper: ObjectMapper,
    private val geofenceService: GeofenceService,
    private val unlockService: UnlockService,
    private val statisticsService: StatisticsService,
    private val webSocketPush: WebSocketPushService,
    private val locationTrackRepository: LocationTrackRepository,
    private val geoCoderService: GeoCoderService,
    private val locationAddressBackfill: LocationAddressBackfill
) {
    private val log = LoggerFactory.getLogger(IngestService::class.java)

    /** 哪些日志类型的变化会影响今日用量看板：只有这些才值得触发一次 usage.today 推送 */
    private companion object {
        val USAGE_PUSH_TYPES = setOf("USAGE_TOTAL", "APP_USAGE", "URL_VISIT", "URL_BLOCKED")
        /** S-4：单次日志上报的条数硬上限（防御 OOM / 连接池占用） */
        const val MAX_LOGS_PER_BATCH = 2_000
        /** S-4 同款：单次轨迹点硬上限（轨迹批只增不删、单点含 address 回填，内存放大比日志更明显） */
        const val MAX_POINTS_PER_BATCH = 2_000
    }
    /**
     * 设备上报日志查询（家长端「通知记录」等台账读取）。
     *
     * 归属校验由调用方（Controller 经 [DeviceService.getDevice]）完成——
     * 本方法只负责按设备 + 类型取时间倒序的前 [limit] 条（钳制 1..500）。
     * 返回 [UsageLog] 实体，Controller 映射为仅含元数据的精简视图（不含通知正文）。
     */
    fun listDeviceLogs(deviceId: String, type: String, limit: Int): List<UsageLog> =
        usageLogRepository.findByDeviceIdAndTypeOrderByTimestampDesc(
            deviceId, type, PageRequest.of(0, limit.coerceIn(1, 500))
        )

    /**
     * 孩子端批量上报行为日志：按 logId 幂等去重；UNLOCK_REQUEST 转临时解锁工单。
     *
     * ## 为什么必须批量处理
     * 孩子端是**批量**上报（单次可达上百条）。原先逐条 `existsByLogId` + 逐条 `save`，
     * 一次上报就是 2N 次数据库往返；日志表又只增不删、体积最大，于是单次上报耗时长达数秒，
     * 期间占住连接，家长端的请求只能在连接池里排队 —— 表现就是"点什么都要转好几秒"。
     *
     * 改为：**一次查重 + 一次批量写入**（配合 hibernate.jdbc.batch_size，
     * Hibernate 会把 INSERT 攒批下发，往返次数再从 N 降到 N/batch_size）。
     */
    fun saveLogs(req: LogsRequest): Map<String, Int> {
        val items = req.logs
        if (items.isEmpty()) return mapOf("accepted" to 0, "duplicated" to 0)
        // S-4：单次批量上限，防御恶意/异常设备发送超大批次（整批读入内存去重会放大内存压力）。
        // 超过即拒绝，避免服务端在连接线程上长时间占住连接池。
        if (items.size > MAX_LOGS_PER_BATCH) {
            throw BizException(ParentErr.PARAM_ERROR, "日志批量过大（上限 $MAX_LOGS_PER_BATCH 条）", Audience.CHILD)
        }

        // 撤回授权后停止采集（PIPL 撤回同意）：该设备的个人数据不再落库。
        // 设备在线状态由心跳链路单独维护，不受影响。
        val dev = deviceRepository.findById(req.deviceId).orElse(null)
        if (dev?.consentRevoked == true) {
            return mapOf("accepted" to 0, "duplicated" to items.size, "revoked" to 1)
        }

        // 批内也要去重：同一批里出现重复 logId 会撞 unique 约束，导致整批失败
        val candidates = items.distinctBy { it.logId }

        // 一次查询取回本批中已存在的 logId，取代逐条 existsByLogId
        val knownLogIds = usageLogRepository.findExistingLogIds(candidates.map { it.logId })
        val fresh = candidates.filter { it.logId !in knownLogIds }
        if (fresh.isEmpty()) {
            return mapOf("accepted" to 0, "duplicated" to items.size)
        }

        val now = System.currentTimeMillis()
        // S8：批量写入；若并发上报同一 logId 撞 unique 约束导致整批失败，
        // 降级为逐行 ON CONFLICT DO NOTHING，只跳过真正已存在的行。
        val inserted = try {
            usageLogRepository.saveAll(fresh.map { item ->
                UsageLog(
                    id = UUID.randomUUID().toString(), deviceId = req.deviceId, logId = item.logId,
                    type = item.type, timestamp = item.timestamp,
                    payloadJson = item.payload?.let { objectMapper.writeValueAsString(it) },
                    receivedAt = now
                )
            })
            fresh
        } catch (e: org.springframework.dao.DataIntegrityViolationException) {
            log.warn("usage_logs 批量写入撞唯一约束（并发上报竞态），降级逐行 upsert: {}", e.message)
            fresh.filter { item ->
                usageLogRepository.insertIgnoreConflict(
                    id = UUID.randomUUID().toString(), deviceId = req.deviceId, logId = item.logId,
                    type = item.type, timestamp = item.timestamp,
                    payloadJson = item.payload?.let { objectMapper.writeValueAsString(it) },
                    receivedAt = now
                ) > 0
            }
        }

        // 只对真正新增的日志建工单；重复上报不应刷出一堆解锁工单
        inserted.filter { it.type == "UNLOCK_REQUEST" }
            .forEach { unlockService.createFromRequest(req.deviceId, it.payload) }

        // 今日用量看板实时推送：孩子端上行了用量类日志，立即把最新今日快照经 WS 推给家长端，
        // 家长端无需轮询即实时刷新（"今日平板使用情况"实时 + 不占轮询资源）。
        // 推送失败绝不拖累入库：整段兜底，且只在确有 usage 变化时触发。
        pushTodayIfNeeded(req.deviceId, inserted)

        return mapOf("accepted" to inserted.size, "duplicated" to items.size - inserted.size)
    }

    /**
     * 上行中包含用量类日志时，计算该设备最新今日用量并经 WS 推送给其家长。
     *
     * - 先 [StatisticsService.invalidateToday] 失效缓存，保证推的是刚入库后的最新值；
     * - [WebSocketPushService.pushToUser] 只向"当前已打开 WS 的该家长会话"发，家长端没连则不发（零浪费）；
     * - 整段 runCatching：推送是增强能力，任何异常都不该让日志入库失败。
     */
    private fun pushTodayIfNeeded(deviceId: String, fresh: List<LogItem>) {
        if (fresh.none { it.type in USAGE_PUSH_TYPES }) return
        val dev = deviceRepository.findById(deviceId).orElse(null) ?: return
        val userId = dev.userId ?: return
        runCatching {
            statisticsService.invalidateToday(deviceId)
            val snap = statisticsService.today(userId, deviceId)
            webSocketPush.pushToUser(userId, buildUsageTodayPayload(snap))
        }.onFailure {
            log.warn("usage.today 实时推送失败（已忽略）: {}", it.message)
        }
    }

    /** 把今日用量快照序列化成 WS 推送报文，字段与客户端 UsageStatsDto 完全一致（多一个 type 标识） */
    private fun buildUsageTodayPayload(snap: UsageStatsDto): Map<String, Any?> = mapOf(
        "type" to "usage.today",
        "deviceId" to snap.deviceId,
        "date" to snap.date,
        "totalUsageMinutes" to snap.totalUsageMinutes,
        "appUsages" to (snap.appUsages?.map { a ->
            mapOf(
                "packageName" to a.packageName,
                "appName" to a.appName,
                "usageMinutes" to a.usageMinutes,
                "iconUrl" to a.iconUrl,
                "startAt" to a.startAt,
                "endAt" to a.endAt,
                "launchCount" to a.launchCount
            )
        } ?: emptyList<Any>())
    )

    /** 孩子端上报定位轨迹：批量入库 + 围栏检测；同时更新设备最新位置 */
    fun saveLocations(req: LocationsRequest): Int {
        val points = req.points
        if (points.isEmpty()) return 0
        // S-4 同款：单批上限，防御恶意/异常设备发送超大批次（saveAll 会整批物化实体，内存放大）。
        if (points.size > MAX_POINTS_PER_BATCH) {
            throw BizException(ParentErr.PARAM_ERROR, "轨迹批量过大（上限 $MAX_POINTS_PER_BATCH 点）", Audience.CHILD)
        }

        // 撤回授权后停止采集位置轨迹（PIPL 撤回同意）。
        val dev = deviceRepository.findById(req.deviceId).orElse(null)
        if (dev?.consentRevoked == true) return 0

        // 入口坐标钳制：lat∈[-90,90]、lng∈[-180,180]，非有限值（NaN/±Inf）置 null。
        // 心跳链路早有同款裁剪（见 DeviceService.applyHeartbeat），轨迹主路径漏配——
        // 畸形坐标会原样落轨迹表、污染设备最新位置，并让 haversine 围栏判定得出错误结果
        // （999° 会被当成合法弧度参与三角函数），误触 GEOFENCE_ENTER/EXIT 告警。
        val sanitized = points.map { p ->
            p.copy(
                lat = p.lat?.takeIf { it.isFinite() }?.coerceIn(-90.0, 90.0),
                lng = p.lng?.takeIf { it.isFinite() }?.coerceIn(-180.0, 180.0)
            )
        }

        // 批前最后一点：必须在批量落库「之前」取，否则落库后该点会被本批 newer 点覆盖。
        // 它代表「本批之前设备上最后已知位置」，是判断批次起点是否刚越出围栏的依据。
        val prevPoint = locationTrackRepository
            .findByDeviceIdOrderByTsDesc(req.deviceId, PageRequest.of(0, 1))
            .firstOrNull()

        // 批量落库：单次 saveAll 取代逐点 save，配合 hibernate.jdbc.batch_size 把 DB 往返从 N 降到 N/batch_size。
        // 落库阶段**不做**逆地理编码：逆地理是外部 HTTP 调用，若直接嵌在写入路径，一旦配置高德 Web Key，
        // 每批可达数百个点会变成数百次同步请求，频控 / 超时将直接拖慢入库。
        // 坐标先以纯数据落库（address 暂留空），地址反查交由 [LocationAddressBackfill] 在后台异步回填。
        val saved = locationTrackRepository.saveAll(
            sanitized.map { p ->
                LocationTrack(
                    id = UUID.randomUUID().toString(), deviceId = req.deviceId,
                    lat = p.lat, lng = p.lng, accuracy = p.accuracy,
                    provider = p.provider, ts = p.ts, address = null,
                    fresh = p.fresh,
                    receivedAt = System.currentTimeMillis()
                )
            }
        )

        // 仅当配置了高德 Web Key 才需要回填地址；默认（无 Key）不发起任何异步任务，零开销。
        if (geoCoderService.isConfigured()) {
            val toGeocode = saved.mapNotNull { e ->
                if (e.lat != null && e.lng != null) {
                    LocationTrackAddressInput(e.id, e.lat!!, e.lng!!)
                } else null
            }
            if (toGeocode.isNotEmpty()) {
                locationAddressBackfill.backfill(toGeocode)
            }
        }

        // 围栏进入/离开检测（多围栏，内存批处理，仅一次回查）
        geofenceService.detectTransitions(req.deviceId, sanitized, prevPoint)

        // 更新设备最新位置（取本批中 ts 最大者，而非简单取列表末项，避免乱序上报）
        val last = sanitized.maxByOrNull { it.ts ?: 0L } ?: sanitized.last()
        deviceRepository.findById(req.deviceId).ifPresent { dev ->
            last.lat?.let { dev.latitude = it }
            last.lng?.let { dev.longitude = it }
            deviceRepository.save(dev)
        }
        return points.size
    }
}
