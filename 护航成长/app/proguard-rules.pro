# ====== 设备管控核心 ======
# DeviceAdminReceiver 由系统反射实例化
-keep class * extends android.app.admin.DeviceAdminReceiver { <init>(); }
# DevicePolicyManager 相关反射
-keep class com.padguard.core.engine.device.** { *; }
# 策略模型参与序列化，构造器与字段必须保留
-keep class com.padguard.core.engine.policy.model.** { *; }
-keep class com.padguard.core.data.model.** { *; }
# Room
-keep class com.padguard.core.data.db.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }

# ====== MQTT (Eclipse Paho) ======
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.internal.**
-dontwarn javax.naming.**
-dontwarn org.apache.harmony.javax.naming.**
# Paho 使用反射实例化网络模块
-keepnames class org.eclipse.paho.client.mqttv3.internal.NetworkModuleService

# ====== kotlinx.serialization（策略包 PolicyPackage 等 @Serializable 模型，A3）======
# release 下若生成的 $$serializer 及其 descriptor 被混淆/裁剪，反序列化会静默失败。
# 必须保留序列化器类与 descriptor 字段（官方推荐规则集）。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.padguard.** {
    *** Companion;
    *** INSTANCE;
}
-keepclasseswithmembers class com.padguard.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.padguard.**$$serializer { *; }
-keepclassmembers class com.padguard.**$$serializer {
    *** descriptor;
}

# ====== 无障碍服务（降级管控模式）======
-keep class * extends android.accessibilityservice.AccessibilityService { <init>(); }

# 移除日志（release 构建不打日志，减少信息泄露面）
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
