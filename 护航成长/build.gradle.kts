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

// ---------------------------------------------------------------------------
// 构建互斥：禁止同一项目被并发构建（2026-10-14 加，针对实际发生的构建故障）
//
// 与家长端同一处故障、同一套处理。Kotlin 增量编译缓存（caches-jvm 下
// PersistentEnumerator 系列的 *.tab）**不是并发安全的**：同一模块被两次构建并发
// 访问即写坏，报
//   CorruptedException: PersistentEnumerator storage corrupted ...source-to-classes.tab
// 回退全量编译时又因输出目录被另一进程占用而无法清空
//   java.io.IOException: Could not delete ...\tmp\kotlin-classes\debug\com
// 构建彻底中断且无法自愈。本端曾于 2026-10-09 实测命中该故障（core-data 模块）。
//
// 处理：OS 级文件锁把同一项目的构建串行化，后发构建排队等待；锁随进程退出自动
// 释放，不会永久死锁；仅在执行构建任务时加锁，IDE 的 Gradle 同步不受影响。
// 临时绕过（不推荐）：./gradlew <task> -Ppadguard.buildLock=off
// ---------------------------------------------------------------------------
run {
    val lockRoot = rootDir
    val lockLogger = logger
    val lockEnabled = providers.gradleProperty("padguard.buildLock").orNull != "off"
    val lockFile = File(lockRoot, ".gradle/padguard-build.lock")
    gradle.taskGraph.whenReady {
        if (!lockEnabled) return@whenReady
        lockFile.parentFile?.mkdirs()
        val raf = java.io.RandomAccessFile(lockFile, "rw")
        val deadline = System.currentTimeMillis() + 15 * 60 * 1000L
        var held: java.nio.channels.FileLock? = null
        var notified = false
        while (held == null) {
            held = runCatching { raf.channel.tryLock() }.getOrNull()
            if (held != null) break
            if (!notified) {
                lockLogger.lifecycle(
                    "PadGuard: 同一项目已有构建在运行，本次构建排队等待，" +
                        "避免 Kotlin 增量编译缓存被并发写入而损坏…"
                )
                notified = true
            }
            if (System.currentTimeMillis() > deadline) {
                raf.close()
                throw GradleException(
                    "等待构建锁超时（15 分钟）：$lockFile。若确认没有其它构建在运行，" +
                        "删除该文件后重试；也可用 -Ppadguard.buildLock=off 临时绕过。"
                )
            }
            Thread.sleep(1000L)
        }
        gradle.buildFinished {
            runCatching { held?.release() }
            runCatching { raf.close() }
        }
    }
}
