package com.padguard.child.hidden

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 契约锁：**隐藏桌面图标绝不允许杀掉正在运行的管理控制台**。
 *
 * 事故（2026-11-18 真机，家长端「显示管理台」后点「去开启」）：
 * 控制台里点授权项 → 拉起系统设置页 → MainActivity 收到 `onUserLeaveHint` →
 * `AppHider.hideManagementUi()` 把 MainActivity 组件置 disabled →
 * 系统 `finishDisabledPackageActivitiesLocked` 立刻强杀**正在运行的这个 Activity**，
 * 连同同一任务栈里压在上面的系统设置页一起关掉。家长看到的就是"控制台一闪就没了"，
 * 返回键也回不去，一项授权都授不上。
 *
 * 修复：桌面图标改由 `activity-alias`（[AppHider.LAUNCHER_ALIAS]）承载，
 * 隐藏只禁用别名，宿主 Activity 与任务栈毫发无损。
 *
 * 这组断言锁的就是上面这个形状——任何人把「隐藏」改回禁用 MainActivity，立刻变红。
 */
class LauncherAliasHiddenTest {

    // ================= 清单形状 =================

    @Test
    fun `MAIN LAUNCHER 必须挂在 activity-alias 上 而不是 MainActivity 本体`() {
        val manifest = readText(
            "src/main/AndroidManifest.xml",
            "护航成长/app/src/main/AndroidManifest.xml"
        )

        // 别名块：必须指向 MainActivity 并持有 LAUNCHER
        val aliasIdx = manifest.indexOf("<activity-alias")
        assertTrue("清单里必须有 activity-alias 承载桌面图标", aliasIdx >= 0)
        val aliasBlock = manifest.substring(aliasIdx, manifest.indexOf("</activity-alias>"))
        assertTrue(
            "别名必须转发给 MainActivity",
            aliasBlock.contains("android:targetActivity=\".MainActivity\"")
        )
        assertTrue(
            "别名必须持有 android.intent.category.LAUNCHER（桌面图标的唯一来源）",
            aliasBlock.contains("android.intent.category.LAUNCHER")
        )
        assertTrue(
            "别名默认必须 enabled=true：首次安装要能看到图标",
            aliasBlock.contains("android:enabled=\"true\"")
        )

        // 宿主的开始标签内不得再出现 LAUNCHER —— 出现即意味着"隐藏图标 = 禁用宿主"
        val hostIdx = manifest.indexOf("android:name=\".MainActivity\"")
        assertTrue("清单里必须仍有 MainActivity 声明", hostIdx >= 0)
        val hostTag = manifest.substring(hostIdx, manifest.indexOf('>', hostIdx) + 1)
        assertFalse(
            "MainActivity 本体不得再带 LAUNCHER 类目：一旦带回去，AppHider 的隐藏就会" +
                "重新作用在运行中的 Activity 上，控制台会再次被系统强杀",
            hostTag.contains("android.intent.category.LAUNCHER")
        )
    }

    @Test
    fun `AppHider 常量必须与清单里的别名一致`() {
        val manifest = readText(
            "src/main/AndroidManifest.xml",
            "护航成长/app/src/main/AndroidManifest.xml"
        )
        assertTrue(
            "清单别名名必须是 .hidden.LauncherAlias（与 AppHider.LAUNCHER_ALIAS 对应）",
            manifest.contains("android:name=\".hidden.LauncherAlias\"")
        )
        assertTrue(
            "AppHider.LAUNCHER_ALIAS 必须是全限定类名且以 .hidden.LauncherAlias 结尾",
            AppHider.LAUNCHER_ALIAS == "com.padguard.child.hidden.LauncherAlias"
        )
    }

    // ================= 隐藏/揭示的作用对象 =================

    @Test
    fun `hideManagementUi 只能禁用图标别名 绝不能禁用宿主 Activity`() {
        val hider = readText(
            "src/main/kotlin/com/padguard/child/hidden/AppHider.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/hidden/AppHider.kt"
        )
        val code = stripComments(hider)
        val hideBody = code.substringAfter("fun hideManagementUi()")
            .substringBefore("fun revealManagementUi()")

        assertTrue(
            "隐藏必须作用在图标别名上",
            hideBody.contains("setComponentEnabled(launcherComponent, enabled = false)")
        )
        assertFalse(
            "隐藏不得再禁用 MainActivity 本体：那会强杀正在运行的控制台" +
                "（finishDisabledPackageActivitiesLocked），家长看到的就是闪退",
            hideBody.contains("setComponentEnabled(mainComponent, enabled = false)")
        )
        assertTrue(
            "隐藏时必须顺手确保宿主 enabled：旧版本把宿主置 disabled 的状态跨升级保留，" +
                "不纠正会出现「图标在、点了没反应」",
            hideBody.contains("setComponentEnabled(mainComponent, enabled = true)")
        )
    }

    @Test
    fun `revealManagementUi 必须同时点亮别名与宿主`() {
        val hider = readText(
            "src/main/kotlin/com/padguard/child/hidden/AppHider.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/hidden/AppHider.kt"
        )
        val code = stripComments(hider)
        val revealBody = code.substringAfter("fun revealManagementUi()")
            .substringBefore("fun isManagementHidden()")
        assertTrue(
            "揭示必须点亮别名（否则桌面图标不回来）",
            revealBody.contains("setComponentEnabled(launcherComponent, enabled = true)")
        )
        assertTrue(
            "揭示必须点亮宿主（别名指向的目标被禁用时，点图标会 ActivityNotFound）",
            revealBody.contains("setComponentEnabled(mainComponent, enabled = true)")
        )
    }

    @Test
    fun `隐藏态判定必须读别名 否则永远显示已揭示`() {
        val hider = readText(
            "src/main/kotlin/com/padguard/child/hidden/AppHider.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/hidden/AppHider.kt"
        )
        val code = stripComments(hider)
        val body = code.substringAfter("fun isManagementHidden()")
            .substringBefore("fun migrateToAliasHiding()")
        assertTrue(
            "isManagementHidden 必须查 launcherComponent —— 隐藏态已经不在宿主身上了",
            body.contains("getComponentEnabledSetting(launcherComponent)")
        )
    }

    // ================= 迁移与任务识别 =================

    @Test
    fun `启动时必须迁移旧版遗留的宿主禁用态`() {
        val app = readText(
            "src/main/kotlin/com/padguard/child/PadGuardApplication.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/PadGuardApplication.kt"
        )
        assertTrue(
            "Application.onCreate 必须调用 appHider.migrateToAliasHiding()：" +
                "覆盖升级后 PMS 里仍留着 MainActivity=disabled，图标点了会 ActivityNotFound",
            stripComments(app).contains("appHider.migrateToAliasHiding()")
        )
        val hider = readText(
            "src/main/kotlin/com/padguard/child/hidden/AppHider.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/hidden/AppHider.kt"
        )
        assertTrue(
            "迁移只做「点亮宿主」这一件事，不得顺手改别名（否则会把家长正在用的图标藏掉）",
            stripComments(hider)
                .substringAfter("fun migrateToAliasHiding()")
                .substringBefore("private fun setComponentEnabled")
                .let { !it.contains("launcherComponent") }
        )
    }

    @Test
    fun `授权回跳的任务识别必须同时认别名与宿主`() {
        val presenter = readText(
            "src/main/kotlin/com/padguard/child/consent/ConsentReturnPresenter.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/consent/ConsentReturnPresenter.kt"
        )
        val code = stripComments(presenter)
        assertTrue(
            "任务底座识别必须走 AppHider.isConsoleTaskHost（别名与宿主都认）",
            code.contains("AppHider.isConsoleTaskHost(")
        )
        assertFalse(
            "不得再用 className == MainActivity 精确比较：图标经别名启动时，" +
                "任务底座可能记录成别名类名，精确比较会让「授权完回不到控制台」",
            code.contains("baseActivity?.className == MainActivity::class.java.name")
        )
    }

    // ============ 管理台揭示会话的鉴权闸门（P0 越权回归） ============

    @Test
    fun `MainActivity 本体必须 non-exported 不能让外部 Intent 直达控制台`() {
        val manifest = readText(
            "src/main/AndroidManifest.xml",
            "护航成长/app/src/main/AndroidManifest.xml"
        )
        val hostIdx = manifest.indexOf("android:name=\".MainActivity\"")
        assertTrue("清单里必须仍有 MainActivity 声明", hostIdx >= 0)
        val hostTag = manifest.substring(hostIdx, manifest.indexOf('>', hostIdx) + 1)
        assertTrue(
            "MainActivity 必须 non-exported：否则第三方 App 一条显式 Intent 带 " +
                "EXTRA_REVEAL=true 即可绕过悬浮卡密码进入家长控制台",
            hostTag.contains("android:exported=\"false\"")
        )
    }

    @Test
    fun `揭示会话必须一次性令牌鉴权 不得信任 Intent 布尔 extra`() {
        val main = stripComments(
            readText(
                "src/main/kotlin/com/padguard/child/MainActivity.kt",
                "护航成长/app/src/main/kotlin/com/padguard/child/MainActivity.kt"
            )
        )
        val floating = stripComments(
            readText(
                "src/main/kotlin/com/padguard/child/hidden/FloatingActionActivity.kt",
                "护航成长/app/src/main/kotlin/com/padguard/child/hidden/FloatingActionActivity.kt"
            )
        )
        val guard = stripComments(
            readText(
                "src/main/kotlin/com/padguard/child/service/GuardService.kt",
                "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardService.kt"
            )
        )
        assertFalse(
            "MainActivity 不得再用 getBooleanExtra(EXTRA_REVEAL)：导出的 alias 可透传该布尔值",
            main.contains("getBooleanExtra(EXTRA_REVEAL")
        )
        assertTrue(
            "揭示态必须经进程内一次性令牌消费后才成立",
            main.contains("consumeRevealToken(intent.getStringExtra(EXTRA_REVEAL))")
        )
        assertTrue(
            "必须提供只由鉴权路径调用的令牌签发口",
            main.contains("fun newRevealToken()") && main.contains("pendingRevealToken = null")
        )
        for ((name, code) in listOf("FloatingActionActivity" to floating, "GuardService" to guard)) {
            assertTrue(
                "$name 启动控制台时必须签发一次性令牌而非传 true",
                code.contains("putExtra(MainActivity.EXTRA_REVEAL, MainActivity.newRevealToken())")
            )
        }
        assertFalse(
            "任何路径都不得再硬编码 EXTRA_REVEAL=true",
            (main + floating + guard).contains("putExtra(MainActivity.EXTRA_REVEAL, true)")
        )
    }

    private fun stripComments(text: String): String = text.lines()
        .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
        .joinToString("\n")

    private fun readText(vararg candidates: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        for (relative in candidates) {
            val direct = File(relative)
            if (direct.isFile) return direct.readText().replace("\r\n", "\n")
            var dir: File? = cwd
            repeat(6) {
                if (dir == null) return@repeat
                val candidate = File(dir, relative)
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
                dir = dir!!.parentFile
            }
        }
        throw AssertionError("找不到源码文件：${candidates.joinToString()}；cwd=$cwd")
    }
}
