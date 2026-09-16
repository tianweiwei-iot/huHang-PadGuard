package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ChildErr
import com.padguard.server.dto.BindCodeResponse
import com.padguard.server.repository.BindCodeRepository
import org.springframework.stereotype.Service

@Service
class BindCodeService(
    private val bindCodeRepository: BindCodeRepository
) {
    /** 家长端生成 6 位一次性绑定码（10 分钟有效） */
    fun generate(userId: String): BindCodeResponse {
        val code = (100000..999999).random().toString()
        val now = System.currentTimeMillis()
        val expiresAt = now + 10 * 60_000
        bindCodeRepository.save(
            com.padguard.server.domain.BindCode(
                code = code, userId = userId, expiresAt = expiresAt, createdAt = now
            )
        )
        return BindCodeResponse(code, expiresAt)
    }

    /** 孩子端消费绑定码，返回所属家长 userId；无效/过期/已用均抛异常 */
    fun consume(code: String): String {
        val bc = bindCodeRepository.findById(code).orElse(null)
            ?: throw BizException(ChildErr.BIND_CODE_EXPIRED, "绑定码无效", Audience.CHILD)
        if (bc.used) throw BizException(ChildErr.BIND_CODE_EXPIRED, "绑定码已使用", Audience.CHILD)
        if (bc.expiresAt < System.currentTimeMillis()) {
            throw BizException(ChildErr.BIND_CODE_EXPIRED, "绑定码已过期", Audience.CHILD)
        }
        bc.used = true
        bindCodeRepository.save(bc)
        return bc.userId
    }
}
