package com.padguard.server.controller

import com.padguard.server.common.ApiResponse
import com.padguard.server.common.ParentErr
import com.padguard.server.dto.FileUploadDto
import com.padguard.server.service.FileStorageService
import org.slf4j.LoggerFactory
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * 文件下载（截图 / 录屏 / 录音 / 分发 APK）。
 *
 * ## 为什么必须流式返回 Resource，而不是 `ResponseEntity<ByteArray>`
 * 早期实现是 `Files.readAllBytes()` 把整个文件一次性读进堆内存再写出：
 * - 一个 43MB 的 APK 会让堆瞬时涨几十 MB，并发几个请求就可能 OOM；
 * - 更要命的是它让错误**无法返回**：客户端在传输中途断开、或写出失败时，
 *   异常会冒到 [com.padguard.server.common.GlobalExceptionHandler]，
 *   而此时响应的 Content-Type 已被预设成 `application/vnd.android.package-archive`，
 *   JSON 转换器拒绝写入 → `HttpMessageNotWritableException` → 连接被重置。
 *   客户端（管控端）看到的只有一句"连接被重置"，真实原因被彻底吞掉，
 *   表现就是"服务端连不上"，排查时极易误判成网络问题。
 *
 * [FileSystemResource] 由 Spring 的 `ResourceHttpMessageConverter` 以固定缓冲区流式写出，
 * 全程不把文件内容装进内存，且天然支持 `Range` 断点续传。
 *
 * ## 为什么文件不存在时返回 404 而不是抛异常
 * 抛异常就会走进上面那条"预设 Content-Type 与错误响应冲突"的死路。
 * 直接构造 404 + JSON 响应，成功路径与失败路径各自声明各自的 Content-Type，互不干扰。
 */
@RestController
@RequestMapping("/v1/files")
class FilesController(
    private val fileStorageService: FileStorageService,
    // 归属判定规则已于 2026-11-18 下沉到 service.FileAccessService（Controller 不再直连 Repository）
    private val fileAccessService: com.padguard.server.service.FileAccessService,
    // 可选注入（纯单测可直构）：媒体 URL 签名验签与匿名强制签名闸门
    private val mediaUrlSigner: com.padguard.server.security.MediaUrlSigner? = null,
    @org.springframework.beans.factory.annotation.Value("\${padguard.media.require-signature:false}")
    private val requireSignature: Boolean = false
) {

    private val log = LoggerFactory.getLogger(FilesController::class.java)

    @GetMapping("/{id}")
    fun get(request: jakarta.servlet.http.HttpServletRequest, @PathVariable id: String): ResponseEntity<Any> {
        // ---- 签名 URL 校验（第八轮评估 P1-2）----
        // 带了 exp/sig 任一参数即进入签名语义：fail-closed，验不过一律 403，
        // 绝不降级放行——否则攻击者删掉 sig 参数就能绕过"强制匿名签名"闸门。
        val sigExp = request.getParameter("exp")
        val sigVal = request.getParameter("sig")
        val signaturePresent = sigExp != null || sigVal != null
        val signatureValid = signaturePresent &&
            mediaUrlSigner != null && mediaUrlSigner.verify(id, sigExp, sigVal)
        if (signaturePresent && !signatureValid) {
            log.warn("media url signature rejected: fileId={}", id)
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail<Any>(ParentErr.FORBIDDEN, "链接已失效，请刷新后重试"))
        }

        // ---- 访问主体判定 ----
        val hasCredentials = request.getAttribute("fileViewerUserId") != null ||
            request.getAttribute("fileViewerDeviceId") != null
        if (hasCredentials) {
            // 有凭证：按精确归属校验（签名不能替凭证提权，防止泄漏的签名链接越过归属规则）
            if (fileAccessService.resolveOwnership(
                    request.getAttribute("fileViewerUserId") as? String,
                    request.getAttribute("fileViewerDeviceId") as? String,
                    id
                ) == com.padguard.server.service.FileAccessService.Verdict.DENY
            ) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ApiResponse.fail<Any>(ParentErr.FORBIDDEN, "无权访问该文件"))
            }
        } else if (requireSignature && !signatureValid && fileAccessService.isDeviceOwnedFile(id)) {
            // 匿名强制签名闸门（默认关）：开启后，设备上行敏感文件（截图/录屏/录音）
            // 不再依赖"128 位 id 不可穷举"这一唯一防线，匿名访问必须持有效签名。
            // 家长发布的共享素材（ownerUserId 独有）不在此列——孩子端图片库匿名加载依赖它。
            log.warn("anonymous access to device media requires signed url: fileId={}", id)
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail<Any>(ParentErr.FORBIDDEN, "请使用有效凭证或签名链接访问"))
        } else if (!hasCredentials && !requireSignature && fileAccessService.isDeviceOwnedFile(id)) {
            // 闸门未开且匿名访问设备私有媒体：仅依赖 128 位 fileId 不可穷举这一道防线。
            // 补访问审计（R-P2-15），便于事后追溯异常批量访问；不改放行语义，避免破坏现有客户端。
            log.info(
                "anonymous access to device-owned media (signature gate off): fileId={} remote={}",
                id, request.remoteAddr
            )
        }
        // 无凭证且闸门未开：维持能力 URL 语义（历史行为，见 FileAccessInterceptor 注释）

        val resolved = fileStorageService.resolve(id)
        if (resolved == null) {
            log.warn("file not found: id=$id")
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail<Any>(ParentErr.PARAM_ERROR, "文件不存在或已失效"))
        }

        val (meta, file) = resolved
        // 安全策略（存储型 XSS 防护，第八轮评估 P1-A）：
        // contentType 是上传时客户端声明的任意字符串，绝不能原样透传。
        // 若按声明返回 `text/html` 之类的类型并 inline 渲染，攻击者上传一个 HTML 文件后，
        // /v1/files/{id} 会与 /admin 管理后台同源执行任意 JS —— 窃取管理员会话。
        // 因此：仅媒体类（image/video/audio）允许按声明类型 inline 渲染；
        // 其余一律降级为 application/octet-stream 并强制 attachment 下载，
        // 配合 nginx 的 X-Content-Type-Options:nosniff 双保险。
        val declared = runCatching { MediaType.parseMediaType(meta.contentType) }
            .getOrNull()
        // 显式排除 SVG：`image/svg+xml` 能匹配 `image/*` 通配符，但 SVG 在浏览器里是 XML 文档，
        // 内联打开时 <script> 与 /admin 同源执行。历史文件/旧版本客户端已落盘的 SVG 也必须降级。
        val subtype = declared?.subtype?.lowercase().orEmpty()
        val neverInline = subtype == "svg+xml" || subtype.endsWith("+xml")
        val safeInline = declared != null && !neverInline && INLINE_SAFE_TYPES.any { declared.isCompatibleWith(it) }
        val type = if (safeInline) declared!! else MediaType.APPLICATION_OCTET_STREAM
        val disposition = if (safeInline) "inline" else "attachment"

        return ResponseEntity.ok()
            .contentType(type)
            .header(HttpHeaders.CONTENT_DISPOSITION, "$disposition; filename=\"${headerSafeName(file.name)}\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(FileSystemResource(file))
    }

    /** 允许 inline 渲染的白名单：浏览器/WebView 只渲染这三族媒体，脚本类文件全部强制下载。 */
    private val INLINE_SAFE_TYPES = listOf(
        MediaType.parseMediaType("image/*"),
        MediaType.parseMediaType("video/*"),
        MediaType.parseMediaType("audio/*")
    )

    /** Content-Disposition 头里的文件名必须干净：去引号/反斜杠/换行，防止响应头注入。 */
    private fun headerSafeName(name: String): String =
        name.replace(Regex("[\"\\\\\\r\\n]"), "_").take(200).ifBlank { "file" }

    /**
     * 客户端未声明 Content-Type 时按扩展名兜底。
     *
     * 部分 Android 文件选择器（尤其是从"文件管理"或网盘选取时）会给出 `application/octet-stream`，
     * 原样存下来后孩子端拿到的类型不可播/不可渲染。这里按扩展名补全常见类型。
     */
    private fun guessContentType(name: String?): String =
        com.padguard.server.controller.ParentMediaUploadController.guessContentType(name)
}

/**
 * 家长端上传素材 / APK。
 *
 * ## 为什么必须单独开一个控制器，而不是挂在文件下载路径下
 * `WebConfig` 把文件下载路径（files 前缀）整体排除在家长鉴权之外——
 * 孩子端下载家长发的图片/视频走的就是这个路径，它拿不到家长 JWT。
 * 因此挂在 files 前缀下的接口取不到 `userId` 请求属性，
 * 上传这种"往磁盘写任意内容"的入口就变成了完全匿名可调用，既不安全也无法审计。
 * 这里改挂受保护的 media 前缀，鉴权拦截器照常注入 userId。
 *
 * ## 为什么必须有这个端点
 * 早期文件写入只在孩子端上报截图/录屏时发生，**家长侧没有任何写入通道**。
 * 后果是"信息发布"只能填一个外部 URL——而家长手里只有本地的照片、录音、视频和 APK 安装包，
 * 于是页面上只能塞写死的 mock 地址，功能等于没做。远程安装同理，没有 APK 上传就无法分发。
 *
 * ## 为什么用 multipart 而不是 base64 塞 JSON
 * base64 会让体积膨胀约 33%，一个 40MB 的安装包或一段课堂视频在弱网下基本传不动，
 * 且必须整块读进内存才能编码。multipart 是流式写入，配合已配置的 200MB 上限即可覆盖真实场景。
 */
@RestController
@RequestMapping("/v1/media")
class ParentMediaUploadController(private val fileStorageService: FileStorageService) {

    private val log = LoggerFactory.getLogger(ParentMediaUploadController::class.java)

    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestAttribute("userId") userId: String,
        @RequestParam("file") file: MultipartFile
    ): ApiResponse<FileUploadDto> {
        if (file.isEmpty) {
            // 第 4 轮修正：原为 return ApiResponse.fail(...)，返回 HTTP 200 + code=1001，
            // 绕过了全局异常映射 —— 网关/监控按 HTTP 语义会把"空文件"当成功请求。
            // 改为抛 BizException 后统一映射为 400（PARAM_ERROR），家长端 exec 的
            // HTTP 非 2xx 分支与业务码分支都能正确展示失败原因，行为不变。
            throw com.padguard.server.common.BizException(
                com.padguard.server.common.ParentErr.PARAM_ERROR, "文件为空，请重新选择", com.padguard.server.common.Audience.PARENT
            )
        }
        // 上传类型黑名单统一收在 FileStorageService：孩子端头像/截图/媒体三条入口
        // 也必须走同一份规则，不能只在家长入口挡住 HTML/SVG/JS。
        val mime = com.padguard.server.service.FileStorageService.resolveMime(file.originalFilename, file.contentType)
        com.padguard.server.service.FileStorageService.unsafeUploadReason(file.originalFilename, mime)?.let { reason ->
            throw com.padguard.server.common.BizException(
                com.padguard.server.common.ParentErr.PARAM_ERROR, reason, com.padguard.server.common.Audience.PARENT
            )
        }
        // 流式落盘：上限 200MB 的文件若走 file.bytes 会整块进堆，两三个并发上传即可 OOM
        // （见 FileStorageService.storeStream 的说明）。这里只占一个 64KB 缓冲区。
        val stored = fileStorageService.storeStream(mime, file.inputStream, file.originalFilename, ownerUserId = userId)
        // 上传是唯一能往磁盘写任意内容的入口，出问题时（磁盘打满、传错文件）需要能追到是谁传的
        log.info("parent upload: userId={} fileId={} name={} size={} type={}", userId, stored.id, stored.name, file.size, mime)
        return ApiResponse.ok(
            FileUploadDto(
                fileId = stored.id,
                url = stored.url,
                fileName = file.originalFilename ?: stored.name,
                contentType = mime,
                size = file.size
            )
        )
    }

    companion object {
        /** 兼容既有调用点；规则本体在 FileStorageService，避免两处漂移。 */
        fun guessContentType(name: String?): String =
            com.padguard.server.service.FileStorageService.guessContentType(name)
    }
}
