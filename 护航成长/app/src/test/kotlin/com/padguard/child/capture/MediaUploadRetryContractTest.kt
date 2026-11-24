package com.padguard.child.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R45：媒体重试队列 enqueue 半截文件不泄漏的契约回归（纯 JVM 源码扫描）。
 *
 * [MediaUploadRetry.enqueue] 的 `copyTo` 中途失败（磁盘满，录屏文件数十 MB 最易触发）
 * 时，半截 dest 不在索引、drain/trim/MAX_AGE 都只处理索引内文件 → media_retry/ 永久泄漏。
 * 修复：copyTo 失败当场删 dest 再抛，源文件保持原样，重试语义不破坏。
 */
class MediaUploadRetryContractTest {

    private fun source(): String {
        val relative = "src/main/kotlin/com/padguard/child/capture/MediaUploadRetry.kt"
        val direct = File(relative)
        if (direct.isFile) return direct.readText()
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir: File? = cwd
        repeat(6) {
            if (dir == null) return@repeat
            val candidate = File(dir!!, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir!!.parentFile
        }
        throw AssertionError("找不到 $relative；cwd=$cwd，若源码被移动请同步修正本契约测试。")
    }

    @Test
    fun `copyTo 失败必须当场删除半截 dest 再抛`() {
        val src = source()
        // 剥注释，避免断言命中注释文字
        val code = src.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//[^\\n]*"), "")

        val enqueueBody = code.substringAfter("fun enqueue(").substringBefore("suspend fun drain")

        assertTrue(
            "enqueue 里 copyTo 必须被 try/catch 包住",
            Regex("try[\\s]*\\{[\\s\\S]*?copyTo\\(dest, overwrite = true\\)[\\s\\S]*?\\}[\\s]*catch").containsMatchIn(enqueueBody)
        )
        val catchBlock = enqueueBody.substringAfter("catch (e: Exception)")
        assertTrue(
            "catch 块必须删除半截 dest 后 rethrow，否则 media_retry/ 永久泄漏无主文件",
            "dest.delete()" in catchBlock && "throw e" in catchBlock
        )
        // 裸 copyTo（无 try 包裹）不得回归
        val bare = Regex("(?<!try[\\s\\S]{0,200})source\\.copyTo\\(dest, overwrite = true\\)")
        assertFalse(
            "不允许出现无守卫的裸 copyTo(dest)（半截文件无清理者）",
            bare.containsMatchIn(enqueueBody) && !enqueueBody.contains("catch (e: Exception)")
        )
    }
}
