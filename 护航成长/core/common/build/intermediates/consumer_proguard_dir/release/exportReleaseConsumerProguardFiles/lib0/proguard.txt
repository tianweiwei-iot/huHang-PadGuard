# 保留序列化模型类名，避免 R8 混淆后反序列化失败
-keepclassmembers class com.padguard.core.common.** {
    *** Companion;
}
-keepclasseswithmembers class com.padguard.core.common.** {
    <init>(...);
}
