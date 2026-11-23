# Stremonino

A fast, text-menu Stremio client for old Android TV and Fire TV sticks (Android 5.1+), Android phones and Linux terminals.
No posters and no animations, just your Stremio account as plain DOS-style lists. Works well on a 2016 Fire Stick where the official app crawls.

It's a client for **your own Stremio account and add-ons**. It doesn't come with any content or add-ons.

## Install

- **Fire Stick / Android TV:** in the Downloader app, enter
  `github.com/shaz-arch/stremio-lite-/releases/latest/download/stremonino.apk`
- **Android phone:** open the same link in the browser and allow the install.
- **Linux:** see [below](#linux-terminal-app).

The app tells you when a new build is out, and updates itself from Settings.

## What it does

- **Sign in** with a code you approve on your phone (or email and password). Several profiles per device.
- **Home** shows the catalogue rows from your account's add-ons, in Stremio's order, with Continue watching, Library and Search.
  Long add-on catalogues can sit in an **Index** of folders instead of crowding Home.
- **Play best** picks a stream for you (quality, size, your preferred groups) and checks that it actually plays before handing over.
  **Choose a stream** lists them all, with broken releases marked ✗.
- **Subtitles** from your subtitle add-ons, in your languages, are passed to the player.
- **Watched marks, resume and Continue watching** sync with your Stremio account, so the official apps stay in step.
- **Next episode** offered when one finishes, using the same kind of release (binge mode).
- **Following:** shows you start are followed automatically. **New episodes** and a **Calendar** list what's out and coming.
  The **Waiting list** tracks films and seasons that have no stream yet, with phone notifications when they're playable.
- **MDBList / personal lists** from your add-ons show up as rows and count as followed.
- **Share my setup:** a QR code or link that copies your rows and settings to another device or another person's account
  (your own lists, sign-in and debrid-backed stream add-ons are not shared).
- **Player:** VLC or Just Player on Android, mpv on Linux.
- **Looks:** colour themes, clock, portrait layout on phones.

Not included: plain torrent streams (it has no streaming server, so use a debrid-backed add-on), Trakt and skip intro.

## Remote

| Button | Does |
|---|---|
| Up / Down | Move |
| OK | Open / play |
| Left / Right | Jump a page |
| Back | Up one screen; exits from Home |
| ☰ | Search |

On a phone, tap to open and scroll to move.

## Linux (terminal app)

The same menus in a terminal, playing through **mpv**. Resume, watched marks and the next episode all work, because the app follows mpv's position.

Needs Java 17+ and mpv. On Arch:

    sudo pacman -S jre-openjdk mpv
    curl -fsSL https://raw.githubusercontent.com/shaz-arch/stremio-lite-/main/setup/install-linux.sh | sh
    stremonino

Keys: ↑↓ (or j/k) move · Enter open · p play · ←→ / PgUp PgDn page · Esc back · / search · q quit.
Settings live in `~/.config/stremonino`. Updates: Settings → Check for updates.

## Building

Every push to `main` builds the APK and the Linux jar with GitHub Actions and publishes them as the latest release.
To build locally: JDK 17 and Android SDK platform 35, then `./gradlew assembleRelease :linux:fatJar`.

Code layout: `core/` holds the shared Kotlin (Stremio API, sync, stream picking, metadata, following), `app/` the Android app,
and `linux/` the terminal app (Lanterna).

Not affiliated with Stremio.
