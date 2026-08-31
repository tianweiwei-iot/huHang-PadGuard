package com.padguard.child.ui.unlock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.core.data.repository.UnlockRequest
import com.padguard.core.data.repository.UnlockRequestRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 临时解锁申请页 ViewModel。
 *
 * 三段状态合流：
 * 1. 表单本地状态（时长、理由、目标应用）—— 纯 UI 态，用 [formState] 单独持有；
 * 2. 是否有在途申请 —— 决定提交按钮是否可用，防止孩子连点刷屏家长；
 * 3. 申请历史 —— 让孩子能看到"申请中 / 已同意 / 已拒绝"，否则会重复提交。
 *
 * 进页面先跑一次 [UnlockRequestRepository.expireStale]：
 * 家长长时间不处理时，旧的 PENDING 会一直堵着提交按钮，
 * 必须在用户真正看到这个页面的时刻把过期申请清掉，而不是等下一次心跳。
 */
@HiltViewModel
class UnlockRequestViewModel @Inject constructor(
    private val repository: UnlockRequestRepository
) : ViewModel() {

    private val formState = MutableStateFlow(FormState())

    val state: StateFlow<UnlockRequestUiState> = combine(
        formState,
        repository.hasPendingRequest(),
        repository.observeRecent(HISTORY_LIMIT)
    ) { form, hasPending, history ->
        UnlockRequestUiState(
            targetPackage = form.targetPackage,
            targetLabel = form.targetLabel,
            durationMinutes = form.durationMinutes,
            reasonText = form.reasonText,
            submitting = form.submitting,
            justSubmitted = form.justSubmitted,
            hasPending = hasPending,
            history = history.map { it.toUi() }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UnlockRequestUiState()
    )

    init {
        viewModelScope.launch { repository.expireStale() }
    }

    /**
     * 设置申请对象。由拦截页带包名进入时调用；从首页进入时不调用，保持"整机"默认值。
     *
     * 只在包名真的变化时写状态，避免 Compose 重组反复触发 setTarget 导致
     * 用户已填的理由被"当作新目标"清空。
     */
    fun setTarget(packageName: String, appLabel: String) {
        if (packageName.isBlank()) return
        if (formState.value.targetPackage == packageName) return
        formState.value = formState.value.copy(
            targetPackage = packageName,
            targetLabel = appLabel.ifBlank { packageName }
        )
    }

    fun setDuration(minutes: Int) {
        formState.value = formState.value.copy(
            durationMinutes = minutes.coerceIn(
                UnlockRequestRepository.MIN_MINUTES,
                UnlockRequestRepository.MAX_MINUTES
            )
        )
    }

    fun setReason(text: String) {
        formState.value = formState.value.copy(reasonText = text.take(MAX_REASON_INPUT))
    }

    /**
     * 提交申请。
     *
     * 三道拦：正在提交中、已有在途申请、理由为空 —— 任一命中直接返回。
     * 理由必填是有意的：没有理由的申请家长无法判断，只会被无脑拒绝，
     * 反而让这个功能失去意义。
     */
    fun submit() {
        val form = formState.value
        if (form.submitting) return
        if (form.reasonText.isBlank()) return
        if (state.value.hasPending) return

        formState.value = form.copy(submitting = true)
        viewModelScope.launch {
            runCatching {
                repository.submit(
                    packageName = form.targetPackage,
                    appLabel = form.targetLabel,
                    reasonText = form.reasonText,
                    durationMinutes = form.durationMinutes
                )
            }
            // 提交完成后清空理由并标记"刚提交"，让界面切到等待审批的说明
            formState.value = formState.value.copy(
                submitting = false,
                justSubmitted = true,
                reasonText = ""
            )
        }
    }

    /** 用户继续操作后收起"已提交"提示 */
    fun dismissSubmitted() {
        formState.value = formState.value.copy(justSubmitted = false)
    }

    private fun UnlockRequest.toUi() = UnlockRequestItemUi(
        requestId = requestId,
        targetLabel = appLabel.ifBlank { "整机使用时间" },
        durationLabel = "${durationMinutes}分钟",
        reasonText = reasonText,
        timeLabel = TIME_FORMAT.format(Date(createdAt)),
        status = status
    )

    private data class FormState(
        val targetPackage: String = "",
        val targetLabel: String = "整机使用时间",
        val durationMinutes: Int = 30,
        val reasonText: String = "",
        val submitting: Boolean = false,
        val justSubmitted: Boolean = false
    )

    companion object {
        private const val HISTORY_LIMIT = 20
        private const val MAX_REASON_INPUT = 200

        /** 可选时长档位：覆盖"查个资料"到"看完一节网课"的常见跨度 */
        val DURATION_OPTIONS = listOf(15, 30, 60)

        /** 常用理由，点一下即填，降低孩子的输入成本（尤其是小学阶段） */
        val QUICK_REASONS = listOf("要查学习资料", "老师布置的作业", "需要联系家长", "上网课")

        private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }
}

data class UnlockRequestUiState(
    val targetPackage: String = "",
    val targetLabel: String = "整机使用时间",
    val durationMinutes: Int = 30,
    val reasonText: String = "",
    val submitting: Boolean = false,
    val justSubmitted: Boolean = false,
    /** 有在途申请时禁用提交，避免重复打扰家长 */
    val hasPending: Boolean = false,
    val history: List<UnlockRequestItemUi> = emptyList()
) {
    val canSubmit: Boolean get() = !submitting && !hasPending && reasonText.isNotBlank()
}

data class UnlockRequestItemUi(
    val requestId: String,
    val targetLabel: String,
    val durationLabel: String,
    val reasonText: String,
    val timeLabel: String,
    val status: UnlockRequest.Status
)
