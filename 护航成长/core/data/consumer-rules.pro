# Room 数据库实体与 DAO 不能被混淆
-keep class com.padguard.core.data.db.** { *; }
-keepclassmembers class com.padguard.core.data.db.** { *; }
# 策略模型参与序列化，保留构造器
-keep class com.padguard.core.data.model.** { *; }
