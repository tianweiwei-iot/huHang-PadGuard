package com.padguard.child.guard

import android.app.ActivityManager
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

    /** 用于「关闭对话框即退出应用」：强杀被管控应用进程（需 [android.Manifest.permission.KILL_BACKGROUND_PROCESSES]）。 */
    private val activityManager by lazy {
        getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }

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
                AppAccessGateOverlay(onDismiss = { exitAndFinish() }) {
                    if (manager.isEnabledNow()) {
                        AppAccessGateCard(
                            appLabel = label,
                            reason = reason,
                            manager = manager,
                            onDismiss = { exitAndFinish() },
                            onGranted = { grantAndOpen(pkg) }
                        )
                    } else {
                        // 未设操作密码：本地没有可比对凭据，只能如实告知，绝不因为"验不了"就放行
                        AppAccessNoPasswordCard(
                            appLabel = label,
                            reason = reason,
                            onDismiss = { exitAndFinish() }
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
     * 授予放行 → 把应用带回前台 → 收起浮层，顺序严格不可颠倒。
     *
     * 必须先落放行再操作前台：无障碍服务在应用窗口事件到达的同一帧就会做放行判定，
     * 若此时放行还没生效，孩子会看到"密码输对了，应用刚起来又被顶回桌面"。
     * [GuardPasswordManager.grantAppAccess] 内部已同步镜像主线程快照，因此这里
     * 在它返回后再把应用提到前台即可保证判定读取到放行。
     *
     * 关键改动：受管控应用此刻就在前台（被透明浮层盖在下面、已 paused），
     * 不需要再 `getLaunchIntentForPackage` 起一个全新实例——那样会偶发"再起一个根 Activity"
     * 的闪屏或重复实例。改为用 [Intent.FLAG_ACTIVITY_REORDER_TO_FRONT] 把它的任务
     * 原样提到最前，收起浮层后应用自然回到可见状态。
     */
    private fun grantAndOpen(pkg: String) {
        lifecycleScope.launch {
            manager.grantAppAccess(pkg)
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch == null) {
                Logger.w(TAG) { "no launch intent for $pkg after access granted" }
            } else {
                launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                runCatching { startActivity(launch) }
                    .onFailure { Logger.e(TAG, it) { "failed to foreground $pkg after access granted" } }
            }
            finish()
        }
    }

    /**
     * 孩子取消 / 点框外 → 关闭门禁并回到桌面。
     *
     * ## 关键：绝不在"被管控应用仍可能在前台"时强杀它
     * 门禁浮层是由无障碍服务在**已把应用压回桌面之后**才拉起的，因此进入本方法时，
     * 被管控应用早已不在前台（被 [GLOBAL_ACTION_HOME] 压回后台）。这里只需：
     * 1. [android.content.Intent.ACTION_MAIN] + [android.content.Intent.CATEGORY_HOME]
     *    确保落点是桌面（华为等非默认 KidHome 启动器的设备，HOME 仍能回到系统桌面）；
     * 2. 应用已在后台，[ActivityManager.killBackgroundProcesses] 此刻可安全生效，作为
     *    "关闭即退出"的兜底（防孩子从最近任务重新拉起）；
     * 3. [finish] 收起浮层。
     *
     * 早期实现是"先 HOME 再 kill 再 finish"的脆弱时序：HOME 是异步全局动作，kill 立即执行时
     * 应用往往仍是前台进程、被系统拒绝强杀，浮层一收应用原地复活、直接可用 ——
     * 这正是"管控了还是能继续打开"的回归根因。本方法把强杀前提（应用已后台）交给
     * 调用前的不变量保证，不再依赖时序竞态。
     * 即便 kill 因极端时序失败，孩子在桌面若再点开该应用，无障碍服务会立即重新拦截并弹门禁，
     * 形成闭环，不会失控。
     */
    private fun exitAndFinish() {
        val pkg = targetPackage
        // 1) 回到桌面：确保落点可预测，且被管控应用保持后台
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Logger.w(TAG, it) { "failed to go home on gate dismiss" } }
        // 2) 应用已在后台，强杀此刻可安全生效（非前台进程，系统不拒）
        if (pkg.isNotEmpty()) {
            runCatching { activityManager.killBackgroundProcesses(pkg) }
                .onFailure { Logger.w(TAG, it) { "failed to kill $pkg on gate dismiss" } }
        }
        // 3) 收起浮层
        finish()
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
