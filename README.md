# Stremio Lite — step 1: skeleton

A text-menu Android TV app for old Fire TV Sticks. This first version has **sample menus only**. It proves that:

- the app installs and appears in the Fire TV app list (with its banner)
- the remote works: Up/Down move, OK opens, Back goes back, Left/Right jump a page
- a 300-line list scrolls smoothly on the stick
- the stick can hand a video to VLC (Home › Device check › Test VLC)

No account, no add-ons yet. That's step 2.

## 1. Build the APK

### Option A — GitHub (nothing to install)

1. Create a new **private** repository on GitHub, e.g. `stremio-lite`.
2. From this folder:
   ```sh
   git init -b main
   git add .
   git commit -m "Step 1: skeleton"
   git remote add origin git@github.com:<you>/stremio-lite.git
   git push -u origin main
   ```
3. Open the repo's **Actions** tab. The *Build APK* run takes about 3 minutes.
4. Open the finished run, download **stremio-lite-apk** (a zip) and unzip it to get `app-release.apk`.

### Option B — on your Arch machine

```sh
sudo pacman -S jdk17-openjdk android-tools
yay -S android-sdk-cmdline-tools-latest          # or any AUR helper
export ANDROID_HOME=/opt/android-sdk
sudo $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
sudo $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-35" "build-tools;34.0.0" "platform-tools"
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

## 2. Install on the Fire Stick over Wi-Fi

1. On the stick: **Settings › My Fire TV › About**, then click the device name 7 times to unlock Developer options.
2. **Settings › My Fire TV › Developer options**: turn on **ADB debugging** (and **Apps from unknown sources** if shown).
3. Find the stick's IP: **Settings › My Fire TV › About › Network**.
4. On your computer (`android-tools` provides `adb`):
   ```sh
   adb connect 192.168.x.x:5555      # accept the prompt on the TV the first time
   adb install -r app-release.apk
   ```
5. Stremio Lite appears under **Your Apps & Channels** (it may be at the end of the list).

Also install **VLC** from the Fire TV Appstore for the playback test.

## What to check

| Test | Where | Pass if |
|---|---|---|
| Launch | Your Apps | Blue "STREMIO LITE" banner, opens to the home menu |
| Remote | Any list | Highlight bar follows Up/Down; Left/Right jump a page |
| Long list | Top 100 Today | 300 lines scroll without stutter |
| Back | Anywhere | Goes up one screen, returns to the same line; exits from home |
| Memory | Device check | "This app is using" stays small (a few MB) |
| Player | Device check › Test VLC | VLC opens and plays the clip |

Send back the Device check numbers and anything that felt slow.

## Layout

```
app/src/main/java/app/stremiolite/
  MainActivity.kt   one activity: stack of text screens, remote keys
  Menu.kt           Row and Screen
  DemoMenus.kt      sample menus + device check (replaced in step 3)
  Player.kt         hand-off to VLC / Just Player
```
