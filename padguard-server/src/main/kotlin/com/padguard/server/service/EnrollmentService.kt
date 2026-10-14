package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ChildErr
import com.padguard.server.domain.EnrollmentSession
import com.padguard.server.dto.EnrollmentDto
import com.padguard.server.repository.EnrollmentSessionRepository
import com.padguard.server.ws.WebSocketPushService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 设备接入（Enrollment）的唯一权威。
 *
 * ## 它取代了什么
 * 旧的 `BindCodeService` 里，一个 6 位随机串同时充当二维码内容、配对码展示值和绑定结果
 * 的回写位置。三者共用一个没有身份的对象，直接导致三个后果：
 *   1. 家长端要同时展示二维码和配对码，只能各调一次生成接口 → 拿到两个不同的码；
 *   2. 绑定结果只能 `markBound` 硬写回码上，码既是凭证又是状态容器；
 *   3. 家长端无从订阅"这次接入"的进展，只能拿码盲轮询，轮询错了码就永远 PENDING。
 *
 * 这里把"一次接入"提升为实体 [EnrollmentSession]：二维码与配对码只是它的两种呈现，
 * 绑定结果落在它的 `deviceId` 上，进展通过 WS 主动推给家长端。
 *
 * ## 对外契约保持兼容
 * 孩子端提交的 `BindRequest.bindCode` 仍是 6 位短码（[EnrollmentSession.pairingCode]），
 * 孩子端无需任何改动 —— 换码只换短码，会话身份 [EnrollmentSession.id] 不变。
 */
@Service
class EnrollmentService(
    private val repository: EnrollmentSessionRepository,
    private val webSocketPush: WebSocketPushService
) {

    /**
     * 打开接入会话：**幂等**。
     *
     * 家长端每次进入接入页都调它。若该家长名下已有一个尚未过期、仍处于 PENDING 的会话，
     * 直接复用 —— 否则家长"退出去再进来"就会换一个码，孩子端手里那个刚扫到的码立刻失效，
     * 表现为"刚扫完就说码无效"。幂等是这里的关键，不是优化。
     */
    @Transactional
    fun open(userId: String): EnrollmentDto {
        val now = System.currentTimeMillis()
        repository.purgeTerminalBefore(now - TERMINAL_RETENTION_MILLIS)
        val live = repository.findFirstByUserIdAndStateOrderByCreatedAtDesc(userId, STATE_PENDING)
            ?.takeIf { it.expiresAt > now }
        return (live ?: create(userId, now)).toDto(now)
    }

    /**
     * 家长端查询"我这次接入现在什么状态"。
     *
     * 返回最近一条会话（含已完成的），这样孩子端绑上之后，家长端即使错过 WS 推送，
     * 也能靠这一次查询拿到 BOUND 与 deviceId —— 推送是快路径，查询是必然正确的兜底。
     */
    fun current(userId: String): EnrollmentDto? {
        val now = System.currentTimeMillis()
        return repository.findFirstByUserIdOrderByCreatedAtDesc(userId)?.toDto(now)
    }

    /** 主动换一个码（家长认为码已泄露、或孩子端一直绑不上时） */
    @Transactional
    fun refresh(userId: String): EnrollmentDto {
        val now = System.currentTimeMillis()
        repository.findFirstByUserIdAndStateOrderByCreatedAtDesc(userId, STATE_PENDING)
            ?.let { it.state = STATE_CANCELLED; repository.save(it) }
        return create(userId, now).toDto(now)
    }

    /** 作废当前进行中的接入 */
    @Transactional
    fun cancel(userId: String) {
        repository.findFirstByUserIdAndStateOrderByCreatedAtDesc(userId, STATE_PENDING)
            ?.let { it.state = STATE_CANCELLED; repository.save(it) }
    }

    /**
     * 孩子端消费短码，返回这次接入的归属家长与会话身份。
     *
     * 声明事务是为了与后续绑定步骤同属一个原子操作：调用方
     * [DeviceService.bindChild] 消费之后还要建设备台账并回写结果，任一步失败时
     * 这里的抢占必须一并回滚 —— 否则码被白白烧掉，家长端一直停在"等待孩子扫码"。
     */
    @Transactional
    fun consume(code: String): EnrollmentClaim {
        val now = System.currentTimeMillis()
        val session = repository.findByPairingCode(code)
            ?: throw BizException(ChildErr.BIND_CODE_EXPIRED, "接入码无效", Audience.CHILD)
        // 抢占失败要给出可执行的下一步，而不是笼统的"绑定失败"
        if (session.state != STATE_PENDING || session.expiresAt < now) {
            val message = if (session.expiresAt < now) "接入码已过期，请在家长端刷新后重试"
            else "接入码已被使用，请在家长端重新获取"
            throw BizException(ChildErr.BIND_CODE_EXPIRED, message, Audience.CHILD)
        }
        // 状态迁移**只在这里、只经由实体**发生。
        //
        // 不能用 `@Modifying` 的条件 UPDATE 来做这件事：批量更新绕过持久化上下文，
        // 库里 state 已变而缓存中的实体仍是 PENDING，紧接着 [markBound] 回写 deviceId 时
        // 保存的是那个陈旧实体，提交时的脏检查会把 state 一起覆盖回 PENDING ——
        // 设备绑上了、deviceId 也回写了，家长端却永远停在"等待孩子扫码"。
        //
        // 并发排他改由 [EnrollmentSession.revision]（@Version）承担：两个孩子端同时提交
        // 同一短码时，后提交者的 `update ... where id=? and revision=?` 影响行数为 0，
        // 乐观锁冲突使整笔事务（含它刚建的设备台账）一并回滚。
        session.state = STATE_BOUND
        session.boundAt = now
        repository.save(session)
        return EnrollmentClaim(userId = session.userId, sessionId = session.id)
    }

    /**
     * 绑定完成后回写结果并通知家长端。
     *
     * 家长端判定"接入成功"的依据是**这条会话的状态**（配合 WS 推送），
     * 而不是"设备列表里出现了新 deviceId"：孩子端重装/重新接入时服务端按硬件指纹复用台账，
     * deviceId 根本不变，只看列表永远等不到"新设备"。
     */
    fun markBound(sessionId: String, deviceId: String) {
        // 「账号密码绑定」这条路径不经过接入会话，静默跳过（历史上这里传空码同样要求 no-op）
        if (sessionId.isBlank()) return
        val session = repository.findById(sessionId).orElse(null) ?: return
        val now = System.currentTimeMillis()
        session.deviceId = deviceId
        session.boundAt = now
        repository.save(session)
        // 主动推送：家长端不必轮询，WS 断开时它会退化到查询 [current]
        webSocketPush.pushToUser(
            session.userId,
            mapOf(
                "type" to EVENT_DEVICE_ENROLLED,
                "sessionId" to sessionId,
                "deviceId" to deviceId
            )
        )
    }

    private fun create(userId: String, now: Long): EnrollmentSession {
        val session = EnrollmentSession(
            id = UUID.randomUUID().toString(),
            userId = userId,
            pairingCode = generateUniqueCode(),
            state = STATE_PENDING,
            expiresAt = now + TTL_MILLIS,
            createdAt = now
        )
        return repository.save(session)
    }

    /**
     * 6 位短码只有 90 万个取值，而历史会话会累积 —— 撞上唯一约束就是插入失败，
     * 表现为家长点开接入页直接报错。重试几次即可化解（存量 10 万时，5 次全撞的概率可忽略）。
     */
    private fun generateUniqueCode(): String {
        repeat(MAX_CODE_RETRY) {
            val code = (100000..999999).random().toString()
            if (!repository.existsByPairingCode(code)) return code
        }
        throw BizException(ChildErr.SERVER_ERROR, "接入码生成失败，请重试", Audience.PARENT)
    }

    /**
     * 状态的最终判定：PENDING 且已过期的会话一律视为 EXPIRED。
     *
     * 惰性判定而不依赖定时扫描，省掉一个"定时任务没跑导致界面显示已过期却仍显示等待中"
     * 的失败面；定时任务只负责清理，不负责正确性。
     */
    private fun effectiveState(session: EnrollmentSession, now: Long): String =
        if (session.state == STATE_PENDING && session.expiresAt < now) STATE_EXPIRED else session.state

    private fun EnrollmentSession.toDto(now: Long): EnrollmentDto = EnrollmentDto(
        sessionId = id,
        pairingCode = pairingCode,
        qrPayload = QR_PAYLOAD_PREFIX + pairingCode,
        state = effectiveState(this, now),
        deviceId = deviceId,
        expiresAt = expiresAt,
        serverNow = now
    )

    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_BOUND = "BOUND"
        const val STATE_EXPIRED = "EXPIRED"
        const val STATE_CANCELLED = "CANCELLED"

        /** 家长端 WS 推送的事件类型，与客户端解析分支一一对应 */
        const val EVENT_DEVICE_ENROLLED = "device.enrolled"

        /** 二维码载荷前缀：孩子端扫码后按 `code` 取短码，两端约定不得改写 */
        const val QR_PAYLOAD_PREFIX = "padguard://bind?code="

        /** 接入码有效期：10 分钟。够长到家长能从容操作，够短到泄露窗口有限 */
        const val TTL_MILLIS = 10 * 60_000L

        /** 终态会话保留时长（供排障追溯），超出即清理，避免短码空间被历史数据挤占 */
        private const val TERMINAL_RETENTION_MILLIS = 24 * 60 * 60 * 1000L

        private const val MAX_CODE_RETRY = 5
    }
}

/** 一次接入被孩子端抢占后的归属信息 */
data class EnrollmentClaim(val userId: String, val sessionId: String)
