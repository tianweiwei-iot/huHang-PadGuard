package com.padguard.core.transport.security

import android.content.Context
import android.util.Base64
import com.padguard.core.common.Logger
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

/**
 * 证书固定（Certificate Pinning），阻断「导入自定义 CA 的主动中间人」（Fiddler / Charles 可解密观测流量）。
 *
 * 受管控端指令与上报内容靠 HMAC 保证**完整性**（篡改即失效），但**机密性**此前只依赖 TLS +
 * 系统信任链——具备自定义 CA 的主动 MITM 虽无法伪造指令，却可观测明文行为画像。
 * 本类把「对端证书」也锁死，使主动 MITM 直接握手失败、断链。
 *
 * ## 两层策略
 * 1. **静态固定**：[staticPins] 非空时严格生效（SPKI SHA-256，格式 `sha256/<base64>`）。
 *    运维用 openssl 取出生产证书哈希写入（见运维手册），证书轮换时同步更新即可，最稳。
 * 2. **TOFU 兜底**：未配置静态 pin 时，首次成功握手（绑定发生在家长可控的可信网络）的证书公钥被记下；
 *    之后若证书更换但**签发者(DN)不变**（= 证书正常轮换）则自动重新记录；
 *    若签发者变了（= 可能的中间人）则拒绝连接。
 *    pin 落盘于 `filesDir/tofu_pins.json`：应用更新 / 重启后仍在；清数据 / 卸载后失效（属预期）。
 *
 * 无论哪一层，都**先过系统 CA 校验**——随机 / 过期 / 自签证书先被拒，只把「合法证书被替换」这一类交给 pin 兜底，
 * 因此不会因固定逻辑误伤正常公网证书。
 */
class CertPinTrustManager(
    private val staticPins: Set<String>,
    private val tofuFile: File
) : X509TrustManager {

    private val systemTm: X509TrustManager
    /** TOFU 文件存在但内容损坏：必须 fail-closed，绝不能当作"无记录"走首次信任。 */
    private var tofuCorrupt = false
    private val tofu = loadTofu(tofuFile)

    init {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as java.security.KeyStore?)
        systemTm = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = systemTm.getAcceptedIssuers()

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        systemTm.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        // 1) 先过系统 CA：只接受真实有效的证书链，把无效证书挡在 pin 之外。
        systemTm.checkServerTrusted(chain, authType)

        // 2) 再做 pin 校验。
        val leaf = chain[0]
        val spki = spkiSha256(leaf)
        val issuer = leaf.issuerX500Principal?.name ?: ""

        if (staticPins.isNotEmpty()) {
            if (!staticPins.contains("sha256/$spki")) {
                throw CertificateException(
                    "certificate pin mismatch (static); none of ${staticPins.size} pinned certs matched"
                )
            }
            return
        }

        // 3) TOFU：首次信任 + 同签发者轮换豁免。
        synchronized(tofu) {
            // 文件存在但损坏时 loadTofu 会置位：此时若当作"无记录"落到首次信任分支，
            // 任意由系统 CA 签发的证书（含用户导入的 MITM 根 CA）都会被无条件记录为可信，
            // TOFU 被绕过。必须 fail-closed —— 宁可断链，不可静默放行。
            if (tofuCorrupt) {
                throw CertificateException("tofu store corrupt; fail-closed (re-install to rebuild trust)")
            }
            val prev = tofu[issuer]
            when {
                prev == null && tofu.isEmpty() -> {
                    // 首次建立信任：记下该签发者维度的公钥。
                    tofu[issuer] = spki
                    saveTofu()
                    Logger.i(TAG) { "tofu: first trust captured for issuer=$issuer" }
                }
                prev == null -> {
                    // 已信任过部分签发者，却出现陌生签发者 → 疑似中间人，拒绝。
                    throw CertificateException("tofu: unknown issuer=$issuer (possible MITM)")
                }
                prev != spki -> {
                    // 同签发者、公钥变了 = 证书轮换，重新记录（不阻断连接）。
                    tofu[issuer] = spki
                    saveTofu()
                    Logger.i(TAG) { "tofu: cert rotated for issuer=$issuer, re-pinned" }
                }
                // prev == spki：完全一致，放行。
            }
        }
    }

    private fun spkiSha256(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private fun loadTofu(file: File): MutableMap<String, String> {
        if (!file.isFile) return mutableMapOf()
        return runCatching {
            val obj = JSONObject(file.readText())
            obj.keys().asSequence().associateTo(mutableMapOf()) { it to obj.getString(it) }
        }.getOrElse {
            // 文件在但读/解析失败：置损坏标志，由 checkServerTrusted fail-closed。
            // 绝不能 getOrDefault(emptyMap()) —— 那会与"首次安装"不可区分，导致 TOFU 被绕过。
            tofuCorrupt = true
            Logger.w(TAG) { "tofu store unreadable, will fail-closed: ${it.message}" }
            mutableMapOf()
        }
    }

    private fun saveTofu() {
        runCatching {
            val obj = JSONObject()
            tofu.forEach { (k, v) -> obj.put(k, v) }
            // 原子写：先写同目录临时文件再 rename 覆盖。writeText 直接覆写时若在写一半
            // 进程被杀，文件半截 → loadTofu 解析失败 → 旧实现 fail-open 放过任意证书。
            // 与项目 JsonLinesFile 的落盘纪律一致。
            val tmp = File(tofuFile.absolutePath + ".tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(tofuFile)) {
                tmp.delete()
                throw IllegalStateException("tofu atomic rename failed")
            }
        }.onFailure { Logger.w(TAG) { "tofu save failed: ${it.message}" } }
    }

    companion object {
        private const val TAG = "CertPin"

        fun parsePins(raw: String): Set<String> =
            raw.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()

        /** 构造「系统校验 + 固定」的 SSLContext，供 OkHttp / Paho 共用。 */
        fun sslContext(context: Context, staticPins: Set<String>): SSLContext {
            val tm = CertPinTrustManager(staticPins, File(context.filesDir, "tofu_pins.json"))
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), null)
            return ctx
        }

        /** OkHttp 要求同时传入 SocketFactory 与 TrustManager，故成对返回。 */
        fun forOkHttp(
            context: Context,
            staticPins: Set<String>
        ): Pair<javax.net.ssl.SSLSocketFactory, X509TrustManager> {
            val tm = CertPinTrustManager(staticPins, File(context.filesDir, "tofu_pins.json"))
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), null)
            return ctx.socketFactory to tm
        }
    }
}
