# ShelfDrive AAOS review fixture

This directory contains a deterministic, no-dependency local HTTP fixture for
end-to-end Android Automotive debug testing and rights-safe screenshot capture.
Every identity, title, progress record, and media file is synthetic. Do not add
exports, credentials, covers, or logs from a real Audiobookshelf server here.

## Run

From the repository root:

```sh
python3 tools/review-fixture/review_server.py
```

In the Android Emulator, connect the **debug** ShelfDrive build with:

- Server: `http://10.0.2.2:13378`
- Username: `reviewer`
- Password: `ShelfDriveReview!`

The server deliberately binds to `0.0.0.0` so the emulator can reach it. It is a
development fixture, so stop it after capture and do not expose port 13378 beyond
the local development network. Production ShelfDrive builds correctly require
HTTPS and therefore cannot connect to this HTTP fixture.

Run the complete contract test with:

```sh
python3 tools/review-fixture/self_test.py
```

The self-test launches an isolated loopback server on a random port and exercises
every endpoint below, including failed authentication and an ExoPlayer-style byte
range request.

## Fixed review content

- Library: **ShelfDrive Review Library**
- Title: **ShelfDrive Sample Journey**
- Author/narrator: **ShelfDrive Studio**
- Duration: 12 seconds
- Initial progress: 3 seconds (25%)
- Server compatibility version: 2.22.0

`assets/review-cover.png` is a byte-for-byte copy of the repository-owned
`store/shelfdrive-play-icon-512.png`. `assets/review-audio.mp3` is an original
low-volume 392 Hz test tone generated locally with ffmpeg; it contains no speech,
music, or third-party source recording.

## Endpoint contracts

All `/api/...` routes require `Authorization: Bearer review-access-token`, except
the cover route. Unknown routes return a JSON 404. Successful mutation routes
return `{}`.

| Method | Path | Response used by ShelfDrive |
| --- | --- | --- |
| GET | `/ping` | `{"success":true}` |
| POST | `/login` | `user` with `id`, `username`, access/refresh tokens and `mediaProgress`; `serverSettings.version` |
| POST | `/api/authorize` | `user.mediaProgress` |
| GET | `/api/me` | `id`, `username`, `mediaProgress` |
| GET | `/api/libraries?include=stats` | `libraries[]` with folder and stats fields |
| GET | `/api/libraries/review-library/personalized` | raw shelf array with `recently-added` and `discover` book shelves |
| GET | `/api/libraries/review-library/items?...` | `results[]` containing the fixture `LibraryItem`; accepts the app's filter/sort query variants |
| GET | `/api/libraries/review-library/series?...` | `results[]` with one series and embedded book |
| GET | `/api/libraries/review-library/authors` | `authors[]` with one author |
| GET | `/api/libraries/review-library/collections?...` | `results[]` with one collection and embedded book |
| GET | `/api/libraries/review-library/search?q=...` | `book[]` wrappers plus empty `podcast`, `series`, and `authors` arrays |
| GET | `/api/me/items-in-progress` | `libraryItems[]`; each item includes `progressLastUpdate` |
| GET | `/api/items/review-item?expanded=1` | expanded fixture `LibraryItem` |
| GET/HEAD | `/api/items/review-item/cover` | token-free PNG artwork |
| POST | `/api/items/review-item/play` | direct-play `PlaybackSession` with one MP3 track |
| GET | `/api/session/review-session` | current `PlaybackSession` |
| POST | `/api/session/review-session/sync` | updates in-memory current time |
| POST | `/api/session/review-session/close` | closes the in-memory session |
| POST | `/api/session/local` | accepts a locally queued session |
| POST | `/api/session/local-all` | `results[]` keyed by submitted session IDs |
| GET/PATCH | `/api/me/progress/review-item` | reads or updates `MediaProgress` |
| GET/HEAD | `/public/session/review-session/track/0` | token-free MP3 with single-range HTTP support |

## App assumptions captured by the fixture

- Native sign-in posts JSON username/password to `/login`, sets
  `x-return-tokens: true`, and accepts `user.accessToken` (or legacy `user.token`).
- `ApiHandler` wraps a raw top-level JSON array as `value`; the personalized route
  must therefore return an array, while libraries/items return named object fields.
- Browse items must include `media.numTracks` or non-empty `media.tracks`, otherwise
  ShelfDrive filters them out as unplayable.
- The continue-listening response is a full `LibraryItem` augmented with the
  top-level `progressLastUpdate` field; user progress is also supplied by
  `/api/authorize`.
- With server version 2.22.0, ShelfDrive builds direct-play URLs as
  `/public/session/{sessionId}/track/{trackIndex}` and does not put access tokens in
  media URLs. ExoPlayer can issue byte-range requests, so the fixture returns 206
  and `Content-Range` for a valid single range.
- Cover metadata is token-free for server versions 2.17.0 and newer. The fixture's
  cover endpoint is therefore intentionally public and contains only owned art.
- The fixture is HTTP by design and is reachable only from debug builds; release
  builds enforce HTTPS.
