# Soundcore Switch

An unofficial app that shows and switches the multipoint connection targets of
Soundcore earbuds without opening the official app.
**Works on both Android and Windows.**

> **This is an unofficial tool.** It is not affiliated with, endorsed by, or
> sponsored by Anker Innovations or the Soundcore brand. It was implemented
> independently, and no code or images from the official app are used.
> "Soundcore" is a trademark of Anker Innovations. Product names are used only
> to identify supported models.

## Why

Switching the connection target in the official app takes
"launch → wait for the earbuds → settings → change target".
This app shortens that to "launch → pick a target → done".

## Features

| Feature | Android | Windows | Notes |
|---|---|---|---|
| List multipoint targets | ✅ | ✅ | Read from the earbuds |
| Show each target's connection state | ✅ | ✅ | "Unknown" when unavailable |
| Switch target with one tap | ✅ | ✅ | Supported models only |
| Favorites shown first | ✅ | ✅ | Stored by internal ID |
| Protect a target from auto-disconnect | ✅ | ✅ | Prevents accidental disconnects |
| Quick Settings tile / system tray | ✅ | ✅ | Without opening the window |
| Restore the last selected model | ✅ | ✅ | Stored on the device only |
| Instant display from cache | ✅ | ✅ | Clearly marked as possibly stale |
| ANC, EQ, firmware updates, playback | ❌ | ❌ | Out of scope |

### Supported models

| Model | Code | Status |
|---|---|---|
| soundcore Liberty 4 NC | A3947 | ✅ Verified on hardware (Android / Windows) |
| Soundcore Space A40 | A3936 | ✅ Verified on hardware (Android / Windows) |

Other Soundcore models are shown but switching stays disabled until verified.

### Known limitations

- **The official Soundcore app must be closed.** It holds the RFCOMM channel,
  so this app cannot connect while it is running.
- Simultaneous connections follow the model's limit (2). When the limit is
  reached, the app disconnects one target before connecting. Some targets
  refuse to disconnect; the app then tries another one.
- On some devices the Bluetooth profile API is unavailable and the earbuds'
  connection state shows as "Unknown". Switching still works; the result is
  confirmed by re-reading the target list.
- If you have paired several earbuds with the same name, they appear with the
  same name. Use the connection dot to tell them apart.

## How it works

Android's standard Bluetooth API cannot read the earbuds' internal target list
or ask the earbuds to switch. This app implements the **model-specific RFCOMM
protocol**, reverse-engineered by observing traffic on real hardware.

```
Vendor UUID: 0cf12d31-fac3-4553-bd80-d6832e7b + 4-digit model code
Example: Liberty 4 NC (A3947) → 0cf12d31-fac3-4553-bd80-d6832e7b3947
```

The protocol details are documented in [docs/RESEARCH.md](docs/RESEARCH.md).

## Requirements

### Android
- Android 8.0 (API 26) or later
- A device with Bluetooth

### Windows
- Windows 10 / 11
- A Bluetooth adapter
- Java 17 or later (bundled in the installer)

## Install

### Android

Download the APK from [Releases](../../releases) and open it.
You may need to allow installing from unknown sources.

To update automatically, add this repository to
[Obtainium](https://github.com/ImranR98/Obtainium).

### Windows

Download and run the MSI from [Releases](../../releases).

To uninstall, use "Settings → Apps → Soundcore Switch".
If you installed manually, also delete `%APPDATA%\SoundcoreSwitch\`.

## Build

Requires JDK 17+, the Android SDK (`compileSdk 36`), and `local.properties`
with `sdk.dir`.

```powershell
# Android
.\gradlew.bat :app:assembleDebug

# Windows
.\gradlew.bat :desktop:packageMsi

# Tests
.\gradlew.bat :core:test
.\gradlew.bat :desktop:test
```

On Windows, a PATH entry containing parentheses (for example
`C:\Program Files (x86)\PowerShell\7`) breaks the test worker. Use the bundled
wrapper instead:

```powershell
.\scripts\gradle.ps1 :core:test
```

## Privacy

- No network access (the app does not request the `INTERNET` permission)
- Bluetooth addresses and device names are never logged or sent anywhere
- Settings and cache stay on the device
- Diagnostic logs redact identifiers

## License

[MIT License](LICENSE)

The protocol was reverse-engineered by observing traffic on real hardware.
[BudsLink](https://github.com/maniacx/BudsLink) (GPL-3.0) was consulted for
reference, but no code was copied. See
[docs/LEGAL_REVIEW.md](docs/LEGAL_REVIEW.md) for details and open questions.

## Disclaimer

Provided as-is, without warranty. The author is not liable for any damage
caused by using this app. Switching connection targets changes the state of
your device; use it at your own risk.
