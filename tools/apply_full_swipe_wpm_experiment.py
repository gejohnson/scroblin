from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"Expected source block not found in {path}:\n{old}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


SETTINGS = "app/src/main/java/com/scroblin/app/settings/SettingsRepository.kt"
MAIN = "app/src/main/java/com/scroblin/app/MainActivity.kt"
ENGINE = "app/src/main/java/com/scroblin/app/scroll/GestureScrollEngine.kt"
KNOB = "app/src/main/java/com/scroblin/app/overlay/SpeedKnobView.kt"

# Keep this experiment's WPM preference isolated from main so installing this
# build does not overwrite the existing px/notch tuning when switching back.
replace_once(
    SETTINGS,
    "        const val DEFAULT_PIXELS_PER_NOTCH = 0f\n",
    "        const val DEFAULT_MAX_WPM = 600f\n"
    "        const val DEFAULT_PIXELS_PER_NOTCH = DEFAULT_MAX_WPM\n",
)
replace_once(
    SETTINGS,
    '        const val KEY_PIXELS_PER_NOTCH = "pixels_per_notch"\n',
    '        const val KEY_PIXELS_PER_NOTCH = "experimental_max_wpm"\n',
)
replace_once(
    SETTINGS,
    "        val PIXELS_PER_NOTCH_RANGE = 0f..100f\n"
    "        const val PIXELS_PER_NOTCH_STEP = 5f\n",
    "        val PIXELS_PER_NOTCH_RANGE = 100f..1200f\n"
    "        const val PIXELS_PER_NOTCH_STEP = 50f\n",
)

# The user's comfortable thumb arc spans about one third of the Pixel screen.
# There are MAX_SPEED_STEP * 2 transitions from -100% to +100%, so derive the
# per-notch travel from 32% of the screen height. Cap it at 7dp on unusually
# tall/low-density displays.
replace_once(
    KNOB,
    "    private val speedTravelPerStep = 16f * density\n",
    "    private val speedTravelPerStep = min(\n"
    "        7f * density,\n"
    "        resources.displayMetrics.heightPixels * 0.32f /\n"
    "            (MAX_SPEED_STEP * 2f),\n"
    "    )\n",
)

# GestureScrollEngine still exposes the existing ScrollEngine API, but the
# scalar arriving through updatePixelsPerNotch is now the experiment's max WPM.
# Convert the curved target back to an effective px/notch immediately before it
# reaches the already-tested node/touch engines.
replace_once(
    ENGINE,
    "import android.os.Looper\n",
    "import android.os.Looper\nimport kotlin.math.abs\n",
)
replace_once(
    ENGINE,
    "class GestureScrollEngine(\n    service: AccessibilityService,\n",
    "class GestureScrollEngine(\n    private val service: AccessibilityService,\n",
)
replace_once(
    ENGINE,
    "    private var pixelsPerNotch = 0f\n",
    "    private var maxWpm = 0f\n",
)
replace_once(
    ENGINE,
    "            nodeEngine.setSpeedStep(step)\n"
    "            touchEngine.setSpeedStep(step)\n"
    "            if (desiredRunning) chooseStrategyAndResume()\n",
    "            nodeEngine.setSpeedStep(step)\n"
    "            touchEngine.setSpeedStep(step)\n"
    "            updateChildSpeedScale()\n"
    "            if (desiredRunning) chooseStrategyAndResume()\n",
)
replace_once(
    ENGINE,
    "    override fun updatePixelsPerNotch(pixelsPerNotch: Float) {\n"
    "        runOnMain {\n"
    "            this.pixelsPerNotch = pixelsPerNotch\n"
    "            nodeEngine.updatePixelsPerNotch(pixelsPerNotch)\n"
    "            touchEngine.updatePixelsPerNotch(pixelsPerNotch)\n"
    "            if (desiredRunning) chooseStrategyAndResume()\n"
    "        }\n"
    "    }\n",
    "    override fun updatePixelsPerNotch(pixelsPerNotch: Float) {\n"
    "        runOnMain {\n"
    "            maxWpm = pixelsPerNotch.coerceAtLeast(0f)\n"
    "            updateChildSpeedScale()\n"
    "            if (desiredRunning) chooseStrategyAndResume()\n"
    "        }\n"
    "    }\n",
)
replace_once(
    ENGINE,
    "            if (stopped || speedStep == 0 || pixelsPerNotch <= 0f) return@runOnMain\n"
    "            desiredRunning = true\n"
    "            chooseStrategyAndResume()\n",
    "            if (stopped || speedStep == 0 || maxWpm <= 0f) return@runOnMain\n"
    "            desiredRunning = true\n"
    "            updateChildSpeedScale()\n"
    "            chooseStrategyAndResume()\n",
)
replace_once(
    ENGINE,
    "        if (!desiredRunning || stopped || speedStep == 0 || pixelsPerNotch <= 0f) return\n",
    "        if (!desiredRunning || stopped || speedStep == 0 || maxWpm <= 0f) return\n",
)
replace_once(
    ENGINE,
    "    private fun runOnMain(block: () -> Unit) {\n",
    "    private fun updateChildSpeedScale() {\n"
    "        val magnitude = abs(speedStep)\n"
    "        val targetPixelsPerSecond = abs(\n"
    "            WpmSpeedModel.targetPixelsPerSecond(\n"
    "                step = speedStep,\n"
    "                maxWpm = maxWpm,\n"
    "                screenHeightPixels = service.resources.displayMetrics.heightPixels,\n"
    "            ),\n"
    "        )\n"
    "        val effectivePixelsPerNotch = if (magnitude > 0) {\n"
    "            targetPixelsPerSecond / magnitude\n"
    "        } else {\n"
    "            0f\n"
    "        }\n"
    "        nodeEngine.updatePixelsPerNotch(effectivePixelsPerNotch)\n"
    "        touchEngine.updatePixelsPerNotch(effectivePixelsPerNotch)\n"
    "    }\n\n"
    "    private fun runOnMain(block: () -> Unit) {\n",
)

# Settings/preview: the first settings knob is now Max WPM, and the preview uses
# the same curve + words-per-screen model as the live engine.
replace_once(
    MAIN,
    "import com.scroblin.app.overlay.SpeedKnobView\n",
    "import com.scroblin.app.overlay.SpeedKnobView\n"
    "import com.scroblin.app.scroll.WpmSpeedModel\n",
)
replace_once(
    MAIN,
    "    val previewPixelsPerSecond = previewSpeedStep * settings.pixelsPerNotch\n",
    "    val screenHeightPixels = LocalResources.current.displayMetrics.heightPixels\n"
    "    val previewPixelsPerSecond = WpmSpeedModel.targetPixelsPerSecond(\n"
    "        step = previewSpeedStep,\n"
    "        maxWpm = settings.pixelsPerNotch,\n"
    "        screenHeightPixels = screenHeightPixels,\n"
    "    )\n"
    "    val previewWpm = WpmSpeedModel.estimatedWpm(\n"
    "        step = previewSpeedStep,\n"
    "        maxWpm = settings.pixelsPerNotch,\n"
    "    )\n",
)
replace_once(
    MAIN,
    "                pixelsPerSecond = previewPixelsPerSecond,\n"
    "                playing = previewPlaying,\n",
    "                pixelsPerSecond = previewPixelsPerSecond,\n"
    "                estimatedWpm = previewWpm,\n"
    "                playing = previewPlaying,\n",
)
replace_once(
    MAIN,
    "    pixelsPerSecond: Float,\n"
    "    playing: Boolean,\n",
    "    pixelsPerSecond: Float,\n"
    "    estimatedWpm: Float,\n"
    "    playing: Boolean,\n",
)
replace_once(
    MAIN,
    '            text = "${pixelsPerSecond.roundToInt()} px/s",\n',
    '            text = "${kotlin.math.abs(estimatedWpm).roundToInt()} WPM",\n',
)
replace_once(
    MAIN,
    '                    label = "Rate",\n'
    '                    valueText = "${settings.pixelsPerNotch.roundToInt()} px/tick",\n',
    '                    label = "Max",\n'
    '                    valueText = "${settings.pixelsPerNotch.roundToInt()} WPM",\n',
)
replace_once(
    MAIN,
    "                    visualNotchCount = 20,\n",
    "                    visualNotchCount = 23,\n",
)

print("Full-swipe curved-knob WPM experiment applied.")
