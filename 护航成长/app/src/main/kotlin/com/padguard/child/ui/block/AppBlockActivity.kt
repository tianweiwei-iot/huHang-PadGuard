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
 * 依赖 Device Owner 的 `setApplicationHidden` / 用户限制，属于 P1 Kiosk 范畴；
 * 当前 P0 下通过"前台应用被识别为黑名单 → 盖此页 + 返回桌面"实现拦截，
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val reason = intent?.getStringExtra(EXTRA_REASON).orEmpty()
        val label = resolveLabel(pkg)

        setContent {
            PadGuardTheme {
                if (showRequestForm.value) {
                    UnlockRequestScreen(
                        onBack = { showRequestForm.value = false },
                        prefillPackage = pkg,
                        prefillLabel = label
                    )
                } else {
                    AppBlockContent(
                        label = label,
                        reason = reason,
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
