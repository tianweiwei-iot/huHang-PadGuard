package com.padguard.server.config

import com.padguard.server.domain.Plan
import com.padguard.server.domain.PolicyTemplate
import com.padguard.server.domain.SubStatus
import com.padguard.server.domain.Subscription
import com.padguard.server.domain.User
import com.padguard.server.repository.PolicyTemplateRepository
import com.padguard.server.repository.SubscriptionRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.Passwords
import com.padguard.server.service.ContentFilterService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/**
 * 演示数据与种子数据：
 * 1. 内置一个家长账号，便于 P0 直接联调；
 * 2. 幂等播种策略模板（P3-2，≥6 套），postgres 与 h2 双覆盖；
 * 3. 初始化内容管控字典（P2：网址分类库 + 敏感词库）。
 */
@Component
class DataInitializer(
    private val userRepository: UserRepository,
    private val policyTemplateRepository: PolicyTemplateRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val contentFilterService: ContentFilterService,
    private val env: Environment,
    /**
     * E7（与 AdminDataInitializer 同口径）：家长端初始/重置口令文件落盘路径。
     * 旧实现写死相对路径（容器工作目录）——docker-compose 只挂了 `./uploads:/app/uploads`，
     * 工作目录在容器可写层里，容器一重建文件就丢，而家长口令已被强制重置出弱口令，
     * 后果是全新部署直接锁死家长端、没人知道口令。默认改到挂载卷内的 uploads/ 下，
     * 并支持用环境变量/配置覆盖到 secrets 卷（`PADGUARD_PARENT_INITIAL_PASSWORD_FILE`）。
     *
     * **为什么与 AdminDataInitializer 分成两个配置键**：那边写的是后台管理员口令、
     * 这边写的是家长端账号口令，是两个不同账号的两份明文。共用一个键时，
     * 运营按任一处的说明覆盖路径，两份口令就会被挤进同一个文件、后写的覆盖先写的 ——
     * 拿到文件的那一方登录直接失败，且没有任何报错。
     */
    @Value("\${padguard.parent-initial-password-file:uploads/初始密码-请登录后修改.txt}")
    private val initialPasswordFile: String
) {

    private val log = LoggerFactory.getLogger(DataInitializer::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun seed() {
        seedParentAccount()
        if (env.activeProfiles.contains(PROFILE_LOCAL)) {
            // 测试账号（PERMANENT 永久订阅）只允许在 local 联调环境播种。
            // 此前它对所有环境无条件生效，等于随安装包分发一个永久全功能账号，
            // 密码还随源码/安装包分发 —— 与演示账号属同一类漏洞，收口到同一处理口径。
            seedTestAccount()
        } else {
            // 已部署库里若已存在仍在用出厂弱口令的测试账号，强制重置 ——
            // 光"跳过播种"挡不住存量库（与 seedParentAccount 对 admin123 的处理同口径）。
            resetTestAccountIfPresent()
        }
        seedPolicyTemplates()
        runCatching { contentFilterService.seedDictionaries() }
            .onFailure { log.warn("seed content dictionaries failed: {}", it.message) }
    }

    /**
     * 账号初始化。
     *
     * 原先无条件播种 `13800000000 / admin123`，且口令写死在代码里随安装包分发 ——
     * 任何人拿到安装包就能登录家长端，查看实时画面、定位轨迹并远程操控设备。
     *
     * 但也不能简单删掉：系统**没有任何注册入口**（两个登录接口都要求用户已存在），
     * 删掉等于把系统彻底锁死。所以改为：
     * - 开发环境（local profile）：保留固定演示账号，方便联调；
     * - 正式部署：库里无账号时才创建，口令**随机生成并写入本地文件**，绝不硬编码。
     */
    private fun seedParentAccount() {
        if (env.activeProfiles.contains(PROFILE_LOCAL)) {
            seedDemoAccount()
            return
        }
        val existing = userRepository.findByPhone(DEFAULT_ADMIN_PHONE)
        when {
            // 库里已有家长账号且口令已改过 → 什么都不做
            existing == null && userRepository.count() > 0 -> return
            // 关键：只"新建时随机"是不够的。已经部署过的库里那个 admin123 账号依然存在
            // （因为它命中了上面的"已有账号"分支），等于漏洞没修。
            // 所以这里主动识别出仍在使用出厂弱口令的账号并强制重置 ——
            // 家长若已自行改过密码，matches 为 false，不会打扰。
            existing != null && Passwords.matches(DEMO_PASSWORD, existing.passwordHash) -> {
                // W16 修复：旧实现在 `seedInitialAdminPassword` 内部对用户做了一次 save，
                // 但那时 passwordHash 还是旧哈希，返回新密码后只改了内存对象、未二次落库，
                // 导致 DB 仍是出厂弱口令。现改为：先算新哈希并赋值，再一次性 save。
                val password = randomPassword()
                existing.passwordHash = Passwords.encode(password)
                userRepository.save(existing)
                writeInitialPasswordFile(existing.phone, password, forceReset = true)
            }
            existing == null -> seedInitialAdmin()
        }
    }

    /** 正式部署：识别仍在使用出厂弱口令的测试账号并强制重置（与演示账号同口径） */
    private fun resetTestAccountIfPresent() {
        val existing = userRepository.findByPhone(TEST_ACCOUNT_PHONE) ?: return
        if (!Passwords.matches(TEST_ACCOUNT_PASSWORD, existing.passwordHash)) return
        val password = randomPassword()
        existing.passwordHash = Passwords.encode(password)
        userRepository.save(existing)
        writeInitialPasswordFile(existing.phone, password, forceReset = true)
    }

    private fun seedDemoAccount() {
        val phone = DEFAULT_ADMIN_PHONE
        if (!userRepository.existsByPhone(phone)) {
            userRepository.save(
                User(
                    id = UUID.randomUUID().toString(),
                    phone = phone,
                    passwordHash = Passwords.encode(DEMO_PASSWORD),
                    nickname = "演示家长",
                    role = "PARENT",
                    sceneType = "FAMILY",
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun seedInitialAdmin() {
        val password = randomPassword()
        userRepository.save(
            User(
                id = UUID.randomUUID().toString(),
                phone = DEFAULT_ADMIN_PHONE,
                passwordHash = Passwords.encode(password),
                nickname = "家长",
                role = "PARENT",
                sceneType = "FAMILY",
                createdAt = System.currentTimeMillis()
            )
        )
        writeInitialPasswordFile(DEFAULT_ADMIN_PHONE, password, forceReset = false)
    }

    /**
     * 把初始/重置后的明文口令写入服务端工作目录，并在日志里提示绝对路径。
     *
     * 为什么必须落盘而不是只打日志：家长不会去翻 server-run.log，
     * 拿不到密码就进不去家长端，系统等于锁死。而口令不能硬编码（那正是本次要修的漏洞），
     * 所以"随机生成 + 落到显眼的文件 + 日志提示路径"是唯一既安全又可用的组合。
     */
    private fun writeInitialPasswordFile(phone: String, password: String, forceReset: Boolean) {
        val file = File(initialPasswordFile)
        runCatching {
            // E7：目标目录可能不存在（uploads 卷未预建 / secrets 卷空挂载），先建目录再写
            file.parentFile?.mkdirs()
            file.writeText(
                """
                PadGuard 初始账号口令
                ============================
                手机号：$phone
                初始密码：$password

                ${if (forceReset) "检测到该账号仍在使用出厂弱口令，已强制重置为上述随机口令。" else ""}
                请在家长端登录后立即修改密码。
                此文件包含明文口令，妥善保管，用后可删除。
                """.trimIndent(), Charsets.UTF_8
            )
        }.onFailure { log.error("写入初始密码文件失败: {}", it.message) }
        log.warn(
            "账号 phone={} 的口令已写入 {}（请登录后立即修改该文件并删除）",
            phone, file.absolutePath
        )
    }

    /**
     * 播种固定测试账号（永久会员）。
     *
     * 与演示账号（local 专用、弱口令）不同，测试账号面向所有环境、且是真实可用账号：
     * - 在所有 profile 下都幂等播种（existByPhone 去重），清库重部署也能恢复；
     * - 密码明文仅此处硬编码（用户显式要求的种子账号），并在日志中以 warn 提示尽快修改；
     * - 直接落一条 PERMANENT 订阅，状态机永不置过期，等价于"付费即全功能"的永久形态。
     */
    private fun seedTestAccount() {
        val phone = TEST_ACCOUNT_PHONE
        if (userRepository.existsByPhone(phone)) {
            log.info("测试账号已存在，跳过播种: {}", phone)
            return
        }
        val now = System.currentTimeMillis()
        val user = User(
            id = UUID.randomUUID().toString(),
            phone = phone,
            passwordHash = Passwords.encode(TEST_ACCOUNT_PASSWORD),
            nickname = "测试账号",
            role = "PARENT",
            sceneType = "FAMILY",
            createdAt = now
        )
        userRepository.save(user)
        subscriptionRepository.save(
            Subscription(
                id = UUID.randomUUID().toString(),
                userId = user.id,
                plan = Plan.PERMANENT.key,
                status = SubStatus.ACTIVE.name,
                deviceLimit = 10,
                startedAt = now,
                // 极远未来：即便状态机将来误判，也远在过期线之外；refreshStatus 对 PERMANENT 直接 return
                expiredAt = now + 100L * 365 * 86_400_000L,
                createdAt = now,
                updatedAt = now
            )
        )
        log.warn("已播种测试账号：手机号={} / 永久会员（仅限 local 联调环境）", phone)
    }

    /** 去掉了 0/O、1/l/I 等易看错的字符，避免家长照抄时反复失败 */
    private fun randomPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        return (1..16).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    /**
     * 策略模板市场（P3-2）：覆盖家庭与校园、日常与特殊场景，≥6 套。
     * 以 existsById 幂等，避免与 postgres 的 V2 迁移种子重复（V2 已含前 2 套）。
     */
    private fun seedPolicyTemplates() {
        val templates = listOf(
            PolicyTemplate(
                id = "tpl_family_basic", name = "家庭基础防护", sceneType = "FAMILY", category = "BASIC",
                description = "适合家庭场景的均衡管控：白名单应用、夜间锁屏、护眼提醒",
                packageJson = """{"peripheral":{"wifi":"ALLOW","bluetooth":"DISABLE","camera":"DISABLE","usbFileTransfer":"DISABLE"},"app":{"mode":"WHITELIST","whitelist":["com.android.calculator2","com.example.study"],"blacklist":["com.tencent.mm","com.ss.android.ugc.aweme"]},"appLimit":{"dailyTotalMinutes":120},"schedule":{"timezone":"Asia/Shanghai","rules":[{"dayTypes":["WEEKDAY"],"start":"21:30","end":"07:00","action":"LOCK"}]},"eyeCare":{"enabled":true,"continuousMinutes":40,"restMinutes":10},"monitoring":{"heartbeatIntervalSec":30,"logUploadIntervalSec":300}}"""
            ),
            PolicyTemplate(
                id = "tpl_school_focus", name = "课堂专注模式", sceneType = "SCHOOL", category = "FOCUS",
                description = "适合学校场景：仅学习类应用可用、上课时段全锁、禁用相机与浏览器下载",
                packageJson = """{"peripheral":{"wifi":"ALLOW","bluetooth":"DISABLE","camera":"DISABLE","microphone":"DISABLE","castScreen":"DISABLE"},"app":{"mode":"WHITELIST","whitelist":["com.example.study","com.android.calculator2"],"blacklist":["com.tencent.mm"]},"appLimit":{"dailyTotalMinutes":240},"schedule":{"timezone":"Asia/Shanghai","rules":[{"dayTypes":["WEEKDAY"],"start":"08:00","end":"17:00","action":"LOCK"}]},"kiosk":{"enabled":true,"mode":"MULTI_APP","allowedPackages":["com.example.study"]},"security":{"antiUninstall":true,"blockRoot":true}}"""
            ),
            PolicyTemplate(
                id = "tpl_learning", name = "学习模式", sceneType = "FAMILY", category = "STUDY",
                description = "学习时段：仅学习类与工具类应用可用，每日 180 分钟，开启护眼",
                packageJson = """{"peripheral":{"wifi":"ALLOW","bluetooth":"DISABLE","camera":"DISABLE","usbFileTransfer":"DISABLE"},"app":{"mode":"WHITELIST","whitelist":["com.example.study","com.android.calculator2","com.android.chrome"],"blacklist":["com.tencent.mm","com.ss.android.ugc.aweme","com.netease.gamecenter"]},"appLimit":{"dailyTotalMinutes":180},"eyeCare":{"enabled":true,"continuousMinutes":30,"restMinutes":10},"monitoring":{"heartbeatIntervalSec":30,"logUploadIntervalSec":300}}"""
            ),
            PolicyTemplate(
                id = "tpl_exam_week", name = "考试周", sceneType = "FAMILY", category = "EXAM",
                description = "考试周强化：仅学习类应用，上课/自习时段全锁，禁用社交与游戏",
                packageJson = """{"peripheral":{"wifi":"ALLOW","bluetooth":"DISABLE","camera":"DISABLE","usbFileTransfer":"DISABLE"},"app":{"mode":"WHITELIST","whitelist":["com.example.study","com.android.calculator2"],"blacklist":["com.tencent.mm","com.ss.android.ugc.aweme","com.netease.gamecenter","com.eg.android.AlipayGphone"]},"appLimit":{"dailyTotalMinutes":240},"schedule":{"timezone":"Asia/Shanghai","rules":[{"dayTypes":["WEEKDAY"],"start":"07:00","end":"22:00","action":"LOCK"}]},"kiosk":{"enabled":true,"mode":"MULTI_APP","allowedPackages":["com.example.study","com.android.calculator2"]},"monitoring":{"heartbeatIntervalSec":20,"logUploadIntervalSec":300}}"""
            ),
            PolicyTemplate(
                id = "tpl_holiday", name = "假期放松", sceneType = "FAMILY", category = "RELAX",
                description = "假期放宽：每日 240 分钟、周末 300 分钟，社交与游戏限时可用",
                packageJson = """{"peripheral":{"wifi":"ALLOW","bluetooth":"ALLOW","camera":"ALLOW","usbFileTransfer":"DISABLE"},"app":{"mode":"BLACKLIST","whitelist":[],"blacklist":["com.android.browser"]},"appLimit":{"dailyTotalMinutes":240,"weekdayTotalMinutes":240,"weekendTotalMinutes":300,"rules":[{"packageName":"com.ss.android.ugc.aweme","dailyMinutes":60,"dayTypes":["WEEKDAY","WEEKEND"]},{"packageName":"com.tencent.mm","dailyMinutes":90,"dayTypes":["WEEKDAY","WEEKEND"]}]},"eyeCare":{"enabled":true,"continuousMinutes":45,"restMinutes":10},"monitoring":{"heartbeatIntervalSec":30,"logUploadIntervalSec":300}}"""
            ),
            PolicyTemplate(
                id = "tpl_night_lock", name = "夜间锁屏", sceneType = "FAMILY", category = "SAFETY",
                description = "强制宵禁：22:00–06:00 停止服务，其余时段正常，防深夜沉迷",
                packageJson = """{"peripheral":{"wifi":"ALLOW","bluetooth":"DISABLE","camera":"DISABLE","usbFileTransfer":"DISABLE"},"app":{"mode":"BLACKLIST","whitelist":[],"blacklist":[]},"appLimit":{"dailyTotalMinutes":120},"schedule":{"timezone":"Asia/Shanghai","rules":[{"dayTypes":["WEEKDAY","WEEKEND"],"start":"22:00","end":"06:00","action":"LOCK"}]},"monitoring":{"heartbeatIntervalSec":30,"logUploadIntervalSec":300}}"""
            ),
            PolicyTemplate(
                id = "tpl_anti_addiction", name = "防沉迷", sceneType = "FAMILY", category = "SAFETY",
                description = "强防沉迷：每日 90 分钟、连续 30 分钟休息、游戏与社交应用限时使用",
                packageJson = """{"peripheral":{"wifi":"ALLOW","bluetooth":"DISABLE","camera":"DISABLE","usbFileTransfer":"DISABLE"},"app":{"mode":"BLACKLIST","whitelist":[],"blacklist":["com.netease.gamecenter","com.miHoYo.ys.mi","com.tencent.tmgp.sgame"]},"appLimit":{"dailyTotalMinutes":90,"rules":[{"packageName":"com.tencent.mm","dailyMinutes":30,"dayTypes":["WEEKDAY","WEEKEND"]}]},"eyeCare":{"enabled":true,"continuousMinutes":30,"restMinutes":10},"monitoring":{"heartbeatIntervalSec":20,"logUploadIntervalSec":300}}"""
            )
        )

        var added = 0
        for (t in templates) {
            if (!policyTemplateRepository.existsById(t.id)) {
                policyTemplateRepository.save(t)
                added++
            }
        }
        if (added > 0) log.info("seeded {} policy templates", added)
    }

    private companion object {
        const val PROFILE_LOCAL = "local"
        const val DEFAULT_ADMIN_PHONE = "13800000000"
        /** 仅 local profile 使用的演示口令；正式部署走随机生成，绝不会用到它 */
        const val DEMO_PASSWORD = "admin123"
        /** 固定测试账号：用户显式要求的永久会员种子账号（仅此处硬编码明文） */
        const val TEST_ACCOUNT_PHONE = "19371581808"
        const val TEST_ACCOUNT_PASSWORD = "201325"
    }
}
