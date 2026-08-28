# ShelfDrive v131 — Play reviewer response and access instructions

Use this document only for package `com.jedibk.shelfdrive`, version code
**131**. Do not paste reviewer credentials into this repository.

## Checks that still require the Play Console account owner

- [ ] Keep a public HTTPS Audiobookshelf reviewer server online throughout
  review, with no MFA, VPN, invitation, IP allowlist, or location restriction.
- [ ] Give the account access to at least one rights-cleared audiobook that can
  be streamed and downloaded.
- [ ] Save the exact URL, username, password, library, and title in Play Console
  **App access**.
- [ ] Update Data Safety using `PLAY_STORE_SUBMISSION.md`; do not answer “No data
  collected.”
- [ ] Remove the stale foreground-service declaration from Play Console. The
  v131 artifact declares and uses no foreground service.
- [ ] Upload only the verified version-code-131 app bundle, install the
  Play-delivered build, and check its Pre-launch report before submission.

## App access instructions

Replace the bracketed values before pasting into Play Console:

> ShelfDrive is an Android Automotive OS client for an externally managed
> Audiobookshelf server. Sign-in is required to browse, stream, and download
> the review library. The account requires no MFA, VPN, invitation, IP
> allowlist, or location restriction.
>
> 1. Open ShelfDrive from the Android Automotive OS media source list and
>    select **Sign in**. The vehicle/emulator must be parked because Settings
>    contains text input.
> 2. Enter **[PUBLIC HTTPS SERVER URL]**, **[REVIEWER USERNAME]**, and
>    **[REVIEWER PASSWORD]**, then select **Sign in**.
> 3. ShelfDrive shows an inline disclosure naming the server and the data sent
>    to it. Read it and select **Continue and sign in**.
> 4. Open **Libraries → [LIBRARY NAME] → Books → [RIGHTS-CLEARED TITLE]**.
>    Test playback, pause/resume, jump backward, jump forward, search, and
>    reconnect.
> 5. Beside the test title, choose **Download**. Android's Download Manager
>    shows transfer progress. When complete, open **Downloads**, disconnect the
>    network, and play the title offline.
> 6. If the test vehicle does not expose custom browse actions, start the test
>    title, park, open ShelfDrive Settings, and choose **Download current
>    audiobook**. Settings also provides **Cancel all** and **Delete all**.

## Response to the version-code-129 crash rejection

Paste only after the checks above and the final evidence record are complete:

> Hello Google Play review team,
>
> Thank you for reporting the repeated crash in ShelfDrive version code 129.
> We reproduced the startup race locally on Android Automotive OS. Version 129
> registered a connectivity callback before its media manager was initialized;
> an immediate callback could access that uninitialized dependency and restart
> the bound media service repeatedly.
>
> Version code 131 initializes the media manager before registering network
> callbacks and adds bounded, single-completion handling around browse, search,
> artwork, lifecycle, and malformed-response paths. It also adds offline
> audiobook support using Android's system Download Manager, not an app
> foreground service. Download ownership is persisted before handoff; account
> removal and cancellation invalidate queued work; completion is reconciled by
> exact platform ID; files are size/type/container validated and atomically
> published only after every part is complete.
>
> The version-code-131 release targets SDK 35 and is native Android Automotive
> OS only. Its merged manifest contains no foreground-service permission, no
> foreground-service type, no broad storage permission, and no cleartext
> traffic opt-in. Working reviewer credentials and the exact test path are
> provided in Play Console App access. Please review version code 131.

## Final evidence record

This record belongs only to the final locally signed v131 artifacts described
below; do not reuse it for a rebuilt or Play-re-signed artifact.

- Verification date/time: **2026-08-28 23:29:44 AEST (+1000)**
- AAB SHA-256:
  **35bbfd4899e8e7d48b71ce79a4ed1d96ac6c16f4f8de7f3c0dce4c23e40291a8**
- APK SHA-256:
  **fb9edc722490bd2146551c3fbaaffa599df5f3870bf1137c1f0d2b3ee2748fba**
- Signing certificate SHA-256:
  **34eb31e2a7d852ab1153b769d957ba4bfb159b678e90d3b53b1c2ebb5f6ed506**
- Debug unit tests: **198 passed; 0 failed, errored, or skipped**
- Release unit tests: **185 passed; 0 failed, errored, or skipped**
- Debug and release lint: **passed with 0 errors or fatals; 99 debug and
  57 release warnings reviewed**
- Debug APK, release APK, and release AAB builds: **passed from a clean tree**
- Bundle validation and merged-manifest inspection: **passed; package
  `com.jedibk.shelfdrive`, version code 131, min SDK 28, target SDK 35,
  required AAOS feature, cleartext disabled, protected download-completion
  receiver, and no foreground-service or broad-storage declaration**
- APK/AAB signature and shrinker inspection: **passed; expected upload
  certificate verified and all three offline custom-action icons retained**
- AAOS fresh install / cold bind / Settings: **passed on the API 33 Automotive
  emulator; Car Media bound the service, parked Settings rendered, direct
  launch of the private activity was blocked, `startForegroundCount=0`, and
  the final log scan contained no ShelfDrive crash or ANR**
- Authenticated download / offline playback / public reviewer-server path:
  **not run locally because reviewer-server credentials were not available;
  must be completed with the Play Console reviewer account before submission**

Local diagnosis must be described as local evidence, not as a Google reviewer
log. Do not attach logs containing credentials, tokens, or private server URLs.
