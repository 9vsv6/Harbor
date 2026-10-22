# Harbor for Android

A fully native Android Harbor: Kotlin and Jetpack Compose, with mpv for playback (as in
Harbor's desktop player), Media3 as the fallback, and no WebView. It follows the desktop
app in this repository (`src/`) screen for screen: Home, Movies, Shows and Anime rows,
detail pages, the source picker with Harbor's ranking and badges, the player with its
subtitle style bar, the library with tracker tabs, and Harbor's settings.

This is the `android` branch of [9vsv6/Harbor](https://github.com/9vsv6/Harbor), a fork of
[Harborofficial/Harbor](https://github.com/Harborofficial/Harbor). The desktop app and the
rest of the repository follow upstream; this folder is the Android app.

- Application id: `app.harbor.android`
- Releases: APKs on this fork's [releases](https://github.com/9vsv6/Harbor/releases), tagged
  `android-vX.Y.Z` (`Harbor_X.Y.Z_android.apk` for phones, `_android-x86_64.apk` for emulators).

## Build

JDK 21 and the Android SDK (path in `local.properties`, git-ignored).

```
./gradlew -p android-native assembleDebug      # both ABIs in one APK
./gradlew -p android-native assembleRelease    # one APK per ABI, signed if key.properties exists
```

`key.properties` (git-ignored) holds `storeFile`, `storePassword`, `keyAlias` and
`keyPassword` for release signing. The baseline profile in `baselineprofile/` is
regenerated on an emulator with `:app:generateBaselineProfile`.

## Layout

- `data/`: Stremio account and addons, TMDB, trackers (Trakt, Simkl, AniList,
  MyAnimeList, Letterboxd), debrid (Real-Debrid, TorBox, AllDebrid, Premiumize,
  Debrid-Link), ranking, settings and Harbor backup import.
- `ui/`: the screens, drawn after Harbor's (`HarborControls.kt`, `SettingsKit.kt`).
- `player/`: mpv and Media3 engines and Harbor's player chrome.

MIT, like Harbor. The APK includes GPL components (FFmpeg, Jellyfin's FFmpeg decoder,
NewPipeExtractor), so its source is this folder.
