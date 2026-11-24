package com.padguard.server.controller

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import com.padguard.server.common.SERVER_ZONE_ID
import java.time.format.DateTimeFormatter
import javax.sql.DataSource

/**
 * 存活探针。
 *
 * 路径刻意放在 `/health` 而不是 `/v1/health`：
 * `/v1/` 与 `/api/v1/` 两个前缀都被鉴权拦截器覆盖，无 token 访问会返回 401，
 * 而 401 无法区分"服务活着但拒绝未授权访问"和"服务已死"。
 * 守护脚本需要一个**无条件可探**的端点，所以必须落在拦截范围之外。
 *
 * 另外本项目没有引入 spring-boot-starter-actuator，`/actuator/health` 会走到
 * NoResourceFoundException 再被 GlobalExceptionHandler 兜成 500 ——
 * 历史上正因如此被反复误判成"服务端挂了"。这个端点是为了消除该类误判。
 *
 * 检查项覆盖两个"进程活着但功能已废"的真实故障形态：
 * 1. 数据库连通性：类加载损坏 / 连接池耗尽 / 库文件锁 —— 必须真下一次查询才能发现；
 * 2. 文件存储可用性：磁盘满 / 只读挂载 / uploads 被误删或换成文件 ——
 *    否则截图/录屏/素材上传会静默失败，运维却看到 /health=UP 而毫无察觉。
 */
@RestController
class HealthController(
    private val dataSource: DataSource,
    @Value("\${padguard.storage.dir:./uploads}") private val storageDir: String
) {

    private val log = LoggerFactory.getLogger(HealthController::class.java)

    /** 进程启动时刻，用于计算 uptime；守护脚本据此判断"是不是刚刚被重启过"。 */
    private val startedAt = System.currentTimeMillis()

    @Volatile
    private var ready = false

    /** 存储探针结果缓存（守护脚本探测频繁，避免每次都落盘）。0 表示从未探测。 */
    @Volatile
    private var storageProbeAt = 0L

    @Volatile
    private var storageOkCached = false

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        ready = true
        log.info("health endpoint ready at /health")
    }

    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, Any?>> {
        val dbOk = probeDatabase()
        val storageOk = probeStorage()
        // 启动完成前也返回 200：守护脚本若在启动期就判死并重启，会与正在启动的实例抢
        // H2 文件锁，把一次正常启动打断成反复重启。启动期用 ready=false 表达，
        // 由守护脚本按"连续失败 N 次"来兜底，而不是一次探不到就立刻重启。
        val healthy = dbOk && storageOk
        val status = if (healthy) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE
        return ResponseEntity.status(status).body(
            mapOf(
                "status" to if (healthy) "UP" else "DOWN",
                "ready" to ready,
                "db" to if (dbOk) "UP" else "DOWN",
                "storage" to if (storageOk) "UP" else "DOWN",
                "uptimeMs" to (System.currentTimeMillis() - startedAt),
                "startedAt" to formatTime(startedAt)
            )
        )
    }

    /**
     * 真实下一次查询，而不是复用连接池的空闲连接：
     * H2 文件库被别的实例独占时，连接池里可能还有旧连接可用，
     * 但新业务请求会全部失败 —— 只有新开连接才能暴露这类问题。
     */
    private fun probeDatabase(): Boolean = runCatching {
        dataSource.connection.use { conn: Connection ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT 1").use { rs -> rs.next() }
            }
        }
    }.onFailure {
        // 只 warn 不 error：守护脚本探测频繁，堆成 error 会淹没真正的故障日志
        log.warn("health check: database probe failed: {}", it.message)
    }.getOrDefault(false)

    /**
     * 文件存储可用性：写一个临时探针文件再删掉，真正验证"可写"而非只看权限位。
     *
     * 目录不存在时按 FileStorageService 的同款语义 mkdirs（它初始化时就会自动建目录，
     * 目录缺失本身不算故障）；只有"建不出来 / 路径被换成普通文件 / 写不进去"才判 DOWN。
     * 磁盘满时 createTempFile 或 write 会抛 IOException，如实反映为存储不可用。
     * 探针文件用 finally 删除，不给媒体目录留垃圾。
     */
    private fun probeStorage(): Boolean {
        val now = System.currentTimeMillis()
        if (now - storageProbeAt < STORAGE_PROBE_TTL_MS) return storageOkCached
        val ok = runCatching {
            val dir = File(storageDir)
            if (!dir.exists() && !dir.mkdirs()) throw IOException("cannot create storage dir: $storageDir")
            if (!dir.isDirectory) throw IOException("storage path is not a directory: $storageDir")
            val probe = Files.createTempFile(dir.toPath(), ".health-", ".probe")
            try {
                Files.write(probe, byteArrayOf(0x48)) // 真写一个字节，暴露只读/满盘
            } finally {
                Files.deleteIfExists(probe)
            }
            true
        }.onFailure {
            log.warn("health check: storage probe failed: {}", it.message)
        }.getOrDefault(false)
        storageProbeAt = now
        storageOkCached = ok
        return ok
    }

    private fun formatTime(millis: Long): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .format(Instant.ofEpochMilli(millis).atZone(SERVER_ZONE_ID))

    companion object {
        /** 存储探针结果缓存时长：守护脚本通常每几秒探一次，30s 内不重复落盘。 */
        private const val STORAGE_PROBE_TTL_MS = 30_000L
    }
}
