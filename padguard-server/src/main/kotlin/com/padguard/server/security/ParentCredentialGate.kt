package com.padguard.server.security

import com.padguard.server.domain.User
import com.padguard.server.domain.UserStatus

/**
 * 家长账号密码校验的**共享判定层**（纯函数，无 Spring / DB 依赖，可直接 JVM 单测）。
 *
 * ## 为什么要抽出来（新增缺陷 M）
 * 项目里校验同一个 `users.password_hash` 的入口有两条：
 * `AuthService.loginWithPassword`（家长端登录）与 `DeviceService.bindChildByAccount`
 * （孩子端拿家长手机号+密码直接绑定设备）。登录那条已经补齐了账号级锁定、
 * 失败计数、冻结/待激活状态校验；绑定那条却只有 `findByPhone + Passwords.matches` ——
 * 攻击者只要换到绑定通道，就能以 8 次/分钟的 IP 频控之外**无限低速**跑密码字典，
 * 冻结账号也照样能绑，一旦命中直接拿到该家长名下设备的
 * `deviceToken / mqttPassword / hmacSecret`。
 *
 * 把判定口径收敛到这里，两条入口只剩「异常码与文案」按各自 Audience 不同：
 * - [isLocked] / [remainingMinutes]：锁定期；
 * - [isUsable]：账号状态（冻结、待激活都不许通过）；
 * - [shouldClearFailure] / [lockUntilAfter] / [maxAttempts]：失败计数的写库参数与阈值。
 *
 * DB 副作用（registerLoginFailure / clearLoginFailure）仍留在各自 Service，
 * 因为它们的事务边界与失败降级策略不同，不应被组件偷偷吞掉。
 */
object ParentCredentialGate {

    fun isLocked(lockUntil: Long?, nowMillis: Long): Boolean =
        (lockUntil ?: 0L) > nowMillis

    /** 锁定期剩余分钟，最少 1 分钟，避免出现「请约 0 分钟后再试」。 */
    fun remainingMinutes(lockUntil: Long?, nowMillis: Long): Long =
        (((lockUntil ?: 0L) - nowMillis) / 60_000L).coerceAtLeast(1)

    fun isUsable(user: User): Boolean = UserStatus.isUsable(user.status)

    /** 只有确实存在残留计数/锁定态时才写 UPDATE，避免每次成功登录都多一次写放大。 */
    fun shouldClearFailure(failCount: Int?, lockUntil: Long?): Boolean =
        (failCount ?: 0) != 0 || (lockUntil ?: 0L) != 0L

    /** 阈值最小 1，防配置为 0/负数时变成「一次失败即永久锁定」或永远不锁。 */
    fun maxAttempts(configured: Int): Int = configured.coerceAtLeast(1)

    fun lockUntilAfter(nowMillis: Long, configuredMinutes: Int): Long =
        nowMillis + configuredMinutes.coerceAtLeast(1) * 60_000L
}
