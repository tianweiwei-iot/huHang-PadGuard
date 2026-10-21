package com.padguard.server.stream

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 实时看屏的帧中继中心。
 *
 * ## 为什么需要它
 * 「家长看到孩子平板的实时画面」在 Android 上没有别的路可走：孩子端把屏幕逐帧压成 JPEG
 * 推上来，服务端原样转发给正在观看的家长端。它是**流**而不是"隔几秒刷新一张图"——
 * 孩子屏幕上滑动一下，家长端就在同一秒看到，而不是等到下一次轮询。
 *
 * ## 为什么用「有界队列 + 丢最旧帧」而不是无界缓冲
 * 网络抖动或家长端卡顿会让帧积压。无界缓冲的后果是：家长端越播越慢，
 * 看到的永远是几十秒前的画面（"慢动作回放"），实时性彻底失效，而且内存无上限。
 * 有界队列 + 丢最旧帧保证家长端看到的永远是**最新的**画面，最多掉帧。
 *
 * ## 为什么保留 lastFrame
 * 家长端刚点开看屏、孩子端的下一帧还没到时，如果干等会先黑屏一两秒。
 * 缓存最近一帧让新订阅者立即出画面。
 */
@Component
class ScreenStreamHub {

    class Subscriber {
        /** 3 帧缓冲足够吸收抖动；再多就是拿延迟换流畅，与"实时"目标相悖 */
        private val queue = ArrayBlockingQueue<ByteArray>(MAX_QUEUE)
        @Volatile var closed = false

        fun offer(frame: ByteArray) {
            if (closed) return
            if (!queue.offer(frame)) {
                queue.poll()
                queue.offer(frame)
            }
        }

        /** 阻塞取帧；[timeoutMs] 内没有新帧返回 null（调用方据此判断连接是否还活着） */
        fun poll(timeoutMs: Long): ByteArray? {
            if (closed) return null
            return queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }

        fun close() {
            closed = true
            queue.clear()
        }
    }

    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<Subscriber>>()
    private val lastFrames = ConcurrentHashMap<String, ByteArray>()
    private val lastFrameAt = ConcurrentHashMap<String, AtomicLong>()

    // ==================== 带宽遥测 ====================
    // 实时看屏是服务端中转架构，每一路都是"一份上行 + 一份下行"带宽。
    // 不把带宽亮出来，就永远不知道"卡/贵"究竟卡在哪、花了多少 —— 这也是早期
    // 线上"还不知道带宽占用情况"的根因。这里按设备累计字节，并维护一个滚动 1s 窗口算速率。
    private val upWindowBytes = ConcurrentHashMap<String, AtomicLong>()
    private val upWindowStart = ConcurrentHashMap<String, AtomicLong>()
    private val downWindowBytes = ConcurrentHashMap<String, AtomicLong>()
    private val downWindowStart = ConcurrentHashMap<String, AtomicLong>()
    private val upTotalBytes = ConcurrentHashMap<String, AtomicLong>()
    private val downTotalBytes = ConcurrentHashMap<String, AtomicLong>()

    // 正在推流的设备集合：服务端出口带宽的主要来源，也是并发准入（见 MonitorService）的依据
    private val publishers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun publish(deviceId: String, frame: ByteArray) {
        lastFrames[deviceId] = frame
        lastFrameAt.computeIfAbsent(deviceId) { AtomicLong() }.set(System.currentTimeMillis())
        subscribers[deviceId]?.forEach { sub ->
            runCatching { sub.offer(frame) }
        }
        addUplink(deviceId, frame.size.toLong())
    }

    /** 上行字节入账（孩子端推来的每一帧） */
    fun addUplink(deviceId: String, n: Long) {
        upTotalBytes.computeIfAbsent(deviceId) { AtomicLong() }.addAndGet(n)
        upWindowBytes.computeIfAbsent(deviceId) { AtomicLong() }.addAndGet(n)
        upWindowStart.computeIfAbsent(deviceId) { AtomicLong(System.currentTimeMillis()) }
    }

    /** 下行字节入账（家长端拉走的每一帧）。下行与上行 1:1，这里单独计是为了在端点上分别呈现 */
    fun addDownlink(deviceId: String, n: Long) {
        downTotalBytes.computeIfAbsent(deviceId) { AtomicLong() }.addAndGet(n)
        downWindowBytes.computeIfAbsent(deviceId) { AtomicLong() }.addAndGet(n)
        downWindowStart.computeIfAbsent(deviceId) { AtomicLong(System.currentTimeMillis()) }
    }

    /** 滚动 1s 窗口的速率（字节/秒）。窗口不足 500ms 时用累计估算，达到窗口则结算并重置 */
    private fun rollingBps(bytesMap: ConcurrentHashMap<String, AtomicLong>, startMap: ConcurrentHashMap<String, AtomicLong>, deviceId: String): Long {
        val w = bytesMap[deviceId] ?: return 0L
        val s = startMap[deviceId] ?: return 0L
        val now = System.currentTimeMillis()
        val elapsed = now - s.get()
        if (elapsed < 500) return if (elapsed <= 0) 0L else w.get() * 1000 / elapsed
        val bytes = w.getAndSet(0)
        s.set(now)
        return bytes * 1000 / elapsed
    }

    fun upstreamBps(deviceId: String): Long = rollingBps(upWindowBytes, upWindowStart, deviceId)
    fun downstreamBps(deviceId: String): Long = rollingBps(downWindowBytes, downWindowStart, deviceId)
    fun totalUpBytes(deviceId: String): Long = upTotalBytes[deviceId]?.get() ?: 0L
    fun totalDownBytes(deviceId: String): Long = downTotalBytes[deviceId]?.get() ?: 0L

    /** 注册/注销推流设备（在 ChildStreamController 的推流长连接起止处调用） */
    fun registerPublisher(deviceId: String) = publishers.add(deviceId)
    fun unregisterPublisher(deviceId: String) = publishers.remove(deviceId)

    /**
     * 该设备当前是否存在活跃上行连接。注意：进程被杀但 TCP 未即时断开时会残留"僵尸连接"，
     * 调用方必须叠加 [lastFrameAt] 的新鲜度判定，才能真正区分"在推流"与"连接残骸"。
     */
    fun isPublishing(deviceId: String): Boolean = publishers.contains(deviceId)
    fun publisherCount(): Int = publishers.size
    /** 推流中设备的快照（用于 /metrics 聚合，避免直接暴露并发集合） */
    fun publishersSnapshot(): List<String> = publishers.toList()

    /** 周期性打印各路实时看屏带宽，直接回答"带宽到底占了多少"（见 [padguard.stream] 运维） */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10_000L)
    fun logAggregateBandwidth() {
        if (publishers.isEmpty()) return
        val sb = StringBuilder("stream bandwidth: devices=${publishers.size}")
        var upSum = 0L
        var downSum = 0L
        publishers.forEach { d ->
            val up = upstreamBps(d)
            val down = downstreamBps(d)
            upSum += up
            downSum += down
            sb.append(" | $d up=${up / 1024}KB/s down=${down / 1024}KB/s")
        }
        sb.append(" || total up=${upSum / 1024}KB/s down=${downSum / 1024}KB/s")
        log.info(sb.toString())
    }

    /**
     * 订阅某个设备的实时画面。
     *
     * @return null 表示该设备的并发观看数已达 [MAX_SUBSCRIBERS]（异常并发）。
     * 调用方**必须**把它转成明确的错误响应：返回一个立刻结束的流会让家长端
     * 以为是"孩子端停推"，从而按既有逻辑自动重连，形成无限重连循环。
     */
    fun subscribe(deviceId: String): Subscriber? {
        val list = subscribers.computeIfAbsent(deviceId) { CopyOnWriteArrayList() }
        // 每路观看都是一份完整的上行+下行带宽（这是"服务端中转"架构最大的成本项），
        // 而同一台平板被多人同时看屏在正常业务里不该发生（一个家庭一个家长账号）。
        // 不限制的话，一旦出现异常（重连未清理、脚本刷接口），带宽会成倍放大且无人察觉。
        if (list.size >= MAX_SUBSCRIBERS) {
            log.warn("stream subscriber rejected (device at capacity): device=$deviceId current=${list.size}")
            return null
        }
        val sub = Subscriber()
        list.add(sub)
        // 仅当设备近期仍在推流（最后一帧未过期）才把缓存帧立即推给新订阅者。
        // 否则（设备已离线 / 卸载重装、长期无新帧）缓存的是失效前的旧画面，
        // 若立刻回放，家长端会停在一张「卸载前的旧屏」上、并误以为在直播——
        // 而孩子端因重装后无 MediaProjection 令牌推不出新帧，画面永远不更新。
        // 离线设备直接走「无信号」更诚实。阈值与 ParentStreamController.MAX_IDLE_MS(30s) 对齐：
        // 只有 30s 内还推过帧的设备才视为「仍在线」，可立即出画。
        val lastAt = lastFrameAt[deviceId]?.get() ?: 0L
        if (lastAt != 0L && System.currentTimeMillis() - lastAt <= STALE_FRAME_MS) {
            lastFrames[deviceId]?.let { sub.offer(it) }
        }
        log.info("stream subscriber added: device=$deviceId total=${list.size}")
        return sub
    }

    fun unsubscribe(deviceId: String, sub: Subscriber) {
        sub.close()
        subscribers[deviceId]?.remove(sub)
        log.info("stream subscriber removed: device=$deviceId total=${subscribers[deviceId]?.size ?: 0}")
    }

    /** 最近一帧的时间戳：家长端据此判断"流是否还活着"，避免对着一张死图以为在看实时画面 */
    fun lastFrameAt(deviceId: String): Long = lastFrameAt[deviceId]?.get() ?: 0L

    private companion object {
        const val MAX_QUEUE = 3
        /** 单设备并发观看上限：正常业务远小于此，超过即视为异常并发 */
        const val MAX_SUBSCRIBERS = 3
        /**
         * 缓存帧回放的新鲜度窗口，与 ParentStreamController.MAX_IDLE_MS(30s) 保持一致：
         * 仅设备 30s 内还推过帧，新订阅者才立即看到上一帧；超过则视为设备已离线，
         * 缓存帧是失效前的旧画面，不再回放（见 [subscribe]）。
         */
        const val STALE_FRAME_MS = 30_000L
        val log = LoggerFactory.getLogger(ScreenStreamHub::class.java)
    }
}
