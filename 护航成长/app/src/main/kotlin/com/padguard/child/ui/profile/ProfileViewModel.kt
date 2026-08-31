package com.padguard.child.ui.profile

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.core.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "我的"页 ViewModel。
 *
 * 设备硬件信息（型号 / Android 版本 / 存储 / 电量）实时读取，不进数据库；
 * 管控权限状态读取 [DevicePolicyManager] 标记的位。
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                authRepository.studentName,
                authRepository.deviceSn,
                authRepository.isBound
            ) { name, sn, bound ->
                ProfileUiState(
                    studentName = name,
                    deviceSn = sn.ifBlank { Build.SERIAL ?: "未识别" },
                    isBound = bound,
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                    androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    storage = readStorage(),
                    battery = readBattery(),
                    deviceAdminActive = isDeviceAdminActive(),
                    usageStatsGranted = isUsageStatsGranted()
                )
            }
                .flowOn(Dispatchers.IO)
                .collect { _state.value = it }
        }
    }

    private fun readStorage(): String {
        return runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.totalBytes / (1024 * 1024 * 1024)
            val free = stat.availableBytes / (1024 * 1024 * 1024)
            "$free GB 可用 / 共 $total GB"
        }.getOrDefault("未知")
    }

    private fun readBattery(): String {
        return runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (level in 0..100) "电量 $level%" else "电量未知"
        }.getOrDefault("电量未知")
    }

    private fun isDeviceAdminActive(): Boolean {
        return runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return@runCatching false
            val adminComponent = ComponentName(
                context,
                "com.padguard.child.receiver.PadGuardDeviceAdminReceiver"
            )
            dpm.isAdminActive(adminComponent)
        }.getOrDefault(false)
    }

    private fun isUsageStatsGranted(): Boolean {
        // 见 P1 接入时的 UsageStatsManager.checkUsageStatsPermission，简化先返回 false
        return false
    }
}

data class ProfileUiState(
    val studentName: String = "",
    val deviceSn: String = "未识别",
    val isBound: Boolean = false,
    val deviceModel: String = "未知设备",
    val androidVersion: String = "Android --",
    val storage: String = "未知",
    val battery: String = "未知",
    val deviceAdminActive: Boolean = false,
    val usageStatsGranted: Boolean = false
)
