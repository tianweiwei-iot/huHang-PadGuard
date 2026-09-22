package com.padguard.child.maintain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.padguard.core.common.Logger

/**
 * 安装 / 卸载结果回调。
 *
 * 必须存在的原因：[AppInstaller] 提交会话后不等结果，而"提交成功 ≠ 安装成功"。
 * 若没有这个接收器，孩子端省电策略杀掉进程时我们连 `INSTALL_FAILED_ABORTED`
 * 都拿不到，管控端只会看到指令一直"执行中"。
 *
 * 结果只落日志：真正的结果回执走既有的日志上行通道（[LogType.COMMAND_EXEC]），
 * 不在这里单开一条网络请求 —— 安装失败时网络未必可用，多发一次请求只是多一次失败。
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        when (intent.action) {
            ACTION_INSTALL -> {
                val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    Logger.i(TAG) { "installed $packageName (session=$sessionId)" }
                } else {
                    Logger.w(TAG) {
                        "install failed: $packageName status=$status(${statusName(status)}) $message"
                    }
                }
                // 失败会话必须主动放弃，否则残留的 session 会一直占着 /data 空间
                if (status != PackageInstaller.STATUS_SUCCESS && sessionId >= 0) {
                    runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
                }
            }

            ACTION_UNINSTALL -> {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    Logger.i(TAG) { "uninstalled $packageName" }
                } else {
                    Logger.w(TAG) {
                        "uninstall failed: $packageName status=$status(${statusName(status)}) $message"
                    }
                }
            }
        }
    }

    private fun statusName(status: Int): String = when (status) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> "PENDING_USER_ACTION"
        PackageInstaller.STATUS_SUCCESS -> "SUCCESS"
        PackageInstaller.STATUS_FAILURE -> "FAILURE"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "BLOCKED"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "ABORTED"
        PackageInstaller.STATUS_FAILURE_INVALID -> "INVALID"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "CONFLICT"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "STORAGE"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "INCOMPATIBLE"
        else -> "UNKNOWN"
    }

    companion object {
        private const val TAG = "InstallResultReceiver"

        const val ACTION_INSTALL = "com.padguard.child.action.INSTALL_RESULT"
        const val ACTION_UNINSTALL = "com.padguard.child.action.UNINSTALL_RESULT"

        const val EXTRA_PACKAGE = "packageName"
        const val EXTRA_SESSION_ID = "sessionId"
    }
}
