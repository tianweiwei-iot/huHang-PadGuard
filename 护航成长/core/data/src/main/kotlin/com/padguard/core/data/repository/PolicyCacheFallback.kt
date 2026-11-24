package com.padguard.core.data.repository

import com.padguard.core.data.model.policy.PolicyPackage

/**
 * 策略缓存「读不出内容」时的取值与自愈决策（纯函数层，无 Android 依赖，可直接 JVM 单测）。
 *
 * ## 为什么必须把这段逻辑单独钉住（新增缺陷 L）
 *
 * [PolicyRepository] 的策略包用 Android Keystore 加密落库。旧实现一旦解密失败
 * （改/清锁屏密码后密钥失效、系统升级、备份恢复、加密行被写坏）就做两件事：
 *
 * 1. `dao.clear()` **物理删除**唯一一份本地策略；
 * 2. 回落 [PolicyPackage.default] —— 这是「宵禁关、时段规则空、日额度 0、
 *    黑白名单全空」的**不管控基线**，且 `observePolicy()` 会把这份 default 回写到
 *    `AppGuardPolicyStore` 的独立镜像，连应用管控那份好快照一起覆盖掉。
 *
 * 注释里的辩护是「服务端下一轮同步会重新下发」，但这个前提**只在联网时成立**：
 * 离线设备一次密钥失效 = 宵禁/额度/应用管控永久全放开，且原行已删，连重试解密的机会都没有。
 * 这直接违反「离线状态下时间管控、应用管控必须本地独立生效」的产品红线。
 *
 * 本层只负责**决策**，所有 Room/Keystore 副作用留在 Repository：
 * - [resolve]：优先沿用本进程上次成功解码的策略，绝不因为「读不出来」就主动放开；
 * - [shouldMirror]：缓存不可信时禁止把 default 回写到应用管控镜像 / 总开关 / 护眼滤镜；
 * - [shouldAcceptIncoming] / [reportedVersion]：解开「保留坏行反而让重发被版本门槛挡掉」的死结，
 *   让服务端下一轮（哪怕只重发同版本）能覆盖坏行完成自愈。
 */
object PolicyCacheFallback {

    /**
     * 一次读取该返回什么策略。
     *
     * @param entityPresent 库里是否存在策略行。`false` = **从未下发过策略**，
     *   回落到 default 是既定语义（首次安装不该把设备锁死），与「读坏了」性质完全不同。
     * @param decoded 本次解密结果，`null` 表示解密/反序列化失败。
     * @param lastKnownGood 本进程上一次成功解码的策略副本，可能为 null（冷启动首次读即失败）。
     */
    fun resolve(
        entityPresent: Boolean,
        decoded: PolicyPackage?,
        lastKnownGood: PolicyPackage?,
        /** 设备上曾经成功保存过策略、但这次读取发现行不见了（删库/备份恢复/ROOT 篡改）。 */
        policyRowLost: Boolean = false
    ): PolicyPackage {
        // 真正从未下发过策略：默认基线可信，允许正常写镜像。
        if (!entityPresent && !policyRowLost) return PolicyPackage.default()
        // 已下发过却丢行：绝不把 default 当成可信策略返回。有进程内好快照就沿用它，
        // 没有则返回 default 但由 repository 的 decodeFailed 闸门阻止它污染镜像。
        if (!entityPresent && policyRowLost) return lastKnownGood ?: PolicyPackage.default()
        // 本次解开了：以最新内容为准。
        if (decoded != null) return decoded
        // 有策略行却解不开：沿用上次已知良好快照，管控强度不下降。
        if (lastKnownGood != null) return lastKnownGood
        // 冷启动首次读即失败、本进程没有任何可信副本：只能回落 default。
        // 这是**有意保留的残余边界**（见报告），由 [shouldMirror] 阻止它污染应用管控镜像，
        // 并由 [reportedVersion] 促使服务端尽快重发；是否需要落一份独立持久快照属产品拍板项。
        return PolicyPackage.default()
    }

    /**
     * 本次策略是否可信到足以**回写**镜像 / 整机总开关 / 护眼滤镜。
     *
     * 只要本进程曾成功解码过一次，或当前缓存根本没坏，就允许；
     * 否则（冷启动即坏且无副本）必须跳过回写，保住 `AppGuardPolicyStore` 里
     * 那份跨进程持久化的好快照，避免「时间策略读坏了，顺手把应用策略也清空」。
     */
    fun shouldMirror(decodeFailed: Boolean, lastKnownGood: PolicyPackage?): Boolean =
        !decodeFailed || lastKnownGood != null

    /**
     * 服务端下发的新策略是否应当接受。
     *
     * 常规路径保持「版本不高于本地一律丢弃」的防回退语义（见 `PolicyRepository.save` 注释）。
     * 但缓存已损坏时**必须放行任意版本**：本地那份是坏行/默认基线，服务端很可能认为
     * 「设备已是 vN」而只重发同一个 vN；若仍按版本比较丢弃，坏行永远修不回来。
     */
    fun shouldAcceptIncoming(
        decodeFailed: Boolean,
        incomingVersion: Int,
        currentVersion: Int
    ): Boolean = if (decodeFailed) true else incomingVersion > currentVersion

    /**
     * 上报给服务端的「设备当前策略版本」。
     *
     * 服务端以 `policy.version == currentVersion` 判断设备是否最新。缓存损坏时坏行里的
     * 版本号仍在，会让服务端误判「不用重发」→ 设备永远等不到修复。返回 0 强制触发重发。
     */
    fun reportedVersion(decodeFailed: Boolean, rawVersion: Int): Int =
        if (decodeFailed) 0 else rawVersion.coerceAtLeast(0)
}
