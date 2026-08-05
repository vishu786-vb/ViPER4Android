-keepattributes *Annotation*
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Shizuku — keep API classes and the provider so the binder works in release builds
-keep class rikka.shizuku.** { *; }
-keep class rikka.sui.** { *; }
# Preserve the hidden newProcess method accessed via reflection
-keepclassmembers class rikka.shizuku.Shizuku {
    public static *** newProcess(...);
}
