package com.padguard.server.service

import com.padguard.server.repository.LocationTrackRepository
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 定位轨迹地址异步回填（与落库主流程解耦）。
 *
 * [com.padguard.server.service.IngestService.saveLocations] 先把坐标纯数据落库，
 * 再把"需要反查地址"的点交给本服务在后台线程批量做逆地理编码并回填。
 * 这样即便配置了高德 Web Key，定位上报的**写入路径**也不再被同步 HTTP 调用阻塞，
 * 避免频控 / 超时拖累入库。地址会在孩子端下次上报或家长端再次拉取时呈现，属可接受的轻微延迟。
 *
 * 由 [IngestService] 跨 Bean 调用（非自调用），确保 @Async 代理生效；仅在已配置高德 Key 时触发。
 */
@Service
class LocationAddressBackfill(
    private val locationTrackRepository: LocationTrackRepository,
    private val geoCoderService: GeoCoderService
) {
    @Async
    @Transactional
    fun backfill(items: List<LocationTrackAddressInput>) {
        for (item in items) {
            // 同一坐标（GeoCoderService 内四舍五入到 4 位小数 ≈ 11 米）已缓存，重复坐标只打一次高德接口。
            val address = geoCoderService.reverseGeocode(item.lat, item.lng) ?: continue
            locationTrackRepository.updateAddressById(item.id, address)
        }
    }
}

/** 回填输入：定位点主键 + 经纬度（WGS-84）。 */
data class LocationTrackAddressInput(val id: String, val lat: Double, val lng: Double)
