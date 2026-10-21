package com.padguard.server.service

/**
 * 下行指令契约（服务端 → 被管控端 App，项目「护航成长」）。
 *
 * 为什么必须有这个文件：
 * 指令类型与 payload 字段名是两端之间的**隐式约定**。一旦写错，
 * 服务端对家长端仍然返回 200 success，但孩子端会以 `BAD_PAYLOAD` 拒绝执行，
 * 外部表现为「操作成功、设备毫无反应」，且不留任何服务端错误，极难排查。
 * 已实际发生：`SHOW_MESSAGE` 服务端下发 `text`，孩子端读取 `content`，
 * 导致信息发布功能长期不可用。
 *
 * 契约的唯一权威在对端：
 * `护航成长/core/engine/src/main/kotlin/com/padguard/core/engine/command/CommandExecutor.kt`
 * （其中 KEY_* 常量定义了孩子端真正读取的字段名）。
 * 修改本文件前请先核对该文件，禁止在调用点再写魔法字符串。
 */

/** 指令类型，取值必须与孩子端 `CommandType` 枚举一致 */
object CommandType {
    const val LOCK_SCREEN = "LOCK_SCREEN"
    const val UNLOCK = "UNLOCK"
    const val TEMP_UNLOCK = "TEMP_UNLOCK"
    const val SHOW_MESSAGE = "SHOW_MESSAGE"
    const val SCREENSHOT = "SCREENSHOT"
    const val LOCATE = "LOCATE"
    const val PHOTO = "PHOTO"
    const val RECORD = "RECORD"
    const val STOP_RECORD = "STOP_RECORD"
    const val SCREEN_RECORD = "SCREEN_RECORD"
    const val STOP_SCREEN_RECORD = "STOP_SCREEN_RECORD"
    /** 开启实时看屏推流（孩子端持续上传屏幕帧，家长端拉流观看） */
    const val LIVE_VIEW_START = "LIVE_VIEW_START"
    /** 停止实时看屏推流 */
    const val LIVE_VIEW_STOP = "LIVE_VIEW_STOP"
    const val INSTALL_APP = "INSTALL_APP"
    const val UNINSTALL_APP = "UNINSTALL_APP"
    const val SET_APP_SUSPENDED = "SET_APP_SUSPENDED"
    /** 隐藏/显示应用：家长端"使用权限"开关。与挂起的区别见孩子端 CommandType.SET_APP_HIDDEN */
    const val SET_APP_HIDDEN = "SET_APP_HIDDEN"
    /**
     * 权限管理：授予 / 收回某包名的运行时权限（DO/PO 才能改别人的权限）。
     * payload { packageName, permission, grant }。
     */
    const val SET_RUNTIME_PERMISSION = "SET_RUNTIME_PERMISSION"
    /**
     * 功能管理：整包下发设备功能开关（相机/截屏/恢复出厂/安全启动/USB/自动对时等）。
     * payload 为扁平的 featureKey -> "true"/"false"，孩子端逐项映射成 DPM 限制。
     */
    const val SET_FEATURE_POLICY = "SET_FEATURE_POLICY"
    const val REFRESH_POLICY = "REFRESH_POLICY"
    const val UPDATE_CONFIG = "UPDATE_CONFIG"
    const val FLUSH_LOGS = "FLUSH_LOGS"
    /**
     * 受管控水印开关。
     *
     * 用途不是"装饰"，而是替代一个**做不到的诉求**：有家长期望录屏时不要出现
     * 系统那个红色 REC 指示器。该指示器由系统在 MediaProjection 会话期间绘制，
     * 属于 Android 的隐私红线，Device Owner / 系统签名都无法关闭。
     * 于是改为提供本应用自己的水印：家长可自定义文案（如"本机已开启家长监护"），
     * 至少在管控生效时段给出明确提示。
     */
    const val SET_WATERMARK = "SET_WATERMARK"
    /**
     * 整机管控 暂停 / 恢复。
     *
     * 对应家长端「一键释放 / 恢复对平板的全部管控」诉求：
     * 下发后孩子端 [com.padguard.core.engine.guard.GuardMasterSwitch] 置为对应态——
     * - active=false：收起锁屏浮层、解除设置 / 应用拦截、DO 设备归还权限、不钉 Kiosk，
     *   设备回到完全可用，家长 / 操作员可自由进入系统设置；
     * - active=true：恢复管控（仍处于锁定态时重铺锁屏等）。
     * 缺省按 true 处理（见孩子端 CommandExecutor.payloadBoolean 的默认值）。
     * payload { active }，字段名必须与孩子端 CommandExecutor.KEY_ACTIVE 一致（"active"）。
     */
    const val SET_MANAGEMENT_ACTIVE = "SET_MANAGEMENT_ACTIVE"
}

/** payload 字段名，取值必须与孩子端 CommandExecutor 的 KEY_* 常量一致 */
object CommandKey {
    /** 锁屏原因 */
    const val REASON = "reason"
    /** 限时解锁时长（分钟） */
    const val DURATION_MINUTES = "durationMinutes"
    /** 消息全屏展示时长（秒） */
    const val DURATION_SEC = "durationSec"
    /** 消息标题，留空时孩子端用默认文案 */
    const val TITLE = "title"
    /** 素材类型：TEXT / IMAGE / VIDEO / AUDIO */
    const val CONTENT_TYPE = "contentType"
    /** 素材下载地址（霸屏时孩子端要在正中展示） */
    const val MEDIA_URL = "mediaUrl"
    /** 素材展示名 */
    const val MEDIA_NAME = "mediaName"
    /** 消息正文，孩子端要求非空，否则判 BAD_PAYLOAD */
    const val CONTENT = "content"
    /** 关联的解锁申请单号，孩子端收到后会作废该申请 */
    const val REQUEST_ID = "requestId"
    /** 是否阻塞式全屏展示 */
    const val BLOCKING = "blocking"
    const val PACKAGE_NAME = "packageName"
    const val APK_URL = "apkUrl"
    const val VERSION_CODE = "versionCode"
    /** 权限管理：被操作的权限名（如 android.permission.CAMERA） */
    const val PERMISSION = "permission"
    /** 权限管理：true=授予，false=收回 */
    const val GRANT = "grant"
    // —— 功能管理：featureKey 常量（取值必须与对端 DeviceAdminBridge.applyFeaturePolicy 一致）——
    const val FEATURE_CAMERA_DISABLED = "camera_disabled"
    const val FEATURE_SCREENSHOT_DISABLED = "screenshot_disabled"
    const val FEATURE_FACTORY_RESET_DISABLED = "factory_reset_disabled"
    const val FEATURE_SAFE_BOOT_DISABLED = "safe_boot_disabled"
    const val FEATURE_UNKNOWN_SOURCES_DISABLED = "unknown_sources_disabled"
    const val FEATURE_CONFIG_DATETIME_DISABLED = "config_datetime_disabled"
    const val FEATURE_USB_DEBUG_DISABLED = "usb_debug_disabled"
    const val FEATURE_USB_DATA_DISABLED = "usb_data_disabled"
    const val FEATURE_STATUS_BAR_DISABLED = "status_bar_disabled"
    const val FEATURE_AUTO_TIME_ENFORCED = "auto_time_enforced"
    const val SUSPENDED = "suspended"
    const val HIDDEN = "hidden"
    /** 截图任务号，必须携带，否则上传结果无法与本次请求关联 */
    const val SHOT_ID = "shotId"
    /** 媒体任务号（拍照/录音/录屏） */
    const val TASK_ID = "taskId"
    const val TRIGGER_TYPE = "triggerType"
    const val RESOLUTION = "resolution"
    const val WITH_AUDIO = "withAudio"
    /** 布尔开关，孩子端读取后决定启用还是关闭（水印 / 挂起 / 隐藏等通用开关字段） */
    const val ENABLED = "enabled"
    /** 整机管控 暂停/恢复 开关：true=恢复管控，false=释放整机（必须与孩子端 CommandExecutor.KEY_ACTIVE 一致） */
    const val ACTIVE = "active"
    /**
     * 实时看屏画质档位：true=高清（更宽、更高 JPEG 质量），false=标准（省带宽）。
     *
     * 必须随 LIVE_VIEW_START 下发：孩子端推流参数原本是写死的常量，
     * 家长端「高清画面」开关改了以后设备端毫不知情，表现为"设了高清还是模糊"。
     */
    const val HIGH_DEFINITION = "highDefinition"
}
