package com.padguard.child.ui.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R42：「系统锁由谁压着 / 家长解锁是否完成」是解锁链唯一持久状态，不是可丢的 UI 偏好。
 *
 * 旧实现用 apply()：写内存即返回、异步刷盘。家长下发解锁后进程若被回收，
 * "待撤锁"意图与压锁记账可能在落盘前丢失，自愈触发点再也追不回遗留系统锁，
 * 表现为"家长已放行、孩子端仍停在锁面"。旧版本残留 pushed_at 的升级场景尤其明显。
 */
class KeyguardPersistenceContractTest {

    @Test
    fun `压锁记账与待撤锁意图都必须同步落盘`() {
        val src = stripComments(
            readText(
                "app/src/main/kotlin/com/padguard/child/ui/lock/SystemKeyguardBridge.kt",
                "src/main/kotlin/com/padguard/child/ui/lock/SystemKeyguardBridge.kt"
            )
        )
        assertTrue(
            "压锁记账必须 commit()",
            src.contains("putLong(KEY_PUSHED_AT, System.currentTimeMillis()).commit()")
        )
        assertTrue(
            "待撤锁意图必须 commit()",
            src.contains("putLong(KEY_PENDING_UNLOCK_AT, System.currentTimeMillis()).commit()")
        )
        assertTrue(
            "清除待撤锁必须 commit()",
            src.contains("remove(KEY_PENDING_UNLOCK_AT).commit()")
        )
        assertTrue(
            "清除压锁记账必须 commit()",
            src.contains("remove(KEY_PUSHED_AT).commit()")
        )
        assertFalse(
            "解锁链标记禁止 apply()：进程在异步落盘前被杀会永久丢解锁意图",
            src.contains(".apply()")
        )
    }

    /** 源码注释引用 apply() 作反例，扫描前剥离，避免注释命中的假红。 */
    private fun stripComments(text: String): String = text
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
        .lines().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }

    private fun readText(vararg candidates: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        for (relative in candidates) {
            val direct = File(relative)
            if (direct.isFile) return direct.readText()
            var dir: File? = cwd
            repeat(6) {
                if (dir == null) return@repeat
                val candidate = File(dir, relative)
                if (candidate.isFile) return candidate.readText()
                dir = dir!!.parentFile
            }
        }
        throw AssertionError("找不到源码文件：${candidates.joinToString()}；cwd=$cwd")
    }
}
