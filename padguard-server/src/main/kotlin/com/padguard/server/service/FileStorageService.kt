package com.padguard.server.service

import com.padguard.server.domain.UploadedFile
import com.padguard.server.repository.UploadedFileRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.util.UUID

/**
 * 流式拷贝缓冲：64KB。够大以摊薄系统调用，又小到与文件大小无关 ——
 * 200MB 安装包上传时堆上稳定只占这一个缓冲区（见 [FileStorageService.storeStream]）。
 */
private const val STREAM_BUFFER_BYTES = 64 * 1024

/**
 * 单次孤儿清扫的删除上限。
 *
 * 存在的意义是"配置写错也别造成灾难"：`padguard.storage.dir` 若被误指向一个装了几十万张
 * 图的老目录，没有上限就是一次 `rm -rf` 级别的操作。正常部署的孤儿文件是每天几条到几十条，
 * 5000 足够；被上限截断时 `truncated=true`，第二天继续清。
 */
private const val ORPHAN_SWEEP_MAX_DELETE = 5000

/** 结果里回报的文件名样例条数（够运维判断"删的是什么"，又不至于把响应撑爆）。 */
private const val ORPHAN_SAMPLES = 10

/** 本地文件存储（开发/单机可用）。生产可替换为 MinIO/OSS 对象存储，仅改本类实现。 */
@Service
class FileStorageService(
    @Value("\${padguard.storage.dir:./uploads}") private val storageDir: String,
    @Value("\${padguard.base-url:}") private val baseUrl: String,
    private val uploadedFileRepository: UploadedFileRepository,
    // 可选注入（默认 null 保证纯单测可直构）：存在时下发的文件 URL 自动附带短期签名
    private val urlSigner: com.padguard.server.security.MediaUrlSigner? = null,
    /*
     * 两个初始口令文件的路径，与 AdminDataInitializer / DataInitializer 用同一组配置键。
     *
     * 它们**就写在 uploads/ 根目录且不进数据库**，因此是孤儿清扫唯一会误伤的"有用文件"，
     * 而它同时是登录锁死时唯一的恢复入口（本机踩过一次）。把保护名单收在存储层，
     * 任何调用方都不会"忘了传保护名单"—— 这比在调用点传参可靠。
     */
    @Value("\${padguard.initial-password-file:uploads/管理员初始密码-请登录后修改.txt}")
    private val adminPasswordFile: String = "uploads/管理员初始密码-请登录后修改.txt",
    @Value("\${padguard.parent-initial-password-file:uploads/初始密码-请登录后修改.txt}")
    private val parentPasswordFile: String = "uploads/初始密码-请登录后修改.txt"
) {
    private val dir = File(storageDir).apply { if (!exists()) mkdirs() }

    /** 清扫时必须放过的文件名（只取文件名，因为清扫按文件名比对）。 */
    private val passwordFileNames: Set<String> =
        listOf(adminPasswordFile, parentPasswordFile)
            .mapNotNull { it.takeIf(String::isNotBlank)?.let { p -> File(p).name } }
            .toSet()

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
     * 流式落盘（大文件唯一正确的写法）。
     *
     * `store(contentType, file.bytes, ...)` 会把整个文件读进堆：上限 200MB 时，
     * 两三个家长同时上传安装包/课堂录屏就能把堆打满，表现为整站 502 且日志里只有 OOM，
     * 与"服务端挂了"无法区分。这里用固定缓冲区边读边写，堆占用与文件大小无关。
     *
     * 返回值中的 size 以**实际写入字节数**为准，不采信 `MultipartFile.size`：
     * 客户端可声明任意长度，库里记错会导致下载侧 Range/Content-Length 与真实文件不符。
     *
     * 写入中途失败（客户端断开、磁盘满）时会删除半截文件 —— 否则 `uploads/` 里会留下
     * 一堆没有 DB 记录、也没人能访问的孤儿文件，日积月累把磁盘吃光。
     */
    fun storeStream(
        contentType: String,
        input: java.io.InputStream,
        originalName: String? = null,
        ownerUserId: String? = null,
        deviceId: String? = null
    ): StoredFile {
        val id = UUID.randomUUID().toString()
        val safeName = sanitize(originalName)
        val fileName = "${id}_$safeName"
        val target = File(dir, fileName)
        val written = try {
            input.use { src ->
                target.outputStream().use { out -> src.copyTo(out, STREAM_BUFFER_BYTES) }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        uploadedFileRepository.save(
            UploadedFile(
                id = id, contentType = contentType, storedPath = fileName,
                size = written, createdAt = System.currentTimeMillis(),
                ownerUserId = ownerUserId, deviceId = deviceId
            )
        )
        return StoredFile(id, url(id), safeName)
    }

    /**
     * 生成可访问的文件 URL。
     *
     * 设计原则（视频/截图/录屏加载失败的根治点）：
     * 文件 URL 必须指向「客户端实际连接的那台服务端」，而非「上传请求的来源 Host」——
     * 否则在家长端/孩子端跨网络、反向代理等场景下会出现地址错配，整批 404。
     *
     * 优先级：
     * 1. 显式配置了对外 base-url（建议等于孩子端连接的服务端地址，如 https://files.example.com）
     *    → 生成绝对地址，最可控；生产/私有化部署强烈建议配置；
     * 2. 未配置 base-url → 返回相对路径 `/v1/files/{id}`，
     *    交由客户端按其「连接服务端用的 baseUrl」拼接（孩子端 MediaUrlResolver、
     *    家长端 retrofit 均按此解析），保证文件 URL 与请求 URL 同源。
     *
     * 注：不采用「当前请求 Host 动态拼绝对地址」——上传方网络位置 ≠ 下载方网络位置，
     * 反而会把文件指向另一台机器（如内网/localhost/代理地址），导致孩子端下载失败。
     */
    fun url(id: String): String {
        val raw = if (baseUrl.isNotBlank()) "${baseUrl.trimEnd('/')}/v1/files/$id" else "/v1/files/$id"
        // 签名 URL（第八轮评估 P1-2）：URL 泄漏（日志/转发）后只能在 TTL 内被匿名访问，
        // 过期自动失效。未注入签名器（纯单测）时保持原样。
        return urlSigner?.sign(raw) ?: raw
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

    /**
     * 库里登记过的文件名集合（只取文件名，与 [resolve] 的历史兼容口径一致）。
     *
     * **读库失败必须抛/返回 null，绝不返回空集**：空集会让孤儿清扫认为"所有文件都没登记"，
     * 一次数据库抖动就能把整个 `uploads/` 删空（与本项目离线队列 `readOrNull()==null`
     * 那条约定的成因完全同类）。调用方在 [deleteOrphanFiles] 里显式兜住。
     */
    fun referencedFileNames(): Set<String> =
        uploadedFileRepository.findAllStoredPaths().mapTo(HashSet()) { File(it).name }

    /**
     * 清扫磁盘孤儿：**盘上有、`uploaded_files` 里没有任何记录**的文件。
     *
     * ## 为什么保留期清理管不到
     * `RetentionCleanup` 的三个清理步骤全部以 **DB 记录**为起点（按记录删文件）。
     * 记录本身不存在时，磁盘文件永远扫不到，于是有三条稳定的产生路径：
     * 1. [storeDetailed] 先写盘再 `save`：save 抛异常或进程被 kill → 文件留下、记录没有；
     * 2. 早期版本把**绝对路径**写进 `storedPath`，目录搬家后 `resolve` 全部失效、记录被人工清掉；
     * 3. 自动化测试/curl 直接把样本文件写进 `storageDir`（`shell.php.png`、`evil.png` 之类），从不进库。
     *
     * 后果不只是占磁盘：发布脚本会把整个 `uploads/` 复制进安装包，**测试载荷随包分发**。
     *
     * ## 安全边界（宁可不删，不可错删）
     * - 只删修改时间早于 `graceDays` 天的文件：正在落盘（写盘 → save 之间）的文件、
     *   以及"库从旧备份恢复、文件比记录新"的场景都在宽限期内，不会被误删；
     * - 只比对**文件名**，不比对路径，与 [resolve] 的历史兼容口径保持同一套规则；
     * - 只处理存储根目录下的普通文件：不递归、不碰子目录（子目录可能是别人挂进来的东西）；
     * - 单次删除上限 [maxDelete]：配置写错 `storage.dir` 时最多也就能删这么多；
     * - [protectedNames] 里的文件一律跳过：初始口令文件就落在 `uploads/` 根目录且**不入库**，
     *   按"无记录即孤儿"会被清掉，而它是登录锁死时唯一的恢复入口；
     * - [referencedFileNames] 读失败直接返回 `dbReadFailed=true` 的空结果，**一个都不删**。
     *
     * @param dryRun true 时只统计不删（后台默认值就是 true，先看数量再决定）
     */
    fun deleteOrphanFiles(
        graceDays: Long,
        maxDelete: Int = ORPHAN_SWEEP_MAX_DELETE,
        dryRun: Boolean = false,
        protectedNames: Set<String> = emptySet()
    ): OrphanSweepResult {
        val root = runCatching { dir.canonicalFile }.getOrNull()
            ?: return OrphanSweepResult(0, 0, 0, emptyList(), truncated = false, dbReadFailed = true)
        val referenced = runCatching { referencedFileNames() }.getOrNull()
            ?: return OrphanSweepResult(0, 0, 0, emptyList(), truncated = false, dbReadFailed = true)
        val files = runCatching { root.listFiles() }.getOrNull()
            ?: return OrphanSweepResult(0, 0, 0, emptyList(), truncated = false, dbReadFailed = false)

        // 宽限期至少留 1 天：graceDays=0 会让"刚写完还没 save"的文件也满足条件，
        // 那是唯一能造成真实数据丢失的竞态，这里从入口堵死。
        val effectiveGraceDays = graceDays.coerceAtLeast(1)
        val threshold = System.currentTimeMillis() - effectiveGraceDays * 86_400_000L

        var scanned = 0
        var deleted = 0
        var freed = 0L
        var truncated = false
        val samples = mutableListOf<String>()
        for (f in files) {
            if (!f.isFile) continue
            scanned++
            if (f.name in referenced) continue
            // 保护名单：初始口令文件就放在 uploads/ 根目录且**不进数据库**
            // （`uploads/管理员初始密码-请登录后修改.txt` 之类），照"无记录即孤儿"的规则
            // 会被清掉 —— 那正是登录锁死时唯一的恢复入口，删了等于把运维关在门外。
            if (f.name in passwordFileNames || f.name in protectedNames) continue
            if (f.lastModified() > threshold) continue
            if (deleted >= maxDelete) { truncated = true; break }
            val size = f.length()
            if (dryRun || runCatching { f.delete() }.getOrDefault(false)) {
                deleted++
                freed += size
                if (samples.size < ORPHAN_SAMPLES) samples += f.name
            }
        }
        return OrphanSweepResult(scanned, deleted, freed, samples, truncated, dbReadFailed = false)
    }

    /** [deleteOrphanFiles] 的结果。`dbReadFailed=true` 表示读库失败、本轮什么都没删。 */
    data class OrphanSweepResult(
        val scanned: Int,
        val deleted: Int,
        val bytesFreed: Long,
        val samples: List<String>,
        val truncated: Boolean,
        val dbReadFailed: Boolean
    )

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

    companion object {
        /**
         * 上传黑名单的单一来源：所有入口（家长素材、孩子头像/截图/媒体）共用。
         *
         * 之前只在家长上传入口校验，孩子端三条入口直接信任客户端声明的 contentType；
         * 上传一个 `image/svg+xml` 后，下载侧按 image 通配类型会判为可 inline 渲染，
         * 而该 URL 与 `/admin` 同源 —— 构成存储型 XSS。孩子端本就持有设备令牌，
         * 因此这条路径不是理论风险。
         */
        private val BLOCKED_EXTENSIONS = setOf(
            "html", "htm", "xhtml", "shtml", "svg", "js", "mjs", "css", "xml",
            "json", "swf", "hta", "jsp", "asp", "aspx", "php", "exe", "bat", "cmd", "ps1", "sh"
        )

        private val BLOCKED_MIME_TYPES = setOf(
            "text/html", "application/xhtml+xml", "image/svg+xml",
            "text/javascript", "application/javascript", "application/x-javascript",
            "text/xml", "application/xml", "application/x-shockwave-flash"
        )

        /** 客户端未声明可用类型时按扩展名推断，与下载侧渲染策略共用一份规则。 */
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

        /** 解析最终落库的 MIME：声明优先，通配/缺失才按文件名兜底。 */
        fun resolveMime(originalName: String?, declared: String?): String {
            val usable = declared?.takeIf { it.isNotBlank() && it != "*/*" }
            return usable?.substringBefore(";")?.trim()?.lowercase() ?: guessContentType(originalName)
        }

        /** @return 拒绝原因；null 表示允许上传。调用方按自身端别映射到对应错误码。 */
        fun unsafeUploadReason(originalName: String?, mime: String): String? {
            val ext = originalName?.substringAfterLast('.', "")?.lowercase().orEmpty()
            if (ext in BLOCKED_EXTENSIONS) return "不支持上传 .$ext 类型的文件"
            if (mime.substringBefore(';').trim().lowercase() in BLOCKED_MIME_TYPES) {
                return "不支持上传该类型的文件"
            }
            return null
        }
    }
}
