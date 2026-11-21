# Stremio Lite

A text-menu Stremio client for old Android TV / Fire TV sticks (Android 5.1+). No posters, no animations: your Stremio home rows as plain lists, with streams handed to VLC.

**Install on a Fire Stick:** Downloader app → `github.com/shaz-arch/stremio-lite-/releases/latest/download/stremio-lite.apk`

## What it does

- **Sign in** with a code you approve on your phone (or email and password).
- **Home** shows every catalogue row from the add-ons on your Stremio account, in Stremio's order.
- **Lists** load a page at a time as you scroll; **details** show seasons and episodes.
- **Streams** come from all your stream add-ons at once, best quality first; OK plays in VLC.
- **Subtitles** from your subtitle add-ons are passed to the player, in your chosen language.
- **Search** (Home › Search, or ☰ on the remote) covers every add-on catalogue that supports it.
- **Settings:** player (VLC, Just Player, ask each time) and subtitle language.

Not included: plain torrent streams (no streaming server), watch history, Trakt, skip intro.

## Remote

| Button | Does |
|---|---|
| Up / Down | Move |
| OK | Open / play |
| Left / Right | Jump a page |
| Back | Up one screen; exits from Home |
| ☰ | Search |

## Building

Every push to `main` builds a release APK with GitHub Actions and publishes it as the latest release. To build locally: JDK 17, Android SDK platform 35, then `./gradlew assembleRelease`.

## Code

```
app/src/main/java/app/stremiolite/
  MainActivity.kt  one activity: stack of text screens, remote keys
  Menu.kt          Row and Screen
  SignIn.kt        link-code sign-in
  Stremio.kt       account calls and add-on manifests
  Home.kt          home rows, add-on list and connection checks
  Catalogs.kt      paged catalogue lists
  Details.kt       full details, seasons, episodes
  Streams.kt       streams from all add-ons
  Subtitles.kt     subtitles from all add-ons
  Search.kt        search across catalogues
  Player.kt        hand-off to VLC / Just Player
  Settings.kt      player and subtitle language
  Net.kt           HTTP, background threads, bundled root certificates
  DemoMenus.kt     device check and about
```
