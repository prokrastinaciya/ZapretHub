# Add project specific ProGuard rules here.

# ─── ProxyService (foreground service, must not be obfuscated) ───
-keep class com.amurcanov.tgwsproxy.ProxyService { *; }

# ─── DataStore ───
-keep class androidx.datastore.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# WorkManager's Room 2.6 database is instantiated through reflection. Its
# consumer rule keeps the class name, but R8 full mode can remove this ctor.
-keepclassmembers class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}

# ─── Coroutines ───
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }

# ─── Compose ───
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep @androidx.compose.runtime.Composable class * { *; }

# ─── Keep native .so loaders ───
-keepclasseswithmembernames class * {
    native <methods>;
}
