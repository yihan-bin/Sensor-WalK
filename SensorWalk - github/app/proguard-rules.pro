# 文件: app/proguard-rules.pro
# 修改历史 (Modification History):
# ------------------------------------------------------------------------------
# YYYY-MM-DD - [你的名字/ID] - [简述上次修改，AI无需填写]
# 2025-07-30 - Gemini-AI - [V1] 为所有数据模型和网络模型添加精确的保留规则，防止Release构建时序列化失败。
# 2025-07-30 - Gemini-AI - [V2] 规则保持不变。V1的规则已足够健壮，覆盖了Kotlin反射、序列化和网络库，满足本次重构需求。

#------------- 通用Kotlin规则 -------------
-dontwarn kotlin.**
-dontwarn kotlinx.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-keep class kotlin.jvm.internal.DefaultConstructorMarker { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,Kotlin*

#------------- Kotlin Coroutines -------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler
-keepclassmembers class kotlinx.coroutines.flow.internal.ChannelFlow {
    *** owner;
}

#------------- ★★★ 核心修复: 全面保留Kotlin反射及内部依赖，解决 ExceptionInInitializerError ★★★ -------------
# Ktor, Netty, Kotlinx.Serialization 严重依赖这些反射API，必须保留
-keep,includecodemembernames class kotlin.reflect.** { *; }
-keep,includecodemembernames class kotlin.jvm.internal.** { *; }
-keep,includecodemembernames class kotlin.sequences.** { *; }
-keep,includecodemembernames class kotlin.collections.** { *; }
-keep,includecodemembernames class kotlin.text.** { *; }
-keep,includecodemembernames class kotlin.io.** { *; }
-keep,includecodemembernames public class * implements kotlin.reflect.KCallable { *; }
-keep,includecodemembernames public class * implements kotlin.reflect.KProperty { *; }
-keep,includecodemembernames public class * implements kotlin.reflect.KFunction { *; }
-keep class **$Companion { *; }
-keep class kotlin.reflect.jvm.internal.impl.** { *; }
-dontwarn kotlin.reflect.jvm.internal.impl.**

#------------- Kotlinx Serialization -------------
-keep,includecodemembernames class kotlinx.serialization.** { *; }
-keepclassmembers,allowobfuscation class * {
    @kotlinx.serialization.Serializable <methods>;
    public static final kotlinx.serialization.KSerializer serializer(...);
}
-keep class * { @kotlinx.serialization.Serializable *; }
-keep @kotlinx.serialization.Serializable class * { *; }

# ★★★ 本次修改：为所有数据模型和网络模型添加精确的保留规则 ★★★
-keep class com.example.sensorwalk.data.** { *; }
-keep class com.example.sensorwalk.connectivity.** { *; }
-keepclassmembers class com.example.sensorwalk.data.** { *; }
-keepclassmembers class com.example.sensorwalk.connectivity.** { *; }

#------------- Ktor & Netty -------------
-keep,includecodemembernames class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }
-keepnames,includecodemembernames class io.ktor.**
-dontwarn io.ktor.**

-keep,includecodemembernames class io.netty.** { *; }
-keepclassmembers class io.netty.** { *; }
-dontwarn io.netty.**
-keep class org.slf4j.impl.StaticLoggerBinder { *; }
-dontwarn org.slf4j.impl.StaticLoggerBinder

#------------- Hilt & Dagger -------------
-keep class * extends androidx.lifecycle.ViewModel
-keep class **_HiltModules* { *; }
-keep class dagger.hilt.internal.aggregatedroot.codegen.*
-keep class hilt_aggregated_deps.*
-keep class com.example.sensorwalk.SensorWalkApplication_HiltComponents** { *; }
-keep,allowobfuscation @dagger.hilt.android.AndroidEntryPoint class *
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }

#------------- 其他库 -------------
-keep class org.apache.commons.math3.** { *; }
-keep public class com.patrykandpatrick.vico.** { *; }
-keep interface com.patrykandpatrick.vico.** { *; }
-dontwarn com.patrykandpatrick.vico.**
