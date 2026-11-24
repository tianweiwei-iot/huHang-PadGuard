package com.padguard.server.common

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingPathVariableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.context.request.async.AsyncRequestTimeoutException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.servlet.resource.NoResourceFoundException

/** 调用方：决定用哪套响应包装 / 错误码 */
enum class Audience { PARENT, CHILD }

/** 控制端（家长）错误码：1xxx/2xxx/3xxx */
object ParentErr {
    const val SUCCESS = 0
    const val PARAM_ERROR = 1001
    const val UNAUTHORIZED = 1002
    const val FORBIDDEN = 1003
    /**
     * 账号已被后台冻结（suspended）。
     *
     * 独立错误码而非复用 UNAUTHORIZED：客户端要能据此给出"账号已被冻结，请联系客服"
     * 的明确提示，而不是笼统的"登录已过期，请重新登录"——后者会让家长反复重试登录，
     * 永远得不到可执行的下一步。
     */
    const val ACCOUNT_SUSPENDED = 1004
    /** 账号待激活（pending）：已建档但未开通，同样不允许登录 */
    const val ACCOUNT_PENDING = 1005
    const val DEVICE_NOT_FOUND = 2001
    const val DEVICE_OFFLINE = 2002
    const val DEVICE_NOT_OWNED = 2003
    /** 同一设备的并发观看数已达上限（每路观看都是一份完整的上行+下行带宽） */
    const val LIVE_VIEWERS_FULL = 2004
    /** 服务端同时推流的设备数已达上限（并发带宽保护，见 MonitorService.startLiveView） */
    const val LIVE_DEVICES_FULL = 2006
    const val POLICY_NOT_FOUND = 3001
    const val POLICY_CONFLICT = 3002
    /** 围栏不存在（或不属于当前设备，按"不存在"处理以避免泄漏归属） */
    const val GEOFENCE_NOT_FOUND = 3003
    // 订阅：4xxx
    /** 未订阅 / 订阅已过期，功能不可用 */
    const val SUBSCRIPTION_REQUIRED = 4001
    /** 订阅有效但设备数已达上限，不能再绑定 */
    const val DEVICE_LIMIT_EXCEEDED = 4002
    const val ORDER_NOT_FOUND = 4003
    const val ORDER_STATE_INVALID = 4004
    /**
     * S14 限流：触发频控（登录 / 绑定等爆破面）。
     * 注意不能用 1004 —— 该值已被 ACCOUNT_SUSPENDED（账号冻结）占用，
     * 同值会让异常映射 when 分支互相遮蔽（限流 429 与冻结 401 必坏一个）。
     */
    const val TOO_MANY_REQUESTS = 1006
    const val SERVER_ERROR = 5001
}

/** 被管控端（孩子）错误码：40xxx */
object ChildErr {
    const val SUCCESS = 0
    const val PARAM_ERROR = 40001
    const val TOKEN_INVALID = 40101
    const val DEVICE_UNBOUND = 40301
    const val BIND_CODE_EXPIRED = 40302
    const val DEVICE_ALREADY_BOUND = 40303
    /** 家长账号未订阅或已过期 —— 孩子端必须给出这条明确提示，而不是笼统的"绑定失败" */
    const val SUBSCRIPTION_REQUIRED = 40304
    /** 可绑定设备数已达套餐上限 */
    const val DEVICE_LIMIT_EXCEEDED = 40305
    /** 家长账号已被冻结：孩子端不得继续绑定/换绑，需提示"家长账号已被冻结" */
    const val PARENT_ACCOUNT_SUSPENDED = 40306
    const val TIME_OUT_OF_SYNC = 40901
    /** S14 限流：绑定等匿名入口触发频控 */
    const val TOO_MANY_REQUESTS = 40902
    const val SERVER_ERROR = 50000
}

/** 业务异常，携带错误码与调用方 */
class BizException(
    val code: Int,
    override val message: String,
    val audience: Audience = Audience.PARENT
) : RuntimeException(message)

/** 控制端（家长）统一响应包装：{ code, message, data, timestamp } */
data class ApiResponse<T>(
    val code: Int = 0,
    val message: String = "success",
    val data: T? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun <T> ok(data: T?) = ApiResponse(data = data)
        fun <T> fail(code: Int, message: String) = ApiResponse<T>(code, message)
    }
}

/** 被管控端（孩子）统一响应包装：{ code, message, data, serverTime } */
data class DeviceApiResponse<T>(
    val code: Int = 0,
    val message: String = "success",
    val data: T? = null,
    val serverTime: Long = System.currentTimeMillis()
) {
    companion object {
        fun <T> ok(data: T?) = DeviceApiResponse(data = data)
        fun <T> fail(code: Int, message: String) = DeviceApiResponse<T>(code, message)
    }
}

/**
 * 全局异常兜底。
 *
 * ## 为什么要在写出响应前 `reset()`
 * 这是一个真实踩过的坑：Controller 在成功路径上预设了 Content-Type
 * （例如下载 APK 时设为 `application/vnd.android.package-archive`），
 * 随后在处理过程中抛出异常。此时若直接返回 JSON 错误体，Spring 会发现
 * "没有转换器能用 APK 这个 Content-Type 写 ApiResponse"，抛
 * `HttpMessageNotWritableException` —— 于是**异常处理器自己失败了**，
 * 客户端收到连接重置，而真正导致失败的那个原始异常在日志里彻底消失。
 * 排查时只会看到一句 converter 报错，极易误判成网络问题。
 *
 * 因此在写错误响应之前先清掉响应上已被设置的头/Content-Type，
 * 保证错误永远能以 JSON 的形式送达。
 *
 * ## 为什么区分"客户端断连"
 * 用户在管控端取消一次大文件下载，Tomcat 就会抛
 * `IOException: Connection reset by peer` / `AsyncRequestNotUsableException`。
 * 把它记成 ERROR 会让日志被这类正常中断淹没，真正需要关注的故障反而看不见。
 * 这里降级为 DEBUG 并直接放行（返回 null 表示不处理），不做任何响应改写。
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(BizException::class)
    fun handleBiz(e: BizException, response: HttpServletResponse): ResponseEntity<*>? {
        if (!resetIfPossible(response)) return null
        // 鉴权失败必须落到对应的 HTTP 状态码，不能只写在响应体里：
        // - 家长端：OkHttp Authenticator 只在 401 上触发自动续期；
        // - 被管控端：HTTP 401/403 会被映射为 TOKEN_INVALID / DEVICE_UNBOUND 终态，
        //   终端据此清空本地凭据回到绑定页。若这里回 200，终端会永远停在
        //   "已绑定"的假象里（例如服务端换库后设备记录已不存在）。
        val status = when {
            e.audience == Audience.PARENT && e.code == ParentErr.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED
            // 冻结/待激活必须落到 401 而不是 403：
            // 家长端 OkHttp Authenticator 只在 401 上触发（刷新令牌→失败→清空本地会话→
            // SessionViewModel 响应式退回登录页）。回 403 的话 Authenticator 根本不介入，
            // 客户端会停在"令牌还在、界面还在主页、但每个接口都报错"的假登录态里。
            e.audience == Audience.PARENT &&
                (e.code == ParentErr.ACCOUNT_SUSPENDED || e.code == ParentErr.ACCOUNT_PENDING) ->
                HttpStatus.UNAUTHORIZED
            // S14 限流：必须是 429，客户端与网关才能识别"稍后重试"语义，重试退避才不会变成立即重打
            e.code == ParentErr.TOO_MANY_REQUESTS || e.code == ChildErr.TOO_MANY_REQUESTS ->
                HttpStatus.TOO_MANY_REQUESTS
            // 越权（含后台 RBAC 闸门拒绝、家长端"资源不属于当前用户"）必须落到 403：
            // HTTP 状态码是网关 / 前端拦截器判断成败的首要依据，回 200 会让越权操作被误判为"成功"，
            // 管理端 http() 在 !res.ok 时直接抛错、家长端 ApiDataSource 在 !isSuccessful 时走错误分支——
            // 两者都不会静默放行，但只有正确的 403 才能让"被拒绝"这一语义在链路各层一致。
            e.audience == Audience.PARENT && e.code == ParentErr.FORBIDDEN -> HttpStatus.FORBIDDEN
            // 归属类拒绝（2003）此前落到 else -> 200，与上面几行注释的结论直接矛盾：
            // DeviceOwnershipGuard / GeofenceService / UnlockService / BillingService.orderStatusOf
            // 抛的都是 2003 / 3003，而不是 1003，于是"设备不属于当前用户"被回成
            // HTTP 200 + body.code=2003 —— 网关、后台 api.js、家长端拦截器全部按"成功"处理，
            // 越权读取会变成"成功但无数据"，越权写会变成"成功但没生效"。
            // 只对 PARENT 生效：孩子端把 403 映射为 DEVICE_UNBOUND 终态并清本地凭据，
            // 若设备已易主让孩子端收到 403，会把一台正常绑定的设备踢回绑定页。
            e.audience == Audience.PARENT && e.code == ParentErr.DEVICE_NOT_OWNED -> HttpStatus.FORBIDDEN
            // 资源不存在同类：404 既不泄漏归属，也能让客户端走"数据没了"而不是"操作成功"。
            e.audience == Audience.PARENT && (
                e.code == ParentErr.DEVICE_NOT_FOUND ||
                    e.code == ParentErr.GEOFENCE_NOT_FOUND ||
                    e.code == ParentErr.POLICY_NOT_FOUND ||
                    e.code == ParentErr.ORDER_NOT_FOUND
                ) -> HttpStatus.NOT_FOUND
            // 订阅类业务拒绝（过期 / 设备数超限）：与上面 403 同源 —— 家长端 api.js 以 HTTP 2xx 判成败，
            // 回 200 会让"订阅已过期 / 设备已满"被误判为操作成功（与已修的 2003/3003 同类 bug）。
            // 归到 403：鉴权网关 / 前端拦截器统一按"被拒绝"处理，前端据 body.code(4001/4002) 给出付费引导。
            e.audience == Audience.PARENT && (
                e.code == ParentErr.SUBSCRIPTION_REQUIRED ||
                    e.code == ParentErr.DEVICE_LIMIT_EXCEEDED
                ) -> HttpStatus.FORBIDDEN
            // 订单状态非法（超时关闭 / 验签未通过等）：属客户端或状态错误，必须落到 4xx 而非 200，
            // 否则未验签的支付回调、超时被拒的订单会被网关/前端误判为"成功"。
            e.audience == Audience.PARENT && e.code == ParentErr.ORDER_STATE_INVALID -> HttpStatus.BAD_REQUEST
            // 服务端内部错误：绝不能落到 else -> 200，否则前端把"服务器炸了"当成"操作成功"。
            e.audience == Audience.PARENT && e.code == ParentErr.SERVER_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR
            // 设备离线：指令无法下发，必须落到 4xx 让前端给出"设备不在线"提示而非"成功"。
            e.audience == Audience.PARENT && e.code == ParentErr.DEVICE_OFFLINE -> HttpStatus.CONFLICT
            // 直播观看/推流并发上限：与限流同源，必须是 429，前端据此提示"当前观看人数已满"。
            e.audience == Audience.PARENT &&
                (e.code == ParentErr.LIVE_VIEWERS_FULL || e.code == ParentErr.LIVE_DEVICES_FULL) ->
                HttpStatus.TOO_MANY_REQUESTS
            e.audience == Audience.CHILD && e.code == ChildErr.TOKEN_INVALID -> HttpStatus.UNAUTHORIZED
            e.audience == Audience.CHILD && e.code == ChildErr.DEVICE_UNBOUND -> HttpStatus.FORBIDDEN
            // 无效/过期绑定码本质是参数错误（家长端应据此提示"刷新接入码"），必须以 4xx 返回：
            // 此前落到 else -> 200，HTTP 语义是"成功"，网关/监控/前端只看状态码时会误判绑定成功。
            // 孩子端按 body.code 判失败（不受影响），这里只把 HTTP 语义纠正为正确的 4xx。
            e.audience == Audience.CHILD && e.code == ChildErr.BIND_CODE_EXPIRED -> HttpStatus.BAD_REQUEST
            // 家长账号被冻结：孩子端是"已绑定设备想再绑/换绑"的场景，属终态拒绝（403），
            // 不能回 401 —— 401 会让孩子端清空凭据回到绑定页，把已绑定设备也一并踢掉。
            e.audience == Audience.CHILD && e.code == ChildErr.PARENT_ACCOUNT_SUSPENDED -> HttpStatus.FORBIDDEN
            // 参数错误 / 业务校验失败落到 400，不能回 200 只把错误码塞进响应体：
            // 管理端 api.js 的 http() 以 `res.ok`（即 HTTP 2xx）判定成败，
            // 回 200 时它会把 { code:1001, data:null } 当成成功返回，
            // 于是前端弹出"保存成功"，而事实上服务端拒绝写入 —— 用户改了半天权限，什么都没变。
            // 典型场景：RBAC 保存时"有写无读"的前置校验被拦，前台却提示保存成功。
            // 必须限定 audience：孩子端若误抛 ParentErr.PARAM_ERROR（历史上有过），
            // 这条不限定会把家长码塞进 DeviceApiResponse；孩子端码表是 40xxx，1001 属未知码。
            e.audience == Audience.PARENT && e.code == ParentErr.PARAM_ERROR -> HttpStatus.BAD_REQUEST
            e.audience == Audience.CHILD && e.code == ChildErr.PARAM_ERROR -> HttpStatus.BAD_REQUEST
            else -> {
                // 系统性隐患兜底：任何"未在此 when 显式映射"的孩子端业务异常都会落到这里以 HTTP 200 返回
                // （仅 body.code 非零）。一旦新增 ChildErr 码却漏配状态码，HTTP 会静默表现为"成功"，
                // 只看状态码的网关/前端会误判。这里对未映射的 ChildErr 打 WARN，让遗漏可见、可追责。
                if (e.audience == Audience.CHILD) {
                    log.warn("child error code not explicitly mapped to HTTP status, returned 200: code={} msg={}", e.code, e.message)
                }
                HttpStatus.OK
            }
        }
        return if (e.audience == Audience.CHILD) {
            ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(DeviceApiResponse<Any>(e.code, e.message))
        } else {
            ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse<Any>(e.code, e.message))
        }
    }

    /**
     * 乐观锁冲突：并发写同一条记录时后到者失败。
     *
     * 目前唯一带 `@Version` 的实体是接入会话，因此这里的冲突只有一个来源 ——
     * 两个孩子端几乎同时提交同一个接入码。前一个事务把会话置为 BOUND 并提交，
     * 后一个事务提交时 `revision` 已不匹配，影响行数为 0，Hibernate 抛本异常、
     * 整笔事务（含它刚建立的设备台账）回滚。
     *
     * 必须翻译成业务语义：落到 [ChildErr.BIND_CODE_EXPIRED]（与"码已被使用"同一错误码），
     * 孩子端据此提示家长刷新接入码重试；若让它冒到兜底分支变成 500，
     * 用户看到的是"服务器错误"，而真实原因只是慢了一步。
     */
    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLock(
        e: OptimisticLockingFailureException,
        response: HttpServletResponse
    ): ResponseEntity<*>? {
        if (!resetIfPossible(response)) return null
        log.warn("concurrent update conflict, later writer rejected: {}", e.message)
        return ResponseEntity.status(HttpStatus.OK)
            .contentType(MediaType.APPLICATION_JSON)
            .body(DeviceApiResponse<Any>(ChildErr.BIND_CODE_EXPIRED, "接入码已被使用，请在家长端重新获取"))
    }

    /**
     * 上传体积超限：给明确文案而不是笼统的 500。
     * 家长分发 APK 时若超过 `spring.servlet.multipart.max-file-size`，
     * 返回 413 + "文件过大"才能让管控端给出可操作的提示，
     * 否则用户只会看到"操作失败"，反复重试。
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleUploadTooLarge(
        e: MaxUploadSizeExceededException,
        response: HttpServletResponse
    ): ResponseEntity<*>? {
        if (!resetIfPossible(response)) return null
        log.warn("upload rejected: {}", e.message)
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ApiResponse<Any>(ParentErr.PARAM_ERROR, "上传文件过大，请调整服务端 multipart 上限后重试"))
    }

    /**
     * 静态资源不存在（典型：浏览器打开管理后台时自动请求 favicon.ico）。
     * 这是正常 404 语义，绝不能落到 [handleOther] 的 ERROR 分支——否则每次有人
     * 打开后台都刷一条 "unhandled exception" 假告警，上线后真正的 ERROR 会被淹没。
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(e: NoResourceFoundException, response: HttpServletResponse): ResponseEntity<*>? {
        if (!resetIfPossible(response)) return null
        log.debug("static resource not found: {}", e.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ApiResponse<Any>(ParentErr.PARAM_ERROR, "请求的资源不存在，请检查访问地址"))
    }

    /**
     * Spring MVC 内建的「调用协议错误」：405 方法不对 / 415 媒体类型 / 406 / 400 参数或报文不可读。
     *
     * 这些异常自带正确的 HTTP 语义（都实现 [ErrorResponse]），**是调用方的问题，不是服务端的故障**。
     * 缺了这个分支，它们会全部落到 [handleOther] 变成 500，带来两个实打实的后果：
     *
     * 1. **日志被假故障淹没** —— 健康检查脚本 / 前端用 GET 探测一个只接受 PUT 的接口，
     *    就会刷一条 `ERROR unhandled exception`。上线后这类噪音会让真正的服务器故障看不见，
     *    这是排障时最贵的那种损失：告警还在响，但没人信它了。
     * 2. **客户端行为被误导** —— 500 的含义是"服务端出错，可以重试"。而 405/400 重试一万次也是一样的结果。
     *    客户端据此退避重打，只会把错误放大，且掩盖"其实是自己调错了"这一事实。
     *
     * 这里保留异常自带的准确状态码，日志降到 WARN（够用且不污染 ERROR），
     * 并把技术细节换成中文可执行提示 —— 终端用户看到的是"该接口只接受 PUT"，
     * 而不是一句没头没尾的 "Request method 'GET' is not supported"。
     */
    @ExceptionHandler(
        HttpRequestMethodNotSupportedException::class,
        HttpMediaTypeNotSupportedException::class,
        HttpMediaTypeNotAcceptableException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentNotValidException::class,
        MissingServletRequestParameterException::class,
        MissingServletRequestPartException::class,
        MissingPathVariableException::class,
        MethodArgumentTypeMismatchException::class
    )
    fun handleProtocolError(
        e: Exception,
        request: HttpServletRequest,
        response: HttpServletResponse
    ): ResponseEntity<*>? {
        if (!resetIfPossible(response)) return null

        // 注意：这里的参数刻意声明为 Exception 而不是 ErrorResponse。
        // 上述异常里 HttpMessageNotReadableException 并未实现 ErrorResponse（Spring 6.1 就此为止），
        // 若写成 ErrorResponse，它在编译期看不出来，却会在 Spring 分派异常的那一刻
        // 因参数类型不匹配而失败 —— 即"异常处理器自己抛异常"，比原问题更难排查。
        val err = e as? ErrorResponse
        val status = err?.statusCode?.let { HttpStatus.valueOf(it.value()) } ?: HttpStatus.BAD_REQUEST
        val hint = err?.let { protocolHint(it) } ?: "请求体无法解析：不是合法 JSON，或字段类型与接口定义不匹配"

        // 端区分口径必须与拦截器一致：孩子端统一在 /api/v1/**（见 WebConfig.addInterceptors 的
        // childAuthInterceptor）。两处各写各的判定迟早会漂移，届时孩子端会收到家长版的响应包装，
        // 而 {timestamp} 与 {serverTime} 字段不同名，反序列化直接丢内容。
        val audience = if (request.requestURI.startsWith("/api/")) Audience.CHILD else Audience.PARENT

        log.warn(
            "request rejected by protocol check: {} {} -> {} ({})",
            request.method, request.requestURI, status.value(), err?.body?.detail
        )
        return if (audience == Audience.CHILD) {
            ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(DeviceApiResponse<Any>(ChildErr.PARAM_ERROR, hint))
        } else {
            ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse<Any>(ParentErr.PARAM_ERROR, hint))
        }
    }

    /** 把框架抛出的英文技术异常，翻译成调用方能直接照做的提示。 */
    private fun protocolHint(e: ErrorResponse): String = when (e) {
        is HttpRequestMethodNotSupportedException -> {
            val allowed = e.supportedHttpMethods?.joinToString(" / ") { it.name() }
            if (allowed.isNullOrBlank()) "请求方法不支持，请检查接口文档" else "请求方法不支持，该接口只接受 $allowed"
        }
        is HttpMediaTypeNotSupportedException -> "请求体格式不支持，请带上请求头 Content-Type: application/json"
        is HttpMediaTypeNotAcceptableException -> "服务端无法按 Accept 指定的格式返回内容，请检查 Accept 请求头"
        is HttpMessageNotReadableException -> "请求体无法解析：不是合法 JSON，或字段类型与接口定义不匹配"
        is MethodArgumentNotValidException -> {
            val first = e.bindingResult.fieldErrors.firstOrNull()
            if (first == null) "请求参数校验未通过"
            else "请求参数校验未通过：${first.field} ${first.defaultMessage.orEmpty()}".trim()
        }
        is MissingServletRequestParameterException -> "缺少必需参数：${e.parameterName}"
        is MissingServletRequestPartException -> "缺少必需的文件字段：${e.requestPartName}"
        is MissingPathVariableException -> "缺少路径参数：${e.variableName}"
        is MethodArgumentTypeMismatchException -> "参数 ${e.name} 的值类型不正确"
        else -> "请求参数有误"
    }

    /**
     * 异步请求超时：`StreamingResponseBody`（实时看屏上行流）与 `DeferredResult`
     * （孩子端指令长轮询）都会在 `spring.mvc.async.request-timeout` 到点后抛这个异常。
     *
     * 它是**预期生命周期**，不是故障：
     * - 看屏流到点后响应早已提交（帧一直在写），容器直接结束连接，家长端按重连策略续看；
     * - 长轮询自带 `DeferredResult(capped)` 超时，永远先于全局超时返回。
     *
     * 此前它落到 [handleOther] 的"响应已提交"分支，每次打一条 ERROR + 整段
     * `TimeoutCallableProcessingInterceptor` 堆栈（本机日志实测每 10 分钟一条），
     * 既把错误率告警打成假阳性，也会把真正的异常埋掉。
     * 这里按 DEBUG 记一行，并保持与 Spring 默认一致的 503（仅未提交时）。
     */
    @ExceptionHandler(AsyncRequestTimeoutException::class)
    fun handleAsyncTimeout(
        e: AsyncRequestTimeoutException,
        request: HttpServletRequest,
        response: HttpServletResponse
    ): ResponseEntity<*>? {
        if (!resetIfPossible(response)) {
            log.debug(
                "async timeout after response committed (normal for long-lived streams): {} {}",
                request.method, request.requestURI
            )
            return null
        }
        log.warn("async timeout before response committed: {} {}", request.method, request.requestURI)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build<Any>()
    }

    @ExceptionHandler(Exception::class)
    fun handleOther(
        e: Exception,
        request: HttpServletRequest,
        response: HttpServletResponse
    ): ResponseEntity<*>? {
        // 客户端主动断开：正常业务中断，不记账、不改写响应
        if (isClientAbort(e)) {
            log.debug("client aborted the request: {}", e.message)
            return null
        }
        if (!resetIfPossible(response)) {
            // 响应已提交，改不动了；此时必须把原始异常记下来，
            // 否则就会重演"只看到 converter 报错、看不到真凶"的排查困境。
            log.error("unhandled exception (response already committed)", e)
            return null
        }
        // 对外只给一个可对照日志的短编号，绝不回传 e.message：
        // JPA/Spring 的异常消息里带表名、列名、约束名甚至 SQL 片段
        // （例如 `duplicate key value violates unique constraint "uk_usage_logs_log_id"`），
        // 等于把库结构直接送给家长端 App 和抓包者。而"一律返回 server error"又会让
        // 用户报障时无任何可定位信息，因此用 ref 把两侧接上：日志里 grep 同一个 ref 即可。
        val ref = java.util.UUID.randomUUID().toString().take(8)
        log.error("unhandled exception ref={}", ref, e)
        val message = "服务器内部错误，请稍后重试（错误编号 $ref）"
        // 端区分口径必须与 handleProtocolError 一致（孩子端统一 /api/v1/**）：
        // 否则孩子端任何未捕获异常（例如迁移缺列抛出的 SQLException）都会拿到家长版信封
        // {timestamp} + 家长码 5001，而孩子端按 {serverTime} + 40xxx 解析，反序列化直接丢内容。
        val audience = if (request.requestURI.startsWith("/api/")) Audience.CHILD else Audience.PARENT
        val body: Any = if (audience == Audience.CHILD) {
            DeviceApiResponse<Any>(ChildErr.SERVER_ERROR, message)
        } else {
            ApiResponse<Any>(ParentErr.SERVER_ERROR, message)
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
    }

    /**
     * 清掉响应上已设置的状态与头，为 JSON 错误体腾出干净的写出环境。
     * @return 是否可以继续写错误响应；false 表示响应已提交（客户端已收到部分数据），不能再改。
     */
    private fun resetIfPossible(response: HttpServletResponse): Boolean {
        if (response.isCommitted) return false
        val ok = runCatching { response.reset() }.isSuccess
        // reset() 把状态码、Content-Type 与**全部响应头**一并清空，其中就包括过滤器
        // 事先写好的 nosniff / X-Frame-Options / Referrer-Policy。若不补回，
        // "全局安全头"只在 200 上成立，而错误响应（最容易被嗅探成 HTML、被 iframe 套）
        // 恰恰是唯一没有它的那一类。这里按同一份定义补回，两层不各写一遍字符串。
        if (ok) SecurityHeaders.apply(response)
        return ok
    }

    private fun isClientAbort(e: Throwable): Boolean {
        var cursor: Throwable? = e
        while (cursor != null) {
            val name = cursor::class.java.name
            if (name.contains("ClientAbort") ||
                name.contains("AsyncRequestNotUsable") ||
                name.contains("BrokenPipe") ||
                (cursor is java.io.IOException && cursor.message?.contains("reset by peer") == true)
            ) return true
            cursor = cursor.cause
        }
        return false
    }
}

/** SHA-256 十六进制 */
object HashUtil {
    fun sha256(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * 客户端真实 IP 的取法（全局唯一定义）。
 *
 * 家庭部署常见前置 Nginx / 端口转发，只认 `RemoteAddr` 会一律拿到 127.0.0.1——
 * 局域网发现会因此永远发现不了任何东西，且这种错很难从现象反推。
 * 统一在这里处理转发头，任何需要"对方网络位置"的地方都调它，避免每个调用点各写各的、
 * 出现一处改了另一处没改的漂移。
 */
object ClientIps {
    /**
     * 转发头感知的客户端 IP（用于**局域网发现 / 设备存活定位**这类非安全决策）。
     *
     * 家庭部署常见前置 Nginx / 端口转发，只认 `RemoteAddr` 会一律拿到 127.0.0.1，
     * 局域网发现会因此永远发现不了任何设备。
     *
     * **不得用于限流/鉴权等安全决策**：`X-Forwarded-For` 是客户端可任意伪造的头，
     * 用了它等于把频控的 IP 维度交给了攻击者。安全场景请用 [peerOf]。
     */
    fun of(request: jakarta.servlet.http.HttpServletRequest): String? {
        val xff = request.getHeader("X-Forwarded-For")
            ?.split(',')
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return (xff ?: request.remoteAddr)?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * TCP 对端地址：由内核从三次握手得来，**客户端无法伪造**。
     *
     * 限流、风控等安全决策一律以此为准（见 [com.padguard.server.security.RateLimit]）。
     * 代价是在受信任反向代理之后会一律看到代理的地址，因此部署在 Nginx 之后时
     * 需要显式打开 `padguard.ratelimit.trust-forwarded-for`，由 [of] 承担解析。
     */
    fun peerOf(request: jakarta.servlet.http.HttpServletRequest): String? =
        request.remoteAddr?.trim()?.takeIf { it.isNotBlank() }

    /**
     * 受信任反向代理之后的客户端 IP（可用于安全决策）。
     *
     * 只采信 `X-Real-IP`：本项目的 Nginx（nginx/nginx.conf）用
     * `proxy_set_header X-Real-IP $remote_addr` **覆盖**该头，客户端自带的同名头会被丢弃，
     * 因此经过代理后它等价于 TCP 对端地址。
     *
     * 刻意不采信 `X-Forwarded-For`：Nginx 用 `$proxy_add_x_forwarded_for` 是"追加"，
     * 客户端伪造的第一段会被原样保留并在 [of] 里被取到 —— 用它做网段/限流判定，
     * 等于把边界交给攻击者（发一个 `X-Forwarded-For: 10.0.5.9` 就能枚举别人网段的设备）。
     */
    fun viaTrustedProxy(request: jakarta.servlet.http.HttpServletRequest): String? =
        request.getHeader("X-Real-IP")?.trim()?.takeIf { it.isNotBlank() } ?: peerOf(request)
}
