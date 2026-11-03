package com.padguard.core.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [filterKnownCommandTypes] 的单元测试：服务端先于孩子端升级时，
 * 未知的指令类型只应被单独剔除，同批其余指令必须存活。
 * 被剔除的类型明细通过 [CommandBatchFilterResult.droppedTypes] 返回，供调用方记日志。
 */
class CommandBatchFilterTest {

    private fun cmd(msgId: String, type: String) = """
        {"msgId":"$msgId","type":"$type","version":1,"timestamp":1,"expiresAt":9,
         "priority":"NORMAL","payload":{},"signature":"sig"}
    """.trimIndent()

    @Test
    fun `bare array keeps known and drops unknown types`() {
        val batch = Json.parseToJsonElement(
            "[${cmd("m1", "LOCK_SCREEN")},${cmd("m2", "REVEAL_MANAGEMENT_UI")},${cmd("m3", "SOME_FUTURE_TYPE")}]"
        )
        val out = filterKnownCommandTypes(batch)
        assertEquals(2, (out.element as JsonArray).size)
        val ids = out.element.jsonArray.mapNotNull { it.jsonObject["msgId"]?.let { p -> (p as JsonPrimitive).content } }
        assertEquals(listOf("m1", "m2"), ids)
        assertEquals(listOf("SOME_FUTURE_TYPE"), out.droppedTypes)
    }

    @Test
    fun `object envelope filters commands and keeps other fields`() {
        val batch = Json.parseToJsonElement(
            """{"policyVersion":7,"commands":[${cmd("m1", "UNLOCK")},${cmd("m2", "BRAND_NEW")}]}"""
        )
        val out = filterKnownCommandTypes(batch).element.jsonObject
        assertEquals("7", (out["policyVersion"] as JsonPrimitive).content)
        val commands = out["commands"]!!.jsonArray
        assertEquals(1, commands.size)
        assertEquals("m1", ((commands[0].jsonObject["msgId"]) as JsonPrimitive).content)
    }

    @Test
    fun `object envelope without commands array passes through`() {
        val batch = Json.parseToJsonElement("""{"policyVersion":3}""")
        val out = filterKnownCommandTypes(batch)
        assertEquals(batch, out.element)
        assertTrue(out.droppedTypes.isEmpty())
    }

    @Test
    fun `missing or non-string type is left for deserializer to reject`() {
        val batch = Json.parseToJsonElement("""[{"msgId":"m1","timestamp":1}]""")
        val out = filterKnownCommandTypes(batch)
        assertEquals(1, (out.element as JsonArray).size)
        assertTrue(out.droppedTypes.isEmpty())
    }

    @Test
    fun `all unknown still yields a valid empty array instead of throwing`() {
        val batch = Json.parseToJsonElement("[${cmd("m1", "GHOST")},${cmd("m2", "PHANTOM")}]")
        val out = filterKnownCommandTypes(batch)
        assertTrue((out.element as JsonArray).isEmpty())
        assertEquals(listOf("GHOST", "PHANTOM"), out.droppedTypes)
    }

    @Test
    fun `non array non object element passes through untouched`() {
        val primitive = JsonPrimitive("x")
        val out = filterKnownCommandTypes(primitive)
        assertEquals(primitive, out.element)
        assertTrue(out.droppedTypes.isEmpty())
    }
}
