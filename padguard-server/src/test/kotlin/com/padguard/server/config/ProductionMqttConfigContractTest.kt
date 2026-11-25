package com.padguard.server.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 生产 MQTT 设备凭据契约：模板、编排、broker ACL 三处必须同一口径。
 *
 * 事故背景：旧版 `.env.prod.example` 注入共享账号 `pgdevice`，compose 用 `${ VAR:? }`
 * 强制野服运维填写，但生产 `mosquitto/aclfile` 只允许 `pattern ...%u`（deviceId）。
 * 结果是照模板部署后所有设备 MQTT 握手成功、订阅/发布被 ACL 拒绝，
 * 表现仅为"实时推送退化成轮询"，日志没有明显错误。共享账号还是多租户跨家庭全通洞，
 * 不能靠"顺手给 ACL 加一行 pgdevice"修。唯一正确解法是设备凭据留空走每设备账号。
 *
 * 只测代码不够：服务端 `DeviceService` 一直是对的，错的是部署模板与 ACL 不一致。
 */
class ProductionMqttConfigContractTest {

    private fun read(moduleFile: String): String {
        val file = File(moduleFile)
        assumeTrue(file.exists(), "仅在 padguard-server 模块根目录运行本断言（缺 $moduleFile）")
        return file.readText(Charsets.UTF_8)
    }

    private fun activeLines(text: String): List<String> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test
    fun `prod env template keeps device credentials blank`() {
        val lines = activeLines(read(".env.prod.example"))
        val values = listOf("PADGUARD_MQTT_DEVICE_USERNAME", "PADGUARD_MQTT_DEVICE_PASSWORD")
            .map { key ->
                val line = lines.firstOrNull { it.startsWith("$key=") }
                assertTrue(line != null, "模板必须显式列出 $key（留空），否则运维不知道该删掉共享账号")
                line!!.substringAfter("=").trim()
            }
        assertTrue(values.all { it.isEmpty() }, "生产模板不得启用共享设备账号，实际值=$values")
    }

    @Test
    fun `prod compose must not force shared device credentials`() {
        val text = read("docker-compose.prod.yml")
        assertFalse(text.contains("PADGUARD_MQTT_DEVICE_USERNAME:?"))
        assertFalse(text.contains("PADGUARD_MQTT_DEVICE_PASSWORD:?"))
        assertTrue(text.contains("\${PADGUARD_MQTT_DEVICE_USERNAME:-}"))
        assertTrue(text.contains("\${PADGUARD_MQTT_DEVICE_PASSWORD:-}"))
        assertTrue(text.contains("sync-mqtt-users.sh"), "每设备账号必须配套同步脚本说明，否则设备连不上 broker")
    }

    @Test
    fun `mqtt sync operations must have a finite timeout`() {
        val cfg = File("src/main/kotlin/com/padguard/server/config/MqttConfig.kt").readText(Charsets.UTF_8)
        assertTrue(
            "client.timeToWait = operationTimeoutMs" in cfg,
            "Paho timeToWait 默认 -1 无限等待：必须设置有限值，否则半开 broker 可冻结全部请求线程"
        )
        assertTrue(
            "setConnectionTimeout(" in cfg,
            "连接阶段同样必须有超时，否则启动重试循环可能永久挂住"
        )
        val yml = File("src/main/resources/application.yml").readText(Charsets.UTF_8)
        assertTrue(
            "operation-timeout-ms: 5000" in yml,
            "超时值必须是可运维配置项，不能在代码里写死"
        )
    }

    @Test
    fun `prod acl stays per-device and never grants shared pgdevice`() {
        val lines = activeLines(read("mosquitto/aclfile"))
        assertFalse(lines.any { it.equals("user pgdevice", ignoreCase = true) },
            "共享 pgdevice 用户会跨家庭读写全通，禁止加入生产 ACL")
        assertTrue(lines.any { it == "pattern read pg/v1/+/down/%u/#" })
        assertTrue(lines.any { it == "pattern write pg/v1/+/up/%u/#" })
        assertTrue(lines.any { it == "pattern read pg/v1/+/broadcast/+/command" },
            "批量指令订阅是批量提交，少一条 filter 会让整次订阅失败")
    }
}
