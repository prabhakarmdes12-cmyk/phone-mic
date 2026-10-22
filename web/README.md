# PhoneMic Web Landing Page & APK Download Portal

This directory contains the production-ready landing page for **PhoneMic**, styled according to the **Chiti Technologies Unified Design System v3**.

## Bundled APK Distributions

This directory hosts two distributions for direct download:

| File | Edition | Size | Description |
|---|---|---|---|
| [`app-debug.apk`](./app-debug.apk) | **v1.2 Beta (Latest)** | ~22 MB | Multi-Mic Hub (up to 8 phones), LAN-1 Walkie-Talkie Mesh, Vocal Auto-Ducking, Hardware Volume PTT. |
| [`app-v1.0-stable.apk`](./app-v1.0-stable.apk) | **v1.0 Stable (Original)** | ~22 MB | Original solo wireless microphone release with rock-solid single-phone stability. |

```
web/
├── index.html            # Production landing page with live sound demo
├── vercel.json           # Vercel deployment configuration
├── README.md             # This document
├── app-debug.apk         # Latest v1.2 Beta APK
└── app-v1.0-stable.apk   # Original Stable v1.0 APK
```

## Interactive Web Sound Demo
`index.html` includes an interactive in-browser audio simulation powered by the Web Audio API:
- Captures real microphone audio from the user's browser (with explicit permission).
- Renders responsive 3D tilt effects on the mobile device mockup.
- Demonstrates real-time vocal auto-ducking on continuous acoustic background music.

## Deploying to Vercel (1-Click)

1. Go to [vercel.com/new](https://vercel.com/new).
2. Import your GitHub repository: `prabhakarmdes12-cmyk/phone-mic`.
3. **Root Directory**: Select `web` (or leave default, as root `vercel.json` already points to `web`).
4. Click **Deploy**.

Your landing page will be live in ~10 seconds! You can open it on your Android phone, click **Download APK**, and install PhoneMic immediately.
