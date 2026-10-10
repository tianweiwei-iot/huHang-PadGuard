package com.padguard.server.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletResponse

/**
 * 全局异常处理器 → HTTP 状态码映射的单元测试。
 *
 * 这是"RBAC 403 越权拦截"在 HTTP 层面的最后一公里：业务层 [AdminPermissionService]
 * 抛 [BizException]([ParentErr.FORBIDDEN]) 只是第一步，真正让网关 / 前端拦截器识别为
 * "被拒绝"的是响应状态码。此前 FORBIDDEN 被错误地映射成 200，越权操作在链路层会被误判为成功，
 * 属于必须消除的不一致。这里把映射关系钉死。
 */
class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `parent FORBIDDEN maps to HTTP 403 and keeps the business code in body`() {
        val resp = MockHttpServletResponse()
        val ex = BizException(ParentErr.FORBIDDEN, "无权限执行该操作（需要 rbac:write）", Audience.PARENT)

        val entity = handler.handleBiz(ex, resp)!!

        assertEquals(HttpStatus.FORBIDDEN.value(), entity.statusCode.value(), "越权必须返回 403，否则会被当成成功")
        val body = entity.body as ApiResponse<*>
        assertEquals(ParentErr.FORBIDDEN, body.code, "响应体仍需携带业务码，前端据此展示具体原因")
        assertEquals("无权限执行该操作（需要 rbac:write）", body.message)
    }

    @Test
    fun `parent UNAUTHORIZED maps to HTTP 401`() {
        val resp = MockHttpServletResponse()
        val ex = BizException(ParentErr.UNAUTHORIZED, "未登录或令牌无效", Audience.PARENT)

        val entity = handler.handleBiz(ex, resp)!!

        assertEquals(HttpStatus.UNAUTHORIZED.value(), entity.statusCode.value())
        assertEquals(ParentErr.UNAUTHORIZED, (entity.body as ApiResponse<*>).code)
    }

    @Test
    fun `child DEVICE_UNBOUND maps to HTTP 403`() {
        val resp = MockHttpServletResponse()
        val ex = BizException(ChildErr.DEVICE_UNBOUND, "设备未绑定", Audience.CHILD)

        val entity = handler.handleBiz(ex, resp)!!

        assertEquals(HttpStatus.FORBIDDEN.value(), entity.statusCode.value())
    }

    @Test
    fun `parent PARAM_ERROR maps to HTTP 400 and keeps the business code in body`() {
        // 参数错误 / 业务校验失败落到 400（同事 8cce9bf 修复）：admin 前端 api.js 的 http()
        // 以 res.ok（HTTP 2xx）判定成败，回 200 会把 { code:1001 } 当成成功，
        // 前端弹"保存成功"而服务端实际拒绝写入。与 FORBIDDEN→403 同一类不一致，一并消除。
        val resp = MockHttpServletResponse()
        val ex = BizException(ParentErr.PARAM_ERROR, "手机号已注册", Audience.PARENT)

        val entity = handler.handleBiz(ex, resp)!!

        assertEquals(HttpStatus.BAD_REQUEST.value(), entity.statusCode.value())
        assertEquals(ParentErr.PARAM_ERROR, (entity.body as ApiResponse<*>).code)
        assertEquals("手机号已注册", (entity.body as ApiResponse<*>).message)
    }

    @Test
    fun `other parent business errors still return 200 with the code in body`() {
        // 未被显式映射的业务错误（如设备离线）维持既有约定：200 + body code。
        // 显式映射（401/403/400/429）之外的错误码不应被波及。
        val resp = MockHttpServletResponse()
        val ex = BizException(ParentErr.DEVICE_OFFLINE, "设备离线，暂无法下发", Audience.PARENT)

        val entity = handler.handleBiz(ex, resp)!!

        assertEquals(HttpStatus.OK.value(), entity.statusCode.value())
        assertEquals(ParentErr.DEVICE_OFFLINE, (entity.body as ApiResponse<*>).code)
    }
}
