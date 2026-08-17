# Scroblin

Scroblin is an Android accessibility utility with a floating rotary control for continuous, adjustable scrolling in the foreground app.

## Requirements

- Windows with PowerShell
- Microsoft OpenJDK 17
- Android SDK 36
- A device running Android 9 (API 28) or newer

## Build

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot'
$env:ANDROID_HOME = 'C:\Users\Johnsons\AppData\Local\Android\Sdk'
.\gradlew.bat assembleDebug
```

The debug APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

The on-device acceptance sequence is in [`docs/PIXEL_TEST_CHECKLIST.md`](docs/PIXEL_TEST_CHECKLIST.md).

## Use

1. Install and open Scroblin.
2. Enable the **Scroblin auto-scroll** accessibility service once. Its Android accessibility shortcut is not required.
3. Drag the left-aligned **Preview** knob to audition the combined speed against the looping “missile knows where it is” copypasta. The preview renders three copies, opens on the middle one, and silently recenters by one measured copy height at either boundary for a seamless loop.
4. In **Scroll Lab**, use the compact Rate, Size, Opacity, and Color row to configure the floating widget. While Color is held, only Preview shows the live hue; Rate, Size, Opacity, and the Start/Stop button retain their committed colors, then snap to the new palette on release. Toggle haptics in the Scroll Lab header, then tap the large **start scroblin** button.
5. Leave Settings to reveal the floating knob. Drag vertically to choose speed and direction.
6. The single ring has 40 notches; each one adds the configured Rate value.
7. Touch foreground content or tap the active knob to pause. Tap the paused knob to resume at the stored speed.
8. Starting from paused, tap twice to briefly resume and then smoothly reset the saved speed to zero.
9. Swipe horizontally in either direction for the settings gear. Hold the gear to reposition it, or tap it to reopen settings.

The title-free settings surface keeps all controls and a short gesture guide on one Pixel 8 screen. Its Start/Stop button fills the remaining space, follows the selected hue, and cycles through seven Android system font families on successive presses, allowing both button states to visit every font. The auto-sized white label has a black silhouette; holding the button keeps the native touch ripple, starts independent two-pixel text jitter immediately, ramps toward a twenty-pixel peak over five seconds, and carries the button hue through exactly one counterclockwise color-wheel revolution over the full grow-and-fade animation. The sweep accelerates with the jitter, decelerates during its fade, and releasing early smoothly returns the button to its selected hue. The floating control stays hidden while Settings is open; it starts paused and also pauses and hides while the software keyboard is visible. Reopen the app and tap **stop scroblin** to remove the overlay without disabling accessibility access.

On Home-screen pages, scrolling is temporarily disabled and the knob becomes a non-interactive, minimum-opacity control with an engraved X. Opening a normal app restores its prior state; the vertically scrollable app drawer remains usable.

## Privacy

Scroblin uses Android accessibility APIs only to display its overlay, detect interaction and keyboard state, and dispatch scrolling gestures. It has no network permission and does not collect or transmit foreground content.
