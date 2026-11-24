package com.padguard.server.service

import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Admin
import com.padguard.server.domain.Device
import com.padguard.server.domain.UsageSession
import com.padguard.server.dto.PageResult
import com.padguard.server.repository.AdminRepository
import com.padguard.server.security.AdminPermissionService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest

/**
 * 覆盖 [com.padguard.server.service.AdminShared] 中的包级纯函数与护栏。
 * 这些是后台域大量 Service 复用的"事实源"逻辑，早期分散在各处容易漂移，
 * 现集中后必须有单元测试钉死边界行为。全部为纯函数/轻量 mock，不加载 Spring 上下文。
 */
class AdminSharedTest {

    // ---------------- paginate / clamp ----------------

    @Test
    fun `paginate 默认 page=1 size 在 1 到 200 闭区间`() {
        val all = (1..250).map { "i$it" }
        val r = paginate(all, 1, 200)
        assertEquals(200, r.items.size)
        assertEquals(1, r.page)
        assertEquals(200, r.size)
        assertEquals(250, r.total)
        assertEquals(2, r.totalPages)
    }

    @Test
    fun `paginate page 小于 1 归为 1`() {
        val r = paginate((1..5).map { it }, 0, 10)
        assertEquals(1, r.page)
        assertEquals(5, r.items.size)
    }

    @Test
    fun `paginate size 小于 1 回落 20`() {
        val r = paginate((1..5).map { it }, 1, 0)
        assertEquals(20, r.size)
        assertEquals(5, r.items.size)
    }

    @Test
    fun `paginate size 大于 200 回落 200`() {
        val r = paginate((1..10).map { it }, 1, 999)
        assertEquals(200, r.size)
        assertEquals(10, r.items.size)
    }

    @Test
    fun `paginate 越界页码返回空页但 totalPages 至少 1`() {
        val r = paginate((1..5).map { it }, 99, 10)
        assertTrue(r.items.isEmpty())
        assertEquals(1, r.totalPages)
    }

    @Test
    fun `paginate 总数为 0 时 totalPages 为 1 而非 0`() {
        val r = paginate(emptyList<Int>(), 1, 10)
        assertEquals(0, r.total)
        assertEquals(1, r.totalPages)
        assertTrue(r.items.isEmpty())
    }

    @Test
    fun clampPage_与_clampSize_边界() {
        assertEquals(1, clampPage(0))
        assertEquals(1, clampPage(-5))
        assertEquals(3, clampPage(3))
        assertEquals(20, clampSize(0))
        assertEquals(200, clampSize(9999))
        assertEquals(15, clampSize(15))
    }

    // ---------------- plan 归类 ----------------

    @Test
    fun `planCategory 归类边界`() {
        assertEquals("free", planCategory("TRIAL"))
        assertEquals("monthly", planCategory("MONTHLY"))
        assertEquals("monthly", planCategory("QUARTERLY"))
        assertEquals("yearly", planCategory("YEARLY"))
        assertEquals("permanent", planCategory("PERMANENT"))
        assertEquals("free", planCategory(null))
        assertEquals("free", planCategory(""))
        assertEquals("free", planCategory("BOGUS"))
    }

    @Test
    fun `planLabel 未知或空回落免费版`() {
        assertEquals("免费版", planLabel(null))
        assertEquals("免费版", planLabel(""))
        assertEquals("月卡", planLabel("MONTHLY"))
        assertEquals("免费版", planLabel("BOGUS"))
    }

    // ---------------- todayUsedMinutes（计时派生） ----------------

    @Test
    fun `todayUsedMinutes 空 session 返回 0`() {
        assertEquals(0, todayUsedMinutes(null, 1700000000000L))
    }

    @Test
    fun `todayUsedMinutes 跨天返回 0`() {
        val now = 1700000000000L
        val s = UsageSession(deviceId = "d", dayKey = "2020-01-01", startEpoch = now - 60000)
        assertEquals(0, todayUsedMinutes(s, now))
    }

    @Test
    fun `todayUsedMinutes 未起表返回 0`() {
        val now = 1700000000000L
        val s = UsageSession(deviceId = "d", dayKey = todayKey(now), startEpoch = null)
        assertEquals(0, todayUsedMinutes(s, now))
    }

    @Test
    fun `todayUsedMinutes 起表在未来返回 0`() {
        val now = 1700000000000L
        val s = UsageSession(deviceId = "d", dayKey = todayKey(now), startEpoch = now + 60000)
        assertEquals(0, todayUsedMinutes(s, now))
    }

    @Test
    fun `todayUsedMinutes 正常按分钟向上取整`() {
        val now = 1700000000000L
        val s = UsageSession(
            deviceId = "d", dayKey = todayKey(now), startEpoch = now - 120000,
            quotaMinutes = 60, extendedMinutes = 0, restAccumulatedMs = 0
        )
        assertEquals(2, todayUsedMinutes(s, now))
    }

    @Test
    fun `todayUsedMinutes 扣除护眼休息`() {
        val now = 1700000000000L
        // 必须用两个独立 session：UsageSession 是普通类，also{} 会就地改同一个引用
        fun session(rest: Long) = UsageSession(
            deviceId = "d", dayKey = todayKey(now), startEpoch = now - 120000,
            quotaMinutes = 120, extendedMinutes = 0, restAccumulatedMs = rest
        )
        assertEquals(2, todayUsedMinutes(session(0), now))
        assertEquals(1, todayUsedMinutes(session(60000), now))
    }

    @Test
    fun `todayUsedMinutes 受当日额度封顶`() {
        val now = 1700000000000L
        val s = UsageSession(
            deviceId = "d", dayKey = todayKey(now), startEpoch = now - 120000,
            quotaMinutes = 1, extendedMinutes = 0, restAccumulatedMs = 0
        )
        // 实际已用 2 分钟，但额度仅 1 分钟 -> 封顶为 1
        assertEquals(1, todayUsedMinutes(s, now))
    }

    // ---------------- online 兜底判定 ----------------

    @Test
    fun `online 非 ONLINE 状态 false`() {
        val dev = Device(id = "d", userId = "u").apply { onlineStatus = "OFFLINE" }
        assertFalse(online(dev))
    }

    @Test
    fun `online lastOnlineAt 为空 false`() {
        val dev = Device(id = "d", userId = "u").apply {
            onlineStatus = "ONLINE"; lastOnlineAt = null
        }
        assertFalse(online(dev))
    }

    @Test
    fun `online 滞后超过阈值 false`() {
        val now = System.currentTimeMillis()
        val dev = Device(id = "d", userId = "u").apply {
            onlineStatus = "ONLINE"; lastOnlineAt = now - (ONLINE_STALE_MS + 5000)
        }
        assertFalse(online(dev))
    }

    @Test
    fun `online 新鲜在线 true`() {
        val now = System.currentTimeMillis()
        val dev = Device(id = "d", userId = "u").apply {
            onlineStatus = "ONLINE"; lastOnlineAt = now - 1000
        }
        assertTrue(online(dev))
    }

    // ---------------- fmt ----------------

    @Test
    fun `fmt 0 与 null 返回 null`() {
        assertNull(fmt(0L))
        assertNull(fmt(null))
    }

    @Test
    fun `fmt 正常时间戳返回格式化串`() {
        val s = fmt(1700000000000L)
        assertNotNull(s)
        assertTrue(s!!.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
    }

    // ---------------- 状态小写映射 ----------------

    @Test
    fun `orderStatusLower 映射`() {
        assertEquals("paid", orderStatusLower("PAID"))
        assertEquals("unpaid", orderStatusLower("PENDING"))
        assertEquals("refunded", orderStatusLower("REFUNDED"))
        assertEquals("unknown", orderStatusLower("UNKNOWN"))
    }

    @Test
    fun `subStatusLower 仅小写`() {
        assertEquals("active", subStatusLower("ACTIVE"))
    }

    // ---------------- 审计动作元信息 ----------------

    @Test
    fun `auditActionsOf 已知领域返回动作集合`() {
        val parents = auditActionsOf("parents")
        assertTrue(parents.contains(AdminAudit.PARENTS_SUSPEND))
        assertTrue(parents.contains(AdminAudit.PARENTS_ERASE))
    }

    @Test
    fun `auditActionsOf 未知领域返回空而非全量`() {
        // 关键：空集合才能被调用方当成"不过滤"；若返回全量则拼错 area 会静默退化成全量列表
        assertTrue(auditActionsOf("no-such-area").isEmpty())
    }

    @Test
    fun `resolveAuditAction 未登记归空领域但保留原 action`() {
        val (area, action) = resolveAuditAction("SOME_NEW_ACTION")
        assertEquals("", area)
        assertEquals("SOME_NEW_ACTION", action)
    }

    @Test
    fun `resolveAuditAction 已登记返回领域与中文名`() {
        val (area, name) = resolveAuditAction(AdminAudit.PARENTS_SUSPEND)
        assertEquals("parents", area)
        assertEquals("冻结家长账号", name)
    }

    // ---------------- 防越权提升为 super ----------------

    private fun admin(role: String): Admin = Admin().apply { id = "a"; this.role = role }

    @Test
    fun `ensureNotEscalatingToSuper 非 super 角色不触发校验`() {
        val repo = mock(AdminRepository::class.java)
        `when`(repo.findById(anyString() ?: "")).thenReturn(java.util.Optional.of(admin("ops")))
        // roleId 不是 super，actor 是谁都不应抛
        ensureNotEscalatingToSuper(repo, "actor", "ops")
    }

    @Test
    fun `ensureNotEscalatingToSuper super 操作且本身就是 super 放行`() {
        val repo = mock(AdminRepository::class.java)
        `when`(repo.findById(anyString() ?: "")).thenReturn(java.util.Optional.of(admin("super")))
        ensureNotEscalatingToSuper(repo, "actor", "super")
    }

    @Test
    fun `ensureNotEscalatingToSuper 非 super 造 super 抛 FORBIDDEN`() {
        val repo = mock(AdminRepository::class.java)
        `when`(repo.findById(anyString() ?: "")).thenReturn(java.util.Optional.of(admin("ops")))
        val ex = assertThrows(BizException::class.java) {
            ensureNotEscalatingToSuper(repo, "actor", "super")
        }
        assertEquals(ParentErr.FORBIDDEN, ex.code)
    }

    // ---------------- toAdminDto ----------------

    @Test
    fun `toAdminDto 未传 perm 时 privileges 为空`() {
        val a = admin("ops")
        val dto = toAdminDto(a)
        assertEquals("a", dto.id)
        assertEquals("ops", dto.roleId)
        assertTrue(dto.privileges.isEmpty())
    }

    @Test
    fun `toAdminDto 传入 perm 解析 privileges`() {
        val a = admin("ops")
        val perm = mock(AdminPermissionService::class.java)
        `when`(perm.rolePrivileges(anyString() ?: "")).thenReturn(mapOf("rbac" to listOf("read")))
        val dto = toAdminDto(a, perm)
        assertEquals(mapOf("rbac" to listOf("read")), dto.privileges)
    }

    // ---------------- toPageResult（与 paginate 对齐） ----------------

    @Test
    fun `toPageResult 总数为 0 时 totalPages 为 1`() {
        val page = PageImpl<String>(emptyList(), PageRequest.of(0, 10), 0L)
        val r: PageResult<String> = toPageResult(page, 1, 10) { it }
        assertEquals(0, r.total)
        assertEquals(1, r.totalPages)
        assertTrue(r.items.isEmpty())
    }

    @Test
    fun `toPageResult 正常翻页信封`() {
        val page = PageImpl(listOf("a", "b"), PageRequest.of(0, 10), 2L)
        val r: PageResult<String> = toPageResult(page, 1, 10) { it.uppercase() }
        assertEquals(2, r.total)
        assertEquals(1, r.totalPages)
        assertEquals(listOf("A", "B"), r.items)
    }
}
