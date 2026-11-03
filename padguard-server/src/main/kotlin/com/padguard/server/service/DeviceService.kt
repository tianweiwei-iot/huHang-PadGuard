package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ChildErr
import com.padguard.server.common.HashUtil
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Device
import com.padguard.server.dto.BindRequest
import com.padguard.server.dto.BindResult
import com.padguard.server.dto.DeviceDto
import com.padguard.server.dto.LanBindRequest
import com.padguard.server.dto.LanDeviceDto
import com.padguard.server.dto.DeviceProfileRequest
import com.padguard.server.dto.HeartbeatDto
import com.padguard.server.mqtt.MqttGateway
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.PolicyRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.Passwords
import com.padguard.server.service.AuditAction
import com.padguard.server.service.AuditService
import com.padguard.server.ws.WebSocketPushService
import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DeviceService(
    private val deviceRepository: DeviceRepository,
    private val ownershipGuard: com.padguard.server.security.DeviceOwnershipGuard,
    private val enrollmentService: EnrollmentService,
    private val policyService: PolicyService,
    private val webSocketPush: WebSocketPushService,
    private val mqttGateway: MqttGateway,
    private val userRepository: UserRepository,
    private val policyRepository: PolicyRepository,
    private val auditService: AuditService,
    @Value("\${padguard.device.token-ttl-days:30}") private val tokenTtlDays: Long,
    /**
     * 下发给所有孩子端的 MQTT 凭据（家庭部署包由启动脚本注入）。
     *
     * 留空时退回"每设备随机密码"（开发/外部 broker 场景的行为，与改造前一致）。
     * 之所以要共享而不是每设备一套：Windows 版 Mosquitto 无法热加载 password_file，
     * 绑定时临时写入的密码要等 broker 重启才生效，孩子端会一直认证失败，
     * 而服务端以为自己已经发下去了凭据 —— 这种"看起来成功、实际连不上"最难排查。
     */
    @Value("\${padguard.mqtt.device-username:}") private val mqttDeviceUsername: String,
    @Value("\${padguard.mqtt.device-password:}") private val mqttDevicePassword: String,
    private val billingService: BillingService,
    private val deviceDataPurgeService: DeviceDataPurgeService,
    /** 秒级在线态的**内存**快照（见 [DevicePresenceRegistry]）：本类只负责"信号进来就登记" */
    private val presence: DevicePresenceRegistry,
    /**
     * 实时看屏的帧缓存。解绑/易主时要把该设备的缓存帧与带宽统计一并清掉 ——
     * 否则 ScreenStreamHub 里的条目会跟着 deviceId 永久留在内存里（帧缓存是整张 JPEG）。
     */
    private val streamHub: com.padguard.server.stream.ScreenStreamHub
) {

    private val log = LoggerFactory.getLogger(DeviceService::class.java)

    /**
     * 孩子端绑定：消费绑定码 -> **按硬件指纹复用或新建设备** -> 下发令牌/MQTT 凭据/HMAC 密钥 -> 建默认策略
     *
     * ## 为什么必须复用而不是每次新建
     * 早期实现每次绑定都 `UUID.randomUUID()` 造一个新 deviceId。孩子端一旦重装（升级、
     * 排障重装、换机恢复），就会多出一条设备记录：家长端列表里出现两个同名"孩子平板"，
     * 而家长端选中的往往还是那条**已经死掉的旧记录** ——
     * 表现就是"实时画面一直转圈、锁屏/录屏全部 PENDING"，因为指令全发给了离线设备。
     *
     * 以 `deviceSn`（= [android.provider.Settings.Secure.ANDROID_ID]）为硬件指纹：
     * 同一次绑定用户下命中已有设备时，沿用原 deviceId 只轮换凭据，
     * 家长端的历史绑定、策略、截图记录全部保留，重装后立刻可用。
     */
    /**
     * 必须整体事务：消费绑定码 → 建/复用设备台账 → 回写绑定结果，三步要么全成要么全败。
     *
     * 没有任何一步可以容忍"半途成功"：
     * - 码被消费但台账没建成 → 孩子端拿到凭据却无法上报，家长端停在"等待扫码"直到超时；
     * - 台账建成但没回写 deviceId → 家长端轮询状态永远是 PENDING，同样判定超时，
     *   而孩子端其实已经绑上并开始心跳（这正是"绑定未成功"的成因之一）。
     * 回滚后接入会话仍是 PENDING，家长端刷新即可继续，不会留下"废码"。
     */
    @Transactional
    fun bindChild(req: BindRequest): BindResult {
        // req.bindCode 就是接入会话的短码：抢占会话 -> 确定归属家长 -> 消费结果回写到会话
        val claim = enrollmentService.consume(req.bindCode)
        val result = bindChildWithUser(req, claim.userId)
        // 绑定结果回写到**会话**上（而不是凭证上）：家长端靠这个会话的状态判定成功。
        // 重绑已存在设备时 deviceId 不变，只看设备列表是判不出来"这次绑上了"的。
        enrollmentService.markBound(claim.sessionId, result.deviceId)
        return result
    }

    /**
     * 绑定主流程：确认归属家长后创建/复用设备并下发凭据（配对码与账号密码两种方式共用）。
     *
     * 付费闸门放在**这里**而不是两个入口各放一次：
     * 校验分散到调用方，日后新增第三种绑定方式（比如扫码直连）时极易漏掉，
     * 漏掉就是一种免费后门。共用入口只有一处，不可能漏。
     *
     * ## 为什么要按设备指纹加锁
     * 下面 [matchExistingDevice] 是"查台账，没有才新建"的 check-then-act。
     * 孩子端绑定超时重试、家长同时用扫码与账号密码两种方式接入，都会让两个事务同时进来：
     * 都查不到台账 → 各建一条 → 家长端出现两台同名设备，用量/策略/指令从此分裂。
     * 键锁把"同一台平板"的绑定串行化，锁活到事务提交（详见 [DeviceBindLocks]）。
     */
    fun bindChildWithUser(req: BindRequest, userId: String): BindResult =
        DeviceBindLocks.withKey(DeviceBindLocks.bindKey(req.deviceSn, req.fingerprint)) {
            doBindChildWithUser(req, userId)
        }

    private fun doBindChildWithUser(req: BindRequest, userId: String): BindResult {
        billingService.ensureCanBindForChild(userId)
        val deviceId = UUID.randomUUID().toString()
        val deviceToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        val hmacSecret = "hs_" + UUID.randomUUID().toString().replace("-", "")
        // 家庭部署包注入了共享凭据 → 用共享的（broker 用同一份 password_file 校验）；
        // 没注入 → 退回每设备随机，保持开发/外部 broker 场景与改造前一致。
        val mqttPassword =
            if (mqttDevicePassword.isNotBlank()) mqttDevicePassword
            else "mk_" + UUID.randomUUID().toString().replace("-", "")
        val now = System.currentTimeMillis()

        val existing = matchExistingDevice(userId, req)
        if (existing != null) {
            // 跨账号复用（孤儿回收 / 设备易主）：复用的台账上一任归属人不是当前家长，
            // 其累积的 usage_logs 对新区划家长应不可见——否则"新账号一绑定就看到上一任的历史使用记录"
            // （家长端反馈"卸载重装、用新账号登录，哪里来的历史"的根因正是此处）。
            // 在重新归属前清掉该设备的历史用量，新账号从空白开始。
            if (existing.userId != userId) {
                // 清**全部**私有数据，而不只是用量日志：截图/轨迹/告警/指令/已装应用/围栏/设备设置
                // 都是上任孩子与上任家长的痕迹，留下来就是隐私泄漏（新家长一绑定就能看到
                // 别人家孩子的截图和行踪）。统一入口保证新增私有表时不会漏清。
                purgeDeviceDataWithRetry(existing.id, "TRANSFER")
            }
            existing.apply {
                // 必须重新归属给当前家长。
                // 复用台账（尤其"孤儿回收"来的历史设备）时若只更新设备信息而不改 userId：
                // 孤儿设备的 userId 仍是 null，重绑后这台平板在家长端"设备不属于当前用户"、
                // 设备列表里根本看不到，表现为"绑上了但家长端没有这台设备"。
                this.userId = userId
                name = name ?: "孩子平板"
                // 只允许"非空"入参覆盖台账字段：空串会把已保存的硬件指纹（deviceSn / fingerprint）
                // 抹掉，导致这台平板下次重装重新绑定时匹配不到自己，被当成新设备新建台账，
                // 家长端仍指向已失效的旧 deviceId —— 表现为"重装后数据不再同步"。
                req.deviceSn?.takeIf { it.isNotBlank() }?.let { deviceSn = it }
                req.fingerprint?.takeIf { it.isNotBlank() }?.let { fingerprint = it }
                req.model?.takeIf { it.isNotBlank() }?.let { model = it }
                req.brand?.takeIf { it.isNotBlank() }?.let { brand = it }
                req.androidVersion?.takeIf { it.isNotBlank() }?.let { osVersion = it }
                req.sdkInt?.let { sdkInt = it }
                req.appVersion?.takeIf { it.isNotBlank() }?.let { appVersion = it }
                req.controlMode?.let { controlMode = it }
                this.hmacSecret = hmacSecret
                deviceTokenHash = HashUtil.sha256(deviceToken)
                this.mqttPassword = mqttPassword
            }
            deviceRepository.save(existing)
            auditService.record(userId, existing.id, AuditAction.DEVICE_BIND, targetType = "device", targetId = existing.id)
            return BindResult(
                deviceId = existing.id, deviceToken = deviceToken,
                mqttUsername = if (mqttDeviceUsername.isNotBlank()) mqttDeviceUsername else existing.id,
                mqttPassword = mqttPassword,
                hmacSecret = hmacSecret, expiresAt = now + tokenTtlDays * 86_400_000
            )
        }

        val device = Device(
            id = deviceId, userId = userId, name = "孩子平板",
            deviceSn = req.deviceSn, fingerprint = req.fingerprint,
            model = req.model, brand = req.brand, osVersion = req.androidVersion,
            sdkInt = req.sdkInt, appVersion = req.appVersion,
            controlMode = req.controlMode, scene = "FAMILY",
            onlineStatus = "OFFLINE", hmacSecret = hmacSecret,
            deviceTokenHash = HashUtil.sha256(deviceToken),
            mqttPassword = mqttPassword, createdAt = now
        )
        deviceRepository.save(device)
        policyService.createDefault(deviceId)
        auditService.record(userId, deviceId, AuditAction.DEVICE_BIND, targetType = "device", targetId = deviceId)

        return BindResult(
            deviceId = deviceId, deviceToken = deviceToken,
            mqttUsername = if (mqttDeviceUsername.isNotBlank()) mqttDeviceUsername else deviceId,
            mqttPassword = mqttPassword,
            hmacSecret = hmacSecret, expiresAt = now + tokenTtlDays * 86_400_000
        )
    }

    /**
     * 「账号密码绑定」：孩子端直接拿家长的手机号 + 密码绑定，无需配对码 / 扫码。
     *
     * 与 [bindChild] 的唯一区别是归属家长的确认方式（账号密码 vs 一次性绑定码），
     * 设备台账、令牌下发、默认策略等后续逻辑完全复用，避免两套实现漂移。
     *
     * 必须整体事务（与 [bindChild] 同口径）：建设备台账 + createDefault 策略 + audit
     * 要么全成要么全败。注意 @Transactional 必须加在这个**外部入口**上 ——
     * 若只加在被内部自调用的 bindChildWithUser 上，Spring 同类自调用不走代理，
     * 事务注解会静默失效（bindChild 路径不受影响：其自身注解已覆盖整个流程）。
     */
    @Transactional
    fun bindChildByAccount(req: com.padguard.server.dto.BindByAccountRequest): BindResult {
        val user = userRepository.findByPhone(req.phone.trim())
            ?: throw BizException(ChildErr.TOKEN_INVALID, "家长账号不存在", Audience.CHILD)
        if (!Passwords.matches(req.password, user.passwordHash)) {
            throw BizException(ChildErr.TOKEN_INVALID, "家长账号或密码错误", Audience.CHILD)
        }
        // 复用既有绑定实现：把「已确认的 userId」当作绑定码消费的结果
        return bindChildWithUser(req.req, user.id)
    }

    /**
     * 在同一家长名下按硬件指纹找"同一台平板"的历史记录。
     *
     * 优先 `deviceSn`（ANDROID_ID，重装不变）；退而求其次用 `fingerprint`（ROM 指纹，
     * 同型号同版本的多台平板会撞车，故只在 deviceSn 缺失时兜底）。
     */
    private fun matchExistingDevice(userId: String, req: BindRequest): Device? {
        val mine = deviceRepository.findByUserId(userId)
        val sn = req.deviceSn?.takeIf { it.isNotBlank() }
        if (sn != null) {
            mine.firstOrNull { it.deviceSn == sn }?.let { return it }
            // 孤儿回收：同一台平板（同 deviceSn）的历史台账，若处于"已解绑"状态、
            // 或原归属家长账号已不存在（清库/删号），则直接复用这条记录。
            // 不做这层回收的话，每次重绑都会新建一条设备，家长端会出现重复设备，
            // 而旧记录又永远卡在别人名下 —— 表现为"解绑后这台平板再也绑不回来"。
            deviceRepository.findByDeviceSn(sn).firstOrNull { dev ->
                val owner = dev.userId
                owner.isNullOrBlank() || !userRepository.existsById(owner)
            }?.let { return it }
        }
        val fp = req.fingerprint?.takeIf { it.isNotBlank() }
        if (fp != null) {
            val byFp = mine.filter { it.fingerprint == fp }
            // 只有唯一命中才敢复用：多条同指纹说明家长有多台同型号平板，认错了就串台
            if (byFp.size == 1) return byFp[0]
        }
        return null
    }

    /**
     * 批量导入设备台账（Excel / CSV）。
     *
     * ## 与绑定流程的关系
     * 走的是同一套"按 `deviceSn` 复用台账"口径（见 [matchExistingDevice]），只是把来源从绑定码
     * 换成表格行。刻意复用而不是另写一套：另写一套就等于两条设备台账来源，
     * 迟早在"导入的设备为什么不能用"上分叉。
     *
     * ## 安全边界
     * 只认当前家长名下 + 孤儿设备（userId 为空或归属账号已不存在）。别人的已绑定设备即使
     * 硬件编号撞车也不会被抢走，计为 skipped。
     *
     * ## 失败隔离
     * 逐行独立处理并收集失败原因：几十台设备的表不会因为一行格式错误整体失败。
     */
    @Transactional
    fun importDevices(userId: String, req: com.padguard.server.dto.DeviceImportRequest): com.padguard.server.dto.DeviceImportResultDto {
        val rows = req.rows
        var success = 0
        var skipped = 0
        val failures = mutableListOf<String>()

        rows.forEachIndexed { i, row ->
            val lineNo = i + 1
            val hardwareId = row.hardwareId.trim()
            if (hardwareId.isBlank()) {
                failures += "第${lineNo}行：设备编号为空"
                return@forEachIndexed
            }
            runCatching {
                val existing = findImportableDevice(userId, hardwareId)
                if (existing != null) {
                    // 已在我的名下，或已被别人占用：都算跳过，不做任何改写（避免抢设备 / 覆盖别名）
                    skipped++
                    log.info("批量导入跳过第{}行：设备编号 {} 已存在（deviceId={}）", lineNo, hardwareId, existing.id)
                } else {
                    val now = System.currentTimeMillis()
                    val deviceId = UUID.randomUUID().toString()
                    deviceRepository.save(
                        Device(
                            id = deviceId, userId = userId,
                            name = row.alias?.takeIf { it.isNotBlank() } ?: "孩子平板",
                            deviceSn = hardwareId, model = row.model?.takeIf { it.isNotBlank() },
                            scene = "FAMILY", onlineStatus = "OFFLINE", createdAt = now
                        )
                    )
                    policyService.createDefault(deviceId)
                    auditService.record(
                        userId, deviceId, AuditAction.DEVICE_BIND,
                        targetType = "device", targetId = deviceId
                    )
                    success++
                }
            }.onFailure {
                // 单行失败不能中断整批；记录原因让家长知道改哪一行
                log.warn("批量导入第{}行失败：{}", lineNo, it.message)
                failures += "第${lineNo}行：${it.message ?: "导入失败"}"
            }
        }

        log.info("批量导入完成：userId={} 总行数={} 成功={} 跳过={} 失败={}", userId, rows.size, success, skipped, failures.size)
        return com.padguard.server.dto.DeviceImportResultDto(
            totalCount = rows.size, successCount = success, skippedCount = skipped, failedRows = failures
        )
    }

    /** 导入场景下的台账查找：优先自己名下的，其次孤儿设备。 */
    private fun findImportableDevice(userId: String, hardwareId: String): Device? {
        deviceRepository.findByUserId(userId).firstOrNull { it.deviceSn == hardwareId }?.let { return it }
        return deviceRepository.findByDeviceSn(hardwareId).firstOrNull { dev ->
            val owner = dev.userId
            owner.isNullOrBlank() || !userRepository.existsById(owner)
        }
    }

    fun listDevices(userId: String): List<DeviceDto> =
        deviceRepository.findByUserId(userId).map { it.toDto() }

    fun getDevice(userId: String, deviceId: String): DeviceDto {
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (dev.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        }
        return dev.toDto()
    }

    /** 处理孩子端心跳（MQTT 或 HTTP 降级共用），上线状态变更时推 WS */
    fun applyHeartbeat(deviceId: String, hb: HeartbeatDto) {
        // 心跳本身就是最权威的存活信号：先登记 presence，再落库。
        // 顺序不能反 —— 下面的 save 可能慢（行锁 / 日志上报抢写），
        // 若等它返回才登记，弱网时 presence 会比真实上线晚几百毫秒，白白多等一轮扫描。
        presence.touch(deviceId)
        deviceRepository.findById(deviceId).ifPresent { dev ->
            val wasOffline = dev.onlineStatus != "ONLINE"
            dev.onlineStatus = "ONLINE"
            dev.lastOnlineAt = System.currentTimeMillis()
            (hb.battery?.get("level") as? Number)?.toInt()?.let { dev.batteryLevel = it }
            hb.location?.let { loc ->
                (loc["lat"] as? Number)?.toDouble()?.let { dev.latitude = it }
                (loc["lng"] as? Number)?.toDouble()?.let { dev.longitude = it }
            }
            hb.controlMode?.let { dev.controlMode = it }
            // P3 守护健康度快照：持久化孩子端上报的守护状态，供 T3 失联判定与家长端展示。
            hb.daemonAlive?.let { alive ->
                dev.daemonAlive = alive
                if (alive) dev.daemonAliveAt = System.currentTimeMillis()
                // 守护恢复后清零"已告警"标记，允许下次失联再次告警。
                if (alive && dev.daemonLostAlerted == true) dev.daemonLostAlerted = false
            }
            hb.a11yAlive?.let { dev.a11yAlive = it }
            hb.onlineState?.let { dev.daemonOnlineState = it }
            hb.lastRestartReason?.let { dev.lastRestartReason = it }
            hb.permissionVersion?.let { dev.permissionVersion = it }
            deviceRepository.save(dev)
            if (wasOffline) {
                webSocketPush.pushToUser(dev.userId, mapOf(
                    "type" to "device.online", "deviceId" to deviceId,
                    "timestamp" to System.currentTimeMillis()
                ))
            }
        }
    }

    /**
     * T3 守护失联：标记已推送告警，避免 [OfflineMonitorService] 每轮重复推送。
     * 守护恢复后由 [applyHeartbeat] 在 daemonAlive 回到 true 时清零。
     */
    fun markDaemonLost(deviceId: String) {
        deviceRepository.findById(deviceId).ifPresent { dev ->
            dev.daemonLostAlerted = true
            deviceRepository.save(dev)
        }
    }

    /**
     * 设备当前策略版本，供心跳回执做版本对账。
     *
     * 心跳是孩子端唯一"必定周期性到达服务端"的通道，把策略版本搭在回执里，
     * 终端就能在漏掉策略推送时立刻自我纠偏，不必等下一次自检轮询。
     *
     * ## 为什么这里查 policies 表而不是读 `devices.policy_version`
     * `devices.policy_version` 这一列**从来没有写入点** —— 策略保存分散在
     * [PolicyService]（创建默认 / 改模式 / 未成年人模式）与 [PolicyExtensionService]
     * （应用模板 / 子表重建）共五处，没有任何一处同步更新过这一列，它恒为 0。
     * 而孩子端对账逻辑是 `ack.policyVersion > 0 && != local`，
     * 0 会被直接跳过 —— 后果是：**MQTT 关闭时家长改的任何策略都不会下发到设备**，
     * 表现为家长端显示"已开启未成年人模式"、平板上纹丝不动，且永不自愈。
     *
     * 修在读取侧而不是给五个写入点各补一行，是因为补写入点必然再次漏掉未来的新路径；
     * 直接以 policies 表的最新版本号为唯一事实源，任何写入路径都自动生效。
     */
    fun policyVersionOf(deviceId: String): Int =
        policyRepository.findFirstByDeviceIdOrderByVersionDesc(deviceId)?.version ?: 0

    /**
     * 置为离线并推 `device.offline`。
     *
     * 调用方有三处：[PresenceMonitorService]（信号超时）、[OfflineMonitorService]（兜底）、解绑/易主。
     * 无论谁调，都顺手把 presence 表里的这条摘掉 —— 否则内存里它还是"在线"，
     * [DevicePresenceRegistry.touch] 不会认为这是一次"离线→在线"的恢复，
     * 家长端就看不到它重新上线的那条推送（表现为"设备回来了但一直显示离线"）。
     */
    fun setOffline(deviceId: String) {
        presence.markOffline(deviceId)
        deviceRepository.findById(deviceId).ifPresent { dev ->
            if (dev.onlineStatus == "ONLINE") {
                dev.onlineStatus = "OFFLINE"
                deviceRepository.save(dev)
                webSocketPush.pushToUser(dev.userId, mapOf(
                    "type" to "device.offline", "deviceId" to deviceId,
                    "lastSeen" to System.currentTimeMillis()
                ))
            }
        }
    }

    /**
     * 处理"MQTT 连接断开"这一路的离线提示（broker 发布的遗嘱 `{"online":false}`）。
     *
     * ## 为什么要先看 presence，而不是直接置离线
     * 遗嘱只说明**MQTT 这条连接**断了，不说明设备没了：切 Wi-Fi、broker 重启、
     * 校园网掐掉 1883 端口都可能触发，而孩子端的 HTTPS 拉指令可能一切正常。
     * 改造前家长端靠 10 秒轮询，这种 1~2 秒的抖动被自然吞掉；
     * 现在 WS 直推、状态秒级可见，若照单全收就会变成"离线/在线"反复横跳。
     *
     * 判据：最近 [BROKER_WILL_GRACE_MS] 内还有其它存活信号（HTTP 每 3 秒一次）→
     * 说明设备活着，忽略这条提示，交给 [PresenceMonitorService] 用统一口径判定；
     * 否则（设备真的没了）立刻置离线，省下这几秒。
     */
    fun handleBrokerOffline(deviceId: String) {
        val now = System.currentTimeMillis()
        val last = presence.lastSeenAt(deviceId)
        if (last != null && now - last < BROKER_WILL_GRACE_MS) {
            log.info("broker offline hint for {} ignored: presence is fresh ({} ms ago)", deviceId, now - last)
            return
        }
        setOffline(deviceId)
    }

    fun getUserId(deviceId: String): String? =
        deviceRepository.findById(deviceId).orElse(null)?.userId

    /** 家长解绑设备：清空归属与令牌，旧 deviceToken 立即失效 */
    fun unbind(userId: String, deviceId: String) {
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (dev.userId != userId) {
            // 孤儿设备兜底：原归属家长账号已不存在（清库/删号）时，允许当前家长接管并解绑。
            // 否则这类设备会永远卡在"别人的设备"状态，家长端点解绑只得到
            // 「设备不属于当前用户」，且再也无法重新绑定 —— 这是"以前绑的设备解绑报错"的成因之一。
            val ownerExists = dev.userId?.let { userRepository.existsById(it) } ?: false
            if (ownerExists) {
                throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
            }
        }
        dev.userId = null
        dev.onlineStatus = "OFFLINE"
        dev.groupId = null
        dev.deviceTokenHash = "revoked_" + UUID.randomUUID().toString()
        // 清空操作/守护密码哈希：解绑后设备不再归属任何家长，按空串语义视为"未设密码"。
        // 否则旧哈希残留会使重绑后服务端仍上报 guardPasswordSet=true，家长端据此跳过管理密码引导
        // （Bug ⑦ 在重绑场景复现）；且隐藏形态前置校验会把应用留在可见态，家长无法补设密码。
        dev.guardPasswordHash = ""
        deviceRepository.save(dev)
        // 解绑即清理该设备全部私有业务数据：截图、轨迹、用量、指令、告警、已装应用、围栏、
        // 设备设置等。早期只把 userId 置空，这些行永久残留在库里（且下次有人绑到同一台平板
        // 时会被新孩子继承）。审计日志刻意保留，供管理员追责。
        purgeDeviceDataWithRetry(deviceId, "UNBIND")
        auditService.record(userId, deviceId, AuditAction.DEVICE_UNBIND, targetType = "device", targetId = deviceId)
        // 关键：必须通知被控端。否则平板本地仍持有旧令牌、继续上报心跳并处于受控态，
        // 家长端看着"解绑了"，但孩子端其实还挂在管控里 —— 这就是"解绑无效"的体感来源。
        // 复用下行 config 通道下发 unbind=1，孩子端收到后清除本地凭据并回到绑定页。
        pushUnbind(deviceId)
    }

    /** 下发解绑通知给孩子端（尽力而为：平板离线时会在其下次上线后由重连逻辑兜底）。 */
    private fun pushUnbind(deviceId: String) {
        runCatching { mqttGateway.publishConfig(deviceId, mapOf("unbind" to "1")) }
            .onFailure { log.warn("push unbind config failed: $it") }
    }

    /**
     * 易主 / 解绑时清理设备私有数据，带有限重试。
     *
     * [com.padguard.server.service.DeviceDataPurgeService.purge] 现已改为原子事务：
     * 任意一张表删除失败即整体回滚并抛异常。这里捕获后做有限次重试，避免因瞬时 DB 抖动
     * 导致隐私数据漏清（假成功）。每次重试都是全新的整轮事务，前一次回滚的内容不会残留。
     * 重试耗尽仍失败则记 ERROR（运维需关注），但**不**阻断解绑/易主主流程。
     */
    private fun purgeDeviceDataWithRetry(deviceId: String, reason: String, maxRetries: Int = 3) {
        // 内存态不属于"私有数据清理"的重试范围：它必然成功，且必须在推流连接可能仍存活时
        // 就先把旧画面摘掉（易主场景下，新家长不该看到上一任孩子遗留的最后一帧）。
        runCatching { streamHub.evict(deviceId) }
            .onFailure { log.warn("evict stream cache for {} failed: {}", deviceId, it.message) }
        var lastErr: Throwable? = null
        repeat(maxRetries) { attempt ->
            try {
                deviceDataPurgeService.purge(deviceId, reason)
                return
            } catch (e: Exception) {
                lastErr = e
                log.warn("清理设备 {} 私有数据失败（第 {} 次，将重试）：{}", deviceId, attempt + 1, e.message)
            }
        }
        log.error("清理设备 {} 私有数据在 {} 次重试后仍失败，隐私数据可能残留：{}", deviceId, maxRetries, lastErr?.message)
    }

    /** 家长修改设备别名 */
    fun rename(userId: String, deviceId: String, name: String) {
        val dev = requireOwned(userId, deviceId)
        dev.name = name
        deviceRepository.save(dev)
        // 实时同步到被控端：通过下行 config 通道把自定义设备名推下去，
        // 孩子端「我的」页立即显示新名称，无需重连或重启。
        pushDeviceName(deviceId, name)
    }

    /** 孩子端自定义设备名（凭设备令牌鉴权，不依赖家长 userId） */
    fun updateNameByDevice(deviceId: String, name: String) {
        deviceRepository.findById(deviceId).ifPresent { dev ->
            dev.name = name
            deviceRepository.save(dev)
        }
        // 同步回被控端：确保各端（家长看板 / 孩子端本地）最终一致
        pushDeviceName(deviceId, name)
    }

    /** 把设备名通过 MQTT 下发行推给被控端 */
    private fun pushDeviceName(deviceId: String, name: String) {
        runCatching { mqttGateway.publishConfig(deviceId, mapOf("deviceName" to name)) }
            .onFailure { log.warn("push deviceName config failed: $it") }
    }

    /** 家长分配设备到分组 */
    fun assignToGroup(userId: String, deviceId: String, groupId: String) {
        val dev = requireOwned(userId, deviceId)
        dev.groupId = groupId
        deviceRepository.save(dev)
    }

    /**
     * 设备归属校验已收敛至 [com.padguard.server.security.DeviceOwnershipGuard]（唯一实现），
     * 消除逐 Service 复制私有副本的漏网风险（详见该组件类注释）。
     */
    private fun requireOwned(userId: String, deviceId: String): Device =
        ownershipGuard.requireOwned(userId, deviceId)

    /**
     * 把设备置为在线并刷新 `last_online_at`（MQTT `status=online` 等显式上线信号）。
     *
     * @param clientIp 本次请求的来源 IP（有则一并写入，供局域网发现用）
     */
    fun applyHeartbeatOnline(deviceId: String, clientIp: String? = null) {
        presence.touch(deviceId)
        setOnline(deviceId, clientIp)
    }

    /**
     * 落库置在线：一次读 + 一次写，顺带记录来源 IP。
     *
     * presence 登记由调用方负责（[applyHeartbeatOnline] / [markAlive] 各自已做）：
     * 若这里再 touch 一次，同一次信号会被当成两个样本、间隔 0 毫秒，
     * 把 [DevicePresenceRegistry] 的节奏均值往下拽，误判阈值随之变小。
     *
     * 合并写库是刻意的：改造前 `applyHeartbeatOnline` 和 `recordLanPresence` 各做一次
     * findById + save，同一个信号落库两遍 —— 这正是原实现必须把刷新间隔压到 30 秒
     * （[DevicePresenceRegistry.DB_REFRESH_MS] = 15s 的两倍）的直接原因。
     */
    private fun setOnline(deviceId: String, clientIp: String?) {
        deviceRepository.findById(deviceId).ifPresent { dev ->
            val now = System.currentTimeMillis()
            val wasOffline = dev.onlineStatus != "ONLINE"
            dev.onlineStatus = "ONLINE"
            dev.lastOnlineAt = now
            // IP 为空时不强写：把空串写进去会让上一次有效的地址平白丢失。
            clientIp?.takeIf { it.isNotBlank() }?.let {
                dev.lastIp = it
                dev.lastActiveAt = now
            }
            deviceRepository.save(dev)
            // 仅 OFFLINE/UNKNOWN -> ONLINE 这一跳推 WS：持续在线期间零推送、零副作用。
            if (wasOffline) {
                webSocketPush.pushToUser(dev.userId, mapOf(
                    "type" to "device.online", "deviceId" to deviceId,
                    "timestamp" to now
                ))
            }
        }
    }

    /**
     * 记录孩子端的一次 HTTP 活动（在线状态刷新 + 来源 IP）。
     *
     * 任意已鉴权的孩子端 HTTP 请求（拉指令、上报日志/截图、ack、心跳）都视为「存活」信号：
     * [com.padguard.server.security.ChildAuthInterceptor] 在鉴权成功后统一调用本方法。
     *
     * ## 快慢分离：内存每次登记，落库按秒级节流
     * 孩子端在线时每 3 秒拉一次指令，若每次都写 `devices` 行，单是"维护在线态"就占了
     * 高频写入的绝大部分，还会和孩子端日志上报抢行锁。所以：
     * - **内存**：每次信号都进 [DevicePresenceRegistry]（一次 CAS，无 SQL）——
     *   这是秒级离线判定的依据，丢一次就少一次判定机会；
     * - **落库**：只在状态翻转或距上次落库 ≥ [DevicePresenceRegistry.DB_REFRESH_MS] 时做，
     *   写放大被压到"在线设备数 / 15 秒"。
     *
     * @param clientIp 本次请求的来源 IP，写进 `devices.last_ip` 供局域网发现使用。
     *                 IP 可能为空（拿不到时），此时只刷新在线状态，不强写。
     */
    fun markAlive(deviceId: String, clientIp: String? = null) {
        // 绝大多数调用会在这里返回 false：稳态下只有内存写，没有 SQL。
        if (!presence.touch(deviceId)) return
        setOnline(deviceId, clientIp)
    }

    /**
     * 局域网发现：列出"当前这张网络里、可以接入到本账号"的平板。
     *
     * ## 判定口径（刻意收紧）
     * 1. 同网段：候选设备的 `lastIp` 与**调用者 IP** 处于同一 /24 网段。不同网段说明不是同一张局域网，
     *    把异地设备列出来只会造成误导（点了也连不上）。
     * 2. 近期活跃：`lastActiveAt` 在 5 分钟内。过早的地址早已失效（DHCP 重分配后 IP 会变）。
     * 3. **只列无主设备**：`userId` 为空，或归属账号已不存在。别人的设备即便恰好在同一网段，
     *    也绝不暴露型号与 IP —— 局域网发现不是"扫邻居"，把第三家庭的平板列给家长看是隐私事故。
     * 4. 排除已属于本人的：那些已经在设备列表里，不必重复显示。
     */
    fun discoverLanDevices(userId: String, callerIp: String?): List<LanDeviceDto> {
        val subnet = subnetOf(callerIp) ?: return emptyList()
        val since = System.currentTimeMillis() - LAN_DISCOVERY_WINDOW_MS

        val mine = deviceRepository.findByUserId(userId).map { it.id }.toHashSet()
        return deviceRepository.findByLastActiveAtGreaterThan(since)
            .asSequence()
            .filter { it.id !in mine }
            .filter { isOrphan(it) }
            .filter { subnetOf(it.lastIp) == subnet }
            .map {
                LanDeviceDto(
                    deviceId = it.deviceSn ?: it.id,
                    model = it.model,
                    ipAddress = it.lastIp ?: "",
                    osVersion = it.osVersion
                )
            }
            .toList()
    }

    /**
     * 接入一台局域网发现的平板（等价于孤儿设备的最后一步归属）。
     *
     * 走与"导入 / 复用台账"完全一致的口径：按 deviceSn 找到那条无主记录 → 归属当前家长 →
     * 重建默认策略。清理私有数据由 [DeviceDataPurgeService] 负责（设备从别人名下过来时必须清）。
     */
    @Transactional
    fun bindLanDevice(userId: String, req: LanBindRequest): DeviceDto {
        val hardwareId = req.hardwareId.trim()
        if (hardwareId.isBlank()) {
            throw BizException(ParentErr.PARAM_ERROR, "设备编号为空", Audience.PARENT)
        }
        // 「按 deviceSn 找无主设备 → 改归属」同样是 check-then-act：
        // 两个家长同时对同一台孤儿设备点接入，两边都会读到 userId 为空、也都认为自己抢到了，
        // 后提交者覆盖先提交者 —— 前一个家长会在毫无提示的情况下丢设备。
        // 与绑定主流程共用同一把按指纹的键锁，锁活到事务提交。
        return DeviceBindLocks.withKey(DeviceBindLocks.bindKey(hardwareId, null)) {
            claimLanDevice(userId, req, hardwareId)
        }
    }

    private fun claimLanDevice(userId: String, req: LanBindRequest, hardwareId: String): DeviceDto {
        val dev = deviceRepository.findByDeviceSn(hardwareId).firstOrNull { isOrphan(it) }
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "该设备已被其他账号接入或不存在", Audience.PARENT)

        if (!dev.userId.isNullOrBlank() && dev.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "该设备已被其他账号接入", Audience.PARENT)
        }
        // 订阅/设备数闸门：与 bindChildWithUser 同口径。
        // 缺了这行，订阅过期或设备数已满的账号可以通过局域网接入无限收编设备，
        // 等于给「付费才能用」开了一条免费后门。
        billingService.ensureCanBindForChild(userId)

        // 凭据已被吊销的设备（曾经解绑过）不能靠"改归属"接回来：
        // unbind 会把 deviceTokenHash 写成 revoked_xxx，而孩子端仍持旧令牌，
        // 这里若不轮换凭据，设备在列表里存在但永远 401/离线、指令全部 PENDING，
        // 表现为"绑定成功却什么都做不了"。凭据只能由孩子端重新配对获取，
        // 因此这里 fail-fast 给出可执行提示，而不是造一台僵尸设备。
        val tokenHash = dev.deviceTokenHash
        if (tokenHash.isNullOrBlank() || tokenHash.startsWith("revoked_")) {
            throw BizException(
                ParentErr.DEVICE_NOT_OWNED,
                "该设备已解除绑定，请让孩子端重新打开应用生成接入码后再绑定",
                Audience.PARENT
            )
        }

        val previousOwner = dev.userId
        dev.userId = userId
        dev.name = req.alias?.takeIf { it.isNotBlank() } ?: dev.name ?: "孩子平板"
        dev.scene = dev.scene ?: "FAMILY"
        deviceRepository.save(dev)

        // 从别人名下过来的必须清干净：截图/轨迹/用量/指令，见 DeviceDataPurgeService 的说明。
        if (!previousOwner.isNullOrBlank() && previousOwner != userId) {
            purgeDeviceDataWithRetry(dev.id, "TRANSFER")
        }
        policyService.createDefault(dev.id)
        auditService.record(userId, dev.id, AuditAction.DEVICE_BIND, targetType = "device", targetId = dev.id)
        return dev.toDto()
    }

    /** IPv4 /24 网段（前三段）。非 IPv4（含 IPv6）直接返回 null —— 不做半吊子的IPv6 前缀推算。 */
    private fun subnetOf(ip: String?): String? {
        val raw = ip?.trim().orEmpty()
        val v4 = raw.substringBefore('%')
            .let { if (it.startsWith("::ffff:", ignoreCase = true)) it.substring(7) else it }
        if (v4.isBlank() || v4 == "127.0.0.1" || v4 == "::1") return null
        val parts = v4.split('.')
        if (parts.size != 4) return null
        if (parts.any { it.toIntOrNull() == null }) return null
        return parts.take(3).joinToString(".")
    }

    /** 无人认领的设备：userId 为空，或归属账号已经不存在。 */
    private fun isOrphan(dev: Device): Boolean {
        val owner = dev.userId
        return owner.isNullOrBlank() || !userRepository.existsById(owner)
    }

    private fun Device.toDto() = DeviceDto(
        id = id, name = name, deviceId = id, model = model, osVersion = osVersion,
        appVersion = appVersion, onlineStatus = effectiveOnlineStatus(this),
        lastOnlineTime = lastOnlineAt, batteryLevel = batteryLevel,
        controlMode = controlMode, sceneMode = sceneMode, groupId = groupId,
        groupName = null, sceneType = scene, latitude = latitude, longitude = longitude,
        remoteLocked = remoteLocked ?: false,
        childName = childName,
        childNickname = childNickname,
        childAvatar = childAvatar,
        ageBand = ageBand,
        guardPasswordSet = guardPasswordHash?.isNotBlank() ?: false,
        // P3 守护健康度快照下发
        daemonAlive = daemonAlive,
        a11yAlive = a11yAlive,
        daemonOnlineState = daemonOnlineState,
        lastRestartReason = lastRestartReason,
        permissionVersion = permissionVersion,
        daemonLost = daemonLostAlerted ?: false
    )

    /**
     * 在线状态的"惰性超时"兜底判定：仅影响返回给家长端的值，不改库。
     *
     * 主判定路径已经是 [PresenceMonitorService]（内存信号 + 秒级扫描，约 5 秒翻转并落库）。
     * 这里只处理主路径不可用的边角：服务端刚重启、内存表尚未预热、扫描线程被阻塞等。
     * 因此 [ONLINE_STALE_MS] 只需要"比落库刷新间隔宽裕"即可（见其注释），
     * 不能反过来当成精度来源 —— 读取侧的超时永远滞后于真实状态。
     */
    private fun effectiveOnlineStatus(dev: Device): String {
        if (dev.onlineStatus != "ONLINE") return dev.onlineStatus
        val last = dev.lastOnlineAt ?: return "OFFLINE"
        return if (System.currentTimeMillis() - last > ONLINE_STALE_MS) "OFFLINE" else "ONLINE"
    }

    private companion object {
        /**
         * 在线状态惰性兜底阈值。
         *
         * 40 秒 = 落库刷新间隔（[DevicePresenceRegistry.DB_REFRESH_MS] = 15s）
         * × 2 余量 + 网络/GC 抖动。**它不是检测延迟**：正常路径下设备掉线约 5 秒就会被
         * [PresenceMonitorService] 写回 OFFLINE，这里只在"内存判定完全失效"时兜底。
         * 改造前该值是 120 秒，且是唯一判定依据 —— 家长端因此最坏要等 2 分钟以上。
         */
        const val ONLINE_STALE_MS = 40_000L

        /**
         * "Broker 说它离线"这条提示的可信窗口：最近这段时间内还有 HTTP 存活信号，
         * 就认为只是 MQTT 连接抖了一下（见 [handleBrokerOffline]）。
         * 取值略大于孩子端的拉指令间隔（3s），保证一次抖动不会把状态打翻。
         */
        const val BROKER_WILL_GRACE_MS = 3_500L

        /**
         * 局域网发现的时间窗（毫秒）：只认 5 分钟内露过面的设备。
         * 家庭 DHCP 租期通常几小時，但 IP 随时可能被分给别人；
         * 窗口开太长会让列表里出现"已经在用平板、但IP早变了"的僵尸项，点了也连不上。
         */
        const val LAN_DISCOVERY_WINDOW_MS = 300_000L
    }

    /**
     * 孩子端自定义个人资料（姓名 / 昵称 / 头像），写库即生效。
     * 任一字段为 null / 空串表示不修改（保留原值），便于孩子端逐项设置。
     * 更新后向家长端推送事件，家长端设备台账下次刷新即可看到。
     */
    fun updateChildProfile(deviceId: String, req: DeviceProfileRequest) {
        deviceRepository.findById(deviceId).ifPresent { dev ->
            req.childName?.takeIf { it.isNotBlank() }?.let { dev.childName = it }
            req.childNickname?.takeIf { it.isNotBlank() }?.let { dev.childNickname = it }
            req.childAvatar?.takeIf { it.isNotBlank() }?.let { dev.childAvatar = it }
            deviceRepository.save(dev)
            dev.userId?.let { uid ->
                webSocketPush.pushToUser(uid, mapOf(
                    "type" to "device.profile.updated",
                    "deviceId" to deviceId,
                    "timestamp" to System.currentTimeMillis()
                ))
            }
        }
    }
}
