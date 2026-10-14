package com.padguard.child

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.padguard.child.ui.theme.PadGuardTheme
import com.padguard.child.guard.AppAccessGateDialog
import com.padguard.child.guard.AppAccessNoPasswordDialog
import com.padguard.child.guard.AppAccessReason
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 孩子桌面（自定义 Launcher）。
 *
 * ## 它是什么
 * 把本应用注册为 HOME 备选（见 Manifest）。家长在孩子平板上把它设为默认桌面后，
 * 孩子按 HOME 就进入这里——一个**只显示白名单应用**的网格，看不到、点不到违禁应用。
 * 这是对"应用管控"最直观的一层：从源头减少孩子触达违禁应用的机会。
 *
 * ## 它不能单独让管控生效
 * 这里只是"可视网关"。孩子若从最近任务 / 通知 / 其他桌面启动违禁应用，
 * 仍由 [com.padguard.child.guard.OperationGuardAccessibilityService] 顶回。两者叠加才是闭环。
 *
 * ## 需要密码时弹在哪里
 * 点开受管控应用就在**本桌面上就地弹密码框**（[com.padguard.child.guard.AppAccessGateDialog]），
 * 不跳转到任何管控系统页面 —— 门禁属于"点图标"这一刻，桌面必须始终在背后可见。
 * 从最近任务 / 通知启动的路径由无障碍服务拉起浮层 Activity 覆盖，两者共用同一套校验组件。
 * 真正不可绕过需要 Device Owner（Kiosk）——本 Activity 已保留 HOME 类目供 KioskEnforcer 反查。
 *
 * ## 防砖
 * 即使策略未下发或拉取异常，[KidHomeViewModel] 也会降级显示全部应用，桌面绝不空白。
 */
@AndroidEntryPoint
class KidHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PadGuardTheme {
                KidLauncherScreen(
                    onOpenManagement = {
                        // 通过应用图标进管理界面（不受本桌面控制时也能用）
                        startActivity(Intent(this, MainActivity::class.java))
                    }
                )
            }
        }
    }
}

@Composable
private fun KidLauncherScreen(
    onOpenManagement: () -> Unit,
    vm: KidHomeViewModel = hiltViewModel()
) {
    var showParentDialog by remember { mutableStateOf(false) }
    /**
     * 当前正在等待家长验证的应用包名；非空即在**本桌面上**就地弹出门禁对话框。
     *
     * 这里刻意不再 `startActivity` 跳转到管控系统的解锁页：应用门禁发生在"点桌面图标"这一刻，
     * 正确形态是桌面上浮出一个密码框，而不是把人带到另一个页面去。
     * （从最近任务 / 通知 / 系统桌面启动的路径由无障碍服务用浮层 Activity 覆盖，
     * 与本对话框共用同一套校验组件，行为一致。）
     */
    var gatePackage by remember { mutableStateOf<String?>(null) }

    // 进入与每次回到桌面都刷新名单：singleTask 复用时不会重建，靠 Lifecycle 监听 onResume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val apps = vm.apps

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (apps.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无可用应用", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 84.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        AppIconCell(
                            app = app,
                            onClick = {
                                val pkg = app.packageName
                                // 需要门禁时只在桌面上就地弹出密码框，绝不跳转页面；
                                // 验证通过由 ViewModel 先落放行再启动，否则直接启动。
                                if (vm.needsLaunchGuard(pkg)) {
                                    gatePackage = pkg
                                } else {
                                    vm.launchApp(pkg)
                                }
                            }
                        )
                    }
                }
            }

            // 家长入口：受操作密码保护，避免孩子直接进入管理界面关闭管控
            Button(
                onClick = { showParentDialog = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) {
                Text("家长")
            }
        }
    }

    if (showParentDialog) {
        ParentUnlockDialog(
            vm = vm,
            onDismiss = { showParentDialog = false },
            onSuccess = {
                showParentDialog = false
                onOpenManagement()
            }
        )
    }

    // 应用门禁：就地弹在桌面上。桌面仍在背后可见，关闭即回到桌面，没有页面跳转。
    gatePackage?.let { pkg ->
        val label = apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
        val reason = if (vm.isRevoked(pkg)) AppAccessReason.REVOKED else AppAccessReason.CONTROLLED
        if (vm.passwordManager.isEnabledNow()) {
            AppAccessGateDialog(
                appLabel = label,
                reason = reason,
                manager = vm.passwordManager,
                onDismiss = { gatePackage = null },
                onGranted = {
                    gatePackage = null
                    vm.openAfterGrant(pkg)
                }
            )
        } else {
            // 密码未启用：如实告知家长需要去设置，而不是弹一个注定验不过的密码框
            AppAccessNoPasswordDialog(
                appLabel = label,
                reason = reason,
                onDismiss = { gatePackage = null }
            )
        }
    }
}

@Composable
private fun AppIconCell(app: LauncherAppItem, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Image(bitmap = app.icon, contentDescription = app.label, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(6.dp))
        Text(app.label, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun ParentUnlockDialog(
    vm: KidHomeViewModel,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    if (!vm.isGuardEnabled()) {
                        onSuccess()
                        return@launch
                    }
                    val result = vm.verifyParent(text)
                    if (result.ok) {
                        onSuccess()
                    } else {
                        error = if (result.lockedForMs > 0) {
                            "密码错误次数过多，已锁定，请稍后再试"
                        } else {
                            "密码错误，还可尝试 ${result.attemptsLeft} 次"
                        }
                    }
                }
            }) { Text("进入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("家长验证") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("操作密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }
    )
}
