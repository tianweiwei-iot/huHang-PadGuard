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

    /** 保存字节，返回可访问的 URL（相对或绝对，取决于 base-url 配置） */
    fun store(contentType: String, bytes: ByteArray, originalName: String? = null): String {
        val id = UUID.randomUUID().toString()
        val safeName = originalName?.replace(Regex("[^\\w.\\-]"), "_") ?: "file"
        val target = File(dir, "${id}_${safeName}")
        target.writeBytes(bytes)
        uploadedFileRepository.save(
            UploadedFile(
                id = id, contentType = contentType, storedPath = target.absolutePath,
                size = bytes.size.toLong(), createdAt = System.currentTimeMillis()
            )
        )
        return if (baseUrl.isNotBlank()) "$baseUrl/v1/files/$id" else "/v1/files/$id"
    }

    fun resolve(id: String): Pair<UploadedFile, File>? {
        val meta = uploadedFileRepository.findById(id).orElse(null) ?: return null
        val file = File(meta.storedPath)
        if (!file.exists()) return null
        return meta to file
    }
}
