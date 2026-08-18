from pathlib import Path

path = Path("app/src/main/java/com/scroblin/app/overlay/SpeedKnobView.kt")
text = path.read_text(encoding="utf-8")
old = """    private var moved = false
    private val moveSlop = 11f * density
    private val speedTravelPerStep = min(
        7f * density,
        resources.displayMetrics.heightPixels * 0.32f /
            (MAX_SPEED_STEP * 2f),
    )
"""
new = """    private var moved = false
    private val speedTravelPerStep = min(
        7f * density,
        resources.displayMetrics.heightPixels * 0.32f /
            (MAX_SPEED_STEP * 2f),
    )
    private val moveSlop = min(4f * density, speedTravelPerStep * 0.8f)
"""
if new not in text:
    if old not in text:
        raise RuntimeError("Expected compressed knob gesture block not found")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Full-swipe notch precision tweak applied.")
