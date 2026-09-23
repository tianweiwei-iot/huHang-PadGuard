package com.padguard.child.bind

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.padguard.child.ui.theme.PadGuardTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.Executors

/**
 * 扫码绑定页（说明书 §4.3 方式①）。
 *
 * 用 CameraX 取景 + MLKit `barcode-scanning` 解析二维码，
 * 支持家长端下发的 `padguard://bind?code=XXX` 以及裸绑定码。
 * 解析成功后以 [EXTRA_CODE] 回传，由 [BindActivity] 汇流到既有提交流程。
 *
 * ## 必须在这里动态申请相机权限
 * `AndroidManifest.xml` 里声明 `<uses-permission android:name="android.permission.CAMERA" />`
 * 只是"告知系统与应用商店"，**Android 6.0+ 真正能否打开摄像头取决于运行时授权**。
 * 此前本页直接 `ProcessCameraProvider.bindToLifecycle(...)`，未授权时抛
 * `SecurityException`，又被 `runCatching` 静默吞掉 —— 表现就是"点了扫码没反应、
 * 摄像头完全没被调用"。现在改为：先申请权限，未授权给出明确提示与补救入口，
 * 且相机启动失败时把错误显示出来，不再静默失败。
 */
@AndroidEntryPoint
class QrScanActivity : ComponentActivity() {

    /** 已通过 `rememberLauncher` 之外的方式在 Activity 层管理，便于权限回调直接驱动重组 */
    private var cameraGranted by mutableStateOf(false)

    /** 用户勾选了"不再询问"：此时再弹系统授权框无效，只能引导去设置页 */
    private var deniedPermanently by mutableStateOf(false)

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        deniedPermanently = !granted &&
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        if (!granted) {
            Toast.makeText(
                this,
                if (deniedPermanently) "相机权限已被永久拒绝，请在系统设置中开启" else "需要相机权限才能扫码",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
        setContent {
            PadGuardTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ScanScreen(
                        cameraGranted = cameraGranted,
                        deniedPermanently = deniedPermanently,
                        onRequestPermission = { requestCamera.launch(Manifest.permission.CAMERA) },
                        onOpenSettings = { openAppSettings() },
                        onCodeScanned = { code -> finishWithCode(code) },
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        }.onFailure {
            Toast.makeText(this, "无法打开设置页，请手动在系统设置中开启相机权限", Toast.LENGTH_LONG).show()
        }
    }

    private fun finishWithCode(code: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, code))
        finish()
    }

    companion object {
        const val EXTRA_CODE = "code"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanScreen(
    cameraGranted: Boolean,
    deniedPermanently: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onCodeScanned: (String) -> Unit,
    onClose: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("扫码绑定") },
            navigationIcon = {
                IconButton(onClick = onClose) { Text("✕") }
            }
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (cameraGranted) {
                CameraPreview(onCodeScanned = onCodeScanned)
            } else {
                CameraPermissionPlaceholder(
                    deniedPermanently = deniedPermanently,
                    onRequestPermission = onRequestPermission,
                    onOpenSettings = onOpenSettings,
                    onClose = onClose
                )
            }
        }
    }
}

/** 未授权时的兜底界面：说明原因 + 给出补救入口，避免"点了没反应"。 */
@Composable
private fun CameraPermissionPlaceholder(
    deniedPermanently: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = if (deniedPermanently) "相机权限已被拒绝" else "需要相机权限",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = if (deniedPermanently) {
                "请在系统设置中允许本应用使用相机后，再回来扫码。\n也可以返回改用「输入绑定码」。"
            } else {
                "扫码绑定需要调用摄像头来识别家长端的二维码。\n也可以返回改用「输入绑定码」。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        if (deniedPermanently) {
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("去系统设置开启")
            }
        } else {
            Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                Text("授予相机权限")
            }
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("返回，改用输入绑定码")
        }
    }
}

@Composable
private fun CameraPreview(onCodeScanned: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember { BarcodeScanning.getClient() }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val analyzer = remember {
        object : ImageAnalysis.Analyzer {
            override fun analyze(image: ImageProxy) {
                val mediaImage = image.image
                if (mediaImage != null) {
                    val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
                    scanner.process(input)
                        .addOnSuccessListener { barcodes ->
                            barcodes.firstOrNull()?.rawValue?.let { onCodeScanned(it) }
                        }
                        .addOnCompleteListener { image.close() }
                } else {
                    image.close()
                }
            }
        }
    }

    if (errorMessage != null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "摄像头启动失败", style = MaterialTheme.typography.titleMedium)
            Text(
                text = errorMessage ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener(
                {
                    val cameraProvider = runCatching { future.get() }.getOrNull()
                    if (cameraProvider == null) {
                        errorMessage = "无法获取相机服务，请重启应用后重试"
                        return@addListener
                    }
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(Executors.newSingleThreadExecutor(), analyzer) }
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    } catch (t: Throwable) {
                        // 不再静默吞掉：把原因暴露给用户，否则只会表现为"扫码没反应"
                        errorMessage = when (t) {
                            is SecurityException -> "没有相机权限，请在系统设置中开启"
                            else -> t.message ?: "未知错误"
                        }
                    }
                },
                ContextCompat.getMainExecutor(ctx)
            )
            previewView
        }
    )
}
