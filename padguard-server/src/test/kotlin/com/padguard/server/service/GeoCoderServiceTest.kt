package com.padguard.server.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * [GeoCoderService] 单测：信任关键——逆地理编码失败绝不能拖垮定位落库主流程，
 * 且同坐标结果必须缓存避免轨迹密集上报反复打高德接口。
 *
 * 该服务是 @Service 但无构造注入：[webKey] 是 @Value lateinit、[httpClient] 是类内硬编码的
 * [HttpClient]（抽象类，可被 Mockito mock）。本测试用反射注入这两个字段，并模拟
 * [HttpClient.send] 的返回，从而在不触网、不加载 Spring 上下文的前提下覆盖全部分支。
 */
class GeoCoderServiceTest {

    private val reqPlaceholder: HttpRequest =
        HttpRequest.newBuilder().uri(URI.create("http://localhost")).build()
    private val handlerPlaceholder = HttpResponse.BodyHandlers.ofString()

    private fun newService(webKey: String): GeoCoderService {
        val svc = GeoCoderService()
        GeoCoderService::class.java.getDeclaredField("webKey").apply {
            isAccessible = true
            set(svc, webKey)
        }
        val client = mock(HttpClient::class.java)
        GeoCoderService::class.java.getDeclaredField("httpClient").apply {
            isAccessible = true
            set(svc, client)
        }
        return svc
    }

    private fun clientOf(svc: GeoCoderService): HttpClient {
        GeoCoderService::class.java.getDeclaredField("httpClient").apply {
            isAccessible = true
            return get(svc) as HttpClient
        }
    }

    private fun mockResponse(statusCode: Int, body: String): HttpResponse<String> {
        val resp = mock(HttpResponse::class.java) as HttpResponse<String>
        `when`(resp.statusCode()).thenReturn(statusCode)
        `when`(resp.body()).thenReturn(body)
        return resp
    }

    private fun prime(svc: GeoCoderService, resp: HttpResponse<String>) {
        doReturn(resp).`when`(clientOf(svc)).send(
            any(HttpRequest::class.java) ?: reqPlaceholder,
            any(HttpResponse.BodyHandler::class.java) ?: handlerPlaceholder
        )
    }

    private fun primeThrow(svc: GeoCoderService) {
        doThrow(IOException("timeout")).`when`(clientOf(svc)).send(
            any(HttpRequest::class.java) ?: reqPlaceholder,
            any(HttpResponse.BodyHandler::class.java) ?: handlerPlaceholder
        )
    }

    private fun verifySend(svc: GeoCoderService, count: Int) {
        verify(clientOf(svc), times(count)).send(
            any(HttpRequest::class.java) ?: reqPlaceholder,
            any(HttpResponse.BodyHandler::class.java) ?: handlerPlaceholder
        )
    }

    @Test
    fun blankKeyReturnsNullWithoutNetwork() {
        val svc = newService("")
        assertNull(svc.reverseGeocode(31.2, 121.5))
        assertFalse(svc.isConfigured())
        verifySend(svc, 0)
    }

    @Test
    fun zeroPointReturnsNullWithoutNetwork() {
        val svc = newService("mykey")
        assertNull(svc.reverseGeocode(0.0, 0.0))
        verifySend(svc, 0)
    }

    @Test
    fun outOfRangeLatReturnsNullWithoutNetwork() {
        val svc = newService("mykey")
        assertNull(svc.reverseGeocode(999.0, 0.0))
        verifySend(svc, 0)
    }

    @Test
    fun successParsesFormattedAddress() {
        val svc = newService("mykey")
        prime(svc, mockResponse(200, """{"status":"1","regeocode":{"formatted_address":"上海市浦东新区世纪大道"}}"""))
        assertEquals("上海市浦东新区世纪大道", svc.reverseGeocode(31.2304, 121.4737))
        verifySend(svc, 1)
    }

    @Test
    fun sameCoordsCachedSecondCallHitsCache() {
        val svc = newService("mykey")
        prime(svc, mockResponse(200, """{"status":"1","regeocode":{"formatted_address":"ADDR"}}"""))
        svc.reverseGeocode(31.2304, 121.4737)
        svc.reverseGeocode(31.2304, 121.4737)
        verifySend(svc, 1)
    }

    @Test
    fun businessFailureReturnsNull() {
        val svc = newService("mykey")
        prime(svc, mockResponse(200, """{"status":"0","info":"INVALID_USER_KEY"}"""))
        assertNull(svc.reverseGeocode(31.2304, 121.4737))
        verifySend(svc, 1)
    }

    @Test
    fun httpNon200ReturnsNull() {
        val svc = newService("mykey")
        prime(svc, mockResponse(500, "error"))
        assertNull(svc.reverseGeocode(31.2304, 121.4737))
        verifySend(svc, 1)
    }

    @Test
    fun networkExceptionSwallowedReturnsNull() {
        val svc = newService("mykey")
        primeThrow(svc)
        assertNull(svc.reverseGeocode(31.2304, 121.4737))
        verifySend(svc, 1)
    }

    @Test
    fun isConfiguredReflectsKey() {
        val svc = newService("mykey")
        assertTrue(svc.isConfigured())
    }

    @Test
    fun `逆地理失败日志不得泄露儿童精确坐标`() {
        // 儿童 GPS 坐标属敏感个人信息；逆地理失败（Key 无效/超时/额度耗尽）在定位密集上报时
        // 高频触发，旧实现把 lat/lng 明文打进日志，任何有日志读权限者即得孩子实时精确位置。
        val svc = newService("mykey")
        primeThrow(svc)
        val logger = org.slf4j.LoggerFactory.getLogger(GeoCoderService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            assertNull(svc.reverseGeocode(31.2304, 121.4737))
            val logged: String = appender.list.joinToString("\n") { it.formattedMessage }
            val hasLat: Boolean = logged.indexOf("31.2304") >= 0
            val hasLng: Boolean = logged.indexOf("121.4737") >= 0
            assertFalse(hasLat, "日志不得出现纬度 31.2304 原文（儿童精确位置泄露）")
            assertFalse(hasLng, "日志不得出现经度 121.4737 原文（儿童精确位置泄露）")
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
