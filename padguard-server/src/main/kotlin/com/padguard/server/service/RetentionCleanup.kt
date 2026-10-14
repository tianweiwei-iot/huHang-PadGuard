package com.padguard.server.service

import com.padguard.server.common.SERVER_ZONE
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
    private val fileStorageService: FileStorageService
) {
    private val log = LoggerFactory.getLogger(RetentionCleanup::class.java)

    /** 定位轨迹保留天数。取 90 天：家长端轨迹回看按月计，90 天覆盖完整季度视角。 */
    // zone 显式钉死北京时间：不带 zone 的 cron 跟随 JVM 默认时区（容器未设 TZ 时为 UTC），
    // "凌晨 4:30 清理"会漂移到北京时间中午，IO 高峰执行清理且与 UsageLogCleanup 一并压到白天。
    @Scheduled(cron = "0 30 4 * * *", zone = SERVER_ZONE)
    fun cleanup() {
        cleanupLocationTracks()
        cleanupScreenshots()
        cleanupMediaTasks()
        cleanupOrphanDeviceFiles()
    }

    /** 每 30 分钟检查一次磁盘水位；超阈值打 error（日志告警接入点）。 */
    @Scheduled(cron = "0 */30 * * * *", zone = SERVER_ZONE)
    fun checkDiskUsage() {
        runCatching {
            val root = File("/")
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
            val threshold = System.currentTimeMillis() - LOCATION_RETENTION_DAYS * 86_400_000L
            val deleted = locationTrackRepository.deleteByTsBefore(threshold)
            if (deleted > 0) {
                log.info("location_tracks 保留期清理完成：删除 {} 条早于 {} 天的轨迹", deleted, LOCATION_RETENTION_DAYS)
            }
        }.onFailure {
            log.warn("location_tracks 保留期清理失败（已忽略，不影响服务）: {}", it.message)
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
            val stale = uploadedFileRepository.findDeviceFilesCreatedBefore(threshold)
            stale.forEach { fileStorageService.deleteById(it.id) }
            if (stale.isNotEmpty()) {
                log.info("孤儿媒体文件保留期清理完成：删除 {} 个超期设备上传文件", stale.size)
            }
        }.onFailure {
            log.warn("孤儿媒体文件清理失败（已忽略，不影响服务）: {}", it.message)
        }
    }

    companion object {
        /** 定位轨迹保留天数（儿童行踪隐私 + 2.1GB/年/设备的增长速率，取 90 天） */
        const val LOCATION_RETENTION_DAYS: Long = 90

        /** 截图/录屏/录音保留天数（90MB/个的录屏是 uploads 增长主因，取 90 天） */
        const val MEDIA_RETENTION_DAYS: Long = 90

        /** 磁盘水位告警阈值（百分比）。留 20% 余量：PG 拒写前运维还有处置窗口。 */
        const val DISK_ALERT_PCT: Long = 80
    }
}
