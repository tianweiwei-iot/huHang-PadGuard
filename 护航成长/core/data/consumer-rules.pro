# Room 数据库实体与 DAO 不能被混淆
-keep class com.padguard.core.data.db.** { *; }
-keepclassmembers class com.padguard.core.data.db.** { *; }
# 策略模型参与序列化，保留构造器
-keep class com.padguard.core.data.model.** { *; }

# kotlinx.serialization：本模块策略模型（PolicyPackage 等）序列化器与 descriptor 必须保留（A3）
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclasseswithmembers class com.padguard.core.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.padguard.core.data.model.**$$serializer { *; }
-keepclassmembers class com.padguard.core.data.model.**$$serializer {
    *** descriptor;
}
