# ShelfDrive v130 — Google Play resubmission checklist

This checklist is for package `com.jedibk.shelfdrive`, version code **130**.
Do not reuse it for version 129. A box means that the item still requires a
human action or final verification; unchecked items must not be represented to
Google as complete.

## Stop: submission blockers

Do not upload or submit v130 until every item in this section is complete.

- [ ] **Open the correct Play Console app.** This release and the rejection are
      for `com.jedibk.shelfdrive`. Do not upload it to or edit declarations for
      the separate `com.jedibrooker.shelfdrive` listing. Confirm the package ID
      in the Console URL/app dashboard before changing any declaration.
- [ ] **Publish the updated privacy policy.** The public URL currently used by
      the app and Play Console,
      `https://jedibrooker.github.io/ShelfDrive/PRIVACY_POLICY`, still serves an
      older May 2026 policy that says ShelfDrive does not transmit personal
      data to any third party, despite transmitting it to the configured server
      (which can be operated by a third party). Publish
      [`../docs/PRIVACY_POLICY.md`](../docs/PRIVACY_POLICY.md) to that exact
      HTTPS URL, then verify the public page in a signed-out browser. Until
      then, the privacy-policy requirement is not complete.
- [x] **Verify the complete AAOS screenshot set against the final release.** The
      four current PNGs in `screenshots-aaos-play/` use the generic AAOS UI,
      contain no third-party cover art or private credentials, have the exact
      required portrait/landscape dimensions, and are 24-bit RGB PNGs without
      alpha. Before upload, compare them with the final Play candidate and
      recapture any screen whose visible UI has changed. Any added browse or
      playback screenshots must use only original, licensed, or documented
      public-domain test media; keep the applicable licence/source record.
- [x] **Complete the final v130 verification run.** The clean unit-test,
      release-lint, release-build, artifact-inspection, and repeated AAOS
      startup/rebind checks must all pass. Record the results below; do not
      paste the reviewer response before this is done.
- [ ] **Provision and test reviewer access.** The HTTPS demo server and account
      must remain available for the entire review, require no MFA/VPN/IP
      allowlist, and contain rights-cleared test media.
- [ ] **Correct Play Console declarations.** Replace any prior “No data
      collected” answer with the Data Safety answers below, update App access,
      and confirm that all declarations match the published privacy policy and
      the v130 app.
- [ ] **Remove the stale foreground-service declaration.** V130 declares no
      `FOREGROUND_SERVICE` permission or foreground-service type and does not
      call `startForeground`. Do not submit
      `play-console/shelfdrive-media-playback-declaration.mp4`, its raw video,
      or `play-console/shelfdrive-media-playback.ass` as evidence for v130.
      Remove/answer No to the old media-playback FGS declaration as Play Console
      permits, and verify that no older bundle with that permission remains in
      an active test or production release.

## Rejection diagnosis and v130 correction

The rejected v129 build was reproduced locally on Android Automotive OS. Three
persisted local crash records showed the same startup failure within 91–191 ms
of process start:

```text
kotlin.UninitializedPropertyAccessException:
lateinit property mediaManager has not been initialized
```

Version 129 registered its `ConnectivityManager.NetworkCallback` before
assigning the media service's `mediaManager`. When an already-active vehicle
network caused Android to deliver an immediate callback, the callback accessed
that uninitialized property. Android restarted the bound media service and the
same race repeated.

In v130, `PlayerNotificationService` constructs `mediaManager` before
registering the network callback. The source also contains defensive service
lifecycle, browse/search completion, malformed-data, and artwork-fetching
hardening. These local records are not logs from Google's reviewer device; see
[`V129_CRASH_EVIDENCE.md`](V129_CRASH_EVIDENCE.md) for the exact evidence and
wording constraint.

Use [`PLAY_REVIEW_RESPONSE_V130.md`](PLAY_REVIEW_RESPONSE_V130.md) only after
all blocking checks and final verification fields in that document are
complete.

## Release identity and target API

- Package: `com.jedibk.shelfdrive`
- Version code: `130`
- Version name: `0.1`
- Android Automotive OS only: `android.hardware.type.automotive` is required
- Target SDK: `35` (Android 15)
- Production cleartext traffic: disabled; configured servers must use HTTPS

Google's current schedule requires new Android Automotive OS apps and updates
to target API 35 or higher starting **31 August 2026**. API 35 is therefore the
correct target for this AAOS release even though the general mobile-app
deadline advances to API 36 on that date.

Official source:
https://support.google.com/googleplay/android-developer/answer/11926878

## Build and verification

Use JDK 21 and the same package/version that will be submitted. Do not upload a
bundle merely because it compiled.

```bash
cd android
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
./gradlew clean testReleaseUnitTest lintRelease assembleRelease bundleRelease
```

The recorded final run used the broader command below so the debug-only source
set and debug lint were verified at the same time:

```bash
./gradlew --offline --no-daemon clean \
  :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease :app:bundleRelease
```

The Play release is native AAOS-only: its release source set contains no web
assets or Capacitor runtime, so Nuxt generation and `cap sync` are not release
build prerequisites. Keeping Node out of the release build also prevents the
legacy phone-web dependency graph from becoming part of the Play supply chain.
Local debug builds can still use Capacitor when `node_modules` is present.
For the broader local regression run used for this candidate, also run
`testDebugUnitTest` and `assembleDebug` after `npm ci`.

Expected bundle:
`android/app/build/outputs/bundle/release/app-release.aab`

Final local candidate recorded on 2026-08-28 at 21:25 AEST:

- AAB SHA-256:
  `aea613c3a18f903d019b8be16b2a7531778a218e6869f0cf8ed8be900587a34e`
- APK SHA-256:
  `0a7521f554ac5494813dd7726ff9feb97602172223227742dde9d1b890f1f5cb`
- Signing certificate SHA-256:
  `34eb31e2a7d852ab1153b769d957ba4bfb159b678e90d3b53b1c2ebb5f6ed506`
- Bundletool 1.18.3 validation: passed
- AAB-derived device split install: passed with `base-master`, `base-en`, and
  `base-ldpi`, all signed by the certificate above

Record the final run:

- [x] `testDebugUnitTest` passed: 177 tests, zero failures or ignored tests
- [x] `testReleaseUnitTest` passed: 165 tests, zero failures or ignored tests
- [x] `lintDebug` and `lintRelease` passed; release lint reports zero errors and
      60 classified non-blocking warnings
- [x] `assembleDebug` and `assembleRelease` passed
- [x] `bundleRelease` passed and Bundletool validation succeeded
- [x] Final AAB resolves to package `com.jedibk.shelfdrive`, version code 130,
      target SDK 35, and the intended signing certificate
- [x] Final merged manifest contains the AAOS media service and parked-only
      Settings activity, with no phone launcher, car launcher activity,
      foreground-service permission, broad storage permission, or cleartext
      opt-in
- [x] Final release artifact contains no stale
      `android.resource://com.jedibrooker.shelfdrive` reference
- [x] A fresh AAB-derived split install and signed v129-to-v130 update both
      survived 75/75 cold media-host binds, 15/15 complete network
      loss/reconnect cycles, parked Settings launches, and service teardown on
      a disposable generic API 33 AAOS emulator
- [x] Signed-out transport handling survived 140 play, pause, next, previous,
      fast-forward, rewind, and stop commands with the process and media
      session remaining active
- [ ] Authenticated browse, search, playback, pause/resume, jump
      backward/forward, progress sync, and reconnect pass end to end against
      the final public reviewer server and account
- [ ] Media voice/search intents handle an exact seeded title, a general
      play/resume request, no match, and signed-out/offline state without a
      crash or indefinite loading state. Automated coverage passes; the final
      authenticated end-to-end run still requires reviewer access.
- [x] No new ShelfDrive crash-buffer entry, fatal exception, security
      exception, or `lastanr` entry appeared during either final emulator run

Release signing is configured through the gitignored
`android/keystore.properties`. Keep the keystore and passwords outside this
repository and backed up. Opt in to Play App Signing; the local key should be
treated as the upload key.

## Store listing and assets

- App name: **ShelfDrive**
- Category: **Music & Audio**
- App: free
- Form factor: **Android Automotive OS** only
- Listing copy: [`../STORE_LISTING_DRAFT.md`](../STORE_LISTING_DRAFT.md)
- 512 x 512 icon: `shelfdrive-play-icon-512.png`
- 1024 x 500 feature graphic: `shelfdrive-feature-graphic-1024x500.png`
- AAOS screenshots: `screenshots-aaos-play/`

For a media app, AAOS screenshots are required under the current car-quality
criteria. Google requires at least two portrait screenshots at 800 x 1280 and
two landscape screenshots at 1024 x 768. They
must come from the generic AAOS emulator/system UI, accurately show v130, and
must not be OEM- or vehicle-specific.

Screenshot acceptance check:

- [x] At least 2 portrait images, exactly 800 x 1280
- [x] At least 2 landscape images, exactly 1024 x 768
- [x] Every screenshot is JPEG or 24-bit RGB PNG with no alpha channel and is
      no larger than 8 MB
- [x] Captured from the generic AAOS `Automotive Portrait` and `Automotive
      (1024p landscape)` emulator profiles
- [x] No Polestar, Volvo, or other OEM-specific UI/branding
- [x] No real server URL, username, password, token, or other private data
- [x] No commercial cover art, title, logo, or other third-party asset unless
      written permission/licensing evidence is ready for Play review
- [x] Screenshots match the final local v130 candidate and intended reviewer
      path. Reconfirm the uploaded Play artifact's UI before submission.

Official sources:

- https://support.google.com/googleplay/android-developer/answer/9866151
- https://developer.android.com/docs/quality-guidelines/car-app-quality
- https://developer.android.com/training/cars/media/voice-actions
- https://support.google.com/googleplay/android-developer/answer/9888072
- https://support.google.com/googleplay/android-developer/answer/9898842

## Play Console app-content answers

### Privacy policy

Use this URL only after the blocker at the top of this document is resolved:

`https://jedibrooker.github.io/ShelfDrive/PRIVACY_POLICY`

The policy must remain public, non-geofenced, available without sign-in, and
consistent with the app and Data Safety declaration. V130 also shows an in-app
privacy summary and presents an affirmative disclosure before first sign-in to
a configured server.

Official source:
https://support.google.com/googleplay/android-developer/answer/10144311

### App access

Choose **All or some functionality is restricted**. ShelfDrive requires a
user-provided Audiobookshelf server and credentials. Add the exact URL,
username, password, library name, and rights-cleared test title using the text
in [`PLAY_REVIEW_RESPONSE_V130.md`](PLAY_REVIEW_RESPONSE_V130.md).

The reviewer server must:

- be reachable from a clean public network over HTTPS;
- remain online through review;
- require no MFA, VPN, invitation, location restriction, or IP allowlist;
- permit browse, search, streaming playback, progress update, and reconnect;
- contain only media that may lawfully appear in the app and screenshots.

Never commit the reviewer password.

### Data Safety

**Do not answer “No data collected.”** Google defines collection as transmitting
data off the device, regardless of whether it goes to the developer or a
third-party server. ShelfDrive transmits data to the server selected by the
user, so **Data collected = Yes**.

Recommended v130 declaration, based on the current source and privacy policy:

| Play data type | Collected | Required | Ephemeral | Purposes |
| --- | --- | --- | --- | --- |
| Personal info → User IDs | Yes | Required | No | App functionality; Account management |
| Device or other IDs → Device or other IDs | Yes | Required | No | App functionality |
| App activity → App interactions | Yes | Required | No | App functionality |
| App activity → In-app search history | Yes | Optional | No | App functionality |
| App activity → Other actions | Yes | Required | No | App functionality; Personalization |

Mapping to behavior:

- **User IDs:** username, server account/user ID, and authentication/session
  identifiers used to sign in and keep the connection authenticated. The
  password is transmitted during sign-in but is not retained by ShelfDrive.
- **Device or other IDs:** random install-scoped app-instance identifier used
  to identify playback sessions. Device model, Android version, and app
  version are also sent as session/device details.
- **App interactions:** library/browse requests and selected titles are sent to
  the configured server as the user navigates.
- **In-app search history:** search text is sent only when the user chooses to
  search, so collection is optional.
- **Other actions:** playback actions, listening position, progress, and
  history support streaming, resume, synchronization, and personalized
  Continue/Recent views.

Security and sharing answers:

- **Encrypted in transit:** Yes. V130 release connections require HTTPS.
- **Data shared:** No, based on Google's exception for a transfer caused by a
  specific user-initiated action or by a prominent disclosure with affirmative
  consent. ShelfDrive shows the destination server and data categories before
  sign-in and requires **Continue and sign in**. The configured server
  operator's role, retention, and deletion practices are still disclosed in
  the app and privacy policy.
- **Account deletion:** ShelfDrive cannot create an Audiobookshelf account and
  does not operate the server account. Do not claim that ShelfDrive deletes the
  external account. Users can disconnect to delete the local ShelfDrive
  profile and credentials; server-side records/account deletion must be done
  through the configured server or its operator. Answer any Console deletion
  question according to its exact wording and this division of responsibility.

The Play Console account owner remains responsible for confirming these
answers against every version and SDK distributed under this listing. If any
other active track contains different data behavior, the declaration must
cover the union of all distributed versions.

Official source:
https://support.google.com/googleplay/android-developer/answer/10787469

### Remaining content declarations

- Ads: **No**
- News app: **No**
- Target audience: select the truthful intended audience; do not infer this
  from the media currently on the review server
- Content rating: complete the IARC questionnaire from actual app behavior and
  accessible server content; do not pre-claim a rating
- Account creation: the app does not offer account creation; it signs into an
  externally managed Audiobookshelf account

### Foreground service permissions

V130 does not use an Android foreground service: its final manifest must not
contain `FOREGROUND_SERVICE` or `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, its media
service has no `android:foregroundServiceType`, and the app does not call
`startForeground`. Therefore the Play Console foreground-service permission
declaration must not claim media playback for v130. The existing media-playback
demo video and subtitle assets under `store/play-console/` are historical and
must not be submitted as v130 evidence.

If Play Console still asks about an FGS permission, inspect every artifact in
every active track and the final merged v130 manifest before answering. Do not
add a permission merely to make the declaration UI appear.

Official sources:

- https://support.google.com/googleplay/android-developer/answer/13392821
- https://support.google.com/googleplay/android-developer/answer/16559646

## Reviewer test path

Use the exact values configured in Play Console App access:

1. Open ShelfDrive from the generic AAOS system media source list.
2. Select **Sign in**. If Settings opens, keep the vehicle/emulator parked.
3. Enter the supplied HTTPS server URL, username, and password, then select
   **Sign In**.
4. Read the data disclosure naming the supplied server and select
   **Continue and sign in**.
5. Return to ShelfDrive and open **Libraries → [LIBRARY] → Books → [TITLE]**.
6. Start playback; verify play/pause, resume, jump backward, and jump forward.
7. Return to the media root and exercise Continue/Recent and search.
8. Exit and reopen the system media app, then reconnect the network and confirm
   ShelfDrive restores without a crash.

Use only labels and paths that were confirmed against the final release and
reviewer account.

## Release sequence

- [ ] Resolve all blockers in this document
- [ ] Upload the verified v130 AAB to an internal test track
- [ ] Install the Play-delivered build on AAOS and rerun the reviewer path
- [ ] Review the Pre-launch report, Android vitals, permissions, manifest, and
      policy warnings; resolve every actionable item
- [ ] Confirm the uploaded artifact still reports version code 130 and target
      SDK 35
- [ ] Save the tested credentials and exact access steps in Play Console
- [ ] Paste the v130 response only after its verification placeholders are
      complete
- [ ] Submit the update for Android Automotive OS review

No step in this document authorizes uploading, publishing the privacy page, or
changing Play Console. Those external actions require the account owner's
explicit approval.
