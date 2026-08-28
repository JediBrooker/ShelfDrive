# ShelfDrive — Google Play listing draft

Candidate listing copy for package `com.jedibk.shelfdrive`. Confirm every
feature against the final v130 Play-delivered build before pasting it into Play
Console.

## App name

ShelfDrive

## Short description

> Audiobooks and podcasts from your self-hosted Audiobookshelf server, in the car.

80 characters; Google Play's limit is 80.

## Full description

> Play audiobooks and podcasts from your self-hosted Audiobookshelf server on
> Android Automotive OS.
>
> ShelfDrive is an independent, open-source Audiobookshelf client. Connect it
> to a server you use, then browse and play your library through the vehicle's
> built-in system media interface.
>
> Designed for Android Automotive OS
>
> • Browse through the system's car media experience
> • Resume listening from Continue and Recent views
> • Browse libraries, books, series, collections, and discovery shelves
> • Search your server library using supported system media search
> • Use system and steering-wheel media controls for play, pause, resume, and
>   backward/forward jumps
> • Keep server setup and sign-in in a parked-only Settings screen
>
> Playback controls
>
> • Stream audio from your configured server
> • Adjustable jump-forward and jump-back intervals
> • Playback-speed controls
>
> Privacy and data control
>
> • ShelfDrive connects to the Audiobookshelf server address you provide
> • The app has no advertising, analytics, or third-party tracking SDK
> • Before sign-in, the app explains the account, device, search, and playback
>   data sent to your chosen server
> • Production connections require HTTPS
> • Disconnecting removes the server profile and credentials stored by
>   ShelfDrive on the vehicle
> • Source code is available under the GPLv3 licence at
>   github.com/JediBrooker/ShelfDrive
>
> Audiobookshelf server required
>
> ShelfDrive does not include or host audiobooks, podcasts, or other media. You
> must have access to a compatible Audiobookshelf server and account. The
> operator of that server controls its content, logs, retention, and
> server-side deletion.
>
> ShelfDrive is independently published and is not affiliated with or endorsed
> by the Audiobookshelf project.

Before use, verify the exact browse labels, media-search behavior, playback
speed, and jump controls against the final v130 release. Remove any line that is
not exercised successfully.

## Release notes for version code 130

> • Fixed an Android Automotive OS media-service startup race that could cause
>   repeated crashes when a network was already active
> • Improved media-service lifecycle, browse, search, network, and malformed
>   response handling
> • Added a clear pre-sign-in data disclosure and an in-app privacy summary
> • Restricted production server connections to HTTPS
> • Improved artwork caching and fallback behavior for the system media UI

## Category and form factor

- Category: Music & Audio
- Android Automotive OS: enabled
- Phone, tablet, Wear OS, Android TV: not enabled for this artifact

## Content rating

Complete Google's IARC questionnaire using the functionality in the submitted
artifact and the content accessible through the reviewer account. Do not
pre-claim an “Everyone” result: ShelfDrive displays media metadata and artwork
from an externally managed server, and the Console answers must match what the
reviewer can access.

## Privacy policy URL

Planned URL:
`https://jedibrooker.github.io/ShelfDrive/PRIVACY_POLICY`

**Submission blocker:** as of 28 August 2026, that URL serves an older policy
that says no personal data is transmitted to any third party, despite the app
transmitting it to a configured server that can be third-party operated.
Publish the current [`docs/PRIVACY_POLICY.md`](docs/PRIVACY_POLICY.md) and
verify the public page before using this URL in a submission.

## Listing assets

- Icon: `store/shelfdrive-play-icon-512.png` (512 x 512)
- Feature graphic: `store/shelfdrive-feature-graphic-1024x500.png`
  (1024 x 500)
- Screenshots: `store/screenshots-aaos-play/`

The current four-image set shows generic AAOS sign-in/settings UI, contains no
third-party cover art or private credentials, uses the exact required portrait
and landscape dimensions, and is encoded as 24-bit RGB PNG without alpha.
Before upload, verify every image against the final Play candidate and recapture
any visible UI that changed. Any added browse/playback images must use only
original, licensed, or documented public-domain media. Do not expose a real
server address, username, password, or token.

## Review notes

> ShelfDrive is an Android Automotive OS media app for an externally managed
> Audiobookshelf server. It contains no media catalog of its own. A working
> HTTPS review server, credentials, library name, and rights-cleared test title
> are provided in Play Console App access.
>
> Browse and playback use Android's `MediaBrowserService` and media session so
> the generic OEM system media template renders the driving experience. The
> only app activity in this AAOS artifact is Settings for server setup, sign-in,
> privacy information, and playback preferences. It is marked not
> distraction-optimized, so text entry and setup are parked-only. No phone or
> custom car-launcher activity is exposed.
>
> On first sign-in, ShelfDrive names the destination server and explains the
> account, device, search, and playback data that will be sent there. The user
> must affirmatively select **Continue and sign in** before transmission begins.
> Production builds require HTTPS.
>
> Version 130 fixes the version 129 startup crash by constructing the media
> manager before Android can invoke the registered network callback. Exact
> access steps and the verification-backed response are supplied separately.

## Official listing references

- AAOS screenshot requirements:
  https://support.google.com/googleplay/android-developer/answer/9866151
- Car app quality criteria:
  https://developer.android.com/docs/quality-guidelines/car-app-quality
- Media voice actions:
  https://developer.android.com/training/cars/media/voice-actions
- Metadata accuracy:
  https://support.google.com/googleplay/android-developer/answer/9898842
- Intellectual property:
  https://support.google.com/googleplay/android-developer/answer/9888072
- User Data and privacy policy:
  https://support.google.com/googleplay/android-developer/answer/10144311
