# ShelfDrive v129 AAOS crash evidence

Captured on 2026-08-28 from the local Android Automotive OS emulator used to test the same release package and version rejected by Google Play.

This is local emulator evidence, not a Google Play reviewer log. It is nevertheless a direct reproduction on AAOS of the repeated startup crash in version code 129, with three independent system crash records for the same exception.

## Test environment

- Package: `com.jedibk.shelfdrive`
- Installed version code: `129`
- Device: `Android Automotive SDK for arm64`
- Android release / API: Android 13 / API 33
- Build fingerprint: `google/sdk_gcar_arm64/emulator_car64_arm64:13/TEA1.250515.001/13505934:userdebug/dev-keys`

The following commands recorded the environment and extracted Android's persisted app-crash entries:

```sh
adb -s emulator-5554 shell getprop ro.build.fingerprint
adb -s emulator-5554 shell getprop ro.product.model
adb -s emulator-5554 shell getprop ro.build.version.release
adb -s emulator-5554 shell getprop ro.build.version.sdk
adb -s emulator-5554 shell pm list packages --show-versioncode
adb -s emulator-5554 shell dumpsys dropbox --print data_app_crash
```

## Persisted v129 crash records

Android DropBox contained three consecutive crashes from version code 129. The first occurred 191 ms after process start; Android restarted the service, then the same failure recurred after 110 ms and 91 ms.

### 2026-08-28 08:23:08

```text
Process: com.jedibk.shelfdrive
PID: 4399
UID: 1010204
Frozen: false
Flags: 0x28d8be44
Package: com.jedibk.shelfdrive v129 (0.1)
Foreground: No
Process-Runtime: 191
Build: google/sdk_gcar_arm64/emulator_car64_arm64:13/TEA1.250515.001/13505934:userdebug/dev-keys
Loading-Progress: 1.0
Dropped-Count: 0

B3.m: lateinit property mediaManager has not been initialized
    at com.audiobookshelf.app.player.PlayerNotificationService.getMediaManager(SourceFile:8)
    at C0.V.onCapabilitiesChanged(SourceFile:142)
    at android.net.ConnectivityManager$NetworkCallback.onAvailable(ConnectivityManager.java:3799)
    at android.net.ConnectivityManager$NetworkCallback.onAvailable(ConnectivityManager.java:3776)
    at android.net.ConnectivityManager$CallbackHandler.handleMessage(ConnectivityManager.java:4100)
    at android.os.Handler.dispatchMessage(Handler.java:106)
    at android.os.Looper.loopOnce(Looper.java:201)
    at android.os.Looper.loop(Looper.java:288)
    at android.os.HandlerThread.run(HandlerThread.java:67)
```

### 2026-08-28 08:23:11

```text
Process: com.jedibk.shelfdrive
PID: 4438
UID: 1010204
Frozen: false
Flags: 0x28d8be44
Package: com.jedibk.shelfdrive v129 (0.1)
Foreground: No
Process-Runtime: 110
Build: google/sdk_gcar_arm64/emulator_car64_arm64:13/TEA1.250515.001/13505934:userdebug/dev-keys
Loading-Progress: 1.0
Dropped-Count: 0

B3.m: lateinit property mediaManager has not been initialized
    at com.audiobookshelf.app.player.PlayerNotificationService.getMediaManager(SourceFile:8)
    at C0.V.onCapabilitiesChanged(SourceFile:142)
    at android.net.ConnectivityManager$NetworkCallback.onAvailable(ConnectivityManager.java:3799)
    at android.net.ConnectivityManager$NetworkCallback.onAvailable(ConnectivityManager.java:3776)
    at android.net.ConnectivityManager$CallbackHandler.handleMessage(ConnectivityManager.java:4100)
    at android.os.Handler.dispatchMessage(Handler.java:106)
    at android.os.Looper.loopOnce(Looper.java:201)
    at android.os.Looper.loop(Looper.java:288)
    at android.os.HandlerThread.run(HandlerThread.java:67)
```

### 2026-08-28 08:23:12

```text
Process: com.jedibk.shelfdrive
PID: 4484
UID: 1010204
Frozen: false
Flags: 0x28d8be44
Package: com.jedibk.shelfdrive v129 (0.1)
Foreground: No
Process-Runtime: 91
Build: google/sdk_gcar_arm64/emulator_car64_arm64:13/TEA1.250515.001/13505934:userdebug/dev-keys
Loading-Progress: 1.0
Dropped-Count: 0

B3.m: lateinit property mediaManager has not been initialized
    at com.audiobookshelf.app.player.PlayerNotificationService.getMediaManager(SourceFile:8)
    at C0.V.onCapabilitiesChanged(SourceFile:142)
    at android.net.ConnectivityManager$NetworkCallback.onAvailable(ConnectivityManager.java:3799)
    at android.net.ConnectivityManager$NetworkCallback.onAvailable(ConnectivityManager.java:3776)
    at android.net.ConnectivityManager$CallbackHandler.handleMessage(ConnectivityManager.java:4100)
    at android.os.Handler.dispatchMessage(Handler.java:106)
    at android.os.Looper.loopOnce(Looper.java:201)
    at android.os.Looper.loop(Looper.java:288)
    at android.os.HandlerThread.run(HandlerThread.java:67)
```

`B3.m` is the minified release name of Kotlin's `UninitializedPropertyAccessException`. The unminified message and application frame identify the failed property and component exactly.

## Verified root cause

Version 129 registered `ConnectivityManager.NetworkCallback` before assigning the service's `lateinit mediaManager` property. Android may immediately deliver `onAvailable` / `onCapabilitiesChanged` when a validated vehicle network already exists. That callback accessed `mediaManager` while service initialization was still in progress, throwing `UninitializedPropertyAccessException`. Android then restarted the bound media service and repeated the same race, matching the three rapid crash records above.

The pre-fix order was:

```text
registerNetworkCallback(...)
...
mediaManager = MediaManager(apiHandler, context)
```

## Resolution in version 130 source

The initialization order is now explicit in `PlayerNotificationService.onCreate`:

1. Construct and assign `mediaManager`.
2. Build the network request.
3. Register `networkCallback` only after `mediaManager` exists.

See [`PlayerNotificationService.kt`](../android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationService.kt), where `mediaManager = MediaManager(apiHandler, ctx)` precedes `connectivityManager.registerNetworkCallback(networkRequest, networkCallback)`.

Because `mediaManager` is assigned before the framework can invoke the callback, even an immediate callback for an already-active AAOS network can safely use it. A Robolectric service-startup test also constructs the complete service lifecycle and verifies that its media session is initialized: [`PlayerNotificationServiceStartupTest.kt`](../android/app/src/test/java/com/audiobookshelf/app/player/PlayerNotificationServiceStartupTest.kt).

## Submission wording constraint

The Play response may accurately state that the v129 crash was reproduced and traced to a network-callback initialization race, and that v130 registers the callback only after its dependencies are initialized. It should not describe these local records as logs supplied by Google Play or as originating from Google's reviewer device.
