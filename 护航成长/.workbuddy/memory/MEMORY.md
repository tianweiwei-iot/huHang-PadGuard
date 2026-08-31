# 项目长期记忆（MEMORY.md）

> 由 2026-08-30 起维护。仅记录跨会话有价值、需长期遵守的项目事实与约定。

## 关键环境约束（务必牢记）

- **项目路径含中文**（`D:\Project\Android\护航PadGuard\护航成长`）。
- **Gradle 的 JVM 单元测试在 Windows 上无法直接跑**：test worker 无法从含非 ASCII 字符的 classpath 加载类，任何 `src/test` 单元测试都会报 `ClassNotFoundException: <测试类>`。这与测试代码无关，是路径编码问题。
- **已修复**：根 `build.gradle.kts` 末尾有 `allprojects { buildDir = File("D:/builds/padguard/${project.name}") }`，把构建产物重定向到 ASCII 路径 `D:/builds/padguard/<moduleName>/`。此后 `:core:common:testDebugUnitTest` 等可正常跑。
  - 还原方式：删除该 `allprojects { buildDir = ... }` 段。
  - 注意：构建产物现在在 `D:/builds/padguard/...` 而非各模块 `build/`，IDE/CI 如需默认位置须相应调整。

## 构建与验证常用命令

- debug 全量构建：`./gradlew :app:assembleDebug`
- release 全量构建（含 R8 混淆）：`./gradlew :app:assembleRelease`（**不要加 `--offline`**，release 变体需联网拉 `com.android.tools.lint:lint-gradle` 做 `extractReleaseAnnotations`，离线缓存缺失会失败）
- 单元测试：`./gradlew :core:common:testDebugUnitTest`

## 代码约定（来自 2026-08-30 修复）

- 所有 `ApiResult.Success` 调用都必须传 `serverTime`（用于时钟校准，见 `ApiResult` sealed interface）。
- `simulateNetwork()` 调用必须显式写泛型实参。
- 不要在 Activity 里覆写已废弃的 `onBackPressed()`，统一用 `OnBackPressedDispatcher` 注册 `OnBackPressedCallback`。
- Android 主题里**不能**写 `android:windowShowWhenLocked` / `android:windowTurnScreenOn`（框架未暴露这两个 theme attr）；锁屏置顶/点亮屏幕改由 Activity 代码 `setShowWhenLocked(true)` / `setTurnScreenOn(true)` 实现。
- 反查 DeviceAdminReceiver 用 `DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED`（不是 `DevicePolicyManager` 上的常量）。

## SDK / 版本

- compileSdk=35, minSdk=26, targetSdk=35；AGP 8.7.3, Kotlin 2.0.21, Hilt 2.52。
- Android SDK 位于 `E:\Tools\Android\SDK`。
- 国内镜像（阿里云）已配在 `settings.gradle.kts`。
