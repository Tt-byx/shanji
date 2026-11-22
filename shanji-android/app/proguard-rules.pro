# 闪记 release 混淆规则

# kotlinx.serialization：保留 serializer 工厂与生成的 $$serializer
-keepattributes Signature,*Annotation*, InnerClasses, EnclosingMethod
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.shanji.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.shanji.app.**$$serializer { *; }

# Retrofit / OkHttp（官方已适配 R8，仅补缺）
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Biometric
-dontwarn androidx.biometric.**
