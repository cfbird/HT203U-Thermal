# HT203U Thermal

Native Android app for the Hti HT-203U (sold rebadged by Vevor) USB-C thermal camera, with **real temperature readout** — not just the colorized video the stock/generic UVC apps show.

## How it works

The HT-203U is an XTherm/InfiRay-family 256×192 core (same protocol family as the Hti HT-301 and InfiRay T2S+). It enumerates as a standard UVC camera streaming "YUYV" at **256×196**:

- Rows 0–191: the image
- Rows 192–195: metadata — calibration coefficients, shutter & FPA temperatures, user parameters (emissivity, ambient temp, humidity, distance), and precomputed min/max/center raw values

Commands are sent over the UVC **Zoom (Absolute)** control:

| Value  | Effect |
|--------|--------|
| 0x8004 | Switch stream to raw 16-bit radiometric mode |
| 0x8000 | Shutter (flat-field) calibration |
| 0x8020 | Normal range (−20…120 °C) |
| 0x8021 | High range (−20…450 °C) |
| 0x80FF | Save parameters |

In raw mode each pixel is a 14-bit value; the app rebuilds a 16384-entry raw→°C lookup table from the metadata rows every few frames (full atmospheric-transmittance model: emissivity, reflected temp, humidity, distance).

The radiometry math is ported from [stawel/ht301_hacklib](https://github.com/stawel/ht301_hacklib) (GPL-3.0), so this project is **GPL-3.0** too. UVC access uses [UVCAndroid](https://github.com/shiyinghan/UVCAndroid) (`com.herohan:UVCAndroid`, Apache-2.0), which ships prebuilt libusb/libuvc — no NDK build required.

## Getting the APK

This repo builds with GitHub Actions. Push it to a GitHub repo (the `.github/` folder must be at repo root), then grab the artifact:

1. Create a new GitHub repo and push this project to it.
2. Actions tab → `build-apk` run → download `ht203u-thermal-debug-apk`.
3. Install the APK (enable "install unknown apps" for your file manager / browser).

Or build locally: open in Android Studio, or `gradle assembleDebug` (Gradle 8.9, JDK 17, Android SDK 34).

## GrapheneOS notes

- Settings → USB-C port: make sure new USB peripherals are allowed (at least "Allow new USB peripherals when unlocked").
- The app uses only the standard `UsbManager` permission dialog — plug in the camera, open the app, accept the USB permission prompt. No root, no shady polling.

## Usage

- Plug in camera → accept USB permission → the app switches it to raw mode and fires one shutter calibration automatically.
- **Calibrate**: manual flat-field correction (do this when the image drifts / after warm-up).
- **Range**: toggle −20…120 °C / −20…450 °C (also switches the camera's hardware range).
- **°C/°F**: display units.
- Overlay: ▼ min (blue marker), ⌖ center (white), ▲ max (red).

## Known limitations / next steps

- Tap-to-measure spot meter, palettes, screenshots, emissivity setting (`SET_EMISSIVITY` float command is already documented in `Xtherm.kt`'s source lib) are not implemented yet — this is the minimal radiometric pipeline.
- If your unit reports a different USB descriptor (no 256×196 YUYV mode), the app will say so on screen — open an issue with the VID:PID and supported sizes.
