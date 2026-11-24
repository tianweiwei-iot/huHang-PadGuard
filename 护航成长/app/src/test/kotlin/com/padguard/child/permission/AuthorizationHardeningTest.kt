package com.padguard.child.permission

import com.padguard.child.ui.profile.AuthorizationSummary
import com.padguard.child.ui.profile.authorizationSummaryText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 授权专项「第二轮加固」的契约回归（纯 JVM + 源码扫描，不触碰 Android 运行时）。
 *
 * 与 [AuthorizationCatalogTest] 的分工：前者锁「授权系统长什么样」，
 * 本类锁「这一轮改掉的缺陷不许回来」：
 * - 逻辑：isGranted 一律 fail-closed、版本阈值与 minSdk 对齐；
 * - 架构：key 只有一份字面量、目录新增项自动获得 KEY 常量；
 * - 代码：无障碍深链走编译期类引用；
 * - 安全：账本 key 白名单、设置页候选按系统应用优先；
 * - 功能：授权总览目录驱动、返回前台刷新实时状态。
 *
 * 源码扫描沿用 [AuthorizationCatalogTest] 的 locate 思路：Gradle 单测 cwd 可能是
 * 模块目录或仓库根，逐级向上找，避免 IDE / 命令行启动位置差异造成假红。
 */
class AuthorizationHardeningTest {

    // ==================== ① 逻辑：失败必须保守 ====================

    @Test
    fun `isGranted 的异常兜底一律 fail-closed 不许再出现 getOrDefault true`() {
        val source = readText(
            "src/main/kotlin/com/padguard/child/permission/SpecialPermission.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/permission/SpecialPermission.kt"
        )
        assertFalse(
            "INSTALL_PACKAGES 曾用 getOrDefault(true)：ROM 上查询抛异常会被当成已授予，" +
                "装机不引导、远程安装静默失效且界面毫无提示",
            source.contains(".getOrDefault(true)")
        )
    }

    @Test
    fun `通知项的状态阈值与 minSdk 对齐为 O`() {
        val source = readText(
            "src/main/kotlin/com/padguard/child/permission/SpecialPermission.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/permission/SpecialPermission.kt"
        )
        assertTrue(
            "APP_NOTIFICATIONS.minSdk=O(26)，isGranted 却按 N(24) 判断会让" +
                "「是否适用」与「是否已授予」两套口径分裂",
            source.contains("if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@runCatching true")
        )
        assertEquals(26, SpecialPermission.APP_NOTIFICATIONS.minSdk)
    }

    // ==================== ② 架构：单一事实源 ====================

    @Test
    fun `KEY 常量引用枚举自身的 key 而不是第二份字符串字面量`() {
        val source = readText(
            "src/main/kotlin/com/padguard/child/permission/SpecialPermission.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/permission/SpecialPermission.kt"
        )
        assertFalse(
            "const val 只能接字面量，会逼我们抄回第二份 key；改用 val + 枚举引用",
            source.contains("const val KEY_")
        )
        SpecialPermission.values().forEach { perm ->
            assertTrue(
                "${perm.name} 的 KEY 常量没有引用枚举 key",
                source.contains("val KEY_${perm.name} = ${perm.name}.key")
            )
        }
    }

    @Test
    fun `目录里每一项都有且只有一个稳定 key`() {
        val keys = SpecialPermission.values().map { it.key }
        assertEquals("key 出现重复会导致跳转链 / 账本串项", keys.size, keys.toSet().size)
        keys.forEach { key ->
            assertEquals("$key 查不回自己", key, SpecialPermission.fromKey(key)?.key)
        }
        assertEquals("未知 key 必须安全返回 null", null, SpecialPermission.fromKey("not-a-permission"))
    }

    // ==================== ③ 代码：编译期安全 ====================

    @Test
    fun `无障碍深链用编译期类引用 不再写死服务类名字符串`() {
        val source = readText(
            "src/main/kotlin/com/padguard/child/permission/SpecialPermission.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/permission/SpecialPermission.kt"
        )
        assertTrue(
            "服务类被重命名时写死字符串只会让深链静默失效，必须编译期报错",
            source.contains("ComponentName(context, OperationGuardAccessibilityService::class.java)")
        )
        assertFalse(
            source.contains("\"com.padguard.child.guard.OperationGuardAccessibilityService\"")
        )
    }

    @Test
    fun `装机清单把厂商防清理步骤拼进后台运行条目`() {
        // 2026-11-17 事故：EMUI「一键清理」把无障碍服务清掉。防清理白名单没有系统页面
        // 可跳，装机这一趟必须靠 OEM_BACKGROUND 条目的文字指引把步骤讲全；
        // 断掉接线（改回 perm.impact 原样透传）会让装机趟静默退化回泛泛的省电提示。
        val vm = readText(
            "src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt"
        )
        assertTrue(
            "装机清单没有把防清理指引拼进 impact",
            vm.contains("cleanupShieldExtraFor")
        )
        assertTrue(
            "防清理指引没有走 ROM 适配层",
            vm.contains("RomSettings.cleanupShieldHint")
        )
        assertTrue(
            "防清理指引只该拼给厂商后台开关条目，其他条目拼了会张冠李戴",
            vm.contains("if (perm != SpecialPermission.OEM_BACKGROUND) return \"\"")
        )
    }

    // ==================== ④ 安全：账本白名单 / 设置页防劫持 ====================

    @Test
    fun `账本只收可检测且参与掉失自愈的目录项`() {
        assertTrue(AuthorizationLedger.isTrackable("usageStats"))
        assertTrue(AuthorizationLedger.isTrackable("batteryOptimization"))
        assertFalse("厂商私有开关无状态接口，记进账本只会得到永远消不掉的假告警",
            AuthorizationLedger.isTrackable("oemBackground"))
        assertFalse(AuthorizationLedger.isTrackable("unknown-key"))
        assertFalse(AuthorizationLedger.isTrackable(""))
    }

    @Test
    fun `账本白名单覆盖目录全矩阵`() {
        SpecialPermission.values().forEach { perm ->
            assertEquals(
                "${perm.key} 的入账资格与 needsReauth 两道闸不一致",
                perm.detectable && perm.requiredForReauth,
                AuthorizationLedger.isTrackable(perm.key)
            )
        }
    }

    @Test
    fun `Android 11 以上厂商私有页的包可见性声明与候选表逐包对应`() {
        val rom = readText(
            "src/main/kotlin/com/padguard/child/permission/RomSettings.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/permission/RomSettings.kt"
        )
        val manifest = readText(
            "src/main/AndroidManifest.xml",
            "护航成长/app/src/main/AndroidManifest.xml"
        )
        // 只抓 pair 的「包名」那一段（`"xxx" to "完整类名"`），不把类名误当包名。
        val vendorPackages = Regex("\"([a-z][a-z0-9_.]*)\"\\s+to\\s+\"")
            .findAll(rom).map { it.groupValues[1] }.toSet()
        assertTrue("候选表没解析到厂商包名，正则需同步", vendorPackages.size >= 5)
        vendorPackages.forEach { pkg ->
            assertTrue(
                "API 30+ 包可见性：$pkg 未在 <queries> 声明，resolveActivity 会返回 null，" +
                    "厂商私有页被全部跳过、降级到应用详情页",
                manifest.contains("<package android:name=\"$pkg\" />")
            )
        }
        // Manifest 的**注释**里会解释为何不用该权限，所以只看真正的 uses-permission 行。
        val declaredPermissions = manifest.lineSequence()
            .filter { it.contains("<uses-permission") }
            .joinToString("\n")
        assertFalse(
            "全量包可见性是上架高危权限，只应逐个声明",
            declaredPermissions.contains("QUERY_ALL_PACKAGES")
        )
    }

    @Test
    fun `设置页候选按系统应用优先 第三方页降级到最后`() {
        val source = readText(
            "src/main/kotlin/com/padguard/child/permission/RomSettings.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/permission/RomSettings.kt"
        )
        assertTrue("缺少 FLAG_SYSTEM 判定", source.contains("ApplicationInfo.FLAG_SYSTEM"))
        assertTrue("漏了「系统应用升级后」标记", source.contains("FLAG_UPDATED_SYSTEM_APP"))
        assertTrue("非系统候选没有被降级", source.contains("non-system settings handler demoted"))
        assertTrue(
            "极少数阉割 ROM 上仍必须给一个能打开的页面，不能点了没反应",
            source.contains("nonSystem.firstOrNull()")
        )
    }

    // ==================== ⑤ 架构：单次遍历 ====================

    @Test
    fun `掉失自检一次遍历完成记账与判定 不再重复查系统状态`() {
        val source = readText(
            "src/main/kotlin/com/padguard/child/permission/AuthorizationKeeper.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/permission/AuthorizationKeeper.kt"
        )
        // 只看代码行：本文件的新注释里恰好提到了被替换掉的旧方法名，
        // 直接 contains 会被注释误伤 —— 源码扫描契约必须先剥掉整行注释。
        val code = source.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertFalse(
            "旧写法 applicable() + missingSpecialPermissions() + filter 三次调用 isGranted",
            code.contains("missingSpecialPermissions()")
        )
        assertTrue(source.contains("SpecialPermission.applicable(context, hasOwner)"))
        assertTrue(source.contains("ledger.observe(perm.key, granted)"))
    }

    // ==================== ⑥ 功能：目录驱动 UI / 状态刷新 ====================

    @Test
    fun `授权总览卡片不再写死权限清单`() {
        val screen = readText(
            "src/main/kotlin/com/padguard/child/ui/profile/ProfileScreen.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/ui/profile/ProfileScreen.kt"
        )
        assertFalse(
            "写死一串权限名等于全 App 第 5 份手写清单，目录新增项后界面会说旧名单",
            screen.contains("使用情况、悬浮窗、无障碍、电池优化")
        )
        assertTrue("总览卡片没有读目录化统计", screen.contains("authorizationSummaryText(summary)"))
        assertTrue("总览卡片没有引用 state.authorizationSummary",
            screen.contains("state.authorizationSummary"))
    }

    @Test
    fun `我的页返回前台会刷新设备实时状态`() {
        val vm = readText(
            "src/main/kotlin/com/padguard/child/ui/profile/ProfileViewModel.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/ui/profile/ProfileViewModel.kt"
        )
        assertTrue("缺少 refresh() 入口", vm.contains("fun refresh()"))
        assertTrue("统计不是从目录算的", vm.contains("SpecialPermission.applicable(context"))

        val screen = readText(
            "src/main/kotlin/com/padguard/child/ui/profile/ProfileScreen.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/ui/profile/ProfileScreen.kt"
        )
        assertTrue("页面没有挂 ON_RESUME 刷新", screen.contains("RefreshDeviceStateOnResume(viewModel)"))
        assertTrue(screen.contains("Lifecycle.Event.ON_RESUME"))
    }

    @Test
    fun `总览文案由统计生成 且区分无状态接口的厂商开关`() {
        val done = authorizationSummaryText(
            AuthorizationSummary(total = 7, missingLabels = emptyList())
        )
        assertTrue(done.contains("共 7 项"))
        assertTrue(done.contains("全部已开启"))

        val some = authorizationSummaryText(
            AuthorizationSummary(total = 7, missingLabels = listOf("通知", "悬浮窗"))
        )
        assertTrue(some.contains("仍缺 2 项"))
        assertTrue(some.contains("通知"))
        assertTrue(some.contains("悬浮窗"))

        // 无状态接口的厂商开关是「独立统计」，不进待办清单：
        // 混进 missingLabels 会让 missingCount 恒 ≥1，"全部已开启"永不可达。
        val oem = authorizationSummaryText(
            AuthorizationSummary(
                total = 7,
                missingLabels = emptyList(),
                noStateApiCount = 1
            )
        )
        assertTrue("厂商开关措辞必须与可检测项区分", oem.contains("厂商开关"))
        assertTrue("可检测项全齐要如实说全齐", oem.contains("可检测项已全部开启"))
        assertFalse("不该把无状态接口的项说成普通待办", oem.contains("仍缺 1 项"))
        assertFalse("厂商开关不该被列成待办", oem.contains("待办："))
        assertFalse("没有真实待办时不该再引导一键补齐", oem.contains("一键逐项补齐"))

        // 反向锁死：真有可检测待办时，待办清单与补齐引导必须保留，不能修过头。
        val oemWithPending = authorizationSummaryText(
            AuthorizationSummary(
                total = 7,
                missingLabels = listOf("悬浮窗"),
                noStateApiCount = 1
            )
        )
        assertTrue(oemWithPending.contains("待办：悬浮窗"))
        assertTrue(oemWithPending.contains("一键逐项补齐"))
    }

    @Test
    fun `首页授权统计不把无状态厂商开关算进待办`() {
        val vm = readText(
            "src/main/kotlin/com/padguard/child/ui/profile/ProfileViewModel.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/ui/profile/ProfileViewModel.kt"
        )
        assertTrue(
            "统计必须只把「可检测且确实未授予」的项列为待办",
            vm.contains("perm.detectable && !perm.isGranted(context)")
        )
        assertTrue(
            "无状态项必须由 applicable 独立计数，不能是 missing 的子集",
            vm.contains("perms.count { !it.detectable }")
        )
        assertFalse(
            "旧口径 `else -> true` 会让华为设备 missingLabels 恒含厂商开关",
            vm.contains("if (perm.detectable) !perm.isGranted(context) else true")
        )
    }

    @Test
    fun `统计的派生计数对脏数据下溢有防御`() {
        val dirty = AuthorizationSummary(
            total = 1,
            missingLabels = emptyList(),
            noStateApiCount = 3
        )
        assertEquals(0, dirty.detectableMissingCount)
        assertEquals(0, dirty.missingCount)
        assertEquals(0, AuthorizationSummary().total)
    }

    // ==================== ⑦ 功能：权限告警不误报、恢复即闭环 ====================

    @Test
    fun `无障碍未启用 不再零容忍弹全屏失效警告`() {
        val checker = readCheckerSource()
        assertTrue(
            "国产 ROM 重启瞬间会清空无障碍设置串，未启用必须过容忍窗口才告警",
            checker.contains("if (!a11yDisabledToleranceElapsed())")
        )
        assertTrue(
            "容忍窗口判定必须是可被 JVM 单测锁住的纯函数",
            checker.contains("a11yDisabledConfirmed(now, since, A11Y_DISABLED_TOLERANCE_MS)")
        )
        assertTrue(
            "观察到已启用 / DO 自愈成功必须清窗口，保证下次真掉线拿到完整观察期",
            checker.contains("clearA11yDisabledSince()")
        )
    }

    @Test
    fun `权限恢复时撤销常驻失效引导并复位本地节流窗`() {
        val checker = readCheckerSource()
        val needle = "if (a11yOk) clearA11yRebindGuideIfNeeded()"
        val count = Regex(Regex.escape(needle)).findAll(checker).count()
        assertTrue("启动自检(check)与周期自检(recheckSpecialCapabilities)都要闭环（实际 $count 处）", count >= 2)
        assertTrue(
            "恢复侧必须复位 30 分钟节流时间戳，否则恢复后短期内再掉线反而不提示",
            checker.contains("prefs.edit().remove(KEY_A11Y_GUIDE_LAST).apply()")
        )
        assertTrue(
            "恢复侧必须做边沿判定，避免每轮自检都写盘",
            checker.contains("if (!a11yGuideNotifier.isGuideActive()) return")
        )

        val notifier = readText(
            "src/main/kotlin/com/padguard/child/guard/A11yRebindNotifier.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/guard/A11yRebindNotifier.kt"
        )
        assertTrue("通知侧要暴露活跃态供恢复侧判定", notifier.contains("fun isGuideActive(): Boolean"))
        assertTrue("只有确实 notify 成功才置活跃态", notifier.contains("putBoolean(KEY_GUIDE_ACTIVE, true)"))
        assertTrue("实例 cancel 要清活跃态", notifier.contains("clearActiveFlag()"))
        assertTrue("引导页静态 cancelFrom 也要清活跃态", notifier.contains("edit().remove(KEY_GUIDE_ACTIVE).apply()"))
    }

    @Test
    fun `悬浮窗掉失必须过观察窗，与无障碍同口径`() {
        val checker = readCheckerSource()
        assertTrue(
            "canDrawOverlays 在 ROM 强制重启期间也会短暂返回 false；零容忍会制造一条 6 小时消不掉的高危告警，" +
                "并被 computeRecovered 记成缺失、下一拍再伪造恢复闭环",
            checker.contains("if (!overlayDisabledToleranceElapsed()) return true")
        )
        assertTrue(
            "观察到已授予必须清窗，保证下次真掉线拿到完整观察期",
            checker.contains("clearOverlayDisabledSince()")
        )
        assertTrue(
            "悬浮窗观察窗必须复用通用纯判定，不得与无障碍各自演化出口径差",
            checker.contains("disabledConfirmed(now, since, OVERLAY_DISABLED_TOLERANCE_MS)") &&
                checker.contains("disabledConfirmed(nowMs, sinceMs, toleranceMs)")
        )
        assertTrue("观察窗起点须独立持久化，与 a11y 窗口互不串扰", checker.contains("KEY_OVERLAY_DISABLED_SINCE"))
    }

    @Test
    fun `告警去抖时间戳只在确实上报成功后落盘，失败必须可重试`() {
        val checker = readCheckerSource()
        val stamp = "prefs.edit().putLong(\"alert_\$key\", now).apply()"
        val onSuccessStamp = "}.onSuccess { $stamp }"
        assertEquals(
            "raiseDroppedAlert / reportRuntimeRevocation / reportLockScreenUnprotected 三处，" +
                "不得再有任何一处在上报前无条件写时间戳",
            3, Regex(Regex.escape(stamp)).findAll(checker).count()
        )
        assertEquals(
            "三处时间戳必须全部落在 onSuccess 里：上报失败时下一拍应能重试，而不是静音 6 小时",
            3, Regex(Regex.escape(onSuccessStamp)).findAll(checker).count()
        )
        assertEquals(
            "本地全屏引导节流窗只应有一处写入",
            1, Regex(Regex.escape("prefs.edit().putLong(KEY_A11Y_GUIDE_LAST, now).apply()"))
                .findAll(checker).count()
        )
        assertTrue(
            "post() 会明确返回 false（Android 14 全屏 intent 权限缺失 / notify 失败），" +
                "失败不得把「操作闸门已失效」引导静音 30 分钟",
            checker.contains("if (posted) prefs.edit().putLong(KEY_A11Y_GUIDE_LAST, now).apply()")
        )
    }

    private fun readCheckerSource(): String = readText(
        "src/main/kotlin/com/padguard/child/guard/PermissionHealthChecker.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/guard/PermissionHealthChecker.kt"
    )

    // ==================== ⑧ 功能：运行时权限不绕过「曾授予又丢」纪律 ====================

    @Test
    fun `运行时权限缺失不得再直接算脱管，必须走曾授予账本`() {
        val keeper = readText(
            "src/main/kotlin/com/padguard/child/permission/AuthorizationKeeper.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/permission/AuthorizationKeeper.kt"
        )
        assertFalse(
            "旧写法 addAll(missingRuntimePermissions()) 会让家长主动拒绝的权限永久缺失，" +
                "每轮自检置 reauthRequired、每次打开 App 都被强制拉回授权页",
            keeper.contains("addAll(permissionGranter.missingRuntimePermissions())")
        )
        assertTrue("stillMissing 只收「曾授予又丢」的运行时权限", keeper.contains("addAll(droppedRuntime)"))
        assertTrue(
            "掉失判定必须是可被 JVM 单测锁住的纯函数",
            keeper.contains("internal fun droppedRuntimePermissions(")
        )
        assertTrue(
            "账本必须跨进程/重启持久化，且只在观察到已授予时单向写入",
            keeper.contains("KEY_RUNTIME_EVER_GRANTED") && keeper.contains("putStringSet(KEY_RUNTIME_EVER_GRANTED")
        )
    }

    @Test
    fun `不骚扰的同时不得静默吞掉系统权限缺失，补开页须如实单列`() {
        val vm = readText(
            "src/main/kotlin/com/padguard/child/permission/PermissionGuideViewModel.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/permission/PermissionGuideViewModel.kt"
        )
        assertTrue("补开页要读运行时权限缺失清单", vm.contains("fun missingRuntimeAuthorizations()"))
        assertTrue(
            "缺失清单必须复用 PermissionGranter 的单一口径，不另写一份",
            vm.contains("permissionGranter.missingRuntimePermissions().map(permissionGranter::labelOf)")
        )
        // R34：suspendFor 新增「持有者」参数，不能再按无参精确字符串匹配。
        // 改为锁定 openAppDetails 函数体本身：既要有豁免窗口，也要登记补开页持有者，
        // 否则它离场时会清掉流水线刚开的窗口（设置页被闸门顶回）。
        val appDetailsBody = vm.substringAfter("fun openAppDetails(): Intent {")
            .substringBefore("\n    }")
        assertTrue(
            "入口必须是所有 ROM 恒存在的应用详情页，并带操作闸门豁免与持有者登记",
            appDetailsBody.contains("gateSuspension.suspendFor(") &&
                appDetailsBody.contains("GuardGateSuspension.OWNER_PERMISSION_GUIDE") &&
                appDetailsBody.contains("RomSettings.appDetailsIntent(app)")
        )

        val screen = readText(
            "src/main/kotlin/com/padguard/child/ui/profile/PermissionGuideScreen.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/ui/profile/PermissionGuideScreen.kt"
        )
        assertTrue("总览要展示系统权限单列", screen.contains("vm.missingRuntimeAuthorizations()"))
        assertTrue("单列不得驱动「一键补齐」自动接续", screen.contains("前往应用详情页开启系统权限"))
        assertFalse(
            "不得把运行时权限塞进特殊权限目录口径（会破坏 includeUndetectable 契约）",
            screen.contains("missingRuntime.isNotEmpty() -> vm.startConsentReview()")
        )
    }

    @Test
    fun `我的页 combine 首帧与 ON_RESUME 必须共用同一份实时字段集合`() {
        val vm = readText(
            "src/main/kotlin/com/padguard/child/ui/profile/ProfileViewModel.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/ui/profile/ProfileViewModel.kt"
        )
        assertTrue(
            "combine 首帧必须走 readRealtimeState().applyTo，不得再逐项手写实时字段",
            vm.contains("readRealtimeState().applyTo(")
        )
        // 本仓源码是 CRLF 行尾，而 Kotlin File.readText() 不像 Python 那样做 universal-newline
        // 转换，含 \n 的跨行字面串永远匹配不上。刻意改成提取函数体后精确相等，不依赖换行符。
        val refreshBody = vm.substringAfter("fun refresh() {")
            .substringBefore("}")
            .trim()
        assertEquals(
            "refresh() 必须整体委托给同一份实时字段集合；旧写法手写 copy 且漏了闸门横幅两项，" +
                "导致家长重开无障碍返回后横幅仍挂着失效警告",
            "_state.value = readRealtimeState().applyTo(_state.value)",
            refreshBody
        )
        assertTrue(
            "实时字段清单必须单点定义在 RealtimeState.applyTo，且包含两条闸门横幅",
            vm.contains("fun applyTo(state: ProfileUiState): ProfileUiState = state.copy(") &&
                vm.contains("guardInfraWarning = guardInfraWarning") &&
                vm.contains("guardInfraDetached = guardInfraDetached")
        )
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
        throw AssertionError("找不到源码文件：${candidates.joinToString()}；cwd=$cwd")
    }
}
