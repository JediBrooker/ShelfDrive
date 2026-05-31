# ShelfDrive ProGuard / R8 rules for release minify.
# Goal: keep code paths that are reached via reflection or by name from
# native bindings, Capacitor bridge, Jackson, MediaSession, ExoPlayer.

# Preserve crash-trace line numbers (small APK cost, big debugging win).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin metadata is needed by Capacitor's reflection for plugin discovery.
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# --- Capacitor + Cordova ------------------------------------------------
# Capacitor finds plugins via reflection on @CapacitorPlugin / @PluginMethod.
-keep @com.getcapacitor.annotation.CapacitorPlugin class * { *; }
-keepclassmembers class * {
    @com.getcapacitor.PluginMethod *;
}
-keep class com.getcapacitor.** { *; }
-keep class org.apache.cordova.** { *; }

# Our own plugins are loaded by name from MainActivity.registerPlugin.
-keep class com.audiobookshelf.app.plugins.** { *; }

# --- ExoPlayer ----------------------------------------------------------
-keep class com.google.android.exoplayer2.** { *; }
-keep interface com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# --- AndroidX media / MediaSession --------------------------------------
-keep class androidx.media.** { *; }
-keep class android.support.v4.media.** { *; }
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

# --- AndroidX MediaRouter ------------------------------------------------
-dontwarn androidx.mediarouter.**

# --- Service / receivers referenced from manifest -----------------------
-keep class com.audiobookshelf.app.player.PlayerNotificationService { *; }
-keep class com.audiobookshelf.app.MainActivity { *; }
-keep class com.audiobookshelf.app.SettingsActivity { *; }
-keep class com.audiobookshelf.app.MediaPlayerWidget { *; }
-keep class com.audiobookshelf.app.media.** { *; }
-keep class com.audiobookshelf.app.managers.** { *; }
-keep class com.audiobookshelf.app.device.** { *; }
