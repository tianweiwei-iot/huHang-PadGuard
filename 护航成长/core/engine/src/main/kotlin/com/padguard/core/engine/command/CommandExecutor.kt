package com.padguard.core.engine.command

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.AckStatus
import com.padguard.core.data.model.Command
import com.padguard.core.data.model.CommandAck
import com.padguard.core.data.model.CommandType
import com.padguard.core.data.repository.PolicyRepository
import com.padguard.core.data.repository.UnlockRequestRepository
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.admin.OpResult
import com.padguard.core.engine.enforcer.AppLimitEnforcer
import com.padguard.core.engine.enforcer.EyeCareEnforcer
import com.padguard.core.engine.enforcer.PeripheralEnforcer
import com.padguard.core.engine.lock.LockController
import com.padguard.core.engine.lock.LockReason
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远程指令执行器。
 *
 * ## 职责边界（为什么不直接在这里做完所有事）
 * engine 模块**不依赖** transport 与 app 模块，因此本类只做两类事：
 * 1. 能在 engine 内闭环的，直接执行（锁屏、重启、挂起应用、外设放行、策略重置…）；
 * 2. 必须由 app 层完成的（需要 UI、MediaProjection、定位权限、网络下载），
 *    通过 [effects] 发出副作用请求，由前台服务消费。
 *
 * 这样做的收益是：指令的**校验与语义**集中在一处且可单元测试，
 * 而平台相关的脏活留在 app 层，不会把 DPM 逻辑和 Activity 生命周期缠在一起。
 *
 * ## 回执语义（必须严格区分，否则管控端会误判）
 * | 回执 | 含义 | 典型场景 |
 * |---|---|---|
 * | SUCCESS | 已执行完成，或已可靠移交给 app 层执行 | 锁屏成功、截屏任务已触发 |
 * | UNSUPPORTED | 当前权限/系统版本**做不到**，重试也没用 | 无 DO 时重启、任何模式下关机 |
 * | FAILED | 本该能做但失败了，可重试 | ROM 拒绝、目标应用未安装 |
 * | REJECTED / DUPLICATED | 签名/时效/幂等校验未过（由 CommandGate 产生，不到这里） |
 *
 * 把 UNSUPPORTED 误报成 FAILED 会让服务端无限重试；反过来会让真实故障被当成"设备不支持"忽略。
 */
@Singleton
class CommandExecutor @Inject constructor(
    private val admin: DeviceAdminBridge,
    private val lockController: LockController,
    private val policyRepository: PolicyRepository,
    private val unlockRequestRepository: UnlockRequestRepository,
    private val peripheralEnforcer: PeripheralEnforcer,
    private val appLimitEnforcer: AppLimitEnforcer,
    private val eyeCareEnforcer: EyeCareEnforcer,
    private val timeProvider: TimeProvider
) {

    private val _effects = MutableSharedFlow<EngineEffect>(extraBufferCapacity = 32)

    /** app 层前台服务订阅此流完成需要平台能力的动作 */
    val effects: SharedFlow<EngineEffect> = _effects.asSharedFlow()

    suspend fun execute(command: Command, deviceId: String): CommandAck {
        Logger.i(TAG) { "executing ${command.type} (msgId=${command.msgId})" }
        return try {
            when (command.type) {
                CommandType.LOCK_SCREEN -> lockScreen(command, deviceId)
                CommandType.UNLOCK -> unlock(command, deviceId)
                CommandType.TEMP_UNLOCK -> tempUnlock(command, deviceId)
                CommandType.REBOOT -> reboot(command, deviceId)
                CommandType.SHUTDOWN -> shutdown(command, deviceId)
                CommandType.SHOW_MESSAGE -> showMessage(command, deviceId)
                CommandType.SCREENSHOT -> screenshot(command, deviceId)
                CommandType.LOCATE -> locate(command, deviceId)
                CommandType.PHOTO -> photo(command, deviceId)
                CommandType.RECORD -> audioRecord(command, deviceId)
                CommandType.STOP_RECORD -> stopAudioRecord(command, deviceId)
                CommandType.SCREEN_RECORD -> screenRecord(command, deviceId)
                CommandType.STOP_SCREEN_RECORD -> stopScreenRecord(command, deviceId)
                CommandType.RESET_POLICY -> resetPolicy(command, deviceId)
                CommandType.REFRESH_POLICY -> refreshPolicy(command, deviceId)
                CommandType.INSTALL_APP -> installApp(command, deviceId)
                CommandType.UNINSTALL_APP -> uninstallApp(command, deviceId)
                CommandType.SET_APP_SUSPENDED -> setAppSuspended(command, deviceId)
                CommandType.SET_WATERMARK -> setWatermark(command, deviceId)
                CommandType.PERIPHERAL_OVERRIDE -> peripheralOverride(command, deviceId)
                CommandType.UPDATE_CONFIG -> updateConfig(command, deviceId)
                CommandType.FLUSH_LOGS -> flushLogs(command, deviceId)
                CommandType.WIPE -> wipe(command, deviceId)
            }
        } catch (e: Exception) {
            // 兜底：任何未预期异常都不能让执行链断掉，否则后续指令全部堆积
            Logger.e(TAG, e) { "command ${command.type} crashed" }
            ack(command, deviceId, AckStatus.FAILED, "EXEC_CRASH", e.message)
        }
    }

    // ==================== 锁屏相关 ====================

    /**
     * 远程锁屏。
     *
     * 两步都要做：① 我们自己的全屏锁定页（任何权限模式都能显示，且能解释原因）；
     * ② 系统级 `lockNow()`（灭屏并要求解锁凭据）。
     *
     * 只做 ② 的话，孩子解锁后立刻恢复自由使用；只做 ① 的话，
     * 在无障碍未开启的降级模式下可能被"最近任务"切走。两者叠加才有实际约束力。
     */
    private fun lockScreen(command: Command, deviceId: String): CommandAck {
        val reasonText = command.payloadString(KEY_REASON, "家长已远程锁定设备")
        lockController.requestLock(LockReason.REMOTE, detail = reasonText)
        _effects.tryEmit(EngineEffect.ShowLockScreen(reasonText))

        val result = admin.lockNow()
        return when {
            result.isOk -> ack(command, deviceId, AckStatus.SUCCESS)
            // 系统锁屏失败但应用内锁定页已生效 —— 属于降级成功，如实标注
            else -> ack(
                command, deviceId, AckStatus.SUCCESS,
                "PARTIAL", "应用内锁定已生效，系统级锁屏失败：${result.reasonOrEmpty()}"
            )
        }
    }

    private fun unlock(command: Command, deviceId: String): CommandAck {
        lockController.cancelTempUnlock()
        lockController.releaseAll()
        _effects.tryEmit(EngineEffect.DismissLockScreen)
        return ack(command, deviceId, AckStatus.SUCCESS)
    }

    /**
     * 限时解锁。
     *
     * 超过 [LockController.MAX_TEMP_UNLOCK_MINUTES] 的请求会被截断而**不是拒绝** ——
     * 家长在管控端手滑填 9999 分钟时，我们按上限执行并在回执里说明，
     * 比直接失败更符合预期（他的意图是"放行"，不是"什么都不做"）。
     *
     * 同时把本地对应的临时解锁申请标记为"已同意"：孩子端发起申请后一直显示"申请中"，
     * 家长同意的信号就藏在这条指令里，不回填的话申请状态永远不会闭环。
     * 家长未经申请主动放行时找不到匹配申请，属正常情况，静默跳过。
     */
    private suspend fun tempUnlock(command: Command, deviceId: String): CommandAck {
        val requested = command.payloadInt(KEY_DURATION_MINUTES, 30)
        if (requested <= 0) {
            return ack(command, deviceId, AckStatus.FAILED, "BAD_PAYLOAD", "durationMinutes 必须大于 0")
        }
        lockController.tempUnlock(requested)
        _effects.tryEmit(EngineEffect.DismissLockScreen)
        unlockRequestRepository.approveLatest(command.payloadString(KEY_PACKAGE_NAME))

        val capped = requested.coerceAtMost(LockController.MAX_TEMP_UNLOCK_MINUTES)
        return if (capped != requested) {
            ack(command, deviceId, AckStatus.SUCCESS, "CAPPED", "请求 ${requested}min 超上限，按 ${capped}min 执行")
        } else {
            ack(command, deviceId, AckStatus.SUCCESS)
        }
    }

    // ==================== 电源相关 ====================

    private fun reboot(command: Command, deviceId: String): CommandAck {
        if (!admin.can(Capability.REBOOT)) {
            return ack(command, deviceId, AckStatus.UNSUPPORTED, "NO_DEVICE_OWNER", "重启需要 Device Owner 权限")
        }
        // 重启前先让 app 层把待上报数据落盘/上传，否则本次会话日志会丢
        _effects.tryEmit(EngineEffect.FlushBeforeShutdown)
        val result = admin.reboot()
        return if (result.isOk) {
            ack(command, deviceId, AckStatus.SUCCESS)
        } else {
            ack(command, deviceId, AckStatus.FAILED, "REBOOT_FAILED", result.reasonOrEmpty())
        }
    }

    /**
     * 关机。
     *
     * **Android 没有任何公开 API 可以让应用关机**，Device Owner 也不行（只有 reboot）。
     * 唯一途径是系统签名应用调用 `PowerManager.shutdown()` 隐藏方法，
     * 这需要与 ROM 厂商定制或整机预装 —— 不在本项目的交付范围内。
     *
     * 这里选择如实回执 UNSUPPORTED，而不是"偷偷执行重启"来假装成功：
     * 家长以为设备关机了、实际只是重启并恢复运行，是更严重的信任问题。
     */
    private fun shutdown(command: Command, deviceId: String): CommandAck = ack(
        command, deviceId, AckStatus.UNSUPPORTED, "NO_PUBLIC_API",
        "Android 未开放应用层关机能力（DO 仅支持重启）；如需关机请改用重启或由预装 ROM 定制实现"
    )

    // ==================== 交给 app 层的动作 ====================

    /**
     * 家长端下发消息。
     *
     * 额外承载"拒绝临时解锁申请"的回填信号：若 payload 带 [KEY_REQUEST_ID]，
     * 说明这条消息是家长对某次申请的**拒绝**回执（契约扩展项）。
     * 此时本地把对应申请标记为 REJECTED，让孩子的"我的申请"列表从"申请中"翻成"已拒绝"，
     * 否则他会一直以为还在等审批而反复问家长。
     *
     * 普通消息不带该键，[UnlockRequestRepository.reject] 不会被调用，行为与之前完全一致。
     * reject 失败（如本地已无该记录）静默吞掉 —— 展示消息本身才是这条指令的主目标。
     */
    private suspend fun showMessage(command: Command, deviceId: String): CommandAck {
        val body = command.payloadString(KEY_CONTENT)
        if (body.isBlank()) {
            return ack(command, deviceId, AckStatus.FAILED, "BAD_PAYLOAD", "content 不能为空")
        }
        val requestId = command.payloadString(KEY_REQUEST_ID)
        if (requestId.isNotBlank()) {
            runCatching { unlockRequestRepository.reject(requestId) }
                .onFailure { Logger.w(TAG) { "reject unlock request $requestId failed: $it" } }
        }
        _effects.tryEmit(
            EngineEffect.ShowMessage(
                title = command.payloadString(KEY_TITLE, "来自家长的消息"),
                body = body,
                durationSec = command.payloadInt(KEY_DURATION_SEC, 0),
                blocking = command.payloadBoolean(KEY_BLOCKING, false),
                // 图片/视频/音频素材：霸屏时要在正中间展示，必须随指令一起下发。
                // 之前只传文字，家长发了一张图下去，孩子端只显示一行空白，等于发了条空消息。
                contentType = command.payloadString(KEY_CONTENT_TYPE, "TEXT"),
                mediaUrl = command.payloadString(KEY_MEDIA_URL),
                mediaName = command.payloadString(KEY_MEDIA_NAME)
            )
        )
        return ack(command, deviceId, AckStatus.SUCCESS)
    }

    /**
     * 远程截屏。
     *
     * 能力现状必须说清楚：即使是 Device Owner，Android 也**没有**静默截屏的公开 API。
     * 可行路径只有 MediaProjection，而它要求用户在弹窗里授权一次
     * （DO 下可用 `MediaProjection` + 预授权白名单，但仍受版本与 ROM 限制）。
     *
     * 因此这里只负责触发，最终能否成功由 app 层判定并单独上报。
     * 回执 SUCCESS 的含义是"任务已受理"，不是"图片已产出"。
     */
    private fun screenshot(command: Command, deviceId: String): CommandAck {
        // shotId 由服务端在落 PENDING 记录时生成，必须原样带回，
        // 否则上传的截图无法与家长端的那次请求关联，家长只会看到"一直在等待中"。
        _effects.tryEmit(
            EngineEffect.CaptureScreenshot(
                msgId = command.msgId,
                shotId = command.payloadString("shotId").ifBlank { command.msgId }
            )
        )
        return ack(command, deviceId, AckStatus.SUCCESS, "ACCEPTED", "截屏任务已受理，结果将随日志单独上报")
    }

    private fun locate(command: Command, deviceId: String): CommandAck {
        _effects.tryEmit(EngineEffect.RequestLocation(command.msgId))
        return ack(command, deviceId, AckStatus.SUCCESS, "ACCEPTED", "定位任务已受理，结果将随日志单独上报")
    }

    private fun photo(command: Command, deviceId: String): CommandAck {
        _effects.tryEmit(
            EngineEffect.CapturePhoto(
                msgId = command.msgId,
                taskId = command.payloadString("taskId").ifBlank { command.msgId }
            )
        )
        return ack(command, deviceId, AckStatus.SUCCESS, "ACCEPTED", "拍照任务已受理，结果将随日志单独上报")
    }

    private fun audioRecord(command: Command, deviceId: String): CommandAck {
        val taskId = command.payloadString("taskId").ifBlank { command.msgId }
        _effects.tryEmit(EngineEffect.StartAudioRecord(command.msgId, taskId))
        return ack(command, deviceId, AckStatus.SUCCESS, "ACCEPTED", "录音任务已受理，结果将随日志单独上报")
    }

    private fun stopAudioRecord(command: Command, deviceId: String): CommandAck {
        _effects.tryEmit(EngineEffect.StopAudioRecord)
        return ack(command, deviceId, AckStatus.SUCCESS)
    }

    private fun screenRecord(command: Command, deviceId: String): CommandAck {
        _effects.tryEmit(
            EngineEffect.StartScreenRecord(
                msgId = command.msgId,
                taskId = command.payloadString("taskId").ifBlank { command.msgId },
                resolution = command.payloadString("resolution", "HD_720P"),
                withAudio = command.payloadBoolean("withAudio", false)
            )
        )
        return ack(command, deviceId, AckStatus.SUCCESS, "ACCEPTED", "录屏任务已受理，结果将随日志单独上报")
    }

    private fun stopScreenRecord(command: Command, deviceId: String): CommandAck {
        _effects.tryEmit(EngineEffect.StopScreenRecord)
        return ack(command, deviceId, AckStatus.SUCCESS)
    }

    private fun flushLogs(command: Command, deviceId: String): CommandAck {
        _effects.tryEmit(EngineEffect.FlushLogs)
        return ack(command, deviceId, AckStatus.SUCCESS)
    }

    private fun setWatermark(command: Command, deviceId: String): CommandAck {
        _effects.tryEmit(
            EngineEffect.SetWatermark(
                enabled = command.payloadBoolean(KEY_ENABLED, true),
                content = command.payloadString(KEY_CONTENT)
            )
        )
        return ack(command, deviceId, AckStatus.SUCCESS)
    }

    private fun updateConfig(command: Command, deviceId: String): CommandAck {
        if (command.payload.isEmpty()) {
            return ack(command, deviceId, AckStatus.FAILED, "BAD_PAYLOAD", "payload 为空")
        }
        _effects.tryEmit(EngineEffect.UpdateRuntimeConfig(command.payload))
        return ack(command, deviceId, AckStatus.SUCCESS)
    }

    // ==================== 策略相关 ====================

    /**
     * 策略重置。
     *
     * 顺序是先清本地缓存再请求全量拉取。这中间存在一个短暂的"无策略窗口"，
     * 期间按 [com.padguard.core.data.model.policy.PolicyPackage.default] 兜底 ——
     * 默认策略是**最宽松**的（否则重置动作本身会误锁设备），
     * 所以这个窗口必须尽量短，app 层收到 effect 后应立即拉取。
     */
    private suspend fun resetPolicy(command: Command, deviceId: String): CommandAck {
        policyRepository.reset()
        appLimitEnforcer.resetSampling()
        eyeCareEnforcer.reset()
        _effects.tryEmit(EngineEffect.RefreshPolicy(afterReset = true))
        return ack(command, deviceId, AckStatus.SUCCESS)
    }

    private fun refreshPolicy(command: Command, deviceId: String): CommandAck {
        _effects.tryEmit(EngineEffect.RefreshPolicy(afterReset = false))
        return ack(command, deviceId, AckStatus.SUCCESS)
    }

    // ==================== 应用管控 ====================

    private fun installApp(command: Command, deviceId: String): CommandAck {
        if (!admin.can(Capability.SILENT_INSTALL)) {
            return ack(command, deviceId, AckStatus.UNSUPPORTED, "NO_DEVICE_OWNER", "静默安装需要 Device Owner / Profile Owner")
        }
        val url = command.payloadString(KEY_APK_URL)
        val pkg = command.payloadString(KEY_PACKAGE_NAME)
        if (url.isBlank() || pkg.isBlank()) {
            return ack(command, deviceId, AckStatus.FAILED, "BAD_PAYLOAD", "apkUrl / packageName 不能为空")
        }
        _effects.tryEmit(
            EngineEffect.InstallApp(
                apkUrl = url,
                packageName = pkg,
                versionCode = command.payloadLong(KEY_VERSION_CODE, 0L),
                msgId = command.msgId
            )
        )
        return ack(command, deviceId, AckStatus.SUCCESS, "ACCEPTED", "下载与安装异步执行，结果单独上报")
    }

    private fun uninstallApp(command: Command, deviceId: String): CommandAck {
        if (!admin.can(Capability.SILENT_UNINSTALL)) {
            return ack(command, deviceId, AckStatus.UNSUPPORTED, "NO_DEVICE_OWNER", "静默卸载需要 Device Owner / Profile Owner")
        }
        val pkg = command.payloadString(KEY_PACKAGE_NAME)
        if (pkg.isBlank()) {
            return ack(command, deviceId, AckStatus.FAILED, "BAD_PAYLOAD", "packageName 不能为空")
        }
        // 自我保护：任何情况下都不执行"卸载自己"，否则一条指令即可让设备彻底脱管
        if (pkg == SELF_GUARD_PLACEHOLDER || pkg.endsWith(".child")) {
            return ack(command, deviceId, AckStatus.FAILED, "SELF_PROTECT", "拒绝卸载管控端自身，解绑请走服务端解绑流程")
        }
        _effects.tryEmit(EngineEffect.UninstallApp(pkg, command.msgId))
        return ack(command, deviceId, AckStatus.SUCCESS, "ACCEPTED", "卸载异步执行，结果单独上报")
    }

    private fun setAppSuspended(command: Command, deviceId: String): CommandAck {
        val pkg = command.payloadString(KEY_PACKAGE_NAME)
        if (pkg.isBlank()) {
            return ack(command, deviceId, AckStatus.FAILED, "BAD_PAYLOAD", "packageName 不能为空")
        }
        if (!admin.can(Capability.SUSPEND_PACKAGES)) {
            return ack(command, deviceId, AckStatus.UNSUPPORTED, "NO_DEVICE_OWNER", "应用挂起需要 Device Owner / Profile Owner")
        }
        val suspended = command.payloadBoolean(KEY_SUSPENDED, true)
        val result = admin.setPackagesSuspended(listOf(pkg), suspended)
        return if (result.failed.isEmpty()) {
            ack(command, deviceId, AckStatus.SUCCESS)
        } else {
            ack(command, deviceId, AckStatus.FAILED, "SUSPEND_FAILED", result.error ?: "系统拒绝挂起 $pkg（未安装或受系统保护）")
        }
    }

    // ==================== 外设临时放行 ====================

    /**
     * 外设临时放行/回收。
     *
     * 典型用途：上课时禁用相机，但美术课需要临时开放 20 分钟。
     * 注意本指令是**临时覆盖**，下一次策略下发会被覆写回策略值 ——
     * 这是有意的设计：临时放行不应该悄悄变成永久状态。
     * 管控端若需长期放行，应下发新策略而不是发临时指令。
     */
    private fun peripheralOverride(command: Command, deviceId: String): CommandAck {
        val item = command.payloadString(KEY_ITEM)
        if (item.isBlank()) {
            return ack(command, deviceId, AckStatus.FAILED, "BAD_PAYLOAD", "item 不能为空")
        }
        val enabled = command.payloadBoolean(KEY_ENABLED, true)
        return when (val result = peripheralEnforcer.override(item, enabled)) {
            is OpResult.Ok -> ack(command, deviceId, AckStatus.SUCCESS)
            is OpResult.Unsupported -> ack(command, deviceId, AckStatus.UNSUPPORTED, "UNSUPPORTED_ITEM", result.reason)
            is OpResult.Failed -> ack(command, deviceId, AckStatus.FAILED, "OVERRIDE_FAILED", result.reason)
        }
    }

    // ==================== 高危 ====================

    /**
     * 恢复出厂。
     *
     * 三道门：① Device Owner；② payload 显式 `confirm=true`；③ 服务端已通过签名校验。
     * 第 ② 道看似冗余，实际是防"误点"的最后防线 ——
     * 管控端 UI 上的一次误触，代价是整台设备数据清空且不可恢复。
     */
    private fun wipe(command: Command, deviceId: String): CommandAck {
        if (!admin.can(Capability.WIPE)) {
            return ack(command, deviceId, AckStatus.UNSUPPORTED, "NO_DEVICE_OWNER", "恢复出厂需要 Device Owner")
        }
        if (!command.payloadBoolean(KEY_CONFIRM, false)) {
            return ack(command, deviceId, AckStatus.FAILED, "NEED_CONFIRM", "高危指令需 payload.confirm=true 二次确认")
        }
        _effects.tryEmit(EngineEffect.FlushBeforeShutdown)
        val result = admin.wipeData()
        return if (result.isOk) {
            ack(command, deviceId, AckStatus.SUCCESS)
        } else {
            ack(command, deviceId, AckStatus.FAILED, "WIPE_FAILED", result.reasonOrEmpty())
        }
    }

    // ==================== 工具 ====================

    private fun ack(
        command: Command,
        deviceId: String,
        status: AckStatus,
        errorCode: String? = null,
        errorMessage: String? = null
    ) = CommandAck(
        msgId = command.msgId,
        deviceId = deviceId,
        status = status,
        executedAt = timeProvider.now(),
        errorCode = errorCode,
        errorMessage = errorMessage
    )

    private fun OpResult.reasonOrEmpty(): String = when (this) {
        is OpResult.Ok -> ""
        is OpResult.Unsupported -> reason
        is OpResult.Failed -> reason
    }

    companion object {
        private const val TAG = "CommandExecutor"
        private const val SELF_GUARD_PLACEHOLDER = "com.padguard.child"

        // payload 键名，与接口契约 §4.1 保持一致
        const val KEY_REASON = "reason"
        const val KEY_DURATION_MINUTES = "durationMinutes"
        const val KEY_DURATION_SEC = "durationSec"
        const val KEY_TITLE = "title"
        const val KEY_CONTENT = "content"
        /** 信息发布素材类型：TEXT / IMAGE / VIDEO / AUDIO */
        const val KEY_CONTENT_TYPE = "contentType"
        /** 信息发布素材下载地址（服务端 /v1/files/{id}） */
        const val KEY_MEDIA_URL = "mediaUrl"
        /** 素材展示名（音频没有画面时用它占位） */
        const val KEY_MEDIA_NAME = "mediaName"
        /** 家长拒绝某次临时解锁申请时随 SHOW_MESSAGE 附带的申请 id（契约扩展项） */
        const val KEY_REQUEST_ID = "requestId"
        const val KEY_BLOCKING = "blocking"
        const val KEY_PACKAGE_NAME = "packageName"
        const val KEY_APK_URL = "apkUrl"
        const val KEY_VERSION_CODE = "versionCode"
        const val KEY_SUSPENDED = "suspended"
        const val KEY_ENABLED = "enabled"
        const val KEY_ITEM = "item"
        const val KEY_CONFIRM = "confirm"
    }
}

/**
 * 需要 app 层平台能力才能完成的副作用。
 *
 * 用 SharedFlow 而不是回调接口：前台服务可能因进程重启而重新订阅，
 * 回调接口在这种场景下容易留下悬空引用；SharedFlow 天然支持重新订阅与背压。
 */
sealed interface EngineEffect {
    data class ShowLockScreen(val reason: String) : EngineEffect
    data object DismissLockScreen : EngineEffect
    /**
     * @param blocking true = 霸屏：占满屏幕且期间禁止任何操作/退出，直到 [durationSec] 到点
     * @param contentType TEXT / IMAGE / VIDEO / AUDIO
     * @param mediaUrl 素材地址（服务端文件下载链接），霸屏时在正中间展示
     */
    data class ShowMessage(
        val title: String,
        val body: String,
        val durationSec: Int,
        val blocking: Boolean,
        val contentType: String = "TEXT",
        val mediaUrl: String = "",
        val mediaName: String = ""
    ) : EngineEffect

    /**
     * @param shotId 服务端为这次请求生成的截图 id，上传时必须原样带回以完成关联
     */
    data class CaptureScreenshot(val msgId: String, val shotId: String) : EngineEffect

    /**
     * @param taskId 服务端生成的媒体任务 id（PHOTO 走媒体任务通道，与截图不同表）
     */
    data class CapturePhoto(val msgId: String, val taskId: String) : EngineEffect
    data class RequestLocation(val msgId: String) : EngineEffect
    data class StartScreenRecord(
        val msgId: String, val taskId: String, val resolution: String, val withAudio: Boolean
    ) : EngineEffect
    data object StopScreenRecord : EngineEffect
    data class StartAudioRecord(val msgId: String, val taskId: String) : EngineEffect
    data object StopAudioRecord : EngineEffect
    data object FlushLogs : EngineEffect

    /** 重启/清除前先把待上报数据落盘并尽力上传 */
    data object FlushBeforeShutdown : EngineEffect

    data class RefreshPolicy(val afterReset: Boolean) : EngineEffect
    data class InstallApp(
        val apkUrl: String,
        val packageName: String,
        val versionCode: Long,
        val msgId: String
    ) : EngineEffect

    data class UninstallApp(val packageName: String, val msgId: String) : EngineEffect
    data class SetWatermark(val enabled: Boolean, val content: String) : EngineEffect
    data class UpdateRuntimeConfig(val params: Map<String, String>) : EngineEffect
}
