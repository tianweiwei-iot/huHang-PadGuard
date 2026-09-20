package com.padguard.domain

/**
 * 已知应用的「包名 → 图标地址」映射（公开 CDN，HTTPS 稳定）。
 *
 * 控制端展示被管控端应用使用记录时，若后端未返回 iconUrl，可按包名自动解析图标；
 * 本地数据源的 Mock 数据也复用本表，避免图标地址散落在多处造成维护负担。
 * 后续新增常驻应用，只需在此扩展一处即可。
 */
object KnownAppIcons {
    private val MAP = mapOf(
        "com.tencent.mm" to
            "https://upload.wikimedia.org/wikipedia/commons/thumb/8/89/Tencent_WeChat.svg/240px-Tencent_WeChat.svg.png",
        "com.tencent.mobileqq" to
            "https://upload.wikimedia.org/wikipedia/commons/thumb/5/56/QQ_logo_2016.svg/240px-QQ_logo_2016.svg.png",
        "com.ss.android.ugc.aweme" to
            "https://upload.wikimedia.org/wikipedia/commons/thumb/3/36/TikTok_logo.svg/240px-TikTok_logo.svg.png",
        "com.tencent.qqlive" to
            "https://upload.wikimedia.org/wikipedia/commons/thumb/2/22/Tencent_Video.svg/240px-Tencent_Video.svg.png",
        "com.netease.dwrg" to
            "https://upload.wikimedia.org/wikipedia/commons/thumb/2/22/NetEase_Logo.svg/240px-NetEase_Logo.svg.png",
        "com.kuaiya.player" to
            "https://upload.wikimedia.org/wikipedia/commons/thumb/0/06/Kuaiying_logo.svg/240px-Kuaiying_logo.svg.png"
    )

    /** 按包名取已知应用图标地址；未知应用返回 null（由调用方回退到字母徽章）。 */
    fun iconFor(packageName: String): String? = MAP[packageName]
}
