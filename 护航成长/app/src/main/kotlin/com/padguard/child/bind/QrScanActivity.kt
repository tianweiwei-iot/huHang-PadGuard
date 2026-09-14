package com.padguard.child.bind

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
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
 */
@AndroidEntryPoint
class QrScanActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PadGuardTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ScanScreen(
                        onCodeScanned = { code -> finishWithCode(code) },
                        onClose = { finish() }
                    )
                }
            }
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
private fun ScanScreen(onCodeScanned: (String) -> Unit, onClose: () -> Unit) {
    androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("扫码绑定") },
            navigationIcon = {
                IconButton(onClick = onClose) { Text("✕") }
            }
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CameraPreview(onCodeScanned = onCodeScanned)
        }
    }
}

@Composable
private fun CameraPreview(onCodeScanned: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember { BarcodeScanning.getClient() }
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

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener(
                {
                    val cameraProvider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(Executors.newSingleThreadExecutor(), analyzer) }
                    runCatching {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    }
                },
                ContextCompat.getMainExecutor(ctx)
            )
            previewView
        }
    )
}
