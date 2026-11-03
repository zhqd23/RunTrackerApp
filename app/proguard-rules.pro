# 默认混淆规则（release 开启 minify 时使用）
-keepattributes Signature
-keepattributes *Annotation*

# osmdroid 保留规则
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Room 生成的代码
-keep class * extends androidx.room.RoomDatabase
