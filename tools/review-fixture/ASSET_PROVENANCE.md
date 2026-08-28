# Review fixture asset provenance

These assets are intended only for ShelfDrive development, Google Play review
testing, and ShelfDrive store screenshots.

## `review-cover.png`

- Source: `store/shelfdrive-play-icon-512.png` in this repository
- Transformation: none; byte-for-byte copy
- SHA-256: `886471286947704f2b629d2326d402cd6b8c257784833214137a7f5d9739d708`
- Dimensions: 512 × 512 pixels

The source is ShelfDrive-owned app artwork, not a third-party book cover.

## `review-audio.mp3`

- Source: generated waveform; no source recording
- SHA-256: `70e62c8ed1a76f205f3e737eac65813cd0e1b588bdd455fd0cdb86fd55908d51`
- Duration: exactly 12 seconds
- Encoded metadata: title `ShelfDrive Sample Journey`, artist `ShelfDrive Studio`

Generation command (ffmpeg 8.0.1 on macOS):

```sh
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i 'sine=frequency=392:duration=12:sample_rate=44100' \
  -af 'volume=0.06,afade=t=in:st=0:d=0.5,afade=t=out:st=11:d=1' \
  -c:a libmp3lame -b:a 64k \
  -metadata title='ShelfDrive Sample Journey' \
  -metadata artist='ShelfDrive Studio' \
  tools/review-fixture/assets/review-audio.mp3
```

The tone is procedurally generated and contains no speech, music composition, or
third-party audio.
