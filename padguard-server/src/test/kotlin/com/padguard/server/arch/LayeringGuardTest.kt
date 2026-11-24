package com.padguard.server.arch

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 分层依赖守卫（2026-11-18 阶段 2 架构收敛的固化锁）。
 *
 * 规则：**controller 包内禁止出现 `com.padguard.server.repository` 引用**——
 * Controller 只能依赖 service/security/common/dto，数据访问一律经 service 层。
 *
 * 历史：FilesController（归属判定直查 UploadedFileRepository/DeviceRepository）、
 * ParentControllers（台账查询直用 UsageLogRepository、PolicyController 挂着无用的
 * DeviceRepository）曾直接注入 Repository，HTTP 层混入数据访问规则；
 * 2026-11-18 已全部下沉（FileAccessService / IngestService.listDeviceLogs /
 * 删除死依赖）。本守卫防止未来回潮。
 *
 * ## 为什么用源码扫描而不是 ArchUnit
 * 零新增依赖，与项目既有"源码锁"守卫惯例一致（AuthAuditTrailContractTest /
 * MvcAsyncTraceContractTest 同款）。Kotlin 里使用 repository 包类型只有两条路：
 * import 语句或全限定名——两者都会在源码行留下 `com.padguard.server.repository.`，
 * 按行扫描足够精确；KDoc/行注释中的引用不算代码依赖，予以豁免。
 */
class LayeringGuardTest {

    /** 返回行内的 repository 包引用，注释行豁免。包级函数便于对检测器本身做自测。 */
    internal fun repositoryReferenceIn(line: String): String? {
        val t = line.trimStart()
        // KDoc/注释行豁免：以 '*'（含 KDoc 延续行）、'//'、'/*' 开头
        if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) return null
        return if (t.contains("com.padguard.server.repository.")) t.trim() else null
    }

    private fun offendingLines(): List<String> {
        val controllerDir = File("src/main/kotlin/com/padguard/server/controller")
        val files = controllerDir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty(), "controller 源目录未找到：测试工作目录应为 padguard-server/")
        return files.flatMap { f ->
            f.readLines().mapIndexedNotNull { idx, line ->
                repositoryReferenceIn(line)?.let { "${f.name}:${idx + 1} $it" }
            }
        }
    }

    @Test
    fun `controllers must not reference repository types`() {
        val offenders = offendingLines()
        assertTrue(offenders.isEmpty()) {
            "Controller 层直连 Repository（必须下沉 service 层）:\n" + offenders.joinToString("\n")
        }
    }

    @Test
    fun `detector catches both import and fully-qualified references`() {
        // 自测：检测器必须同时抓到 import 与全限定名两种引用形态，防止守卫静默失效
        assertTrue(
            repositoryReferenceIn("import com.padguard.server.repository.DeviceRepository") != null,
            "import 形态必须被抓到"
        )
        assertTrue(
            repositoryReferenceIn("private val repo: com.padguard.server.repository.DeviceRepository,") != null,
            "全限定名形态必须被抓到"
        )
    }

    @Test
    fun `detector exempts comments and non-repository imports`() {
        // 注释豁免：KDoc 里的历史说明不算依赖
        assertFalse(repositoryReferenceIn(" * 用 [com.padguard.server.repository.DeviceRepository.findByUserId] 而不是 exists") != null)
        assertFalse(repositoryReferenceIn("// 历史：曾直连 com.padguard.server.repository.XxxRepository") != null)
        // 其他包引用不受影响
        assertFalse(repositoryReferenceIn("import com.padguard.server.service.FileAccessService") != null)
        assertFalse(repositoryReferenceIn("import org.springframework.data.domain.PageRequest") != null)
    }
}
