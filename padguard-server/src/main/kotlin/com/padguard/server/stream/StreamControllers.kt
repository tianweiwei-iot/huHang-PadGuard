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

        val sub = hub.subscribe(deviceId)
        val body = StreamingResponseBody { out: OutputStream ->
            try {
                var idle = 0L
                while (!sub.closed) {
                    val frame = sub.poll(POLL_TIMEOUT_MS)
                    if (frame != null && frame.isNotEmpty()) {
                        writeFrame(out, frame)
                        idle = 0L
                    } else {
                        // 心跳：既保活，也让家长端能区分"没帧"和"连接断了"
                        writeFrame(out, ByteArray(0))
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
            mapOf(
                "deviceId" to deviceId,
                "lastFrameAt" to last,
                "frameAgeMs" to ageMs,
                "live" to (ageMs in 0..LIVE_THRESHOLD_MS)
            )
        )
    }

    private fun writeFrame(out: OutputStream, frame: ByteArray) {
        val header = ByteBuffer.allocate(4).putInt(frame.size).array()
        out.write(header)
        if (frame.isNotEmpty()) out.write(frame)
    }

    private companion object {
        const val POLL_TIMEOUT_MS = 1_000L
        /** 同一个设备 30 秒没有任何帧就收流：孩子端已经停了，没必要继续吊着连接 */
        const val MAX_IDLE_MS = 30_000L
        const val LIVE_THRESHOLD_MS = 5_000L
        val log = LoggerFactory.getLogger(ParentStreamController::class.java)
    }
}
