# VideoDownloader for Windows

Portable Windows desktop audio/video to MP3 converter.

## Features
- Embedded Chromium-based browser.
- Detects common audio files (MP3, M4A, AAC, WAV, FLAC, OGG, OPUS, WebA), video files, HLS M3U8 and DASH MPD requests.
- Scans direct media URLs exposed in page HTML.
- Resolves a Bilibili MP4 URL when its public API allows it.
- Select one candidate and use the single global button to save it as an MP3 file.
- Extracts audio from supported video files and attempts HLS/DASH conversion.
- Bundles FFmpeg through imageio-ffmpeg; no separate FFmpeg installation is required.
- Does not automatically open external browsers.

## Run from source
Requires 64-bit Windows and Python 3.11.

```powershell
cd desktop
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install -r requirements.txt
python main.py
```

## Build locally
```powershell
pip install pyinstaller==6.11.1
pyinstaller --noconfirm --clean --windowed --name VideoDownloader --collect-all imageio_ffmpeg main.py
```

The executable and required Qt/FFmpeg files are in `desktop/dist/VideoDownloader/`.

## Limitations
Conversion requires an accessible media URL and an audio track. Some sites require cookies, authorization, special request headers, or anti-bot checks that the app cannot reproduce. Blob-only sources and DRM-protected streams may not work. This app does not bypass DRM, paywalls, login requirements, or other access controls.

Only save media you are authorized to download.
