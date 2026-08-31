# DevicePolicyManager 反射调用与策略模型需要保留
-keep class com.padguard.core.engine.policy.** { *; }
-keep class com.padguard.core.engine.device.** { *; }
# DeviceAdminReceiver 由系统反射实例化，必须保留无参构造
-keep class * extends android.app.admin.DeviceAdminReceiver { <init>(); }
