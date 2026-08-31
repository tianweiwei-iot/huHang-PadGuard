package com.padguard.child.bind

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.padguard.child.ui.theme.PadGuardTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 绑定流程宿主 Activity。
 *
 * 进入时机：MainActivity 启动时若发现 AuthRepository.isBound == false，则跳转本 Activity；
 * 绑定成功后 finish 自己，回 MainActivity 进入主页。
 */
@AndroidEntryPoint
class BindActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PadGuardTheme {
                BindScreen(
                    onBound = { finish() }
                )
            }
        }
    }
}
