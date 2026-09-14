package com.padguard.child.bind

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.padguard.child.ui.theme.PadGuardTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 绑定流程宿主 Activity（说明书 §4.2 / §4.3）。
 *
 * 进入时机：MainActivity 检测到未绑定时拉起本 Activity；绑定成功后 finish 回 MainActivity。
 *
 * 本类负责把「扫码 / NFC」两种近场绑定方式解析出的绑定码汇流到 [BindScreen.presetCode]，
 * 由既有 [BindViewModel.submit] 统一提交，避免重复实现绑定逻辑。
 */
@AndroidEntryPoint
class BindActivity : ComponentActivity() {

    /** 扫码 / NFC 解析出的绑定码，透传给 BindScreen 走既有提交流程。 */
    private var scannedCode by mutableStateOf<String?>(null)

    private val qrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            scannedCode = result.data?.getStringExtra(QrScanActivity.EXTRA_CODE)
        }
    }

    private var nfcAdapter: NfcAdapter? = null
    private var nfcPendingIntent: PendingIntent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        nfcPendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        setContent {
            PadGuardTheme {
                BindScreen(
                    onBound = { finish() },
                    presetCode = scannedCode,
                    onScanQr = { qrLauncher.launch(Intent(this, QrScanActivity::class.java)) },
                    onStartNfc = { enableNfcReaderMode() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 前台调度：NFC 标签贴近时本 Activity 直接收到，而非弹出系统选择器
        nfcPendingIntent?.let { pi ->
            nfcAdapter?.enableForegroundDispatch(this, pi, NFC_FILTERS, null)
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 系统通过 SINGLE_TOP 把 NFC intent 交给已存在的 Activity
        val code = parseNfcIntent(intent)
        if (!code.isNullOrBlank()) scannedCode = code
    }

    private fun enableNfcReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return
        adapter.enableReaderMode(
            this,
            { tag -> runOnUiThread { parseTag(tag)?.let { scannedCode = it } } },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B,
            null
        )
    }

    /** 解析前台调度拿到的 NFC intent（NDEF 记录里的 padguard://bind?code=...）。 */
    private fun parseNfcIntent(intent: Intent): String? {
        val raw = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return null
        for (msg in raw) {
            (msg as? NdefMessage)?.records?.forEach { record ->
                val code = extractCodeFromPayload(String(record.payload, Charsets.UTF_8))
                if (!code.isNullOrBlank()) return code
            }
        }
        return null
    }

    /** 解析 reader mode 拿到的 Tag。 */
    private fun parseTag(tag: Tag): String? {
        val ndef = runCatching { Ndef.get(tag) }.getOrNull() ?: return null
        val code = runCatching {
            ndef.connect()
            val msgs = ndef.ndefMessage?.records
            ndef.close()
            msgs?.firstNotNullOfOrNull { r ->
                extractCodeFromPayload(String(r.payload, Charsets.UTF_8))
            }
        }.getOrNull()
        return code
    }

    /** 从 NDEF 文本负载里取出绑定码：支持 `padguard://bind?code=XXX` 与裸码。 */
    private fun extractCodeFromPayload(payload: String): String? {
        // NDEF 文本记录前缀可能带语言码/状态码字节，先去掉不可打印前缀
        val text = payload.replace(Regex("^[^a-zA-Z0-9:/?=&]+"), "").trim()
        if (text.startsWith("padguard://")) {
            val code = android.net.Uri.parse(text).getQueryParameter("code")
            return code
        }
        // 裸绑定码（如家长端写入的纯 6 位码）
        val bare = text.filter { it.isLetterOrDigit() }
        return if (bare.length in 6..8) bare else null
    }

    companion object {
        private val NFC_FILTERS = arrayOf(
            IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                try { addDataType("*/*") } catch (_: IntentFilter.MalformedMimeTypeException) {}
            }
        )
    }
}
