package com.padguard.parent.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.data.model.AppealDto
import com.padguard.parent.data.model.DataExportDto
import com.padguard.parent.data.model.DeletionRequestDto
import com.padguard.parent.data.model.GuardianConsentStatusDto
import com.padguard.parent.data.remote.StaticConfigCache
import com.padguard.parent.data.remote.readableActionMessage
import com.padguard.parent.di.PrivacyConsentPrefs
import com.padguard.parent.domain.repository.ComplianceRepository
import com.squareup.moshi.Moshi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 数据权利与合规页状态。
 *
 * 一次性提示（成功/失败）沿用全项目约定：作为 state 字段由 UI 的 Snackbar 消费后清空，
 * 不引入 SharedFlow/Channel（家长端现有 VM 均无事件流）。
 */
data class DataRightsUiState(
    val loading: Boolean = false,
    val export: DataExportDto? = null,
    val appeals: List<AppealDto> = emptyList(),
    /** 提交中的操作标识（防重复点击）；取值 withdraw/reinstate/delete/appeal。 */
    val acting: String? = null,
    /** 注销请求返回凭证：冷静期内可确认或取消，非空时展示凭证卡片。 */
    val deletionRequest: DeletionRequestDto? = null,
    /** 当前查看设备的「不满14周岁监护人单独同意」状态。 */
    val guardianConsent: GuardianConsentStatusDto? = null,
    val message: String? = null,
    val error: String? = null
)

/**
 * 数据主体权利 ViewModel：导出 / 撤回·恢复同意 / 账号注销 / 申诉。
 *
 * 服务端 ParentDataRightsController 端点早已就绪，此前家长端零接线——用户无法实际
 * 行使《个人信息保护法》赋予的查阅复制、撤回同意、删除、申诉权利。本 VM 把四组端点
 * 接到界面，所有操作结果落 state 的 message/error 由 Snackbar 呈现。
 *
 * 危险操作（注销确认）的二次确认由 UI 层的 AlertDialog 承担，本 VM 只负责调用与结果。
 */
@HiltViewModel
class DataRightsViewModel @Inject constructor(
    private val complianceRepository: ComplianceRepository,
    private val tokenManager: com.padguard.parent.data.auth.TokenManager,
    private val accountPrefs: com.padguard.parent.di.AccountPrefs,
    private val consentPrefs: PrivacyConsentPrefs,
    private val staticConfigCache: StaticConfigCache,
    private val application: Application,
    private val moshi: Moshi
) : ViewModel() {

    private val _uiState = MutableStateFlow(DataRightsUiState())
    val uiState: StateFlow<DataRightsUiState> = _uiState.asStateFlow()

    /** 进入页面 / 下拉刷新：拉取导出快照与申诉列表。二者独立失败不互相拖累。 */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            val export = complianceRepository.exportData()
            val appeals = complianceRepository.listAppeals()
            val exportData = export.getOrNull()
            // 冷静期注销凭证从导出快照回填：家长杀进程重进后仍能看到"待确认/可取消"的注销单，
            // 否则凭证只存在内存里，重进即消失，家长无法在冷静期内确认或取消。
            val pendingDeletion = exportData?.deletionRequests
                ?.firstOrNull { it.status.equals("PENDING", ignoreCase = true) }
                ?.let {
                    DeletionRequestDto(
                        ticket = it.ticket, status = it.status,
                        requestedAt = it.requestedAt, executeAt = it.executeAt
                    )
                }
            _uiState.value = _uiState.value.copy(
                loading = false,
                export = exportData,
                appeals = appeals.getOrNull().orEmpty(),
                deletionRequest = pendingDeletion ?: _uiState.value.deletionRequest,
                error = listOfNotNull(export.exceptionOrNull(), appeals.exceptionOrNull())
                    .firstOrNull()?.readableActionMessage("加载数据失败，请稍后重试")
            )
        }
    }

    /**
     * 把当前导出快照序列化为 JSON 文本，供 UI 写入用户选择的文件。
     *
     * PIPL 第 45 条要求可「查阅、复制」：仅在屏幕上显示条数摘要不算复制权落地，
     * 必须让家长拿到可离线保存、可转交的一份完整数据。序列化放在 VM 而非 Composable，
     * 复用 DI 的 Moshi 实例（家长端无 Gson），Dtos.kt 中所有导出 DTO 均带 @JsonClass。
     */
    fun exportJson(): String? {
        val snapshot = _uiState.value.export ?: return null
        return moshi.adapter(DataExportDto::class.java).toJson(snapshot)
    }

    /** 撤回采集授权。deviceIds 为空 = 名下全部设备。 */
    fun withdrawConsent(deviceIds: List<String>? = null) = act("withdraw", "已撤回采集授权") {
        complianceRepository.withdrawConsent(deviceIds).map { Unit }
    }

    /** 恢复采集授权。 */
    fun reinstateConsent(deviceIds: List<String>? = null) = act("reinstate", "已恢复采集授权") {
        complianceRepository.reinstateConsent(deviceIds).map { Unit }
    }

    /**
     * 撤回**应用级**《隐私政策》同意（PIPL 第 15 条「撤回同意」）。
     *
     * ## 与上面 [withdrawConsent] 的区别（两者不能合并）
     * - [withdrawConsent] 是**设备采集范围**的授权撤回：服务端按 deviceId 停落库、下发策略包让
     *   孩子端停止本地采集。家长仍在正常使用 App 管理其他设备，不应被踢下线。
     * - 本方法撤回的是**使用本 App 本身的法律依据**：留痕清除 + 高德 SDK 状态置 false +
     *   结束会话。此后 App 不再具备处理个人信息的合法基础，必须退回同意闸门，
     *   否则就是"界面说不处理、后台照旧上报"的假撤回。
     *
     * 因此这里**必须**连带清会话：`PadGuardNavigation` 以 `user != null && consentAgreed`
     * 决定是否进主页，清 token 后导航必然退回登录页；原因经 `sessionNotice` 显示在登录页横幅，
     * 避免用户只看到"被扔回登录页"却不知为何（与令牌失效链路同一出口）。
     */
    fun revokePolicyConsent() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(acting = "revoke-policy", error = null)
            try {
                // revoke() 内部已同步把高德 SDK 置回未同意，二者必须同拍（见 PrivacyConsentPrefs）。
                consentPrefs.revoke()
                tokenManager.clearWithReason("已撤回《隐私政策》同意，需重新阅读并勾选后才能继续使用")
                _uiState.value = _uiState.value.copy(acting = null, message = "已撤回隐私政策同意")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // 留痕已清、会话未清：导航层仍会因 consentAgreed=false 退回登录页，
                // 所以这里只提示错误、不谎报成功，也不把 acting 卡死。
                _uiState.value = _uiState.value.copy(
                    acting = null,
                    error = "已清除同意留痕，但退出登录失败：" + t.readableActionMessage("请手动退出后重试")
                )
            }
        }
    }

    /** 回显某台设备是否已提交监护人单独同意。 */
    fun loadGuardianConsent(deviceId: String) {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            val result = complianceRepository.guardianConsentStatus(deviceId)
            result.onSuccess { _uiState.value = _uiState.value.copy(guardianConsent = it) }
        }
    }

    /**
     * 提交不满十四周岁监护人单独同意。consent=true 由 UI 勾选框固定，服务端只接受 <14 周岁；
     * 文案版本随客户端版本写死，保证事后能还原家长当时看到的表述。
     */
    fun submitUnder14GuardianConsent(deviceId: String, childBirthDate: String, consentTextVersion: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(acting = "guardian-consent", error = null)
            val outcome = try {
                complianceRepository.submitUnder14GuardianConsent(deviceId, childBirthDate, consentTextVersion)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Result.failure(t)
            }
            outcome.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        acting = null, guardianConsent = it,
                        message = "已记录监护人单独同意（按申报出生日期判定，未做实名核验）"
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        acting = null,
                        error = "提交失败：" + e.readableActionMessage("请检查出生日期与网络后重试")
                    )
                }
            )
        }
    }

    /** 提交注销请求（进入冷静期，返回凭证）。 */
    fun requestDeletion(reason: String?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(acting = "delete", error = null)
            val result = complianceRepository.requestDeletion(reason)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        acting = null,
                        deletionRequest = it,
                        message = "已提交注销申请，冷静期内可取消；到期后数据将被清除"
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        acting = null,
                        error = "提交失败：" + e.readableActionMessage("请检查网络后重试")
                    )
                }
            )
        }
    }

    /**
     * 凭证确认注销（不可撤销，UI 必须先二次确认）。
     *
     * 服务端匿名化账号后，本机必须同步清除会话与账号历史：否则账号已注销、App 却仍持有
     * JWT 与手机号历史、停留在"已登录"态——既与注销结果矛盾，也不满足 PIPL 删除权
     * （本地个人信息同样要清除）。token 清空后导航层监听 SessionViewModel.user 自动退回登录页。
     * 注意：账号历史按手机号清理，注销请求中的手机号来自当前会话（此时尚未清 token）。
     */
    fun confirmDeletion(ticket: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(acting = "delete", error = null)
            val outcome = try {
                complianceRepository.confirmDeletion(ticket)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Result.failure(t)
            }
            val failure = outcome.exceptionOrNull()
            if (failure != null) {
                _uiState.value = _uiState.value.copy(
                    acting = null,
                    error = "操作失败：" + failure.readableActionMessage("请检查网络后重试")
                )
                return@launch
            }
            // 先记下手机号再清 token：清完后 currentUserFlow 立刻变 null，再读就晚了。
            // 导出快照优先（refresh 已加载），网络异常导致快照缺失时退回当前会话用户，
            // 否则「导出没加载成功」会连带让本地账号历史清不掉。
            val phone = _uiState.value.export?.account?.phone
                ?: tokenManager.currentUserFlow().first()?.phone?.takeIf { it.isNotBlank() }
            phone?.let { accountPrefs.remove(it) }
            // 清理顺序不能反：clearWithReason/clear 会让导航立刻退回登录页，当前页的
            // back stack entry 被弹出即取消 viewModelScope —— 放在清 token 之后的清理
            // 协程会被直接掐断，表现为"服务端删了、端上截图缓存还在"（PIPL 删除权未落地）。
            clearLocalPersonalData()
            tokenManager.clear()
            _uiState.value = _uiState.value.copy(acting = null, message = "账号已注销")
        }
    }

    /**
     * 清除注销后残留在本机的个人信息副本。
     *
     * 服务端匿名化 + 物理删除业务数据只覆盖服务端；端上还有两类副本：
     * ① Coil 磁盘/内存缓存里的孩子截图与头像（服务端已删，本地仍可打开 = 删除权未落实）；
     * ② 分龄默认值等按租户下发的静态配置（进程内缓存，同一进程再登录别的账号会读到旧租户值）。
     *
     * **不清 `ServerPrefs`**：服务器地址是部署配置而非个人信息，清掉会把自建家庭服务器的家长
     * 弄成"注销后连不上、又没有网络入口去改地址"的死锁；也不清 `PrivacyConsentPrefs`
     * （协议版本留痕不是个人信息，保留可避免新注册被重复弹同意）。
     */
    private suspend fun clearLocalPersonalData() {
        staticConfigCache.invalidate()
        withContext(Dispatchers.IO) {
            // 缓存清理失败不应阻断注销（服务端数据已删）；失败留给系统磁盘配额自然回收。
            runCatching {
                val loader = coil.Coil.imageLoader(application)
                // Coil 2.7 把 ImageLoader.diskCache 标为实验 API：这里只用 clear()，
                // 与其版本演进无关，显式 opt-in 而不是退回 @Suppress 掩盖其他警告。
                @OptIn(coil.annotation.ExperimentalCoilApi::class)
                run {
                    loader.memoryCache?.clear()
                    loader.diskCache?.clear()
                }
            }
        }
    }

    /** 冷静期内取消注销。 */
    fun cancelDeletion(ticket: String) = act("delete", "已取消注销申请") {
        complianceRepository.cancelDeletion(ticket).map {
            // 取消成功后清掉凭证卡片，避免继续展示可确认的旧 ticket。
            _uiState.value = _uiState.value.copy(deletionRequest = null)
        }
    }

    /** 提交申诉。 */
    fun submitAppeal(deviceId: String?, subject: String, detail: String?, contact: String?) =
        act("appeal", "申诉已提交，我们会尽快处理") {
            complianceRepository.submitAppeal(deviceId, subject, detail, contact).map { Unit }
        }

    /**
     * 统一的写操作入口：设置 acting 防重入 → 执行 → 成功写 message / 失败写 error。
     * CancellationException 原样放开（退出页面 / 取消协程不算业务失败）。
     */
    private fun act(key: String, successMessage: String, block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(acting = key, error = null)
            val outcome = try {
                block()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Result.failure(t)
            }
            outcome.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(acting = null, message = successMessage)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        acting = null,
                        error = "操作失败：" + e.readableActionMessage("请检查网络后重试")
                    )
                }
            )
        }
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null, error = null)
    }
}
