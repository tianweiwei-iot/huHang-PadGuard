package com.padguard.server.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * 逆地理编码服务（服务端侧地址文字化）。
 *
 * 孩子端上报的是原始 GPS 坐标（WGS-84）。定位页需要把坐标变成人能读的「XX 路 XX 号」，
 * 这一步由服务端统一完成，理由：
 *  1. 孩子端国产设备普遍无 GMS，且把 Key 烧进孩子端 apk 等于把「按次计费 / 配额」的凭证
 *     直接发给被管控方，既不合规也不安全；服务端持有 Web 端 Key 更可控。
 *  2. 服务端在落库时一次性反查并落库，家长端查询直接读文字，零额外网络往返。
 *
 * 合规与稳定性要点：
 *  - 高德 Key 经配置项 `padguard.amap.web-key` 注入，默认空 = 不做反查（[reverseGeocode] 直接返回 null），
 *    绝不把真实 Key 写进代码仓库。
 *  - 高德逆地理要求传入的坐标系是 WGS-84（[coordsys]=wgs84），与设备上报坐标系一致，无需二次转换。
 *  - 任意异常（网络超时 / Key 无效 / 额度耗尽）都吞掉并返回 null，绝不让反查失败拖垮定位落库主流程。
 *  - 同坐标（四舍五入到 4 位小数 ≈ 11 米）结果缓存，避免轨迹密集上报时反复打高德接口。
 */
@Service
class GeoCoderService {

    private val log = LoggerFactory.getLogger(javaClass)

    @Value("\${padguard.amap.web-key:}")
    private lateinit var webKey: String

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    private val objectMapper = ObjectMapper()

    /** 坐标 -> 地址缓存，最多保留 [CACHE_MAX] 条，防止长时间运行内存膨胀。 */
    private val cache = object : LinkedHashMap<String, String?>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean =
            size > CACHE_MAX
    }
    private val cacheLock = Any()

    /**
     * 对 WGS-84 坐标做逆地理编码，返回格式化地址；无 Key、坐标非法或调用失败时返回 null。
     */
    fun reverseGeocode(lat: Double, lng: Double): String? {
        if (webKey.isBlank()) return null
        if (lat == 0.0 && lng == 0.0) return null
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null

        val cacheKey = "${round(lat)}:${round(lng)}"
        synchronized(cacheLock) { cache[cacheKey]?.let { return it } }

        val address = try {
            queryAmap(lat, lng)
        } catch (e: Exception) {
            log.warn("逆地理编码失败 lat={}, lng={}: {}", lat, lng, e.message)
            null
        }
        synchronized(cacheLock) { cache[cacheKey] = address }
        return address
    }

    /** 是否已配置高德 Web Key：配置后才真正发起逆地理请求；默认（无 Key）直接返回 null，零开销。 */
    fun isConfigured(): Boolean = webKey.isNotBlank()

    private fun queryAmap(lat: Double, lng: Double): String? {
        val url = URI.create(
            "https://restapi.amap.com/v3/geocode/regeo" +
                "?key=${webKey}" +
                "&location=${lng},${lat}" +
                "&coordsys=wgs84" +
                "&radius=1000" +
                "&extensions=base" +
                "&output=JSON"
        )
        val request = HttpRequest.newBuilder()
            .uri(url)
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build()
        val resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) {
            log.warn("逆地理编码 HTTP 状态异常: {}", resp.statusCode())
            return null
        }
        val root: JsonNode = objectMapper.readTree(resp.body())
        // 高德返回 status=1 为成功
        if (root.path("status").asText() != "1") {
            log.warn("逆地理编码业务失败: {}", root.path("info").asText())
            return null
        }
        val formatted = root.path("regeocode").path("formatted_address").asText(null)
        return if (formatted.isNullOrBlank()) null else formatted
    }

    /** 坐标四舍五入到 4 位小数（≈11 米），用作缓存键，把邻近点归并到同一地址。 */
    private fun round(v: Double): String = "%.4f".format(v)

    companion object {
        private const val CACHE_MAX = 2000
    }
}
