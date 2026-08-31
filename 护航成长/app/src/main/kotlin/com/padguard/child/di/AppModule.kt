package com.padguard.child.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * 应用级协程作用域。
 *
 * 用于 BroadcastReceiver 这类"回调窗口极短但需要做异步落库"的场景：
 * Receiver 的 `onReceive` 返回后进程随时可能被回收，
 * 如果在 Receiver 里 `GlobalScope.launch`，进程被杀时协程直接消失，告警就丢了。
 * 配合 `goAsync()` 使用可以把存活窗口撑到工作完成。
 *
 * ## 为什么必须显式写 @Target（别删）
 * Kotlin 注解在属性上的落点优先级是 `param > property > field`。
 * 若不限定 @Target，默认目标包含 `PROPERTY`，那么字段注入写成
 * `@Inject @ApplicationScope lateinit var scope: CoroutineScope` 时，
 * 限定符会落到 **property** 上，而 Dagger 只读字段 —— 它会认为这里要注入一个
 * **无限定符**的 `CoroutineScope`，于是编译期报：
 * `[Dagger/MissingBinding] kotlinx.coroutines.CoroutineScope cannot be provided...`
 *
 * 迷惑点在于 `@Inject` 是 Java 注解（目标不含 PROPERTY）所以它落点是对的，
 * 表现为"同一行里一半注解生效一半不生效"，很容易往 Module 里瞎找问题。
 * 去掉 `PROPERTY` 后，注解只能落到字段，Hilt 才能正确匹配到本 Module 的绑定。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FUNCTION
)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * 用 [SupervisorJob] 而非普通 Job：
     * 一条告警落库失败不应该连带取消整个作用域，否则后续所有告警都写不进去，
     * 而且这种"静默瘫痪"在日志里几乎看不出来。
     *
     * 用 Dispatchers.Default 而不是 Main：这些任务全是 IO / 计算，
     * 放主线程会在开机广播风暴时明显拖慢启动。
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
