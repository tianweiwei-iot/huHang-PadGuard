package com.padguard.server.service

import com.padguard.server.config.TraceIds

import com.padguard.server.common.SERVER_ZONE
import com.padguard.server.repository.InstalledAppRepository
import com.padguard.server.repository.LocationTrackRepository
import com.padguard.server.repository.MediaTaskRepository
import com.padguard.server.repository.ScreenshotRepository
import com.padguard.server.repository.UploadedFileRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.File

/**
 * 磁盘写满防护 —— 保留期清理 + 磁盘水位告警。
 *
 * ## 不清理的后果（审计实测口径）
 * - `location_tracks`：288 行/天/设备只增不删 ≈ 2.1GB/年/设备；
 * - `uploads/`：孩子端录屏单个可达 90MB，截图每点一次「看屏」一张，均无生命周期；
 * - 叠加备份自身 7-28GB/14 天，50GiB 盘 6-18 个月写满 → **PG 拒写全站瘫痪**。
 *
 * ## 清理策略（与审计约定一致）
 * - 定位轨迹保留 [LOCATION_RETENTION_DAYS] 天（90 天足够家长回看，且属儿童行踪隐私，不宜长期留存）；
 * - 截图 / 录屏 / 录音保留 [MEDIA_RETENTION_DAYS] 天：记录与 uploads 文件**成对删除**，
 *   只删一边会留下 404 记录或永远躺着的孤儿文件；
 * - 只清 `uploaded_files.device_id` 非空的设备上传文件；家长上传的发布素材/APK
 *   被 PublishedMessage / AppInstaller 引用，不在清理范围。
 * - 上述四步都以「DB 记录」为起点，**记录不存在时磁盘文件永远扫不到**；
 *   另有一条 [sweepOrphanFilesDaily] 扫「盘上有、库里没有任何记录」的真孤儿（宽限 7 天）。
 *
 * ## 执行与安全性
 * - 每日 04:30 跑清理（错开 [UsageLogCleanup] 的 04:00，避免两个清理任务叠在同分钟）；
 * - 每 30 分钟一次磁盘水位检查，超 [DISK_ALERT_PCT] 打 error 日志（接入日志告警即可电话/短信通知运维）；
 * - 每个子任务独立 try/catch：清理是"锦上添花"，任何一步失败都不能阻断其余步骤，更不能把服务带崩。
 */
@Component
class RetentionCleanup(
    private val locationTrackRepository: LocationTrackRepository,
    private val screenshotRepository: ScreenshotRepository,
    private val mediaTaskRepository: MediaTaskRepository,
    private val uploadedFileRepository: UploadedFileRepository,
    private val fileStorageService: FileStorageService,
    /** 已装应用台账：只为取"仍被引用的图标地址"，见 [cleanupOrphanDeviceFiles]。 */
    private val installedAppRepository: InstalledAppRepository,
    /**
     * 多副本互斥锁（可选注入：纯 JVM 单测直构时为 null，直接执行，语义与单副本一致）。
     * Spring 装配时注入 [com.padguard.server.support.TaskExecutionLock]，
     * 多副本部署下同一任务同一时刻只有一个副本真正执行（租约制，见该类说明）。
     */
    private val taskLock: com.padguard.server.support.TaskExecutionLock? = null
) {
    private val log = LoggerFactory.getLogger(RetentionCleanup::class.java)

    /** 租约时长：清理任务日常秒级完成，10 分钟远超裕量（见 TaskExecutionLock 的租约说明）。 */
    private val leaseMs = 10 * 60_000L

    /** 副本标识：主机名 + 进程号，保证多副本 holder 互不相同、日志可归属。 */
    private val holder = runCatching {
        "${java.net.InetAddress.getLocalHost().hostName}-${ProcessHandle.current().pid()}"
    }.getOrDefault("unknown-${ProcessHandle.current().pid()}")

    /**
     * 带互斥的执行入口：
     * - 锁对象不存在（纯 JVM 单测 / 单副本未装配锁）→ 直接执行，保持单副本语义；
     * - 锁对象存在 → 完全交给 [TaskExecutionLock.runExclusively]：抢到执行、抢锁失败返回 null 跳过本轮。
     *
     * 严禁用 `taskLock?.runExclusively(...) ?: body()` 的写法：那样"有锁但抢锁失败"也会
     * 因 Elvis 兜底再次执行 body，多副本互斥彻底失效（两个副本同一轮都清理）。
     */
    private fun <T> exclusively(task: String, body: () -> T): T? =
        if (taskLock == null) body() else taskLock.runExclusively(task, holder, leaseMs, body)

    /** 定位轨迹保留天数。取 90 天：家长端轨迹回看按月计，90 天覆盖完整季度视角。 */
    // zone 显式钉死北京时间：不带 zone 的 cron 跟随 JVM 默认时区（容器未设 TZ 时为 UTC），
    // "凌晨 4:30 清理"会漂移到北京时间中午，IO 高峰执行清理且与 UsageLogCleanup 一并压到白天。
    @Scheduled(cron = "0 30 4 * * *", zone = SERVER_ZONE)
    fun cleanup() = TraceIds.withNewTrace("sched") { exclusively("retention-cleanup") { cleanupInternal() } }

    /** 原清理逻辑；公开入口只负责给每次调度一个独立 traceId（见 TraceIdFilter 说明）。 */
    private fun cleanupInternal() {
        cleanupLocationTracks()
        cleanupScreenshots()
        cleanupMediaTasks()
        cleanupOrphanDeviceFiles()
    }

    /** 每 30 分钟检查一次磁盘水位；超阈值打 error（日志告警接入点）。 */
    @Scheduled(cron = "0 */30 * * * *", zone = SERVER_ZONE)
    fun checkDiskUsage() = TraceIds.withNewTrace("sched") { exclusively("disk-usage-check") { checkDiskUsageInternal() } }

    /** 原水位检查逻辑（测试也直接调公开入口，行为不变）。 */
    private fun checkDiskUsageInternal() {
        runCatching {
            // 2026-11-18：原来检查 File("/")（容器根分区），而 uploads 在生产常挂独立卷。
            // 上传卷写满时根分区仍显示充裕 → 唯一预警失效，PG 拒写才发现已经晚了。
            val root = fileStorageService.storageRoot()
            val total = root.totalSpace
            val free = root.usableSpace
            if (total <= 0L) return
            val usedPct = (total - free) * 100 / total
            if (usedPct >= DISK_ALERT_PCT) {
                log.error(
                    "磁盘水位告警：已用 {}%（阈值 {}%）。location_tracks/uploads 保留期清理可能未生效，" +
                        "或数据增长超预期——PG 拒写将导致全站瘫痪，请立即处理！",
                    usedPct, DISK_ALERT_PCT
                )
            }
        }.onFailure { log.warn("磁盘水位检查失败（已忽略，不影响服务）: {}", it.message) }
    }

    private fun cleanupLocationTracks() {
        runCatching {
            // 阈值由服务端时钟算、删的是服务端落库时刻（见 LocationTrackRepository.deleteByTsBefore）。
            // 一旦改拿客户端 ts 比较，设备把时间调到未来即可让行踪永不清除（磁盘写满），
            // 调到过去则会误删近期轨迹（家长端回看出现空洞）。
            val threshold = System.currentTimeMillis() - LOCATION_RETENTION_DAYS * 86_400_000L
            val deleted = locationTrackRepository.deleteByTsBefore(threshold)
            if (deleted > 0) {
                log.info("location_tracks 保留期清理完成：删除 {} 条早于 {} 天的轨迹", deleted, LOCATION_RETENTION_DAYS)
            }
        }.onFailure {
            // 从 warn 升为 error：这条失败意味着表只增不删，最终会写满磁盘导致全站瘫痪，
            // 属于必须告警的运维事件，不能淹没在 warn 里。
            log.error("location_tracks 保留期清理失败（表将持续膨胀，请检查事务/权限）: {}", it.message, it)
        }
    }

    private fun cleanupScreenshots() {
        runCatching {
            val threshold = System.currentTimeMillis() - MEDIA_RETENTION_DAYS * 86_400_000L
            val stale = screenshotRepository.findReceivedAtBefore(threshold)
            stale.forEach { shot ->
                val fileId = fileStorageService.idFromUrl(shot.fileUrl)
                if (fileId != null) fileStorageService.deleteById(fileId)
            }
            if (stale.isNotEmpty()) {
                screenshotRepository.deleteAll(stale)
                log.info("截图保留期清理完成：删除 {} 条记录及对应文件", stale.size)
            }
        }.onFailure {
            log.warn("截图保留期清理失败（已忽略，不影响服务）: {}", it.message)
        }
    }

    private fun cleanupMediaTasks() {
        runCatching {
            val threshold = System.currentTimeMillis() - MEDIA_RETENTION_DAYS * 86_400_000L
            val stale = mediaTaskRepository.findStartedAtBefore(threshold)
            stale.forEach { task ->
                val fileId = fileStorageService.idFromUrl(task.url)
                if (fileId != null) fileStorageService.deleteById(fileId)
            }
            if (stale.isNotEmpty()) {
                mediaTaskRepository.deleteAll(stale)
                log.info("媒体任务保留期清理完成：删除 {} 条记录及对应文件", stale.size)
            }
        }.onFailure {
            log.warn("媒体任务保留期清理失败（已忽略，不影响服务）: {}", it.message)
        }
    }

    /**
     * 兜底：清"设备上传且超期"的 uploaded_files 记录与文件。
     * 前面两步按记录删文件，这里按文件删记录——双保险，
     * 覆盖"记录已丢但文件还在"的历史孤儿（早期版本没有生命周期管理时留下的）。
     */
    private fun cleanupOrphanDeviceFiles() {
        runCatching {
            val threshold = System.currentTimeMillis() - MEDIA_RETENTION_DAYS * 86_400_000L
            // 图标文件也带 device_id（L-19 修归属时改的），会被上面的查询一并扫到。
            // 但图标**没有生命周期终点**：应用一直装着，图标就要一直在。删掉之后
            // `installed_apps.icon_url` 指向 404 且永不重建（hash 未变 → iconUpdate 返回 null），
            // 家长端从此看不到任何应用图标。所以这里必须把它们从清理名单里摘掉。
            //
            // 读失败（DB 抖动/连接池耗尽）时**整轮跳过**，绝不拿空集当"没有图标"：
            // 那会在一次数据库抖动里把所有在用图标删光，与离线队列 readOrNull 的约定同理。
            val iconFileIds = runCatching {
                installedAppRepository.findAllIconUrls()
                    .mapNotNull { fileStorageService.idFromUrl(it) }
                    .toHashSet()
            }.getOrNull() ?: run {
                log.warn("设备文件保留期清理跳过：读取已装应用图标引用失败（本轮不删任何文件）")
                return
            }

            val stale = uploadedFileRepository.findDeviceFilesCreatedBefore(threshold)
                .filterNot { it.id in iconFileIds }
            stale.forEach { fileStorageService.deleteById(it.id) }
            if (stale.isNotEmpty()) {
                log.info("孤儿媒体文件保留期清理完成：删除 {} 个超期设备上传文件", stale.size)
            }
        }.onFailure {
            log.warn("孤儿媒体文件清理失败（已忽略，不影响服务）: {}", it.message)
        }
    }

    /**
     * 磁盘孤儿清扫：**盘上有、库里没有任何记录**的文件（详见 [FileStorageService.deleteOrphanFiles]）。
     *
     * 为什么必须单独一条：上面四个步骤全部以 **DB 记录**为起点，记录不存在时磁盘文件永远扫不到。
     * 实测口径——开发机上跑过多轮集成测试后 `uploads/` 里躺着 400+ 个从不进库的样本文件
     * （`shell.php.png`、`evil.png`、`big.png`…），而 `maintain.ps1` 会把整个 `uploads/`
     * 复制进发布包，等于把测试载荷随包分发给家庭用户。
     *
     * 单独排 05:10（前面几个任务都在 04:00 / 04:30），避免与保留期清理抢同一分钟的磁盘 IO。
     */
    @Scheduled(cron = "0 10 5 * * *", zone = SERVER_ZONE)
    fun sweepOrphanFilesDaily() = TraceIds.withNewTrace("sched") { exclusively("orphan-sweep") { sweepOrphanFilesDailyInternal() } }

    /** 原孤儿清扫逻辑；公开入口只负责给每次调度一个独立 traceId。 */
    private fun sweepOrphanFilesDailyInternal() {
        val r = sweepOrphanFiles(ORPHAN_GRACE_DAYS)
        when {
            // 读库失败时**一个都没删**（FileStorageService 侧已兜住），这里必须 error 告警：
            // 静默跳过会让孤儿文件无限堆积，而它恰恰是"磁盘写满"的隐形贡献项。
            r.dbReadFailed -> log.error("孤儿文件清扫跳过：读取 uploaded_files 失败（本轮未删除任何文件）")
            r.deleted > 0 -> log.info(
                "孤儿文件清扫完成：扫描 {} 个文件，删除 {} 个（{} MB）{}，样例={}",
                r.scanned, r.deleted, r.bytesFreed / 1024 / 1024,
                if (r.truncated) "（已达单次上限，剩余留待下次）" else "", r.samples
            )
        }
    }

    /**
     * 手动/测试入口。`dryRun=true` 只统计不删。
     *
     * 宽限期强制至少 1 天（见 [FileStorageService.deleteOrphanFiles]）：写盘与落库之间的
     * 竞态窗口是唯一能造成真实数据丢失的路径，从入口堵死。
     */
    fun sweepOrphanFiles(graceDays: Long = ORPHAN_GRACE_DAYS, dryRun: Boolean = false):
        FileStorageService.OrphanSweepResult = fileStorageService.deleteOrphanFiles(graceDays, dryRun = dryRun)

    companion object {
        /** 定位轨迹保留天数（儿童行踪隐私 + 2.1GB/年/设备的增长速率，取 90 天） */
        const val LOCATION_RETENTION_DAYS: Long = 90

        /** 截图/录屏/录音保留天数（90MB/个的录屏是 uploads 增长主因，取 90 天） */
        const val MEDIA_RETENTION_DAYS: Long = 90

        /** 磁盘水位告警阈值（百分比）。留 20% 余量：PG 拒写前运维还有处置窗口。 */
        const val DISK_ALERT_PCT: Long = 80

        /**
         * 磁盘孤儿的宽限期（天）。
         *
         * 取 7 天而不是"立刻可删"：写盘成功、`save` 失败的文件在下一轮保存/重试时可能被复用，
         * 数据库从旧备份恢复时也会出现"文件比记录新"的假孤儿。7 天足够覆盖这些场景，
         * 而真正的垃圾（测试样本、历史残留）多躺 7 天无害。
         */
        const val ORPHAN_GRACE_DAYS: Long = 7
    }
}
