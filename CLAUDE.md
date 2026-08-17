# ShelfDrive

## Creating a signed release AAB

When asked to create/cut a new release build, follow this procedure exactly. Don't turn it into a discussion, don't redesign the signing setup, don't add steps.

**Precondition — check this first, every time:** this only works where the release keystore already exists on disk. `android/keystore.properties` is gitignored and never committed, so a fresh clone (including any ephemeral/remote session that just checked out this repo from git) will NOT have it. If `android/keystore.properties` is missing, or it's present but missing `storeFile`/`storePassword`/`keyAlias`/`keyPassword`, or the file it points `storeFile` at doesn't exist — stop and report exactly which one is missing. Do not generate a new keystore, do not ask for the signing passwords, do not print them, do not move them into environment variables, and do not expose the contents of `keystore.properties`. (Env var overrides for the two passwords — `KEYSTORE_STORE_PASSWORD` / `KEYSTORE_KEY_PASSWORD` — exist in `build.gradle` as a fallback path if `keystore.properties` isn't populated, but the normal case is `keystore.properties` already has real values and nothing further is needed.)

Steps:

1. Inspect `android/app/build.gradle` and confirm the current `applicationId` and `versionCode`. Use these live values, not remembered ones — the applicationId is `com.jedibk.shelfdrive` (confirmed against the live Play Console listing on 2026-08-17; an earlier repo commit had it wrong as `com.jedibrooker.shelfdrive` across build.gradle, both capacitor.config.json files, strings.xml, PlayerNotificationService.kt's VALID_MEDIA_BROWSERS list, and nuxt.config.js's ANDROID_APP_URL — all since fixed). Re-verify against build.gradle regardless; don't trust this note if it ever looks stale.
2. Change only `defaultConfig.versionCode` to the next value (the next unused build number — ask if it's not obvious what that is, e.g. if the Play Store might be ahead of what's committed here). Leave `versionName` unchanged unless explicitly asked to bump it.
3. Do not generate a new keystore.
4. Do not modify the signing configuration.
5. Do not change the package name, namespace, Automotive manifest config (`android.hardware.type.automotive` must stay required), target SDK, or application code.
6. Do not create a branch, commit, push, PR, or release tag unless explicitly asked — a version-code-only change is small enough that whether it needs review is the user's call, not a default.
7. Build with (adjust `JAVA_HOME` to wherever the local JDK 21 actually is — the macOS Android Studio path is `/Applications/Android Studio.app/Contents/jbr/Contents/Home`, but don't assume that's right on every machine):

   ```bash
   cd android
   JAVA_HOME="<jdk21 path>" ./gradlew bundleRelease
   ```

8. Verify the resulting AAB:
   - exists at `android/app/build/outputs/bundle/release/app-release.aab`
   - is signed successfully (`jarsigner -verify`)
   - contains the applicationId read from `build.gradle` in step 1
   - has the version code just set
   - still declares `android.hardware.type.automotive`
9. Report: absolute AAB path, package name, version code and version name, file size, signing-verification result, SHA-256 checksum.

If a required file (keystore.properties, the keystore itself, a working JDK) is genuinely missing, report only the exact missing file/setting and stop — don't improvise a workaround.
