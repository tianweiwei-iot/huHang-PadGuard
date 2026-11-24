package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Alert
import com.padguard.server.dto.AlertDto
import com.padguard.server.repository.AlertRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.ws.WebSocketPushService
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class AlertService(
    private val alertRepository: AlertRepository,
    private val deviceRepository: DeviceRepository,
    private val webSocketPush: WebSocketPushService,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(AlertService::class.java)

    /**
     * 告警风暴防护（R-P2-14）：同一 `(deviceId, category)` 在 [cooldownMs] 窗口内只建一次主动告警，
     * 避免单个行为异常设备的重复触发把家长端红点刷屏、淹没真正需要关注的风险（告警疲劳）。
     * 仅作用于服务端主动建告警的 [create] 路径——含 R-P0-3 新增的 `FORGED_UPLINK` 风暴源、
     * 围栏进出、离线/守护进程失联等高频重复场景。
     * 孩子端事件走 [createFromEvent]，源头已做边沿去抖，且既有测试依赖同类别重复建警，故不对其施加冷却。
     */
    @Value("\${padguard.alert.category-cooldown-ms:600000}")
    private val cooldownMs: Long = 600_000L

    private val lastRaisedAt = ConcurrentHashMap<String, Long>()

    /** 孩子端上报事件后建告警（事件级 HIGH/NORMAL/INFO -> 告警 CRITICAL/WARNING/INFO） */
    fun createFromEvent(
        deviceId: String, eventType: String, eventLevel: String, detail: Map<String, Any?>?
    ): Alert? {
        val level = when (eventLevel) {
            "HIGH" -> "CRITICAL"
            "NORMAL" -> "WARNING"
            else -> "INFO"
        }
        val now = System.currentTimeMillis()
        // 兜底**不能**把英文枚举名当标题：家长端把 title/message 原样展示，
        // 一旦孩子端的 RiskType 新增而这里漏登记，家长看到的就是一条标题为
        // "LOCK_SCREEN_UNPROTECTED"、正文为"设备事件"的告警 —— 等于没告诉他发生了什么。
        // 现在改为可读的中文兜底，并把原始枚举名放进正文供排查；同时打 warn 让漏登记可被发现。
        val mapping = EVENT_TITLES[eventType] ?: run {
            log.warn("未登记风险类型的告警文案，已使用通用兜底：eventType={} device={}", eventType, deviceId)
            "设备事件" to "检测到一条未分类的设备事件（$eventType），如反复出现请反馈给客服排查"
        }
        val dev = deviceRepository.findById(deviceId).orElse(null) ?: return null
        val alert = alertRepository.save(
            Alert(
                id = UUID.randomUUID().toString(), deviceId = deviceId, userId = dev.userId,
                level = level, title = mapping.first, message = mapping.second,
                category = eventType, status = "ACTIVE",
                detailJson = detail?.let { objectMapper.writeValueAsString(it) },
                triggeredAt = now
            )
        )
        dev.userId?.let { uid ->
            webSocketPush.pushToUser(uid, mapOf(
                "type" to "alert.new", "alert" to toDto(alert)
            ))
        }
        return alert
    }

    /** 服务端主动建告警（如围栏越界） */
    fun create(
        deviceId: String, level: String, title: String, message: String, category: String
    ): Alert? {
        // 同 (device, category) 冷却：窗口内抑制重复建警（R-P2-14）。
        // 占位必须是**原子**的 `compute`，否则并发下两个请求都读到 prev=null 会各建一条告警。
        val now = System.currentTimeMillis()
        val key = "$deviceId\u0000$category"
        val cooldownOn = cooldownMs > 0
        if (cooldownOn && !claimCooldown(key, now)) {
            log.info(
                "alert cooldown suppressed duplicate: category={} device={} (within {}ms)",
                category, deviceId, cooldownMs
            )
            return null
        }
        var built = false
        try {
            val dev = deviceRepository.findById(deviceId).orElse(null) ?: return null
            val alert = alertRepository.save(
                Alert(
                    id = UUID.randomUUID().toString(), deviceId = deviceId, userId = dev.userId,
                    level = level, title = title, message = message, category = category,
                    status = "ACTIVE", triggeredAt = now
                )
            )
            dev.userId?.let { uid ->
                webSocketPush.pushToUser(uid, mapOf("type" to "alert.new", "alert" to toDto(alert)))
            }
            built = true
            return alert
        } finally {
            // 没真正建出告警就精确回滚本次占位。旧实现先写 key 再查设备：设备不存在时
            // 留下一个幽灵条目，该设备随后产生真实风险时第一条告警会被静默抑制整个冷却窗口；
            // 且 key 含 deviceId，`raiseForged` 会把 topic 里的 id 传进来，只加不删无界增长。
            // 用 remove(key, value) 双参精确删除，不会误删并发刷新出来的新占位。
            if (!built && cooldownOn) lastRaisedAt.remove(key, now)
        }
    }

    /**
     * 原子抢占冷却名额；顺带做**软上界**淘汰。
     *
     * 不设硬上限：条目全都在冷却窗口内时，丢掉谁都会让告警风暴防护凭空失效。
     * 只在数量超过 [MAX_COOLDOWN_KEYS] 时清掉已过期的 key，正常设备数远达不到这个量级。
     */
    private fun claimCooldown(key: String, now: Long): Boolean {
        if (lastRaisedAt.size > MAX_COOLDOWN_KEYS) {
            lastRaisedAt.entries.removeIf { now - it.value >= cooldownMs }
        }
        var won = false
        lastRaisedAt.compute(key) { _, prev ->
            if (prev == null || now - prev >= cooldownMs) {
                won = true
                now
            } else {
                prev
            }
        }
        return won
    }

    fun list(userId: String, deviceId: String?, status: String?, page: Int, size: Int): List<AlertDto> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceAtLeast(1).coerceAtMost(100))
        val alerts = if (deviceId != null) {
            // 过滤下沉到 SQL：原先"先按 user 分页、再在内存里 filter deviceId"，
            // 结果是**分页后过滤**——本页里属于该设备的条目可能只剩两三条，
            // 家长端看到"明明有告警却只显示几条"，且翻页还会漏数据。
            if (status != null) {
                alertRepository.findByUserIdAndDeviceIdAndStatus(userId, deviceId, status, pageable)
            } else {
                alertRepository.findByUserIdAndDeviceId(userId, deviceId, pageable)
            }
        } else {
            if (status != null) alertRepository.findByUserIdAndStatus(userId, status, pageable)
            else alertRepository.findByUserId(userId, pageable)
        }
        // 批量预取设备名，避免逐条 toDto 时对 deviceRepository 的 N+1 查询：
        // 家长端每 15~30s 拉一次告警列表，一页 20 条若分散在多设备，
        // 原先会触发 20 次 findById（每条告警一次）；改为一次 findAllById 拉齐。
        val nameMap = if (alerts.isEmpty()) emptyMap() else
            deviceRepository.findAllById(alerts.map { it.deviceId }.distinct())
                .associate { it.id to (it.name ?: it.id) }
        return alerts.map { toDto(it, nameMap[it.deviceId] ?: it.deviceId) }
    }

    fun unreadCount(userId: String): Long = alertRepository.countByUserIdAndStatus(userId, "ACTIVE")

    fun acknowledge(userId: String, alertId: String) {
        // 仅供"告警不存在 / 不属于当前用户"这两种业务错误的精准报文；真正的状态迁移
        // 交给下面的条件 UPDATE（requireOwned 读到的只是快照，不能据它 save 整行）。
        requireOwned(userId, alertId)
        // 仅 ACTIVE → ACKNOWLEDGED：已被自愈关掉（CLOSED）或后台处置（RESOLVED）的告警
        // 不再被拉回 ACKNOWLEDGED（否则家长端红点死而复生）。0 行 = 已处置，幂等。
        val updated = alertRepository.acknowledgeIfActive(alertId, userId, System.currentTimeMillis())
        if (updated == 0) log.info("alert {} already handled before acknowledge, skip (idempotent)", alertId)
    }

    fun close(userId: String, alertId: String, reason: String?) {
        requireOwned(userId, alertId)
        // 仅 ACTIVE/ACKNOWLEDGED → CLOSED：终态（RESOLVED）不可回退。0 行幂等。
        val updated = alertRepository.closeIfOpen(alertId, userId, System.currentTimeMillis())
        if (updated > 0 && reason != null) {
            // 处理说明属展示字段，用第二次条件更新追加（仍在 CLOSED 态才生效）；
            // 不能并入上面的 UPDATE 后整行 save——那会重新引入快照覆盖。
            alertRepository.appendCloseNoteIfStillClosed(alertId, " [处理: $reason]")
        }
        if (updated == 0) log.info("alert {} already terminal before close, skip (idempotent)", alertId)
    }

    /**
     * 孩子端自愈成功后关闭某类别下全部 ACTIVE 告警（恢复即关闭闭环）。
     *
     * ## 为何需要
     * 此前告警只能由家长手动关闭。DO 形态设备静默重授予权限（无障碍 / 运行时危险权限）后，
     * 服务端 ACTIVE 告警仍长期挂起，家长端持续提示"权限被撤销 / 无障碍已关闭"，
     * 形成"已修复却仍报警"的体验。孩子端在自愈成功时调用本方法，使告警随恢复自动消失。
     *
     * ## 设计取舍
     * - 批量关闭用 `closeActiveByCategory` 带状态条件的单条 UPDATE（替代旧的
     *   "查快照→内存置 CLOSED→saveAll"），与家长 acknowledge / 后台 closeAlert 并发安全，
     *   代价是 `AlertRepository` 新增抽象方法需同步各测试假仓储（已同步）；
     * - 仅关闭 ACTIVE：已 ACK/CLOSED（家长已处置）的告警保留，不越权改动；
     * - deviceId 由鉴权拦截器注入、不可被报文伪造，按它隔离即可精准定位归属设备；
     * - 同时复位该 (device, category) 的建警冷却：否则自愈越及时，问题复发时越容易被残留的
     *   冷却窗口吞掉（建警后 10 分钟内不再告警），家长恰在最需要知道时收不到消息；
     * - 状态迁移用 `closeActiveByCategory` 单条带状态条件的批量 UPDATE（只动 ACTIVE 行），
     *   与家长 acknowledge / 后台 closeAlert 并发时不再互相整行覆盖（R42b 审计确认的竞态）；
     * - 关闭即向归属家长推 WS `alert.resolved`：家长端红点 / 未读随自愈即时回落，形成完整闭环。
     */
    fun resolveActiveByCategory(deviceId: String, category: String, reason: String? = "已自动恢复") {
        require(category.isNotBlank()) { "category 不可为空" }
        // 自愈关闭即复位建警冷却：否则告警刚被关掉、问题立刻复发时，第二次 create 会被残留的
        // 冷却窗口吞掉（自愈越快这个洞越明显）。即便没有 ACTIVE 告警被关也要复位——
        // 冷却 key 可能仍在，复发同样会被吞。key 口径必须与 create 完全一致（同一 \u0000 分隔）。
        lastRaisedAt.remove("$deviceId\u0000$category")
        val count = alertRepository.closeActiveByCategory(deviceId, category, System.currentTimeMillis())
        if (count == 0) return
        deviceRepository.findById(deviceId).orElse(null)?.userId?.let { uid ->
            webSocketPush.pushToUser(uid, mapOf(
                "type" to "alert.resolved",
                "deviceId" to deviceId,
                "category" to category,
                "count" to count
            ))
        }
        log.info("resolved {} ACTIVE alert(s) category={} device={} reason={}", count, category, deviceId, reason)
    }

    private fun requireOwned(userId: String, alertId: String): Alert {
        val alert = alertRepository.findById(alertId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "告警不存在", Audience.PARENT)
        if (alert.userId != userId) {
            throw BizException(ParentErr.FORBIDDEN, "告警不属于当前用户", Audience.PARENT)
        }
        return alert
    }

    private fun toDto(a: Alert, deviceName: String) = AlertDto(
        id = a.id, deviceId = a.deviceId,
        deviceName = deviceName,
        level = a.level, title = a.title, message = a.message, status = a.status,
        category = a.category, triggeredAt = a.triggeredAt,
        acknowledgedAt = a.acknowledgedAt, resolvedAt = a.resolvedAt
    )

    /** 单条告警兜底（建警/自愈即时推送等一次一条的场景）：现查一次设备名，无 N+1 风险。 */
    private fun toDto(a: Alert): AlertDto {
        val name = deviceRepository.findById(a.deviceId).orElse(null)?.name ?: a.deviceId
        return toDto(a, name)
    }

    companion object {
        /** 冷却表软上界：超过后只淘汰已过窗口的 key，绝不丢弃仍在生效的冷却名额。 */
        private const val MAX_COOLDOWN_KEYS = 4096

        /**
         * 孩子端 `RiskType` → 家长端可见的中文文案（标题 / 正文）。
         *
         * `internal` 而非 `private`：`AlertTitleCoverageTest` 需要遍历本表核对
         * "孩子端能上报的每一种风险类型都必须有中文文案"，这是防止两端口径漂移的唯一手段
         * （两端口径不在同一 Gradle 工程，无法用类型系统约束）。
         */
        internal val EVENT_TITLES = mapOf(
            "UNINSTALL_ATTEMPT" to ("尝试卸载管控应用" to "孩子尝试卸载护航成长应用"),
            "FORCE_STOP_ATTEMPT" to ("尝试强制停止" to "孩子尝试强制停止管控应用"),
            "CLEAR_DATA_ATTEMPT" to ("尝试清除数据" to "孩子尝试清除管控应用数据"),
            "CLOCK_TAMPERING" to ("时间被篡改" to "检测到系统时间被手动修改"),
            "ROOT_DETECTED" to ("检测到 Root" to "设备已 Root，存在安全风险"),
            "DEVELOPER_OPTIONS_ENABLED" to ("开发者选项开启" to "开发者选项已开启"),
            // 调试版误部署到已绑定设备（§4.3 项 3 的一半）：release 才合规，debug 携带明文日志且可调试。
            "DEBUG_BUILD_ACTIVE" to ("调试版运行于受管设备" to "该设备运行的是调试版（debug）应用，存在日志明文、可被调试等风险，请用 release 版本重新部署"),
            // 代理/VPN 暴露（P0-3）：主动中间人解密观测的强信号
            "PROXY_DETECTED" to ("设备经代理/VPN 联网" to "设备正通过代理或 VPN 联网，可能在被中间人解密观测流量；本端已启用证书固定阻断解密，但请检查网络环境是否受控"),
            "USB_DEBUG_ENABLED" to ("USB 调试开启" to "USB 调试已开启"),
            "BLACKLIST_APP_LAUNCH" to ("打开黑名单应用" to "孩子打开了被禁止的应用"),
            "BLOCKED_URL_ACCESS" to ("访问被拦截网址" to "尝试访问被屏蔽的网址"),
            "TIME_LIMIT_EXCEEDED" to ("使用时长超限" to "今日使用时长已超过限制"),
            "PERMISSION_REVOKED" to ("权限被撤销" to "管控所需权限被撤销"),
            "POLICY_APPLY_FAILED" to ("策略应用失败" to "部分管控策略未能生效"),
            // 能力性降级 ≠ 执行失败：策略本身已经下发，只是这台设备做不到（如无设备所有者）
            "POLICY_DEGRADED" to ("部分管控项本机未生效" to "设备缺少相应系统能力（如设备所有者），或该能力尚未在终端版本中实现；策略本身已正常下发"),
            "ACCESSIBILITY_DISABLED" to ("无障碍服务被关闭" to "操作闸门的系统级拦截已关闭，孩子可在设置中绕过密码闸门，请引导孩子重新开启"),
            "USAGE_ACCESS_LOST" to ("使用情况权限被关闭" to "使用情况访问权限被关闭，应用管控与时长统计将失效"),
            "GUARD_PASSWORD_NOT_SET" to ("操作密码尚未设置" to "管控设备未设置操作密码，设置页无法验证家长身份，请尽快补设"),
            // 应用装卸行为记录（R-P3-10）：与 UNINSTALL_ATTEMPT 完全不同 ——
            // 后者是"有人想卸载本管控应用"（篡改信号），前者只是"某普通应用多/少了"。
            "APP_INSTALLED" to ("设备新装了应用" to "检测到设备上有新应用安装，可在「应用列表」中查看并决定是否管控"),
            "APP_UNINSTALLED" to ("设备卸载了应用" to "检测到设备上有应用被卸载"),

            // 以下四条此前**在孩子端真实上报，但这里没有登记**：家长看到的标题是英文枚举名
            // （例如 "INVALID_SIGNATURE"）、正文是"设备事件"，等于把最需要看懂的四类告警
            // 说成了同一句废话。逐条补齐，并由 `AlertTitleCoverageTest` 锁定覆盖面。
            //
            // 无障碍 / 悬浮窗被系统静默撤销：操作闸门（设置页拦截、卸载拦截、锁屏抢占）依赖它们，
            // 失活即"闸门整体失效"，与单条 PERMISSION_REVOKED 不是一回事，必须单独说清。
            "GUARD_PERMISSION_DROPPED" to ("管控权限被撤销" to "操作闸门所需的权限（无障碍 / 悬浮窗）已被系统撤销，设置与卸载拦截可能失效，请引导孩子重新授权"),
            // 守护进程被系统清理后自动拉回（2026-11-17 EMUI 一键清理事故闭环）：
            // ROM 清理白名单（启动管理 + 多任务卡片加锁）无查询接口，这是白名单是否生效的
            // 唯一行为证据——配好则永不出现，反复出现 = 防清理设置未生效，引导家长补做。
            "GUARD_PROCESS_RECYCLED" to ("守护进程被系统清理后已自动恢复" to "平板的内存清理（一键清理/清理加速）或系统回收中断过守护进程，现已自动恢复。若反复出现，说明防清理设置未生效：请在平板「启动管理」中允许本应用自启与后台运行，并在最近任务中对本应用卡片下拉加锁"),
            // 前台服务被系统拒绝 = 进程失去前台豁免，随时可被回收；"设备在线"不再等于"守护健康"。
            "GUARD_FOREGROUND_DOWN" to ("守护服务已降级为后台运行" to "系统拒绝了守护服务的前台运行申请，设备虽仍显示在线，但进程随时可能被回收、锁屏与看屏指令可能无响应。请在平板「设置-应用-本应用-电池」中关闭省电限制，并在「启动管理」中允许自启与后台运行；设备设为设备所有者可获得系统豁免"),
            // 指令签名校验失败 = 有人试图伪造管控指令下发，属安全事件，不是网络抖动。
            "INVALID_SIGNATURE" to ("指令签名校验失败" to "收到一条签名不合法（可能被伪造）的管控指令，已拒绝执行；请确认账号未泄露"),
            "HEARTBEAT_TIMEOUT" to ("与服务端通信超时" to "设备与服务器的心跳已超时，管控指令可能无法及时送达"),
            // 锁屏是家长最常用的强干预：呈现层重试耗尽仍未覆盖到前台时才上报，
            // 家长端回执却是"已执行" —— 这条告警是唯一能揭穿"以为锁了其实没锁"的信号。
            "LOCK_SCREEN_UNPROTECTED" to ("锁屏未能生效" to "家长下发锁屏后，锁屏界面没能覆盖到前台，孩子可能仍在使用平板。请检查悬浮窗（后台弹出界面）权限，或激活设备管理员"),
            // 管控完整性丢失（P0-2）：清除应用数据致凭据清空，本地管控整体失效
            "CONTROL_LOST" to ("管控完整性丢失" to "设备曾成功绑定但当前已无绑定凭据（疑似被清除应用数据），本地时长与应用管控已失效，且无法再校验服务端指令。请重新绑定设备"),
            // 默认桌面被第三方替换（P1-2）
            "HOME_CHANGED" to ("默认桌面被替换" to "设备默认桌面已被改为第三方应用，按 HOME 键会跳出护航成长管控。请在系统设置中将默认桌面改回护航成长，或重新激活设备所有者"),
            // 网页过滤隧道静默失效（A1-P0 看门狗）：非 DO 设备下 Web 过滤是 DNS-only 的 VpnService，
            // 隧道一停即改用系统 DNS、域名不再过滤（架构性 fail-open）。看门狗检测到后立即上报，
            // 让家长端第一时间看到"网页防护掉了"，而不是让孩子端静默处于无过滤状态。
            "WEB_GUARD_DOWN" to ("网页过滤已停止" to "网页过滤隧道已停止运行，当前域名拦截不生效（设备非设备所有者，重启受限）。请检查设备状态或重新激活设备管理员以恢复网页防护")
        )
    }
}
