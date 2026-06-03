# ShelfDrive — Play Store Listing Draft

Single source of truth for the Google Play Console listing.

---

## App name

**ShelfDrive**

## Short description (80 chars max)

> Audiobooks and podcasts from your self-hosted Audiobookshelf server, in the car.

(78 chars)

## Full description (4000 chars max)

> Play your own audiobooks and podcasts from a self-hosted Audiobookshelf server, designed for Android Automotive OS.
>
> ShelfDrive is an independent, open-source client for Audiobookshelf — the self-hosted audiobook and podcast server. Connect to your server once, and your library is available on the car's display: browse, resume, and listen safely while parked or driving.
>
> 🚗 BUILT FOR ANDROID AUTOMOTIVE OS
>
> • Native Car Media integration — browse from the OEM media template
> • Resume playback from the home media tile
> • Driver-distraction-optimized — text entry is parked-only, all browsing fits within distraction guidelines
> • Steering-wheel button support — play/pause and configurable jump forward / backward
>
> 📚 YOUR LIBRARY, YOUR SERVER
>
> • Browse by Continue Listening, Recently Added, Libraries, and Discover
> • Series and collections supported with cover art
> • Local downloads play offline
> • Cover art cached on-device for fast browse and reliable rendering
>
> ⚙️ CONFIGURABLE PLAYBACK
>
> • Adjustable jump forward / jump backward intervals (5s – 90s)
> • Auto-rewind on resume (toggleable)
> • Sleep timer with audio fade-out
>
> 🔒 PRIVACY
>
> • You provide the server. ShelfDrive does not host or stream any media itself.
> • Sign-in credentials go directly to your Audiobookshelf server. No analytics, no third-party trackers.
> • Open source under the GPLv3 license. Audit the code at github.com/JediBrooker/ShelfDrive
>
> ⚠️ REQUIRED
>
> You must have your own Audiobookshelf server running (audiobookshelf.org). This app does not provide any audiobook, podcast, or media content.
>
> Audiobookshelf and the Audiobookshelf project are not affiliated with ShelfDrive. This fork is published independently.

(~1,850 chars — leaves room to grow)

## Release notes for v0.1 (500 chars max)

> First release.
>
> • Browse and play your Audiobookshelf library from the Polestar / AAOS Car Media tile
> • Real book covers in Continue, Recent, Libraries, Series, and Discover
> • Configurable jump forward / backward (5–90 s)
> • Sign in to your server from the in-car Settings (parked only)
> • Resume from the home tile with full transport controls

## Suggested category

**Music & Audio**

## Form factor

- [x] Android Automotive OS
- [ ] Phone (later)
- [ ] Tablet
- [ ] Wear OS

## Content rating

Self-rate via Google's IARC questionnaire:
- No user-generated content surfaced by the app itself (content is on the user's server)
- No ads
- Result is expected to be **Everyone**

## Privacy policy URL

See `docs/PRIVACY_POLICY.md` in this repo — hosted on GitHub Pages at
`https://jedibrooker.github.io/ShelfDrive/PRIVACY_POLICY`

## Review notes (for the Google reviewer)

> ShelfDrive is an independent open-source fork of the GPLv3 Audiobookshelf mobile app, repackaged for Android Automotive OS. It connects only to user-provided Audiobookshelf servers (https://audiobookshelf.org) and includes no media content itself.
>
> Driver Distraction Guidelines compliance:
> • MediaBrowserService + MediaSession drives all in-car browse and playback through the OEM Car Media template.
> • The only Activity exposed to the launcher is the SettingsActivity (ACTION_APPLICATION_PREFERENCES), which is marked `distractionOptimized="false"` so AAOS auto-hides it while the vehicle is in motion. Free-text inputs (server URL, username, password) are only reachable while parked.
> • The legacy Vue WebView Activity does not have `category.CAR_LAUNCHER` and is not exposed as a car launcher entry point.
> • Not-signed-in handling: when no server is configured, the MediaBrowserService surfaces an actionable "Sign in" prompt through the Car Media template (PlaybackStateCompat error + ERROR_RESOLUTION_ACTION_INTENT) that launches the parked-only sign-in screen — the app never shows empty tabs with no guidance.
>
> ShelfDrive requires a user-provided Audiobookshelf server. A demo server and reviewer credentials are supplied in Play Console → App access so the reviewer can sign in and exercise browse + playback.
>
> Source: https://github.com/JediBrooker/ShelfDrive
