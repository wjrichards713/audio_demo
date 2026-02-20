# Audio Streaming Client - Android

Real-time multi-channel audio streaming application for Android with end-to-end encryption, Opus codec compression, and software-mixed multi-room playback.

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Audio Pipeline](#audio-pipeline)
- [Project Structure](#project-structure)
- [Source Files](#source-files)
- [Dependencies](#dependencies)
- [Network Protocol](#network-protocol)
- [Encryption](#encryption)
- [Audio Configuration](#audio-configuration)
- [Multi-Room Mixing](#multi-room-mixing)
- [Build Requirements](#build-requirements)
- [Permissions](#permissions)
- [Setup & Usage](#setup--usage)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                   MainActivity                       │
│            (UI, permissions, channel mgmt)           │
└──────────────────────┬──────────────────────────────┘
                       │
         ┌─────────────▼──────────────┐
         │ WebSocketClientRtpAudioStream│
         │   (OkHttp3 WebSocket + UDP) │
         └──────┬──────────────┬──────┘
                │              │
       ┌────────▼────┐  ┌─────▼────────┐
       │StreamPlayer3│  │StreamRecorder │
       │ (RX/Playback│  │(TX/Recording) │
       │  + Mixing)  │  │  + Encoding)  │
       └─────────────┘  └──────────────┘
```

**Control plane**: WebSocket (WSS) for channel management, join/leave, transmit start/stop.

**Data plane**: UDP for audio packets (JSON-wrapped, AES-encrypted, Opus-encoded).

---

## Audio Pipeline

### Receive (RX) - Playback

```
UDP Socket
  │  JSON packet: { "type":"audio", "channel_id":"...", "data":"base64..." }
  ▼
JSON Parse + Base64 Decode
  │
  ▼
AES-256-GCM Decrypt (12-byte IV prepended to ciphertext)
  │
  ▼
Opus Decode (per-channel decoder, 48kHz mono, max 4800 samples/frame)
  │
  ▼
Per-Channel Jitter Buffer (ConcurrentLinkedQueue<PcmFrame>)
  │
  ▼
Software Mixer Thread (sum all channels into single buffer)
  │  - IntArray accumulator to avoid clipping
  │  - Clamp to 16-bit range
  │  - Mono → Stereo duplication
  ▼
Single AudioTrack (48kHz, stereo, 16-bit PCM, WRITE_BLOCKING)
```

### Transmit (TX) - Recording

```
AudioRecord (48kHz, mono, 16-bit PCM)
  │
  ▼
Audio Enhancement (NoiseSuppressor + AutomaticGainControl)
  │
  ▼
Opus Encode (per-channel encoder, 48kHz mono)
  │
  ▼
AES-256-GCM Encrypt (random 12-byte IV)
  │
  ▼
Base64 Encode + JSON Wrap
  │  { "type":"audio", "channel_id":"...", "data":"base64..." }
  ▼
UDP Socket Transmit
  │
  ▼
Local WavWriter (optional, saves to cache directory)
```

---

## Project Structure

```
audio_demo/
├── app/                                    # Main application module
│   ├── build.gradle                        # App dependencies & SDK config
│   └── src/main/
│       ├── AndroidManifest.xml             # Permissions & activity declaration
│       ├── java/com/home/audiostreaming/
│       │   ├── MainActivity.kt             # UI activity, channel management
│       │   ├── WebSocketClientRtpAudioStream.kt  # WebSocket + UDP networking
│       │   ├── StreamPlayer3.kt            # Audio receiver, decoder, mixer
│       │   ├── StreamRecorder.kt           # Audio recorder, encoder, transmitter
│       │   ├── AudioStreamConstant.kt      # Shared constants (sample rate, keys)
│       │   ├── Room.kt                     # Channel/room data model
│       │   ├── Member.kt                   # Channel member data model
│       │   └── WavWriter.java              # WAV file writer for local recording
│       └── res/
│           ├── layout/activity_main.xml    # Main UI layout
│           └── layout/item_channel.xml     # Channel list item layout
│
├── opuslib/                                # Opus codec native library module
│   ├── build.gradle                        # NDK build config
│   └── src/main/
│       ├── java/com/example/opuslib/utils/
│       │   ├── OpusDecoder.java            # JNI wrapper for Opus decoder
│       │   └── OpusEncoder.java            # JNI wrapper for Opus encoder
│       └── jni/
│           ├── Android.mk                  # NDK build script
│           ├── com_score_rahasak_utils_OpusDecoder.c/h   # JNI native decoder
│           ├── com_score_rahasak_utils_OpusEncoder.c/h   # JNI native encoder
│           └── opus/                       # Full Opus C source (v1.0.3)
│               ├── celt/                   # CELT codec (high quality)
│               ├── silk/                   # SILK codec (speech optimized)
│               ├── src/                    # Core Opus encoder/decoder
│               └── include/                # Public headers
│
├── build.gradle                            # Root build config
├── settings.gradle                         # Module declarations
└── gradle/                                 # Gradle wrapper
```

---

## Source Files

### `AudioStreamConstant.kt`

Shared constants used by both player and recorder:

| Constant | Value | Description |
|---|---|---|
| `SAMPLE_RATE` | 48000 | Audio sample rate in Hz |
| `HEADER_SIZE` | 9 | Packet header size in bytes |
| `AUDIO_TYPE` | 0x01 | Audio packet type identifier |
| `frameDurationMs` | 40 | Configured frame duration (actual decoded: 100ms) |
| `channel` | 1 | Mono audio channel |
| `AES_256_ALGO_KEY` | Base64 string | AES-256 encryption key |

### `StreamPlayer3.kt` - Audio Playback Engine

The core playback component with multi-room mixing support.

**Threads:**
- **Receiver thread** (`AudioReceiver`): Listens on UDP socket, decrypts, decodes Opus, pushes PCM frames into per-channel jitter buffers.
- **Mixer thread** (`AudioMixer`): Polls all channel buffers, sums PCM samples in an `IntArray` accumulator, clamps to 16-bit, writes mixed stereo output to a single `AudioTrack`.

**Key data structures:**
- `jitterBuffers`: `ConcurrentHashMap<String, ConcurrentLinkedQueue<PcmFrame>>` - per-channel decoded PCM queues
- `opusDecoders`: `ConcurrentHashMap<String, OpusDecoder>` - per-channel Opus decoder instances (each maintains internal state)
- `channelVolumes`: `ConcurrentHashMap<String, Float>` - per-channel volume (0.0 - 1.0)
- `channelSpeakerTypes`: `ConcurrentHashMap<String, String>` - per-channel speaker pan ("left", "right", "center")
- `channelPlaying`: `ConcurrentHashMap<String, Boolean>` - jitter gate state (set once on first connect, never reset)

**Jitter buffer strategy:**
- Initial gate: waits for 3 frames before starting playback (prevents initial underruns)
- Once opened, gate stays open permanently — brief gaps produce natural silence without re-gating
- Queue capped at 20 frames to prevent memory growth

### `StreamRecorder.kt` - Audio Recording & Transmission

Records from device microphone and transmits over UDP.

- `AudioRecord` at 48kHz, mono, 16-bit PCM
- Audio enhancement: `NoiseSuppressor` + `AutomaticGainControl` (when available)
- Opus encoding → AES-GCM encryption → Base64 → JSON → UDP
- Optional local WAV recording via `WavWriter`

### `WebSocketClientRtpAudioStream.kt` - Network Manager

Manages both control (WebSocket) and data (UDP) connections.

- OkHttp3 WebSocket client connects to `wss://audio.redenes.org/ws/`
- Handles messages: `connect`, `transmit_started`, `transmit_ended`, `disconnect`
- Creates/manages `StreamPlayer3` and `StreamRecorder` instances
- Keep-alive heartbeat every 10 seconds
- Routes events to `MainActivity` via listener interface

### `MainActivity.kt` - User Interface

- Channel management UI (add, connect, remove channels)
- WebSocket connection controls
- Audio transmission start/stop buttons
- Per-channel volume slider (SeekBar, 0-100%)
- Per-channel speaker select buttons (L / C / R) for stereo panning
- Runtime permission handling (`RECORD_AUDIO`)
- RecyclerView for channel list with per-channel controls

### `Room.kt` / `Member.kt` - Data Models

- `Room`: channel metadata (ID, name, join state, speaking state, members, volume, speakerType)
- `Member`: participant info (authorization ID, name, speaking state)

### `WavWriter.java` - Local Recording

- Writes PCM audio to WAV file format
- RIFF/WAVE header generation
- Saves to app cache directory

### `OpusDecoder.java` / `OpusEncoder.java` - Codec Wrappers

JNI wrappers for the native Opus codec library (`libsenz.so`).

```java
// Decoder
decoder.init(sampleRate, channels, maxFrameSize)
int samples = decoder.decode(encodedBytes, pcmShorts)
decoder.close()

// Encoder
encoder.init(sampleRate, channels, frameSize)
int bytes = encoder.encode(pcmShorts, encodedBytes)
encoder.close()
```

---

## Dependencies

| Dependency | Version | Purpose |
|---|---|---|
| `androidx.core:core-ktx` | latest | Kotlin Android extensions |
| `androidx.appcompat:appcompat` | latest | Backward-compatible UI |
| `com.google.android.material` | latest | Material Design components |
| `com.google.code.gson:gson` | 2.8.2 | JSON serialization |
| `com.squareup.retrofit2:retrofit` | 2.6.0 | HTTP client framework |
| `com.squareup.retrofit2:converter-gson` | 2.6.0 | Retrofit JSON converter |
| `com.squareup.retrofit2:converter-scalars` | 2.6.0 | Retrofit string converter |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | HTTP/WebSocket client |
| `com.squareup.okhttp3:okhttp-sse` | 4.12.0 | Server-Sent Events |
| `opuslib` | local module | Opus codec (JNI/NDK) |

---

## Network Protocol

### WebSocket (Control Channel)

- **URL**: `wss://audio.redenes.org/ws/`
- **Protocol**: Secure WebSocket (WSS)
- **Format**: JSON messages
- **Message types**: `connect`, `transmit_started`, `transmit_ended`, `disconnect`

### UDP (Audio Data Channel)

- **Server**: Dynamically provided via WebSocket response
- **Port**: Dynamically provided via WebSocket response
- **Format**: JSON-wrapped audio packets

```json
{
  "type": "audio",
  "channel_id": "555",
  "data": "base64(IV + AES-GCM-ciphertext(opus-encoded-pcm))"
}
```

**Packet flow:**
```
Sender → JSON → Base64(12-byte-IV + AES-GCM(Opus(PCM))) → UDP → Receiver
```

---

## Encryption

| Parameter | Value |
|---|---|
| Algorithm | AES-256-GCM (Galois/Counter Mode) |
| Key size | 256 bits |
| IV size | 12 bytes (prepended to ciphertext) |
| Auth tag | 128 bits (implicit in GCM) |
| Key storage | Hardcoded Base64 in `AudioStreamConstant.kt` |

**Encrypted payload layout:**
```
[IV: 12 bytes][Ciphertext + GCM Tag: variable]
```

---

## Audio Configuration

| Parameter | Value | Notes |
|---|---|---|
| Sample rate | 48000 Hz | Both record and playback |
| Channels | 1 (mono) | Opus encode/decode in mono |
| Bit depth | 16-bit PCM | `AudioFormat.ENCODING_PCM_16BIT` |
| Opus max frame size | 4800 samples | 100ms at 48kHz |
| Actual decoded frame | 4800 samples | 100ms per frame |
| Playback output | Stereo | Mono panned to L, R, or both (center) per channel |
| Mixer frame size | 1920 samples | 40ms fixed output per mix cycle |
| AudioTrack buffer | max(minBuf*4, 61440) | ~320ms buffer headroom |
| Jitter buffer gate | 5 frames | ~200ms initial buffering |
| Max queue size | 20 frames | ~2 seconds cap |

---

## Multi-Room Mixing

The application supports simultaneous playback from multiple rooms/channels using software mixing.

### Design Decisions

| Decision | Rationale |
|---|---|
| **Single AudioTrack** | Multiple AudioTracks cause device-dependent noise from Android HAL mixing |
| **No timestamp-based writes** | `WRITE_BLOCKING` naturally paces at hardware playback rate |
| **Software mixing in IntArray** | Int accumulator prevents clipping during summation |
| **Peak limiter** | Normalizes frame if peak > 32767 instead of hard-clipping individual samples |
| **Per-channel Opus decoders** | Each decoder maintains independent state for its stream |
| **One-time jitter gate** | Re-gating after brief gaps caused 300ms audible breaks |
| **Fixed 1920-sample mixer output** | Variable-size writes (1920/3840/4800) caused timing jitter and queue drain |
| **Accumulation buffer (not remainder)** | Remainder approach caused partial frames when frame sizes don't divide evenly (4800/1920=2.5) |
| **Crossfade on transitions** | 64-sample fade-out/fade-in when channel goes empty/returns prevents clicks |

### Mixing Flow (per cycle)

```
For each active channel:
  Phase 1 — Accumulate:
    1. Fill per-channel accumulation buffer from jitter queue
    2. Only proceed if accBuffer >= 1920 samples (full mixer frame)

  Phase 2 — Mix (only if full frame available):
    3. Apply channel volume (0.0 - 1.0)
    4. Apply fade-in if channel is returning from empty (64-sample ramp)
    5. Apply speaker type: "center" → both L+R, "left" → L only, "right" → R only
    6. Sum into IntArray accumulator (L+R interleaved)
    7. Shift remaining data in accBuffer to front (System.arraycopy)

  If not enough data:
    - Generate 64-sample fade-out from last sample value → 0
    - Log UNDERFLOW warning

After all channels:
  8. Peak limiter: if max |sample| > 32767, scale entire frame proportionally
  9. Write fixed 3840 stereo shorts to AudioTrack (WRITE_BLOCKING paces at 40ms)
```

### Jitter Buffer Behavior

```
Channel first seen → frames arrive → queue grows
                                        │
                              queue >= 5 frames?
                              ├── No  → skip (wait)
                              └── Yes → gate OPEN (permanent)
                                        │
                                  ┌──────▼──────┐
                                  │ Accumulate   │◄──── frames arrive
                                  │ into accBuf  │
                                  └──────┬──────┘
                                         │
                                accBuf >= 1920?
                                ├── No  → fade-out if was active, log UNDERFLOW
                                └── Yes → mix 1920 samples, shift remainder
```

### Diagnostic Logging

The mixer logs diagnostic info every 50th cycle:
```
MIX #N active=2 written=3840 peak=12345 underruns=0 underflows=0 queues=[555=4, 666=3]
```

| Field | Meaning |
|---|---|
| `active` | Number of channels that contributed audio this cycle |
| `written` | Stereo shorts written to AudioTrack (always 3840) |
| `peak` | Max absolute sample value before limiting |
| `underruns` | AudioTrack hardware underrun count (API 24+) |
| `underflows` | Cumulative count of channel queue-empty events |
| `queues` | Per-channel jitter buffer sizes at time of logging |

Warning logs:
- `UNDERFLOW ch=X #N lastSample=Y accCount=Z` — channel had insufficient data for a full mixer frame

---

## Crackling Debug History (Feb 2026)

This section documents the multi-session debugging effort to fix audio crackling when 2+ channels play simultaneously. Kept for reference if the issue needs further investigation.

### Issue 1: Multiple AudioTracks (FIXED)
- **Symptom**: Noise/artifacts when 2+ rooms play simultaneously
- **Cause**: Android HAL mixing of multiple AudioTracks introduces device-dependent noise
- **Fix**: Single AudioTrack with software mixing in `StreamPlayer3.kt`

### Issue 2: Timestamp offset mismatch (FIXED)
- **Symptom**: Overlap/gap artifacts in audio
- **Cause**: Code used 40ms frame duration but actual Opus frames are 100ms (4800 samples)
- **Fix**: Corrected `OPUS_MAX_FRAME_SIZE` to 4800

### Issue 3: Aggressive jitter re-gating (FIXED)
- **Symptom**: 300ms audible breaks during natural speech pauses
- **Cause**: Jitter gate re-closed after 500ms idle, requiring 3 frames (300ms) to re-open
- **Fix**: Jitter gate opens once on first connect, stays open permanently

### Issue 4: Variable-size mixer output (FIXED)
- **Symptom**: Crackling when 2 channels active, queues climbing to 10-12
- **Cause**: Greedy accumulation consumed up to 4800 samples/cycle, creating variable writes (3840/7680/9600 shorts). Feast/famine queue oscillation caused intermittent channel dropouts.
- **Fix**: Fixed `MIXER_FRAME_SIZE = 1920` (40ms), consistent 3840-short writes

### Issue 5: Partial frames from frame size mismatch (FIXED)
- **Symptom**: Crackling persisted after fix 4, 42 underflows in 4 minutes
- **Cause**: Some devices send 4800-sample frames (100ms). 4800/1920 = 2.5 — doesn't divide evenly. Old remainder approach produced 960-sample partial frames every 3rd cycle, creating mid-frame silence discontinuities.
- **Fix**: Accumulation buffer — only mix when full 1920 samples available. Partial data stays in buffer for next cycle.
- **Logs confirmed**: `pcmSamples=4800` for channel 666 despite "all Android" test, `underflows=42` during session

### Issue 6: Remaining minor crackling (UNDER INVESTIGATION)
- **Status**: Much improved but occasional crackling still reported
- **Observations from logs**:
  - `underruns=1-2` (AudioTrack hardware underruns) — could be GC pauses or thread scheduling
  - `underflows` still > 0 during extended playback — network jitter can still drain accumulation buffer
  - Peak values well under 32767 — clipping is NOT a factor
- **Potential next steps**:
  1. Increase jitter buffer further (5 → 8 frames) for more cushion
  2. Add volume smoothing (ramp between old/new volume across frame boundary to prevent clicks during slider drag)
  3. Investigate if crackling correlates with `UNDERFLOW` log entries
  4. Try `PERFORMANCE_MODE_LOW_LATENCY` on AudioTrack
  5. Profile GC pressure from per-packet allocations (ShortArray, PcmFrame, copyOf)
  6. Consider reducing MIXER_FRAME_SIZE to 960 (GCD of 1920 and 4800) for perfect alignment with all frame sizes

---

## Build Requirements

| Requirement | Version |
|---|---|
| Android Studio | Latest stable |
| Compile SDK | 36 |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 36 |
| Kotlin | JVM target 11 |
| NDK | 22.1.7171670 |
| Architecture | arm64-v8a |

---

## Permissions

| Permission | Required For |
|---|---|
| `INTERNET` | WebSocket connection and UDP streaming |
| `RECORD_AUDIO` | Microphone access for audio transmission |

---

## Setup & Usage

1. **Build & install** the app on an Android device (arm64).

2. **Configure connection**:
   - WebSocket URL: `wss://audio.redenes.org/ws/` (default)
   - Enter Affiliation ID, User Name, Agency Name
   - Tap **Connect**

3. **Join channels**:
   - Enter a Channel ID (room number)
   - Tap **Add Channel**
   - Tap **Connect** on the channel card

4. **Audio streaming**:
   - Tap **Start Audio** to begin transmitting (push-to-talk)
   - Incoming audio from all connected rooms plays automatically
   - Multiple rooms are mixed together in real-time

5. **Per-room controls**:
   - **Volume slider**: Drag the SeekBar on each channel card to adjust volume (0-100%)
   - **Speaker select**: Tap **L** / **C** / **R** buttons to pan audio:
     - **L** — Left ear only
     - **C** — Center (both ears, default)
     - **R** — Right ear only
