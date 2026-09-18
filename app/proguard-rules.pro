# SMS-L 的 R8 规则。
#
# 开启代码压缩后，R8 会剔除未被引用的 Compose / AndroidX / Material 代码，
# 显著减小 dex —— 系统生成的 vdex/odex 也会跟着变小。
# 下面只保留真正会被反射访问、R8 静态分析看不到的部分。

# ---------------------------------------------------------------- Room
# Room.databaseBuilder() 是按名字反射查找生成的实现类（<DatabaseClass>_Impl），
# 这个引用 R8 看不见，不保留的话运行时会抛
# "Cannot find implementation for ...SmsDatabase"。
-keep class * extends androidx.room.RoomDatabase

# 实体与 DAO 的注解需要保留，Room 在打开数据库时会读它们做校验。
-keepattributes *Annotation*
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# ---------------------------------------------------------------- 组件
# 四个组件都在 AndroidManifest 里声明，R8 会自动保留，
# 这里显式再写一遍是为了防止将来有人改动清单时被误删。
-keep class com.localsmsrelay.MainActivity { *; }
-keep class com.localsmsrelay.RelayService { *; }
-keep class com.localsmsrelay.BootReceiver { *; }
-keep class com.localsmsrelay.CopyOtpReceiver { *; }

# ---------------------------------------------------------------- OkHttp
# OkHttp 自带 consumer 规则，这里补齐它引用的可选 TLS provider 的告警抑制，
# 否则 R8 会因为找不到这些类而报错。
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 注：曾试过 -repackageclasses '' + -allowaccessmodification 想进一步压 dex，
# 实测 APK 反而大了约 15 KB（R8 本来就把类名混淆得很短，再重打包到根包只会变长），
# 因此不启用。
