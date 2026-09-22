package com.padguard.server.controller

import com.padguard.server.common.ApiResponse
import com.padguard.server.common.ParentErr
import com.padguard.server.service.FileStorageService
import org.slf4j.LoggerFactory
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
}
