package com.padguard.server.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.http.MockHttpInputMessage
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException

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

    // ------------------------------------------------------------------
    // Spring MVC 内建的协议错误（405/415/400）
    //
    // 这些是"调用方用错了"而非"服务端坏了"。此前它们全部落到 handleOther 返回 500，
    // 有两个代价：日志每刷一条假 ERROR（淹没真故障），以及客户端把不可重试的错误当成可重试。
    // 这里钉死"用正确状态码 + 不带-stacktrace 的可执行提示"。
    // ------------------------------------------------------------------

    @Test
    fun `wrong HTTP method returns 405 with the allowed methods named in the message`() {
        // 真实触发场景：健康检查脚本 / 前端用 GET 去探测一个只接受 PUT 的接口。
        val req = MockHttpServletRequest("GET", "/v1/policies/dev-1/web/smart-shutdown")
        val resp = MockHttpServletResponse()
        // 第二参数只收 Collection<String>（不是 HttpMethod，javap 已确认）
        val ex = HttpRequestMethodNotSupportedException("GET", listOf("PUT"))

        val entity = handler.handleProtocolError(ex, req, resp)!!

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED.value(), entity.statusCode.value(), "方法不匹配必须 405，不能是 500")
        val body = entity.body as ApiResponse<*>
        assertEquals(ParentErr.PARAM_ERROR, body.code)
        assertTrue(
            body.message.contains("PUT"),
            "提示里要点明正确的方法，否则调用方只能对着 500 瞎猜：${body.message}"
        )
    }

    @Test
    fun `protocol errors on device endpoints are wrapped in the child envelope`() {
        // 端区分必须走 /api/ 前缀（与 WebConfig 里 childAuthInterceptor 的路径口径一致）。
        // 走错的话孩子端会收到 {timestamp} 而非 {serverTime}，反序列化后内容直接丢空。
        val req = MockHttpServletRequest("GET", "/api/v1/device/policy")
        val resp = MockHttpServletResponse()
        val ex = HttpRequestMethodNotSupportedException("GET", listOf("POST", "PUT"))

        val entity = handler.handleProtocolError(ex, req, resp)!!

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED.value(), entity.statusCode.value())
        assertTrue(entity.body is DeviceApiResponse<*>, "孩子端路径必须返回 DeviceApiResponse 包装")
        assertEquals(ChildErr.PARAM_ERROR, (entity.body as DeviceApiResponse<*>).code)
    }

    @Test
    fun `unsupported media type returns 415 instead of leaking through as 500`() {
        // 典型成因：原生 App 发 JSON 但忘了带 Content-Type。
        val req = MockHttpServletRequest("PUT", "/v1/policies/dev-1/web/browser")
        val resp = MockHttpServletResponse()
        // 第二个参数是 message 版本，构造最省心；提示文案不依赖 supportedMediaTypes。
        val ex = HttpMediaTypeNotSupportedException("Content type 'text/plain' not supported")

        val entity = handler.handleProtocolError(ex, req, resp)!!

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), entity.statusCode.value())
        assertTrue(
            (entity.body as ApiResponse<*>).message.contains("application/json"),
            "提示要直接告诉对方该带什么 Content-Type"
        )
    }

    @Test
    fun `missing query parameter and unreadable body both return 400`() {
        val missingResp = MockHttpServletResponse()
        val missingEntity = handler.handleProtocolError(
            MissingServletRequestParameterException("deviceId", "String"),
            MockHttpServletRequest("GET", "/v1/devices"),
            missingResp
        )!!
        assertEquals(HttpStatus.BAD_REQUEST.value(), missingEntity.statusCode.value())
        assertTrue(
            (missingEntity.body as ApiResponse<*>).message.contains("deviceId"),
            "缺哪个参数要说清楚"
        )

        val badJson = HttpMessageNotReadableException(
            "JSON parse error", MockHttpInputMessage(ByteArray(0))
        )
        val jsonEntity = handler.handleProtocolError(
            badJson, MockHttpServletRequest("POST", "/v1/devices/bind-code"), MockHttpServletResponse()
        )!!
        assertEquals(HttpStatus.BAD_REQUEST.value(), jsonEntity.statusCode.value(), "报文不可读属客户端错误，不是 500")
    }

    @Test
    fun `protocol errors keep the response writable reset behaviour`() {
        // resetIfPossible 的契约：响应已提交时不许再改写（返回 null），否则异常处理器自身会失败。
        val req = MockHttpServletRequest("GET", "/v1/devices")
        val committed = MockHttpServletResponse().apply { setCommitted(true) }

        val result = handler.handleProtocolError(
            HttpRequestMethodNotSupportedException("GET", listOf("POST")), req, committed
        )

        assertEquals(null, result, "响应已提交时必须放弃改写，交由容器处理")
    }
}
