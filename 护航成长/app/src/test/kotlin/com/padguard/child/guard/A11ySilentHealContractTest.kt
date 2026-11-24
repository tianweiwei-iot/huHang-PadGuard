package com.padguard.child.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 无障碍「静默自愈」链路接线契约（源码扫描锁）。
 *
 * ## 背景：用户报障「管控系统被清理后，无障碍服务起不来」
 * EMUI「一键清理」停用无障碍开关 + 杀进程后，非 DO 设备原先只有
 * 「检测 + 告警 + 等家长到场」一条路，管控真空以分钟计。
 * 2026-11-17 起补第二条静默通道：装机时经 adb 一次性授予
 * `WRITE_SECURE_SETTINGS`，被拉回的进程自己把开关写回
 * `ENABLED_ACCESSIBILITY_SERVICES`，清理→自愈全自动闭环。
 *
 * 全链路四环，任何一环被"顺手清理"都会退化回静默失效（真机一键清理才复现），
 * 故钉死：
 * 1. 清单声明权限（不声明则 pm grant 必失败，静默通道名存实亡）；
 * 2. [AccessibilityCapabilityHealer] 运行时自查授权（未授权自动退回引导路径，不假装能自愈）；
 * 3. [PermissionHealthChecker] 的静默重启用必须被 canSilentHeal() 闸住，
 *    且自愈成功抑制误报告警；
 * 4. [com.padguard.child.guard.OperationGuardAccessibilityService.onUnbind]
 *    必须排即时闹钟（清理的第一刀解绑服务，进程还活着的最后一秒补上秒级兜底）。
 */
class A11ySilentHealContractTest {

    @Test
    fun `清单必须声明 WRITE_SECURE_SETTINGS 否则 pm grant 无法授予`() {
        val manifest = readText(
            "src/main/AndroidManifest.xml",
            "护航成长/app/src/main/AndroidManifest.xml"
        )
        assertTrue(
            "AndroidManifest 未声明 android.permission.WRITE_SECURE_SETTINGS：" +
                "signature|privileged 权限不声明就连装机授权都给不进去，静默自愈通道名存实亡",
            manifest.contains("android.permission.WRITE_SECURE_SETTINGS")
        )
    }

    @Test
    fun `安装脚本必须 best-effort 授予 WRITE_SECURE_SETTINGS`() {
        val sh = readText(
            "../build-and-install.sh",
            "build-and-install.sh"
        )
        assertTrue(
            "build-and-install.sh 缺 pm grant WRITE_SECURE_SETTINGS：装机脚本不授，" +
                "新装机/重装机的设备永远只有告警引导一条路（自愈闭环要靠人记得手动敲 adb）",
            sh.contains("pm grant") && sh.contains("android.permission.WRITE_SECURE_SETTINGS")
        )
    }

    @Test
    fun `Healer 的静默写入通道必须直写优先 且不信任 Android 10+ 的 DO 身份`() {
        val healer = readText(
            "src/main/kotlin/com/padguard/child/guard/AccessibilityCapabilityHealer.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/guard/AccessibilityCapabilityHealer.kt"
        )

        // 2026-11-18 真机事故：设 DO 后无障碍静默自愈彻底失效。
        // 根因是 `dpm.setSecureSetting` 对无障碍两把键抛
        // `SecurityException: Device owners cannot update accessibility_enabled`，
        // 而旧实现 DO 优先 + 提前 return，把本来可用的 WRITE_SECURE_SETTINGS 直写通道挡在门外。
        assertFalse(
            "canSilentHeal 不许回退成无条件 `admin.isDeviceOwner() || checkSelfPermission(...)`：" +
                "Android 10+ 的 DO 写不了无障碍两把键，会把「修不了」伪装成「能自愈」，" +
                "连告警一起吞掉（2026-11-18 真机证伪）",
            healer.contains(
                "admin.isDeviceOwner() || context.checkSelfPermission(PERMISSION_WRITE_SECURE_SETTINGS)"
            )
        )
        assertTrue(
            "canSilentHeal 必须要求 WRITE_SECURE_SETTINGS 直写，DO 仅保留 API<29 的兼容分支",
            healer.contains("admin.isDeviceOwner() && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q")
        )

        // 通道顺序：直写必须排在 DPM 之前，且 DPM 不能再是提前 return 的唯一路径。
        val writeSecureBody = healer.substringAfter("private fun writeSecure(")
            .substringBefore("private fun putSecureDirect(")
        val directIdx = writeSecureBody.indexOf("putSecureDirect(")
        val doIdx = writeSecureBody.indexOf("admin.setSecureSetting(")
        assertTrue("writeSecure 必须存在直写分支 putSecureDirect", directIdx >= 0)
        assertTrue("writeSecure 必须保留 DPM 兜底分支", doIdx >= 0)
        assertTrue(
            "writeSecure 必须先试直写再退回 DPM（顺序颠倒就是 2026-11-18 事故原样复现）",
            directIdx < doIdx
        )
        // 直写实现本身仍走 Settings.Secure.putString
        assertTrue(
            "直写必须用 Settings.Secure.putString（不受 DO 无障碍白名单限制的通道）",
            healer.contains("Settings.Secure.putString(context.contentResolver, key, value)")
        )

        // 日志必须如实：写失败不许再打"force-enabled"。
        assertTrue(
            "enable 写入失败必须有独立的失败日志（旧代码无条件打成功，掩盖了 DO 拒绝写入）",
            healer.contains("a11y service enable write FAILED")
        )

        // 重绑不允许「摘掉了写不回」：摘除写失败必须立刻中止。
        assertTrue(
            "rebind 必须在摘除写失败时中止，绝不能把服务留在启用名单之外",
            healer.contains("a11y rebind aborted")
        )
        // 串行化：读-改-写必须互斥，否则并发 heal/rebind/disable 互相覆盖名单
        assertTrue(
            "ENABLED_ACCESSIBILITY_SERVICES 的读-改-写必须经 opMutex 串行化，" +
                "否则并发健康操作会互相覆盖、甚至把解绑中移除的服务加回去（权限放大）",
            healer.contains("private val opMutex = Mutex()")
        )
        // 恢复写前必须重读名单（防覆盖并发改动）
        val reReadCount = Regex("恢复写前重新读取").findAll(healer).count()
        assertTrue(
            "heal 与 rebind 的恢复写都必须「先重读当前名单再写回」" +
                "（直接回写延迟前旧快照会覆盖并发 disable/解绑的改动，把本服务加回启用名单）",
            reReadCount >= 2
        )
    }

    @Test
    fun `PermissionHealthChecker 的静默自愈必须被 canSilentHeal 闸住 且自愈成功不误报`() {
        val checker = readText(
            "src/main/kotlin/com/padguard/child/guard/PermissionHealthChecker.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/guard/PermissionHealthChecker.kt"
        )
        // 静默重启用前必须先确认设备真的有写入通道
        assertTrue(
            "checkAccessibility 里 healer.ensureEnabled() 必须包在 canSilentHeal() 判定内：" +
                "未授权设备硬调只会得到一行日志，且掩盖「应走告警引导」的事实",
            checker.contains("if (healer.canSilentHeal())")
        )
        // 自愈成功抑制告警（否则家长收到"无障碍已关闭"误报，人到场却无事可做）
        assertTrue(
            "静默自愈成功后必须抑制 GUARD_PERMISSION_DROPPED 告警" +
                "（needsA11yDroppedAlert + silentHealCapable 组合判定），" +
                "否则 1~3 分钟的自动恢复窗口内家长端会收到一条误报",
            checker.contains("needsA11yDroppedAlert(a11yNow, healer.canSilentHeal())")
        )
    }

    @Test
    fun `无障碍服务被解绑的最后一刻必须排即时兜底闹钟`() {
        val a11y = readText(
            "src/main/kotlin/com/padguard/child/guard/OperationGuardAccessibilityService.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/guard/OperationGuardAccessibilityService.kt"
        )
        assertTrue(
            "onUnbind 必须 scheduleImmediate：EMUI 一键清理先解绑再杀进程，" +
                "这一拍是进程活着的最后一秒，不排闹钟就只能等 60s 周期闹钟 + 家长到场",
            a11y.contains("GuardAlarmReceiver.scheduleImmediate(this)")
        )
        // 反向断言：onUnbind 不许把兜底退化为纯日志
        val onUnbind = a11y.substringAfter("override fun onUnbind(intent: Intent?): Boolean {")
            .substringBefore("override fun")
        assertFalse(
            "onUnbind 里不允许出现 runCatching 吞掉 scheduleImmediate 失败还返回 true 的" +
                "「纯成功」形态（当前实现失败留 Logger.w，属如实留痕，不许改回假装成功）",
            onUnbind.contains(".getOrDefault(true)")
        )
    }

    @Test
    fun `看屏看门狗与开机恢复的静默自愈必须用 canSilentHeal 不许退回 isDeviceOwner`() {
        val guard = readText(
            "src/main/kotlin/com/padguard/child/service/GuardService.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardService.kt"
        )
        // 看屏高频看门狗段（runSafely("a11yLiveHeal") 到日志批量上报之前）
        val liveHeal = guard.substringAfter("runSafely(\"a11yLiveHeal\")")
            .substringBefore("runSafely(\"uploadPending\")")
        assertTrue(
            "看屏掉线的高频自愈必须由 canSilentHeal() 判定：非 DO + WRITE_SECURE_SETTINGS 的" +
                "目标平板也具备静默写名单能力，只认 isDeviceOwner 会让它空等周期自检、看屏被打断",
            liveHeal.contains("a11yCapabilityHealer.canSilentHeal()")
        )
        assertFalse(
            "看屏看门狗不许用 admin.isDeviceOwner() 当静默能力判定：" +
                "会挡住已授权的非 DO 设备，看屏期间掉线无法 10 秒级自愈",
            liveHeal.contains("if (admin.isDeviceOwner())")
        )
        // 开机授权镜像恢复段（consentStoreRestore）
        val consentRestore = guard.substringAfter("runSafely(\"consentStoreRestore\")")
            .substringBefore("runSafely(\"uninstallBlock\")")
        assertTrue(
            "开机「授权一次永不失效」的静默重绑必须由 canSilentHeal() 门控：" +
                "退回 isDeviceOwner 会让已授权非 DO 设备开机这一拍不自愈",
            consentRestore.contains("a11yCapabilityHealer.canSilentHeal()")
        )
    }

    @Test
    fun `守护服务启动即跑权限自检 自愈链的入口不许断`() {
        val guard = readText(
            "src/main/kotlin/com/padguard/child/service/GuardService.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardService.kt"
        )
        assertTrue(
            "GuardService 启动/主循环必须调用 permissionHealthChecker.check()：" +
                "它是闹钟拉回进程后触发 healer.ensureEnabled 的唯一入口，" +
                "砍掉即「进程被拉回、开关仍是关的、没人补写」",
            guard.contains("permissionHealthChecker.check()")
        )
        assertTrue(
            "运行期必须周期复检 recheckSpecialCapabilities（进程长期存活期间被 ROM 解绑也能拉回）",
            guard.contains("permissionHealthChecker.recheckSpecialCapabilities()")
        )
    }

    @Test
    fun `设置观察者只读判定 两种清理落点与解绑防复活全部钉死`() {
        val ours = "com.padguard.child.debug/com.padguard.child.guard.OperationGuardAccessibilityService"
        val others = "com.other/a11y:com.third/Service"
        val healthy = "$others:$ours"

        // 健康态（名单有我们 + 总开关开）：不写 —— 这是防自激循环的第一道闸
        assertFalse(
            "名单有本服务且总开关开时必须判为健康、不触发写回（否则写回→回调→再写死循环）",
            AccessibilityCapabilityHealer.needsReenable(healthy, 1, ours)
        )
        // 落点①：被移出启用名单 → 必须写回
        assertTrue(
            "EMUI 清理把本服务移出启用名单时必须触发静默写回",
            AccessibilityCapabilityHealer.needsReenable(others, 1, ours)
        )
        assertTrue(
            "名单为空（更彻底的清理）同样必须触发写回",
            AccessibilityCapabilityHealer.needsReenable(null, 1, ours)
        )
        // 落点②：名单在但总开关被置 0 → 必须写回（否则"检测说在、服务不跑"的静默锁死）
        assertTrue(
            "名单保留但 ACCESSIBILITY_ENABLED=0 是 EMUI 清理的第二种落点，必须触发写回",
            AccessibilityCapabilityHealer.needsReenable(healthy, 0, ours)
        )
        // 忽略大小写（不同 ROM 大小写不一致不应误判为被关）
        assertFalse(
            "组件名大小写差异不能误触发写回",
            AccessibilityCapabilityHealer.needsReenable(healthy.uppercase(), 1, ours.lowercase())
        )
    }

    @Test
    fun `设置观察者接线 主控闸与回声抑制必须齐全 否则解绑后复活或自激`() {
        val healer = readText(
            "src/main/kotlin/com/padguard/child/guard/AccessibilityCapabilityHealer.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/guard/AccessibilityCapabilityHealer.kt"
        )
        // 订阅父 URI + descendants：部分 ROM 只通知父节点，漏了就收不到清理事件
        assertTrue(
            "必须订阅 Settings.Secure.CONTENT_URI 且 notifyForDescendants=true，" +
                "只订阅 per-key URI 会漏掉 EMUI 仅通知父节点的 ROM",
            healer.contains("registerContentObserver(Settings.Secure.CONTENT_URI, true, observer)")
        )
        // 主控闸：解绑后必须拒绝写，否则把刚禁用的服务重新拉起（复活管控）
        assertTrue(
            "回调必须由 shouldHeal() 主控闸 guard，解绑后返回 false 拒绝写回",
            healer.contains("if (shouldHeal?.invoke() != true)")
        )
        // 回声抑制统一在 writeSecure 单一入口设置，防止某个写路径忘记设窗
        assertTrue(
            "回声抑制窗必须在 writeSecure 单一写入口统一设置，" +
                "否则 disable/rebind 等写路径漏设会把自己的写当外部清理",
            healer.contains("writeEchoUntil = SystemClock.elapsedRealtime() + WRITE_ECHO_WINDOW_MS")
        )
        // 抖动合并不可省：ROM 连改多键不合并会并发多次读-改-写
        assertTrue(
            "回调必须 debounce 合并抖动",
            healer.contains("delay(WATCH_DEBOUNCE_MS)")
        )

        val guard = readText(
            "src/main/kotlin/com/padguard/child/service/GuardService.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardService.kt"
        )
        // 必须在抗杀的 GuardService 注册，且传入主控开关探针
        assertTrue(
            "GuardService 必须注册设置观察者，否则不存在秒级自愈宿主",
            guard.contains("a11yCapabilityHealer.startWatching(scope)")
        )
        assertTrue(
            "注册时必须传 GuardMasterSwitch.active 探针作为解绑防复活闸：" +
                "用 boundNow 或常量 true 都会在解绑窗口把服务重新拉起",
            guard.contains("a11yCapabilityHealer.startWatching(scope) { guardMasterSwitch.active.value }")
        )
        assertTrue(
            "服务销毁必须注销观察者，否则 HandlerThread 随服务反复重建泄漏",
            guard.contains("a11yCapabilityHealer.stopWatching()")
        )
    }

    @Test
    fun `名单纯函数只动自身条目 不覆盖其他无障碍服务`() {
        val ours = "com.padguard.child.debug/com.padguard.child.guard.OperationGuardAccessibilityService"
        val others = listOf("com.other/a11y", "com.third/Service")

        assertEquals(emptyList<String>(), AccessibilityCapabilityHealer.splitEntries(null))
        assertEquals(emptyList<String>(), AccessibilityCapabilityHealer.splitEntries(""))
        assertEquals(
            listOf("a/b", "c/d"),
            AccessibilityCapabilityHealer.splitEntries("a/b::  :c/d:")
        )
        assertEquals(others + ours, AccessibilityCapabilityHealer.withEntry(others, ours))
        assertEquals(
            others + ours.uppercase(),
            AccessibilityCapabilityHealer.withEntry(others, ours.uppercase())
        )
        val withOurs = others + ours
        // 已存在（忽略大小写）时必须原样返回，不制造重复条目
        assertEquals(withOurs, AccessibilityCapabilityHealer.withEntry(withOurs, ours.uppercase()))
        assertEquals(others, AccessibilityCapabilityHealer.withoutEntry(withOurs, ours))
        assertEquals(withOurs, AccessibilityCapabilityHealer.withoutEntry(withOurs, "com.another/Service"))
    }

    @Test
    fun `授权总览与失效引导页如实区分自愈已开启与未开启`() {
        val enabled = com.padguard.child.ui.profile.authorizationSummaryText(
            com.padguard.child.ui.profile.AuthorizationSummary(total = 6, silentHealEnabled = true)
        )
        assertTrue("已授权设备要告知清理后会自动恢复", enabled.contains("自动恢复"))
        assertTrue("已授权设备应给出恢复时间预期", enabled.contains("1~3 分钟"))

        val disabled = com.padguard.child.ui.profile.authorizationSummaryText(
            com.padguard.child.ui.profile.AuthorizationSummary(total = 6, silentHealEnabled = false)
        )
        assertTrue("未授权设备必须如实说明需手动重开", disabled.contains("需手动重开"))
        assertTrue("未授权设备要给出可执行的授权出口", disabled.contains("WRITE_SECURE_SETTINGS"))
        assertFalse("未授权设备绝不能显示自动恢复", disabled.contains("可在 1~3 分钟内自动恢复"))

        val guide = readText(
            "src/main/kotlin/com/padguard/child/guard/A11yRebindGuideActivity.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/guard/A11yRebindGuideActivity.kt"
        )
        assertTrue("引导页必须运行时查询能力，不能写死一句话", guide.contains("canSilentHeal()"))
        assertTrue("已授权分支要给恢复时间预期", guide.contains("1~3 分钟"))
        assertTrue("未授权分支要给出授权命令", guide.contains("pm grant WRITE_SECURE_SETTINGS"))
    }

    @Test
    fun `授权守护 Keeper 的无障碍自愈必须走 canSilentHeal 不许回退 hasOwner DO 门`() {
        val src = readText(
            "src/main/kotlin/com/padguard/child/permission/AuthorizationKeeper.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/permission/AuthorizationKeeper.kt"
        )
        // 去掉注释行再判定：注释里必须解释"为什么不能用 hasOwner"，若把旧门写进注释会误报。
        val code = src.lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }
            .joinToString("\n")

        assertTrue(
            "Keeper 必须保留 healer.ensureEnabled() 自愈调用",
            code.contains("healer.ensureEnabled()")
        )
        // 关键：ensureEnabled 所在那一条 if 必须是 canSilentHeal()，不能是 hasOwner/isDeviceOwner。
        val callLine = code.lines().first { it.contains("healer.ensureEnabled()") }
        val idx = code.lines().indexOf(callLine)
        val window = code.lines().subList((idx - 3).coerceAtLeast(0), idx)
            .joinToString("\n")
        assertTrue(
            "Keeper 的 ensureEnabled 必须由 canSilentHeal() 闸住：" +
                "非 DO+已授权设备（目标华为平板）在开机/服务启动/周期巡检这条入口也要能自愈",
            window.contains("healer.canSilentHeal()")
        )
        assertFalse(
            "Keeper 的 ensureEnabled 不得关在 hasOwner 门内——该门对非 DO 恒 false，是第三处漏网",
            window.contains("hasOwner")
        )
        assertFalse(
            "Keeper 的 ensureEnabled 不得关在 isDeviceOwner 门内",
            window.contains("isDeviceOwner")
        )
        // 运行时权限补授只有 DO/PO 能做，必须仍留在 hasOwner 门（不能一起放宽，那会虚构能力）。
        assertTrue(
            "运行时权限补授 permissionGranter 必须仍在 hasOwner 门内（只有 DO/PO 能静默 re-grant）",
            code.contains("if (hasOwner)")
        )
        assertTrue(
            "permissionGranter.ensureGranted 调用必须保留",
            code.contains("permissionGranter.ensureGranted()")
        )
    }

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
