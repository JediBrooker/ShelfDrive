# ShelfDrive v130 — Play reviewer response and access instructions

Use this document only for package `com.jedibk.shelfdrive`, version code
**130**. Do not paste it while any bracketed field or unchecked verification
item remains. The detailed local v129 crash evidence remains in
[`V129_CRASH_EVIDENCE.md`](V129_CRASH_EVIDENCE.md).

## Blocking check before submission

- [ ] The updated privacy policy is live at
      `https://jedibrooker.github.io/ShelfDrive/PRIVACY_POLICY` and no longer
      says that ShelfDrive transmits no personal data to any third party.
- [x] The four prepared screenshots match the final v130 candidate, were
      captured on generic AAOS, use the exact required dimensions, and are JPEG
      or 24-bit RGB PNG without alpha. They contain no private credentials or
      unlicensed third-party media.
- [ ] The public HTTPS reviewer server and account work without MFA, VPN, IP
      allowlisting, location restrictions, or an expiring invitation.
- [ ] The account can browse/search a library and stream at least one
      rights-cleared test title.
- [ ] The URL and credentials are saved in Play Console **App access** for
      `com.jedibk.shelfdrive` and match the placeholders below.
- [x] A clean signed v130 release has passed the final unit-test, release-lint,
      release-build, artifact-inspection, and repeated AAOS startup/rebind run.
- [ ] The uploaded bundle is version code 130 and targets SDK 35.
- [ ] Data Safety says data is collected and matches the current app/privacy
      policy; the old “No data collected” answer is gone.
- [ ] The stale media-playback foreground-service declaration/video was not
      submitted for v130; the final v130 artifact contains no foreground-
      service permission or type.

Never commit the reviewer password to this repository.

## App access instructions for Play Console

Replace every bracketed value before pasting:

> ShelfDrive is an Android Automotive OS client for an externally managed
> Audiobookshelf server. Sign-in is required to browse and stream the review
> library. No MFA, VPN, invitation, IP allowlist, or location restriction is
> used for this reviewer account.
>
> 1. Open ShelfDrive from the Android Automotive OS media source list and
>    select **Sign in**. The vehicle/emulator must be parked because Settings
>    contains text input.
> 2. Enter **[PUBLIC HTTPS SERVER URL]** as Server URL,
>    **[REVIEWER USERNAME]** as Username, and **[REVIEWER PASSWORD]** as
>    Password. Select **Sign In**.
> 3. ShelfDrive displays a disclosure describing the account, device, search,
>    and playback data sent to that server. Select **Continue and sign in**.
> 4. Return to ShelfDrive and open **Libraries → [LIBRARY NAME] → Books →
>    [RIGHTS-CLEARED TEST TITLE]**. Start playback, then test pause, resume,
>    jump backward, and jump forward.
> 5. Return to the root to test Continue/Recent and search. Closing and
>    reopening the system media app should reconnect to ShelfDrive and retain a
>    usable media session.

## Response to the v129 crash rejection

Paste only after the blocking check is complete:

> Hello Google Play review team,
>
> Thank you for reporting the repeated crash in ShelfDrive version code 129.
> We reproduced the startup failure locally on Android Automotive OS and fixed
> its root cause in version code 130.
>
> Version 129 registered a network callback before its media manager dependency
> was initialized. Android Automotive OS can immediately invoke that callback
> when a validated vehicle network is already active. The callback then
> accessed the uninitialized media manager, threw a Kotlin
> `UninitializedPropertyAccessException`, and caused Android to restart the
> bound media service, allowing the crash to repeat.
>
> Version 130 initializes the media manager before registering the network
> callback, so an immediate network callback cannot observe a partially
> initialized service. We also added lifecycle cleanup and defensive timeout,
> completion, malformed-response, and artwork-fetch handling around the media
> service's asynchronous paths.
>
> We verified the final signed version 130 release on generic Android
> Automotive OS using both a fresh installation from the app bundle's
> device-specific split APKs and an in-place update from the signed version 129
> release. The final unit-test and release-lint suites passed, the release APK
> and app bundle built successfully, and 75 cold media-host binds, 15 complete
> network loss/reconnect cycles, parked Settings launches, and 140 signed-out
> transport commands completed without a ShelfDrive crash or ANR. Automated
> tests also cover browse, search, playback, pause/resume, jump controls,
> malformed responses, timeouts, and service teardown. The authenticated
> reviewer-server path must be confirmed using the App access account before
> this response is submitted.
>
> Working HTTPS reviewer credentials and exact navigation steps are provided in
> Play Console App access for `com.jedibk.shelfdrive`. The account requires no
> MFA, VPN, invitation, IP allowlisting, or location restriction and contains a
> rights-cleared test title. Please review version code 130 (target SDK 35).

The verification paragraph above is deliberately written in completed tense.
Delete or rewrite any sentence that is not supported by the final recorded run.

## Final evidence record

Complete the external items noted below before using the response:

- Verification date/time: **2026-08-28 21:25 AEST (+1000)**
- Final AAB SHA-256:
  **`aea613c3a18f903d019b8be16b2a7531778a218e6869f0cf8ed8be900587a34e`**
- Final APK SHA-256:
  **`0a7521f554ac5494813dd7726ff9feb97602172223227742dde9d1b890f1f5cb`**
- Signing certificate SHA-256:
  **`34eb31e2a7d852ab1153b769d957ba4bfb159b678e90d3b53b1c2ebb5f6ed506`**
- AAOS emulator profile/API: **PASS — disposable generic Android Automotive
  SDK for arm64, API 33 (`ShelfDrive_Verification_API_33`)**
- Fresh-install test: **PASS — Bundletool 1.18.3 generated and installed the
  exact AAB's device-specific `base-master`, `base-en`, and `base-ldpi` splits;
  the system media host bound ShelfDrive and rendered the signed-out root**
- v129-to-v130 update test: **PASS — archived signed v129 installed, ShelfDrive
  selected as the active media source, and the final v130 APK installed with
  `adb install -r`; the signing certificate matched and app data was retained**
- Repeated startup/rebind count: **PASS — 75/75 cold system-media-host binds
  started the app process and created its media session (50 upgraded-APK runs
  plus 25 fresh AAB-split runs)**
- Network loss/reconnect: **PASS — 15/15 complete Wi-Fi plus airplane-mode
  offline/online cycles during repeated cold starts**
- Browse/search/playback/pause/resume/jump controls: **AUTOMATED PASS — unit
  coverage passed; authenticated end-to-end run is NOT RUN until the public
  reviewer server and account are provisioned**
- Media voice/search intents: **AUTOMATED PASS — seeded-title, general resume,
  no-match, signed-out, and offline cases passed unit coverage; authenticated
  end-to-end voice/search is NOT RUN until reviewer access exists**
- Signed-out media transport handling: **PASS — 140 play, pause, next,
  previous, fast-forward, rewind, and stop commands; process and media session
  remained active**
- Service teardown/reopen: **PASS — included in all 75 cold binds with no
  missing process, launch failure, or missing media session**
- Unit tests: **PASS — 177 debug and 165 release tests; zero failures or
  ignored tests**
- Release lint: **PASS — `lintDebug` and `lintRelease`; release report has zero
  errors and 60 non-blocking warnings**
- Release builds: **PASS — clean `assembleDebug`, `assembleRelease`, and
  `bundleRelease`; Bundletool validation and APK v2 signature verification
  passed**
- Final crash-log check: **PASS — zero ShelfDrive entries in Android's crash
  buffer, zero ShelfDrive `lastanr` entries, and no fatal/security exception in
  the captured runtime logs**

Local diagnosis evidence must be described as local evidence, not as a Google
reviewer log. Do not attach secrets, tokens, private server addresses, or logs
containing them.
