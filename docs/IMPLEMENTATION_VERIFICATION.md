# Implementation and verification

## Build

Use JDK 17, Android SDK 34, NDK 26.1.10909125 and CMake 3.22.1.
The checked-in Gradle 8.6 wrapper includes both scripts and its JAR.

```sh
./gradlew :app:externalNativeBuildDebug
./gradlew clean assembleDebug testDebugUnitTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Android verification GitHub workflow executes these build tasks and uploads
`PhoneMic-debug`. Release builds require distributor-provided signing; they no
longer silently use a debug key.

## Actual sandbox results (2026-10-15)

Both requested Gradle invocations were attempted. Both failed before Gradle
startup: `JAVA_HOME is not set and no 'java' command could be found in your PATH`.
No Android SDK, NDK, CMake, emulator or physical device was available. A download
probe to dl.google.com failed; this environment restricts outbound hosts.
**No successful assembleDebug, APK, installation or hardware test is claimed.**
`git diff --check` passed. DSP JUnit tests are provided but could not run here.

## Successful GitHub CI Runs & Hardware Hardening (2026-10-22)

Verified latest application commits (`aa8010a`, `120c5da`) on Ubuntu 24.04 with JDK 17:

- `./gradlew :app:externalNativeBuildDebug` passed (exit 0).
- `./gradlew clean assembleDebug testDebugUnitTest lintDebug` passed (exit 0).
- Generated and uploaded both `PhoneMic-debug` APK and `PhoneMic-web` distributions.
- **Run ID:** [39820861771](https://github.com/prabhakarmdes12-cmyk/phone-mic/actions/runs/39820861771) (Status: `completed`, Conclusion: `success`).

### Physical Device "Connecting" Crash Resolutions:
1. **Android 14 SystemUI Notification Vector Rejection:**
   - Previous vector icon (`R.drawable.ic_microphone`) contained dark `#121416` background fills, causing SystemUI to throw `RemoteServiceException$BadForegroundServiceNotificationException`.
   - Replaced with standard platform system drawables (`android.R.drawable.ic_btn_speak_now`, `ic_media_pause`, `ic_menu_close_clear_cancel`) guaranteed to pass SystemUI validation on all Android OS versions.
2. **Android 14 Foreground Service Contract:**
   - On Android 14 (API 34), `startForegroundWithNotification()` is called synchronously right inside `onStartCommand()`. Calling `stopSelf()` without `startForeground()` was eliminated.
   - Dispatched clean single FGS types: `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` for Receiver Hub; `ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE` for mic capture roles.
3. **Physical Microphone Hardware Rate & ADC Format:**
   - Removed fixed `setSampleRate(48000)` and resamplers on `inBuilder`. The input stream now opens at the device's native clock rate and configures output to match.
   - Added automatic fallback to 16-bit integer PCM (`AudioFormat::I16`) with real-time float conversion in `onAudioReady()`.
4. **Dual Release Bundles:**
   - Extracted verified original stable binary `web/app-v1.0-stable.apk` (21,997,887 bytes) alongside latest multi-mic `web/app-debug.apk` (22,231,988 bytes).

## Implemented architecture

- Process-scoped AudioSessionManager; activity observes StateFlow and does not
  own capture lifetime. Foreground service starts before either engine opens.
- Native first, platform AudioRecord/AudioTrack on false, initialization exception
  or missing native library. Permission denial prevents opening audio.
- Notification MUTE stops capture/playback; UNMUTE explicitly starts a new
  session. STOP/service destruction closes audio and releases the wake lock.
- Output removal and audio-focus loss stop capture, retain a muted state and
  never auto-resume. Route selection follows Android's media output routing.
- Platform worker preallocates PCM and DSP storage; UI metering is polled outside
  the audio loop. Native SPSC initialization precedes callbacks and DSP filter
  ownership is confined to the output callback.
- Smoothed input gain and permanent -0.5 dBFS hard ceiling in both engines.

## Chiti UDS V3 adaptation

Source: https://github.com/chititech-web/Chiti_Technologies_Design_System
`design system v3/tokens.json` inspected through GitHub API. Dark background,
layered surfaces, violet primary, cyan secondary, muted text, 4/8/16/24 spacing
and rounded cards are adapted to Compose. System typography remains the offline
fallback (Outfit/Inter font files are not bundled). Live microphone status uses
explicit text as well as color; controls scroll on small screens and haptics
confirm mic toggling. No webview or remote assets are required.

## Required device acceptance tests (not yet executed)

1. Fresh install: deny microphone, tap button, grant on retry; no capture before
   permission. Denying notifications must not prevent audio.
2. Start native on ARM64 hardware, test platform fallback on emulator; speak and
   confirm live meters and audible media output. Test all gain positions.
3. Select Natural/Clear/Loud; listen for tone change, clipping and transition
   artifacts. Repeat for wired/USB/A2DP output.
4. Disconnect the active external speaker during speech: capture must stop and
   never resume on reconnection without an explicit tap.
5. Notification mute/stop while screen locked; rotate/recreate activity; rapid
   toggles; revoke permission; incoming cellular and VoIP calls; audio focus
   denial. Confirm no automatic rebroadcast.
6. Run 60 minutes and measure underruns, battery use and actual acoustic delay.

## Remaining limitations

Bluetooth transport delay is device/codec dependent and not measured; the old
fabricated 126 ms GOOD badge was removed. No sub-25 ms end-to-end guarantee is
made. The adaptive feedback notch, full compressor/noise gate, karaoke reverb,
advanced effects/settings and calibration are not implemented. A hard limiter
prevents digital clipping but **does not prevent acoustic feedback or guarantee
safe listening levels**; keep speaker volume low and the phone away from it.
Fallback tone shaping is simpler than native biquad EQ. Native preset changes
are now race-free but artifact-free transitions still need listening tests.
Call protection currently depends on audio-focus loss; telephony/VoIP behavior
must be tested across devices. No call audio is intentionally captured.


## Phases 4–6 follow-up

Recording, WAV share/preview, NSD/UDP two-phone mode and the synthesized PA
soundboard are now implemented. See [PHASES_4_6.md](PHASES_4_6.md) for current
architecture, successful CI evidence, APK/web artifact links and remaining
hardware testing. Earlier sections describe the initial dual-engine milestone.
