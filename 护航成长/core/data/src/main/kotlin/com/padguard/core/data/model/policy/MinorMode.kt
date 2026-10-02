package com.padguard.core.data.model.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 未成年人模式（分龄合规底座）。
 *
 * ## 为什么要独立成一块，而不是散在 appLimit / eyeCare / schedule 里
 * 《移动互联网未成年人模式建设指南》要求的是**一组必须同时成立**的约束：
 * 分龄推荐 + 时长上限 + 连续使用提醒 + 夜间宵禁 + 退出需家长验证。
 * 若把这些参数拆进既有策略对象，会出现两个实际问题：
 *
 * 1. **一键启动无从下手**：家长点一次「开启未成年人模式」，端上要改写 4 个策略对象；
 *    漏改任何一处都是"看起来开了、实际没生效"的静默不合规。
 * 2. **合规审计无法回答**：监管/客户问"这台设备当前是不是未成年人模式、几岁档"，
 *    没有一个字段能直接回答，只能反推。
 *
 * 因此这里用一个 [MinorModePolicy] 作为**唯一事实源**，
 * 由 [com.padguard.core.engine.enforcer.MinorModeEnforcer] 在每次生效时把分龄基线
 * 派生（并压严）到 appLimit / eyeCare / schedule 上。家长仍可在分龄上限内自行收紧。
 *
 * ## 时长口径（严格对齐指南）
 * - 不满 16 周岁：每日总时长不超过 1 小时（60 分钟）
 * - 16 周岁以上不满 18 周岁：每日总时长不超过 2 小时（120 分钟）
 * - 连续使用超过 30 分钟应提示休息（低龄档更严，属"更保守"方向，不违反指南）
 * - 每日 22 时至次日 6 时不向未成年人提供服务（低龄档就寝时间更早）
 *
 * 家长上调额度是合规允许的（指南约束的是**默认**与**推荐**），
 * 但上调行为必须留痕（[MinorModePolicy.parentOverridden]），
 * 否则出问题无法界定是产品默认不合规还是家长自主设置。
 */
@Serializable
enum class AgeBand(
    /** 展示名，家长端直接取用 */
    val label: String,
    /** 每日总时长上限（分钟） */
    val dailyLimitMinutes: Int,
    /** 连续使用上限（分钟），超过即提示休息 */
    val continuousMinutes: Int,
    /** 每次休息时长（分钟） */
    val restMinutes: Int,
    /** 宵禁起始 "HH:mm" */
    val curfewStart: String,
    /** 宵禁结束 "HH:mm" */
    val curfewEnd: String,
    /** 是否允许游戏类应用 */
    val allowGame: Boolean,
    /** 是否允许短视频 */
    val allowShortVideo: Boolean,
    /** 是否允许直播 */
    val allowLive: Boolean,
    /** 是否允许社交（含陌生人私信） */
    val allowSocial: Boolean,
    /** 是否允许充值 / 打赏 */
    val allowRecharge: Boolean
) {
    /** 不满 3 周岁：以启蒙内容为主，不提供游戏/短视频/直播 */
    UNDER_3(
        label = "不满3岁",
        dailyLimitMinutes = 30,
        continuousMinutes = 15,
        restMinutes = 10,
        curfewStart = "20:00",
        curfewEnd = "07:00",
        allowGame = false,
        allowShortVideo = false,
        allowLive = false,
        allowSocial = false,
        allowRecharge = false
    ),

    /** 3 周岁以上不满 8 周岁：启蒙教育、兴趣培养；不提供直播与充值 */
    BAND_3_8(
        label = "3–8岁",
        dailyLimitMinutes = 60,
        continuousMinutes = 20,
        restMinutes = 10,
        curfewStart = "21:00",
        curfewEnd = "07:00",
        allowGame = true,
        allowShortVideo = false,
        allowLive = false,
        allowSocial = false,
        allowRecharge = false
    ),

    /** 8 周岁以上不满 12 周岁：通识教育与知识科普；限制短视频，不提供直播与充值 */
    BAND_8_12(
        label = "8–12岁",
        dailyLimitMinutes = 60,
        continuousMinutes = 30,
        restMinutes = 10,
        curfewStart = "22:00",
        curfewEnd = "06:00",
        allowGame = true,
        allowShortVideo = true,
        allowLive = false,
        allowSocial = false,
        allowRecharge = false
    ),

    /** 12 周岁以上不满 16 周岁：不提供直播，充值需限额 */
    BAND_12_16(
        label = "12–16岁",
        dailyLimitMinutes = 60,
        continuousMinutes = 30,
        restMinutes = 10,
        curfewStart = "22:00",
        curfewEnd = "06:00",
        allowGame = true,
        allowShortVideo = true,
        allowLive = false,
        allowSocial = true,
        allowRecharge = true
    ),

    /** 16 周岁以上不满 18 周岁：时长放宽至 2 小时，其余按成年边界过渡 */
    BAND_16_18(
        label = "16–18岁",
        dailyLimitMinutes = 120,
        continuousMinutes = 30,
        restMinutes = 10,
        curfewStart = "22:00",
        curfewEnd = "06:00",
        allowGame = true,
        allowShortVideo = true,
        allowLive = true,
        allowSocial = true,
        allowRecharge = true
    );

    /**
     * 由年龄（周岁）推导档位。
     *
     * 边界一律按"不满"处理：`fromAge(8)` 得到 [BAND_8_12] 而非 [BAND_3_8]，
     * 与指南"3周岁以上不满8周岁"的表述保持一致。
     */
    companion object {
        fun fromAge(age: Int): AgeBand = when {
            age < 3 -> UNDER_3
            age < 8 -> BAND_3_8
            age < 12 -> BAND_8_12
            age < 16 -> BAND_12_16
            else -> BAND_16_18
        }

        /** 由服务端下发的字符串解析；未知值回落到 8–12 岁档（覆盖大多数在管设备） */
        fun parseOrNull(value: String?): AgeBand? =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }

        fun parse(value: String?): AgeBand = parseOrNull(value) ?: BAND_8_12
    }
}

/**
 * 未成年人模式策略。
 *
 * 字段全部带默认值，服务端可以只下发 `{"enabled": true, "ageBand": "BAND_8_12"}`
 * 就让端上按该档位的合规基线执行，其余项由端上补齐 —— 这对"一键启动"至关重要。
 */
@Serializable
data class MinorModePolicy(
    /** 未成年人模式总开关。这是 P1 的核心：一键启动/一键退出 */
    @SerialName("enabled") val enabled: Boolean = false,

    @SerialName("ageBand") val ageBand: AgeBand = AgeBand.BAND_8_12,

    /** 夜间宵禁开关（指南：22:00–06:00 不提供服务） */
    @SerialName("curfewEnabled") val curfewEnabled: Boolean = true,

    /** 宵禁起始 "HH:mm"，默认取 [AgeBand.curfewStart] */
    @SerialName("curfewStart") val curfewStart: String = "",

    /** 宵禁结束 "HH:mm"，默认取 [AgeBand.curfewEnd] */
    @SerialName("curfewEnd") val curfewEnd: String = "",

    /** 每日总时长上限（分钟）；0 表示沿用 [AgeBand.dailyLimitMinutes] */
    @SerialName("dailyLimitMinutes") val dailyLimitMinutes: Int = 0,

    /** 周末每日总时长上限（分钟）；0 表示与工作日相同 */
    @SerialName("weekendLimitMinutes") val weekendLimitMinutes: Int = 0,

    /** 连续使用多少分钟提示休息；0 表示沿用 [AgeBand.continuousMinutes] */
    @SerialName("continuousMinutes") val continuousMinutes: Int = 0,

    /** 每次休息时长（分钟）；0 表示沿用 [AgeBand.restMinutes] */
    @SerialName("restMinutes") val restMinutes: Int = 0,

    /**
     * 家长临时豁免到期的墙钟时间戳；0 表示无豁免。
     *
     * 用于"今晚特殊情况再多玩半小时"这类场景。豁免只压过宵禁与护眼，
     * **不解除**每日总时长 —— 时长是合规硬约束，不能由临时豁免突破。
     */
    @SerialName("parentExemptUntil") val parentExemptUntil: Long = 0L,

    /**
     * 退出未成年人模式是否需要家长验证。
     *
     * 默认 true：孩子在设备上是退不出去的（锁机页没有退出入口，
     * 设置项需要家长端确认）。若为 false，孩子可自行降级到普通模式，
     * 整个未成年人模式形同虚设 —— 因此服务端下发 false 时按 true 处理。
     */
    @SerialName("requireParentAuthToExit") val requireParentAuthToExit: Boolean = true,

    /** 家长是否手动调整过默认额度（合规审计用，端上只读展示） */
    @SerialName("parentOverridden") val parentOverridden: Boolean = false
) {
    /** 实际生效的每日时长上限：家长设为 0 时回落分龄默认值 */
    fun effectiveDailyLimit(): Int =
        if (dailyLimitMinutes > 0) dailyLimitMinutes else ageBand.dailyLimitMinutes

    /** 实际生效的周末时长上限 */
    fun effectiveWeekendLimit(): Int = when {
        weekendLimitMinutes > 0 -> weekendLimitMinutes
        dailyLimitMinutes > 0 -> dailyLimitMinutes
        else -> ageBand.dailyLimitMinutes
    }

    fun effectiveContinuousMinutes(): Int =
        if (continuousMinutes > 0) continuousMinutes else ageBand.continuousMinutes

    fun effectiveRestMinutes(): Int =
        if (restMinutes > 0) restMinutes else ageBand.restMinutes

    fun effectiveCurfewStart(): String =
        curfewStart.takeIf { it.isNotBlank() } ?: ageBand.curfewStart

    fun effectiveCurfewEnd(): String =
        curfewEnd.takeIf { it.isNotBlank() } ?: ageBand.curfewEnd

    /** 退出保护：服务端下发 false 也按 true 处理，避免配置错误开后门 */
    fun effectiveRequireParentAuth(): Boolean = true

    /**
     * 本档位应当被管控的内容类别。
     *
     * 取值来自 [AgeBand] 的 `allow*` 开关：不允许的类别即被管控。
     * 只覆盖 [com.padguard.core.data.model.policy.AppCategory.sensitive] 的类别 ——
     * 教育、工具这类永远不因"分龄"被禁，否则"开启未成年人模式"会等同于"只留网课"，
     * 那是家长自己配白名单时才该发生的事。
     */
    fun blockedCategories(): Set<AppCategory> {
        if (!enabled) return emptySet()
        val out = mutableSetOf<AppCategory>()
        if (!ageBand.allowGame) out += AppCategory.GAME
        if (!ageBand.allowShortVideo) out += AppCategory.SHORT_VIDEO
        if (!ageBand.allowLive) out += AppCategory.LIVE
        if (!ageBand.allowSocial) out += AppCategory.SOCIAL
        return out
    }

    /** 当前是否处于家长豁免期（墙钟判定，豁免是"今晚"这类墙钟语义） */
    fun isExempt(nowMillis: Long): Boolean =
        parentExemptUntil > 0 && nowMillis < parentExemptUntil

    /**
     * 给定"当日分钟数"（0..1439）是否落在宵禁区间内。
     *
     * 抽成纯函数是为了让**首页与锁机判定共用同一份口径**：
     * 首页如果另写一遍区间判断，一旦与 [com.padguard.core.engine.enforcer.MinorModeEnforcer]
     * 的边界处理不一致（跨零点、起止相等），就会出现
     * "首页显示可用、设备却锁着"这种自相矛盾的界面。
     */
    fun isCurfewAt(minutesOfDay: Int): Boolean {
        if (!enabled || !curfewEnabled) return false
        val start = ScheduleRule.toMinutes(effectiveCurfewStart())
        val end = ScheduleRule.toMinutes(effectiveCurfewEnd())
        return when {
            start == end -> true // 起止相同视为全天宵禁（异常配置，按最严格处理）
            end <= start -> minutesOfDay >= start || minutesOfDay < end // 跨零点
            else -> minutesOfDay >= start && minutesOfDay < end
        }
    }

    /** 综合豁免后的最终判定：首页与执行器都走这里，口径必然一致 */
    fun curfewActiveAt(minutesOfDay: Int, nowMillis: Long): Boolean {
        if (isExempt(nowMillis)) return false
        return isCurfewAt(minutesOfDay)
    }

    /**
     * 把分龄基线压到既有策略对象上。
     *
     * 规则是**只压严、不放宽**：家长已经设得更严（额度更小、护眼更短）的保留家长值；
     * 未设置或比基线松的，一律收敛到基线。这样"一键开启"不会把家长精心配的
     * 更严规则冲掉，同时保证任何情况下都不低于合规底线。
     */
    fun coerceAppLimit(current: AppLimitPolicy): AppLimitPolicy {
        if (!enabled) return current
        val weekdayCap = effectiveDailyLimit()
        val weekendCap = effectiveWeekendLimit()
        return current.copy(
            enabled = true,
            weekdayTotalMinutes = tighten(current.weekdayTotalMinutes, weekdayCap),
            weekendTotalMinutes = tighten(current.weekendTotalMinutes, weekendCap),
            dailyTotalMinutes = tighten(current.dailyTotalMinutes, weekdayCap)
        )
    }

    fun coerceEyeCare(current: EyeCarePolicy): EyeCarePolicy {
        if (!enabled) return current
        val continuous = effectiveContinuousMinutes()
        return current.copy(
            enabled = true,
            continuousMinutes = tighten(current.continuousMinutes, continuous),
            restMinutes = current.restMinutes.takeIf { it > 0 } ?: effectiveRestMinutes()
        )
    }

    /**
     * 把本档位应管控的内容类别落成包名黑名单，并入家长已有的应用策略。
     *
     * ## 为什么是"并集"而不是"替换"
     * 家长手工拉黑的某个应用（比如孩子沉迷的某一款特定游戏）必须保留：
     * 若按类别整体替换，切一次档位就把家长的历史设置冲掉了，
     * 表现为"我明明禁了这个应用，换个年龄段它又回来了"。
     *
     * ## 为什么同时动白名单
     * 白名单模式的语义是"不在名单里的一律不可用"。若只加黑名单不动白名单，
     * 白名单里那个游戏条目依然让孩子能打开它 —— 分类管控形同虚设。
     * 因此白名单模式下改为**剔除**命中条目。
     *
     * @param installedPackages 设备上已安装的包名；用于把"类别"落成具体包名。
     *   传入空集合时退化为只使用分类目录内的已知包名（仍能拦住常见应用）。
     */
    fun coerceAppPolicy(current: AppPolicy, installedPackages: Set<String> = emptySet()): AppPolicy {
        if (!enabled) return current
        val categories = blockedCategories()
        if (categories.isEmpty()) return current

        // 候选包名：目录内的已知包名 + 设备上已安装且命中类别的（覆盖目录没收录的新应用）
        val candidates = (AppCategoryCatalog.packagesOf(AppCategory.GAME) +
            AppCategoryCatalog.packagesOf(AppCategory.SHORT_VIDEO) +
            AppCategoryCatalog.packagesOf(AppCategory.LIVE) +
            AppCategoryCatalog.packagesOf(AppCategory.SOCIAL) +
            installedPackages).toSet()

        val blocked = candidates.filter { pkg ->
            AppCategoryCatalog.categoryOf(pkg) in categories
        }.toSet()

        if (blocked.isEmpty()) return current

        return when (current.mode) {
            AppListMode.BLACKLIST -> current.copy(
                blacklist = (current.blacklist + blocked).distinct(),
                // 白名单同时清掉：否则黑名单模式下虽然用不到白名单，
                // 但家长哪天切成白名单模式，这些应用又会立刻放行。
                whitelist = current.whitelist.filterNot { it in blocked }
            )
            AppListMode.WHITELIST -> current.copy(
                whitelist = current.whitelist.filterNot { it in blocked },
                blacklist = (current.blacklist + blocked).distinct()
            )
        }
    }

    /** 取值：已有值且更严则保留，否则用基线；0 视为"未设置" */
    private fun tighten(current: Int, cap: Int): Int =
        if (current > 0 && current <= cap) current else cap
}
