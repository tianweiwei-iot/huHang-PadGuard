package com.padguard.server.config

import com.padguard.server.domain.AuditLog
import com.padguard.server.domain.Device
import com.padguard.server.domain.DeviceSetting
import com.padguard.server.domain.PolicyTemplate
import com.padguard.server.domain.Subscription
import com.padguard.server.domain.SensitiveWord
import com.padguard.server.domain.UrlCategory
import com.padguard.server.domain.User
import com.padguard.server.repository.AuditLogRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.DeviceSettingRepository
import com.padguard.server.repository.PolicyTemplateRepository
import com.padguard.server.repository.SensitiveWordRepository
import com.padguard.server.repository.SubscriptionRepository
import com.padguard.server.repository.UrlCategoryRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.DeviceOwnershipGuard
import com.padguard.server.security.Passwords
import com.padguard.server.service.AuditService
import com.padguard.server.service.ContentFilterService
import com.padguard.server.service.FakeJpaRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import org.springframework.mock.env.MockEnvironment
import java.io.File
import java.util.LinkedHashMap

/**
 * W16 出厂弱口令强重置的门禁测试。
 *
 * 审计判定：安装包分发 `13800000000 / admin123`，任何人拿到包即可登录家长端。
 * 修复口径（DataInitializer.seedParentAccount）：
 * - 正式部署（非 local profile）库里**已存在**仍用出厂弱口令的账号 → 强制重置为随机口令；
 * - 空库播种 → 随机口令；
 * - 只有 local 联调 profile 才保留 admin123 演示账号。
 *
 * 本测试守住三条不变量：
 * 1. 重置后 DB 里的哈希**确实变了**（旧实现只改内存对象未二次落库，DB 仍是弱口令）；
 * 2. `admin123` 对新哈希**不可再登录**（matches=false）；
 * 3. 写进初始密码文件的新口令**可以登录**（matches=true）—— 重置不能把家长锁死在系统外。
 */
class DataInitializerTest {

    // ----------------------------- 内存假仓储 -----------------------------

    private class FakeUserRepo : FakeJpaRepository<User, String>(), UserRepository {
        override val table = LinkedHashMap<String, User>()
        override fun idOf(e: User) = e.id
        override fun existsByPhone(phone: String) = table.values.any { it.phone == phone }
        override fun findByPhone(phone: String): User? = table.values.firstOrNull { it.phone == phone }
        override fun countByStatus(status: String) =
            table.values.count { (it.status ?: "active") == status }.toLong()
    }

    private class FakeDeviceRepo : FakeJpaRepository<Device, String>(), DeviceRepository {
        override val table = LinkedHashMap<String, Device>()
        override fun idOf(e: Device) = e.id
        override fun findByUserId(userId: String) = table.values.filter { it.userId == userId }
        override fun countByUserId(userId: String) = table.values.count { it.userId == userId }.toLong()
        override fun countByOnlineStatus(onlineStatus: String) =
            table.values.count { it.onlineStatus == onlineStatus }.toLong()
        override fun findByDeviceSn(deviceSn: String) = table.values.filter { it.deviceSn == deviceSn }
        override fun findByLastActiveAtGreaterThan(since: Long) =
            table.values.filter { (it.lastActiveAt ?: 0L) > since }
    }

    private class FakeDeviceSettingRepo : FakeJpaRepository<DeviceSetting, String>(), DeviceSettingRepository {
        override val table = LinkedHashMap<String, DeviceSetting>()
        override fun idOf(e: DeviceSetting) = e.deviceId
        override fun deleteByDeviceId(deviceId: String): Int {
            val before = table.size
            table.values.removeIf { it.deviceId == deviceId }
            return before - table.size
        }
    }

    private class FakeUrlCategoryRepo : FakeJpaRepository<UrlCategory, String>(), UrlCategoryRepository {
        override val table = LinkedHashMap<String, UrlCategory>()
        override fun idOf(e: UrlCategory) = e.id
    }

    private class FakeSensitiveWordRepo : FakeJpaRepository<SensitiveWord, String>(), SensitiveWordRepository {
        override val table = LinkedHashMap<String, SensitiveWord>()
        override fun idOf(e: SensitiveWord) = e.id
        override fun findByWord(word: String): SensitiveWord? = table.values.firstOrNull { it.word == word }
    }

    private class FakePolicyTemplateRepo : FakeJpaRepository<PolicyTemplate, String>(), PolicyTemplateRepository {
        override val table = LinkedHashMap<String, PolicyTemplate>()
        override fun idOf(e: PolicyTemplate) = e.id
        override fun findBySceneType(sceneType: String) = table.values.filter { it.sceneType == sceneType }
    }

    private class FakeSubscriptionRepo : FakeJpaRepository<Subscription, String>(), SubscriptionRepository {
        override val table = LinkedHashMap<String, Subscription>()
        override fun idOf(e: Subscription) = e.id
        override fun findByUserId(userId: String): Subscription? = table.values.firstOrNull { it.userId == userId }
        override fun countByStatus(status: String) = table.values.count { it.status == status }.toLong()
        override fun findAllByOrderByExpiredAtDesc() = table.values.sortedByDescending { it.expiredAt }
    }

    private class FakeAuditLogRepo : FakeJpaRepository<AuditLog, String>(), AuditLogRepository {
        override val table = LinkedHashMap<String, AuditLog>()
        override fun idOf(e: AuditLog) = e.id
        override fun findByUserIdAndCreatedAtBetween(userId: String, start: Long, end: Long) = emptyList<AuditLog>()
        override fun findByDeviceIdAndCreatedAtBetween(deviceId: String, start: Long, end: Long) = emptyList<AuditLog>()
        override fun findAllByOrderByCreatedAtDesc(): List<AuditLog> = table.values.sortedByDescending { it.createdAt }
        override fun findByDeviceIdOrderByCreatedAtDesc(deviceId: String, pageable: Pageable) = emptyList<AuditLog>()
        override fun findByDeviceIdAndActionOrderByCreatedAtDesc(deviceId: String, action: String, pageable: Pageable) = emptyList<AuditLog>()
        override fun findByUserIdOrderByCreatedAtDesc(userId: String, pageable: Pageable) = emptyList<AuditLog>()
        override fun findByUserIdAndActionOrderByCreatedAtDesc(userId: String, action: String, pageable: Pageable) = emptyList<AuditLog>()
    }

    // ----------------------------- 夹具 -----------------------------

    /** 初始密码文件写在**当前工作目录**（相对路径），测试后必须清理，不留在工程里 */
    private val passwordFile = File("初始密码-请登录后修改.txt")

    private lateinit var users: FakeUserRepo

    /** 无 active profile = 正式部署口径（local 演示分支不进入） */
    private fun initializer(users: FakeUserRepo): DataInitializer {
        val devices = FakeDeviceRepo()
        val auditLogs = FakeAuditLogRepo()
        val guard = DeviceOwnershipGuard(devices)
        val contentFilter = ContentFilterService(
            devices, guard, FakeDeviceSettingRepo(), FakeUrlCategoryRepo(), FakeSensitiveWordRepo(),
            AuditService(auditLogs, guard)
        )
        return DataInitializer(
            users, FakePolicyTemplateRepo(), FakeSubscriptionRepo(), contentFilter, MockEnvironment(),
            // 正式部署口径：演示账号开关关闭（padguard.demo-accounts-enabled 默认值）。
            false,
            // E7 新增构造参数：口令文件路径。传与默认键同值的相对路径，
            // 让既有断言（文件落在模块目录、tearDown 清理）继续成立。
            passwordFile.name
        )
    }

    @BeforeEach
    fun setUp() {
        users = FakeUserRepo()
    }

    @AfterEach
    fun tearDown() {
        // 初始密码文件含明文口令：测试产物必须删除，不留在模块目录里
        runCatching { passwordFile.delete() }
    }

    /** 从初始密码文件里解析回新口令（文件格式见 DataInitializer.writeInitialPasswordFile） */
    private fun readPasswordFromFile(): String {
        assertTrue(passwordFile.exists(), "重置后必须写下初始密码文件，否则家长拿不到新口令")
        val line = passwordFile.readLines().firstOrNull { it.startsWith("初始密码：") }
        assertNotNull(line, "初始密码文件缺少口令行")
        return line!!.substringAfter("初始密码：").trim()
    }

    // ----------------------------- W16：存量弱口令强制重置 -----------------------------

    @Test
    fun `existing admin123 account is force reset and the new hash is persisted`() {
        // 模拟"已部署过的库"：账号还在，口令仍是出厂弱口令
        val weakHash = Passwords.encode("admin123")
        users.save(User(id = "u-legacy", phone = "13800000000", passwordHash = weakHash, role = "PARENT", sceneType = "FAMILY"))

        initializer(users).seed()

        val stored = users.findByPhone("13800000000")!!
        // 1) DB 哈希必须真的变了（旧实现只改内存、未二次落库，这条断言正对那个 bug）
        assertFalse(stored.passwordHash == weakHash, "W16：新哈希必须真实落库，不能只改内存对象")
        // 2) admin123 不可再登录
        assertFalse(Passwords.matches("admin123", stored.passwordHash), "出厂弱口令 admin123 必须失效")
        // 3) 文件里的新口令可以登录（重置不能把家长锁死在系统外）
        val newPwd = readPasswordFromFile()
        assertTrue(newPwd.isNotBlank(), "新口令不能为空")
        assertTrue(Passwords.matches(newPwd, stored.passwordHash), "初始密码文件里的新口令必须能登录")
    }

    @Test
    fun `an account whose password was already changed is left untouched`() {
        val changed = Passwords.encode("MyStr0ng-Pass!")
        users.save(User(id = "u-changed", phone = "13800000000", passwordHash = changed, role = "PARENT", sceneType = "FAMILY"))

        initializer(users).seed()

        val stored = users.findByPhone("13800000000")!!
        assertEquals(changed, stored.passwordHash, "家长已自行改过口令的账号不得被重置打扰")
        assertFalse(passwordFile.exists(), "未发生重置时不得写初始密码文件")
    }

    @Test
    fun `a fresh empty repository gets a seeded account with a random password`() {
        initializer(users).seed()

        val seeded = users.findByPhone("13800000000")
        assertNotNull(seeded, "空库部署必须播种一个可登录的家长账号（系统无注册入口）")
        assertFalse(Passwords.matches("admin123", seeded!!.passwordHash), "正式部署播种的口令绝不能是 admin123")
        assertTrue(Passwords.matches(readPasswordFromFile(), seeded.passwordHash), "播种口令与密码文件必须一致")
    }
}
