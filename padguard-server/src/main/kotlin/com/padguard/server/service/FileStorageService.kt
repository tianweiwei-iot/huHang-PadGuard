package com.padguard.server.service

import com.padguard.server.domain.UploadedFile
import com.padguard.server.repository.UploadedFileRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.util.UUID

/** 本地文件存储（开发/单机可用）。生产可替换为 MinIO/OSS 对象存储，仅改本类实现。 */
@Service
class FileStorageService(
    @Value("\${padguard.storage.dir:./uploads}") private val storageDir: String,
    @Value("\${padguard.base-url:}") private val baseUrl: String,
    private val uploadedFileRepository: UploadedFileRepository
) {
    private val dir = File(storageDir).apply { if (!exists()) mkdirs() }

    /**
     * 保存字节，返回可访问的 URL（相对或绝对，取决于 base-url 配置）。
     *
     * 库里**只存文件名、不存绝对路径**。
     * 早期实现存 `target.absolutePath`，后果是发布包目录一旦被移动或改名，
     * 所有历史文件记录全部失效（截图打不开、录屏下不来），
     * 而这种失效往往在很久之后才被发现，数据已经无法重建。
     * 只存文件名、运行时基于当前配置的 [storageDir] 解析，目录搬家就不会失效。
     */
    fun store(contentType: String, bytes: ByteArray, originalName: String? = null): String {
        val id = UUID.randomUUID().toString()
        val safeName = sanitize(originalName)
        val fileName = "${id}_$safeName"
        val target = File(dir, fileName)
        target.writeBytes(bytes)
        uploadedFileRepository.save(
            UploadedFile(
                id = id, contentType = contentType, storedPath = fileName,
                size = bytes.size.toLong(), createdAt = System.currentTimeMillis()
            )
        )
        return url(id)
    }

    fun url(id: String): String =
        if (baseUrl.isNotBlank()) "${baseUrl.trimEnd('/')}/v1/files/$id" else "/v1/files/$id"

    /**
     * 解析出可读取的文件。
     *
     * 两道校验缺一不可：
     * 1. 文件必须真实存在 —— 否则 Controller 抛异常时会撞上"Content-Type 已预设"的坑；
     * 2. 解析后的规范路径必须仍在存储目录内 —— 防止 `../` 目录穿越读到系统任意文件。
     */
    fun resolve(id: String): Pair<UploadedFile, File>? {
        val meta = uploadedFileRepository.findById(id).orElse(null) ?: return null
        // 兼容历史数据：早期版本存的是绝对路径，这里只取文件名部分再按当前存储目录解析。
        // 没有这层兼容，升级后所有已上传的截图/录屏都会变成"文件不存在"。
        val stored = File(meta.storedPath).name
        val file = File(dir, stored)
        if (!file.isFile) return null
        val root = dir.canonicalFile
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (canonical != root && !canonical.startsWith(root.path + File.separator)) return null
        return meta to file
    }

    private fun sanitize(originalName: String?): String {
        val name = originalName?.substringAfterLast('/')?.substringAfterLast('\\') ?: "file"
        return name.replace(Regex("[^\\w.\\-]"), "_").ifBlank { "file" }
    }
}
