package com.padguard.parent.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 家长端证书固定（P0-1 对称加固）的可执行回归测试。
 *
 * 重点验证 [CertPinTrustManager.parsePins]：它直接消费构建期注入的
 * `BuildConfig.CERT_PINS`（格式 `sha256/xxx,sha256/yyy`），解析结果是后续
 * 静态固定能否生效的唯一入口。解析错了（多余空格、缺失前缀、重复项）会导致
 * 要么固定永远不命中、要么空串误触发静态固定分支——两种都会让 TLS 中间人防护失效。
 */
class CertPinTrustManagerTest {

    @Test
    fun `parsePins 解析标准逗号列表`() {
        val pins = CertPinTrustManager.parsePins("sha256/AAA,sha256/BBB")
        assertEquals(setOf("sha256/AAA", "sha256/BBB"), pins)
    }

    @Test
    fun `parsePins 去除每项前后空白`() {
        val pins = CertPinTrustManager.parsePins("  sha256/AAA , sha256/BBB  ")
        assertEquals(setOf("sha256/AAA", "sha256/BBB"), pins)
    }

    @Test
    fun `parsePins 空串返回空集，触发 TOFU 路径`() {
        val pins = CertPinTrustManager.parsePins("")
        assertTrue("空串必须返回空集，否则会误入静态固定分支而非 TOFU", pins.isEmpty())
    }

    @Test
    fun `parsePins 首尾多余逗号不产生空项`() {
        val pins = CertPinTrustManager.parsePins(",sha256/AAA,,sha256/BBB,")
        assertEquals("首尾/重复逗号不应产生空元素", setOf("sha256/AAA", "sha256/BBB"), pins)
    }

    @Test
    fun `parsePins 去重相同项`() {
        val pins = CertPinTrustManager.parsePins("sha256/AAA,sha256/AAA,sha256/BBB")
        assertEquals("重复 pin 必须去重为 Set", setOf("sha256/AAA", "sha256/BBB"), pins)
    }

    @Test
    fun `parsePins 丢弃纯空白项但保留有效项`() {
        val pins = CertPinTrustManager.parsePins("sha256/AAA,   ,sha256/BBB")
        assertEquals(setOf("sha256/AAA", "sha256/BBB"), pins)
    }

    @Test
    fun `parsePins 保留非 sha256 前缀的原始串（交由运行时比对）`() {
        // 暂不对前缀格式做强校验：前缀是否合法由运行时 SPKI 比对兜底，
        // 这里只验证解析层不擅自改写/丢弃内容。
        val pins = CertPinTrustManager.parsePins("sha1/ZZZ,sha256/AAA")
        assertEquals(setOf("sha1/ZZZ", "sha256/AAA"), pins)
        assertFalse("不应把 sha1 误判为空", pins.isEmpty())
    }
}
