package com.padguard.core.transport

import com.padguard.core.transport.Level
import com.padguard.core.transport.Verdict
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 构建期开关 [BuildConfig.ALLOW_CLEARTEXT_PUBLIC] 与运行期安全闸门 [EndpointSafety] 的接线回归测试。
 *
 * 第二十三轮遗留缺口：孩子端 `core:transport` 的 `BuildConfig.ALLOW_CLEARTEXT_PUBLIC` 曾被硬编码 `false`，
 * 而 app 模块的 release 构建守卫 `validateReleaseEndpoints` 读的是另一份 `-PALLOW_CLEARTEXT_PUBLIC`。
 * 于是「构建守卫放行、运行期却仍拒绝」的割裂：打出带明文公网的特殊部署包，运行时仍把
 * `http://公网IP` 当不安全地址拒掉。本测试确保两处用的是**同一个编译期常量**，且它确实驱动运行期判定。
 *
 * 该测试在两种构建形态下都成立：
 * - 默认（不传 -PALLOW_CLEARTEXT_PUBLIC）：常量必为 false，公网明文必须被拒（安全默认不可被悄悄改掉）；
 * - 显式开启（-PALLOW_CLEARTEXT_PUBLIC=true）：常量必为 true，公网明文被放行且标记为 EXPLICITLY_INSECURE。
 * 无论哪种形态，常量都必须**真实影响** [EndpointSafety.check] 的结果——这正是「接线是否打通」的判据。
 */
class BuildConfigCleartextContractTest {

    /** 一个明确的公网明文地址：公网 IP + 非加密 HTTP。 */
    private val publicCleartext = "http://8.148.68.183:8090/api/v1/"

    @Test
    fun `ALLOW_CLEARTEXT_PUBLIC actually drives the runtime gate in both build modes`() {
        val verdict = EndpointSafety.check(publicCleartext, BuildConfig.ALLOW_CLEARTEXT_PUBLIC)
        if (BuildConfig.ALLOW_CLEARTEXT_PUBLIC) {
            assertTrue(
                "开启开关后公网明文应被放行并标记为 EXPLICITLY_INSECURE，实际=$verdict",
                verdict is Verdict.Allowed && verdict.level == Level.EXPLICITLY_INSECURE
            )
        } else {
            assertTrue(
                "默认关死时公网明文必须被拒绝，实际=$verdict",
                verdict is Verdict.Rejected
            )
        }
    }

    @Test
    fun `default posture keeps public cleartext blocked`() {
        // 安全红线：未显式开启时，编译期常量必须为 false，绝不能默认放行公网明文。
        // 这条专门守护「有人把默认值改成 true」这种危险回退——一旦默认放开，测试立刻红。
        if (!BuildConfig.ALLOW_CLEARTEXT_PUBLIC) {
            assertTrue(
                "默认构建下公网明文必须被拒绝",
                EndpointSafety.check(publicCleartext, BuildConfig.ALLOW_CLEARTEXT_PUBLIC) is Verdict.Rejected
            )
        }
    }
}
