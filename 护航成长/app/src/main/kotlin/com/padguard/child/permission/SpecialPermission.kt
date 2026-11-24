package com.padguard.child.permission

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.padguard.child.guard.GuardA11y
import com.padguard.child.guard.OperationGuardAccessibilityService
import com.padguard.core.common.Logger

/**
 * 需要「家长在系统页面亲手确认」的授权项 —— 全 App **唯一的授权目录**。
 *
 * ## 为什么必须单独拎出来
 * 这几项**不属于运行时权限**：既不出现在 `requestPermissions` 的系统弹窗里，
 * 也无法用 `checkSelfPermission` 查询，更关键的是——
 * **连 Device Owner 都无权代为授予**（`setPermissionGrantState` 只作用于危险权限，
 * 对 AppOps 直接返回 false）。唯一路径是用户在系统设置页亲手打开开关。
 *
 * ## 授权专项后这里成了「单一事实源」
 * 装机清单（[com.padguard.child.consent.OneTimeConsentViewModel]）、App 内补开页、
 * 授权回退自愈（[AuthorizationKeeper]）、常驻通知自检，全部读同一份 [values]。
 * 历史上有三套各自手写的权限清单，结果是「声明了却没引导」「引导了却没声明」
 * 「掉失了却没人管」同时存在。新增一项权限现在只改这一个枚举即可全链路生效。
 *
 * ## 四类「人工确认」项的差异（决定自动接续怎么判完成）
 * - **可检测（[detectable] = true）**：悬浮窗 / 使用情况 / 无障碍 / 电池优化 /
 *   精确闹钟 / 通知，都有官方查询 API。拨完开关 → 轮询查到变 true → 自动接回并跳下一项。
 * - **不可检测（[detectable] = false）**：厂商「自启动 / 后台弹出界面」开关，
 *   Android 没有公开查询接口，任何第三方 App 都读不到状态。
 *   对这类项**绝不谎报"已开启"**：完成判定改成「已把家长带到设置页、且页面已返回」，
 *   清单标 [CONFIRMED]「已带您前往设置（请确认开启）」，由家长自己确认开关。
 *
 * ## 缺这些项的后果是**静默失效**，这是最危险的地方
 * - [USAGE_STATS] 没开 → 前台应用探测为空 → 用量恒 0、单应用限额永不触发；
 * - [OVERLAY] 没开 → 全局水印静默不显示；
 * - [BATTERY_OPTIMIZATION] 没加白 → 息屏进 Doze 后 MQTT 心跳/定位/推送被冻结，每晚脱管；
 * - [EXACT_ALARM] 被撤销 → 时段边界闹钟变钝，「21:00 该锁，21:20 才锁」；
 * - [APP_NOTIFICATIONS] 被关 → 授权引导通知、锁屏全屏通知全部收不到，且家长端只看到"连接中"。
 */
enum class SpecialPermission(
    val key: String,
    val label: String,
    /** 缺失后果（家长看得懂的白话，不写技术名词） */
    val impact: String,
    /** 本项有意义的最低 Android API；低于它一律不进清单（避免多弹一次无意义的设置页） */
    val minSdk: Int,
    /** 是否只有国产/定制 ROM 才有这项开关；原生 Android 上不进清单 */
    val oemOnly: Boolean = false,
    /** 是否有公开、可靠的状态查询 API（决定自动接续用"轮询到授予"还是"页面返回"） */
    val detectable: Boolean = true,
    /** 是否属于「可选增强」（跳过时文案不同，且不计入强制重授权） */
    val optional: Boolean = false,
    /** 掉失后是否要把家长拉回授权流程（不可检测项恒 false，否则每次自检都误报） */
    val requiredForReauth: Boolean = true
) {
    OVERLAY(
        "overlay",
        "悬浮窗",
        "全局水印、无权限提示覆盖层依赖它显示，未开启则这些提示静默不出现",
        Build.VERSION_CODES.M
    ),
    USAGE_STATS(
        "usageStats",
        "使用情况访问",
        "应用使用时长统计与单个应用限额都依赖它，未开启则限额设了不生效、用量恒为 0",
        Build.VERSION_CODES.LOLLIPOP
    ),
    /**
     * 安装未知应用（API 26+）。
     *
     * 已是设备所有者时走 PackageInstaller 静默安装，本项由 [appliesTo] 直接剔除，
     * 不占用家长的一次设置页跳转。
     */
    INSTALL_PACKAGES(
        "installPackages",
        "安装未知应用",
        "家长端远程推送安装应用时需要；已是设备所有者则不需要（可静默安装）",
        Build.VERSION_CODES.O
    ),
    /**
     * 操作闸门无障碍服务。
     *
     * 它不属于 AppOps，而是写在 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 里，
     * 但仍与上面几项**同构**：App 无法自开、DO 也无法代开，只有家长在系统设置页手动启用。
     *
     * ## 跳转策略（务必稳定，不玩 ROM 黑魔法）
     * 无障碍详情页**不接受**让我们直接落到本应用服务开关的通用深链；各厂商 ROM 还各自改写了入口，
     * 靠 `ComponentName` 硬跳在 A 手机能开、B 手机就崩，正式产品不可依赖。
     * 因此只走官方 `ACTION_ACCESSIBILITY_SETTINGS`（落到无障碍列表），
     * 并以 `Intent.EXTRA_COMPONENT_NAME` 做**尽力深链**：原生/部分 ROM 会直接定位到本服务详情，
     * 识别不了的 ROM 静默降级为列表页——两条路都安全、都不会崩。
     * 真正的"免手动返回"由 [com.padguard.child.consent.ConsentReturnPresenter] 的轮询守护 +
     * 全屏 Intent 兜底，不靠深链。
     */
    ACCESSIBILITY(
        "accessibility",
        "无障碍服务（操作闸门）",
        "密码闸门的系统级拦截依赖它，未启用则孩子可从系统设置绕过管控、卸载本应用；" +
            "请同时完成「后台运行」一项的防清理设置，避免系统一键清理停用本服务",
        Build.VERSION_CODES.LOLLIPOP
    ),

    // ---------------- 授权专项新增项 ----------------

    /**
     * 电池优化白名单（authorization gap #1）。
     *
     * 原本只躺在「我的 → 权限引导」里，装机这一趟完全不引导，也不进任何掉失自检：
     * 家长装完机以为万事大吉，孩子晚上一息屏，Doze 冻结后台，锁屏指令收不到。
     * 现在进装机清单并参与回退自愈。
     *
     * 合规边界：**不声明** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（Play 家庭政策 +
     * 国内商店都会打回），只跳系统「不受电池优化限制的应用」列表页由家长手动选择。
     */
    BATTERY_OPTIMIZATION(
        "batteryOptimization",
        "电池优化白名单",
        "加入豁免名单后息屏仍能锁屏、定位和收消息；未加入时每晚息屏可能脱管",
        Build.VERSION_CODES.M
    ),

    /**
     * 精确闹钟 / 「闹钟和提醒」（authorization gap #2）。
     *
     * `SCHEDULE_EXACT_ALARM` 已在 Manifest 声明，`AlarmPingSender` 与
     * `LockBoundaryAlarmReceiver` 都依赖它，但用户可在系统里撤销「闹钟和提醒」；
     * API 34+ 且 targetSdk 34+ 时默认就是不授予。撤销后代码只静默降级成
     * `setAndAllowWhileIdle`，家长端看不到任何提示 —— 典型的"声明了却没引导"。
     */
    EXACT_ALARM(
        "exactAlarm",
        "闹钟和提醒（精确闹钟）",
        "时段锁屏到点精确唤醒、息屏后守护心跳依赖它；未允许时锁屏可能晚到、心跳变钝",
        Build.VERSION_CODES.S
    ),

    /**
     * 通知开关（authorization gap #3）。
     *
     * `POST_NOTIFICATIONS` 只是运行时权限：家长一旦勾了「不再询问」永久拒绝，
     * 装机流水线不会再弹第二次，而 App 内也没有 `ACTION_APP_NOTIFICATION_SETTINGS`
     * 补开入口 —— 结果授权引导通知、全屏锁屏通知全部收不到。
     * 这里用 `NotificationManager.areNotificationsEnabled()` 覆盖「权限被拒 +
     * 通知渠道被关」两种形态，并提供系统通知设置页补开。
     */
    APP_NOTIFICATIONS(
        "appNotifications",
        "通知",
        "授权引导、锁屏与消息提醒都靠通知送达；关闭后家长端会一直停在「连接中」且平板无任何提示",
        Build.VERSION_CODES.O
    ),

    /**
     * 厂商后台运行开关（authorization gap #4）。
     *
     * 华为/荣耀「自启动 + 后台活动」、小米「自启动 + 后台弹出界面」、OPPO/vivo
     * 「后台运行 / 后台弹窗」等，都是 OEM 私有的 AppOps，**没有公开查询接口**，
     * 也没有统一的官方设置页。缺了它们，进程被杀后守护服务不重启、
     * 锁屏页/授权页从后台拉不起来，在国产平板上是最常见的"装了却管不住"。
     *
     * 因此 detectable = false：只读 ROM 适配层探测到的私有页，带家长去一次，
     * 如实标「已带您前往设置（请确认开启）」状态，绝不假装已经打开。
     * 原生 Android / 未知品牌（[RomSettings.Vendor.GENERIC]）上本项不进清单。
     */
    OEM_BACKGROUND(
        "oemBackground",
        "后台运行（自启动 / 后台弹出界面）",
        "本品牌的省电与后台管理会杀掉守护服务、拦截后台弹窗；「一键清理 / 清理加速」" +
            "还可能停用无障碍开关导致管控失效。请在其「权限管家 / 启动管理」里" +
            "把本应用设为允许自启与后台运行，并按下方防清理步骤给本应用加锁",
        Build.VERSION_CODES.LOLLIPOP,
        oemOnly = true,
        detectable = false,
        optional = true,
        requiredForReauth = false
    );

    /**
     * 当前是否已授予。
     *
     * 每一项都要走各不相同的专用 API，没有统一的查询口径。
     * [OEM_BACKGROUND] 恒 false：平台不提供查询能力，返回 true 会变成对家长说谎。
     */
    fun isGranted(context: Context): Boolean = when (this) {
        USAGE_STATS -> runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return@runCatching false
            val uid = context.applicationInfo.uid
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

        OVERLAY ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        // 异常一律按「未授予」：查询失败时宁可多带家长去一次设置页确认，
        // 也不能静默当成已开启 —— 后者会让远程安装静默失效且界面毫无提示。
        // 目录里其余各项的失败兜底都是 false，这里曾误写成 true（fail-open）。
        INSTALL_PACKAGES -> runCatching {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                context.packageManager.canRequestPackageInstalls()
        }.getOrDefault(false)

        ACCESSIBILITY -> GuardA11y.isEnabled(context)

        BATTERY_OPTIMIZATION -> runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@runCatching true
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return@runCatching false
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)

        // 版本守卫放在 runCatching 外面：canScheduleExactAlarms 是 API 31 才有的方法，
        // 把 SDK 判断藏在 lambda 里 lint 的 NewApi 分析未必能穿透，会误报成编译门禁错误。
        EXACT_ALARM ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
            else runCatching {
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    ?: return@runCatching false
                am.canScheduleExactAlarms()
            }.getOrDefault(false)

        // 阈值与 [minSdk] 对齐（O = 26）：低于 O 本项根本不进清单，
        // 旧代码写 N(24) 让"是否适用"与"是否已授予"两套口径分裂，单测也难以覆盖。
        APP_NOTIFICATIONS -> runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@runCatching true
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return@runCatching false
            nm.areNotificationsEnabled()
        }.getOrDefault(false)

        OEM_BACKGROUND -> false
    }

    /**
     * 本项在这台设备上是否应当出现在清单里（**纯函数**，便于 JVM 单测覆盖版本 × 厂商矩阵）。
     *
     * 三条闸门缺一不可：
     * 1. Android 版本达到 [minSdk]（低版本上该项要么不存在，要么随安装自动具备）；
     * 2. 厂商相关项只在该厂商存在（原生 Android 不该被带去一个不存在的「权限管家」）；
     * 3. 已是 DO/PO 时不需要 [INSTALL_PACKAGES]：设备所有者走 PackageInstaller 静默安装，
     *    压根不请求这个权限，算作"缺失"只会让家长去做一次无意义的设置跳转。
     */
    fun appliesTo(sdkInt: Int, vendor: RomSettings.Vendor, hasOwnerPrivileges: Boolean): Boolean {
        if (sdkInt < minSdk) return false
        if (oemOnly && vendor == RomSettings.Vendor.GENERIC) return false
        if (this == INSTALL_PACKAGES && hasOwnerPrivileges) return false
        return true
    }

    /** 生产便捷入口：注入本机真实的 SDK / 厂商 / 管控身份。 */
    fun applies(context: Context, hasOwnerPrivileges: Boolean): Boolean = appliesTo(
        Build.VERSION.SDK_INT,
        RomSettings.vendorOfDevice(),
        hasOwnerPrivileges
    )

    /**
     * 官方设置页候选（按优先级）。
     *
     * **带上 package data 是关键**：不带的话系统会甩出一个几十项的全量应用列表，
     * 家长要在里面自己找「护航成长」，实际常常找不到就放弃了。
     * 无障碍页例外：它不接受 package data，只能进「无障碍」总页后手工找到服务条目。
     *
     * **绝不能加 FLAG_ACTIVITY_NEW_TASK**：所有 Intent 都由授权页经
     * `registerForActivityResult(StartActivityForResult)` 拉起，落在 App 自身任务栈内即可。
     * 一旦带 NEW_TASK，设置页会被开到另一个任务栈，与结果契约冲突 —— 系统立刻回传
     * RESULT_CANCELED 且设置页打不开，正是"点了去开启却没进页面"的根因。
     */
    fun officialIntents(context: Context): List<Intent> {
        val pkgUri = Uri.fromParts("package", context.packageName, null)
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(pkgUri)
        return when (this) {
            USAGE_STATS -> listOf(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(pkgUri),
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            )
            OVERLAY -> listOf(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).setData(pkgUri),
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            )
            INSTALL_PACKAGES -> listOf(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(pkgUri),
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            )
            ACCESSIBILITY -> listOf(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    // 必须放**真正的 ComponentName 对象**：`EXTRA_COMPONENT_NAME` 的契约是 Parcelable。
                    // 塞 flattenToString() 字符串会让系统设置的 getParcelableExtra 抛
                    // ClassCastException（表现为"设置页闪一下就没了"）。换成对象后最坏只是"不深链"。
                    //
                    // 类名走编译期引用而不是写死字符串：服务被重命名/换包时编译期即报错，
                    // 不再出现"深链静默失效、家长被丢到无障碍总页却找不到本应用"的哑故障。
                    // `ComponentName(context, Class)` 也会自动带上真实 applicationId
                    // （debug 包的 `.debug` 后缀），不依赖手写包名。
                    putExtra(
                        Intent.EXTRA_COMPONENT_NAME,
                        ComponentName(context, OperationGuardAccessibilityService::class.java)
                    )
                }
            )
            BATTERY_OPTIMIZATION -> listOf(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
            // API 31+ 的官方「闹钟和提醒」直达页；低版本本项不进清单，这里仍给应用详情兜底。
            EXACT_ALARM -> listOf(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(pkgUri),
                appDetails
            )
            APP_NOTIFICATIONS -> listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                appDetails
            )
            // 没有官方页：只靠 ROM 适配层 + 应用详情兜底（见 openSettings）。
            OEM_BACKGROUND -> emptyList()
        }
    }

    companion object {
        /**
         * 稳定 key 的对外出口。
         *
         * 值一律**引用枚举实例自己的 [key]**，不再抄一份字符串字面量：
         * 枚举构造期 companion 尚未初始化，所以构造参数只能是字面量（见各枚举项），
         * 但反过来引用是安全的 —— Kotlin 保证枚举常量先于 companion 初始化。
         * 这样新增一项只需在枚举里写一次 key，[RomSettings.privateComponents] 的
         * `when` 分支、账本、清单、跳转链全部自动对齐，不会再出现
         * 「厂商候选页按一个永远匹配不上的 key 去查」那种静默退化。
         *
         * 刻意用 `val` 而非 `const val`：const 只能接字面量，会逼我们抄回第二份。
         * `when (key)` 分支不要求编译期常量，普通 val 完全可用。
         */
        val KEY_OVERLAY = OVERLAY.key
        val KEY_USAGE_STATS = USAGE_STATS.key
        val KEY_INSTALL_PACKAGES = INSTALL_PACKAGES.key
        val KEY_ACCESSIBILITY = ACCESSIBILITY.key
        val KEY_BATTERY_OPTIMIZATION = BATTERY_OPTIMIZATION.key
        val KEY_EXACT_ALARM = EXACT_ALARM.key
        val KEY_APP_NOTIFICATIONS = APP_NOTIFICATIONS.key
        val KEY_OEM_BACKGROUND = OEM_BACKGROUND.key

        fun fromKey(key: String): SpecialPermission? = values().firstOrNull { it.key == key }

        /**
         * 当前缺失项（按声明顺序，便于 UI 稳定展示）。
         *
         * 一次性装机流程要的就是这份清单：装机这一趟把全部项收走。
         * [appliesTo] 会先按 Android 版本 / 厂商 / 管控身份把不适用的项剔除，
         * 否则低版本设备会被带去根本不存在的「闹钟和提醒」页。
         *
         * ## [includeUndetectable]：两种"缺失"口径，混用会出事
         * - `true`（默认）：**装机流水线 / 状态盘点**口径。含不可检测的厂商开关
         *   （[OEM_BACKGROUND] 等），它们 [isGranted] 恒 false、平台没有查询接口，
         *   必须靠"带家长去一次设置页 + 页面返回"判定完成，不能用状态轮询。
         *   装机流水线用 confirmedKeys 单独兜住"回来仍是缺失态"，不会卡死。
         * - `false`：**待补齐 / 自动接续**口径。只留可检测且确实未授予的项 ——
         *   这类项拨完开关能被 API 明确判定为完成，才适合驱动「一键补齐」按钮、
         *   「还剩几项」计数、以及"从设置页返回要不要自动跳下一项"。
         *
         * 为什么必须分：不可检测项恒 false，若混进"待补齐"判定，在华为/小米等
         * 定制 ROM 上 [missing] 永远非空 → 补开页「一键补齐」永不消失、
         * "全部已开启"永不可达、每次从设置页返回都被一键补齐重新拉回授权流水线
         * （PermissionGuideViewModel.startConsentReview），
         * 家长看到的就是「权限明明开了还显示未开、反复提示」。
         * 这类项的正确呈现是由总览**单独如实提示**「需到设置页确认（系统不提供状态查询）」，
         * 既不谎报已开启，也不冒充待办。
         */
        fun missing(
            context: Context,
            hasOwnerPrivileges: Boolean,
            includeUndetectable: Boolean = true
        ): List<SpecialPermission> {
            val sdk = Build.VERSION.SDK_INT
            val vendor = RomSettings.vendorOfDevice()
            return values().filter { perm ->
                perm.appliesTo(sdk, vendor, hasOwnerPrivileges) &&
                    (includeUndetectable || perm.detectable) &&
                    !perm.isGranted(context)
            }
        }

        /** 本设备上参与清单的全部项（含已授予的），供「授权总览」完整展示。 */
        fun applicable(context: Context, hasOwnerPrivileges: Boolean): List<SpecialPermission> {
            val sdk = Build.VERSION.SDK_INT
            val vendor = RomSettings.vendorOfDevice()
            return values().filter { it.appliesTo(sdk, vendor, hasOwnerPrivileges) }
        }

        /** 单项便捷的缺失判断，供状态横幅等非装机场景使用。 */
        fun isMissing(context: Context, perm: SpecialPermission, hasOwnerPrivileges: Boolean): Boolean =
            perm in missing(context, hasOwnerPrivileges)

        /**
         * 稳妥跳转到设置页：**四级降级链**，任何厂商 / 任何 Android 版本都能落到一个可打开的页。
         *
         * 1. 官方专属页（带 package data，多数 ROM 直达本应用开关）；
         * 2. 厂商私有页（仅 [OEM_BACKGROUND] 等确实没有官方页的项优先，其余作为候选）；
         * 3. 官方列表页；
         * 4. 应用详情页（所有厂商、所有版本恒存在，从那里能进任意一项权限）。
         *
         * 每个候选都经 [RomSettings.resolveFirst] 的 `resolveActivity` 校验；
         * 个别 ROM 改过 Action / 不接受 package data 时自动退到下一级，
         * 全失败才返回 null 由调用方提示手动路径，绝不静默无反应、更不会崩。
         */
        fun openSettings(context: Context, perm: SpecialPermission): Intent? {
            val official = perm.officialIntents(context)
            val private = RomSettings.privateIntents(context, perm.key)
            val chain = when (perm) {
                OEM_BACKGROUND ->
                    // 没有官方页：厂商私有页优先，应用详情兜底（原生 Android 上本项已被 appliesTo 剔除）
                    private + official + listOf(RomSettings.appDetailsIntent(context))
                BATTERY_OPTIMIZATION ->
                    // 电池优化白名单：OEM 私有页（如华为「受保护应用 / 电池管理」）才是能找到并
                    // 加白本应用的入口；系统级 ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS 在多数
                    // 国产 ROM 只列全局开关、且常找不到本应用，点了无法操作。故 OEM 页优先于官方页；
                    // 原生 Android（private 为空）仍走官方 Action + 应用详情兜底。
                    if (private.isNotEmpty()) {
                        private + official + listOf(RomSettings.appDetailsIntent(context))
                    } else {
                        listOfNotNull(official.firstOrNull()) +
                            official.drop(1) +
                            listOf(RomSettings.appDetailsIntent(context))
                    }
                else ->
                    listOfNotNull(official.firstOrNull()) +
                        private +
                        official.drop(1) +
                        listOf(RomSettings.appDetailsIntent(context))
            }
            val intent = RomSettings.resolveFirst(context, chain)
            if (intent == null) {
                Logger.e(TAG) { "no activity to open settings for ${perm.key}" }
            } else {
                Logger.i(TAG) { "open settings for ${perm.key} (${chain.size} candidates)" }
            }
            return intent
        }

        private const val TAG = "SpecialPermission"
    }
}
