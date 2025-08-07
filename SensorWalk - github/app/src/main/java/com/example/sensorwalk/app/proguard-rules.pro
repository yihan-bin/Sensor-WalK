#------------- 通用规则 -------------
-keepattributes Signature
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }

#------------- Kotlin Coroutines -------------
-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherFactory {
    private final java.lang.String a;
    public final java.util.List b;
}
-keep public class kotlinx.coroutines.android.AndroidDispatcherFactory
-keep public class kotlinx.coroutines.android.AndroidExceptionPreHandler

#------------- Kotlinx Serialization -------------
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable <methods>;
}
-keepclassmembers class * {
    @kotlinx.serialization.Transient <fields>;
}

#------------- Ktor (客户端和服务端) -------------
# Ktor 使用大量反射
-keep class io.ktor.** { *; }
-keepnames class io.ktor.**
-dontwarn io.ktor.**

#------------- Hilt & Dagger -------------
-keep class * extends androidx.lifecycle.ViewModel
-keep class **_HiltModules* { *; }
-keep class dagger.hilt.internal.aggregatedroot.codegen.*
-keep class hilt_aggregated_deps.*

#------------- Apache Commons Math -------------
# 通常不需要特殊规则，但如果遇到问题可以放开
# -keep class org.apache.commons.math3.** { *; }

#------------- Vico (图表) -------------
-keep public class com.patrykandpatrick.vico.** { *; }
-keep interface com.patrykandpatrick.vico.** { *; }

