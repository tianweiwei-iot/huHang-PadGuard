package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ChildErr
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.BindCode
import com.padguard.server.dto.BindCodeResponse
import com.padguard.server.dto.BindCodeStatusResponse
import com.padguard.server.repository.BindCodeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BindCodeService(
    private val bindCodeRepository: BindCodeRepository
) {
    /**
     * 家长端生成 6 位一次性绑定码（10 分钟有效）。
     *
     * 为什么重试：6 位码只有 90 万个取值，而过期码从不清理、会一直留在库里，
     * 于是"随机撞上已有主键"的概率随存量单调上升 —— 撞上就是主键冲突异常，
     * 表现为家长点一下「生成配对码」直接报错。重试几次即可化解。
     */
    fun generate(userId: String): BindCodeResponse {
        val now = System.currentTimeMillis()
        val expiresAt = now + 10 * 60_000
        repeat(MAX_GENERATE_RETRY) {
            val code = (100000..999999).random().toString()
            if (!bindCodeRepository.existsByCode(code)) {
                bindCodeRepository.save(
                    BindCode(code = code, userId = userId, expiresAt = expiresAt, createdAt = now)
                )
                return BindCodeResponse(code, expiresAt)
            }
        }
        throw BizException(ParentErr.SERVER_ERROR, "生成绑定码失败，请重试", Audience.PARENT)
    }

    /**
     * 孩子端消费绑定码，返回所属家长 userId；无效 / 过期 / 已用均抛异常。
     *
     * 声明事务是为了与后续绑定步骤同属一个原子操作：
     * 调用方 [DeviceService.bindChild] 在消费之后还要建设备台账并回写绑定结果，
     * 若其中任一步失败，这里置的 `used=true` 必须一并回滚 —— 否则码被白白烧掉，
     * 家长端会一直停在"等待孩子扫码"，直到超时才提示失败，而重试时码已不可用。
     */
    @Transactional
    fun consume(code: String): String {
        val now = System.currentTimeMillis()
        val bc = bindCodeRepository.findById(code).orElse(null)
            ?: throw BizException(ChildErr.BIND_CODE_EXPIRED, "绑定码无效", Audience.CHILD)
        if (bc.expiresAt < now) {
            throw BizException(ChildErr.BIND_CODE_EXPIRED, "绑定码已过期", Audience.CHILD)
        }
        // 原子抢占，而不是"读到 used=false 就认为抢到了"：
        // 并发下两个请求会同时读到未使用而各自通过，同一个码被绑两次。
        // 带条件的 UPDATE 由数据库串行化，第二次的影响行数必为 0。
        if (bindCodeRepository.claimIfUnused(code, now) == 0) {
            throw BizException(ChildErr.BIND_CODE_EXPIRED, "绑定码已使用", Audience.CHILD)
        }
        return bc.userId
    }

    /**
     * 绑定完成后，把结果设备回写到这张绑定码上。
     *
     * 家长端判定"绑定成功"必须靠轮询这个码的状态，而不是等设备列表里出现新 deviceId：
     * 孩子端重装 / 重新扫码时，服务端会按硬件指纹复用同一条设备台账
     * （见 [DeviceService.matchExistingDevice]），**deviceId 根本不会变**。
     * 若家长端只认"新出现的 deviceId"，重绑就永远判定超时 ——
     * 表现为"孩子端明明已经绑上并开始上报心跳，家长端却提示绑定未成功"。
     * 所以绑定结果必须落到码上，给家长端一个一锤定音的依据。
     */
    fun markBound(code: String, deviceId: String) {
        if (code.isBlank()) return
        val bc = bindCodeRepository.findById(code).orElse(null) ?: return
        bc.deviceId = deviceId
        bindCodeRepository.save(bc)
    }

    /**
     * 家长端轮询绑定码状态（三态见 [BindCodeStatusResponse]）。
     *
     * 只查自己名下生成的码：6 位码空间很小（90 万），
     * 若不校验归属，凭穷举就能试探出别人绑到了哪台设备。
     */
    fun statusOf(code: String, userId: String): BindCodeStatusResponse {
        val bc = bindCodeRepository.findById(code).orElse(null)
        if (bc == null || bc.userId != userId) {
            return BindCodeStatusResponse(STATE_EXPIRED, null, 0)
        }
        val deviceId = bc.deviceId
        val state = when {
            !deviceId.isNullOrBlank() -> STATE_BOUND
            bc.expiresAt < System.currentTimeMillis() -> STATE_EXPIRED
            // 已被消费但设备还没回写（绑定事务尚未提交完）：仍算进行中，继续等
            else -> STATE_PENDING
        }
        return BindCodeStatusResponse(state, deviceId, bc.expiresAt)
    }

    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_BOUND = "BOUND"
        const val STATE_EXPIRED = "EXPIRED"

        /** 生成码撞上已有主键时的重试次数（90 万取值，撞 5 次的概率可忽略） */
        private const val MAX_GENERATE_RETRY = 5
    }
}
