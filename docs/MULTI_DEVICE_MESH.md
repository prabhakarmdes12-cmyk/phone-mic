# Multi-device mixer and LAN walkie-talkie

## Scope and baseline

The v1.2 snapshot from main (`7d99f61`) is imported into the existing Arena branch;
no branch switch or main push is required. Its compact default screen, scenarios,
voice presets, BGM, recording, touch lock and expandable tools remain in place.

Roles use `NetworkRole.ALL = values().toList()` (no dynamic enum `entries` calls):
Single Phone, Wireless Mic, Speaker Hub (Multi-Mic), Walkie-Talkie Mesh.

## Speaker Hub

1. Connect hub output to the PA using Android media routing (USB/AUX preferred for
   latency; Bluetooth transport adds delay).
2. In Advanced > Network & Tools choose Speaker Hub, then start it with the main
   button. Each presenter chooses Wireless Mic, discovers/selects the hub, starts
   its microphone and sends 480-sample PCM16 frames at 48 kHz.
3. A compact Connected Microphones (N) summary appears above master gain. Expand
   Advanced for individual model name, RMS, gain (0–200%), mute and loss counts.

The UDP worker demultiplexes by source IP+port into a ConcurrentHashMap backed by
an eight-slot preallocated pool. Each peer owns its own jitter state and frame;
one missing microphone never stalls the other seven. Playout sums a Float master
bus at 10 ms intervals. A unity-gain region below 0.7 amplitude and a tanh soft
knee saturate at **0.944 (-0.5 dBFS)**; PCM conversion cannot wrap. The playback
worker applies smoothed master trim and the final limiter again after local cues.
Stale channels expire after 3 seconds; an empty hub keeps waiting for new mics.
Excess devices are ignored rather than growing memory/CPU without bounds.

## Walkie-Talkie LAN-1

All peers must join the same trusted Wi-Fi network, with multicast enabled.
Select Walkie-Talkie Mesh in Advanced. Hold the on-screen action button, or either
volume key **while the activity is in the foreground**, to join/speak. Releasing
PTT stops and releases AudioRecord but leaves reception running. Leave Channel or
the notification STOP closes everything. Volume keys are not intercepted globally
from other apps or a locked/background activity (Android limitation).

- Multicast `239.255.42.99:50005`, TTL 1, bound to the active Wi-Fi interface.
- Android WifiManager MulticastLock acquired on session start, released on stop,
  setup failure or worker failure. VPNs hiding the Wi-Fi interface are rejected.
- Idle has no open AudioRecord, no continuous silence playback and no TX polling:
  TX/playback workers park, and RX blocks waiting for datagrams (1 s idle timeout).
  The foreground service/wake lock and telemetry still have a battery cost;
  actual power consumption is not measured.
- A separate preallocated TX worker wakes on PTT; RX switches to short timeouts for
  per-peer jitter/mixing when peers are present. Local multicast loopback is
  disabled and cached local interface addresses reject self packets.
- Contention policy is simultaneous summation, not a guaranteed exclusive floor.
  Listening peers hear the mixed active talkers. Local playback is silenced while
  transmitting (half-duplex acoustic feedback precaution). No sidetone.
- PTT release, pointer cancellation, Activity pause or focus/output loss releases
  microphone capture. A call/focus loss mutes the session and never auto-transmits
  on focus return. Starting/rejoining explicitly is required after a mute.
- Notification and UI distinguish listening from transmitting. Foreground service
  promotion must succeed before audio/network initialization; the mic/mediaPlayback
  service type requires microphone permission even though idle capture is closed.

Audio packets stay backward-compatible: 4-byte big-endian wrapping sequence,
8-byte big-endian sender monotonic timestamp, 960 bytes little-endian signed PCM16.
Optional `PMIC` metadata packets carry a sanitized UTF-8 model name and burst token;
legacy 68-byte name metadata is accepted, new 72-byte metadata adds the token.
A fresh PTT token resets just that peer's jitter timeline without resetting gain
or mute. Repeated tokens do not flush buffered frames. Metadata is UDP too: loss
can delay discovery/name or burst synchronization until the periodic retry.

## Real-time boundaries and limits

- Steady-state PCM codec, mixing and jitter work reuse preallocated arrays and
  datagrams. Audio callbacks do no socket/file I/O. UI snapshots are built on
  telemetry, not render(). Peer admission/map nodes and name metadata allocate on
  the background socket/control path, never on the audio callback.
- Android's DatagramSocket implementation itself can allocate addresses and timeout
  exceptions. This is not a claim that Android networking is globally allocation-
  free; the PCM render path is kept separate from that framework I/O.
- All network audio is **unencrypted and unauthenticated**. Sender names and IP
  addresses are not identity verification. Trusted LAN only; do not expose the
  port to the Internet. There is no reliable pairing, ban list or Internet relay.
- Jitter starts at 30 ms and adapts to at most 60 ms, plus hardware/transport delay
  and capture warm-up. Therefore **<20 ms end-to-end latency is not promised**.
  RTT is reported only by unicast presenter/hub acknowledgment; unsynchronized
  clock subtraction is not presented as one-way latency.
- Bounded handoff queues may drop writes under overload. No Opus/compression or
  lossless network transport is claimed. Eight streams use roughly 6.4 Mbps plus
  transport overhead. Multi-phone Wi-Fi/A2DP stability still needs device testing.
- Walkie recording currently captures the receive/station bus, not a separate
  mix-minus recording of the local microphone. BGM/cues should be tested per role.
- Limiting prevents digital sample overload, not all distortion, feedback or unsafe
  volume. Keep phones away from PA speakers and test at low volume first.

## Tests and device validation

`MultiChannelMixerTest` covers 2/4/8 microphones, independent reordering, missing
frames, per-channel gain/mute, monotonic/symmetric saturation, invalid float guard,
bounded admission, 3 s expiry and clean pool reuse, PCM header/sign roundtrip,
sequence wrap, and new/repeated PTT burst tokens preserving channel controls.
Existing DSP, WAV, ring, soundboard and jitter tests remain in the CI suite.

Required manual tests (not run in this sandbox):
- Two, four and eight physical presenters on a hub, mute/gain while speaking,
  output loss/focus loss, stale cleanup, ninth device rejection and reconnection.
- Three-phone walkie, simultaneous presses, both volume keys, rapid press/release,
  cancelled touch, rotation/background/screen lock and volume keys outside the app.
- Call interruption and permission revocation during PTT/foreground startup; no
  silent microphone restart after resume, reconnect, process death or reboot.
- Wi-Fi AP isolation/multicast filtering, VPN and network switch, IPv4 interface
  selection, multicast-lock release on failed start/stop, packet loss/reordering,
  warm-up latency and at least 60 minutes of battery/clock-drift testing.

Build artifacts: CI runs native build then `clean assembleDebug testDebugUnitTest
lintDebug`, uploads PhoneMic-debug and PhoneMic-web (landing page + freshly built
APK). The checked-in web APK inherited from v1.2 is not automatically replaced by
pushing source; use the latest CI web artifact for the new build. No deployment to
main or a live web host is claimed.
