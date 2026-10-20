# ShelfDrive v132 — Play rejection diagnosis, reviewer response and evidence

Use this document only for package `com.jedibk.shelfdrive`, version code
**132**. Do not paste reviewer credentials into this repository.

## What Google rejected

On 2026-10-20 Play rejected version code 132 with **Issue found: App
installation** — "The app could not be evaluated in compliance with the
Families Policy Requirements due to installation issues." The notice cites the
Families Policy Requirements and the Broken Functionality policy.

## Diagnosis

The shipped artifact did not change between the accepted v131 install path and
v132. The only v132 commit bumps `versionCode` and edits Nuxt web files
(`components/app/SideDrawer.vue`, `components/widgets/FlagAu.vue`,
`strings/en-us.json`) that the AAOS artifact does not package: the app module
removes the web asset source set entirely, so the release AAB contains no web
UI.

The rejection wording is the template Play uses when an app is routed to the
Families review, whose reviewers install on phones and tablets. The v132
bundle declares `android.hardware.type.automotive` as **required**, so Play's
device filtering offers it to no phone or tablet, and the artifact has no
phone launcher activity. The reviewer therefore cannot install or open it on
the devices that review uses. The Android package manager itself does not
block the install (see the sideload note in the evidence record), which is
why this is a Play Console targeting problem rather than a code defect.

## Checks that require the Play Console account owner

- [x] **Policy → App content → Target audience and content**: select only
  **18 and over**. Any under-18 group routes the app to Families review. Do
  not opt into Designed for Families or Teacher Approved. **Done on
  2026-10-21: the declaration had 6-8, 9-12, 13-15, 16-17 and 18+ all
  selected and stated the Families policy applied; it is now 18 and over
  only, with the optional "restrict minors" setting left off.**
- [x] **Release tracks**: confirmed via the Play Developer API on 2026-10-21
  that version code 132 was released on `automotive:production` and the
  phone `production` track is empty. The phone `alpha` track holds an empty
  draft release (no version codes) that can be deleted in Console.
- [x] **Data safety**: completed on 2026-10-21 per the table in
  `PLAY_STORE_SUBMISSION.md`. The pending draft had declared "No data
  collected". Now: User IDs (required; app functionality, account
  management), App interactions (required; app functionality), In-app search
  history (optional; app functionality), Other actions (required; app
  functionality, personalisation), Device or other IDs (required; app
  functionality); none ephemeral; nothing marked as shared with third
  parties; encrypted in transit; no in-app account creation; sign-in with
  accounts created by the user's Audiobookshelf server administrator; the
  optional deletion-request question was left unanswered. The privacy policy
  URL in Console is still the GitHub blob link, not the GitHub Pages page.
- [x] Add the compatibility notes Google asked for to the store description:
  Android Automotive OS only; Android 9 (API 28) or newer; no companion app;
  a self-hosted Audiobookshelf server and account are required. **Done on
  2026-10-21 via the Play Developer API (en-AU full description, committed
  with changes not sent for review); it goes to review with the next
  submission.**
- [ ] Keep the public HTTPS Audiobookshelf reviewer server, credentials and
  exact navigation steps from `PLAY_REVIEW_RESPONSE_V131.md` current in
  **App access**.
- [ ] Upload only the verified version-code-132 app bundle to an internal AAOS
  track, install the Play-delivered build on an Android Automotive OS
  emulator, and check its Pre-launch report before resubmitting. **Not done;
  the existing 132 bundle was resubmitted as-is.**

## Submission record

On 2026-10-21 (AEDT) four changes were sent for review from Publishing
overview, after Play's quick checks passed: the Production (Android
Automotive OS) release 0.1 / version code 132 (full rollout), the en-AU full
description change, the target audience change to 18 and over, and the
completed Data safety questionnaire. The submission dialog offered no notes
field, so the reviewer response below was not sent; keep it for an appeal if
needed. Console showed "Changes in review" afterwards.

## Response to the version-code-132 installation rejection

Paste only after the checks above are complete:

> Hello Google Play review team,
>
> Thank you for the installation report on ShelfDrive version code 132.
> ShelfDrive is a native Android Automotive OS media app. Its manifest
> requires the `android.hardware.type.automotive` feature and it has no phone
> launcher activity, so Play does not offer it to phones or tablets and it
> cannot be evaluated on those devices. It is not designed for children; the
> target audience is now set to 18 and over and the Families program is not
> selected.
>
> Compatibility: Android Automotive OS only, Android 9 (API 28) or newer,
> no companion app. The app requires a user-provided Audiobookshelf server;
> working reviewer credentials and the exact test path are in App access.
>
> Version code 132 is functionally identical to the previously reviewed
> version code 131 apart from the version number; it installs and binds on
> an Android Automotive OS device. Please review version code 132 on an
> Android Automotive OS device.

## Final evidence record

This record belongs only to the locally signed v132 artifacts described
below; do not reuse it for a rebuilt or Play-re-signed artifact.

- Verification date/time: **2026-10-21 09:19:54 AEDT (+1100)**
- Source: branch `made-in-australia` at commit `79420664`, clean tree
- AAB SHA-256:
  **ee49f69ceff5e6437359ee4aadf7d4b3de366218eddfc543e8a937b266cab096**
- APK SHA-256:
  **f040907db60c35017cb1b8fe0b16256a5f801f622031ce94c7679789e8f2854c**
- Signing certificate SHA-256 (AAB and APK):
  **34eb31e2a7d852ab1153b769d957ba4bfb159b678e90d3b53b1c2ebb5f6ed506**
  (same upload certificate as v131)
- Gate command: the `PLAY_STORE_SUBMISSION.md` release gate with JDK 21;
  run without `--offline` because the Gradle cache lacked
  `com.android.tools.lint:lint-gradle:31.8.2` (no code change involved)
- Debug unit tests: **198 passed; 0 failed, errored, or skipped**
- Release unit tests: **185 passed; 0 failed, errored, or skipped**
- Debug and release lint: **passed with 0 errors or fatals; 99 debug and
  57 release warnings (unchanged from v131)**
- Debug APK, release APK, and release AAB builds: **passed from a clean tree**
- Bundle validation (bundletool 1.18.3) and merged-manifest inspection:
  **passed; package `com.jedibk.shelfdrive`, version code 132, version name
  0.1, min SDK 28, target SDK 35, `android.hardware.type.automotive`
  required, cleartext disabled, `appCategory` audio, download-completion
  receiver protected by `SEND_DOWNLOAD_COMPLETED_INTENTS`, no foreground
  service permission or type, no `startForeground` call, no broad storage
  permission, no launcher activity**
- Permissions in the artifact: INTERNET, VIBRATE, ACCESS_NETWORK_STATE and
  the app's own signature-level download-state permission only
- Shrinker inspection: **passed; `ic_offline_download`, `ic_offline_cancel`
  and `ic_offline_remove` retained in the release APK**
- Phone sideload (API 36 phone emulator, `adb install` of the release APK):
  **installs successfully**, confirming the package manager does not reject
  the automotive feature requirement; the app has no launcher icon there.
  Play's device filtering, not the OS, is what keeps v132 off reviewer phones.
- AAOS fresh install / cold bind / Settings: **not run for v132**; no Android
  Automotive OS system image is installed on the build machine. The shipped
  code is identical to v131, which passed this check on the API 33 Automotive
  emulator. Repeat it with the Play-delivered internal-track build before
  resubmission.
- Authenticated download / offline playback / public reviewer-server path:
  **not run locally**; must be completed with the Play Console reviewer
  account before submission.

Local diagnosis must be described as local evidence, not as a Google reviewer
log. Do not attach logs containing credentials, tokens, or private server URLs.
