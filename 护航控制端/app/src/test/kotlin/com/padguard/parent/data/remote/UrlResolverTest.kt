package com.padguard.parent.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [resolveAbsoluteUrl] 行为测试：相对路径补全与「/v1 前缀去重」的边界。
 *
 * 该函数同时服务 HTTP 图标加载与 WS 推送路径，拼错的表现是图标集体裂开或 500，
 * 因此把三种关键形态（带前缀 base / 裸 base / 绝对路径直传）锁进测试。
 */
class UrlResolverTest {

    @Test
    fun `空路径返回 null`() {
        assertNull(resolveAbsoluteUrl("https://a.example.com/v1", null))
        assertNull(resolveAbsoluteUrl("https://a.example.com/v1", "  "))
        assertNull(resolveAbsoluteUrl("https://a.example.com/v1", ""))
    }

    @Test
    fun `绝对地址原样直传不做拼接`() {
        val url = "HTTPS://cdn.example.com/icon.png"
        assertEquals(url, resolveAbsoluteUrl("https://a.example.com/v1", url))
        assertEquals("http://cdn.example.com/x.png", resolveAbsoluteUrl("https://a.example.com/v1", "http://cdn.example.com/x.png"))
    }

    @Test
    fun `带 v1 前缀的 base 拼带 v1 的资源路径不产生双重前缀`() {
        assertEquals(
            "https://a.example.com/v1/files/f1",
            resolveAbsoluteUrl("https://a.example.com/v1", "/v1/files/f1")
        )
    }

    @Test
    fun `裸 base 拼带 v1 的资源路径保持完整路径`() {
        assertEquals(
            "https://a.example.com/v1/files/f1",
            resolveAbsoluteUrl("https://a.example.com", "/v1/files/f1")
        )
    }

    @Test
    fun `资源路径无前导斜杠时自动补斜杠`() {
        assertEquals(
            "https://a.example.com/v1/files/f1",
            resolveAbsoluteUrl("https://a.example.com/v1", "files/f1")
        )
    }

    @Test
    fun `base 末尾多余斜杠被清理`() {
        assertEquals(
            "https://a.example.com/v1/files/f1",
            resolveAbsoluteUrl("https://a.example.com/v1/", "/v1/files/f1")
        )
    }

    @Test
    fun `host 恰为 v1 时去重逻辑不削坏地址`() {
        // "http://v1".removeSuffix("/v1") = "http:" 不含 "://"，保护条件生效，地址原样保留
        assertEquals("http://v1/v1/x", resolveAbsoluteUrl("http://v1", "/v1/x"))
    }

    @Test
    fun `base 携带双重 v1 后缀时逐段去重`() {
        assertEquals(
            "https://a.example.com/v1/files/f1",
            resolveAbsoluteUrl("https://a.example.com/v1/v1", "/v1/files/f1")
        )
    }
}
