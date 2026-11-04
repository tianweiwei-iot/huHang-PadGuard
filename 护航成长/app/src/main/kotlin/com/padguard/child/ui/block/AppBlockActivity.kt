package com.padguard.child.ui.block

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.padguard.child.ui.components.GlyphBadge
import com.padguard.child.ui.theme.PadGuardTheme
import com.padguard.child.ui.unlock.UnlockRequestScreen
import dagger.hilt.android.AndroidEntryPoint

/**
 * 应用违规拦截覆盖层（应用黑白名单/超额命中时由 GuardService.reconcileAppBlock 拉起）。
 *
 * 设计依据（设计说明书「界面交互规范 · 违规拦截弹窗」）：
 * - 展示「当前应用/网站已被管控限制，请使用合规学习资源」正向提示；
 * - 展示**具体拦截原因**（超时长 / 名单限制 / 时段限制），只说"被限制"会让孩子
 *   觉得是系统故障从而反复重试，说清原因才能形成预期；
 * - 提供「返回桌面」作为唯一出口 —— 它只把用户送回桌面，**不会**把被拦应用放到前台，
 *   因此符合「无关闭逃逸入口」的本意（用户无法借关闭按钮进入被禁应用）；
 * - 提供「申请临时解锁」柔性通道 —— 家庭场景下硬拦一刀切会把孩子逼向对抗
 *   （借同学手机、找破解），给一条"讲理由、等同意"的正规路径反而更可控。
 *
 * 注意：本页是"软拦截"的视觉拦截层。真正禁止应用启动（用户点图标根本进不去）
 * 依赖 Device Owner 的 `setApplicationHidden` / 用户限制，属于 Kiosk 范畴；
 * 当前通过"前台应用被识别为黑名单 → 盖此页 + 返回桌面"实现拦截，
 * 且在 GuardService 中同一包名在仍处前台时已通过 `lastBlockedPackage` 去重避免反复拉起。
 */
@AndroidEntryPoint
class AppBlockActivity : ComponentActivity() {

    /**
     * 申请表单是否展开。
     *
     * 提到 Activity 层而不是留在 setContent 内部，是为了让返回键回调能读到它 ——
     * 否则用户在申请表单里按返回会被直接送回桌面，刚填的理由白填。
     */
    private val showRequestForm = mutableStateOf(false)

    /**
     * 当前被拦的应用信息。
     *
     * 必须是可变状态而不是 onCreate 里的局部变量：本 Activity 由 GuardService 以
     * CLEAR_TOP|SINGLE_TOP 反复拉起，第二次起走 onNewIntent。此前没有 onNewIntent，
     * 拦截页会一直显示**上一个**应用的名字，孩子点「申请临时解锁」提交的也是上一个包——
     * 家长批准了 A，实际被拦的 B 依然打不开。
     */
    private val blockedPkg = mutableStateOf("")
    private val blockedReason = mutableStateOf("")
    private val blockedLabel = mutableStateOf("")

    /**
     * 本页此刻是否处于**前台可见**状态（onResume ~ onPause 之间）。
     *
     * 只判 `isFinishing/isDestroyed` 是不够的：孩子按**系统 HOME 键**或从最近任务
     * 切到别的应用时，本页进入后台（stopped）但实例仍然存活、也从未 finish。
     * 此时 [isShowing] 会继续返回 true，`GuardService.reconcileAppBlock` 的同包去重
     * 就永远不会重弹 —— 拦截页在后台看不见，被拦应用在前台照常使用，
     * 单应用限额 / 黑名单 / Kiosk 降级三条线同时失守。
     *
     * 这是"点「回桌面」后重开应用逃逸"（按钮路径已修）的残留变体：
     * 按钮会 finish 本页，HOME 键不会。
     */
    private var resumed = false

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        applyIntent(intent)

        setContent {
            PadGuardTheme {
                if (showRequestForm.value) {
                    UnlockRequestScreen(
                        onBack = { showRequestForm.value = false },
                        prefillPackage = blockedPkg.value,
                        prefillLabel = blockedLabel.value
                    )
                } else {
                    AppBlockContent(
                        label = blockedLabel.value,
                        reason = blockedReason.value,
                        onRequestUnlock = { showRequestForm.value = true },
                        onGoHome = { goHome() }
                    )
                }
            }
        }

        // 返回键：申请表单 → 退回拦截页；拦截页 → 回桌面。
        // 任何一步都不能让返回动作落到被拦应用上。
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (showRequestForm.value) {
                        showRequestForm.value = false
                    } else {
                        goHome()
                    }
                }
            }
        )
    }

    /** 解析 intent 到可变状态；onCreate 与 onNewIntent 共用同一路径 */
    private fun applyIntent(i: Intent?) {
        val pkg = i?.getStringExtra(EXTRA_PACKAGE).orEmpty()
        blockedPkg.value = pkg
        blockedReason.value = i?.getStringExtra(EXTRA_REASON).orEmpty()
        blockedLabel.value = resolveLabel(pkg)
    }

    /**
     * SINGLE_TOP 复用时系统不再走 onCreate —— 必须在这里刷新被拦应用。
     * 同时收起申请表单：表单里预填的是上一个包，直接沿用会提交错解锁申请。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showRequestForm.value = false
        applyIntent(intent)
    }

    private fun resolveLabel(pkg: String): String {
        if (pkg.isBlank()) return ""
        return runCatching {
            val pm = packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 用户想离开：回桌面而非回到被拦应用 */
    private fun goHome() {
        val i = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(i)
        finish()
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_REASON = "reason"

        /**
         * 当前在前的拦截页实例。
         *
         * 家长同意放行后必须能把它关掉：拦截页一旦盖在应用之上，
         * 孩子即使已经拿到放行也不会自动回到应用 —— 屏幕上仍是"已被管控限制"，
         * 家长端却显示"已同意"，这就是最典型的"同意了却毫无反应"。
         */
        private var instance: AppBlockActivity? = null

        /** 放行/解除拦截时关闭拦截页；没有在前的拦截页时是安全的空操作 */
        fun dismiss(context: Context) {
            instance?.let { activity ->
                if (!activity.isFinishing) activity.finish()
            }
        }

        /**
         * 拦截页是否**真的还盖在孩子眼前**。
         *
         * GuardService 需要它来区分两种"后台包 == 上次拦截包"的情形：
         * ① 拦截页仍在前台（探针伪影，不能重复弹）；② 孩子已把拦截页弄走、
         * 又重新点开了被拦应用（必须重新拦截）。只看包名会把 ② 误判成 ①，
         * 导致按一次返回就永久逃逸。
         *
         * 而"弄走"有两条路径，必须都覆盖：
         * - 点「回桌面」→ `goHome()` 会 finish 本页（isFinishing 为真）；
         * - 按**系统 HOME 键 / 最近任务切走**→ 本页只是进入后台，实例还活着、从未 finish。
         *   所以必须再判 [resumed]，否则第二条路径仍然逃逸。
         */
        fun isShowing(): Boolean {
            val activity = instance ?: return false
            return activity.resumed && !activity.isFinishing && !activity.isDestroyed
        }

        /**
         * @param reason 人类可读的拦截原因（如「今日使用时长已达上限」）。
         *               留空时页面回退到通用文案，不会显示空白区块。
         */
        fun show(context: Context, packageName: String, reason: String = "") {
            val intent = Intent(context, AppBlockActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_REASON, reason)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(intent)
        }
    }
}

@Composable
private fun AppBlockContent(
    label: String,
    reason: String,
    onRequestUnlock: () -> Unit,
    onGoHome: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            GlyphBadge("禁", MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(24.dp))
            Text(
                text = "该应用已被管控限制",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(12.dp))
            if (label.isNotBlank()) {
                Text(
                    text = "应用：$label",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = "当前应用属于管控限制范围，请使用合规学习资源。",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
            if (reason.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                ReasonCard(reason)
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onRequestUnlock,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "申请临时解锁")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onGoHome) {
                Text(text = "返回桌面")
            }
        }
    }
}

/** 拦截原因单独成卡：与正向引导文案区分开，避免被当成同一段废话跳过 */
@Composable
private fun ReasonCard(reason: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "拦截原因",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
