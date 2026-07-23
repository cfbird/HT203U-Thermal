# HT203U Thermal

Native Android app for the Vevor/Hti **HT-203U** USB-C thermal camera with real radiometric readout — built by reverse-engineering the module live, since the stock apps only show colorized video.

## The big finding

Despite the Hti branding, the HT-203U is a rebranded **HIKMICRO Mini2Plus** core (USB `2bdf:0102`, product string "HikCamera"). It is *not* an Xtherm/InfiRay device, so none of the published HT-301/T2S+ protocols (zoom-command `0x8004`, Xtherm calibration metadata) apply. Everything below was reverse-engineered from the device itself.

### Device layout (as discovered)

USB descriptors:

- IF#0: VideoControl — input terminal (id 2), processing unit (id 5), **extension unit id 10, 15 controls, GUID `{A29E7641-DE04-47E3-8B2B-F4341AFF003B}`** (vendor controls: FFC/shutter etc. — mapping in progress), interrupt EP 0x83
- IF#1: VideoStreaming — YUY2 uncompressed (7+ frame descriptors), MJPEG, H.264; **bulk** endpoint 0x81 (maxPkt 512), alt setting 0

The interesting mode is uncompressed **256×392 @ 25 fps** (200,704 B/frame), a stacked frame:

| Rows | Content |
|---|---|
| 0–191 | **Raw thermal counts**, u16 LE (~5000–5500 at room temp; drifts with FPA until FFC) |
| 192–195 | Config/state block (contains sensor dims 256/192, FPA-tracking values; partially decoded) |
| 196–387 | Camera-rendered display image as YUY2 (chroma byte pinned to 0x80) |
| 388–391 | **Telemetry block**: magic `ffff eeee`, then device-computed max/min/avg raw values |

Raw-count → °C scale is not yet fitted (needs a two-point ice/boiling water reference — next session). The raw block is identified at runtime by chroma signature (display block has every odd byte = 0x80).

### Why the usual Android UVC libraries fail

libuvc-based libraries (saki/UVCCamera forks, UVCAndroid) fail negotiation on this device with `UVC_ERROR_INVALID_MODE (-51)` for **every** mode. This app therefore implements **UVC-over-bulk in pure Kotlin** (`BulkUvc.kt`) on the standard `UsbManager` API:

1. Claim the VS interface, PROBE/COMMIT via `controlTransfer` (probe response: `dwMaxVideoFrameSize=200704`, `dwMaxPayloadTransferSize=12288`, `bmFramingInfo=0x03`)
2. Read the bulk endpoint, reassemble payloads by header FID/EOF bits
3. No native code, no NDK — the APK is under 1 MB

## App features (current)

- Live raw-thermal display, ironbow palette, auto-exposure, min/max/center markers
- Raw counts shown for ▼/⌖/▲ (°C pending scale calibration)
- **Rotate** (90° steps, persisted), **Probe XU** (read-only walk of the 15 vendor controls), **Dump meta** (hex-dumps rows 192–195/388–391 + frame stats), **Log** (in-app trace + logcat with share/copy)
- Mode auto-cycling if a stream mode delivers no frames
- Runtime CAMERA permission (Android 10+ silently denies USB access to video-class devices without it) + standard USB permission dialog (GrapheneOS-friendly)

## Getting the APK

Every push to `main` builds via GitHub Actions and publishes to the rolling release:

**https://github.com/cfbird/HT203U-Thermal/releases/download/latest/app-debug.apk**

Or build locally: Gradle 8.9, JDK 17, Android SDK 34 — `gradle assembleDebug`.

## GrapheneOS notes

- Allow USB peripherals: Settings → USB-C port ("Allow new USB peripherals when unlocked")
- Grant Camera permission on first launch, then accept the USB dialog after plugging in

## TODO / next session

- Fit raw→°C linear scale from two reference temperatures (ice water / boiling water)
- Map the XU controls (FFC/shutter trigger first — needed to stop raw drift; possibly a factory-calibrated temperature mode)
- Finish decoding config rows 192–193 and telemetry row 388 (min/max/avg indices)
- Tap-to-measure spot meter, palettes, capture

## Credits / license

UVC access is original code on Android's USB host API. The Xtherm calibration math in `Xtherm.kt` (kept for genuine Xtherm-family devices) is ported from [stawel/ht301_hacklib](https://github.com/stawel/ht301_hacklib) (GPL-3.0); this project is **GPL-3.0**.
