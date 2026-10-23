package com.padguard.child.capture

import android.content.Context
import android.content.Intent
import android.os.Parcel
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 屏幕采集权限闩锁存储 —— "一次性授权后权限钉死，仅解绑可撤"的基座。
 *
 * ## 解决什么问题
 * 旧实现把采集授权当成"进程内易失令牌"：退出看屏即清、息屏即清、重启即失，
 * 于是"返回主页再进入实时监控"必然重新向孩子弹系统录屏授权框，权限随时在丢。
 *
 * 本存储把"已授权"这一事实**持久化**，使权限不再随进程/会话生命周期浮动：
 * - [isA11yGranted]：无障碍通道（[AccessibilityScreenshot]）的授权标记。无障碍服务
 *   启用态本身是系统级**持久授权**（重启/进程死均不失效），本标记记录"用户已确立该能力"，
 *   供启动/亮屏时主动 `ensureEnabled()` 把服务静默重绑，做到"授权一次、永不丢失"。
 * - 投影凭据（resultCode + data）：best-effort 落盘，进程内复用零弹窗；跨重启后若系统已
 *   作废 IBinder，[ScreenCaptureSession.obtainProjection] 会安全失败并清理，退回无障碍主路。
 *
 * ## 持久化边界（如实声明，不夸大）
 * 文件位于应用私有目录（`MODE_PRIVATE`），Device Owner 管控设备默认开启文件级加密（FBE），
 * 静止态受设备加密保护。此处**不引入新加密依赖**（避免构建面与兼容性风险），满足
 * "进程死/重启不丢、仅 [revoke] 可清"的设计目标。
 *
 * ## 唯一撤权出口
 * [revoke] 同时清除 a11y 标记与投影凭据，且只由设备解绑流程（[com.padguard.child.service.GuardService.handleRemoteUnbind]）
 * 调用。任何中途的"退出看屏/息屏/进程被杀"都不得触碰此处存储——这正是权限"钉死"的保证。
 */
@Singleton
class CaptureConsentStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val consentFile = File(context.filesDir, CONSENT_FILE_NAME)

    /** 标记/确认无障碍截屏能力已授权（系统级持久授权的本地镜像）。 */
    fun markA11yGranted() {
        prefs.edit().putBoolean(KEY_A11Y_GRANTED, true).apply()
        Logger.i(TAG) { "a11y capture capability marked granted (latched)" }
    }

    /** 是否已确立无障碍采集授权（持久）。 */
    fun isA11yGranted(): Boolean = prefs.getBoolean(KEY_A11Y_GRANTED, false)

    /** 仅解绑时清除无障碍授权标记。 */
    fun clearA11yGranted() {
        prefs.edit().remove(KEY_A11Y_GRANTED).apply()
    }

    /**
     * 持久化投影凭据（best-effort）。
     * 包含 IBinder 的 Intent 跨进程/重启后引用可能失效，但进程内复用与"启动即尝试"均安全：
     * 复用失败由 [ScreenCaptureSession] 兜底清理，不在此处抛异常。
     */
    fun saveProjectionConsent(resultCode: Int, data: Intent) {
        runCatching {
            val p = Parcel.obtain()
            p.writeInt(resultCode)
            data.writeToParcel(p, 0)
            val bytes = p.marshall()
            p.recycle()
            consentFile.writeBytes(bytes)
            Logger.i(TAG) { "projection consent persisted (${bytes.size} bytes)" }
        }.onFailure { Logger.e(TAG, it) { "saveProjectionConsent failed" } }
    }

    /** 读取持久化投影凭据；文件不存在或反序列化失败（IBinder 失效等）返回 null。 */
    fun loadProjectionConsent(): Pair<Int, Intent>? = runCatching {
        if (!consentFile.exists()) return null
        val bytes = consentFile.readBytes()
        if (bytes.isEmpty()) return null
        val p = Parcel.obtain()
        p.unmarshall(bytes, 0, bytes.size)
        p.setDataPosition(0)
        val code = p.readInt()
        val intent = Intent.CREATOR.createFromParcel(p)
        p.recycle()
        code to intent
    }.getOrNull()

    /** 清除持久化投影凭据（不触碰运行时 [ScreenCaptureSession]）。 */
    fun clearProjectionConsent() {
        runCatching { consentFile.delete() }
    }

    /** 唯一撤权：清除全部本地授权痕迹。仅设备解绑调用。 */
    fun revoke() {
        clearA11yGranted()
        clearProjectionConsent()
        Logger.w(TAG) { "capture consent fully revoked (unbind)" }
    }

    companion object {
        private const val TAG = "CaptureConsentStore"
        private const val PREFS_NAME = "padguard_capture_consent"
        private const val CONSENT_FILE_NAME = "proj_consent.bin"
        private const val KEY_A11Y_GRANTED = "a11y_granted"
    }
}
