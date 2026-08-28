# ShelfDrive v131 — Google Play resubmission checklist

This checklist is for package `com.jedibk.shelfdrive`, version code **131**,
target SDK 35. Do not reuse the v129 or v130 artifact, hashes, screenshots, or
verification claims.

## External submission blockers

The repository and release artifact can be completed locally, but these items
must be completed in the correct Play Console listing before submission:

- [ ] Confirm the listing package is `com.jedibk.shelfdrive`, not the separate
  `com.jedibrooker.shelfdrive` package.
- [ ] Keep the public privacy policy available without sign-in at
  `https://jedibrooker.github.io/ShelfDrive/PRIVACY_POLICY` and verify the
  published page contains the 28 August 2026 policy naming JediBkApps.
- [ ] Provision a public HTTPS Audiobookshelf reviewer account with no MFA,
  VPN, invitation, IP allowlist, or location restriction and at least one
  rights-cleared downloadable audiobook.
- [ ] Save the tested credentials and exact navigation steps from
  `PLAY_REVIEW_RESPONSE_V131.md` in **App access**. Never commit the password.
- [ ] Correct Data Safety as described below; “No data collected” is not
  consistent with this app.
- [ ] Remove the stale foreground-service declaration in Play Console. Do not
  submit the historical media-playback declaration video.
- [ ] Recapture/verify the required generic-AAOS screenshots against v131. The
  Settings and offline-download UI changed after the v130 captures.
- [ ] Upload only the final verified v131 AAB to an internal track, install the
  Play-delivered build, and resolve every actionable Pre-launch report or
  policy warning before production submission.

## Rejection diagnosis and v131 correction

The rejected v129 build had a reproducible service-startup race: it registered
the connectivity callback before initializing `mediaManager`. A vehicle with
an already-active network could immediately invoke the callback, causing an
`UninitializedPropertyAccessException` and repeated bound-service restarts.
The detailed local evidence remains in `V129_CRASH_EVIDENCE.md`.

V131 preserves the initialization-order fix and adds defensive lifecycle,
timeout, malformed-response, caller-scope, and browse-action handling. Offline
audiobooks now use Android's system Download Manager. ShelfDrive persists
ownership before platform handoff, performs redirect-disabled authenticated
GET probes outside the global account lock, invalidates cancel/disconnect work,
reconciles the exact completed platform ID, validates size/type/container, and
atomically publishes a book only after all parts are complete. Custom browse
actions provide Download/Cancel/Remove where supported; parked Settings offers
**Download current audiobook** as the fallback on hosts without custom actions.

## Release identity and artifact requirements

- Application ID: `com.jedibk.shelfdrive`
- Version code: `131`
- Version name: `0.1`
- Target SDK: `35`
- Native Android Automotive OS only
- `android.hardware.type.automotive` required
- Production cleartext traffic disabled; configured servers must use HTTPS
- No foreground-service permission, type, or `startForeground` call
- No broad external-storage permission
- Settings is parked-only and not distraction optimized

## Reproducible release gate

Use JDK 21 from the `android` directory:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
./gradlew --offline --no-daemon clean \
  :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease :app:bundleRelease
```

Then verify:

- [ ] All debug and release unit tests pass with no failures or skipped tests.
- [ ] Debug and release lint finish with zero errors; review every warning.
- [ ] `assembleDebug`, `assembleRelease`, and `bundleRelease` succeed.
- [ ] Bundletool validates `android/app/build/outputs/bundle/release/app-release.aab`.
- [ ] The merged release manifest reports package/version 131/target 35 and has
  no FGS, broad-storage, phone-launcher, or cleartext declarations.
- [ ] The download completion receiver requires
  `android.permission.SEND_DOWNLOAD_COMPLETED_INTENTS`.
- [ ] The signed APK and AAB use the expected upload certificate; record fresh
  hashes in `PLAY_REVIEW_RESPONSE_V131.md`.
- [ ] A fresh generic-AAOS install cold-binds the media browser and opens parked
  Settings without a crash or ANR.
- [ ] With the reviewer server, verify browse, search, stream, controls,
  download, offline playback, cancel, remove, reconnect, and process restart.

## Privacy policy and in-app disclosure

V131 shows a readable policy summary directly in parked Settings, so a vehicle
without a browser still has in-app privacy text. The optional button opens the
complete public policy. The two-step inline sign-in flow names the destination
server and discloses credentials, account/device identifiers, searches,
playback actions, listening progress, and enabled background progress sync
before any sign-in request is sent.

The public policy must stay consistent with the release: downloaded audio and
partials are stored on the vehicle; system Download Manager receives the bearer
as an HTTPS header; endpoints must serve files directly because a later
redirect can receive replayed headers; completed offline books remain until
the user deletes them or clears app storage.

Official Play privacy-policy guidance:
https://support.google.com/googleplay/android-developer/answer/10144311

## Data Safety answers

Do not answer “No data collected.” ShelfDrive transmits data off the vehicle to
the Audiobookshelf server selected by the user. Confirm the current Console
wording, then declare at least:

| Play data type | Collected | Required | Ephemeral | Purposes |
| --- | --- | --- | --- | --- |
| Personal info → User IDs | Yes | Required | No | App functionality; Account management |
| Device or other IDs | Yes | Required | No | App functionality |
| App activity → App interactions | Yes | Required | No | App functionality |
| App activity → In-app search history | Yes | Optional | No | App functionality |
| App activity → Other actions | Yes | Required | No | App functionality; Personalization |

Behavior mapping:

- User IDs include username, account/user ID, and authentication/session IDs.
  The password is transmitted during sign-in but is not retained.
- Device data includes a random install-scoped app-instance identifier plus
  manufacturer/model, Android version, and ShelfDrive version.
- App interactions include library/browse requests and selected titles.
- Search text is sent only when the user searches.
- Other actions include playback commands, listening position, progress, and
  history used for resume and synchronization.
- Data is encrypted in transit because production connections require HTTPS.
- ShelfDrive cannot create or delete the external Audiobookshelf account.
  Disconnect removes the local profile and credentials; server-side deletion
  belongs to the configured server/operator.

Do not assume the “Data shared” answer. Select it from Google's current
definitions and disclosure/consent exception after reviewing every active
track under the listing. The declaration must cover the union of versions
distributed to users.

Official Data Safety guidance:
https://support.google.com/googleplay/android-developer/answer/10787469

## Why the foreground-service declaration is stale

V131 does not run a foreground service. `PlayerNotificationService` is a bound
AAOS `MediaBrowserService`, and offline transfers are owned by Android's system
Download Manager. The final manifest must contain neither
`FOREGROUND_SERVICE` nor `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, no service has an
`android:foregroundServiceType`, and the source does not call
`startForeground`.

If Play Console still shows an FGS questionnaire, it is retained Console state
or comes from another artifact in an active track. Inspect every active bundle,
remove the old declaration when Console permits, and do not add an FGS
permission merely to satisfy the stale form.

Official FGS declaration guidance:
https://support.google.com/googleplay/android-developer/answer/13392821

## Final submission sequence

- [ ] Complete the release gate and evidence record.
- [ ] Publish and verify the privacy page.
- [ ] Complete Data Safety, App access, content declarations, and screenshots.
- [ ] Upload v131 to an internal test track and install the Play-delivered build.
- [ ] Repeat the reviewer path, including offline playback with networking off.
- [ ] Resolve Pre-launch report, Android vitals, permissions, and policy issues.
- [ ] Paste the v131 response only after every statement is supported.
- [ ] Submit the Android Automotive OS update for review.

This repository workflow publishes source/privacy changes to GitHub. It does
not authorize or claim a Play Console upload or production rollout.
