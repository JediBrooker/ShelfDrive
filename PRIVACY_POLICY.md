# ShelfDrive Privacy Policy

_Last updated: 2026-05-21_

ShelfDrive is an independent, open-source client for self-hosted [Audiobookshelf](https://audiobookshelf.org) servers. It is published by Christian Brooker as a free, GPLv3-licensed app for Android Automotive OS.

This policy explains what data ShelfDrive collects, where it goes, and what your rights are.

## Summary

ShelfDrive does not collect, store, or transmit any personal data to the app's authors or to any third party. All data you enter into the app is sent only to the Audiobookshelf server **you provide**.

## What the app stores on your device

- **Server connection details**: the URL, username, and access token for the Audiobookshelf server you sign in to. Stored locally on your car / device only. Used to authenticate API requests to your server.
- **Playback preferences**: jump forward / jump backward intervals, auto-rewind toggle, sleep-timer settings. Stored locally only.
- **Cached cover art**: book and podcast cover images are downloaded from your server and cached in the app's private cache directory to render the in-car browse view. The cache is automatically purged by Android when device storage is low. You can clear it manually via Settings → Apps → ShelfDrive → Storage → Clear Cache.
- **Playback session state**: your current book, position, and recent playback history, so the app can resume where you left off. Synced with your Audiobookshelf server when online; not shared with anyone else.

## What the app sends over the network

- Standard Audiobookshelf API requests (sign-in, library listing, cover fetch, audio streaming, progress sync) to **the server URL you enter**. No traffic is sent to any other host.
- Audio streams are fetched directly from your Audiobookshelf server.

## What the app does **not** do

- No analytics, telemetry, crash reporting, or advertising SDKs are integrated.
- No data is sent to the app authors, Audiobookshelf project, Google, or any other third party from the app itself.
- No location, contacts, microphone, or camera access is requested.

## Third-party services

ShelfDrive is distributed through Google Play. Google Play Console may collect diagnostic and install data per its own [policies](https://policies.google.com/privacy). That data is not accessible to the ShelfDrive developer beyond aggregated install / crash counts shown in the Console.

When connected to an Audiobookshelf server, that server has its own logs, retention, and policies which are under your or the server administrator's control.

## Your rights

- You can delete all local data at any time by uninstalling the app or clearing app storage in your car / device settings.
- You can revoke ShelfDrive's access to your Audiobookshelf server by tapping **Disconnect** in the in-car settings or by removing the token on the server side.

## Open source

The full source code is published at https://github.com/JediBrooker/ShelfDrive under the GPLv3 license. You can audit every network call and data store.

## Contact

For questions or concerns about this policy, file a GitHub issue at https://github.com/JediBrooker/ShelfDrive/issues or email christianbrooker@gmail.com.

## Changes to this policy

If this policy materially changes, the updated version will be committed to the same path in the repository (`PRIVACY_POLICY.md`) and the "Last updated" date at the top will reflect the change.
