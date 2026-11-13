# Phone Charger Alarm

An Android app that alerts you when your phone finishes charging (or reaches a
chosen battery level), so you can unplug it on time.

## Features

- Monitors battery level in the background
- Alert when charging completes / reaches a target level
- Quick Settings tile to toggle monitoring
- Runs after reboot

## Build

Release APKs are built automatically on GitHub Actions for **arm64-v8a** only.
Download the latest build from the **Actions** tab (artifact
`phone-charger-alarm-arm64-v8a-release`).

To build locally:

```bash
./gradlew assembleArm64-v8aRelease
```

The APK is at `app/build/outputs/apk/arm64-v8a/release/`.

## Requirements

- Android 10 (API 29) or later
- arm64-v8a device
