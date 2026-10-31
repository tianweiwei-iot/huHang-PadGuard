package com.padguard.child.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.padguard.core.common.Logger
import com.padguard.core.data.repository.LogRepository
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.engine.PolicyEngine
import com.padguard.core.engine.enforcer.WebVerdict
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * 上网管控的本地 DNS 过滤 VPN（E1 主通道）。
 *
 * ## 为什么是「仅 DNS、主机路由」
 * 不做全量转发：只对「本 VPN 的 DNS 服务器 IP」加一条 /32 主机路由，
 * 其余流量仍走原网络。好处：
 * - 域名级拦截（黑名单/白名单/违规分类）对所有 App、所有浏览器、所有 WebView 生效，
 *   且无需 MITM、无需读取页面内容；
 * - 没有默认路由 = 不存在「把所有流量都塞进用户态转发」这一最危险也最易写崩的路径，
 *   通道逻辑即便有缺陷，最坏也是「过滤失效 / 短暂无网」，不会把整台平板的互联网堵死。
 *
 * ## 判定口径
 * 每个 DNS 查询的域名交给 [PolicyEngine.matchUrl]（内部即 [com.padguard.core.engine.enforcer.WebEnforcer]），
 * 与远端策略同一套规则：命中黑名单 / 不在白名单 / 命中违规分类 → 返回 0.0.0.0（连接失败，即被拦）；
 * 放行 → 转发上游 DNS 并把真实应答回灌隧道。
 *
 * ## 已知边界（如实声明，不夸大）
 * - 仅拦截「域名」级：URL 路径 / 页面正文 / 关键词这类需在 HTTP 层可见的信息，本通道看不到
 *   （无 MITM、不装证书），故「关键词过滤」在本版不生效——只拦域名。需要更深覆盖须走 DO 或未来 HTTPS 分流；
 * - 通道涉及底层网络栈，**必须在真机回归**（DEVICE_QA_REQUIRED）；本环境无法验证，任何异常都会自停，
 *   绝不留下半死 VPN 卡 DNS。
 */
@AndroidEntryPoint
class WebGuardVpnService : VpnService() {

    @Inject
    lateinit var policyEngine: PolicyEngine

    @Inject
    lateinit var logRepository: LogRepository

    @Inject
    lateinit var vpnController: WebGuardVpnController

    private var fd: ParcelFileDescriptor? = null
    private var loopThread: Thread? = null
    // SupervisorJob：单次告警上报失败不得取消整个作用域，否则后续拦截全部不再上报。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn("explicit-stop")
                return START_NOT_STICKY
            }
        }
        // 防御式：已在运行则忽略重复启动
        if (fd != null) return START_STICKY
        try {
            startForeground(NOTIF_ID, buildNotification())
            val descriptor = buildVpn().establish()
            if (descriptor == null) {
                Logger.e(TAG) { "vpn establish returned null (prepared?=${VpnService.prepare(this) == null})" }
                stopVpn("establish-null")
                return START_NOT_STICKY
            }
            fd = descriptor
            vpnController.markActive(true)
            running = true
            runLoop(descriptor)
            Logger.i(TAG) { "web guard vpn started" }
        } catch (t: Throwable) {
            Logger.e(TAG, t) { "web guard vpn start failed, self-stopping to avoid dns stall" }
            stopVpn("start-exception")
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun buildVpn(): Builder = Builder().apply {
        setSession("护航成长·上网防护")
        // 仅本地隧道地址 + 主机路由到 DNS 服务器；不加默认路由（关键安全约束）
        addAddress(TUN_LOCAL, 30)
        addRoute(TUN_LOCAL, 32)
        addDnsServer(TUN_LOCAL)
        // 不让本应用自身流量进隧道（避免回环）
        try { addDisallowedApplication(packageName) } catch (_: Throwable) {}
        setMtu(MTU)
    }

    private fun runLoop(descriptor: ParcelFileDescriptor) {
        val buffer = ByteArray(MTU + 64)
        loopThread = thread(name = "webguard-vpn") {
            val input = FileInputStream(descriptor.fileDescriptor)
            val output = FileOutputStream(descriptor.fileDescriptor)
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val len = input.read(buffer)
                    if (len <= 0) {
                        if (len < 0) break // 隧道关闭
                        continue
                    }
                    val pkt = buffer.copyOf(len)
                    handlePacket(pkt, output)
                } catch (t: Throwable) {
                    Logger.e(TAG, t) { "vpn read loop error, self-stopping" }
                    stopVpn("loop-exception")
                    break
                }
            }
        }
    }

    /** 处理一个 IP 包：仅关心发往本 DNS 服务器的 UDP/DNS；其余（理论上不会出现）直接丢弃。 */
    private fun handlePacket(pkt: ByteArray, output: java.io.FileOutputStream) {
        if (pkt.size < 20) return
        val ip = IpPacket.parse(pkt) ?: return
        if (ip.protocol != 17) return // 仅 UDP
        if (ip.dst != TUN_LOCAL) return // 仅发往本 DNS 服务器的
        val udp = UdpPacket.parse(ip.payload, ip.src, ip.dst) ?: return
        if (udp.dstPort != 53) return
        val dns = DnsMessage.parse(udp.payload) ?: return
        val queryName = dns.queryName ?: return
        val verdict = runCatching { policyEngine.matchUrl(queryName, "") }
            .getOrElse { WebVerdict.Allowed }
        if (verdict is WebVerdict.Blocked) {
            Logger.i(TAG) { "DNS BLOCKED: $queryName (${verdict.reason})" }
            scope.launch { reportBlocked(queryName, verdict) }
            writeResponse(output, ip, udp, dns, blocked = true)
        } else {
            forwardUpstream(output, ip, udp, dns)
        }
    }

    /** 命中拦截：直接构造应答，A→0.0.0.0 / AAAA→::，让连接失败即「被拦」。 */
    private fun writeResponse(
        output: java.io.FileOutputStream,
        ip: IpPacket,
        udp: UdpPacket,
        dns: DnsMessage,
        blocked: Boolean
    ) {
        val response = dns.buildResponse(blocked)
        val outUdp = UdpPacket.build(srcPort = 53, dstPort = udp.srcPort, srcIp = ip.dst, dstIp = ip.src, payload = response)
        val outIp = IpPacket.build(protocol = 17, src = ip.dst, dst = ip.src, payload = outUdp)
        runCatching { output.write(outIp) }.onFailure { Logger.w(TAG, it) { "write block response failed" } }
    }

    /** 放行：转发到上游 DNS，并把应答回灌隧道（改写事务 ID 以匹配原始查询）。 */
    private fun forwardUpstream(
        output: java.io.FileOutputStream,
        ip: IpPacket,
        udp: UdpPacket,
        dns: DnsMessage
    ) {
        // use{} 保证任何路径（含超时/异常）都关闭 socket。
        // 早期版本把 socket.close() 写在 try 末尾，send/receive 一旦抛异常就跳过关闭，
        // 每次转发失败泄漏一个 fd —— VPN 长时间跑会耗尽进程 fd 上限，表现为"转发全失败但服务还活着"。
        val socket = runCatching { DatagramSocket() }
            .getOrElse { t ->
                Logger.w(TAG, t) { "create upstream socket failed for ${dns.queryName}" }
                return
            }
        socket.use { s ->
            try {
                protect(s) // 关键：让转发流量走真实网络，不进隧道（避免回环）
                val upstream = DatagramPacket(dns.raw, dns.raw.size, InetAddress.getByName(UPSTREAM_DNS), 53)
                s.send(upstream)
                val recvBuf = ByteArray(4096)
                val recv = DatagramPacket(recvBuf, recvBuf.size)
                s.soTimeout = 5000
                s.receive(recv)
                val upstreamPayload = recv.data.copyOf(recv.length)
                val answer = DnsMessage.rewriteId(upstreamPayload, dns.id)
                val outUdp = UdpPacket.build(srcPort = 53, dstPort = udp.srcPort, srcIp = ip.dst, dstIp = ip.src, payload = answer)
                val outIp = IpPacket.build(protocol = 17, src = ip.dst, dst = ip.src, payload = outUdp)
                runCatching { output.write(outIp) }.onFailure { Logger.w(TAG, it) { "write forward response failed" } }
            } catch (t: Throwable) {
                Logger.w(TAG, t) { "forward dns failed for ${dns.queryName}" }
            }
        }
    }

    /**
     * 上报一次域名拦截告警。
     *
     * **必须 suspend**：`logRepository.raise` 是挂起函数（内部走数据库/网络队列），
     * 在非挂起函数里直接调用编译不过；由调用方在 `scope.launch` 内使用。
     */
    private suspend fun reportBlocked(name: String, verdict: com.padguard.core.engine.enforcer.WebVerdict.Blocked) {
        runCatching {
            logRepository.raise(
                type = RiskType.BLOCKED_URL_ACCESS,
                level = RiskLevel.NORMAL,
                detail = mapOf(
                    "host" to verdict.host,
                    "reason" to verdict.reason.name,
                    "matched" to verdict.matched,
                    "channel" to "vpn-dns"
                ),
                deviceId = "" // 由 logRepository 内部补 deviceId
            )
        }.onFailure { t ->
            // CancellationException 必须重新抛出：协程取消被吞会让本该中止的 IO 继续跑完。
            if (t is kotlinx.coroutines.CancellationException) throw t
            Logger.w(TAG, t) { "raise BLOCKED_URL_ACCESS failed for $name" }
        }
    }

    override fun onRevoke() {
        Logger.w(TAG) { "web guard vpn revoked by user/system" }
        stopVpn("revoked")
    }

    override fun onDestroy() {
        stopVpn("destroy")
        // 取消上报协程作用域：服务销毁后仍在跑的告警上报会持有 logRepository 与本服务引用，
        // 是典型的 service 泄漏。
        runCatching { scope.cancel() }
        super.onDestroy()
    }

    private fun stopVpn(reason: String) {
        Logger.i(TAG) { "web guard vpn stopping: $reason" }
        loopThread?.interrupt()
        loopThread = null
        runCatching { fd?.close() }
        fd = null
        // running 必须在这里复位：它是跨进程/跨组件判断"VPN 是否还在跑"的唯一进程内标记，
        // 早期版本只在启动时置 true、从不置 false，于是服务已经被 revoke/停止后
        // 仍对外报告"运行中"，上层据此不再重新拉起 —— 上网防护就此静默失效。
        running = false
        vpnController.markActive(false)
        runCatching { stopForeground(true) }
    }

    private fun buildNotification(): Notification {
        val channelId = "webguard_vpn"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "上网防护", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("上网防护已开启")
            .setContentText("正在按家长设置过滤不良网站")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    companion object {
        const val ACTION_START = "com.padguard.child.action.WEBGUARD_START"
        const val ACTION_STOP = "com.padguard.child.action.WEBGUARD_STOP"
        private const val TAG = "WebGuardVpn"
        private const val NOTIF_ID = 9051
        private const val TUN_LOCAL = "10.111.222.1"
        private const val UPSTREAM_DNS = "8.8.8.8"
        private const val MTU = 1500

        /** 当前服务是否正在运行（进程内标记；跨进程以 WebGuardVpnController 心跳为准）。 */
        @Volatile
        var running: Boolean = false
            private set

        fun isPrepared(context: android.content.Context): Boolean =
            runCatching { VpnService.prepare(context) == null }.getOrDefault(false)

        fun prepareIntent(context: android.content.Context): Intent? =
            runCatching { VpnService.prepare(context) }.getOrNull()
    }
}

// ===================== 极简 IP / UDP / DNS 解析（仅满足 DNS 过滤所需） =====================

/** IPv4 包解析与构造。 */
private data class IpPacket(
    val protocol: Int,
    val src: String,
    val dst: String,
    val payload: ByteArray
) {
    companion object {
        fun parse(raw: ByteArray): IpPacket? {
            if (raw.size < 20) return null
            val version = (raw[0].toInt() and 0xF0) shr 4
            if (version != 4) return null
            val ihl = (raw[0].toInt() and 0x0F) * 4
            if (ihl < 20 || raw.size < ihl) return null
            val totalLen = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
            val protocol = raw[9].toInt() and 0xFF
            val src = inet4(raw, 12)
            val dst = inet4(raw, 16)
            val payload = raw.copyOfRange(ihl, minOf(totalLen, raw.size))
            return IpPacket(protocol, src, dst, payload)
        }

        fun build(protocol: Int, src: String, dst: String, payload: ByteArray): ByteArray {
            val totalLen = 20 + payload.size
            val buf = ByteBuffer.allocate(totalLen)
            val srcIp = InetAddress.getByName(src).address
            val dstIp = InetAddress.getByName(dst).address
            buf.put((4 shl 4 or 5).toByte())      // version=4, IHL=5
            buf.put(0x00)                          // TOS
            buf.putShort(totalLen.toShort())        // total length
            buf.putShort(0)                         // identification
            buf.putShort(0x4000.toShort())          // flags=DF
            buf.put(64)                            // TTL
            buf.put(protocol.toByte())              // protocol
            buf.putShort(0)                         // header checksum (0; tun 一般不过校验)
            buf.put(srcIp)
            buf.put(dstIp)
            buf.put(payload)
            return buf.array()
        }

        private fun inet4(raw: ByteArray, off: Int): String =
            "${(raw[off].toInt() and 0xFF)}.${(raw[off + 1].toInt() and 0xFF)}." +
                "${(raw[off + 2].toInt() and 0xFF)}.${(raw[off + 3].toInt() and 0xFF)}"
    }
}

/** UDP 包解析与构造。 */
private data class UdpPacket(
    val srcPort: Int,
    val dstPort: Int,
    val srcIp: String,
    val dstIp: String,
    val payload: ByteArray
) {
    companion object {
        fun parse(payload: ByteArray, srcIp: String, dstIp: String): UdpPacket? {
            if (payload.size < 8) return null
            val srcPort = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            val dstPort = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
            val len = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
            val data = payload.copyOfRange(8, minOf(len, payload.size))
            return UdpPacket(srcPort, dstPort, srcIp, dstIp, data)
        }

        fun build(srcPort: Int, dstPort: Int, srcIp: String, dstIp: String, payload: ByteArray): ByteArray {
            val totalLen = 8 + payload.size
            val buf = ByteBuffer.allocate(totalLen)
            buf.putShort(srcPort.toShort())
            buf.putShort(dstPort.toShort())
            buf.putShort(totalLen.toShort())
            buf.putShort(0) // checksum
            buf.put(payload)
            return buf.array()
        }
    }
}

/**
 * 极简 DNS 解析：只读第一个问题的 QNAME 与 TYPE，足以做域名级判定；
 * 应答构造支持 A/AAAA 拦截（返回 0.0.0.0 / ::）与「原样转发」两种路径。
 */
private data class DnsMessage(
    val id: Int,
    val raw: ByteArray,
    val queryName: String?,
    val queryType: Int
) {
    /**
     * 构造拦截应答：A→0.0.0.0 / AAAA→::（连接失败即「被拦」）；其余类型原样回 NXDOMAIN。
     *
     * 注意：这里**必须是成员函数，不能放进 companion**。
     * 声明在 companion 里的 `fun DnsMessage.buildResponse(...)` 是一个「位于伴生对象内部的
     * 扩展函数」，它在调用点并不自动进入作用域（需要显式 import 或带 companion 限定），
     * 早期版本正是这样写的，导致 `:app:compileDebugKotlin` 直接报
     * `Unresolved reference 'buildResponse'` —— 整个孩子端编译不过。
     */
    fun buildResponse(blocked: Boolean): ByteArray {
        val name = this.queryName ?: ""
        val type = this.queryType
        val buf = ByteBuffer.allocate(512)
        buf.putShort(id.toShort())                       // 事务 ID
        buf.putShort(0x8180.toShort())                  // QR=1 RD=1 RA=1
        buf.putShort(1)                                 // QDCOUNT
        buf.putShort(if (blocked) 1 else 0)             // ANCOUNT
        buf.putShort(0); buf.putShort(0)                // NS / AR
        // Question
        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            val b = label.toByteArray(Charsets.US_ASCII)
            buf.put(b.size.toByte()); buf.put(b)
        }
        buf.put(0)
        buf.putShort(type.toShort()); buf.putShort(1)
        if (blocked) {
            buf.putShort(0xC00C.toShort())              // 名字压缩指针 → Question
            buf.putShort(type.toShort())
            buf.putShort(1)
            buf.putInt(60)                              // TTL
            if (type == TYPE_AAAA) {
                buf.putShort(16); repeat(16) { buf.put(0) }   // ::
            } else {
                buf.putShort(4); buf.putInt(0)                // 0.0.0.0
            }
        }
        return buf.flip().run { ByteArray(remaining()) }.also { buf.get(it) }
    }

    companion object {
        const val TYPE_A = 1
        const val TYPE_AAAA = 28

        fun parse(raw: ByteArray): DnsMessage? {
            if (raw.size < 12) return null
            val id = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
            val qdcount = ((raw[10].toInt() and 0xFF) shl 8) or (raw[11].toInt() and 0xFF)
            if (qdcount < 1) return null
            var off = 12
            val labels = StringBuilder()
            while (off < raw.size) {
                val len = raw[off].toInt() and 0xFF
                if (len == 0) { off++; break }
                if (len >= 0xC0) { off += 2; break } // 压缩指针：QNAME 一般无压缩，忽略后续
                if (off + 1 + len > raw.size) return null
                if (labels.isNotEmpty()) labels.append('.')
                labels.append(String(raw, off + 1, len, Charsets.US_ASCII))
                off += 1 + len
            }
            if (off + 4 > raw.size) return null
            val qtype = ((raw[off].toInt() and 0xFF) shl 8) or (raw[off + 1].toInt() and 0xFF)
            return DnsMessage(id, raw, labels.toString().lowercase(), qtype)
        }

        /** 把上游应答的事务 ID 改写为原始查询 ID，使客户端能匹配。 */
        fun rewriteId(upstream: ByteArray, originalId: Int): ByteArray {
            if (upstream.size < 2) return upstream
            val out = upstream.copyOf()
            out[0] = (originalId shr 8).toByte()
            out[1] = (originalId and 0xFF).toByte()
            return out
        }
    }
}
