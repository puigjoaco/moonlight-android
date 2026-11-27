# Moonlight Android

[![AppVeyor Build Status](https://ci.appveyor.com/api/projects/status/232a8tadrrn8jv0k/branch/master?svg=true)](https://ci.appveyor.com/project/cgutman/moonlight-android/branch/master)
[![Translation Status](https://hosted.weblate.org/widgets/moonlight/-/moonlight-android/svg-badge.svg)](https://hosted.weblate.org/projects/moonlight/moonlight-android/)

[Moonlight for Android](https://moonlight-stream.org) is an open source client for NVIDIA GameStream and [Sunshine](https://github.com/LizardByte/Sunshine).

Moonlight for Android will allow you to stream your full collection of games from your Windows PC to your Android device,
whether in your own home or over the internet.

Moonlight also has a [PC client](https://github.com/moonlight-stream/moonlight-qt) and [iOS/tvOS client](https://github.com/moonlight-stream/moonlight-ios).

You can follow development on our [Discord server](https://moonlight-stream.org/discord) and help translate Moonlight into your language on [Weblate](https://hosted.weblate.org/projects/moonlight/moonlight-android/).

## Downloads
* [Google Play Store](https://play.google.com/store/apps/details?id=com.limelight)
* [Amazon App Store](https://www.amazon.com/gp/product/B00JK4MFN2)
* [F-Droid](https://f-droid.org/packages/com.limelight)
* [APK](https://github.com/moonlight-stream/moonlight-android/releases)

## Building
* Install Android Studio and the Android NDK
* Run ‘git submodule update --init --recursive’ from within moonlight-android/
* In moonlight-android/, create a file called ‘local.properties’. Add an ‘ndk.dir=’ property to the local.properties file and set it equal to your NDK directory.
* Build the APK using Android Studio or gradle

## Authors

* [Cameron Gutman](https://github.com/cgutman)  
* [Diego Waxemberg](https://github.com/dwaxemberg)  
* [Aaron Neyer](https://github.com/Aaronneyer)  
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was
started as a project at [MHacks](http://mhacks.org).

## Native desktop fork

The `nonRootDebug` build installs beside upstream as `app.porta`, labeled
**Porta**. Select a **Native fullscreen** resolution to follow device
orientation at native panel pixels. Fixed resolutions retain upstream behavior;
Android's rotation lock is respected. Landscape/portrait changes are debounced
for 250 ms, then stop only the stream and quietly recreate the streaming activity
after teardown. No host-app quit request is sent. A compatible Sunshine host can
retain its virtual display during the brief reconnect.

Both `/launch` and `/resume` carry the optional HTTPS query parameter
`virtualDisplayScale`, derived from Android logical density (`densityDpi / 240`)
and rounded to a 25% step in the 100–400% range. A 600 dpi device therefore uses
250%, retaining a 576×1248 logical desktop at 1440×3120 physical pixels.
**Desktop scale** in streaming settings offers **Auto** (the default) or an
explicit 100–400% override in 25% steps, applied on the next connection.
Compatible Sunshine uses it only
for the current virtual output; other hosts ignore it. No native library or
moonlight-common-c changes are needed.

Build and focused checks (Android SDK 37 / NDK version from `app/build.gradle`):

```sh
./gradlew testNonRootDebugUnitTest assembleNonRootDebug lintNonRootDebug --no-daemon
```

APK: `app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk`.
The JVM tests cover density rounding, native portrait/landscape dimensions, and
axis-change reconnect policy. Real-device rotation and teardown timing require
a paired device and a live host check.

In **Trackpad** + **Remote desktop mouse mode**, finger motion uses fullscreen
view pixels on both axes before Moonlight converts it into absolute packets.
This gives equal visual motion in portrait and landscape and bypasses host mouse
acceleration. Host desktop scale is applied by Sunshine, not a second time here.
Game-mode mouse input and two-finger scroll sensitivity retain upstream behavior.

For a live measurement, first obtain a confirmed hands-off window and install
the APK on the paired phone. Open the stream manually, then run
`tools/check-trackpad.py --serial <phone-ip>:<port> --width 1440 --height 3120 --scale 2.5 --hands-off-confirmed`.
Use the current port from `~/Android/Sdk/platform-tools/adb mdns services`.
The script uses only that ADB binary and the selected device. It sends 100px horizontal, vertical and diagonal swipes at two speeds,
reads `workspace.cursorPos`, and expects 40 logical pixels per axis at scale 2.5.
Reconnect manually in landscape and repeat with width/height swapped. Repeat at
scale 1, expecting 100 logical pixels. Keep the cursor away from desktop edges.
Then manually check one/two-finger taps, drag, two-finger scroll and direct-touch
tap placement. The script does not measure visual latency, cursor rendering or
absolute-touch taps.
