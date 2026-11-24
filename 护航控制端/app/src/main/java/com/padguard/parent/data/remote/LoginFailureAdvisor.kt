package com.padguard.parent.data.remote

/**
 * 把"登录/注册失败"的一句话，翻译成**下一步该做什么**。
 *
 * ## 为什么按文案判定，而不是错误码
 *
 * 服务端 `AuthService.login` 里，"用户不存在"与"密码错误"都抛
 * `ParentErr.UNAUTHORIZED`（1002）。这是有意的：错误码统一可以少给攻击者一个
 * 号码枚举的判据。代价是端上拿不到可分支的 code，只能看 message。
 *
 * 于是这里把文案作为**跨端契约**来对待：
 *   - 文案来源固定为服务端 `AuthService.kt`（"用户不存在" / "密码错误" /
 *     "密码连续错误次数过多…锁定" / "账号已被冻结" / "尚未激活"）；
 *   - 新增一类失败文案时，本类的关键词表要同步补，否则只会退化成 [Kind.OTHER]
 *     （即"仍然显示原文，只是不给快捷动作"），不会误导用户。
 *
 * 判定顺序有意从"最具体的账号状态"排到"最泛的连接问题"：
 * 一条消息里同时出现多个关键词时，账号层面的结论更该优先。
 */
object LoginFailureAdvisor {

    enum class Kind {
        /** 该手机号还没有账号 —— 唯一应该直接给"去注册"出口的情况 */
        NOT_REGISTERED,

        /** 账号存在但口令不对 —— 应引导"忘记密码"而不是注册 */
        WRONG_PASSWORD,

        /** 连续失败导致临时锁定 —— 应告知等待，不要继续试 */
        LOCKED,

        /** 被后台冻结 */
        FROZEN,

        /** 尚未激活 */
        INACTIVE,

        /** 连不上服务器 —— 该去改地址/启动服务端 */
        CONNECTION,

        OTHER
    }

    /** 每条关键词都对应服务端真实产出的一句文案（见类注释）。 */
    private val RULES: List<Pair<Kind, List<String>>> = listOf(
        Kind.NOT_REGISTERED to listOf("用户不存在", "该手机号未注册", "手机号未注册"),
        Kind.LOCKED to listOf("临时锁定", "账号已临时锁定"),
        Kind.FROZEN to listOf("冻结"),
        Kind.INACTIVE to listOf("尚未激活", "等待开通"),
        Kind.WRONG_PASSWORD to listOf("密码错误", "账号或密码错误"),
    )

    fun classify(message: String?): Kind {
        if (message.isNullOrBlank()) return Kind.OTHER
        RULES.forEach { (kind, hints) ->
            if (hints.any { message.contains(it) }) return kind
        }
        return if (ServerProbe.looksLikeConnectionFailure(message)) Kind.CONNECTION else Kind.OTHER
    }

    /**
     * 是否该在登录失败处直接给一个「去注册」按钮。
     *
     * 只有"手机号确实没注册"才给 —— 密码错、被锁、被冻结都给注册入口的话，
     * 用户会误以为要重新注册一个新账号，反而把已有账号/绑定关系丢掉。
     */
    fun suggestsRegistration(message: String?): Boolean = classify(message) == Kind.NOT_REGISTERED

    /**
     * 是否该引导"忘记密码"。
     *
     * 与登录页常驻的「忘记密码？」入口互补：这里只在确实输错口令时主动提示，
     * 平时不打扰。
     */
    fun suggestsPasswordReset(message: String?): Boolean = classify(message) == Kind.WRONG_PASSWORD
}
