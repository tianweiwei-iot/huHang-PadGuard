package com.padguard.server.domain

import jakarta.persistence.*

/**
 * 家长端「开始计时」工单（每日计时须孩子确认后才开始）。
 *
 * ## 为什么需要它
 * 新的一天（日切后）进入「未开始计时」态：孩子端保持等待页、不计时、不消耗额度。
 * 两条确认通道共用同一张工单收敛状态：
 * 1. **日切自动**：孩子端本地跨日后自动弹确认浮层（离线可用，本地起表）；
 * 2. **家长发起**：家长点「开始计时」→ 落 PENDING 工单 + 下发 START_TIMER_CONFIRM 指令
 *    → 孩子端 force 弹确认框（"家长已为你开启今日计时"）→ 孩子点「开始计时」。
 *
 * 孩子确认（任何通道）都会调 [com.padguard.server.service.UsageSessionService.start]，
 * 起表后把**当日全部 PENDING 工单**置 CONFIRMED —— 不依赖孩子端回传 requestId：
 * 孩子可能从等待页按钮、日切浮层等多个入口确认，回传链路缺一环工单就永远悬空。
 *
 * ## 状态机
 * `PENDING → CONFIRMED`（孩子确认）；`PENDING → EXPIRED`（跨日未确认，懒作废）。
 * 终态不可回退：CONFIRMED/EXPIRED 靠数据库 WHERE 条件更新，不整行 save。
 */
@Entity
@Table(name = "usage_start_requests")
class UsageStartRequest(
    /** 工单号（st_ 前缀），指令 payload 携带给孩子端（确认后回传关联；不回传也可按设备日收敛） */
    @Id
    @Column(name = "request_id", length = 64)
    var requestId: String = "",

    @Column(name = "device_id", length = 64)
    var deviceId: String = "",

    /** 发起日（Asia/Shanghai），与 usage_sessions.day_key 同口径；跨日 PENDING 作废 */
    @Column(name = "day_key", length = 20)
    var dayKey: String = "",

    /** PENDING / CONFIRMED / EXPIRED */
    @Column(name = "status", length = 16)
    var status: String = "PENDING",

    /** 发起来源：PARENT（家长按钮）；日切自动通道不落工单（无家长动作） */
    @Column(name = "source", length = 16)
    var source: String = "PARENT",

    @Column(name = "created_at")
    var createdAt: Long = 0L,

    /** 孩子确认时刻的服务端墙钟；未确认为 null */
    @Column(name = "confirmed_at")
    var confirmedAt: Long? = null
)
