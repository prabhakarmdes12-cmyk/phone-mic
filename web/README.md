# PhoneMic Web Landing Page & APK Download Portal

This directory contains the production-ready landing page for **PhoneMic**, styled according to the **Chiti Technologies Unified Design System v3**.

## Where to Place the APK

1. Download the generated `app-debug.apk` from GitHub Actions:
   - Run URL: https://github.com/prabhakarmdes12-cmyk/phone-mic/actions/runs/38707531521
   - Or download directly from artifact: [PhoneMic-debug](https://github.com/prabhakarmdes12-cmyk/phone-mic/actions/runs/38707531521/artifacts/11959820314)
2. Unzip the artifact to obtain `app-debug.apk`.
3. Place `app-debug.apk` directly inside this `web/` folder:
   ```
   web/
   ├── index.html
   ├── vercel.json
   ├── README.md
   └── app-debug.apk    <-- Place your compiled APK here!
   ```
4. Commit and push:
   ```bash
   git add web/
   git commit -m "feat: add landing page and APK for direct mobile installation"
   git push origin main
   ```

## Deploying to Vercel (1-Click)

1. Go to [vercel.com/new](https://vercel.com/new).
2. Import your GitHub repository: `prabhakarmdes12-cmyk/phone-mic`.
3. **Root Directory**: Select `web` (or leave default, as root `vercel.json` already points to `web`).
4. Click **Deploy**.

Your landing page will be live in ~10 seconds! You can open it on your Android phone, click **Download APK**, and install PhoneMic immediately.
