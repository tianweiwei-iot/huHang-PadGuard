// 根构建文件：仅声明插件，不引入实现（交由各模块按需要 apply）
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// ---------------------------------------------------------------------------
// 构建产物输出目录：重定向到与项目「同盘符」的纯 ASCII 路径
//
// 这里有两条互相制约的硬约束，任何一条不满足构建都会失败：
//  ① 项目路径含中文（…\项目\护航成长）。Windows 上 Gradle 的 JVM test worker 无法从含
//     非 ASCII 字符的 classpath 条目加载类，会让所有 src/test 单元测试统一报
//     ClassNotFoundException（与测试代码无关，纯粹是路径编码问题）。→ 输出路径必须纯 ASCII。
//  ② KSP 生成代码时会对「生成目录」与「模块源码目录」调用 Path.relativize；两者不在同一
//     盘符时，Windows 上会直接抛
//     IllegalArgumentException: this and base files have different roots。
//     → 输出路径必须与项目位于同一盘符（历史配置写死 D:，项目迁到 E: 后即因此失败）。
//
// 因此：输出根目录 = <项目所在盘符>:/builds/padguard，默认自动推导；
//       可用 gradle.properties 的 padguard.build.root 覆盖（不同盘符会快速失败并给出提示）。
//       若项目本身已位于纯 ASCII 路径，则不做任何重定向，保持 Gradle 默认 build/ 行为。
// ---------------------------------------------------------------------------
val projectPath = rootProject.projectDir.absolutePath
val projectDrive = projectPath.substringBefore(':')

if (projectPath.all { it.code < 0x80 }) {
    logger.lifecycle("PadGuard: 项目路径为纯 ASCII，使用 Gradle 默认构建目录。")
} else {
    val buildRoot = rootProject.file(
        providers.gradleProperty("padguard.build.root").orNull ?: "$projectDrive:/builds/padguard"
    )
    val buildRootDrive = buildRoot.absolutePath.substringBefore(':')
    require(buildRootDrive.equals(projectDrive, ignoreCase = true)) {
        "padguard.build.root 必须与项目位于同一盘符。" +
            "KSP 会对生成目录与模块源码目录做 Path.relativize，跨盘符会抛 " +
            "IllegalArgumentException。项目在 $projectDrive:，当前配置为 ${buildRoot.absolutePath}。"
    }
    logger.lifecycle("PadGuard: 构建产物将输出到 ${buildRoot.absolutePath}")
    allprojects {
        // 以项目路径而非项目名作目录键，避免同名模块互相覆盖构建产物
        val moduleKey = path.removePrefix(":").replace(':', '-').ifEmpty { "root" }
        layout.buildDirectory.set(File(buildRoot, moduleKey))
    }
}
