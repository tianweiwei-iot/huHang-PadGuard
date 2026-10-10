package com.padguard.server.stream

import com.padguard.server.common.ApiResponse
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.repository.DeviceRepository
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * 实时看屏的两个端点。
 *
 * ## 帧格式：4 字节大端长度 + JPEG 数据
 * 不用 multipart/x-mixed-replace 是刻意的：分块解析要处理 boundary、CRLF、Content-Length
 * 三种细节，两端各写一套解析器，任一端写错就是"流卡在某一帧"。长度前缀只有两行代码，
 * 且天然支持"长度为 0 的心跳帧"。
 *
 * ## 上传为什么挂在 /api/v1/device/ 前缀下
 * 走孩子端现有的设备令牌鉴权（X-Device-Id + Bearer deviceToken），
 * 拦截器已注入 deviceId，无需再设计一套鉴权。
 */
@RestController
@RequestMapping("/api/v1/device/stream")
class ChildStreamController(
    private val hub: ScreenStreamHub
) {

    /**
     * 孩子端推流：长连接，逐帧写入。
     *
     * 连接断开（进程退出 / 网络切换 / 家长停止看屏）即视为本次推流结束，
     * 不做"半死不活"的重连状态——下一次看屏重新建连，状态最干净。
     */
    @PostMapping("/up", consumes = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    fun upload(
        @RequestAttribute("deviceId") deviceId: String,
        request: HttpServletRequest
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        var frames = 0L
        var bytes = 0L
        val startedAt = System.currentTimeMillis()
        // 标记该设备正在推流：供并发准入（MonitorService）与带宽聚合日志使用
        hub.registerPublisher(deviceId)
        try {
            request.inputStream.use { input ->
                while (true) {
                    val frame = readFrame(input) ?: break
                    if (frame.isEmpty()) continue // 心跳帧
                    hub.publish(deviceId, frame)
                    frames++
                    bytes += frame.size
                }
            }
        } catch (e: Throwable) {
            // 客户端断开、进程被杀都会走到这里，是正常结束路径，不是故障
            log.info("screen stream upload ended: device=$deviceId frames=$frames reason=${e.javaClass.simpleName}")
        } finally {
            hub.unregisterPublisher(deviceId)
        }
        val cost = System.currentTimeMillis() - startedAt
        log.info("screen stream upload finished: device=$deviceId frames=$frames bytes=${bytes / 1024}KB ${cost}ms")
        return ResponseEntity.ok(
            ApiResponse.ok(mapOf("frames" to frames, "bytes" to bytes, "durationMs" to cost))
        )
    }

    private fun readFrame(input: InputStream): ByteArray? {
        val header = ByteArray(4)
        if (!readFully(input, header)) return null
        val len = ByteBuffer.wrap(header).int
        if (len <= 0) return ByteArray(0)
        if (len > MAX_FRAME_BYTES) throw EOFException("帧过大: $len")
        val frame = ByteArray(len)
        if (!readFully(input, frame)) return null
        return frame
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private companion object {
        const val MAX_FRAME_BYTES = 8 * 1024 * 1024
        val log = LoggerFactory.getLogger(ChildStreamController::class.java)
    }
}

/**
 * 家长端拉流。
 *
 * 挂在 /v1/ 前缀下走家长 JWT 鉴权，并校验设备归属——否则任何登录用户都能看别人的孩子屏幕。
 */
@RestController
@RequestMapping("/v1/stream")
class ParentStreamController(
    private val hub: ScreenStreamHub,
    private val deviceRepository: DeviceRepository
) {

    /**
     * 实时画面流：持续输出 `4 字节长度 + JPEG`。
     * 空闲时写长度为 0 的心跳帧，防止反向代理按空闲超时掐断连接。
     *
     * **不要写 `produces = application/octet-stream`**：
     * 一旦声明，Spring 就会拿它与请求头 Accept 做内容协商。家长端 OkHttp 链路带的是
     * `Accept: application/json`（统一拦截器加的，用于其余 JSON 接口），
     * 协商失败直接抛 HttpMediaTypeNotAcceptableException，
     * 表现为 `500 {"code":5001,"message":"No acceptable representation"}` ——
     * 家长端永远停在"连接中"，而用 curl/python 不带 Accept 头去测却一直是 200，
     * 这个坑因此在联调中长期被漏掉。
     * 返回 [StreamingResponseBody] 由专门的 Handler 处理、不走 HttpMessageConverter，
     * 不声明 produces 就不会有内容协商问题；响应类型在下面手动指定。
     */
    @GetMapping("/{deviceId}/live")
    fun live(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String
    ): ResponseEntity<StreamingResponseBody> {
        val device = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (device.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        }

        val sub = hub.subscribe(deviceId) ?: throw BizException(
            ParentErr.LIVE_VIEWERS_FULL,
            "该设备的观看人数已达上限，请稍后再试",
            Audience.PARENT
        )
        val body = StreamingResponseBody { out: OutputStream ->
            try {
                var idle = 0L
                while (!sub.closed) {
                    val frame = sub.poll(POLL_TIMEOUT_MS)
                    if (frame != null && frame.isNotEmpty()) {
                        writeFrame(out, frame, deviceId)
                        idle = 0L
                    } else {
                        // 心跳：既保活，也让家长端能区分"没帧"和"连接断了"
                        writeFrame(out, ByteArray(0), deviceId)
                        // 断流判定要看**设备是否还在推**，而不是本订阅者队列空了多久。
                        // 队列空只是说明这一瞬间没新帧（订阅者刚连上、帧被丢旧、瞬时抖动），
                        // 用它累计计时会在设备明明正常推流时把连接掐掉 ——
                        // 表现为家长端每 30 秒闪一次"连接中"，观感就是"画面一直连不上"。
                        val last = hub.lastFrameAt(deviceId)
                        val deviceAlive = last != 0L && System.currentTimeMillis() - last <= MAX_IDLE_MS
                        if (deviceAlive) {
                            idle = 0L   // 设备还在推，只是这一轮没轮到，继续等
                        } else {
                            // 设备从未推流 / 已停推，才按空闲累计收尾，避免连接永久悬挂
                            idle += POLL_TIMEOUT_MS
                            if (idle >= MAX_IDLE_MS) break
                        }
                    }
                    out.flush()
                }
            } catch (e: Throwable) {
                log.info("live stream terminated: device=$deviceId reason=${e.javaClass.simpleName}")
            } finally {
                hub.unsubscribe(deviceId, sub)
            }
        }
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header("Cache-Control", "no-store, no-cache, must-revalidate")
            .header("X-Accel-Buffering", "no")
            .body(body)
    }

    /** 流的健康状态：家长端用它判断"现在到底有没有实时画面" */
    @GetMapping("/{deviceId}/status")
    fun status(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String
    ): Any {
        val device = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (device.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        }
        val last = hub.lastFrameAt(deviceId)
        val ageMs = if (last == 0L) -1 else System.currentTimeMillis() - last
        return ApiResponse.ok(
            StreamStatusDto(
                deviceId = deviceId,
                lastFrameAt = last,
                frameAgeMs = ageMs,
                live = ageMs in 0..LIVE_THRESHOLD_MS,
                upstreamBps = hub.upstreamBps(deviceId),
                downstreamBps = hub.downstreamBps(deviceId),
                totalBytes = hub.totalUpBytes(deviceId) + hub.totalDownBytes(deviceId)
            )
        )
    }

    /**
     * 实时看屏带宽总览（运维/排查用）。
     *
     * 返回当前正在推流的设备数、全局上行/下行速率、各设备明细。
     * 直接回答"服务端实时看屏到底占了多少带宽、并发高时会不会被打满"。
     */
    @GetMapping("/metrics")
    fun metrics(): Any {
        val devices = hub.publishersSnapshot()
        var upSum = 0L
        var downSum = 0L
        val per = devices.map { d ->
            val up = hub.upstreamBps(d)
            val down = hub.downstreamBps(d)
            upSum += up
            downSum += down
            mapOf(
                "deviceId" to d,
                "upstreamBps" to up,
                "downstreamBps" to down,
                "totalBytes" to (hub.totalUpBytes(d) + hub.totalDownBytes(d))
            )
        }
        return ApiResponse.ok(
            mapOf(
                "activeDevices" to devices.size,
                "totalUpstreamBps" to upSum,
                "totalDownstreamBps" to downSum,
                "devices" to per
            )
        )
    }

    private fun writeFrame(out: OutputStream, frame: ByteArray, deviceId: String) {
        val header = ByteBuffer.allocate(4).putInt(frame.size).array()
        out.write(header)
        if (frame.isNotEmpty()) {
            out.write(frame)
            hub.addDownlink(deviceId, frame.size.toLong())
        }
    }

        private companion object {
        /**
         * 拉流轮询超时：从 1000ms 降到 200ms。
         *
         * 旧值 1s 意味着家长端在队列空时最多阻塞 1s 才取到下一帧，
         * 端到端延迟被直接抬高近 1s（正是线上"1~2 秒延时"的主要来源之一）。
         * 200ms 既把这部分延迟压到肉眼无感，又不至于让拉流线程空转过频。
         */
        const val POLL_TIMEOUT_MS = 200L
        /** 同一个设备 30 秒没有任何帧就收流：孩子端已经停了，没必要继续吊着连接 */
        const val MAX_IDLE_MS = 30_000L
        const val LIVE_THRESHOLD_MS = 5_000L
        val log = LoggerFactory.getLogger(ParentStreamController::class.java)
    }
}

/** 实时看屏状态（含带宽遥测）：家长端拉流页据此显示画面是否在线与实时带宽占用 */
data class StreamStatusDto(
    val deviceId: String,
    val lastFrameAt: Long,
    val frameAgeMs: Long,
    val live: Boolean,
    val upstreamBps: Long,
    val downstreamBps: Long,
    val totalBytes: Long
)
