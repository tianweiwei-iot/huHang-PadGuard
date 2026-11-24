package com.padguard.child.guard

/**
 * 卸载入口识别器（**只读包名与窗口类名，不读界面内容**）。
 *
 * ## 为什么必须单独有它
 * 事故链路（2026-11-02）：孩子在**系统桌面上长按「护航成长」图标 → 弹出菜单 → 卸载**。
 * 这条路径产生的窗口事件包名是**桌面启动器**（如 `com.huawei.android.launcher`），
 * 而闸门原本只认两类包：
 *  - 系统设置各 ROM 包名（[OperationGuardAccessibilityService] 的 `GUARDED_SETTINGS_PACKAGES`）
 *  - `*packageinstaller*` / 安全中心（[AppGuardPolicyStore.isInstallerPackage]）
 * 桌面既不在其一，也不在其二 —— 于是 `decide()` 返回 null，**直接放行**，
 * 连"弹密码框"的机会都没有。手机端普遍存在的"桌面直卸"通路就此成为绕过口。
 *
 * ## 判定口径
 * 1. 类名带卸载语义（`UninstallerActivity` / `UninstallAppProgress` / `DeletePackage`）→ 命中；
 * 2. 包名是**当前默认桌面**，且窗口类名不是桌面主页本身 → 命中。
 *
 * 第 2 条的依据：桌面主页（HOME Activity）在长按图标时不会切换；
 * 长按弹出的菜单、文件夹、桌面设置页都是**另一个窗口**，其窗口类名必然不同于主页面的类名。
 * 因此"同一个桌面包名 + 非主页类名"就是"桌面弹出了一层浮层"的可靠信号，
 * 而这层浮层正是"应用信息 / 卸载 / 移除"的唯一入口。
 *
 * ## 为什么不用更"精确"的做法（读取界面文字找"卸载"二字）
 * 那需要打开 `canRetrieveWindowContent`，与无障碍服务对家长的公开承诺
 * （"不会读取任何界面内容"）直接冲突，也会把整窗文本暴露给进程。
 * 这里选择"宁可多拦一层浮层，也不读一个字的界面内容"：
 * 代价是家长解锁窗口内才能给桌面换壁纸 / 摆小部件（属"改系统设置"，本来也该要密码）。
 *
 * ## 与其它判据的关系
 * 本类只负责"这个窗口是不是桌面卸载入口"，是否拦截仍由调用方按
 * `AppGuardPolicyStore.uninstallNeedsPassword()`（家长开关）与密码闸状态决定 —— 各司其职。
 */
object UninstallEntryDetector {

    /** 类名里出现这些片段，就是卸载/删除相关的界面（覆盖 AOSP、EMUI、MIUI、ColorOS 等）。 */
    private val UNINSTALL_CLASS_HINTS = listOf(
        "uninstall",
        "deletepackage",
        "delete_package",
        "removepackage"
    )

    /**
     * 单桌面版本（保留旧签名，便于调用方与测试按"当前默认桌面"传参）。
     *
     * @param pkg 窗口所属包名（无障碍事件里的 `packageName`）
     * @param className 窗口类名（无障碍事件里的 `className`）
     * @param launcherPackage 当前默认桌面包名；解析不到时传 null（此时只按类名判）
     * @param launcherHomeClass 默认桌面的 HOME Activity 类名；解析不到时传 null
     */
    fun isUninstallSurface(
        pkg: String,
        className: String,
        launcherPackage: String?,
        launcherHomeClass: String?
    ): Boolean = isUninstallSurface(
        pkg = pkg,
        className = className,
        launcherHomeClasses = if (launcherPackage != null && launcherHomeClass != null) {
            mapOf(launcherPackage to launcherHomeClass)
        } else {
            emptyMap()
        }
    )

    /**
     * 多桌面版本（R-P2-8）：一次传入"桌面包名 → 主页类名"。
     *
     * ## 为什么不能只认"当前默认桌面"
     * 早期实现只解析 `resolveActivity(CATEGORY_HOME, MATCH_DEFAULT_ONLY)` 拿到的那**一个**包，
     * 于是有两个漏判口子：
     * 1. **装了多个桌面**（系统桌面 + 第三方桌面 + 儿童桌面）时，只有默认那一个被 arm；
     *    孩子把默认桌面切成另一个（设置里能改，且不需要任何权限），长按卸载就走通了；
     * 2. **运行期切换默认桌面**后，无障碍服务里缓存的值是服务连接那一刻解析的，
     *    不重启服务就永远指向旧包名 —— 同样等于没有防护。
     *
     * 现在由服务把"所有声明了 CATEGORY_HOME 的包"都 arm 进来，并在窗口事件里定期重解析，
     * 两个口子一起堵。判定口径完全不变：仍然只比包名与类名，不读界面内容。
     */
    fun isUninstallSurface(
        pkg: String,
        className: String,
        launcherHomeClasses: Map<String, String>
    ): Boolean {
        if (pkg.isBlank()) return false
        val cls = className.trim()

        // ① 类名本身就是卸载界面：与包名无关，任何人都拦得住
        if (cls.isNotEmpty() && UNINSTALL_CLASS_HINTS.any { it in cls.lowercase() }) return true

        // ② 桌面弹出的浮层（长按菜单 / 文件夹 / 桌面设置）
        val homeClass = launcherHomeClasses[pkg] ?: return false
        // 桌面主页本身绝不拦（否则孩子一按 HOME 就被弹密码框，设备直接不可用）
        if (cls.isEmpty()) return false
        // 该桌面的主页类名未知时无法区分"主页"与"浮层"，只保留第 ① 条，避免把桌面整体拦死
        if (homeClass.isBlank()) return false
        return !sameActivity(cls, homeClass)
    }

    /**
     * 类名比较：无障碍事件里的 className 可能是全限定名，也可能是相对名（`.Launcher`），
     * 直接 `==` 会在部分 ROM 上永远不相等，导致"主页被当成浮层"反复弹密码框。
     * 这里同时比全名与简单名，任一对上即视为同一 Activity。
     */
    private fun sameActivity(a: String, b: String): Boolean {
        if (a == b) return true
        val simpleA = a.substringAfterLast('.')
        val simpleB = b.substringAfterLast('.')
        return simpleA.isNotEmpty() && simpleA == simpleB
    }
}
