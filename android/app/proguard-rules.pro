# ShelfDrive ProGuard / R8 rules for release minify.
# Goal: keep code paths that are reached via reflection or by name from
# native bindings, Capacitor bridge, Jackson, MediaSession, ExoPlayer.

# Preserve crash-trace line numbers (small APK cost, big debugging win).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Release builds have no in-app diagnostic-log viewer. Remove routine Android
# log calls (and their potentially sensitive arguments) while retaining
# warnings/errors that are useful in Play crash and ANR reports.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Kotlin metadata is needed by Jackson's Kotlin module.
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# --- ExoPlayer ----------------------------------------------------------
# ExoPlayer and its MediaSession extension ship consumer rules. Avoid blanket
# keeps: they retain unreachable phone/offline services (including foreground-
# service bytecode) in this dedicated AAOS media artifact.
-dontwarn com.google.android.exoplayer2.**

# --- AndroidX media / MediaSession --------------------------------------
# Manifest entry points are roots automatically; AndroidX ships consumer rules.
-dontwarn androidx.media.**

# --- Jackson (JSON serialization) ---------------------------------------
# Jackson uses reflection on data classes. Keep our data layer + setters.
-keep class com.audiobookshelf.app.data.** { *; }
-keepclassmembers class com.audiobookshelf.app.data.** {
    <init>(...);
    <fields>;
}
# models.* are also Jackson data classes (User, DownloadItem, DownloadItemPart)
# deserialized by reflection — keep their fields/ctors or downloads + login break.
-keep class com.audiobookshelf.app.models.** { *; }
-keepclassmembers class com.audiobookshelf.app.models.** {
    <init>(...);
    <fields>;
}
-keep class com.fasterxml.jackson.** { *; }
# jackson-module-kotlin's inline readValue<T>() creates anonymous TypeReference
# subclasses at each call site. Preserve their generic signatures when they are
# live, but allow R8 to remove subclasses from the dormant phone/plugin source
# surface. A blanket keep here retained otherwise-unreachable plugin bytecode.
-keep,allowshrinking,allowoptimization,allowobfuscation class * extends com.fasterxml.jackson.core.type.TypeReference { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <methods>;
}
-dontwarn com.fasterxml.jackson.databind.**

# --- OkHttp -------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# --- Glide --------------------------------------------------------------
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }
-dontwarn com.bumptech.glide.**

# --- Paper (NoSQL) ------------------------------------------------------
# Paper persists Kotlin classes via Kryo, which uses reflection.
-keep class io.paperdb.** { *; }
-keep class com.audiobookshelf.app.data.** { *; }

# --- Service / receivers referenced from manifest -----------------------
-keep class com.audiobookshelf.app.player.PlayerNotificationService { *; }
-keep class com.audiobookshelf.app.SettingsActivity { *; }
-keep class com.audiobookshelf.app.accounts.ShelfDriveAuthenticatorService { *; }
