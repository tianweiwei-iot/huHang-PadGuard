package com.padguard.server.security

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Device
import com.padguard.server.repository.DeviceRepository
import org.springframework.stereotype.Component

/**
 * 设备归属校验的唯一入口。
 *
 * ## 为什么必须收敛为一个组件
 * 早期各业务 Service 各自复制了一份私有 `requireOwned`（最多时 8 份），实现细节靠
 * 人肉保持一致 —— GeofenceService 正是"漏抄了一份"的产物：位置/轨迹/围栏全线无归属
 * 校验，任何登录家长可读取任意设备的实时位置与轨迹、可改写他人电子围栏（IDOR）。
 * 复制粘贴模式的必然结果就是漏网，根治方法是逻辑只存在一份。
 *
 * 用法：注入本组件，业务入口处调用 [requireOwned]；
 * 需要设备实体的场景直接使用返回值，避免二次查询。
 */
@Component
class DeviceOwnershipGuard(
    private val deviceRepository: DeviceRepository
) {
    /**
     * 校验设备存在且归属 [userId]；不满足即抛业务异常。
     * 错误码与历史实现一致（DEVICE_NOT_FOUND / DEVICE_NOT_OWNED），客户端无感。
     */
    fun requireOwned(userId: String, deviceId: String): Device {
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (dev.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        }
        return dev
    }
}
