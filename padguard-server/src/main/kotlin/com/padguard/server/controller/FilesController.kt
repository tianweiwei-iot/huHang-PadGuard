package com.padguard.server.controller

import com.padguard.server.common.ChildErr
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.service.FileStorageService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.nio.file.Files

@RestController
@RequestMapping("/v1/files")
class FilesController(private val fileStorageService: FileStorageService) {

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): ResponseEntity<ByteArray> {
        val pair = fileStorageService.resolve(id)
            ?: throw BizException(ChildErr.SERVER_ERROR, "文件不存在", Audience.PARENT)
        val (meta, file) = pair
        val bytes = Files.readAllBytes(file.toPath())
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, meta.contentType)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"$id\"")
            .body(bytes)
    }
}
