package com.padguard.child.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.padguard.core.common.Logger
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.transport.http.ApiResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 无障碍通道远程截屏 —— MediaProjection 的"永久免弹窗"替代路径。
 *
 * ## 解决什么问题
 * MediaProjection 的用户确认是 Android 隐私红线：确认框跟随**进程**生命周期，
 * 令牌是进程内的 IBinder，无法写进任何配置文件/注册表持久化，Device Owner 也没有
 * 静默通道。于是"每次打开孩子端 App 都弹『是否允许护航成长录制/投射您的屏幕』"
 * 在进程被回收后就必然复现 —— 这是平台硬边界，不是实现缺陷。
 *
 * 但**远程截屏**这一项存在第二条路：[AccessibilityService.takeScreenshot]
 * （API 30+）。它的授权来自"启用无障碍服务"这个**持久**授权（系统记录，
 * 重启不失效），拍照全程无弹窗、无令牌、不起前台服务。对"家长点一下截屏、
 * 拿一张当前画面"的诉求，这与 MediaProjection 等价，且真正满足"一次授权一直有效"。
 *
 * ## 使用边界（如实声明）
 * - 只服务 [com.padguard.child.capture.ScreenCaptureService] 的**单帧截屏**场景；
 *   实时看屏 / 录屏需要连续帧，仍走 MediaProjection（进程内一次确认后长期复用）。
 * - 系统对连续拍照有限频（[AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT]），
 *   失败即由调用方退回投影链路，不影响功能，只退回旧体验。
 * - 能力依赖无障碍服务元数据里的 `canTakeScreenshot`；若用户在服务已启用**之后**
 *   才升级到带该能力的版本，系统不会重读元数据，[isAvailable] 会探测到能力缺失，
 *   自动退回投影链路（在系统无障碍设置里关闭再打开本服务即可恢复）。
 * - 屏幕关闭/锁定等场景系统可能拒拍，同样退回投影链路。
 *
 * ## 为什么上传逻辑在这里而不在调用方
 * 与 [ScreenCaptureService.doCapture] 保持同一口径：上传失败落盘进
 * [MediaUploadRetry] 重试队列，而不是把帧丢掉。调用方只关心"这一帧拿到没有"。
 */
@Singleton
class AccessibilityScreenshot @Inject constructor(
    private val remote: RemoteDataSource,
    private val mediaRetry: MediaUploadRetry,
    @ApplicationContext private val appContext: Context
) {

    /** 无障碍服务实例（弱引用：服务被系统解绑/销毁后立即失效，不拖长生命周期）。 */
    @Volatile
    private var serviceRef: WeakReference<AccessibilityService>? = null

    /**
     * 通道是否此刻可用：服务已连接、API 30+、且系统确实授予了截屏能力。
     *
     * 能力位必须实探（[AccessibilityServiceInfo.capabilities]）而不是只查实例：
     * 元数据在服务启用之后被改过（覆盖安装升级）时，实例活着但能力不在。
     */
    fun isAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val service = serviceRef?.get() ?: return false
        val caps = runCatching { service.serviceInfo?.capabilities ?: 0 }.getOrDefault(0)
        return caps and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
    }

    /** 服务连接时注册实例（[com.padguard.child.guard.OperationGuardAccessibilityService] 调用）。 */
    fun attach(service: AccessibilityService) {
        serviceRef = WeakReference(service)
        Logger.i(TAG) { "accessibility screenshot channel attached (api=${Build.VERSION.SDK_INT})" }
    }

    /** 服务销毁时注销；传入值必须是当前实例，避免把后到的实例误摘。 */
    fun detach(service: AccessibilityService) {
        if (serviceRef?.get() === service) {
            serviceRef = null
            Logger.i(TAG) { "accessibility screenshot channel detached" }
        }
    }

    /**
     * 采一帧并上传。
     *
     * @return true = 本帧已采集并走完上传/入队流程（调用方**不应**再走投影链路）；
     *         false = 通道不可用或采集失败（调用方退回 MediaProjection 通知引导）。
     */
    suspend fun captureAndUpload(shotId: String): Boolean {
        if (shotId.isBlank()) return false
        if (!isAvailable()) return false
        val jpeg = grabJpeg()
        if (jpeg == null) {
            Logger.w(TAG) { "a11y screenshot unavailable for shot=$shotId, caller falls back to projection" }
            return false
        }
        val capturedAt = System.currentTimeMillis()
        when (val result = remote.uploadScreenshot(shotId, capturedAt, ScreenCaptureService.TRIGGER_REMOTE, jpeg)) {
            is ApiResult.Success ->
                Logger.i(TAG) { "screenshot uploaded via a11y channel: $shotId ${jpeg.size / 1024}KB" }
            else -> {
                // 与投影链路同口径：上传失败不丢帧，落盘移交重试队列
                Logger.w(TAG) { "a11y screenshot upload failed: $result" }
                val tmp = File(appContext.cacheDir, "shot_$shotId.jpg")
                if (runCatching { tmp.writeBytes(jpeg) }.isSuccess) {
                    mediaRetry.enqueue(
                        tmp,
                        MediaUploadRetry.Kind.Screenshot(shotId, capturedAt, ScreenCaptureService.TRIGGER_REMOTE)
                    )
                }
            }
        }
        return true
    }

    /** 取一帧 JPEG；任一步骤失败都返回 null（不抛异常打扰调用方）。 */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun grabJpeg(): ByteArray? {
        val software = grabSoftwareBitmap() ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val baos = ByteArrayOutputStream(256 * 1024)
                if (!software.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)) {
                    Logger.w(TAG) { "jpeg compress returned false" }
                    return@withContext null
                }
                baos.toByteArray()
            } finally {
                software.recycle()
            }
        }
    }

    /**
     * 取一帧并按看屏规格缩放压缩 —— **实时看屏无障碍通道的取帧口**（见 [AccessibilityLiveStreamer]）。
     *
     * 与单帧截屏（[captureAndUpload]）共用同一条系统拍照链路，但帧不落盘、不上传，
     * 只把 JPEG 交给调用方推流。缩放与质量由调用方按当前画质档位指定，
     * 口径与投影推流（effWidth/effQuality）一致，家长端无需区分帧来自哪条通道。
     *
     * @return JPEG 字节；通道不可用/采集失败返回 null，由调用方决定跳帧或降级
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun grabScaledJpeg(maxWidth: Int, quality: Int): ByteArray? {
        if (!isAvailable()) return null
        val software = grabSoftwareBitmap() ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val scale = (maxWidth.toFloat() / software.width).coerceAtMost(1f)
                val src = if (scale < 1f) {
                    val sw = (software.width * scale).toInt().coerceAtLeast(1)
                    val sh = (software.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(software, sw, sh, true)
                } else software
                try {
                    val baos = ByteArrayOutputStream(256 * 1024)
                    if (!src.compress(Bitmap.CompressFormat.JPEG, quality, baos)) {
                        Logger.w(TAG) { "scaled jpeg compress returned false" }
                        return@withContext null
                    }
                    baos.toByteArray()
                } finally {
                    if (src !== software) src.recycle()
                }
            } catch (t: Throwable) {
                Logger.e(TAG, t) { "a11y scaled jpeg failed" }
                null
            } finally {
                software.recycle()
            }
        }
    }

    /**
     * 挂起等待系统拍照并转成**软件位图**（ARGB_8888）。
     * 硬件位图无法直接压缩/缩放，这里统一先 copy 成软件副本；调用方负责 recycle。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun grabSoftwareBitmap(): Bitmap? {
        val service = serviceRef?.get() ?: return null
        val result = runCatching {
            withTimeout(CAPTURE_TIMEOUT_MS) { takeScreenshot(service) }
        }.getOrElse {
            Logger.w(TAG) { "a11y takeScreenshot aborted: ${it.message}" }
            null
        } ?: return null
        return withContext(Dispatchers.IO) { toSoftwareBitmap(result) }
    }

    /**
     * 挂起等待系统拍照结果。
     *
     * 用直执行器递回调：转换在 [grabJpeg] 的 IO 线程做，binder 线程不滞留。
     * [kotlin.coroutines.resume] 而非 resumeWithException：失败是"退回路"的正常分支，
     * 不是异常路径，回调里如实返回 null 即可。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshot(service: AccessibilityService): AccessibilityService.ScreenshotResult? =
        suspendCancellableCoroutine { cont ->
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    if (cont.isActive) cont.resume(screenshot)
                }

                override fun onFailure(errorCode: Int) {
                    Logger.w(TAG) { "a11y takeScreenshot failed, errorCode=$errorCode" }
                    if (cont.isActive) cont.resume(null)
                }
            }
            runCatching { service.takeScreenshot(Display.DEFAULT_DISPLAY, DIRECT_EXECUTOR, callback) }
                .onFailure {
                    Logger.w(TAG) { "takeScreenshot call threw: ${it.message}" }
                    if (cont.isActive) cont.resume(null)
                }
        }

    /**
     * HardwareBuffer → 软件位图（ARGB_8888）。
     *
     * 顺序不能反：先 [Bitmap.wrapHardwareBuffer] 包出硬件位图，立刻
     * [Bitmap.copy] 成 ARGB_8888 软件副本（硬件位图无法直接压缩/缩放），
     * 两张位图都 recycle，最后按官方要求 [HardwareBuffer.close] 释放底层缓冲。
     * 压缩/缩放交给调用方按场景做（单帧截屏 vs 看屏推流规格不同）。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun toSoftwareBitmap(result: AccessibilityService.ScreenshotResult): Bitmap? {
        val buffer: HardwareBuffer = result.hardwareBuffer
        return try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
            if (hardware == null) {
                Logger.w(TAG) { "wrapHardwareBuffer returned null" }
                return null
            }
            try {
                val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
                if (software == null) {
                    Logger.w(TAG) { "software bitmap copy returned null" }
                    return null
                }
                software
            } finally {
                hardware.recycle()
            }
        } catch (t: Throwable) {
            Logger.e(TAG, t) { "a11y screenshot buffer convert failed" }
            null
        } finally {
            runCatching { buffer.close() }
        }
    }

    companion object {
        private const val TAG = "AccessibilityScreenshot"

        /** 拍照等待上限：系统通常在数百毫秒内回调，5s 足够覆盖极端 ROM */
        private const val CAPTURE_TIMEOUT_MS = 5_000L

        /** 与投影链路同档画质（ScreenCaptureService.JPEG_QUALITY 相邻档） */
        private const val JPEG_QUALITY = 85

        /** 直执行器：回调只做 continuation 唤醒，不做重活 */
        private val DIRECT_EXECUTOR = Executor { command -> command.run() }
    }
}
