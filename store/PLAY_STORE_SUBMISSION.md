# ShelfDrive — Play Store submission punch-list

Everything that's already done in the repo is marked ✓.
Everything left for the human to do is marked ☐.

---

## 1. Google Play Developer account

- ☐ Create / sign in to a Play Console account at https://play.google.com/console
- ☐ Pay the one-time $25 USD developer registration fee
- ☐ Complete identity verification (Google may ask for ID + a phone number)

## 2. Release keystore

- ☐ Generate a release keystore (do this **once**; store the file outside this repo and back it up — losing it means you can never push another update to the same listing):

  ```bash
  keytool -genkey -v \
    -keystore ~/keys/shelfdrive-release.jks \
    -keyalg RSA -keysize 2048 -validity 36500 \
    -alias shelfdrive
  ```

  Pick strong passwords; you'll be prompted twice (store password + key password — they can be the same).

- ☐ Create `android/keystore.properties` from `android/keystore.properties.example` and fill in the real path + passwords. **The file is gitignored — do not commit it.**

  ```properties
  storeFile=/Users/cbrooker/keys/shelfdrive-release.jks
  storePassword=...
  keyAlias=shelfdrive
  keyPassword=...
  ```

## 3. Build the signed AAB

✓ `build.gradle` has the release `signingConfig` wired up.
✓ R8 minify + resource shrinking enabled; ProGuard rules in `proguard-rules.pro`.

- ☐ From the repo root, build the App Bundle:

  ```bash
  cd android
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ./gradlew bundleRelease
  ```

- Output: `android/app/build/outputs/bundle/release/app-release.aab` (this is what you upload to Play Console).

## 4. Listing assets

- ✓ App icon (512×512 PNG): `store/shelfdrive-play-icon-512.png`
- ✓ Screenshots (1152×1536 native, 4 captures): `store/screenshots-aaos/`
- ☐ Feature graphic (1024×500). Not done — create one in Canva / Figma. Suggested: orange Polestar gradient, ShelfDrive icon left, white text "ShelfDrive — your audiobooks in the car" right.
- ✓ Short description, full description, release notes: `STORE_LISTING_DRAFT.md`
- ✓ Privacy policy markdown: `PRIVACY_POLICY.md`
- ☐ Host the privacy policy publicly. Easiest path: enable GitHub Pages on the `JediBrooker/ShelfDrive` repo (Settings → Pages → Source: main → /(root)), then the URL is `https://jedibrooker.github.io/ShelfDrive/PRIVACY_POLICY` (markdown auto-renders).

## 5. Play Console setup

- ☐ Create app in Play Console
  - App name: **ShelfDrive**
  - Default language: English (US)
  - App or game: **App**
  - Free or paid: **Free**

- ☐ App content questionnaire
  - Privacy policy URL: the GitHub Pages link from step 4
  - Ads: **No**
  - App access: **All functionality is available without restrictions**
  - Content rating: complete the IARC questionnaire — Everyone is expected
  - Target audience: 18+ (audiobook content is user-supplied)
  - News app: **No**
  - COVID-19 contact tracing: **No**
  - Data safety: **No data collected** (the app sends data only to the user-controlled Audiobookshelf server)

- ☐ Store listing
  - Paste the short and full descriptions from `STORE_LISTING_DRAFT.md`
  - Upload the 512×512 icon
  - Upload screenshots (Play needs at least 2)
  - Upload the feature graphic
  - Category: Music & Audio

- ☐ Form factors → enable **Android Automotive OS**. This unlocks the dedicated Automotive review queue and adds Automotive-specific screenshot slots.

## 6. App signing by Google Play

- ☐ Opt into Play App Signing (recommended — Google holds the production signing key in their HSM)
  - You upload AABs signed with your **upload key** (the keystore from step 2)
  - Google re-signs with the production key before distribution
  - Lets you recover from a lost upload key without losing your listing

## 7. First test track

- ☐ Internal testing → upload `app-release.aab` → add yourself as a tester (Google account email)
- ☐ Wait ~1–2 hours for the build to be processable, then run the **Pre-launch report** from the Console. It boots the AAB on real devices/emulators and surfaces crashes, security, and policy issues. Review the AAOS-specific failures.
- ☐ Test the build by installing through the Play Store on your Polestar emulator (sign in to Play with your tester Google account first)

## 8. Production review

- ☐ Promote internal → closed beta (a handful of testers) → open beta (public opt-in) → production
- ☐ First-time AAOS submissions typically take **3–7 business days** to review. Updates are faster.
- ☐ Address any policy-team feedback. Common AAOS-review pushback:
  - "Activity isn't distraction-optimized" → SettingsActivity already declares `distractionOptimized="false"` so AAOS hides it while driving; cite that in the review reply
  - "WebView entry point" → MainActivity no longer has `category.CAR_LAUNCHER` so it isn't an AAOS launcher entry; cite that
  - "Uses GMS Cast on non-GMS device" → Cast subsystem entirely removed; cite the commit

## Cheat-sheet: full release build from a clean checkout

```bash
git clone git@github.com:JediBrooker/ShelfDrive.git
cd ShelfDrive
npm install
npm run sync                     # builds Vue, syncs into android/

# Create the keystore.properties (one-time)
cp android/keystore.properties.example android/keystore.properties
# ...edit with real paths/passwords...

cd android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew bundleRelease

ls -lh app/build/outputs/bundle/release/app-release.aab
```

Upload that `.aab` to Play Console.
