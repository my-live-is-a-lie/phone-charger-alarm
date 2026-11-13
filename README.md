# Phone Charger Alarm

An Android app that alerts you when your phone finishes charging (or reaches a
chosen battery level), so you can unplug it on time.

## Features

- Monitors battery level in the background
- Alert when charging completes / reaches a target level
- Quick Settings tile to toggle monitoring
- Runs after reboot

## Build

Release APKs are built automatically on GitHub Actions. Download the latest
build from the **Actions** tab (artifact `phone-charger-alarm-release`).

To build locally:

```bash
./gradlew assembleRelease
```

The APK is at `app/build/outputs/apk/release/`. The app has no native code,
so the APK is architecture-independent and runs on arm64-v8a devices.

## Requirements

- Android 10 (API 29) or later
- arm64-v8a device
