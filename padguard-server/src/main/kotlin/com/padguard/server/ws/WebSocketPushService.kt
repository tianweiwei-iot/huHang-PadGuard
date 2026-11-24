package com.padguard.server.ws

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.UserStatus
import com.padguard.server.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 家长端实时推送：按 userId 维护 WS 会话。
 *
 * `open` 不是为继承而设计，而是为了让测试能替换 pushToUser 断言"事件确实被推给了正确的家长"——
 * 推送是接入链路的关键一环（家长端据此结束等待），必须可测。
 *
 * ## 会话集合为什么不是裸的 `ConcurrentHashMap.newKeySet()`（R-P3-7）
 * 1. **需要"踢旧"顺序**：同一账号在多台手机上登录、或客户端异常重连而旧连接没断，
 *    会话会越堆越多，最终把连接池耗尽。上限必须按**注册先后**淘汰最旧的那条，
 *    因此用保持插入顺序的 `LinkedHashSet`（`newKeySet()` 派生自 ConcurrentHashMap，无序，
 *    淘汰谁全看哈希，可能把家长正在用的那条踢掉）。
 * 2. **必须能清掉死会话**：客户端拔网线 / 进程被杀时 `afterConnectionClosed` 未必回调，
 *    只靠 `unregister` 会让集合无限增长，每次推送都对着几百个 `!isOpen` 的会话空转。
 *
 * 线程安全：外层 `ConcurrentHashMap` 保证按 userId 的并发访问；内层集合的所有读写都在
 * `synchronized(set)` 内完成（集合本身虽是 `synchronizedSet`，但**迭代**仍必须显式加锁）。
 *
 * ## 运行期账号复核（新增缺陷 K）
 * HTTP 是无状态的：每个请求都过 `ParentAuthInterceptor`，令牌版本 / 账号状态一变立刻生效。
 * WS 却只在握手时校验一次，此后长期信任这条连接 —— 家长登出、后台冻结、重置密码后，
 * 旧连接仍会持续收到该家庭的告警、解锁申请、截图与定位推送。
 * 因此 [pushToUser] 在每次推送前复核账号是否仍然有效，失效即整账号踢线；
 * 各吊销点另调用 [revokeUser] 做即时掐断，两者互为兜底（不依赖调用点不漏抄）。
 */
@Component
open class WebSocketPushService(
    private val objectMapper: ObjectMapper,
    /**
     * 单账号最多保留的并发会话数，超限时踢掉**最旧**的一条。
     *
     * 默认 5：手机 + 平板 + 车机等多端同时在线够用，又能挡住"脚本反复建连"把连接吃满。
     * 可用 `padguard.ws.max-sessions-per-user` 覆盖。
     */
    @Value("\${padguard.ws.max-sessions-per-user:5}")
    private val maxSessionsPerUser: Int = DEFAULT_MAX_SESSIONS_PER_USER,
    /**
     * 账号有效性复核的数据源。**必填且不可为 null**：这一层是"冻结/登出/重置密码后旧连接不得再收推送"
     * 的唯一兜底。此前为了单测方便留了 `UserRepository?` 并在 null 时直接放行，
     * 生产一旦依赖注入异常就会静默退化成"不复核"（fail-open），风险远大于单测构造噪音。
     * 单测请显式注入一个返回 active 用户的仓储替身，绝不许再把生产依赖做成可空来迁就用例。
     */
    private val users: UserRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val sessions = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()

    /**
     * 正在写 socket 的会话集合（慢客户端隔离）。
     *
     * `WebSocketSession.sendMessage` 是**阻塞写**：客户端拔网线 / 进电梯 / 进程被杀但 TCP 未 FIN 时，
     * 内核发送缓冲区写满后该调用会一直卡到容器超时（Tomcat 默认 `blockingSendTimeout` 20 秒）才抛异常。
     * 而 [pushToUser] 的调用方大多是**上行回调线程**（MQTT 的 `messageArrived` → 落库 → afterCommit 里推送），
     * 一个卡死的家长会话会把整条上行链路（心跳、回执、事件）一起堵住 20 秒 ——
     * 表现就是"全部设备突然集体延迟/掉线"，而日志里只有一条 20 秒后的 send 失败。
     *
     * 因此同一会话上一条推送还没写完时，本条**直接跳过**（丢弃这条实时信号），
     * 而不是排队等锁：排队只会让 N 个调用线程依次各卡一次，把一次故障放大成 N 次。
     * 丢弃是安全的 —— 实时推送本来就是"信号"，HTTP 才是唯一事实源（家长端每次进入页面都会重新拉全量）。
     */
    private val inFlight: MutableSet<WebSocketSession> = ConcurrentHashMap.newKeySet()

    fun register(userId: String, session: WebSocketSession) {
        val evicted = mutableListOf<WebSocketSession>()
        // 用 compute 而不是 computeIfAbsent + 事后再改：后者存在「取到集合 → 另一线程
        // unregister 把空集合从 map 摘掉 → 本线程把新会话加进那个已脱离 map 的孤儿集合」
        // 的竞态，会话注册成功却永远收不到推送。compute 对同一 key 串行，取集合与写入
        // 在同一临界区内完成。注意锁序始终是 compute(桶锁) → synchronized(set)。
        sessions.compute(userId) { _, existing ->
            val set = existing ?: Collections.synchronizedSet(LinkedHashSet())
            synchronized(set) {
                // 顺手清死会话：异常断开时 onClose 未必回调，不清就会无限增长。
                val dead = set.filterNot { it.isOpen }
                if (dead.isNotEmpty()) set.removeAll(dead.toSet())

                set.add(session)

                val cap = maxSessionsPerUser.coerceAtLeast(1)
                while (set.size > cap) {
                    // LinkedHashSet 的迭代顺序 = 注册先后，第一个就是最旧的
                    val oldest = set.firstOrNull() ?: break
                    if (oldest === session) break // 兜底：绝不踢掉刚注册的这条
                    set.remove(oldest)
                    evicted += oldest
                }
            }
            set
        }
        // 关闭动作放在锁外执行：close 可能触发回调，避免持锁重入
        evicted.forEach { closeQuietly(userId, it, "evicted") }
    }

    fun unregister(userId: String, session: WebSocketSession) {
        val set = sessions[userId] ?: return
        synchronized(set) { set.remove(session) }
        // 不能在 synchronized(set) 内 sessions.remove：那会形成 set 锁 → 桶锁的反向锁序，
        // 与 register 的桶锁 → set 锁构成死锁。也不能无条件 remove(key, set)——
        // 若此刻已有并发注册把新会话加回同一个 set，会把新会话一起摘掉。
        // computeIfPresent 在桶锁内校验"仍是同一集合且已空"，两步原子。
        sessions.computeIfPresent(userId) { _, current ->
            if (current === set && synchronized(current) { current.isEmpty() }) null else current
        }
    }

    /**
     * 立即吊销某账号的全部 WS 会话（登出 / 冻结 / 重置密码时调用）。
     *
     * 幂等：账号没有会话时返回 0，不抛异常；关闭动作放在锁外，避免 close 触发回调重入。
     * 与 [pushToUser] 的复核不冲突：一边是"事件发生时立刻掐断"，一边是"万一漏调也挡住推送"。
     */
    fun revokeUser(userId: String): Int {
        val set = sessions.remove(userId) ?: return 0
        val targets: List<WebSocketSession> = synchronized(set) {
            set.toList().also { set.clear() }
        }
        targets.forEach { closeQuietly(userId, it, "revoked") }
        if (targets.isNotEmpty()) {
            log.info("WS sessions revoked: user={} count={}", userId, targets.size)
        }
        return targets.size
    }

    /**
     * 推送前复核：账号被删 / 冻结 / 令牌版本提升后，已建立的长连接不得继续收推送。
     *
     * 判定的是**库里的当前状态**与**会话建立时写入的令牌版本**（[ATTR_TOKEN_VERSION]），
     * 而不是重放握手时的 JWT —— 后者在连接存活期间不会再送来。
     *
     * 唯一放行例外是**读库异常**：这些会话握手时已经验过令牌与账号状态，DB 抖动时
     * 若一律按"失效"踢线，会让全体家长端推送通道同时断开并引发重连风暴，
     * 可用性损失远大于"短暂继续向本账号已认证连接推送"。此处记 warn 维持现状，
     * 真正的吊销仍由显式 [revokeUser] 与下一次成功读库兜住。
     */
    private fun accountStillCurrent(userId: String, targets: List<WebSocketSession>): Boolean {
        val user = try {
            users.findById(userId).orElse(null)
        } catch (e: Exception) {
            log.warn("WS account recheck failed: user={} err={}", userId, e.message)
            return true
        }
        if (user == null) {
            log.info("WS account recheck rejected: user={} reason=not-found", userId)
            return false
        }
        if (!UserStatus.isUsable(user.status)) return false
        val currentVersion = user.tokenVersion
        return targets.none { s ->
            val established = runCatching { s.attributes[ATTR_TOKEN_VERSION] as? Number }.getOrNull()
            // 与 HTTP 侧 Interceptors/AdminAuthInterceptor 口径一致：不是"小于才失效"，
            // 而是"不等于即失效"——库被回滚/手工降版时，超前版本的令牌同样不可信。
            established != null && established.toLong() != currentVersion
        }
    }

    open fun pushToUser(userId: String?, payload: Map<String, Any?>) {
        if (userId == null) return
        val set = sessions[userId] ?: return
        val json = objectMapper.writeValueAsString(payload)

        // 先取快照再发送：发送是网络 IO，不能持锁（否则一个慢客户端会卡住同账号其他会话）
        val targets: List<WebSocketSession>
        synchronized(set) { targets = set.toList() }

        if (!accountStillCurrent(userId, targets)) {
            log.info("WS push suppressed: user={} account no longer current", userId)
            revokeUser(userId)
            return
        }

        val dead = mutableListOf<WebSocketSession>()
        var skipped = 0
        for (s in targets) {
            if (!s.isOpen) {
                dead += s
                continue
            }
            // 慢客户端隔离：该会话上一条推送仍在写 socket，本条直接跳过（见 [inFlight] 注释）。
            // 注意这里**不**把它记为 dead —— 会话可能只是这一瞬间慢，下一条还能送达。
            if (!inFlight.add(s)) {
                skipped++
                continue
            }
            try {
                s.sendMessage(TextMessage(json))
            } catch (e: Exception) {
                // 单条推送失败不影响其他会话；记下来一并清理，别留成僵尸。
                // 必须留痕：实时通道持续半开/序列化失败时，此前所有告警/解锁/截图推送
                // 静默丢弃、日志毫无线索，运维只能等家长投诉才发现通道已废。
                log.warn("WS push failed: user={} err={}", userId, e.message)
                dead += s
            } finally {
                inFlight.remove(s)
            }
        }
        if (skipped > 0) {
            log.debug("WS push skipped in-flight session: user={} skipped={}/{}", userId, skipped, targets.size)
        }
        if (dead.isNotEmpty()) {
            synchronized(set) { set.removeAll(dead.toSet()) }
            inFlight.removeAll(dead.toSet())
            // 与 register/unregister 同锁序：不在 set 锁内取桶锁，否则与
            // compute → synchronized(set) 构成反向锁序死锁；也不无条件摘桶，
            // 避免并发注册刚加回的新会话被顺手清掉。
            sessions.computeIfPresent(userId) { _, current ->
                if (current === set && synchronized(current) { current.isEmpty() }) null else current
            }
        }
    }

    private fun closeQuietly(userId: String, session: WebSocketSession, reason: String) {
        runCatching {
            if (session.isOpen) session.close(CloseStatus.POLICY_VIOLATION)
            log.info("closed ws session for user={} reason={} (cap={})", userId, reason, maxSessionsPerUser)
        }.onFailure { log.debug("close ws session failed for user={}: {}", userId, it.message) }
    }

    companion object {
        /** 单账号默认并发会话上限，可用 `padguard.ws.max-sessions-per-user` 覆盖 */
        const val DEFAULT_MAX_SESSIONS_PER_USER = 5

        /**
         * WS 会话建立时的令牌版本（`users.token_version`），由
         * [DeviceWebSocketHandler.afterConnectionEstablished] 写入 session.attributes。
         * 推送前拿它与库内当前版本比对：登出 / 重置密码后 +1，旧连接即被识别为失效。
         */
        const val ATTR_TOKEN_VERSION = "tokenVersion"
    }
}
