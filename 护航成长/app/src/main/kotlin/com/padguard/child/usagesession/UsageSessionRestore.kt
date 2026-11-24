package com.padguard.child.usagesession

/**
 * 使用计时会话的「冷启动恢复」决策（纯函数，无 Android 依赖）。
 *
 * ## 为什么单独抽出来
 * 恢复逻辑原本写在 [UsageSessionManager.restorePersistedSession] 里，直接读
 * SharedPreferences 并就地改状态 —— 它同时被 `Context` / `SystemClock` 绑住，
 * 任何一条分支都只能靠真机验证。而这段逻辑恰好决定了**离线时每日总时长额度还成不成立**，
 * 是最需要被穷举的分支集合，因此把决策抽成纯函数、把副作用留在原处。
 *
 * ## 三条曾经真实失守的路径
 * 1. **跨零点后重启 → 当天完全不管控**。旧实现一旦发现快照是"昨天"就 `clear()` 后返回，
 *    `UiState` 停在默认值（`active=false, needsStartConfirm=false`）：
 *    `isExpired()` 因 `!active` 恒为 false，`PolicyEngine.tick` 拿到的 `dailyLimitExpired`
 *    永远是 false，且「计时开始」浮层也不会弹 —— 孩子只要在 00:05 关机再开机（或让 ROM
 *    夜间杀掉进程），当天总时长额度就整天消失，联网前无任何自愈路径。
 *    现在跨日进入**待确认**（`needsStartConfirm=true`）并 restamp 落盘：
 *    设备保持等待页、弹确认浮层，孩子确认后才起表 —— 修复「昨天用满额度、今天仍锁屏」
 *    （跨日自动按满额续算）与「不确认就白拿一天额度」两个方向的问题。
 * 2. **同日但未激活 → 额度信息丢失**。旧实现在 `!active` 时直接返回，`quotaMinutes` 留 0，
 *    家长端/孩子端首页会显示"剩余 0 分钟"却不锁屏，对外承诺与实际不一致。
 * 3. **重起表不落盘 → 重启即白送额度**。跨日重置后若不把"今天这份"重新落盘，
 *    每次重启都会再按满额度重算一次 —— 变成"重启就能无限续杯"的新绕过面。
 *    因此跨日分支必须返回 [Plan.restamp] = true，由调用方立即落盘。
 *
 * ## 明确不做的判断
 * 额度未知（`quota + extended <= 0`）时不猜数字：`KEY_QUOTA` 缺省就是 0，
 * 无法区分"家长设了 0 分钟"与"快照里没有这一项"。此时同样进入待确认
 * （起表起点由服务端 `start()` 决定，本地无需知道额度），但等待页与浮层照常工作。
 * "本地额度权威缺失"的口径与旧版一致，不在恢复层擅自发明额度。
 */
internal object UsageSessionRestore {

    /** 落盘快照的原始字段（对应 `sessionPrefs` 里的 KEY_*）。 */
    data class Snapshot(
        /** 原始 dayKey：服务端 ISO（2026-11-04）或本地紧凑格式（20261104）都可能 */
        val dayKey: String?,
        val active: Boolean,
        val remainingMs: Long,
        /** 落盘时的服务端墙钟 */
        val serverNow: Long,
        /** 落盘时的单调时钟读数 */
        val savedElapsed: Long,
        val quotaMinutes: Int,
        val extendedMinutes: Int,
        val timeControlEnabled: Boolean,
        /** 暂停中的快照（冻结的 remainingMs 不再按流逝扣减） */
        val paused: Boolean = false,
        val pauseSource: String? = null,
        val pauseStartedElapsed: Long = 0L
    )

    /**
     * 恢复计划。字段与 [UsageSessionManager.UiState] 一一对应，调用方只做赋值，不再判断。
     */
    data class Plan(
        val active: Boolean,
        val needsStartConfirm: Boolean,
        val remainingMs: Long,
        val serverNow: Long,
        val quotaMinutes: Int,
        val extendedMinutes: Int,
        val timeControlEnabled: Boolean,
        /** true = 必须把「今天」这一份重新落盘（跨日重起表后防止重启白送额度） */
        val restamp: Boolean,
        /** true = 恢复后仍处于暂停态（设备保持锁定，必须显式继续计时） */
        val paused: Boolean = false,
        val pauseSource: String? = null,
        val pauseStartedElapsed: Long = 0L
    )

    /** 归一化日切键：两种格式都只保留数字后比较（与服务端 ISO 口径对齐）。 */
    fun normalizeDayKey(raw: String?): String? =
        raw?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }

    /**
     * @param snapshot 上次落盘的快照；null 表示从未落过盘
     * @param todayKey 本地当前日切键（紧凑格式，来自 `UsageRepository.dayKey()`）
     * @param nowElapsed 当前单调时钟读数
     * @param nowWall 当前受信墙钟（`TimeProvider.now()`）
     * @return null 表示"没有可用快照，保持默认态"
     */
    fun plan(
        snapshot: Snapshot?,
        todayKey: String,
        nowElapsed: Long,
        nowWall: Long
    ): Plan? {
        if (snapshot == null) return null
        val day = normalizeDayKey(snapshot.dayKey) ?: return null

        // 家长关掉「时间管控」总开关：不得起表、不得提示，否则"关掉总开关照样锁屏"
        if (!snapshot.timeControlEnabled) {
            return Plan(
                active = false,
                needsStartConfirm = false,
                remainingMs = 0L,
                serverNow = nowWall,
                quotaMinutes = snapshot.quotaMinutes,
                extendedMinutes = snapshot.extendedMinutes,
                timeControlEnabled = false,
                restamp = false
            )
        }

        if (day == todayKey) {
            if (!snapshot.active) {
                // 今日尚未确认开始：不替孩子起表（起表要经服务端锁起点），但额度要回填，
                // 免得首页显示"剩余 0 分钟"却不锁屏。
                // needsStartConfirm=true：等待页与确认浮层照常工作（进程重启不吞掉提示）。
                return Plan(
                    active = false,
                    needsStartConfirm = true,
                    remainingMs = snapshot.remainingMs.coerceAtLeast(0L),
                    serverNow = snapshot.serverNow,
                    quotaMinutes = snapshot.quotaMinutes,
                    extendedMinutes = snapshot.extendedMinutes,
                    timeControlEnabled = true,
                    restamp = false
                )
            }
            // 同日续算：单调差（进程被杀仍连续）与墙钟差（设备重启后单调归零）取大者。
            // 取大是刻意的——少算等于白送额度，多算最多提前锁屏，联网后第一帧权威值即纠正。
            // 暂停中例外：remainingMs 是冻结值，再扣一次流逝等于"杀进程绕过暂停"；
            // 单调起点在设备重启后可能大于当时读数，钳到 [0, nowElapsed] 由 resume() 兜底。
            val advancedElapsed = (nowElapsed - snapshot.savedElapsed).coerceAtLeast(0L)
            val advancedWall = (nowWall - snapshot.serverNow).coerceAtLeast(0L)
            val advance = maxOf(advancedElapsed, advancedWall)
            return Plan(
                active = true,
                needsStartConfirm = false,
                remainingMs = if (snapshot.paused) {
                    snapshot.remainingMs.coerceAtLeast(0L)
                } else {
                    (snapshot.remainingMs - advance).coerceAtLeast(0L)
                },
                serverNow = if (snapshot.paused) snapshot.serverNow else snapshot.serverNow + advance,
                quotaMinutes = snapshot.quotaMinutes,
                extendedMinutes = snapshot.extendedMinutes,
                timeControlEnabled = true,
                restamp = false,
                paused = snapshot.paused,
                pauseSource = snapshot.pauseSource,
                pauseStartedElapsed = if (snapshot.paused) {
                    snapshot.pauseStartedElapsed.coerceIn(0L, nowElapsed)
                } else 0L
            )
        }

        // 跨日：新的一天不自动起表 —— 必须经孩子确认「开始计时」（家长端可发起/催办）。
        // 旧实现直接按上次已知额度满额起表（active=true），等于"跨零点重启就白拿一天额度"，
        // 且与「未确认不开始计时」的产品口径冲突；它也是「昨天用满、今天仍锁屏」
        // 的镜像问题：只要不重启就一直锁，一重启就白送。现在统一进入待确认：
        // 不计时、不消耗额度，设备由 [com.padguard.core.engine.PolicyEngine.tick]
        // 挂 AWAITING_START 等待页，孩子确认（联网经 start() / 离线本地起表）后转 active。
        // 延时额度（extended）按天清零：昨天的审批不给今天续命（与服务端 startFreshIfStale 一致）。
        return Plan(
            active = false,
            needsStartConfirm = true,
            remainingMs = 0L,
            serverNow = nowWall,
            quotaMinutes = snapshot.quotaMinutes,
            extendedMinutes = 0,
            timeControlEnabled = true,
            restamp = true
        )
    }
}
