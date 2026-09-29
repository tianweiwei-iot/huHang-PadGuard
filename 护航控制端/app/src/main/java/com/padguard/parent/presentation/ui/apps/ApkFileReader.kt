package com.padguard.presentation.ui.apps

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 从家长手机本地读取 APK：取出字节、包名与应用名。
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
 * 因此先复制到本应用 cache 目录再解析，用完立即删除，不留下 APK 副本。
 */
object ApkFileReader {

    data class ApkInfo(
        val bytes: ByteArray,
        val fileName: String,
        val packageName: String,
        val appName: String?
    )

    /** 单文件体积上限（200MB，与服务端 multipart 上限一致，避免传一半被拒） */
    private const val MAX_BYTES = 200 * 1024 * 1024

    suspend fun read(context: Context, uri: Uri): Result<ApkInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(context.cacheDir, "upload_${System.currentTimeMillis()}.apk")
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

                val bytes = tmp.readBytes()

                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageArchiveInfo(tmp.absolutePath, 0)
                val pkg = info?.packageName
                    ?: error("无法解析安装包（文件可能已损坏或不是 APK）")

                val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    // 不同 ROM 的 DocumentsProvider 列名不统一，取最常见的 _display_name
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                } ?: "$pkg.apk"

                ApkInfo(
                    bytes = bytes,
                    fileName = name,
                    packageName = pkg,
                    appName = info.applicationInfo
                        ?.let { context.packageManager.getApplicationLabel(it).toString() }
                )
            } finally {
                tmp.delete()
            }
        }
    }
}
