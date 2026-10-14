plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
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
