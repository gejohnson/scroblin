# Pixel 8 validation checklist

Use this checklist after the Pixel is connected over wireless ADB.

## Install and launch

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot'
$env:ANDROID_HOME = 'C:\Users\Johnsons\AppData\Local\Android\Sdk'
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"

& $adb devices
.\gradlew.bat installDebug
& $adb shell am start -n com.gejohnson.scroblin/.MainActivity
```

Enable **Scroblin auto-scroll** from the Accessibility settings screen opened by the app. Do not enable the optional Android accessibility shortcut; Scroblin has its own Start/Stop control.

## Focused logging

```powershell
& $adb logcat -c
& $adb logcat -s 'AutoScroll:D' '*:S'
```

Logs cover service lifecycle, overlay placement, speed changes, pause/resume, face changes, keyboard visibility, and synthetic gesture completion or cancellation.

## Test sequence

1. Confirm the overlay is absent while Scroblin Settings is visible and every setting fits on one Pixel 8 screen. Confirm there is no title header, service-status box, or help button.
2. Confirm the top card has no section label: its left-aligned knob is labeled **Preview** and the adjacent text box shows the current px/s. Set Preview to two notches with Rate at 5 and confirm only the adjacent “missile knows where it is” copypasta scrolls at 10 px/s. Confirm the text opens at the start of the middle of three copies and silently recenters at either boundary without a visible snap or lost offset. Touch elsewhere and confirm the preview pauses without losing its setting.
3. Confirm **Scroll Lab** contains four evenly sized controls in one row: Rate, Size, Opacity, and Color. Set Rate to 0 px/tick, move it one notch, and confirm it changes by exactly 5 px/tick; sweep the ring and confirm it caps at 100.
4. Adjust Size from 60–115 dp and Opacity from 25–100%. Confirm both changes are immediately visible on Preview while its 115 dp layout bay and the entire top card remain fixed in size.
5. Confirm Color shows a persistent full inner ring in the selected hue, a white-outlined marker on that hue, and a black-outlined marker exactly opposite it. Hold and drag Color. Confirm Preview alone switches to full opacity and a full ring using the live hue; Rate, Size, Opacity, and the large button must retain their committed colors. Release and confirm Preview returns to its real state while the other controls and button snap directly to the newly committed palette.
6. Confirm the Haptics toggle sits on the same row as the **Scroll Lab** header. Disable it and verify Rate, Preview, Size, Opacity, and Color adjustments are silent and vibration-free; re-enable it and verify their haptic ticks return. Confirm there is no sound control or tick sound.
7. Read the inline **Controls** panel. Confirm its bold labels and manual line breaks exactly present Swipe Up/Down, Swipe Sideways, Tap Knob, and Tap Anywhere Else with their compact supporting text.
8. Confirm the button always reads lowercase **start scroblin** or **stop scroblin**; without Accessibility access, pressing Start should open the permission screen without changing the label. Confirm its face fills roughly 95% of all space below Controls and its automatically sized label occupies a 95% safe area without clipping or overlapping. Confirm white glyphs sit over a slightly enlarged black silhouette. Press and hold: the native radiating touch ripple should remain, the two text layers should begin jittering independently at roughly two pixels immediately, grow toward a twenty-pixel peak over five seconds, and then fade. At the same time, confirm the button hue completes exactly one smooth counterclockwise revolution over the full grow-and-fade animation, accelerating toward peak vibration and decelerating afterward. Release early and confirm the button smoothly returns to its selected hue. Press it repeatedly and confirm it cycles through seven visibly distinct Android system families, with both Start and Stop eventually using every family. Start Scroblin, then leave Settings; confirm the button becomes **stop scroblin** and the floating knob appears paused.
9. In Chrome, drag upward through the 40 notches. Confirm one clockwise fill, downward content movement, and a speed increase of exactly the configured Rate at every notch.
10. Confirm no scrolling gesture begins until the knob drag is released and no underlying controls activate during the drag.
11. Drag downward through zero. Confirm the ring switches to the opposite-direction color and content reverses.
12. Touch page content. Confirm scrolling stops promptly, Chrome receives the touch, and the knob's active color turns gray.
13. Tap the active knob. Confirm it pauses and turns gray without losing its speed. Tap again and confirm scrolling resumes at exactly the stored speed.
14. Starting paused, tap twice. Confirm the first tap resumes and the second immediately pauses while the ring smoothly returns to zero. Repeat from active with three taps.
15. Swipe either left or right. Confirm the control flips to the gear and remains paused.
16. Hold the gear for roughly 430 ms, drag it to each screen edge, and release. Confirm clamping and persistence after service restart.
17. Tap the gear. Confirm Scroblin Settings opens and the floating overlay disappears.
18. Focus a text field outside Scroblin. Confirm the keyboard pauses scrolling and fades the overlay out; dismiss it and confirm the overlay returns paused.
19. While scrolling, go Home. Confirm new gestures stop, the knob falls to 25% opacity with an engraved X, and taps and horizontal Home paging pass through it.
20. Open the app drawer. Confirm the normal full-opacity interactive knob returns because this launcher surface scrolls vertically; close the drawer and confirm the disabled Home state returns.
21. Open a normal app. Confirm the prior paused/running intent and saved speed are restored.
22. Tap **Stop Scroblin**. Leave Settings and confirm no overlay appears while the accessibility service remains enabled.
23. Repeat bidirectional speed tests in a native scrolling app and in Twitter/X web.

## Tuning targets

Rate is 0–100 px/s per notch in 5 px/s increments. A floating-knob speed of *n* notches targets `abs(n) × pixelsPerNotch` px/s. If gesture feel still needs adjustment, the centralized geometry is in `GestureProfile.kt` and the rate mapping is in `GestureScrollEngine.kt`.
