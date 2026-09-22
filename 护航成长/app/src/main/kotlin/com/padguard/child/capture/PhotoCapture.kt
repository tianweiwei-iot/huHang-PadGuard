package com.padguard.child.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import com.padguard.core.common.Logger
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.transport.http.ApiResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 远程拍照（家长端"看一眼周围环境"）。
 *
 * ## 为什么不用 CameraX
 * CameraX 的 `ImageCapture` 必须绑定 Lifecycle，而这里是从前台服务的指令流里被触发的，
 * 没有 Activity 也没有 LifecycleOwner。为用它而造一个假 LifecycleOwner，
 * 一旦与真实页面生命周期错位就会出现"相机被占用 / 黑图"，属于典型的自找麻烦。
 * Camera2 虽然啰嗦，但完全自持，不依赖任何 UI 上下文。
 *
 * ## 不显示预览，但要"假装"有预览
 * 静默拍照是需求本身的语义：弹预览就等于告诉孩子"你在被拍"，场景立刻失真。
 * 代价是相机没有稳定期。这里的做法是开一条**只写进 YUV ImageReader 的重复请求**
 * （画面不显示在任何地方，仅驱动 3A 收敛），等 AE/AF 收敛后再拍一张 JPEG。
 * 少了这一步，在光线突变场景（息屏刚点亮、从暗处拿到亮处）会得到全黑或过曝的废图。
 *
 * ## 资源与超时
 * 相机是全局独占资源，任何异常路径都必须关闭，否则后续所有拍照指令都会
 * `CameraInUseException`。整体带硬超时，避免"相机被别的进程占着"时协程永久挂起
 * —— 那会堵住副作用流，让后续指令全部堆积。
 */
@Singleton
class PhotoCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remote: RemoteDataSource
) {

    /** 拍照并上传。@return 失败原因，`null` 表示成功 */
    suspend fun captureAndUpload(
        taskId: String,
        facing: Int = CameraCharacteristics.LENS_FACING_FRONT
    ): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "未授予相机权限，无法拍照"
        }

        return withContext(Dispatchers.IO) {
            val thread = HandlerThread("padguard-camera").apply { start() }
            val handler = Handler(thread.looper)
            var camera: CameraDevice? = null
            var session: CameraCaptureSession? = null
            var previewReader: ImageReader? = null
            var jpegReader: ImageReader? = null

            try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                    ?: return@withContext "设备无相机服务"
                val cameraId = pickCamera(manager, facing) ?: return@withContext "未找到可用摄像头"

                val map = manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return@withContext "相机不支持标准流配置"

                val jpegSize = map.getOutputSizes(ImageFormat.JPEG)
                    ?.maxByOrNull { it.width * it.height }
                    ?: return@withContext "相机不支持 JPEG 输出"
                val previewSize = map.getOutputSizes(ImageFormat.YUV_420_888)
                    ?.minByOrNull { it.width * it.height }
                    ?: Size(PREVIEW_FALLBACK_W, PREVIEW_FALLBACK_H)

                jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
                previewReader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2)

                camera = openCamera(manager, cameraId, handler)
                    ?: return@withContext "相机打开失败（可能被其它应用占用）"
                session = createSession(
                    camera, listOf(previewReader.surface, jpegReader.surface), handler
                ) ?: return@withContext "相机会话创建失败"

                val jpeg = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                    shoot(session!!, camera, previewReader.surface, jpegReader!!, handler)
                } ?: return@withContext "拍照超时（${CAPTURE_TIMEOUT_MS}ms）"

                val file = File(context.cacheDir, "photo_$taskId.jpg")
                FileOutputStream(file).use { it.write(jpeg) }
                val error = when (val r = remote.uploadMedia(taskId, 0, MIME_IMAGE_JPEG, file)) {
                    is ApiResult.Success -> {
                        Logger.i(TAG) { "photo uploaded: $taskId ${file.length() / 1024}KB" }
                        null
                    }
                    else -> {
                        Logger.w(TAG) { "photo upload failed: $r" }
                        "拍照成功但上传失败"
                    }
                }
                runCatching { file.delete() }
                error
            } catch (t: Throwable) {
                Logger.e(TAG, t) { "photo capture failed" }
                "拍照失败：${t.message}"
            } finally {
                runCatching { session?.close() }
                runCatching { camera?.close() }
                runCatching { jpegReader?.close() }
                runCatching { previewReader?.close() }
                runCatching { thread.quitSafely() }
            }
        }
    }

    // ==================== Camera2 流程 ====================

    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler
    ): CameraDevice? = suspendCancellableCoroutine { cont ->
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!cont.isCompleted) cont.resume(device)
                }

                override fun onDisconnected(device: CameraDevice) {
                    runCatching { device.close() }
                    if (!cont.isCompleted) cont.resume(null)
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Logger.w(TAG) { "camera error=$error" }
                    runCatching { device.close() }
                    if (!cont.isCompleted) cont.resume(null)
                }
            }, handler)
        } catch (t: Throwable) {
            Logger.e(TAG, t) { "openCamera threw" }
            cont.resume(null)
        }
    }

    private suspend fun createSession(
        camera: CameraDevice,
        targets: List<Surface>,
        handler: Handler
    ): CameraCaptureSession? = suspendCancellableCoroutine { cont ->
        try {
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (!cont.isCompleted) cont.resume(s)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    runCatching { s.close() }
                    if (!cont.isCompleted) cont.resume(null)
                }
            }, handler)
        } catch (t: Throwable) {
            Logger.e(TAG, t) { "createCaptureSession threw" }
            cont.resume(null)
        }
    }

    /**
     * 3A 收敛 -> 拍一张 JPEG。
     *
     * 重复请求只打在 [previewSurface] 上，因此收敛期间不会产生 JPEG；
     * [jpegReader] 只会在最后的 still capture 上收到一帧，语义干净。
     */
    private suspend fun shoot(
        session: CameraCaptureSession,
        camera: CameraDevice,
        previewSurface: Surface,
        jpegReader: ImageReader,
        handler: Handler
    ): ByteArray? {
        val frame = CompletableDeferred<ByteArray?>()
        val converged = CompletableDeferred<Unit>()

        jpegReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                if (!frame.isCompleted) frame.complete(bytes)
            } catch (t: Throwable) {
                Logger.e(TAG, t) { "jpeg read failed" }
                if (!frame.isCompleted) frame.complete(null)
            } finally {
                image.close()
            }
        }, handler)

        val aeCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                val af = result.get(CaptureResult.CONTROL_AF_STATE)
                val aeReady = ae == null || ae == CameraCharacteristics.CONTROL_AE_STATE_CONVERGED ||
                    ae == CameraCharacteristics.CONTROL_AE_STATE_FLASH_REQUIRED
                val afReady = af == null || af == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    af == CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                    af == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                if (aeReady && afReady && !converged.isCompleted) converged.complete(Unit)
            }
        }

        return try {
            val preview = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            session.setRepeatingRequest(preview.build(), aeCallback, handler)

            // 收敛等不到也要继续拍：宁可成像差一点，也不能让家长端一直转圈。
            // 环境极暗时 AE 可能长时间不收敛，这里超时后按当前参数曝光。
            withTimeoutOrNull(AE_TIMEOUT_MS) { converged.await() }

            val still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpegReader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.JPEG_ORIENTATION, 0)
            }
            session.capture(still.build(), null, handler)
            withTimeoutOrNull(JPEG_TIMEOUT_MS) { frame.await() }
        } finally {
            runCatching { session.stopRepeating() }
            jpegReader.setOnImageAvailableListener(null, null)
        }
    }

    /**
     * 选摄像头。指定朝向不存在时（很多平板只有后置）退回第一个可用摄像头，
     * 而不是直接失败 —— 家长要的是"看一眼环境"，前后置不是关键。
     */
    private fun pickCamera(manager: CameraManager, facing: Int): String? {
        for (id in manager.cameraIdList) {
            val lens = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (lens == facing) return id
        }
        return manager.cameraIdList.firstOrNull()
    }

    companion object {
        private const val TAG = "PhotoCapture"
        private const val MIME_IMAGE_JPEG = "image/jpeg"

        /** 全流程硬超时，防相机被占用时协程永久挂起 */
        private const val CAPTURE_TIMEOUT_MS = 10_000L
        private const val AE_TIMEOUT_MS = 3_000L
        private const val JPEG_TIMEOUT_MS = 6_000L

        private const val PREVIEW_FALLBACK_W = 640
        private const val PREVIEW_FALLBACK_H = 480
    }
}
