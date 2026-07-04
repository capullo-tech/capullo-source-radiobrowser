# capullo-source-radiobrowser

Radio Browser **media source** for the [Capullo Audio Platform](https://github.com/capullo-tech)
(Layer 3 - the integrator seam). It implements the `capullo-audio-contracts` SPI so the shared
delivery engine [`capullo-audio`](https://github.com/capullo-tech/capullo-audio) can broadcast
internet radio without knowing anything about Radio Browser, playlists, or track identification.

## What it does

- **Resolves stations to playable streams.** `RadioBrowserSource.mediaRequestFor(uuid)` looks up the
  station and, for container-less playlists (`.pls` / `.m3u` / `.asx`), unwraps them to the first
  stream entry (HLS manifests get an `application/x-mpegURL` mime hint) via `PlaylistResolver`,
  returning a neutral `MediaRequest`. The engine turns that into a Media3 `MediaItem`.
- **Presents a rotating queue.** Internet radio never ends, so `queue()` returns a `PlaybackQueue`
  with `isRotating = true`.
- **Assembles now-playing + identifies tracks.** `NowPlaying` starts from the station's own tags
  (name → album, country / codec / bitrate → `extras`) and is enriched with title/artist and
  streaming links (YouTube / Spotify / Apple Music) by a Shazam loop that fetches the stream itself
  - no engine coupling.

## Layout

| Package | Contents |
|---|---|
| `RadioBrowserSource`, `RadioRotationQueue` | the SPI implementation (the source seam) |
| `data/api`, `data/model` | Radio Browser Retrofit API + JSON models |
| `data/db`, `data/repository` | favorites / groups Room store + repository |
| `resolver` | `PlaylistResolver` (`.pls`/`.m3u`/`.asx`/HLS) |
| `shazam` | stream capture, signature, Shazam API, YouTube search |

The `:app` module is a minimal harness that constructs the source and reads back its queue +
now-playing, proving the library is consumable in isolation.

## Building

Requires JDK 17 and the Android SDK. Contracts resolve from jitpack, or from a sibling
`../capullo-audio-contracts` checkout via a composite build (see `settings.gradle.kts`).

```
./gradlew :app:assembleDebug
```

## Toolchain

AGP 9.1.0 · Kotlin 2.3.10 · KSP 2.3.9 · Room 2.8.4 · Retrofit 2.11.0 · OkHttp 4.12.0 - the
the org standard.
