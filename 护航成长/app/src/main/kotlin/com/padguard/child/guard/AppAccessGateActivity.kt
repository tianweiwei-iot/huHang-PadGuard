package com.padguard.child.guard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.padguard.child.ui.theme.PadGuardTheme
import com.padguard.core.common.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 应用访问门禁（桌面上浮出的密码框）。
 *
 * ## 它解决的是什么
 * 孩子打开受管控应用时，密码必须**在桌面上当场输入**，而不是被带进管控系统的某个页面里。
 * 本 Activity 因此采用透明浮层主题（[android.R.style] 之外自定义的 `Theme.PadGuard.Gate`）：
 * 窗口背景透明、桌面保持可见，只有一张居中的密码卡片浮在上面。
 *
 * ## 覆盖范围
 * 无障碍服务 [OperationGuardAccessibilityService] 拦截到的两条路径都走这里：
 * - 取消授权的应用（[AppAccessReason.REVOKED]）—— 无密码绝不放行；
 * - 家长开了「打开应用也要密码」的受管控应用（[AppAccessReason.CONTROLLED]）。
 * 自家桌面 [com.padguard.child.KidHomeActivity] 则直接就地弹同款卡片，不经过本 Activity。
 *
 * ## 与「家长操作解锁」的边界
 * [GuardUnlockActivity] 管的是安装 / 卸载 / 改系统设置，验证通过开的是**全局解锁窗口**；
 * 这里管的是打开一个应用，验证通过只授予 [GuardPasswordManager.grantAppAccess] 的
 * 单包放行。两者互不相干，不可合并 —— 合并就会让"打开一个应用"顺带解锁整个设备。
 */
@AndroidEntryPoint
class AppAccessGateActivity : ComponentActivity() {

    @Inject
    lateinit var manager: GuardPasswordManager

    @Inject
    lateinit var appGuard: AppGuardPolicyStore

    /** 当前待放行的包名；singleTask 复用实例时由 [onNewIntent] 更新并驱动重组。 */
    private var targetPackage by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        if (targetPackage.isEmpty()) {
            finish()
            return
        }
        setContent {
            PadGuardTheme {
                val pkg = targetPackage
                if (pkg.isEmpty()) return@PadGuardTheme
                val label = remember(pkg) { appLabel(pkg) }
                val reason = remember(pkg) {
                    if (appGuard.isHiddenControlled(pkg)) AppAccessReason.REVOKED
                    else AppAccessReason.CONTROLLED
                }
                AppAccessGateOverlay(onDismiss = { finish() }) {
                    if (manager.isEnabledNow()) {
                        AppAccessGateCard(
                            appLabel = label,
                            reason = reason,
                            manager = manager,
                            onDismiss = { finish() },
                            onGranted = { grantAndOpen(pkg) }
                        )
                    } else {
                        // 未设操作密码：本地没有可比对凭据，只能如实告知，绝不因为"验不了"就放行
                        AppAccessNoPasswordCard(
                            appLabel = label,
                            reason = reason,
                            onDismiss = { finish() }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        if (pkg.isEmpty()) {
            finish()
            return
        }
        targetPackage = pkg
    }

    /**
     * 授予放行 → 打开应用 → 收起浮层，顺序严格不可颠倒。
     *
     * 必须先落放行再启动：无障碍服务在应用启动瞬间就会收到该包的窗口事件，
     * 若此时放行还没生效，孩子会看到"密码输对了，应用刚起来又被顶回桌面"。
     * [GuardPasswordManager.grantAppAccess] 内部已同步镜像主线程快照，因此这里
     * 在它返回后再启动目标应用即可保证判定读取到放行。
     */
    private fun grantAndOpen(pkg: String) {
        lifecycleScope.launch {
            manager.grantAppAccess(pkg)
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch == null) {
                Logger.w(TAG) { "no launch intent for $pkg after access granted" }
            } else {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(launch) }
                    .onFailure { Logger.e(TAG, it) { "failed to launch $pkg after access granted" } }
            }
            finish()
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)

    companion object {
        private const val TAG = "AppAccessGate"

        /** 待放行的目标应用包名 */
        const val EXTRA_PACKAGE = "extra_gate_package"

        /**
         * 从无障碍服务等非 Activity 上下文拉起门禁。
         *
         * 无障碍服务不受 Android 10+ 后台启动 Activity 的限制，
         * 因此即便刚执行过 [android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME]，
         * 这里也能立刻把浮层顶到桌面上。
         */
        fun show(context: Context, pkg: String) {
            context.startActivity(
                Intent(context, AppAccessGateActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_PACKAGE, pkg)
            )
        }
    }
}
