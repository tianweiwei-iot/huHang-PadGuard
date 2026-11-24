package com.padguard.parent.presentation.viewmodel

import com.padguard.parent.domain.model.GeofenceConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 家长端围栏提交前校验的行为锁（纯 JVM）。
 *
 * 这段逻辑是服务端 `GeofenceService.validateZone` 的客户端同口径兜底：
 * 越界值一旦写进台账，越界告警会永久失真。此前只有生产代码没有测试，
 * 改坏边界不会被任何门禁发现。
 */
class GeofenceInputValidatorTest {

    private fun zone(
        lat: Double = 31.23,
        lon: Double = 121.47,
        radius: Int = 500
    ) = GeofenceConfig(deviceId = "device-1", centerLatitude = lat, centerLongitude = lon, radiusMeters = radius)

    @Test
    fun `合法围栏通过`() {
        assertTrue(GeofenceInputValidator.isValid(zone()))
        assertTrue(GeofenceInputValidator.isValid(zone(lat = -90.0, lon = 180.0, radius = GeofenceInputValidator.RADIUS_MIN)))
        assertTrue(GeofenceInputValidator.isValid(zone(lat = 90.0, lon = -180.0, radius = GeofenceInputValidator.RADIUS_MAX)))
    }

    @Test
    fun `越界半径被拒绝`() {
        assertFalse(GeofenceInputValidator.isValid(zone(radius = GeofenceInputValidator.RADIUS_MIN - 1)))
        assertFalse(GeofenceInputValidator.isValid(zone(radius = GeofenceInputValidator.RADIUS_MAX + 1)))
    }

    @Test
    fun `非有限坐标与越界坐标被拒绝`() {
        assertFalse(GeofenceInputValidator.isValid(zone(lat = Double.NaN)))
        assertFalse(GeofenceInputValidator.isValid(zone(lon = Double.POSITIVE_INFINITY)))
        assertFalse(GeofenceInputValidator.isValid(zone(lat = 90.0001)))
        assertFalse(GeofenceInputValidator.isValid(zone(lon = -180.0001)))
    }
}
