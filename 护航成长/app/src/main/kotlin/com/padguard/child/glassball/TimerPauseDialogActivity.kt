package com.padguard.child.glassball

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.child.usagesession.TimerPauseSource
import com.padguard.child.usagesession.UsageSessionViewModel
import com.padguard.child.ui.theme.PadGuardTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 玻璃球「长按 → 暂停计时」确认对话框（2026-11-18 新增）。
 *
 * ## 为什么要确认
 * 悬浮球同时承载单击（家长入口）、双击（申请延时）与拖拽；长按是新增的高危操作
 * ——确认后立即冻结计时并锁住整台平板。没有二次确认时，孩子拖球犹豫片刻就会
 * 把自己锁在外面，属于不可接受的误触成本。
 *
 * ## 恢复路径
 * 确认暂停后设备进入 TIMER_PAUSED 锁定态，**只有锁屏页「继续计时」能恢复**；
 * 按电源键熄屏再亮屏不会解除手动暂停（见 [TimerPauseSource]）。
 *
 * ## 生命周期
 * 复用 GlassBallDialogActivity 的透明 Gate 主题 + singleInstance/noHistory，
 * 不驻留最近任务、不给孩子留逃逸入口；无悬浮窗权限时玻璃球本就不显示，
 * 本 Activity 也不会被唤起。
 */
@AndroidEntryPoint
class TimerPauseDialogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PadGuardTheme {
                Surface {
                    val viewModel: UsageSessionViewModel = hiltViewModel()
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    AlertDialog(
                        onDismissRequest = { finish() },
                        confirmButton = {
                            Button(
                                onClick = {
                                    viewModel.pauseTimer(TimerPauseSource.MANUAL)
                                    finish()
                                },
                                enabled = state.active && !state.paused
                            ) { Text("确认暂停") }
                        },
                        dismissButton = {
                            TextButton(onClick = { finish() }) { Text("取消") }
                        },
                        title = { Text("暂停计时") },
                        text = {
                            Text(
                                text = if (state.active) {
                                    "确认后今日计时暂停、当前剩余时间冻结，平板暂时不可操作。" +
                                        "点锁屏上的「继续计时」即可恢复。"
                                } else {
                                    "今日计时尚未开始，无需暂停。"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    )
                }
            }
        }
    }
}
