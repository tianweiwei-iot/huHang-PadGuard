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
    const val INSTALL_APP = "INSTALL_APP"
    const val UNINSTALL_APP = "UNINSTALL_APP"
    const val SET_APP_SUSPENDED = "SET_APP_SUSPENDED"
    const val REFRESH_POLICY = "REFRESH_POLICY"
    const val UPDATE_CONFIG = "UPDATE_CONFIG"
    const val FLUSH_LOGS = "FLUSH_LOGS"
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
    const val SUSPENDED = "suspended"
    /** 截图任务号，必须携带，否则上传结果无法与本次请求关联 */
    const val SHOT_ID = "shotId"
    /** 媒体任务号（拍照/录音/录屏） */
    const val TASK_ID = "taskId"
    const val TRIGGER_TYPE = "triggerType"
    const val RESOLUTION = "resolution"
    const val WITH_AUDIO = "withAudio"
}
