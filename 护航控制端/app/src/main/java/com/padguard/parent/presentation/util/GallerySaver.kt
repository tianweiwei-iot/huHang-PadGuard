package com.padguard.presentation.util

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 截图落相册工具。
 *
 * 走 MediaStore（Android 10+ 无需存储权限，且相册/文件管理器立即可见），
 * 归档到 Pictures/PadGuard，家长在系统相册里能直接按相册名找到所有截屏。
 */
@Singleton
class GallerySaver @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 保存 JPEG 字节到系统相册，返回相册内 URI 字符串；失败时抛出原异常供上层提示 */
    fun saveJpeg(bytes: ByteArray): Result<String> = runCatching {
        val resolver = context.contentResolver
        val displayName = "PadGuard_${FILE_FORMAT.format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PadGuard")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("系统相册拒绝写入")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("无法打开相册输出流")
        } finally {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        uri.toString()
    }

    /**
     * 保存 MP4 字节到系统视频库，归档到 Movies/PadGuard。
     * 家长在系统相册/文件管理器里能直接找到录屏文件 —— 满足「录像保存在家长端平板」。
     */
    fun saveVideo(bytes: ByteArray): Result<String> = runCatching {
        val resolver = context.contentResolver
        val displayName = "PadGuard_Rec_${FILE_FORMAT.format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PadGuard")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("系统视频库拒绝写入")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("无法打开视频输出流")
        } finally {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        uri.toString()
    }

    private companion object {
        val FILE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
