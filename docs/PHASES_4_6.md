# Recording, two-phone PCM and PA soundboard

## Architecture

- Standalone: native Oboe first, AudioRecord/AudioTrack fallback. Both mix synthesized
  soundboard cues before the permanent 0.944 amplitude ceiling. Native processed
  samples enter a dedicated preallocated C++ SPSC ring; JNI polling off the audio
  callback drains PCM16 to the recording queue. Native input is negotiated at
  48 kHz with Oboe sample-rate conversion enabled.
- Recording: a dedicated bounded Kotlin SPSC queue separates the audio producer
  from a Dispatchers.IO writer. Canonical 44-byte WAV, mono PCM16/48000 Hz;
  RIFF/data lengths are patched and synced on close. Mute, stop and service
  destruction finalize recording. No recording auto-resume. Overflow counts
  are visible; storage errors are surfaced, not silently reported as success.
- Presenter: AudioRecord -> speech DSP -> soundboard -> limiter -> separate SPSC
  queues for recording and UDP. No local AudioTrack is opened (avoids feedback).
- Station: UDP/jitter worker -> SPSC -> AudioTrack worker -> optional station
  soundboard -> final limiter -> recording + external media output. Station never
  opens AudioRecord; its foreground service uses mediaPlayback, not microphone.
- Disk and networking never run in Oboe callbacks or the platform PCM loop.
  Buffers are bounded/preallocated. File and socket worker allocations are allowed.

## Use

### Record

Start audio, then tap REC. Tap REC again to finalize, or stop/mute the session.
The finished file name, sample-derived duration, SHARE and PLAY PREVIEW controls
appear inline. Preview is disabled during live audio; starting audio releases it.
Files live in app-specific external `Recordings/` on API 26+, with scoped
FileProvider read grants and MIME `audio/wav`. No broad storage permission.
Force-killing the process or power loss can leave an unfinalized file (normal WAV
finalization cannot run after process death). Uninstalling removes these files:
share anything you want to keep.

### Two phones

1. Join both phones to the same trusted Wi-Fi network (no AP/client isolation).
2. On Phone B choose Speaker Station, connect the external speaker using Android
   media routing, then tap the large start button. It advertises `_phonemic._udp.`.
3. On Phone A choose Presenter, select B's discovered name/address, then start.
4. Phone A displays connected receiver and packet RTT after station acknowledgments.
   The first valid sender address+port locks a station until stop/restart.
5. Mute/stop, external output removal or focus loss closes streams. Loss of peer
   packets/acknowledgments for 2 seconds latches MUTED; explicit restart is required.
   A station can wait indefinitely for its first presenter without opening a mic.

Wire format: exactly 972 bytes, 4-byte big-endian wrapping sequence, 8-byte
big-endian sender monotonic nanoseconds, then 480 little-endian signed PCM16
samples (10 ms at 48 kHz). Station replies with the same 12-byte header. RTT is
measured on the presenter's clock; clocks are **not** synchronized, so one-way
latency is not claimed. PCM bandwidth is approximately 0.8 Mbps plus UDP overhead.
One presenter per station. No Opus, Wi-Fi Direct, Internet relay or multi-client mixer.

The socket worker reorders within 64 sequence positions, discards duplicates and
late/out-of-window packets, starts after 30 ms, conceals missing frames with
silence, raises delay to at most 60 ms on late packets and slowly reduces it after
stable delivery. Handoff rings hold at most 4096 samples (85 ms); overflowing writes
are dropped, never allowed to grow unbounded. Scheduling, clock drift and Bluetooth
transport still add delay. Physical two-phone tests are required before PA use.

**Security:** this raw local UDP prototype has no encryption or authenticated
pairing. NSD announces availability on the LAN; first-source pinning is not
cryptographic authentication. Another LAN user could spoof/inject audio. Use only
trusted networks; the UI explicitly discloses this. Do not expose UDP 50005 publicly.

### Soundboard

Expand Soundboard during a live session and trigger Chime (587/880 Hz dual-tone),
synthetic Applause (decaying noise) or Siren (swept tone). A new trigger replaces
rather than layers the old cue, bounding render cost and gain. Cues last at most
2 seconds. Presenter cues travel with the microphone; station cues mix locally.
A hard limiter prevents sample overload, **not** all distortion: clipping is
nonlinear and no limiter guarantees protection from acoustic feedback or unsafe
speaker volume. Keep gain/volume conservative.

## Build and web distribution

Local sandbox still lacks Java/Android tooling. GitHub CI installs pinned SDK/NDK,
executes native build and `clean assembleDebug testDebugUnitTest lintDebug`, then
uploads `PhoneMic-debug`. It also copies the exact same APK to `web/app-debug.apk`
and uploads `PhoneMic-web` containing the landing page plus APK. This avoids
checking generated 17+ MB APKs into source control (existing `*.apk` ignore policy).
Download/unzip/deploy the web artifact as a unit; the source-only landing page's
relative download link is populated by CI, not a placeholder/fake APK. No web
hosting deployment is claimed.

## Verified CI result — 2026-10-15

Application commit `929ccda158282ab22bce1e8c0541c7c1ab7b6488` passed:

- `./gradlew :app:externalNativeBuildDebug`
- `./gradlew clean assembleDebug testDebugUnitTest lintDebug`
- Debug APK upload and web distribution packaging.

Workflow conclusion: **success** (exit 0).
Run: https://github.com/prabhakarmdes12-cmyk/phone-mic/actions/runs/38725214163

Artifacts:
- PhoneMic-debug: https://github.com/prabhakarmdes12-cmyk/phone-mic/actions/runs/38725214163/artifacts/11967572203
- PhoneMic-web (index.html + app-debug.apk): https://github.com/prabhakarmdes12-cmyk/phone-mic/actions/runs/38725214163/artifacts/11967272349

The first implementation run compiled and passed tests, but lint caught use of
`Environment.DIRECTORY_RECORDINGS` on API 26. A version-gated string fallback
fixed that error without raising minSdk or suppressing lint. The successful run
still has nonfatal lint/tooling warnings; no warning-free claim is made.

## Verification and remaining device work

Unit coverage includes canonical WAV header fields/overflow, SPSC wrap/overflow
and concurrent ordering, jitter reorder/loss/duplicates/sequence wrap/depth cap,
and soundboard finite bounds, termination and master mix ceiling.

Device checklist (not executed in this sandbox):
- API 26 and 34: record on both engines, stop/mute/rotate, inspect header with a
  desktop WAV tool; check sample duration and soundboard is captured; test storage
  full, rapid REC toggles and recording while screen locked.
- Share to another app; preview after stopping; start audio during preview.
- Two phones: NSD service names/collisions, Wi-Fi multicast/AP isolation, USB/wired/
  A2DP output, no local presenter playback, and no station microphone privacy light.
- Inject loss, duplicates, reordering, malformed/foreign packets and >2 s outage;
  confirm silence/mute and no automatic restart. Reconnect requires an explicit tap.
- Calls, focus loss, Bluetooth removal, rapid mode changes, foreground service
  destruction, 60-minute drift/battery tests and acoustic roundtrip measurements.

Prior conversation context overstated earlier completion: this checkout still
lacks PTT/touch lock/adaptive feedback notch implementation. This work does not
claim those unrelated features, full karaoke, multi-client mixing or zero latency.
