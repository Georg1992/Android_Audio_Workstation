# Project ProGuard / R8 rules. Keep this list curated to what we actually use.

# Preserve symbols that are referenced through reflection by JNI from C++ (engine bridge).
-keepclassmembers class com.georgv.audioworkstation.engine.NativeEngine {
    *;
}

# Hilt: generated component classes are referenced through reflection at runtime.
-keep class dagger.hilt.** { *; }
-keep class androidx.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.GeneratedComponent { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager { *; }
-dontwarn dagger.hilt.**

# Room: keep generated DAO/Database implementations and entity types.
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# Oboe / native audio: the JNI layer only sees these via dlsym, so keep the bridge surface.
-keep class com.georgv.audioworkstation.engine.** { *; }
-dontwarn com.google.oboe.**

# Compose runtime / lifecycle helpers shouldn't be obfuscated through reflection paths.
-dontwarn kotlinx.coroutines.flow.**

# Keep crash-friendly stack traces in shipped builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
