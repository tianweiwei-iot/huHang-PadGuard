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

    fun publish(deviceId: String, frame: ByteArray) {
        lastFrames[deviceId] = frame
        lastFrameAt.computeIfAbsent(deviceId) { AtomicLong() }.set(System.currentTimeMillis())
        subscribers[deviceId]?.forEach { sub ->
            runCatching { sub.offer(frame) }
        }
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
        lastFrames[deviceId]?.let { sub.offer(it) }
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
        val log = LoggerFactory.getLogger(ScreenStreamHub::class.java)
    }
}
