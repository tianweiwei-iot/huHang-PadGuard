package com.padguard.child.agreement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 协议「暂不同意」的会话级状态。
 *
 * ## 为什么是进程内状态而不是持久化
 * 协议同意与否必须持久化（[com.padguard.core.data.repository.AuthRepository.isAgreementAccepted]），
 * 因为它决定一次装机是否获得高敏授权；但「本次拒绝」恰恰**不能**持久化：
 * 一旦写进 DataStore，拒绝就变成永久事实，孩子端再也拿不到下一次征求同意的机会，
 * 管控能力会静默缺失且无从恢复——这是比弹窗更隐蔽的故障。
 *
 * 进程内状态天然满足注释里描述的语义：本此运行期内不再打扰，冷启动后重新征求。
 *
 * ## 为什么必须是 @Singleton 而不是 ViewModel 字段
 * 弹窗宿主 [AgreementDialogHost] 由 Activity 承载，`hiltViewModel()` 取到的是 Activity 级实例。
 * 若把标记放在 ViewModel 里，一次屏幕旋转导致 Activity 重建就会丢失状态，
 * 弹窗立刻重新弹出——「暂不同意」等于只顶用一次，仍然不满足"本次运行期不再打扰"的承诺。
 */
@Singleton
class AgreementSessionState @Inject constructor() {

    private val _declined = MutableStateFlow(false)
    val declined: StateFlow<Boolean> = _declined.asStateFlow()

    /** 记录本次拒绝：弹窗收起，进入受限预览模式，本次进程内不再重复征求。 */
    fun markDeclined() {
        _declined.value = true
    }

    /**
     * 清除拒绝标记。
     * 仅在成功同意后调用——同意后 [AgreementDialogHost] 的挂载条件已由持久化同意位短路，
     * 本标记不再参与判定，清零可避免后续同版本内的逻辑被脏状态干扰。
     */
    fun clear() {
        _declined.value = false
    }
}
