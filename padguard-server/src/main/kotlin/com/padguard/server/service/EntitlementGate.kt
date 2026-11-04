package com.padguard.server.service

/**
 * 订阅权益闸门 —— 把"订阅状态变化"传播到孩子端的核心逻辑。
 *
 * ## 为什么必须存在
 * 此前订阅只在**绑定设备时**校验一次（ensureCanBindForChild），此后订阅状态与孩子端完全脱钩：
 * - 试用/订阅到期后，孩子端继续执行最后一版策略（锁屏、黑名单、限额全部保留）；
 * - 家长端所有控制接口被 SubscriptionInterceptor 拦截，连"临时解锁""解绑设备"都调不动；
 * - 结果是孩子平板被冻结在到期瞬间的状态，唯一出路是运营后台人工补时长。
 *
 * ## 修法（利用既有自愈机制，零新增下发通道）
 * 策略包本来就有"期望包 vs 库存包"的漂移自愈（PolicyExtensionService.ensureRebuilt）。
 * 把权益状态作为 buildPolicyPackage 的输入：权益失效时期望包变成"放开包"，
 * 闸门在翻转沿触发一次 ensureRebuilt → 检测到漂移 → 重建 + 版本号前进 →
 * 心跳回执把新版本号带给孩子端 → 孩子端自动拉取 → 管控放开。续费后反向同理，自动恢复。
 */
internal object EntitlementGate {

    const val STATE_ACTIVE = "ACTIVE"
    const val STATE_SUSPENDED = "SUSPENDED"

    /** 试用与正式订阅都算有效权益；EXPIRED / REVOKED（退款吊销）一律失效 */
    fun isEntitled(subscriptionStatus: String?): Boolean =
        subscriptionStatus == "TRIAL" || subscriptionStatus == "ACTIVE"

    /**
     * 把已构建好的正常策略包就地改写为「放开包」。
     *
     * 原则：**付费能力全部停止，设备支配权全部归还**。
     * 各段保留原结构、只改开关与集合 —— 这样孩子端解析不会遇到缺字段回落默认值的意外
     * （例如 appLimit.dailyTotalMinutes 写 0 会被当成"整天禁用"，所以额度字段保留原值，
     * 仅关 enabled：孩子端 enabled=false 时不消费额度）。
     */
    fun applyRelease(pkg: MutableMap<String, Any?>) {
        // 外设全放开（家长端的摄像头/蓝牙等限制全部解除）
        pkg["peripheral"] = mapOf(
            "wifi" to "ALLOW", "bluetooth" to "ALLOW", "mobileData" to "ALLOW", "gps" to "ALLOW",
            "camera" to "ALLOW", "microphone" to "ALLOW", "usbFileTransfer" to "ALLOW",
            "nfc" to "ALLOW", "screenCapture" to "ALLOW", "screenRecord" to "ALLOW"
        )
        // 系统锁（救援通道）全放开：开发者选项 / USB 调试 / 未知来源安装 / 安全模式 / 恢复出厂
        pkg["systemLock"] = mapOf(
            "autoTime" to "FORCE_ON", "autoTimeZone" to "FORCE_ON",
            "developerOptions" to "ALLOW", "usbDebug" to "ALLOW", "unknownSourceInstall" to "ALLOW",
            "safeBoot" to "ALLOW", "factoryReset" to "ALLOW"
        )
        // 应用管控：黑白名单清空（mode 保留 BLACKLIST = 空 blacklist 不拦任何应用）、
        // 无 DO 密码闸门关闭、"已取消授权"集合清空
        val app = (pkg["app"] as? Map<*, *>)?.let { m ->
            mutableMapOf<String, Any?>().apply { m.forEach { (k, v) -> put(k.toString(), v) } }
        } ?: mutableMapOf()
        app["mode"] = "BLACKLIST"
        app["whitelist"] = emptyList<String>()
        app["blacklist"] = emptyList<String>()
        app["requirePasswordOnLaunch"] = false
        app["hiddenPackages"] = emptyList<String>()
        val install = (app["installPolicy"] as? Map<*, *>)?.let { m ->
            mutableMapOf<String, Any?>().apply { m.forEach { (k, v) -> put(k.toString(), v) } }
        } ?: mutableMapOf()
        install["requirePasswordOnInstall"] = false
        install["requirePasswordOnUninstall"] = false
        app["installPolicy"] = install
        pkg["app"] = app

        // 应用限时：总开关关 + 单应用规则清空（额度数值保留，见方法注释）
        val limit = (pkg["appLimit"] as? Map<*, *>)?.let { m ->
            mutableMapOf<String, Any?>().apply { m.forEach { (k, v) -> put(k.toString(), v) } }
        } ?: mutableMapOf()
        limit["enabled"] = false
        limit["rules"] = emptyList<Any>()
        pkg["appLimit"] = limit

        // 时段管控：开关关 + 规则清空
        val schedule = (pkg["schedule"] as? Map<*, *>)?.let { m ->
            mutableMapOf<String, Any?>().apply { m.forEach { (k, v) -> put(k.toString(), v) } }
        } ?: mutableMapOf()
        schedule["enabled"] = false
        schedule["rules"] = emptyList<Any>()
        pkg["schedule"] = schedule

        // 内容管控：屏蔽名单/分类清空、浏览器解禁、定时锁机关、安全搜索关
        val web = (pkg["web"] as? Map<*, *>)?.let { m ->
            mutableMapOf<String, Any?>().apply { m.forEach { (k, v) -> put(k.toString(), v) } }
        } ?: mutableMapOf()
        web["mode"] = "BLACKLIST"
        web["blacklist"] = emptyList<String>()
        web["browserDisabled"] = false
        web["smartShutdownEnabled"] = false
        web["safeSearch"] = false
        web["blockedCategories"] = emptyList<String>()
        pkg["web"] = web

        // 监控：定时截屏/定位归零（孩子端约定 0=关闭）、用量与网址采集关闭。
        // 心跳间隔保留 —— 它是"放开包"能持续送达与后续恢复的唯一通道，不能关。
        val mon = (pkg["monitoring"] as? Map<*, *>)?.let { m ->
            mutableMapOf<String, Any?>().apply { m.forEach { (k, v) -> put(k.toString(), v) } }
        } ?: mutableMapOf()
        mon["screenshotIntervalSec"] = 0
        mon["locationIntervalSec"] = 0
        mon["collectAppUsage"] = false
        mon["collectUrlHistory"] = false
        pkg["monitoring"] = mon

        // 护眼休息 / 护眼滤镜 / 未成年人模式 / 篱笆（kiosk）模式全部关闭。
        // minorMode 必须显式关：孩子端 MinorModeEnforcer 会把分龄基线派生到
        // appLimit/eyeCare/schedule，只关那三段的话派生会把限制又加回来。
        val eye = (pkg["eyeCare"] as? Map<*, *>)?.let { m ->
            mutableMapOf<String, Any?>().apply { m.forEach { (k, v) -> put(k.toString(), v) } }
        } ?: mutableMapOf()
        eye["enabled"] = false
        eye["forceLockDuringRest"] = false
        pkg["eyeCare"] = eye

        val prot = (pkg["eyeProtection"] as? Map<*, *>)?.let { m ->
            mutableMapOf<String, Any?>().apply { m.forEach { (k, v) -> put(k.toString(), v) } }
        } ?: mutableMapOf()
        prot["enabled"] = false
        pkg["eyeProtection"] = prot

        val kiosk = (pkg["kiosk"] as? Map<*, *>)?.let { m ->
            mutableMapOf<String, Any?>().apply { m.forEach { (k, v) -> put(k.toString(), v) } }
        } ?: mutableMapOf()
        kiosk["enabled"] = false
        pkg["kiosk"] = kiosk

        val minor = (pkg["minorMode"] as? Map<*, *>)?.let { m ->
            mutableMapOf<String, Any?>().apply { m.forEach { (k, v) -> put(k.toString(), v) } }
        } ?: mutableMapOf()
        minor["enabled"] = false
        minor["curfewEnabled"] = false
        pkg["minorMode"] = minor

        // 安全防护（防卸载/防强停/防改时钟/root 拦截）一并放开：
        // 订阅已结束，不再限制孩子对设备的支配权（否则连"卸载这个 App"都做不到，等于软锁定）。
        pkg["security"] = mapOf(
            "antiUninstall" to false, "antiForceStop" to false,
            "antiClockTamper" to false, "blockRoot" to false
        )
        // guard 段（操作密码哈希）与 scene/sceneMode/effective* 保持原样：
        // 密码只在受保护界面拦截，不构成使用障碍，随原包走即可。
    }
}
