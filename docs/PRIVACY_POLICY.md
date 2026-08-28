# ShelfDrive Privacy Policy

_Last updated: 2026-08-28_

ShelfDrive is an independent, open-source client for self-hosted [Audiobookshelf](https://audiobookshelf.org) servers. It is published by Christian Brooker as a free, GPLv3-licensed app for Android Automotive OS.

This policy explains what data ShelfDrive handles, where it goes, and how you can delete it.

## Summary

ShelfDrive's developer does not operate an analytics, advertising, or ShelfDrive cloud service and does not receive your account or listening data. To provide its core features, the app does transmit data off your vehicle to the Audiobookshelf server whose HTTPS address you enter. That server may be operated by you or by another administrator and has its own logging, retention, and privacy practices.

## Data sent to your configured server

Depending on the feature used, ShelfDrive sends the following to the configured Audiobookshelf server:

- **Account information:** server address, username, and password during sign-in; the server returns a user ID and session tokens. The password is not stored by ShelfDrive.
- **Device information:** a random install-scoped app-instance identifier, vehicle/device manufacturer and model, Android SDK version, and ShelfDrive version. Audiobookshelf uses this information to identify playback sessions.
- **App activity:** library, browse, and search requests; selected titles; playback sessions; listening position, progress, and playback history needed for resume and synchronization.
- **Media requests:** cover-art and audio-stream requests for the content in your library.

Production builds accept only HTTPS server addresses. ShelfDrive does not sell this data or send it to the ShelfDrive developer, advertisers, or data brokers.

## Data stored on your device

- **Server profile:** server URL, username, user ID, display name, access token, and any custom connection headers in app-private storage. Refresh tokens are encrypted with an Android Keystore-backed AES-GCM key. Passwords are never stored.
- **Android account record:** Android AccountManager stores only a non-secret ShelfDrive connection identifier, display name, and schema version. It stores no password, access token, refresh token, or custom authentication header.
- **Playback preferences and state:** media-control, seek, sleep-timer, current-session, listening-position, and recent-playback data.
- **Media files and artwork:** downloaded audio and ebooks you explicitly save, plus cached cover artwork used by the vehicle's system media interface.

Android app sandboxing protects app-private files, and ShelfDrive disables Android backup of its application data.

## What ShelfDrive does not do

- No analytics, advertising, telemetry, or third-party crash-reporting SDK is integrated.
- No location, contacts, microphone, or camera permission is requested.
- No account or listening data is sent to the ShelfDrive developer or the Audiobookshelf project by the app.

## Google Play and your server operator

Google Play may process install and diagnostic information under [Google's Privacy Policy](https://policies.google.com/privacy). The ShelfDrive developer may see aggregated Play Console statistics and crash reports supplied by Google.

Your configured Audiobookshelf server may retain account, request, device-session, and playback-progress records. Contact that server's administrator for its retention policy or to delete server-side records.

## Deleting or controlling your data

- Select **Disconnect** in ShelfDrive settings to remove that server profile, its session credentials, related saved playback sessions, and its Android account record from the vehicle.
- Remove ShelfDrive from Android's account settings to remove the corresponding ShelfDrive server profile and credentials.
- Clear ShelfDrive's cache to remove cached artwork.
- Clear app storage or uninstall ShelfDrive to remove all app-private settings, credentials, cached artwork, and playback state. Remove explicitly downloaded media through the app or the device's storage controls.
- Revoke active sessions or delete server-side history through Audiobookshelf or by contacting the server administrator.

## Open source

The source code is published at https://github.com/JediBrooker/ShelfDrive under the GPLv3 license.

## Contact

For privacy questions, file a GitHub issue at https://github.com/JediBrooker/ShelfDrive/issues or email christianbrooker@gmail.com.

## Changes to this policy

Material changes will be published at this same URL and reflected in the “Last updated” date above.
