package com.padguard.core.data.model.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 应用内容分类。
 *
 * ## 为什么分类要落在端上而不是服务端
 * 服务端只认识"包名黑名单"，它不知道一个包名是游戏还是网课。若把分类判断放在服务端，
 * 就得先把设备上的应用清单上传、等服务端分类完再下发 ——
 * 孩子装一个新游戏到被管控之间会有几分钟到几十分钟的窗口，
 * 而这段时间恰恰是新游戏最容易被玩的时候。
 * 端上分类可以在下一个采样点（15s）内生效。
 *
 * ## 类别边界（这条决定了会不会误伤）
 * - [SOCIAL] **只指陌生人社交**（陌陌、探探、Soul 这类），
 *   **不包含**微信 / QQ / 钉钉 —— 家长要靠微信联系孩子，把它们禁掉是帮倒忙，
 *   而且指南约束的是"陌生人私信"能力，不是通讯工具本身。
 * - [LIVE] 指以直播打赏为主要形态的应用；B站这类"视频为主、直播为辅"的
 *   归入 [SHORT_VIDEO]，按短视频口径处理，避免一刀切禁掉大量学习区内容。
 * - [GAME] 只含明确的游戏；棋牌、益智类因为分龄默认允许，也不在此列。
 */
@Serializable
enum class AppCategory(
    val label: String,
    /** 该类别默认是否需要管控（用于家长端展示说明） */
    val sensitive: Boolean
) {
    /** 游戏 */
    GAME("游戏", sensitive = true),

    /** 短视频 */
    SHORT_VIDEO("短视频", sensitive = true),

    /** 直播（含打赏） */
    LIVE("直播", sensitive = true),

    /** 陌生人社交 */
    SOCIAL("陌生人社交", sensitive = true),

    /** 学习教育 */
    EDUCATION("学习教育", sensitive = false),

    /** 工具 */
    TOOL("工具", sensitive = false),

    /** 未分类 */
    OTHER("其他", sensitive = false);

    companion object {
        /** 由服务端/本地配置里的字符串解析；未知值归为 [OTHER] 而不是抛异常 */
        fun parse(value: String?): AppCategory =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: OTHER
    }
}

/**
 * 内容分类目录：包名 → 类别。
 *
 * ## 匹配规则
 * 先看精确匹配，再按**包名前缀**匹配。前缀是为了覆盖渠道包与马甲包
 * （如 `com.netease.` 下挂着网易全线游戏），逐个枚举既写不完也维护不动。
 *
 * ## 维护说明（重要）
 * 这张表是**保守子集**，不是全量应用库：
 * - 只收录在管设备上真实出现过、且类别明确无争议的应用；
 * - 误判的代价远大于漏判 —— 把网课判成游戏会让家长失去信任，
 *   而漏掉一个小众游戏只是"这个档位下还能玩"，可以靠家长手工加黑名单补上。
 * - 因此新增条目时必须确认该包名的**主形态**，有争议的宁可归入 [AppCategory.OTHER]。
 *
 * 未来若接入服务端分类库，用下发结果覆盖本表即可，匹配逻辑不变。
 */
object AppCategoryCatalog {

    /** 精确包名 → 类别 */
    private val exact: Map<String, AppCategory> = mapOf(
        // ---------- 游戏 ----------
        "com.tencent.tmgp.sgame" to AppCategory.GAME,      // 王者荣耀
        "com.tencent.tmgp.pubgmhd" to AppCategory.GAME,    // 和平精英
        "com.tencent.tmgp.cf" to AppCategory.GAME,         // 穿越火线
        "com.tencent.tmgp.speedmobile" to AppCategory.GAME,// QQ飞车
        "com.mojang.minecraftpe" to AppCategory.GAME,      // Minecraft
        "com.supercell.clashofclans" to AppCategory.GAME,
        "com.supercell.clashroyale" to AppCategory.GAME,
        "com.king.candycrushsaga" to AppCategory.GAME,
        "com.miHoYo.enterprise.NGHToD" to AppCategory.GAME,// 崩坏3
        "com.miHoYo.GenshinImpact" to AppCategory.GAME,    // 原神
        "com.miHoYo.hkrpg" to AppCategory.GAME,            // 崩坏：星穹铁道
        "com.lilithgame.hgame.gp" to AppCategory.GAME,     // 万国觉醒
        "com.hypergryph.arknights" to AppCategory.GAME,    // 明日方舟

        // ---------- 短视频 ----------
        "com.ss.android.ugc.aweme" to AppCategory.SHORT_VIDEO,      // 抖音
        "com.ss.android.ugc.trill" to AppCategory.SHORT_VIDEO,      // TikTok
        "com.ss.android.ugc.lite" to AppCategory.SHORT_VIDEO,       // 抖音极速版
        "com.kuaishou.nebula" to AppCategory.SHORT_VIDEO,           // 快手
        "com.smile.gifmaker" to AppCategory.SHORT_VIDEO,            // 快手（旧包名）
        "com.kuaishou.mininebula" to AppCategory.SHORT_VIDEO,       // 快手极速版
        "com.xingin.xhs" to AppCategory.SHORT_VIDEO,                // 小红书
        "com.tencent.weishi" to AppCategory.SHORT_VIDEO,            // 微视
        "com.bilibili.app.in" to AppCategory.SHORT_VIDEO,           // 哔哩哔哩（视频为主）

        // ---------- 直播 ----------
        "com.douyu.client" to AppCategory.LIVE,       // 斗鱼
        "com.huya.android" to AppCategory.LIVE,       // 虎牙
        "com.tencent.live" to AppCategory.LIVE,       // 腾讯直播
        "com.kaola.live" to AppCategory.LIVE,         // 花椒直播
        "com.yy.hiyo" to AppCategory.LIVE,            // YY

        // ---------- 陌生人社交 ----------
        "com.immomo.momo" to AppCategory.SOCIAL,      // 陌陌
        "com.p1.mobile.putong" to AppCategory.SOCIAL, // 探探
        "com.soulapp.cn" to AppCategory.SOCIAL        // Soul
    )

    /** 包名前缀 → 类别（覆盖渠道包与马甲包） */
    private val prefixes: List<Pair<String, AppCategory>> = listOf(
        "com.tencent.tmgp." to AppCategory.GAME,   // 腾讯游戏发行线（王者、和平精英、飞车…）
        "com.netease." to AppCategory.GAME,        // 网易游戏（阴阳师、第五人格、蛋仔派对…）
        "com.miHoYo." to AppCategory.GAME,
        "com.lilithgame." to AppCategory.GAME,
        "com.hypergryph." to AppCategory.GAME,
        "com.supercell." to AppCategory.GAME,
        "com.ea.game." to AppCategory.GAME,
        "com.kiloo." to AppCategory.GAME,
        "com.imangi." to AppCategory.GAME,

        "com.ss.android.ugc." to AppCategory.SHORT_VIDEO, // 字节系（抖音/TikTok 及各衍生）
        "com.kuaishou." to AppCategory.SHORT_VIDEO,

        "com.douyu." to AppCategory.LIVE,
        "com.huya." to AppCategory.LIVE
    )

    /**
     * 判定包名所属类别。
     *
     * 未命中任何规则时返回 [AppCategory.OTHER] —— 即"不因分类而被管控"，
     * 与"保守子集"的设计一致：不认识的应用不自动禁，避免误伤。
     */
    fun categoryOf(packageName: String): AppCategory {
        if (packageName.isBlank()) return AppCategory.OTHER
        exact[packageName]?.let { return it }
        return prefixes.firstOrNull { packageName.startsWith(it.first) }?.second
            ?: AppCategory.OTHER
    }

    /** 属于 [category] 的**已知**包名集合（用于把"类别"落成"黑名单"） */
    fun packagesOf(category: AppCategory): Set<String> =
        exact.filterValues { it == category }.keys

    /** 该包名是否属于某个已知类别（OTHER 视为未知） */
    fun isKnown(packageName: String): Boolean = categoryOf(packageName) != AppCategory.OTHER
}
