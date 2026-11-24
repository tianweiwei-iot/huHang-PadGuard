package com.padguard.server

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * R43 管理端会话级防护的源码契约（纯 JVM 扫描 JS 源码）。
 *
 * 都是弱网/并发/跨会话时序，行为测试需真浏览器难以稳定复现；用源码契约锁定结构，
 * 防止后续重构回退：
 *  - 401 必须比对"发请求时令牌"与"当前令牌"，旧会话的迟到 401 不得踢掉新会话；
 *  - modal/drawer 必须经会话守卫，登出后的迟到回调不得把敏感弹层挂到登录屏；
 *  - 不可逆注销必须锁按钮防连点；
 *  - 搜索 debounce 回到的 load 必须在节点已换走时放弃。
 */
class AdminSessionGuardR43Test {

    @Test
    fun `401必须比对发送令牌与当前令牌 旧会话迟到401不得踢新会话`() {
        val src = js("api.js")
        val http = src.substringAfter("async function http(").substringBefore("// ---------------- 实现")
        assertTrue("const sentToken = config.token" in http, "必须在发请求前捕获发送令牌代次")
        val unauth = http.substringAfter("if (res.status === 401")
            .substringBefore("throw new Error(msg)")
        assertTrue(
            "config.token === sentToken" in unauth,
            "401 出口必须比对当前令牌与发送令牌，否则旧会话迟到 401 会清空新账号 token"
        )
    }

    @Test
    fun `modal与drawer必须经会话守卫 登出后迟到回调不得挂敏感弹层到登录屏`() {
        val src = js("components.js")
        assertTrue("function overlayRoot(" in src, "缺少会话守卫集中出口 overlayRoot")
        val guard = src.substringAfter("function overlayRoot(").substringBefore("// ---------- Modal")
        assertTrue("login-screen" in guard, "守卫必须依据登录屏可见判定登出态")
        assertTrue("createElement(\"div\")" in guard, "登出态应挂载到 detached 节点，不进文档")
        val modal = src.substringAfter("function modal(").substringBefore("function drawer(")
        val drawer = src.substringAfter("function drawer(").substringBefore("function toast(")
        assertTrue("overlayRoot()" in modal, "modal 必须经会话守卫")
        assertTrue("overlayRoot()" in drawer, "drawer 必须经会话守卫")
        assertFalse(
            "getElementById(\"modal-root\")" in modal || "getElementById(\"modal-root\")" in drawer,
            "modal/drawer 不得再直接挂全局 modal-root"
        )
    }

    @Test
    fun `不可逆注销家长必须in-flight锁按钮防连点`() {
        val src = js("views/parents.js")
        val block = src.substringAfter("m.el.querySelector(\"#ep-ok\")")
            .substringBefore("PG.router.refresh()")
        assertTrue("if (epOk.disabled) return" in block, "注销入口必须判重")
        assertTrue("epOk.disabled = true" in block, "注销发起前必须锁按钮")
        // 失败两路都要恢复，防止一次失败后按钮永久锁死
        val restoreCount = block.split("epOk.disabled = false").size - 1
        assertTrue(restoreCount >= 2, "手机号不一致与请求失败两支都必须恢复按钮")
    }

    @Test
    fun `搜索debounce回到的load必须在节点已换走时放弃`() {
        listOf("parents.js" to "#p-kw", "devices.js" to "#d-kw").forEach { (file, sel) ->
            val src = js("views/$file")
            val load = src.substringAfter("async function load(root)")
                .substringBefore("const q =")
            assertTrue(
                "if (!root.querySelector(\"$sel\")) return" in load,
                "$file 的 load 必须在搜索节点已移除时放弃（切页/登出后 debounce 迟到）"
            )
        }
    }

    private fun js(relative: String): String {
        val rel = "padguard-admin/assets/js/$relative"
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (dir == null) return@repeat
            val candidate = File(dir!!, rel)
            if (candidate.isFile) return candidate.readText()
            dir = dir!!.parentFile
        }
        throw AssertionError("找不到管理端源码 $rel；cwd=${System.getProperty("user.dir")}")
    }
}
