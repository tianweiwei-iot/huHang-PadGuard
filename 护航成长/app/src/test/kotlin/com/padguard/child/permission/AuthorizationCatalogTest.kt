package com.padguard.child.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 授权专项的契约回归（纯 JVM，不触碰 Android 运行时）。
 *
 * 四类断言分别锁住用户诉求的四条：
 * - **逻辑合理**：[AuthorizationLedger.needsReauth] 只认「曾授予又掉」，不骚扰主动跳过 / 无开关的机型；
 * - **权限不遗漏**：目录 key 全集、Manifest 声明 ↔ 目录双向覆盖、装机清单与补开页都读同一份目录；
 * - **全厂商全版本**：厂商识别矩阵 + 每项 minSdk / 厂商适用矩阵 + 降级链末端恒存在；
 * - **自动接续**：不可检测项必须走 `watchReturn`，且清单里有 CONFIRMED 状态而非谎报 DONE。
 *
 * 源码扫描沿用服务端 `ScheduledTraceContractTest` 的做法：Gradle 单测工作目录是模块目录，
 * 但不同 IDE / 命令行启动位置可能不同，故 [locate] 从 cwd 逐级向上找。
 */
class AuthorizationCatalogTest {

    // ==================== ① 授权逻辑 ====================

    @Test
    fun `曾授予又掉才拉回重授权`() {
        assertTrue(
            AuthorizationLedger.needsReauth(
                granted = false, everGranted = true, detectable = true, requiredForReauth = true
            )
        )
    }

    @Test
    fun `当前已授予不拉回`() {
        assertFalse(
            AuthorizationLedger.needsReauth(
                granted = true, everGranted = true, detectable = true, requiredForReauth = true
            )
        )
    }

    @Test
    fun `家长主动跳过且从未授予过不拉回`() {
        assertFalse(
            AuthorizationLedger.needsReauth(
                granted = false, everGranted = false, detectable = true, requiredForReauth = true
            )
        )
    }

    @Test
    fun `不可检测的厂商开关永不参与重授权`() {
        assertFalse(
            "厂商私有开关没有查询接口，恒 false 会变成永远消不掉的假告警",
            AuthorizationLedger.needsReauth(
                granted = false, everGranted = true, detectable = false, requiredForReauth = true
            )
        )
        assertFalse(
            AuthorizationLedger.needsReauth(
                granted = false, everGranted = true, detectable = true, requiredForReauth = false
            )
        )
    }

    // ==================== ② 权限不遗漏 ====================

    @Test
    fun `授权目录覆盖全部需人工确认项`() {
        assertEquals(
            setOf(
                "overlay", "usageStats", "installPackages", "accessibility",
                "batteryOptimization", "exactAlarm", "appNotifications", "oemBackground"
            ),
            SpecialPermission.values().map { it.key }.toSet()
        )
    }

    @Test
    fun `对外暴露的 KEY 常量与枚举 key 严格一致`() {
        // KEY_* 现已改为引用枚举实例自己的 key（单一事实源，见 AuthorizationHardeningTest）。
        // 这条断言保留为对外契约锁：RomSettings 的 when 分支按 KEY_* 查厂商候选页，
        // 任何一侧被改回手写字面量而漏同步，都会让厂商页静默退化成"没有候选"。
        assertEquals(SpecialPermission.OVERLAY.key, SpecialPermission.KEY_OVERLAY)
        assertEquals(SpecialPermission.USAGE_STATS.key, SpecialPermission.KEY_USAGE_STATS)
        assertEquals(SpecialPermission.INSTALL_PACKAGES.key, SpecialPermission.KEY_INSTALL_PACKAGES)
        assertEquals(SpecialPermission.ACCESSIBILITY.key, SpecialPermission.KEY_ACCESSIBILITY)
        assertEquals(SpecialPermission.BATTERY_OPTIMIZATION.key, SpecialPermission.KEY_BATTERY_OPTIMIZATION)
        assertEquals(SpecialPermission.EXACT_ALARM.key, SpecialPermission.KEY_EXACT_ALARM)
        assertEquals(SpecialPermission.APP_NOTIFICATIONS.key, SpecialPermission.KEY_APP_NOTIFICATIONS)
        assertEquals(SpecialPermission.OEM_BACKGROUND.key, SpecialPermission.KEY_OEM_BACKGROUND)
    }

    @Test
    fun `每一项都有家长看得懂的名称与缺失后果`() {
        SpecialPermission.values().forEach { perm ->
            assertTrue("${perm.key} 缺标题", perm.label.isNotBlank())
            assertTrue("${perm.key} 缺缺失后果说明", perm.impact.isNotBlank())
        }
    }

    @Test
    fun `不可检测项必须同时是可选且不参与重授权`() {
        SpecialPermission.values().filter { !it.detectable }.forEach { perm ->
            assertTrue("${perm.key} 无状态接口却标成必需", perm.optional)
            assertFalse("${perm.key} 无状态接口却参与掉失自检", perm.requiredForReauth)
        }
        assertEquals(
            "目前只有厂商私有开关无法查询状态",
            listOf(SpecialPermission.OEM_BACKGROUND),
            SpecialPermission.values().filter { !it.detectable }
        )
    }

    @Test
    fun `清单里声明的特殊访问权都在 Manifest 里有对应声明`() {
        val manifest = readText("src/main/AndroidManifest.xml", "护航成长/app/src/main/AndroidManifest.xml")
        listOf(
            "android.permission.SYSTEM_ALERT_WINDOW",     // OVERLAY
            "android.permission.PACKAGE_USAGE_STATS",     // USAGE_STATS
            "android.permission.REQUEST_INSTALL_PACKAGES",// INSTALL_PACKAGES
            "android.permission.POST_NOTIFICATIONS",      // APP_NOTIFICATIONS
            "android.permission.SCHEDULE_EXACT_ALARM"    // EXACT_ALARM
        ).forEach { permission ->
            assertTrue("Manifest 缺少 $permission（引导页会打开一个本应用不在列表里的系统页）",
                manifest.contains(permission))
        }
    }

    @Test
    fun `装机清单补开页与掉失自检都引用同一份目录`() {
        val vm = readText(
            "src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt"
        )
        assertTrue("装机清单没有改用 SpecialPermission.applicable", vm.contains("SpecialPermission.applicable("))
        assertTrue("不可检测项缺少确认态收口", vm.contains("confirmedKeys"))
        assertTrue("引导结束时没有把已授予项写进账本", vm.contains("ledger.observe("))

        val guide = readText(
            "src/main/kotlin/com/padguard/child/permission/PermissionGuideViewModel.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/permission/PermissionGuideViewModel.kt"
        )
        assertTrue("补开页没有读同一份目录", guide.contains("SpecialPermission.missing("))
        assertTrue("补开页没有接入自动接续流水线", guide.contains("setReauthRequired(ReauthSource.MANUAL_REVIEW)"))

        val keeper = readText(
            "src/main/kotlin/com/padguard/child/permission/AuthorizationKeeper.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/permission/AuthorizationKeeper.kt"
        )
        assertTrue("掉失自检没有按账本过滤", keeper.contains("AuthorizationLedger.needsReauth("))
    }

    @Test
    fun `新增必须项后装机引导版本已升版`() {
        val repo = readText(
            "../core/data/src/main/kotlin/com/padguard/core/data/repository/AuthRepository.kt",
            "护航成长/core/data/src/main/kotlin/com/padguard/core/data/repository/AuthRepository.kt"
        )
        assertTrue(
            "清单新增了 4 项却没升 CONSENT_VERSION_CURRENT，存量设备永远补不到（静默失效）",
            repo.contains("CONSENT_VERSION_CURRENT = 2")
        )
    }

    // ==================== ③ 全厂商 / 全 Android 版本 ====================

    @Test
    fun `厂商识别覆盖主流品牌与未知品牌`() {
        assertEquals(RomSettings.Vendor.HONOR, RomSettings.vendorOf("HUAWEI", "HONOR"))
        assertEquals(RomSettings.Vendor.HUAWEI, RomSettings.vendorOf("HUAWEI", "HUAWEI"))
        assertEquals(RomSettings.Vendor.XIAOMI, RomSettings.vendorOf("Xiaomi", "Redmi"))
        assertEquals(RomSettings.Vendor.XIAOMI, RomSettings.vendorOf("xiaomi", "POCO"))
        assertEquals(RomSettings.Vendor.OPPO, RomSettings.vendorOf("OPPO", "OnePlus"))
        assertEquals(RomSettings.Vendor.OPPO, RomSettings.vendorOf("realme", "realme"))
        assertEquals(RomSettings.Vendor.VIVO, RomSettings.vendorOf("vivo", "iQOO"))
        assertEquals(RomSettings.Vendor.SAMSUNG, RomSettings.vendorOf("samsung", "samsung"))
        assertEquals(RomSettings.Vendor.MEIZU, RomSettings.vendorOf("Meizu", "meizu"))
        assertEquals(RomSettings.Vendor.LENOVO, RomSettings.vendorOf("Lenovo", "motorola"))
        assertEquals(RomSettings.Vendor.GENERIC, RomSettings.vendorOf("Google", "google"))
        assertEquals(RomSettings.Vendor.GENERIC, RomSettings.vendorOf(null, null))
    }

    @Test
    fun `每项按 minSdk 精确生效 不多弹无意义的设置页`() {
        val generic = RomSettings.Vendor.GENERIC
        assertFalse("API 30 没有「闹钟和提醒」开关", SpecialPermission.EXACT_ALARM.appliesTo(30, generic, false))
        assertTrue("API 31 起必须引导精确闹钟", SpecialPermission.EXACT_ALARM.appliesTo(31, generic, false))
        assertTrue("API 35 同样适用", SpecialPermission.EXACT_ALARM.appliesTo(35, generic, false))

        assertFalse("API 22 没有电池优化白名单", SpecialPermission.BATTERY_OPTIMIZATION.appliesTo(22, generic, false))
        assertTrue(SpecialPermission.BATTERY_OPTIMIZATION.appliesTo(23, generic, false))
        assertFalse("API 25 没有官方通知设置页", SpecialPermission.APP_NOTIFICATIONS.appliesTo(25, generic, false))
        assertTrue(SpecialPermission.APP_NOTIFICATIONS.appliesTo(26, generic, false))
        assertFalse("API 22 悬浮窗随安装授予", SpecialPermission.OVERLAY.appliesTo(22, generic, false))
        assertTrue(SpecialPermission.OVERLAY.appliesTo(23, generic, false))
    }

    @Test
    fun `DO 形态不引导冗余的安装未知应用`() {
        assertFalse(SpecialPermission.INSTALL_PACKAGES.appliesTo(35, RomSettings.Vendor.GENERIC, true))
        assertTrue(SpecialPermission.INSTALL_PACKAGES.appliesTo(35, RomSettings.Vendor.GENERIC, false))
    }

    @Test
    fun `厂商开关只在定制 ROM 出现 原生 Android 不进清单`() {
        assertFalse(SpecialPermission.OEM_BACKGROUND.appliesTo(35, RomSettings.Vendor.GENERIC, false))
        listOf(
            RomSettings.Vendor.HUAWEI, RomSettings.Vendor.HONOR, RomSettings.Vendor.XIAOMI,
            RomSettings.Vendor.OPPO, RomSettings.Vendor.VIVO, RomSettings.Vendor.SAMSUNG,
            RomSettings.Vendor.MEIZU, RomSettings.Vendor.LENOVO
        ).forEach { vendor ->
            assertTrue("$vendor 的自启动开关漏了", SpecialPermission.OEM_BACKGROUND.appliesTo(29, vendor, false))
        }
    }

    @Test
    fun `目标机型 EMUI 10 API 29 的适用项矩阵`() {
        val applicable = SpecialPermission.values().filter {
            it.appliesTo(29, RomSettings.Vendor.HUAWEI, false)
        }.map { it.key }
        assertEquals(
            setOf(
                "overlay", "usageStats", "installPackages", "accessibility",
                "batteryOptimization", "appNotifications", "oemBackground"
            ),
            applicable.toSet()
        )
        assertFalse("API 29 不该出现精确闹钟", "exactAlarm" in applicable)
    }

    @Test
    fun `防清理指引覆盖有把握的厂商 没把握的宁可不给`() {
        // 2026-11-17 事故机型（华为 EMUI 10）：孩子点「一键清理」把无障碍服务清掉。
        // ROM 清理白名单没有统一系统页面，唯一通道是文字指引——华为/荣耀必须含
        // 启动管理与多任务加锁手势两个步骤，缺任何一个装机趟都白做。
        listOf(RomSettings.Vendor.HUAWEI, RomSettings.Vendor.HONOR).forEach { vendor ->
            val hint = RomSettings.cleanupShieldHint(vendor)
            assertTrue("$vendor 缺防清理指引", hint != null)
            assertTrue("$vendor 指引缺「一键清理」说明", hint!!.contains("一键清理"))
            assertTrue("$vendor 指引缺启动管理步骤", hint.contains("启动管理"))
            assertTrue("$vendor 指引缺多任务加锁手势", hint.contains("锁"))
        }
        // 小米/OPPO/vivo：有把握给出准确步骤的厂商也要给全指引。
        listOf(RomSettings.Vendor.XIAOMI, RomSettings.Vendor.OPPO, RomSettings.Vendor.VIVO)
            .forEach { vendor ->
                assertTrue("$vendor 应有防清理指引", RomSettings.cleanupShieldHint(vendor) != null)
            }
        // 没把握的厂商返回 null：错误的指引比没有指引更坏（家长照做无效会怪产品）。
        listOf(
            RomSettings.Vendor.SAMSUNG, RomSettings.Vendor.MEIZU,
            RomSettings.Vendor.LENOVO, RomSettings.Vendor.GENERIC
        ).forEach { vendor ->
            assertNull("$vendor 不该有臆造的防清理指引", RomSettings.cleanupShieldHint(vendor))
        }
    }

    @Test
    fun `每个厂商私有页候选都是可解析的显式组件 未知厂商优雅降级`() {        assertTrue(RomSettings.privateComponents(RomSettings.Vendor.GENERIC, "oemBackground").isEmpty())
        listOf(
            RomSettings.Vendor.HUAWEI, RomSettings.Vendor.XIAOMI, RomSettings.Vendor.OPPO,
            RomSettings.Vendor.VIVO, RomSettings.Vendor.SAMSUNG, RomSettings.Vendor.MEIZU,
            RomSettings.Vendor.LENOVO
        ).forEach { vendor ->
            val candidates = RomSettings.privateComponents(vendor, "oemBackground")
            assertTrue("$vendor 缺少自启动页候选", candidates.isNotEmpty())
            candidates.forEach { (pkg, cls) ->
                assertTrue("$vendor 候选包名不完整", pkg.contains('.'))
                assertTrue("$vendor 候选类名不完整", cls.contains('.'))
            }
        }
        assertTrue("无关权限不该带厂商私有页", RomSettings.privateComponents(RomSettings.Vendor.HUAWEI, "overlay").isEmpty())
    }

    @Test
    fun `四级降级链末端恒有应用详情页兜底`() {
        val source = readText(
            "src/main/kotlin/com/padguard/child/permission/SpecialPermission.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/permission/SpecialPermission.kt"
        )
        assertTrue("降级链缺少通用应用详情兜底", source.contains("RomSettings.appDetailsIntent(context)"))
        assertTrue("候选页没有逐个 resolveActivity 校验", source.contains("RomSettings.resolveFirst(context, chain)"))
        assertTrue("厂商私有页没有接入", source.contains("RomSettings.privateIntents(context, perm.key)"))
        assertFalse(
            "授权页跳转绝不能带 NEW_TASK（会破坏 ActivityResult 契约）",
            source.contains("Intent.FLAG_ACTIVITY_NEW_TASK") || source.contains("addFlags(")
        )
    }

    // ==================== ④ 一个确认后自动接续 ====================

    @Test
    fun `不可检测项走页面返回的完成判定 清单如实标已带往设置`() {
        val presenter = readText(
            "src/main/kotlin/com/padguard/child/consent/ConsentReturnPresenter.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/consent/ConsentReturnPresenter.kt"
        )
        assertTrue("缺少不可检测项的返回盯守", presenter.contains("fun watchReturn("))
        assertTrue("返回盯守没有等页面确实离开前台", presenter.contains("LEAVE_BUDGET_MS"))

        val screen = readText(
            "src/main/kotlin/com/padguard/child/consent/OneTimeConsentScreen.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/consent/OneTimeConsentScreen.kt"
        )
        assertTrue("清单没有 CONFIRMED 状态渲染", screen.contains("ConsentItem.State.CONFIRMED"))
        assertTrue("缺少「已带您前往设置」的诚实文案", screen.contains("已带您前往设置"))

        val item = readText(
            "src/main/kotlin/com/padguard/child/consent/ConsentItem.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/consent/ConsentItem.kt"
        )
        assertTrue("ConsentItem 没定义 CONFIRMED", item.contains("CONFIRMED"))
    }

    @Test
    fun `补开页的一键补齐复用同一条自动流水线`() {
        val screen = readText(
            "src/main/kotlin/com/padguard/child/ui/profile/PermissionGuideScreen.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/ui/profile/PermissionGuideScreen.kt"
        )
        assertTrue("补开页缺少授权总览", screen.contains("AuthorizationOverview"))
        assertTrue("补开页缺少一键补齐", screen.contains("一键补齐剩余"))
        assertTrue("授权总览没有列出全部适用项", screen.contains("vm.applicableAuthorizations()"))
    }

    @Test
    fun `补开页待补齐口径排除恒false的厂商开关 否则一键补齐永不消失且反复提示`() {
        // 华为/小米等定制 ROM 上 OEM_BACKGROUND.appliesTo=true 但 isGranted() 恒 false：
        // 若把它算进"待补齐"，missing() 永远非空 → 一键补齐按钮永不消失、
        // "全部已开启"永不可达、从任一设置页返回都触发 startConsentReview()
        // 把家长反复拉回授权流水线（真机表现：明明开了还显示未开、反复提示）。
        val catalog = readText(
            "src/main/kotlin/com/padguard/child/permission/SpecialPermission.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/permission/SpecialPermission.kt"
        )
        assertTrue("missing 缺少 includeUndetectable 双口径开关",
            catalog.contains("includeUndetectable: Boolean = true"))
        assertTrue("待补齐口径没有按 detectable 过滤",
            catalog.contains("includeUndetectable || perm.detectable"))

        val guide = readText(
            "src/main/kotlin/com/padguard/child/permission/PermissionGuideViewModel.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/permission/PermissionGuideViewModel.kt"
        )
        assertTrue("补开页仍把恒 false 的厂商开关算进待办",
            guide.contains("SpecialPermission.missing(app, admin.hasOwnerPrivileges(), includeUndetectable = false)"))
        assertTrue("补开页没有单独如实呈现无状态接口的厂商开关",
            guide.contains("noStateApiAuthorizations"))

        // 反向守卫：装机首次引导必须保留厂商项，否则永远不会带家长去开自启动/后台运行。
        val consent = readText(
            "src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt"
        )
        assertFalse("装机流水线误用了待补齐口径，厂商开关将不再被引导",
            consent.contains("missing(app, admin.hasOwnerPrivileges(), includeUndetectable = false)"))

        val screen = readText(
            "src/main/kotlin/com/padguard/child/ui/profile/PermissionGuideScreen.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/ui/profile/PermissionGuideScreen.kt"
        )
        assertFalse("总览仍从含厂商项的 missing 统计 noStateApi",
            screen.contains("missing.count { !it.detectable }"))
        assertTrue("不可检测项不该再画误导性的「关闭态」开关",
            screen.contains("if (perm.detectable)"))
    }

    @Test
    fun `设备管理员已激活时不再重复弹出激活页 避免 reauth 场景误弹且误写已知晓`() {
        val consent = readText(
            "src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt"
        )
        assertTrue(
            "advanceInternal 步骤①请求 DA 必须排除已激活态，否则 reauth(autoStart=true) 场景下" +
                "DA 已激活仍会被重新弹出激活页、并可能误写 daPromptAck",
            consent.contains("ControlMode.LEGACY && !admin.isAdminActive() && KEY_DEVICE_ADMIN !in requestedOnce")
        )
    }

    @Test
    fun `从系统页返回的 ActivityResult 与 onResume 双信号只处理一次`() {
        // 系统设置页 / 管理员确认页 / 录屏确认页返回时，registerForActivityResult 的回调与
        // 本页 onResume 都会到；二者时序不定，若不只认一次，就会"一次返回被 advance 处理两遍"，
        // 表现为刚开完一项就被当成"回来还没开"、提前进入卡顿轮询甚至误跳过——自动接续失稳的根因。
        val consent = readText(
            "src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt"
        )
        assertTrue("缺少 returnExpected 去抖标记", consent.contains("private var returnExpected = false"))
        assertTrue("未实现 consumeReturn 去抖方法", consent.contains("private fun consumeReturn()"))
        assertTrue(
            "onReturnedToApp 未消费去抖标记，一次返回会被处理两次",
            consent.contains("if (!consumeReturn()) return")
        )
    }

    @Test
    fun `屏幕采集自动步骤必须与手动点击共用同一跳转路径`() {
        // 自动步骤⑤曾复制一份 launch 逻辑却漏掉 returnExpected 武装：
        // CapturePermissionActivity 确认返回 → onReturnedToApp 被 consumeReturn() 吞掉 →
        // 不 advance、不 finish → clearProgress() 不执行 → 电池优化/后台运行的
        // confirmedKeys 持久化残留 → 重进向导被 missingManual() 过滤，三项一起"授权不了"。
        val vm = readText(
            "src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt"
        ).replace("\r\n", "\n")
        val step = vm.substringAfter("if (needsCapture() && KEY_CAPTURE !in requestedOnce)")
            .substringBefore("\n        }")
        assertTrue("空函数体，提取方式失效", step.contains("return"))
        assertTrue(
            "屏幕采集自动步骤自行复制了 launch 逻辑（必然漏掉 returnExpected 武装）",
            step.contains("launchFor(KEY_CAPTURE)")
        )
        val captureBranch = vm.substringAfter("KEY_CAPTURE -> {")
            .substringBefore("\n            KEY_VPN ->")
        assertTrue(
            "KEY_CAPTURE 跳转分支缺少 returnExpected 武装，返回会被去抖吞掉",
            captureBranch.contains("returnExpected = true")
        )
    }

    @Test
    fun `一次返回的两个信号不得把下一项隔空标记完成`() {
        // ActivityResult 与 onResume 先后到达；第一个信号 advance 后已给下一项重新武装，
        // 第二个信号若照样消费，就会把家长还没去过的电池优化 / 后台运行标成
        // 「已带往设置」并跳过——三项目标里最隐蔽的一项。
        val vm = readText(
            "src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt"
        ).replace("\r\n", "\n")
        val consume = vm.substringAfter("private fun consumeReturn(): Boolean {")
            .substringBefore("\n    }")
        assertTrue("consumeReturn 提取失败", consume.contains("returnExpected"))
        assertTrue(
            "返回去抖缺少冷却窗口，尾随信号会隔空确认下一项",
            consume.contains("lastReturnConsumedAt") && consume.contains("RETURN_DEBOUNCE_MS")
        )
        assertTrue("消费成功后必须复位单飞标记", consume.contains("returnExpected = false"))
    }

    @Test
    fun `迷路型设置页必须有分步操作指引`() {
        // 无障碍 / 电池优化是"列表型"页面：系统只给一个几十项的列表，家长要自己
        // 找到「护航成长」——真机反馈"从电池优化和无障碍开始逻辑不清"的主因。
        // 主流厂商与通用兜底都必须给步骤，且必须点名本应用（否则还是不知道找谁）。
        listOf(
            RomSettings.Vendor.HUAWEI, RomSettings.Vendor.HONOR, RomSettings.Vendor.XIAOMI,
            RomSettings.Vendor.OPPO, RomSettings.Vendor.VIVO, RomSettings.Vendor.GENERIC
        ).forEach { vendor ->
            val a11y = RomSettings.settingsStepsHint(vendor, "accessibility")
            assertTrue("$vendor 缺无障碍分步指引", a11y != null && a11y.contains("护航成长"))
            val battery = RomSettings.settingsStepsHint(vendor, "batteryOptimization")
            assertTrue("$vendor 缺电池优化分步指引", battery != null && battery.contains("护航成长"))
        }
        // 使用情况 / 安装未知应用也是列表型页面，同样要有步骤。
        assertTrue(RomSettings.settingsStepsHint(RomSettings.Vendor.GENERIC, "usageStats")?.contains("护航成长") == true)
        assertTrue(
            RomSettings.settingsStepsHint(RomSettings.Vendor.GENERIC, "installPackages")?.contains("护航成长") == true
        )
        // 直达开关页的项不给指引（悬浮窗 / 闹钟 / 通知带 package data 落到本应用开关行），
        // 恒 null 的厂商开关有自己的防清理指引，也不重复给。
        listOf("overlay", "exactAlarm", "appNotifications", "oemBackground").forEach { key ->
            assertNull("直达型 $key 不该有多余指引", RomSettings.settingsStepsHint(RomSettings.Vendor.HUAWEI, key))
        }
    }

    @Test
    fun `电池优化完成判定必须兼容厂商白名单与官方查询两套机制`() {
        // 华为/荣耀的省电白名单（受保护应用/电源管理）与 isIgnoringBatteryOptimizations
        // 互不联通：家长在厂商页完成加白后官方 API 仍恒 false，只盯状态会把家长
        // 晾在设置页直到预算耗尽（真机表现：电池优化这一项怎么都过不去）。
        val presenter = readText(
            "src/main/kotlin/com/padguard/child/consent/ConsentReturnPresenter.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/consent/ConsentReturnPresenter.kt"
        )
        assertTrue("缺少「已授予或已返回」混合盯守", presenter.contains("fun watchGrantedOrReturn("))

        val vm = readText(
            "src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt"
        )
        assertTrue("电池优化没有接混合完成判定",
            vm.contains("watchGrantedOrReturn({ perm.isGranted(app) }, perm.label)"))
        assertTrue("电池优化返回未授予没有按已带往设置收口",
            vm.contains("perm == SpecialPermission.BATTERY_OPTIMIZATION && !perm.isGranted(app)"))
    }

    @Test
    fun `卡住复检窗口到期必须当场推进 不许无声冻结等下一次返回`() {
        // 旧实现：窗口到期只是无声停住，要家长再触发两次返回、三趟约 24 秒才肯跳过，
        // 界面全程显示「正在确认…」——真机反馈"返回后自动跳转等待太长"的直接来源。
        val vm = readText(
            "src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt"
        )
        assertTrue("复检窗口到期缺少当场跳过收口", vm.contains("stuck-window-expired"))
        assertFalse("不允许保留跨返回的卡住计数阶梯（回到无声冻结）",
            vm.contains("STUCK_MAX_RETRIES") || vm.contains("stuckRetries"))
        // 行尾先归一化：仓库 autocrlf 下同一文件可能 LF/CRLF 混排，
        // Kotlin readText 不做转换，带换行的断言必须先统一成 LF 再比对。
        val normalized = vm.replace("\r\n", "\n")
        assertTrue("重试前必须作废旧卡住轮询（防旧轮询抢跑覆盖新跳转）",
            normalized.contains("stuckPollJob?.cancel()\n        stuckPollJob = null"))
    }

    @Test
    fun `曾被跳过的项补开成功后必须如实显示已完成`() {
        // 可检测项的真实状态必须先于 skipped/confirmed 流程标记判定：
        // 家长把曾标"已跳过"的项去开成后，清单若仍显示"已跳过"，会让人以为补开没生效。
        val vm = readText(
            "src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt"
        )
        assertTrue(
            "refreshItemStates 缺少可检测项 granted 优先判定",
            vm.contains("perm.detectable && perm.isGranted(app) -> ConsentItem.State.DONE")
        )
    }

    @Test
    fun `补开页两处设置入口都必须先给出分步指引再进系统页`() {
        // 装机向导已接 RomSettings.settingsStepsHint；补开页此前却直接 startActivity，
        // 家长从「权限引导」进无障碍/电池优化列表页，一样在几十项里找不到「护航成长」。
        val screen = readText(
            "src/main/kotlin/com/padguard/child/ui/profile/PermissionGuideScreen.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/ui/profile/PermissionGuideScreen.kt"
        )
        val callCount = Regex("RomSettings\\.settingsStepsHint\\(").findAll(screen).count()
        assertTrue(
            "补开页必须在总览逐行与 per-kind 去开启两处都接线（实际 $callCount 处）",
            callCount >= 2
        )
        assertTrue("缺少指引卡组件", screen.contains("StepsHintCard"))
        assertTrue("指引卡误用错误色：它是教家长怎么找，不是报错",
            screen.contains("containerColor = MaterialTheme.colorScheme.secondaryContainer"))
        assertTrue("指引必须就地展开在被点击的行下，而不是一段无关的通用说明",
            screen.contains("if (activeKey == perm.key)"))
        assertTrue("per-kind 页必须记住本次指引，返回后仍可对照核对",
            screen.contains("activeGuide = RomSettings.settingsStepsHint("))
    }

    @Test
    fun `补开页电池优化不得在厂商页加白后仍红字报未开启`() {
        // 与装机向导同一根因：华为/荣耀省电白名单与 isIgnoringBatteryOptimizations
        // 互不联通，厂商页加白后查询恒 false。补开页若继续红字「尚未开启」，
        // 就是"我明明开了它却说没开"在第二个入口复现。
        val screen = readText(
            "src/main/kotlin/com/padguard/child/ui/profile/PermissionGuideScreen.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/ui/profile/PermissionGuideScreen.kt"
        )
        assertTrue("总览行缺少电池优化中性态文案",
            screen.contains("厂商省电白名单系统查询不到"))
        assertTrue("电池优化仍未脱离错误色分支",
            screen.contains("batteryNoQuery || noStateApi"))
        assertTrue("per-kind 状态卡缺少同一口径的如实提示",
            screen.contains("本页显示未开启不代表没生效"))
    }

    @Test
    fun `系统设置页打不开必须全量收口 否则每点一次继续就重跳同一个打不开的页面`() {
        // launchManualStep 先发事件再挂盯守，launch() 抛异常发生在其后的 UI 侧。
        // 旧 onLaunchFailed 只对 detectable==false 落账：可检测项（无障碍 / 电池优化等）
        // 失败后仍是 missingManual 待办，家长每点一次「继续」就重跳一次同一个坏页面。
        // 同时盯守 / 指引卡 / 闸门豁免 / runtimeInFlight 全都残留，等于半死状态。
        val vm = readText(
            "src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt"
        ).replace("\r\n", "\n")
        val body = vm.substringAfter("fun onLaunchFailed(key: String, reason: String) {")
            .substringBefore("\n    }")
        assertTrue("空函数体，提取方式失效", body.contains("returnExpected = false"))
        assertTrue("可检测项 launch 失败未标记跳过，会重复跳同一个打不开的页面",
            body.contains("SpecialPermission.fromKey(key)?.let { skippedKeys += it.key }"))
        assertTrue("失败后必须停掉刚挂上的后台盯守", body.contains("consentReturn.stop()"))
        assertTrue("失败后必须撤掉可能残留的返回通知", body.contains("consentReturn.cancelNotification()"))
        assertTrue("失败后必须清掉分步指引卡", body.contains("_guide.value = null"))
        assertTrue("运行时权限弹窗失败必须复位 runtimeInFlight，否则后续返回全被吞",
            body.contains("runtimeInFlight = false"))
        assertTrue("跳转前开的闸门豁免必须在失败路径立即撤销（10 分钟空窗）",
            body.contains("gateSuspension.clearAll()"))
        assertTrue("必须清掉旧卡住复检轮询", body.contains("stuckPollJob?.cancel()"))

        // 同源分支：openSettings 返回 null（本机型无入口）时也不能留上一项的指引卡。
        val unavailableIdx = vm.indexOf("advance(\"settings-unavailable\")")
        val before = vm.substring(if (unavailableIdx > 400) unavailableIdx - 400 else 0, unavailableIdx)
        assertTrue("无设置入口时残留上一项指引卡，家长会照着不存在的页面找开关",
            before.contains("_guide.value = null"))
    }

    // ==================== 工具 ====================

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
        throw AssertionError(
            "找不到源码文件：${candidates.joinToString()}；cwd=$cwd。" +
                "若源码被移动，请同步修正本契约测试的路径。"
        )
    }
}
