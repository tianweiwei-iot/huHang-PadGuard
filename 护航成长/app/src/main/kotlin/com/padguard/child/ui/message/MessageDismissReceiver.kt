package com.padguard.child.ui.message

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 霸屏通知被划掉后的兜底重发。
 *
 * 霸屏（blocking=true）消息要求"必须看到"，但全屏页未拉起时（后台启动受限、锁屏等），
 * 孩子唯一看到的就是那一条高优先级通知 —— 上滑一划就没了，霸屏形同虚设（实际故障）。
 *
 * 本接收器挂在通知的 DeleteIntent 上：只要霸屏通知被移除（横幅上滑、清除面板），
 * 就立即按原内容重新发布（通知 + 再拉一次全屏页），直到霸屏时长走完被 [MessageActivity]
 * 正常关闭。普通消息（blocking=false）不重发，划掉就划掉了。
 */
class MessageDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        runCatching { MessageActivity.handleNotificationDeleted(context, intent) }
    }
}
