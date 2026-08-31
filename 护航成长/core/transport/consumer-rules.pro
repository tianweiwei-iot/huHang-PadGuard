# Paho MQTT 在 R8 下需要保留反射入口与序列化类
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**
-dontwarn javax.naming.**
-keep class com.padguard.core.transport.dto.** { *; }
