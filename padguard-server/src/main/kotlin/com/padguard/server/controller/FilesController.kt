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
class FilesController(private val fileStorageService: FileStorageService) {

    private val log = LoggerFactory.getLogger(FilesController::class.java)

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): ResponseEntity<Any> {
        val resolved = fileStorageService.resolve(id)
        if (resolved == null) {
            log.warn("file not found: id=$id")
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail<Any>(ParentErr.PARAM_ERROR, "文件不存在或已失效"))
        }

        val (meta, file) = resolved
        // contentType 来自客户端上传时声明的字符串，可能是任意值；
        // 解析失败时退回二进制流，绝不能让它变成 500 ——
        // 下载降级是一种正常结果，不是服务端故障。
        val type = runCatching { MediaType.parseMediaType(meta.contentType) }
            .getOrDefault(MediaType.APPLICATION_OCTET_STREAM)

        return ResponseEntity.ok()
            .contentType(type)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"${file.name}\"")
            .body(FileSystemResource(file))
    }

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
 * 此前文件写入只在孩子端上报截图/录屏时发生，**家长侧没有任何写入通道**。
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
            return ApiResponse.fail(ParentErr.PARAM_ERROR, "文件为空，请重新选择")
        }
        val mime = file.contentType?.takeIf { it.isNotBlank() && it != "*/*" }
            ?: guessContentType(file.originalFilename)
        val stored = fileStorageService.storeDetailed(mime, file.bytes, file.originalFilename)
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
        /**
         * 按扩展名推断 MIME。
         * 部分 Android 文件选择器返回通配类型，原样存下来后孩子端拿到的类型不可播/不可渲染。
         */
        fun guessContentType(name: String?): String {
            val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return "application/octet-stream"
            return when (ext) {
                "apk" -> "application/vnd.android.package-archive"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "mp3" -> "audio/mpeg"
                "m4a", "aac" -> "audio/mp4"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "mp4" -> "video/mp4"
                "3gp" -> "video/3gpp"
                "webm" -> "video/webm"
                else -> "application/octet-stream"
            }
        }
    }
}
