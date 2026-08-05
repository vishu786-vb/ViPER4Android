# ViPER4Android Rootless

Material Design 3 UI for ViPER4Android FX. Full feature set of the ViPER4Android DSP engine with a modern interface.

## Features

### Audio Effects

- Output Volume / Channel Pan / Limiter
- Playback Gain Control (AGC)
- LUFS Targeting
- Multiband Compressor (5-band)
- FET Compressor

- ViPER Bass (Natural / Pure Bass+ / Subwoofer)
- ViPER Bass Mono (original v0.5.0 algorithm)
- Psychoacoustic Bass Enhancement
- ViPER Clarity (Natural / OZone+ / XHiFi)
- Tube Simulator
- AnalogX

- ViPER-DDC (Digital Device Correction)
- Spectrum Extension (VSE)
- IIR Equalizer (10 / 15 / 25 / 31 bands)
- Dynamic EQ (up to 8 bands, per-band threshold/attack/release)

- Convolver (IRS impulse response loading)
- Field Surround (Colorful Music)
- Differential Surround
- Stereo Imager (3-band width control)
- Headphone Surround+ (VHE)
- Reverberation
- Dynamic System (headphone virtualization)

- Auditory System Protection (Cure crossfeed)
- Speaker Optimization (speaker-only)

---

### App Features

- Material Design 3 with dynamic theming
- AIDL and legacy (non-AIDL) HAL support
- Automatic headphone / speaker detection with instant switching
- Per-device profile management (auto-save on device change and app background)
- Preset import / export
- Global mode and per-app mode
- In-app log viewer (tap `Driver Version` 7 times in Settings)

## Installation

1. Download the latest APK from the [Releases](https://github.com/likelikeslike/ViPER4Android/releases)
2. Install the APK
3. Flash the Magisk module from [ViPERFX_RE](https://github.com/likelikeslike/ViPERFX_RE) (or the AIDL variant)
4. Reboot
5. Open the app and tune the settings.
6. Enjoy

## Q&A

- **What is Global Mode?**

    > Creates a single AudioEffect on session ID 0 (the global mix). The Android audio framework routes all audio through session 0, so one effect instance processes everything. This is the simplest and most compatible mode.

- **What is Per-App Mode?**

    > Creates a separate AudioEffect for each audio player's session ID. The DSP processes each app's audio independently. Requires root access.

- **Why does Per-App Mode require root?**

    > On modern Android (API 34+ / Android 14+), the framework no longer broadcasts `OPEN_AUDIO_EFFECT_CONTROL_SESSION`, and `AudioManager.getActivePlaybackConfigurations()` returns anonymized session IDs (`sessionId:0, u/pid:-1/-1`). The app uses `AudioPlaybackCallback` to detect playback state changes, then retrieves the real session ID via `su -c "dumpsys audio"`, which requires root.

- **What is Per-Device Profile?**

    > Each audio device (e.g., "Galaxy Buds Pro", "Airpods", "Speaker") gets its own saved effect settings. When you connect a device, the app automatically loads its profile. When you disconnect or switch, the current settings are saved to that device's profile. Settings are also saved when the app goes to the background (home button, app switch), so nothing is lost even without a device change. You can manage profiles in the Devices dialog: rename, save, load, or delete per-device settings.

- **What is AIDL Mode?**
    > Android 14+ introduced a new Audio HAL interface based on AIDL (Android Interface Definition Language), replacing the legacy HIDL interface. If your device's audio HAL uses AIDL, enable this in Settings.

## Root Access

This app may require root access for:

- **AIDL mode**: Creating shared memory files for the AIDL driver (if not set up during module installation)
- **Per-App Mode**: Retrieving real audio session IDs via `dumpsys`
- **Convolver / ViPER-DDC**: Copying IRS/VDC files to `/data/local/tmp/v4a/` for the driver to read
- **Debug log viewer**: Reading `logcat` for driver diagnostics

Please make sure the source of any modified APK is trustworthy to avoid any security risks.

## Contributing

Contributions are welcome. Open an issue or submit a pull request.

### Localization

To help with translations, follow the [template guide](app/res-template/values-template/strings.xml).

- RU: [@maurerdv](https://github.com/maurerdv)
- ZH-CN: [@Arissekai](https://github.com/Arissekai)
