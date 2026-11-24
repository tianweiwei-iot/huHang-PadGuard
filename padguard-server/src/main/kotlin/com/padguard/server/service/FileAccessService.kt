package com.padguard.server.service

import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UploadedFileRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 文件访问归属判定（2026-11-18 阶段 2 架构收敛：自 FilesController 服务层下沉）。
 *
 * Controller 只负责 HTTP 语义（签名闸门、响应构造、Content-Type 安全策略），
 * "谁能读哪个文件"的判定规则全部收敛到这里——规则是纯领域逻辑，
 * 与 HTTP 无关，且被多个入口共享（带凭证访问、匿名强制签名闸门的适用范围判断）。
 *
 * ## 三条放行规则（缺一都会造成回归，历史注释自 FilesController 迁移）
 * 1. 文件没有归属记录（改造前上传的历史数据）→ 放行，否则老截图集体打不开；
 * 2. 请求没带任何可识别凭证（孩子端图片库加载 URL）→ 放行，否则素材图全 404；
 * 3. 归属匹配 → 放行。除此之外一律拒绝：家长不能下载别人家设备的截图，
 *    别的设备也不能拿走本机的录音录屏。
 *
 * ## 为什么必须额外加"家长拥有该设备"这条判定
 * 孩子端上报截图/录屏/录音时只认设备令牌，拿不到家长 userId，
 * 因此这些文件的 owner_user_id 恒为 null、只有 device_id 有值；
 * 家长下载时识别出来的是 userId。这条判定把"设备归属该家长"补上，
 * 既修好"家长下载自家设备截图被 403"，也没有放宽跨用户越权。
 */
@Service
class FileAccessService(
    private val uploadedFileRepository: UploadedFileRepository,
    private val deviceRepository: DeviceRepository
) {

    private val log = LoggerFactory.getLogger(FileAccessService::class.java)

    /**
     * 归属判定结论：
     * - [Verdict.ALLOW]：明确放行；
     * - [Verdict.DENY]：有凭证但归属对不上 → Controller 返回 403；
     * - [Verdict.SKIP]：无凭证或无归属记录 → Controller 维持历史语义（不拦），
     *   即能力 URL 语义（见 FileAccessInterceptor 注释）。
     */
    enum class Verdict { ALLOW, DENY, SKIP }

    fun resolveOwnership(viewerUserId: String?, viewerDeviceId: String?, fileId: String): Verdict {
        if (viewerUserId == null && viewerDeviceId == null) return Verdict.SKIP

        val owner = uploadedFileRepository.findById(fileId).orElse(null) ?: return Verdict.SKIP
        val ownerUserId = owner.ownerUserId
        val ownerDeviceId = owner.deviceId

        val allow = when {
            // 历史文件（早期版本上传，无归属记录）→ 放行，否则老素材集体打不开
            ownerUserId.isNullOrBlank() && ownerDeviceId.isNullOrBlank() -> true
            // 家长主动上传的素材（只有 ownerUserId、无 ownerDeviceId）：通过「信息发布」主动分享给孩子，
            // 必须放行孩子端下载，否则家长发的图片/视频会 403。
            // 缺陷 N 的收窄：不是「任何持有效家长 JWT 的人都放行」——家长 B 拿到 A 的 fileId
            // 不能读 A 发布的素材；收窄为「上传者本人」或「上传者名下孩子设备凭证」。
            // 只写 viewerDeviceId != null 等于「任何家长的任何设备可读任何人的素材」：
            // 开启 media.require-signature 后该分支还会绕过签名闸门，构成跨租户 IDOR。
            // 匿名能力 URL 由调用方的无凭证分支保持原语义。
            ownerUserId != null && ownerDeviceId.isNullOrBlank() ->
                viewerUserId == ownerUserId || (viewerDeviceId != null && ownsDevice(ownerUserId, viewerDeviceId))
            // 归属匹配（家长下载自己上传的 / 孩子下载自己设备产生的）
            viewerUserId != null && ownerUserId == viewerUserId -> true
            viewerDeviceId != null && ownerDeviceId == viewerDeviceId -> true
            // 家长下载自家设备上行的文件（截图/录屏/录音归属只记了 deviceId）
            viewerUserId != null && !ownerDeviceId.isNullOrBlank() && ownsDevice(viewerUserId, ownerDeviceId) -> true
            else -> false
        }
        if (allow) return Verdict.ALLOW
        log.warn("file access denied: fileId={} viewerUser={} viewerDevice={}", fileId, viewerUserId, viewerDeviceId)
        return Verdict.DENY
    }

    /**
     * 设备是否属于该家长。
     *
     * 用 [DeviceRepository.findByUserId] 而不是再加一个 exists 查询：
     * 单个家长的设备数是十以内的量级，全量取出比对与一次点查开销相当，
     * 且这里只在"归属字段对不上"时才走到，不是下载主路径。
     */
    fun ownsDevice(userId: String, deviceId: String): Boolean =
        runCatching { deviceRepository.findByUserId(userId).any { it.id == deviceId } }
            .onFailure { log.warn("device ownership check failed: user={} device={} err={}", userId, deviceId, it.message) }
            .getOrDefault(false)

    /** 该文件是否为孩子端设备上行（截图/录屏/录音）——匿名强制签名闸门的适用范围。 */
    fun isDeviceOwnedFile(fileId: String): Boolean =
        runCatching { uploadedFileRepository.findById(fileId).orElse(null)?.deviceId != null }
            .getOrDefault(false)
}
