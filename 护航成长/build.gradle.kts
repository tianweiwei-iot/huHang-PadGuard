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

// 构建产物重定向到 ASCII 路径。
// 背景：项目目录含中文（护航PadGuard\护航成长）。Gradle 的 test worker 在 Windows 上
// 无法从含非 ASCII 字符的 classpath 条目里通过 URL 加载类，导致所有 JVM 单元测试
// 一律报 ClassNotFoundException（与测试代码本身无关，单纯是路径编码问题）。
// 把 build 目录挪到纯 ASCII 路径即可让单元测试（及任何 JVM 测试）正常运行。
// 如需还原，删除本段即可（构建产物会回到各模块默认 build/ 目录）。
allprojects {
    buildDir = File("D:/builds/padguard/${project.name}")
}
