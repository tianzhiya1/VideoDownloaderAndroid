# VideoDownloader for Windows

Windows desktop companion to the Android app.

## Features
- Embedded Chromium-based browser (Qt WebEngine).
- Detects media URLs requested by pages: MP4 and common video extensions, HLS M3U8, and DASH MPD.
- Parses direct media URLs exposed in page HTML.
- Resolves a current Bilibili MP4 URL when the public API allows it.
- Select one candidate and use the single global button to view and copy its URL.
- Does not automatically open external browsers.

## Build locally
Requires 64-bit Windows and Python 3.11.

```powershell
cd desktop
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install -r requirements.txt
pip install pyinstaller==6.11.1
pyinstaller --noconfirm --clean --windowed --name VideoDownloader main.py
```

The executable and required Qt files are in `desktop/dist/VideoDownloader/`.

## Limitations
Detection depends on media URLs exposed by the page. Some dynamic players, blob-only sources, DRM-protected streams, login-gated media, anti-bot checks, and streams requiring special request headers may not work. This app does not bypass DRM, paywalls, login requirements, or other access controls.

Only download media you are authorized to download.
