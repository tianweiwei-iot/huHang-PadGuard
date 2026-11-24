package com.padguard.parent.presentation.ui.apps

import android.content.Context
import android.net.Uri
import com.padguard.parent.domain.model.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 从家长手机本地读取 APK：取出临时文件、包名与应用名。
 *
 * ## 为什么必须解出真实包名
 * 服务端要靠包名预置台账（否则家长端列表里凭空多一个"未知应用"），
 * 孩子端要靠包名确认安装目标与校验结果。
 * 拿文件名当包名在绝大多数情况下都是错的（"微信.apk"、"weixin_1.2.apk"），
 * 会导致指令下发成功却装不上，且没有任何报错。
 *
 * ## 为什么要先落盘再解析
 * `PackageManager.getPackageArchiveInfo` 只接受**文件路径**，不接受流式输入，
 * 而 [Uri] 可能来自网盘/文件管理器，未必能拿到真实路径。
 * 因此先复制到本应用 cache 目录再解析。
 *
 * ## 落盘后不再整份读进内存
 * 早期这里解析完还会 `readBytes()` 把整个 APK 读进堆，交给上传层再写一遍 ——
 * 200MB 的安装包等于「2 倍峰值」，家长端在上传刚开始时就有被系统 OOM 杀掉的风险。
 * 现在把临时文件本身作为[流式来源][MediaSource]交给上传层，
 * 上传结束后由调用方 `close()` 删除（见 [ApkInfo.asMediaSource]）。
 */
object ApkFileReader {

    data class ApkInfo(
        /** cache 目录里的临时副本；由 [asMediaSource] 交给上传层，上传结束即删 */
        val file: File,
        val fileName: String,
        val packageName: String,
        val appName: String?,
        val versionCode: Long,
        val versionName: String
    ) {
        /**
         * 流式上传来源。**用完后必须 `close()`**（放 `finally`），否则 cache 里会留一份几十 MB 的副本。
         * 即便漏了，[read] 每次调用都会顺手清掉超过 2 小时的孤儿副本。
         */
        fun asMediaSource(): MediaSource = MediaSource.ofFile(
            file = file,
            contentType = "application/vnd.android.package-archive",
            fileName = fileName,
            deleteWhenClosed = true
        )
    }

    /** 单文件体积上限（200MB，与服务端 multipart 上限一致，避免传一半被拒） */
    private const val MAX_BYTES = MediaSource.MAX_UPLOAD_BYTES

    /** 孤儿临时文件的最短存活时间：小于此值视为"可能正在上传"，绝不删 */
    private const val ORPHAN_TTL_MS = 2L * 60 * 60 * 1000

    suspend fun read(context: Context, uri: Uri): Result<ApkInfo> = withContext(Dispatchers.IO) {
        runCatching {
            sweepOrphans(context)
            val tmp = File(context.cacheDir, "upload_${System.currentTimeMillis()}.apk")
            // 解析失败（不是 APK / 超上限 / 权限被撤）必须删掉半截文件；
            // 成功则把删除责任交给上传层（close 时删），所以这里用 keep 标记区分。
            var keep = false
            try {
                // 先把 Uri 落成本地文件：① 解析 APK 必须要真实路径；② 顺便完成大小校验
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out ->
                        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0
                        while (true) {
                            val read = input.read(chunk)
                            if (read <= 0) break
                            total += read
                            require(total <= MAX_BYTES) { "安装包超过 200MB 上限" }
                            out.write(chunk, 0, read)
                        }
                    }
                } ?: error("无法读取所选文件")

                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageArchiveInfo(tmp.absolutePath, 0)
                val pkg = info?.packageName
                    ?: error("无法解析安装包（文件可能已损坏或不是 APK）")

                val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    // 不同 ROM 的 DocumentsProvider 列名不统一，取最常见的 _display_name
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                } ?: "$pkg.apk"

                val result = ApkInfo(
                    file = tmp,
                    fileName = name,
                    packageName = pkg,
                    appName = info.applicationInfo
                        ?.let { context.packageManager.getApplicationLabel(it).toString() },
                    // versionCode 字段已弃用，API 28+ 用 longVersionCode 避免 32 位版本号溢出
                    versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        info.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        info.versionCode.toLong()
                    },
                    versionName = info.versionName ?: ""
                )
                keep = true
                result
            } finally {
                if (!keep) runCatching { tmp.delete() }
            }
        }
    }

    /**
     * 清理上次遗留的 APK 临时副本。
     *
     * 正常路径由上传层 `close()` 删除；但上传中被系统杀掉、或异常路径漏了 close 时，
     * cache 里会积压几十 MB 的副本。每次选取安装包时顺手扫一遍，成本仅一个目录遍历。
     */
    private fun sweepOrphans(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - ORPHAN_TTL_MS
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith("upload_") && f.name.endsWith(".apk") && f.lastModified() < cutoff) {
                    runCatching { f.delete() }
                }
            }
        }
    }
}
