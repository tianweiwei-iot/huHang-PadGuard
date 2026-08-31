package com.padguard.parent.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.padguard.presentation.ui.PadGuardNavHost
import com.padguard.presentation.ui.theme.PadGuardTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 主 Activity - Compose 单 Activity 架构
 * 所有页面通过 Navigation Compose 管理
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PadGuardTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PadGuardNavHost()
                }
            }
        }
    }
}
