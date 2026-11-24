package com.padguard.parent.domain.model

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 待上传素材的**流式**来源（图片 / 视频 / 音频 / APK）。
 *
 * ## 为什么不用 ByteArray
 * 早期链路的写法是「选中素材 → `readBytes()` 整个读进内存 → 交给 multipart」。
 * 家长从相册挑一段 4K 视频（几百 MB）时，这份字节数组本身就接近或超过
 * Android 单进程堆上限，而 OkHttp 组包时还要再往 socket 写一遍 —— 实际峰值是
 * 「素材大小 × 2」，典型表现是选完视频、进度条刚转起来就被系统杀掉（OOM），
 * 家长侧看到的是"选了视频没反应/闪退"，日志里连一行业务错误都没有。
 *
 * 现在只保留**怎么打开这份素材**的办法（[open]），真正读取发生在 OkHttp 的
 * 写线程里，按 8KB 分段流式发送，内存占用恒定。
 *
 * ## 契约
 * - [open] 必须可以**多次调用**，每次都返回一个从头开始的新流。
 *   OkHttp 在鉴权重试 / 重定向时会把同一个 body 再写一遍，返回半路流会导致
 *   上传内容损坏（服务端存下一个残缺的视频，孩子端播放失败）。
 * - [close] 释放临时文件等资源，须幂等。
 */
class MediaSource(
    val fileName: String,
    val contentType: String,
    /** 已知字节数；未知时用 [UNKNOWN_SIZE]，此时走 chunked 上传 */
    val sizeBytes: Long,
    private val opener: () -> InputStream,
    private val disposer: (() -> Unit)? = null
) : Closeable {

    init {
        // 与服务端 multipart 上限（spring.servlet.multipart.max-file-size=200MB）保持一致。
        // 在这里硬拦，避免「传了几分钟才被服务端 413 拒掉」这种最伤体验的失败方式。
        if (sizeBytes > MAX_UPLOAD_BYTES) throw MediaTooLargeException(sizeBytes)
    }

    fun open(): InputStream = opener()

    private val disposed = AtomicBoolean(false)

    override fun close() {
        if (disposed.compareAndSet(false, true)) {
            runCatching { disposer?.invoke() }
        }
    }

    companion object {
        const val UNKNOWN_SIZE = -1L

        /** 单文件上限：与服务端 `spring.servlet.multipart.max-file-size` 对齐 */
        const val MAX_UPLOAD_BYTES = 200L * 1024 * 1024

        /**
         * 本地临时文件作为素材来源。
         *
         * @param deleteWhenClosed 关闭时删除该临时文件（APK 解析留下的 cache 副本用它，
         *   避免家长每挑一次安装包就在 cache 里留一份几十 MB 的垃圾）
         */
        fun ofFile(
            file: File,
            contentType: String,
            fileName: String = file.name,
            deleteWhenClosed: Boolean = false
        ): MediaSource = MediaSource(
            fileName = fileName,
            contentType = contentType,
            sizeBytes = file.length(),
            opener = { FileInputStream(file) },
            disposer = if (deleteWhenClosed) ({ file.delete(); Unit }) else null
        )
    }
}

/** 素材超过 [MediaSource.MAX_UPLOAD_BYTES]：必须在选完的第一时间如实告知，别让家长白等。 */
class MediaTooLargeException(val sizeBytes: Long) :
    Exception("素材大小 ${sizeBytes / 1024 / 1024}MB 超过 200MB 上限")
