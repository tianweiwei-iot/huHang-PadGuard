package com.padguard.parent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 数据权利客户端接线的回归门禁。
 *
 * 背景：服务端 ParentDataRightsController 的导出/撤回/注销/申诉端点早已实现，
 * 但家长端长期零接线——用户在法律意义上无法行使《个人信息保护法》赋予的权利。
 * 这类"服务端有、客户端断"的缺口单看任何一端都发现不了，必须跨文件钉住整条链：
 *   API 声明 → DataSource 实现 → DI 绑定 → VM → Screen → 导航路由 → 设置入口。
 * 任一环被人删掉（如清理"看起来没用"的 DTO），这里立即失败。
 */
class DataRightsWiringContractTest {

    @Test
    fun `api declares all five data rights endpoints`() {
        val api = read("护航控制端/app/src/main/java/com/padguard/parent/data/api/ComplianceApi.kt")
        assertTrue(api.contains("compliance/consent/withdraw"))
        assertTrue(api.contains("compliance/consent/reinstate"))
        assertTrue(api.contains("compliance/export"))
        assertTrue(api.contains("compliance/account/deletion/request"))
        assertTrue(api.contains("compliance/account/deletion/confirm"))
        assertTrue(api.contains("compliance/account/deletion/cancel"))
        assertTrue(api.contains("compliance/appeals"))
        // 空响应端点必须用 Moshi 可解析的 ApiResponse<Unit>，不能误用 Gson（项目无 Gson 依赖）
        assertTrue(!api.contains("com.google.gson"))
    }

    @Test
    fun `datasource implements compliance repository with exec`() {
        val ds = read("护航控制端/app/src/main/java/com/padguard/parent/data/remote/ApiDataSource.kt")
        assertTrue("ApiDataSource 必须实现 ComplianceRepository", "BillingRepository, ComplianceRepository" in ds)
        assertTrue("必须注入 ComplianceApi", "complianceApi: ComplianceApi" in ds)
        for (method in listOf(
            "override suspend fun withdrawConsent", "override suspend fun reinstateConsent",
            "override suspend fun exportData", "override suspend fun requestDeletion",
            "override suspend fun confirmDeletion", "override suspend fun cancelDeletion",
            "override suspend fun submitAppeal", "override suspend fun listAppeals"
        )) {
            assertTrue("ApiDataSource 缺少合规实现：$method", ds.contains(method))
        }
    }

    @Test
    fun `di binds compliance api and repository`() {
        val network = read("护航控制端/app/src/main/java/com/padguard/parent/di/NetworkModule.kt")
        assertTrue("NetworkModule 必须 create ComplianceApi", "retrofit.create(ComplianceApi::class.java)" in network)
        val repo = read("护航控制端/app/src/main/java/com/padguard/parent/di/RepositoryModule.kt")
        assertTrue("RepositoryModule 必须绑定 ComplianceRepository", "provideComplianceRepository" in repo)
    }

    @Test
    fun `viewmodel wires all rights actions`() {
        val vm = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/viewmodel/DataRightsViewModel.kt")
        for (action in listOf("refresh", "withdrawConsent", "reinstateConsent", "requestDeletion",
            "confirmDeletion", "cancelDeletion", "submitAppeal")) {
            assertTrue("DataRightsViewModel 缺少动作：$action", vm.contains("fun $action"))
        }
        // 全局契约：任何 catch(Throwable) 都必须放行 CancellationException
        assertTrue(
            "合规 VM 的写操作入口必须放行协程取消",
            vm.contains("if (t is CancellationException) throw t")
        )
    }

    @Test
    fun `screen and navigation expose data rights route and entry`() {
        val screen = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/profile/DataRightsScreen.kt")
        assertTrue("缺少合规 UI", "fun DataRightsScreen" in screen)

        val nav = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/PadGuardNavigation.kt")
        assertTrue("缺少路由常量", "object DataRights : Screen(\"data_rights\")" in nav)
        assertTrue("缺少 NavHost 注册", "composable(Screen.DataRights.route)" in nav)

        val profile = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/profile/ProfileScreen.kt")
        assertTrue("缺少设置入口", "data object DataRights : ProfileAction" in profile)
        assertTrue("缺少列表项", "数据权利与合规" in profile)

        val main = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/main/MainScreen.kt")
        assertTrue("缺少入口分发", "ProfileAction.DataRights ->" in main)
    }

    @Test
    fun `export dto and ui carry personal data details`() {
        val dtos = read("护航控制端/app/src/main/java/com/padguard/parent/data/model/Dtos.kt")
        for (field in listOf("detailLimitPerDevice", "usageLogs", "locationTracks", "screenshots")) {
            assertTrue("导出 DTO 缺少明细字段：$field", dtos.contains("val $field"))
        }
        val screen = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/profile/DataRightsScreen.kt")
        assertTrue("导出摘要必须展示用量/位置/截图条数", "export.usageLogs.size" in screen)
        assertTrue("导出说明必须写明本地停采链路", "策略包" in screen)
    }

    @Test
    fun `deletion confirmation clears local session and account history`() {
        val vm = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/viewmodel/DataRightsViewModel.kt")
        assertTrue("注销成功后必须清空 JWT", "tokenManager.clear()" in vm)
        assertTrue("注销成功后必须清掉本地账号历史", "accountPrefs.remove(it)" in vm)
        assertTrue(
            "导出快照未加载时须从当前会话取手机号，否则账号历史清不掉",
            "tokenManager.currentUserFlow().first()?.phone" in vm
        )
        val failureCheck = vm.indexOf("val failure = outcome.exceptionOrNull()")
        val earlyReturn = vm.indexOf("return@launch", failureCheck)
        val sessionClear = vm.indexOf("tokenManager.clear()")
        assertTrue("失败分支必须先 return，不能误清会话", failureCheck >= 0 && earlyReturn > failureCheck)
        assertTrue("清会话必须在失败分支之后（且不能在非挂起 fold 回调里）", sessionClear > earlyReturn)
    }

    /**
     * 服务端删除 ≠ 端上删除。
     *
     * 注销只做 `tokenManager.clear()` 时，孩子截图（Coil 磁盘缓存）与按租户下发的静态配置
     * 仍留在本机——账号已注销却还能在本地翻到孩子的画面，属于 PIPL 删除权未落实。
     * 同时清理顺序也被钉住：清 token 会让导航弹栈、取消 viewModelScope，
     * 放在其后的清理协程会被掐断。
     */
    @Test
    fun `deletion also purges local personal data copies`() {
        val vm = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/viewmodel/DataRightsViewModel.kt")
        assertTrue("注销后必须清图片磁盘缓存（孩子截图本地副本）", "diskCache?.clear()" in vm)
        assertTrue("注销后必须清图片内存缓存", "memoryCache?.clear()" in vm)
        assertTrue("注销后必须失效按租户下发的静态配置", "staticConfigCache.invalidate()" in vm)
        assertTrue("缓存清理必须在 IO 线程执行，不能阻塞主线程", "withContext(Dispatchers.IO)" in vm)

        val cleanupCall = vm.indexOf("clearLocalPersonalData()")
        val sessionClear = vm.indexOf("tokenManager.clear()")
        assertTrue(
            "本地清理必须在清 token 之前：清 token 会弹栈取消 viewModelScope，之后的清理被吞掉",
            cleanupCall >= 0 && sessionClear > cleanupCall
        )
        assertTrue(
            "服务器地址属部署配置，注销后保留（清掉会让自建服务器用户无法再改回地址）",
            "resetToDefault()" !in vm
        )
    }

    /**
     * 应用级「撤回隐私政策同意」必须真实生效，而不是只改 UI。
     *
     * 设备采集授权撤回（withdrawConsent）与政策同意撤回（revokePolicyConsent）是两件事：
     * 前者是采集范围，家长仍要管理其他设备；后者是处理个人信息的合法基础，撤回后必须退回同意闸门。
     * 二者混用会造成"撤回了设备采集却被强制退出登录"或"撤回了政策却照旧处理数据"。
     */
    @Test
    fun `policy consent revocation clears trace and forces re-consent`() {
        val vm = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/viewmodel/DataRightsViewModel.kt")
        assertTrue("VM 必须提供政策同意撤回入口", "fun revokePolicyConsent()" in vm)
        assertTrue("撤回必须清除本地同意留痕与 SDK 状态", "consentPrefs.revoke()" in vm)
        assertTrue(
            "撤回后必须结束会话退回同意闸门，并带原因",
            "tokenManager.clearWithReason(" in vm
        )
        // 设备采集撤回不得被连带清会话（否则家长撤回采集就被踢下线）
        val withdrawBlock = vm.substringAfter("fun withdrawConsent")
            .substringBefore("fun reinstateConsent")
        assertTrue("撤回设备采集不得清会话", "tokenManager" !in withdrawBlock)

        val screen = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/profile/DataRightsScreen.kt")
        assertTrue("UI 缺少政策同意撤回入口", "撤回隐私政策同意" in screen)
        assertTrue("危险操作必须二次确认", "showRevokePolicyDialog" in screen)
        assertTrue("入口必须调用 VM 的撤回方法", "viewModel.revokePolicyConsent()" in screen)
        assertTrue(
            "界面展示的协议版本必须取自常量，不能硬编码（法务文本一升级就漂移）",
            "PrivacyConsentPrefs.CURRENT_POLICY_VERSION" in screen &&
                "PrivacyConsentPrefs.CURRENT_TERMS_VERSION" in screen
        )
    }

    @Test
    fun `data export writes a real file instead of a toast summary`() {
        val vm = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/viewmodel/DataRightsViewModel.kt")
        assertTrue("VM 必须提供可序列化的导出文本", "fun exportJson()" in vm)
        assertTrue("必须复用 DI 的 Moshi 而非 Gson", "moshi.adapter(DataExportDto::class.java)" in vm)
        assertTrue("家长端不得引入 Gson", "com.google.gson" !in vm)

        val screen = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/profile/DataRightsScreen.kt")
        assertTrue("必须用系统文件选择器写文件，不能申请存储权限",
            "ActivityResultContracts.CreateDocument(\"application/json\")" in screen)
        assertTrue("必须真正打开输出流写盘", "openOutputStream(uri)" in screen)
        assertTrue("必须写入完整 JSON（不是只看摘要）",
            "out.write(json.toByteArray(Charsets.UTF_8))" in screen)
        assertTrue("必须提示保存结果", "个人数据已保存到所选文件" in screen)
        assertTrue("不得再用 Toast 摘要冒充导出功能", "查看导出摘要" !in screen)
    }

    @Test
    fun `under14 guardian consent is wired end to end`() {
        val api = read("护航控制端/app/src/main/java/com/padguard/parent/data/api/ComplianceApi.kt")
        assertTrue("缺少单独同意提交端点", "compliance/guardian-consent/under14" in api)
        assertTrue("单独同意必须带设备路径参数", "@Path(\"deviceId\")" in api)

        val ds = read("护航控制端/app/src/main/java/com/padguard/parent/data/remote/ApiDataSource.kt")
        assertTrue("DataSource 缺少单独同意实现", "override suspend fun submitUnder14GuardianConsent" in ds)
        assertTrue("DataSource 缺少状态回显", "override suspend fun guardianConsentStatus" in ds)

        val vm = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/viewmodel/DataRightsViewModel.kt")
        assertTrue("VM 缺少单独同意提交", "fun submitUnder14GuardianConsent" in vm)
        assertTrue("VM 缺少状态加载", "fun loadGuardianConsent" in vm)

        val screen = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/profile/DataRightsScreen.kt")
        assertTrue("UI 缺少单独同意卡片", "不满十四周岁监护人单独同意" in screen)
        assertTrue("UI 必须声明这不是实名核验", "不是实名年龄核验" in screen)

        val controller = read("padguard-server/src/main/kotlin/com/padguard/server/controller/ParentDataRightsController.kt")
        assertTrue("服务端缺少单独同意端点", "compliance/guardian-consent/under14" in controller)
    }

    private fun read(relative: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir: File? = cwd
        repeat(6) {
            if (dir == null) return@repeat
            val candidate = File(dir!!, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir!!.parentFile
        }
        throw AssertionError("找不到源码文件：$relative；cwd=$cwd")
    }
}
