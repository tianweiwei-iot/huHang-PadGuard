package com.padguard.child.monitor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.padguard.core.common.Logger
import com.padguard.core.transport.http.AppInventoryItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 已安装应用台账采集（应用监控 / 远程运维的数据源）。
 *
 * ## 为什么不再依赖 QUERY_ALL_PACKAGES
 * Android 11+ 限制包可见性：默认只能看到自己和系统级可见的少量应用。
 * 两种合规做法：① 声明 `QUERY_ALL_PACKAGES`（应用市场上架会被重点审核）；
 * ② 在 manifest 里用 `<queries>` 声明用途。
 *
 * 本项目**不需要**看到全部应用：管控真正关心的是"孩子能打开的、带启动器的应用"，
 * 系统后台服务、无图标组件对家长毫无意义且会把列表撑到几百条不可用。
 * 因此这里用 `MATCH_UNINSTALLED_PACKAGES` 之外的方式：直接取
 * [PackageManager.getInstalledPackages] 后**只保留有启动入口的应用**。
 *
 * ## 性能
 * `getInstalledPackages` 会一次性 binder 调用拉回全部包信息，
 * 上百个包时是几十毫秒级，绝不能在主线程做（否则界面掉帧）。
 * 这里整体包在 [Dispatchers.IO] 里，并且由调用方控制频率（默认 30 分钟一次）。
 */
@Singleton
class AppInventoryCollector @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 采集全量台账。@return 已过滤后的应用列表（无启动入口的系统组件已被剔除） */
    suspend fun collect(): List<AppInventoryItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val items = ArrayList<AppInventoryItem>(128)
        val packages = runCatching { installedPackages(pm) }.getOrElse {
            Logger.e(TAG, it) { "query installed packages failed" }
            emptyList()
        }
        for (info in packages) {
            val pkg = info.packageName ?: continue
            // 没有启动入口 = 用户桌面上不会出现，家长管不到也没有意义
            if (pm.getLaunchIntentForPackage(pkg) == null) continue
            val app = info.applicationInfo
            val isSystem = app != null && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            items.add(
                AppInventoryItem(
                    packageName = pkg,
                    appName = app?.let { pm.getApplicationLabel(it).toString() } ?: pkg,
                    versionName = info.versionName,
                    versionCode = versionCode(info),
                    isSystem = isSystem,
                    installTime = info.firstInstallTime.takeIf { it > 0 },
                    updateTime = info.lastUpdateTime.takeIf { it > 0 }
                )
            )
        }
        Logger.d(TAG) { "app inventory collected: ${items.size}" }
        items
    }

    @Suppress("DEPRECATION")
    private fun installedPackages(pm: PackageManager): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            pm.getInstalledPackages(0)
        }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

    companion object {
        private const val TAG = "AppInventoryCollector"
    }
}
