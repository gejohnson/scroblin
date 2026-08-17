# Scroblin

Scroblin is an Android accessibility utility with a floating rotary control for continuous, adjustable scrolling in the foreground app.

## Requirements

- JDK 17
- Android SDK 36
- A device running Android 9 (API 28) or newer

## Build

```shell
./gradlew assembleDebug
```

## Privacy

Scroblin uses Android accessibility APIs only to display its overlay, detect interaction and keyboard state, and dispatch scrolling gestures. It has no network permission and does not collect or transmit foreground content.
