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
import java.util.concurrent.ConcurrentHashMap

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
    private val deviceDataPurgeService: DeviceDataPurgeService
) {

    private val log = LoggerFactory.getLogger(DeviceService::class.java)

    /** deviceId -> 上次因 HTTP 活动刷新在线状态的时刻，用于 [markAlive] 节流 */
    private val aliveRefreshedAt = ConcurrentHashMap<String, Long>()
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
     */
    fun bindChildWithUser(req: BindRequest, userId: String): BindResult {
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

    fun setOffline(deviceId: String) {
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

    fun applyHeartbeatOnline(deviceId: String) {
        deviceRepository.findById(deviceId).ifPresent { dev ->
            val wasOffline = dev.onlineStatus != "ONLINE"
            dev.onlineStatus = "ONLINE"
            dev.lastOnlineAt = System.currentTimeMillis()
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
     * 任意已鉴权的孩子端 HTTP 活动（长轮询拉指令、上报日志/截图、ack 等）都视为「存活」信号，
     * 刷新 [Device.lastOnlineAt] 并把在线状态置为 ONLINE。
     *
     * 这是 MQTT 关闭（local profile）环境下的关键兜底：孩子端在 POLLING 模式下只跑「轮询循环」
     * （pullCommands + uploadLogs），主循环的心跳（/device/heartbeat）并不触达服务端，
     * 导致 [Device.onlineStatus] 永远停在默认 OFFLINE、家长端看到设备永远离线。
     * 把这些高频 HTTP 活动当作心跳等价信号，由 [com.padguard.server.security.ChildAuthInterceptor]
     * 在每次鉴权成功后统一刷新，无需重建孩子端 APK 即可让在线状态正确翻转。
     *
     * 仅当状态由 OFFLINE→ONLINE 时才推 WS（[applyHeartbeatOnline] 已处理），
     * 持续在线期间不会重复推送，零副作用；离线后由 [effectiveOnlineStatus] 的 2 分钟惰性超时兜底判离线。
     */
    /**
     * 节流：孩子端在轮询模式下每 3 秒就有一次 HTTP 请求，若每次都写一次 devices 行，
     * 单是"在线状态维护"就占了高频写入的绝大部分，还会和孩子端日志上报抢写锁。
     * 而在线状态晚 30 秒刷新对业务零影响 —— 离线判定本身就有 2 分钟惰性超时兜底
     * （见 [effectiveOnlineStatus]），30 秒的刷新粒度远小于该窗口。
     */
    /**
     * 记录孩子端的一次 HTTP 活动（在线状态刷新 + 来源 IP）。
     *
     * @param clientIp 本次请求的来源 IP，写进 `devices.last_ip` 供局域网发现使用。
     *                 IP 可能为空（拿不到时），此时只刷新在线状态，不强写——
     *                 把空串写进去会让上一次有效的地址平白丢失。
     */
    fun markAlive(deviceId: String, clientIp: String? = null) {
        val now = System.currentTimeMillis()
        var due = false
        aliveRefreshedAt.compute(deviceId) { _, last ->
            if (last == null || now - last >= ALIVE_REFRESH_INTERVAL_MS) {
                due = true
                now
            } else {
                last
            }
        }
        if (due) {
            applyHeartbeatOnline(deviceId)
            recordLanPresence(deviceId, clientIp, now)
        }
    }

    /**
     * 登记"这台平板最近出现在这张网络里"。
     *
     * 单独一个方法而不是并入 [applyHeartbeatOnline]：那边的职责是维护在线状态与阈值，
     * 混进来会让"为什么在线判定改了会影响局域网发现"这种耦合在半年后无法解释。
     */
    private fun recordLanPresence(deviceId: String, clientIp: String?, now: Long) {
        val ip = clientIp?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            deviceRepository.findById(deviceId).orElse(null)?.let { dev ->
                dev.lastIp = ip
                dev.lastActiveAt = now
                deviceRepository.save(dev)
            }
        }.onFailure { log.warn("记录设备 {} 的来源 IP 失败(已忽略): {}", deviceId, it.message) }
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
        val dev = deviceRepository.findByDeviceSn(hardwareId).firstOrNull { isOrphan(it) }
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "该设备已被其他账号接入或不存在", Audience.PARENT)

        if (!dev.userId.isNullOrBlank() && dev.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "该设备已被其他账号接入", Audience.PARENT)
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
        guardPasswordSet = guardPasswordHash?.isNotBlank() ?: false
    )

    /**
     * 在线状态的"惰性超时"兜底判定：仅影响返回给家长端的值，不改库。
     *
     * 背景：设备上线状态早期完全依赖两个事件 —— MQTT 断线触发 [setOffline]，或孩子端心跳
     * 触发 [applyHeartbeat] 置 ONLINE。但存在两个盲区：
     * 1. local profile 下 MQTT 关闭，[setOffline] 永不触发；
     * 2. HTTP 降级心跳只把状态置为 ONLINE，从不置 OFFLINE。
     * 结果：孩子端一旦卸载/关机/断网，服务端 [onlineStatus] 永远停在最后一次的 "ONLINE"，
     * 家长端便一直显示"在线"，进而"解绑后设备仍在列表里、还被优先选为当前设备"。
     *
     * 这里以最后一次心跳时间 [Device.lastOnlineAt] 兜底：若距今超过 [ONLINE_STALE_MS]
     * （默认 2 分钟，覆盖 30~60s 心跳间隔 + 网络抖动冗余），即视为已离线返回 "OFFLINE"。
     * 不依赖 MQTT、不回写库，零风险且立即生效。
     */
    private fun effectiveOnlineStatus(dev: Device): String {
        if (dev.onlineStatus != "ONLINE") return dev.onlineStatus
        val last = dev.lastOnlineAt ?: return "OFFLINE"
        return if (System.currentTimeMillis() - last > ONLINE_STALE_MS) "OFFLINE" else "ONLINE"
    }

    private companion object {
        /** 在线状态超时阈值：最后一次心跳距今超过该值即判为离线（毫秒，默认 2 分钟）。 */
        const val ONLINE_STALE_MS = 120_000L

        /** 同一设备"HTTP 活动刷新在线状态"的最小间隔（见 [markAlive]） */
        const val ALIVE_REFRESH_INTERVAL_MS = 30_000L

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
