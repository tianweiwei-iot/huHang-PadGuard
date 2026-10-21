plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}

// ---------------------------------------------------------------------------
// 构建产物输出目录：重定向到与项目「同盘符」的纯 ASCII 路径
//
// 硬约束（与孩子端同一处处理，已实测）：
//  ① 项目路径含中文（…\项目\护航控制端）。Windows 上 Gradle 的 JVM test worker
//     无法从含非 ASCII 字符的 classpath 条目加载类，会让所有 src/test 单元测试统一报
//     ClassNotFoundException（与测试代码无关，纯粹是路径编码问题）。
//     → 输出路径必须纯 ASCII。这也是此前家长端 SafeNavTest 始终 initializationError /
//     ClassNotFoundException 的根因：构建产物落在中文路径下，测试 worker 的
//     URLClassLoader 因中文 URL 未被正确编码而找不到测试类。
//  ② KSP 生成代码时会对「生成目录」与「模块源码目录」调用 Path.relativize；两者不在同一
//     盘符时，Windows 上会直接抛 IllegalArgumentException: this and base files have
//     different roots。→ 输出路径必须与项目位于同一盘符。
//
// 因此：输出根目录 = <项目所在盘符>:/builds/padguard-parent（与家长端区分，避免产物目录冲突），
//       可用 gradle.properties 的 padguard.build.root 覆盖（不同盘符会快速失败并给出提示）。
//       若项目本身已位于纯 ASCII 路径，则不做任何重定向，保持 Gradle 默认 build/ 行为。
// ---------------------------------------------------------------------------
val projectPath = rootProject.projectDir.absolutePath
val projectDrive = projectPath.substringBefore(':')

if (projectPath.all { it.code < 0x80 }) {
    logger.lifecycle("PadGuard: 项目路径为纯 ASCII，使用 Gradle 默认构建目录。")
} else {
    val buildRoot = rootProject.file(
        providers.gradleProperty("padguard.build.root").orNull ?: "$projectDrive:/builds/padguard-parent"
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
// 已实测的故障链：
//   Kotlin 增量编译缓存（build/kotlin/.../caches-jvm 下的 PersistentEnumerator 系列
//   *.tab 文件）**不是并发安全的**。当同一模块的两次构建并发运行时，两个编译进程
//   会同时打开同一份缓存，把它写坏：
//     CorruptedException: PersistentEnumerator storage corrupted ...source-to-classes.tab
//   之后编译器回退全量编译，需要先清空输出目录，此时该目录又被另一进程占用：
//     java.io.IOException: Could not delete ...\app\build\tmp\kotlin-classes\debug\com
//   于是构建彻底中断，并且**无法自愈**——缓存已坏，此后每次构建都在同一处失败。
//
// 触发场景（有 Android Studio 日志实证）：
//   Android Studio 在**前一次 :app:assembleDebug 尚未结束**时又发起一次（实测每次
//   Run 连发两次，间隔 6~28 秒）；或 IDE 与命令行同时构建同一项目。
//
// 处理：用 OS 级文件锁把同一项目的构建串行化——后发构建排队等待，而不是并发写入。
//   锁由操作系统在进程退出时自动释放，不会因崩溃而永久死锁。
//   仅在真正要执行构建任务时加锁（taskGraph.whenReady），因此 IDE 的 Gradle 同步
//   （只做模型查询、不执行任务）不受影响。
//   临时绕过（不推荐，仅在排查问题时使用）：./gradlew <task> -Ppadguard.buildLock=off
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
