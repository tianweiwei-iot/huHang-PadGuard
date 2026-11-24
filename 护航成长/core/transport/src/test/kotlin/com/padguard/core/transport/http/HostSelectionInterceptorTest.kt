package com.padguard.core.transport.http

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [HostSelectionInterceptor] 地址重写契约。
 *
 * 锁死一条曾经真实发生的故障链：
 * 孩子端 baseUrl 形如 `http://host:8090/api/v1/`，而服务端文件接口在**根路径**
 * `/v1/files/{id}`。旧实现把所有请求都按 baseUrl 拼前缀，媒体地址被改写成
 * `/api/v1/v1/files/{id}` → 404 → 家长发布视频后孩子端「视频加载失败」。
 */
class HostSelectionInterceptorTest {

    private val baseApi = "http://192.168.1.12:8090/api/v1/".toHttpUrl()
    private val baseRoot = "http://192.168.1.12:8090/".toHttpUrl()

    @Test
    fun `占位地址按 baseUrl 拼装，保持原有切接入点能力`() {
        val original = "http://localhost/placeholder/device/heartbeat?x=1".toHttpUrl()
        val out = HostSelectionInterceptor.rewriteUrl(original, baseApi)

        assertEquals("http://192.168.1.12:8090/api/v1/device/heartbeat?x=1", out.toString())
    }

    @Test
    fun `媒体地址同源时原样返回，不再拼 api 前缀`() {
        val original =
            "http://192.168.1.12:8090/v1/files/abc123?exp=1700000000000&sig=deadbeef".toHttpUrl()
        val out = HostSelectionInterceptor.rewriteUrl(original, baseApi)

        assertEquals(original.toString(), out.toString())
        assertFalse("绝不能出现重复前缀：" + out, out.encodedPath.contains("/api/v1/v1/"))
        // 签名参数必须完整保留，否则开启匿名强制签名的部署会 403
        assertEquals("1700000000000", out.queryParameter("exp"))
        assertEquals("deadbeef", out.queryParameter("sig"))
    }

    @Test
    fun `媒体地址指向旧接入点时只换 origin，路径与签名保留`() {
        val original =
            "http://192.168.1.11:8090/v1/files/abc123?exp=1700000000000&sig=deadbeef".toHttpUrl()
        val out = HostSelectionInterceptor.rewriteUrl(original, baseApi)

        assertEquals("192.168.1.12", out.host)
        assertEquals(8090, out.port)
        assertEquals("/v1/files/abc123", out.encodedPath)
        assertEquals("1700000000000", out.queryParameter("exp"))
        assertEquals("deadbeef", out.queryParameter("sig"))
    }

    @Test
    fun `外部对象存储与 CDN 地址一律不改写`() {
        val original = "https://cdn.example.com/video/a.mp4?token=xyz".toHttpUrl()
        val out = HostSelectionInterceptor.rewriteUrl(original, baseApi)

        assertEquals(original.toString(), out.toString())
    }

    @Test
    fun `baseUrl 无路径前缀时媒体地址保持原样`() {
        val original = "http://192.168.1.12:8090/v1/files/abc123".toHttpUrl()
        val out = HostSelectionInterceptor.rewriteUrl(original, baseRoot)

        assertEquals(original.toString(), out.toString())
    }

    @Test
    fun `接口地址已带正确前缀时不会被二次拼接`() {
        val original = "http://192.168.1.12:8090/api/v1/device/heartbeat".toHttpUrl()
        val out = HostSelectionInterceptor.rewriteUrl(original, baseApi)

        assertEquals(original.toString(), out.toString())
    }

}
