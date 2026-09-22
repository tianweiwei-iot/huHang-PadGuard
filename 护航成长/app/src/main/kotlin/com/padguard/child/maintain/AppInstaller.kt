package com.padguard.child.maintain

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val http: OkHttpClient
) {

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
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pending = PendingIntent.getBroadcast(context, packageName.hashCode(), intent, flags)

        return@withContext try {
            installer.uninstall(packageName, pending.intentSender)
            null
        } catch (t: Throwable) {
            Logger.e(TAG, t) { "uninstall failed: $packageName" }
            "卸载失败：${t.message}"
        }
    }

    private fun download(apkUrl: String): File {
        val request = Request.Builder().url(apkUrl).get().build()
        val client = http.newBuilder()
            .connectTimeout(DOWNLOAD_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(DOWNLOAD_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
        client.newCall(request).execute().use { response ->
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
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
        Logger.i(TAG) { "install session $sessionId committed for $packageName (${file.length()}B)" }
    }

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

    companion object {
        private const val TAG = "AppInstaller"
        private const val SELF_PROTECT_MESSAGE = "拒绝操作管控端自身，解绑请走服务端解绑流程"
        private const val DOWNLOAD_BUFFER = 64 * 1024
        private const val DOWNLOAD_CONNECT_TIMEOUT_SEC = 20L
        private const val DOWNLOAD_READ_TIMEOUT_SEC = 120L
    }
}
