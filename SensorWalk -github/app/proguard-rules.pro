#------------- 通用规则 -------------
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep class kotlin.Metadata { *; }

#------------- Kotlinx Coroutines (标准规则) -------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler

#------------- Kotlinx Serialization (标准规则) -------------
# ★ 优化: 更精确的规则，保留序列化器和被注解的类
-keepclassmembers,allowobfuscation class * {
    @kotlinx.serialization.Serializable <methods>;
    public static final kotlinx.serialization.KSerializer serializer(...);
}
-keep class * { @kotlinx.serialization.Serializable *; }
-keep @kotlinx.serialization.Serializable class * { *; }
# ★ 优化: 保留你的数据包模型和它们的内部类，以防万一
-keep class com.example.sensorwalk.connectivity.DataPacket { *; }
-keep class com.example.sensorwalk.connectivity.DataPacket$* { *; }
-keep class com.example.sensorwalk.data.SensorDataPoint { *; }


#------------- Ktor (客户端和服务端) - 优化后的规则 -------------
# 保留Ktor的核心引擎和插件，它们大量使用反射
-keep class io.ktor.client.engine.cio.** { *; }
-keep class io.ktor.server.netty.** { *; }
-keep class io.ktor.client.plugins.** { *; }
-keep class io.ktor.server.plugins.** { *; }
-keep class io.ktor.websocket.** { *; }
-keep class io.ktor.serialization.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn io.netty.**

#------------- Hilt & Dagger (标准规则) -------------
-keep class * extends androidx.lifecycle.ViewModel
-keep class **_HiltModules* { *; }
-keep class dagger.hilt.internal.aggregatedroot.codegen.*
-keep class hilt_aggregated_deps.*
-keep class com.example.sensorwalk.SensorWalkApplication_HiltComponents** { *; }
-keep,allowobfuscation @dagger.hilt.android.AndroidEntryPoint class *
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }

#------------- Apache Commons Math -------------
# 这个库也被用于计算，保留必要的统计和分析类
-keep class org.apache.commons.math3.stat.** { *; }
-keep class org.apache.commons.math3.analysis.** { *; }
-keep class org.apache.commons.math3.distribution.** { *; }

#------------- Vico (图表) -------------
-keep public class com.patrykandpatrick.vico.** { *; }
-keep interface com.patrykandpatrick.vico.** { *; }
-dontwarn com.patrykandpatrick.vico.**
