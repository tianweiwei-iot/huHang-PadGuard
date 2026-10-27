package com.padguard.server.service

import com.padguard.server.domain.UploadedFile
import com.padguard.server.repository.UploadedFileRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
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
    fun store(
        contentType: String, bytes: ByteArray, originalName: String? = null,
        ownerUserId: String? = null, deviceId: String? = null
    ): String = storeDetailed(contentType, bytes, originalName, ownerUserId, deviceId).url

    /**
     * 同 [store]，但一并返回 id。
     *
     * 家长端上传需要 id：信息发布页要能"撤掉刚选错的素材"，远程安装要按 id 回显文件名。
     * 只有 url 的话客户端得自己去解析路径取 id —— 一旦 URL 拼接规则变化就会静默失效。
     *
     * [ownerUserId] / [deviceId] 是文件归属，用于下载时校验（见 FilesController）：
     * 文件下载路径整体放开了鉴权，不记归属就无从判断"谁有权下载这个文件"。
     */
    fun storeDetailed(
        contentType: String, bytes: ByteArray, originalName: String? = null,
        ownerUserId: String? = null, deviceId: String? = null
    ): StoredFile {
        val id = UUID.randomUUID().toString()
        val safeName = sanitize(originalName)
        val fileName = "${id}_$safeName"
        val target = File(dir, fileName)
        target.writeBytes(bytes)
        uploadedFileRepository.save(
            UploadedFile(
                id = id, contentType = contentType, storedPath = fileName,
                size = bytes.size.toLong(), createdAt = System.currentTimeMillis(),
                ownerUserId = ownerUserId, deviceId = deviceId
            )
        )
        return StoredFile(id, url(id), safeName)
    }

    /** 落盘结果：id 用于后续按文件操作，url 用于直接访问，name 用于界面回显。 */
    data class StoredFile(val id: String, val url: String, val name: String)

    /**
     * 生成可访问的文件 URL。
     *
     * 优先级（这是视频/截图/录屏集体加载失败的根治点）：
     * 1. **当前 HTTP 请求的 Host（动态 origin）** —— 永远是「客户端实际访问服务端的那台机器」，
     *    从根本上消除「写死地址 / 孩子端 origin 错配」导致的整批 404；
     * 2. 无请求上下文（极少数离线重算）且显式配置了 [baseUrl] → 用配置值兜底；
     * 3. 都没有 → 退回相对路径 `/v1/files/{id}`，交由客户端按自己的 baseUrl 拼接。
     *
     * ## 为什么动态 origin 必须优先于 base-url
     * 早期把 [baseUrl] 写成一个固定地址（或 `localhost`）、或留空下发相对路径让客户端自行拼接，
     * 都会在孩子端出现「地址错配」：服务端实际部署在 A（局域网 IP / 公网域名 / 端口映射），
     * 而写死值或孩子端 [com.padguard.child.ui.message.MediaUrlResolver] 用来拼接的
     * origin 是 B（例如硬编码公网 `8.148.68.183:8090`，或孩子端自己的 `localhost`）。
     * 于是所有已发布的视频、截图、录屏都指向 B，文件却落在 A → 整批 404，
     * 表现为「最初稳定、改了配置后视频就加载不出来」。
     *
     * 动态取当前请求 Host，保证「上传接口返回的地址」与「客户端实际访问服务端的地址」
     * 是同一台机器，从根本上消除错配，且天然支持换 IP / 换域名 / 迁移，无需重发包。
     * 因此即便 [baseUrl] 被误配成 `localhost`，也只在「请求上下文缺失」时才兜底，
     * 不会污染正常上传返回的地址。
     */
    fun url(id: String): String {
        val origin = currentOrigin()
        if (origin != null) return "$origin/v1/files/$id"
        if (baseUrl.isNotBlank()) return "${baseUrl.trimEnd('/')}/v1/files/$id"
        return "/v1/files/$id"
    }

    /**
     * 从当前 HTTP 请求取出 origin（scheme://host[:port]）。
     * 仅在请求线程内（如 `/v1/media/upload`）可用；无请求上下文（离线重算等）返回 null，交给上层兜底。
     */
    private fun currentOrigin(): String? {
        val attrs = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
        val req = attrs?.request
        if (req != null) {
            val scheme = req.scheme
            val host = req.serverName
            val port = req.serverPort
            return if (port == 80 || port == 443) "$scheme://$host" else "$scheme://$host:$port"
        }
        return null
    }

    /**
     * 按 id 删除文件（磁盘 + 记录）。
     *
     * 保留期清理用：uploads 里的孩子端录屏/截图无生命周期，单个 90MB 级，
     * 不清则 50GiB 盘 6-18 个月写满 → PG 拒写全站瘫痪。
     * 先删记录再删文件：记录没了下载即 404，文件残留只是占盘子的孤儿。
     *
     * @return 磁盘文件是否真实删除（记录不存在返回 false）
     */
    fun deleteById(id: String): Boolean {
        val meta = uploadedFileRepository.findById(id).orElse(null) ?: return false
        uploadedFileRepository.delete(meta)
        val file = File(dir, File(meta.storedPath).name)
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /** 存储根目录（磁盘检查用：磁盘用量告警盯着这个目录所在的文件系统）。 */
    fun storageRoot(): File = dir

    /** 从文件 URL 中解析 id；无法解析（历史脏数据/相对路径异常）时返回 null。 */
    fun idFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val marker = "/v1/files/"
        val i = url.lastIndexOf(marker)
        if (i < 0) return null
        return url.substring(i + marker.length).substringBefore('?').takeIf { it.isNotBlank() }
    }

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
