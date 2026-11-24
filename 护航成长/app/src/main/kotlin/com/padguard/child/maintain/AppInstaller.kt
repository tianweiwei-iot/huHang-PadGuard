package com.padguard.child.maintain

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.padguard.core.common.Logger
import com.padguard.core.transport.TransportSettings
import com.padguard.core.transport.http.AuthInterceptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远程安装 / 卸载（Device Owner 静默通道）。
 *
 * ## 为什么用 PackageInstaller 而不是 `Intent.ACTION_INSTALL_PACKAGE`
 * 后者一定会弹"是否安装此应用"确认框，孩子点一下"取消"指令就落空了，
 * 而且 Android 10+ 对后台发起安装 Intent 有额外限制。
 * [PackageInstaller] 会话在 Device Owner / Profile Owner 下走**静默通道**，
 * 提交即完成，无交互、可拿到明确的失败码，是唯一可靠的工程解。
 *
 * ## 下载为什么要落盘再装
 * [PackageInstaller.Session] 只接受流写入，不支持"边下边装"的重试。
 * 先完整落盘可以在写入会话前校验文件大小与 `package` 头，
 * 避免把一个半截 APK 提交给系统（那会得到一个毫无信息量的 `INSTALL_FAILED_INVALID_APK`）。
 *
 * ## 卸载的自我保护
 * 卸载自身会让设备彻底脱管，且 DO 身份下无法自行恢复。这里硬编码拒绝管控端包名，
 * 即使上层逻辑被绕过也拦得住 —— 安全边界不该只依赖调用方自觉。
 */
@Singleton
class AppInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: TransportSettings,
    private val authInterceptor: AuthInterceptor
) {
    /**
     * 专用下载客户端：只挂鉴权拦截器，刻意避开 [com.padguard.core.transport.http.HostSelectionInterceptor]。
     *
     * 后者会把相对路径拼到 baseUrl 上，而 APK 下载地址 `/v1/files/{id}` 是服务端根路径，
     * 一旦 baseUrl 带 `/v1` 前缀就会出现 `/v1/v1/files/{id}` 重复前缀导致 404。
     * 这里由 [normalizeApkUrl] 直接拼出绝对 origin 地址，不经过 host 重写。
     */
    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .connectTimeout(DOWNLOAD_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    /**
     * 下载并安装。
     *
     * @return `null` 表示成功，否则返回失败原因字符串（用于回执与日志）
     */
    suspend fun install(apkUrl: String, packageName: String): String? = withContext(Dispatchers.IO) {
        // 覆盖安装自身是 Device Owner 场景下唯一可行的远程升级通道：
        // 孩子不会主动更新，U 盘/应用市场都不适用于管控设备。
        // PackageInstaller 会拒绝 versionCode 更低的包，天然防降级，无需额外校验。
        val file = runCatching { download(apkUrl) }.getOrElse {
            Logger.e(TAG, it) { "apk download failed: $apkUrl" }
            return@withContext "下载失败：${it.message}"
        }
        // 落盘后、提交安装前做 fail-closed 校验（包名一致 + 自身升级签名一致），
        // 防止下载链路被劫持/替换后，把冒名或伪造的 APK 以受信包名静默装上。
        val verifyError = verifyApk(file, packageName)
        if (verifyError != null) {
            Logger.e(TAG) { "apk verification rejected: $verifyError ($packageName)" }
            runCatching { file.delete() }
            return@withContext verifyError
        }
        try {
            commit(file, packageName)
            null
        } catch (t: Throwable) {
            Logger.e(TAG, t) { "install failed: $packageName" }
            "安装失败：${t.message}"
        } finally {
            runCatching { file.delete() }
        }
    }

    /** 静默卸载。@return `null` 表示提交成功 */
    suspend fun uninstall(packageName: String): String? = withContext(Dispatchers.IO) {
        if (packageName == context.packageName) return@withContext SELF_PROTECT_MESSAGE
        val installer = context.packageManager.packageInstaller
        val intent = Intent(context, InstallResultReceiver::class.java)
            .setAction(InstallResultReceiver.ACTION_UNINSTALL)
            .putExtra(InstallResultReceiver.EXTRA_PACKAGE, packageName)
        val pending = PendingIntent.getBroadcast(
            context, packageName.hashCode(), intent, statusReceiverFlags()
        )

        return@withContext try {
            installer.uninstall(packageName, pending.intentSender)
            null
        } catch (t: Throwable) {
            Logger.e(TAG, t) { "uninstall failed: $packageName" }
            "卸载失败：${t.message}"
        }
    }

    private fun download(apkUrl: String): File {
        val url = normalizeApkUrl(apkUrl, settings.baseUrl.value)
        val request = Request.Builder().url(url).get().build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("empty body")
            val out = File(context.cacheDir, "push_${System.currentTimeMillis()}.apk")
            FileOutputStream(out).use { sink ->
                body.byteStream().use { source -> source.copyTo(sink, DOWNLOAD_BUFFER) }
            }
            if (out.length() <= 0) {
                runCatching { out.delete() }
                error("downloaded apk is empty")
            }
            return out
        }
    }

    /**
     * 把指令里的 apkUrl 规整成可直接下载的绝对地址。
     *
     * 服务端 [com.padguard.server.service.FileStorageService] 默认返回相对路径 `/v1/files/{id}`
     * （`padguard.base-url` 留空时）；若部署时 `base-url` 配置不当（带 `/v1` 后缀）会拼出
     * `/v1/v1/files/{id}` 这种重复前缀，直接 404。这里统一按本机接入点的 origin 重拼，
     * 既兼容相对路径，也兜底修正重复前缀，使下发地址不再依赖服务端 base-url 配置。
     */
    private fun normalizeApkUrl(raw: String, baseUrl: String): String {
        val trimmed = raw.trim()
        val origin = baseUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }
            ?: run {
                Logger.i(TAG) { "baseUrl 非法，无法规整 apkUrl: $baseUrl" }
                return trimmed
            }
        // 绝对地址：抽取路径，修正 /v1/v1/ 重复前缀后按 origin 重拼
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val u = trimmed.toHttpUrlOrNull() ?: return trimmed
            val path = u.encodedPath.replace("/v1/v1/", "/v1/")
            val query = if (u.encodedQuery.isNullOrBlank()) "" else "?${u.encodedQuery}"
            return "$origin$path$query"
        }
        // 相对路径（以 / 开头）：直接按 origin 拼
        return origin + trimmed
    }

    /**
     * 用 PackageInstaller 会话提交安装。
     *
     * 结果通过广播异步回来（[InstallResultReceiver]）——
     * 这里只保证"会话已成功提交"，不阻塞等安装完成：
     * 安装大 APK 可能要几十秒，挂在这里会让副作用流被卡住，后续指令全部堆积。
     */
    private fun commit(file: File, packageName: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("WrongConstant")
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("padguard_push", 0, file.length()).use { output ->
                    input.copyTo(output, DOWNLOAD_BUFFER)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
                .setAction(InstallResultReceiver.ACTION_INSTALL)
                .putExtra(InstallResultReceiver.EXTRA_PACKAGE, packageName)
                .putExtra(InstallResultReceiver.EXTRA_SESSION_ID, sessionId)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, statusReceiverFlags())
            session.commit(pending.intentSender)
        }
        Logger.i(TAG) { "install session $sessionId committed for $packageName (${file.length()}B)" }
    }

    /**
     * PackageInstaller 的 statusReceiver 必须**可变**（R27）。
     *
     * 系统是以 `IntentSender.sendIntent()` + fillIn 的方式，把 `EXTRA_STATUS` /
     * `EXTRA_STATUS_MESSAGE` / `EXTRA_SESSION_ID` 回填到我们创建的 PendingIntent 上。
     * 若沿用全项目通用的 `FLAG_IMMUTABLE`，这些 extras 会被丢弃：
     * - [InstallResultReceiver] 恒读到默认的 `STATUS_FAILURE`，每次安装都被误记失败，
     *   还会对其实已成功的会话调用 `abandonSession`；
     * - Android 15+（本仓 targetSdk 35）对 statusReceiver 有硬性校验，
     *   `session.commit()` 直接抛 `IllegalArgumentException`，远程静默安装 / 自升级在提交阶段全废。
     *
     * 这里两处 Intent 都是指向自身 `exported=false` 接收器的**显式** Intent，
     * IntentSender 只交给 system_server，改 mutable 不扩大攻击面；
     * 其余 PendingIntent 一律保持 immutable、不走本方法。
     */
    private fun statusReceiverFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)

    /** 目标包是否已安装，供回执与日志判断"安装是否真的生效" */
    fun isInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        true
    }.getOrDefault(false)

    /**
     * 安装前完整性校验（fail-closed：任何不一致都拒绝安装）。
     *
     * 1. 包名一致性：下载下来的 APK 真实包名必须等于请求安装的包名，
     *    防止下载链路被劫持/替换后，把一个别的 APK 以受信包名静默装上。
     * 2. 自身升级：APK 的签名证书必须与本机已装版本一致，
     *    防止用不同密钥签名的木马冒名升级管控端（系统升级本就拒签不同，这里双保险且覆盖首装异常路径）。
     *
     * 第三方受管控应用的静默推送：服务端已鉴权下发，此处仅做包名一致性校验；
     * 若未来引入"可信公钥白名单"，应在此扩展——但绝不把未知签名的任意 APK 当可信。
     *
     * @return null 表示校验通过；否则返回失败原因（用于回执与日志）
     */
    private fun verifyApk(file: File, requestedPkg: String): String? {
        // GET_SIGNATURES 自 API 1 起可用、在所有版本（含 33+）都能拿到签名证书，
        // 故统一用它而非 API 28+ 才有的 GET_SIGNING_CERTIFICATES / SigningInfo，
        // 避免对低版本 minSdk(26) 引入 NewApi 依赖（见 signaturesMatch 注释）。
        @Suppress("DEPRECATION")
        val sigFlag = PackageManager.GET_SIGNATURES
        val archive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(sigFlag.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, sigFlag)
        } ?: return "APK 无法解析或已损坏"

        if (archive.packageName != requestedPkg) {
            return "APK 包名(${archive.packageName})与请求($requestedPkg)不一致，疑似被篡改"
        }

        if (requestedPkg == context.packageName) {
            val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching {
                    context.packageManager.getPackageInfo(
                        requestedPkg,
                        PackageManager.PackageInfoFlags.of(sigFlag.toLong())
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                runCatching { context.packageManager.getPackageInfo(requestedPkg, sigFlag) }
            }.getOrNull() ?: return null // 本机未装自身（极端首装）无法比对，放行交系统校验
            if (!signaturesMatch(installed, archive)) {
                return "升级包签名证书与已安装版本不一致，疑似伪造升级包"
            }
        }
        return null
    }

    /**
     * 比较两个 [PackageInfo] 的签名证书集合（顺序无关），一致返回 true。
     *
     * 用 [PackageInfo.signatures]（[PackageManager.GET_SIGNATURES]，全版本可用）而非
     * API 28+ 的 [android.content.pm.SigningInfo]，以兼容 minSdk 26。该字段虽被标记
     * 为弃用，但在 28+ 仍有效返回签名证书，是跨版本最稳的取法；弃用警告由本函数抑制。
     */
    @Suppress("DEPRECATION")
    private fun signaturesMatch(installed: PackageInfo, archive: PackageInfo): Boolean {
        val a: Array<Signature> = installed.signatures ?: return false
        val b: Array<Signature> = archive.signatures ?: return false
        val setA = a.map { it.toByteArray().contentToString() }.toSet()
        val setB = b.map { it.toByteArray().contentToString() }.toSet()
        return setA == setB
    }

    companion object {
        private const val TAG = "AppInstaller"
        private const val SELF_PROTECT_MESSAGE = "拒绝操作管控端自身，解绑请走服务端解绑流程"
        private const val DOWNLOAD_BUFFER = 64 * 1024
        private const val DOWNLOAD_CONNECT_TIMEOUT_SEC = 20L
        private const val DOWNLOAD_READ_TIMEOUT_SEC = 120L
    }
}
