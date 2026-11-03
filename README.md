# VideoDownloaderAndroid

An Android video downloader for media you are authorized to download.

## Planned capabilities
- Accept shared webpage links from Android browsers and pasted URLs.
- Detect directly exposed MP4 and supported HLS (M3U8) media.
- Prefer the highest available HLS variant when the playlist exposes bandwidth metadata.
- Show download status and save supported downloads locally.

## Limitations
This app does not bypass DRM, paywalls, login requirements, or other access controls. Media discovery depends on what the page exposes; dynamic players and protected streams may not be supported.

## Windows desktop app

A Windows 10/11 x64 desktop version is available in the [desktop](desktop/README.md) folder. It uses an embedded browser to detect media URLs and provides a single copy-link action. The Windows build is published separately from the Android APK.

The Android APK will be built with the repository's GitHub Actions workflow once the Gradle project is in place.
