@file:OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)

package com.spop.poverlay.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Snackbar
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import com.spop.poverlay.overlay.composables.OverlayMainContent
import com.spop.poverlay.overlay.composables.OverlayMinimizedContent
import com.spop.poverlay.overlay.composables.OverlaySideContent
import com.spop.poverlay.sensor.heartrate.HeartRateManager
import androidx.compose.ui.graphics.toArgb
import com.spop.poverlay.ui.theme.HrZone1Color
import com.spop.poverlay.ui.theme.HrZone2Color
import com.spop.poverlay.ui.theme.HrZone3Color
import com.spop.poverlay.ui.theme.HrZone4Color
import com.spop.poverlay.ui.theme.HrZone5Color
import com.spop.poverlay.ui.theme.MetricHeartRateColor
import com.spop.poverlay.util.livechart.ZoneBand
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import timber.log.Timber

const val VisibilityChangeDurationMs = 150
// After the overlay changes shape mid-drag (bar to side column or back), drag readings are ignored this long
// while its window moves to the new edge.
const val ShapeChangeSettleMs = 250L
val OverlayCornerRadius = 25.dp
val StatCardWidth = 105.dp
val PowerChartFullWidth = 200.dp
val PowerChartShrunkWidth = 120.dp
val BackgroundColorDefault = Color(20, 20, 20, 230)

// Shown when a sensor hasn't reported a value yet
const val SensorValuePlaceholderText = "-"

internal fun heartRateZoneColor(bpm: Int, zones: List<Int>): Color = when {
    bpm < zones[0] -> HrZone1Color
    bpm < zones[1] -> HrZone2Color
    bpm < zones[2] -> HrZone3Color
    bpm < zones[3] -> HrZone4Color
    else -> HrZone5Color
}

@Composable
fun Overlay(
    sensorViewModel: OverlaySensorViewModel,
    timerViewModel: OverlayTimerViewModel,
    height: Dp,
    locationState: State<OverlayLocation>,
    horizontalDragCallback: (Float) -> Unit,
    verticalDragCallback: (Float) -> Float,
    offsetCallback: (Float, Float) -> Unit,
    onLayout: (IntSize) -> Unit,
    onTimerLayout: (IntSize) -> Unit,
    onDragFinished: () -> Unit,
    sideDragCallback: (Float, Float) -> Unit = { _, _ -> }
) {
    val displayedPower by sensorViewModel.powerDisplay.collectAsState()
    val power = remember(displayedPower.watts) {
        if (displayedPower.watts.isFinite()) com.spop.poverlay.sensor.power.telemetryWatts(displayedPower.watts).toString()
        else SensorValuePlaceholderText
    }
    val powerComparison = remember(displayedPower) { displayedPower.comparison }
    val powerSource = displayedPower.source.label

    val defaultMetric by sensorViewModel.defaultMetric.collectAsState()
    val selectedMetric by sensorViewModel.selectedMetric.collectAsState(initial = defaultMetric)
    val currentGraph = remember(selectedMetric) { sensorViewModel.getGraphForMetric(selectedMetric) }
    // Card visibility is initialized from the detected device before the first frame.
    // What the rider chose to show (settings › Show on the overlay), within what this machine has.
    val overlayItems by timerViewModel.overlayItems.collectAsState()
    val showPowerCard = sensorViewModel.showPowerCard.collectAsState().value &&
        com.spop.poverlay.OverlayItem.Power in overlayItems
    val showCadenceCard = sensorViewModel.showCadenceCard.collectAsState().value &&
        com.spop.poverlay.OverlayItem.Cadence in overlayItems
    val showResistanceCard = sensorViewModel.showResistanceCard.collectAsState().value &&
        com.spop.poverlay.OverlayItem.Resistance in overlayItems
    val showInclineCard = sensorViewModel.showInclineCard.collectAsState().value &&
        com.spop.poverlay.OverlayItem.Incline in overlayItems
    val showSpeed = com.spop.poverlay.OverlayItem.Speed in overlayItems
    val showHeartRate = com.spop.poverlay.OverlayItem.HeartRate in overlayItems
    val showCalories = com.spop.poverlay.OverlayItem.Calories in overlayItems
    val showChart = com.spop.poverlay.OverlayItem.Chart in overlayItems
    // Collected only by the chip itself, and only when switched on: temperature changes don't recompose the overlay.
    val flywheelLabel = sensorViewModel.flywheelLabel
        .takeIf { com.spop.poverlay.OverlayItem.FlywheelTemperature in overlayItems }
    val isTread by sensorViewModel.isTread.collectAsState()
    val rpm by sensorViewModel.rpmValue.collectAsState(initial = SensorValuePlaceholderText)
    val resistance by sensorViewModel.resistanceValue.collectAsState(initial = SensorValuePlaceholderText)
    val speed by sensorViewModel.speedValue.collectAsState(initial = SensorValuePlaceholderText)
    val speedLabel by sensorViewModel.speedLabel.collectAsState(initial = "")
    val incline by sensorViewModel.inclineValue.collectAsState(initial = SensorValuePlaceholderText)
    val calories by sensorViewModel.caloriesValue.collectAsStateWithLifecycle(initialValue = SensorValuePlaceholderText)
    val heartRate by HeartRateManager.heartRate.collectAsStateWithLifecycle(initialValue = null)
    val heartRateZones by HeartRateManager.heartRateZones.collectAsStateWithLifecycle(initialValue = null)
    val connectedHeartRateDevice by HeartRateManager.connectedDevice.collectAsStateWithLifecycle(initialValue = null)
    val heartRateColor = remember(heartRate, heartRateZones) {
        val bpm = heartRate
        val zones = heartRateZones
        if (bpm != null && bpm > 0 && zones != null) heartRateZoneColor(bpm, zones)
        else MetricHeartRateColor
    }
    val heartRateZoneBands = remember(heartRateZones) {
        val zones = heartRateZones ?: return@remember null
        listOf(
            ZoneBand(0f,          zones[0].toFloat(), HrZone1Color.copy(alpha = 0.3f).toArgb()),
            ZoneBand(zones[0].toFloat(), zones[1].toFloat(), HrZone2Color.copy(alpha = 0.3f).toArgb()),
            ZoneBand(zones[1].toFloat(), zones[2].toFloat(), HrZone3Color.copy(alpha = 0.3f).toArgb()),
            ZoneBand(zones[2].toFloat(), zones[3].toFloat(), HrZone4Color.copy(alpha = 0.3f).toArgb()),
            ZoneBand(zones[3].toFloat(), 220f,         HrZone5Color.copy(alpha = 0.3f).toArgb()),
        )
    }
    val timerLabel by timerViewModel.timerLabel.collectAsState(initial = "")
    val isTimerPaused by timerViewModel.timerPaused.collectAsState(initial = false)
    val errorMessage by sensorViewModel.errorMessage.collectAsState(initial = null)

    // Max values
    val maxPower by sensorViewModel.maxPower.collectAsState()
    val maxCadence by sensorViewModel.maxCadence.collectAsState()
    val maxResistance by sensorViewModel.maxResistance.collectAsState()
    val maxSpeed by sensorViewModel.maxSpeed.collectAsState()
    val maxHeartRate by sensorViewModel.maxHeartRate.collectAsState()

    // Session totals and averages
    val totalEnergy by sensorViewModel.totalEnergy.collectAsState()
    val totalDistance by sensorViewModel.totalDistance.collectAsState()
    val avgCadence by sensorViewModel.avgCadence.collectAsState()
    val avgResistance by sensorViewModel.avgResistance.collectAsState()
    val avgHeartRate by sensorViewModel.avgHeartRate.collectAsState()

    var isCurrentlyAnimating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        sensorViewModel.isMinimized
            .drop(1) // Ignore the initial value since animations only happen after new updates
            .collect(object : FlowCollector<Boolean> {
                override suspend fun emit(value: Boolean) {
                    isCurrentlyAnimating = true
                }
            })
    }

    val minimized by sensorViewModel.isMinimized.collectAsState(initial = false)
    val showHeartRateCard = connectedHeartRateDevice != null && showHeartRate
    val location by locationState
    LaunchedEffect(showHeartRateCard, selectedMetric) {
        if (!showHeartRateCard && selectedMetric == MetricType.HEART_RATE) {
            sensorViewModel.onMetricSelected(defaultMetric)
        }
    }

    val size = remember { mutableStateOf(IntSize.Zero) }


    val contentHeight = height + if (powerComparison != null) 14.dp else 0.dp
    val mainContentHeight = with(LocalDensity.current) {
        contentHeight.roundToPx()
    }

    val timerAlpha by animateFloatAsState(
        if (minimized) .5f else 1f,
        animationSpec = TweenSpec(VisibilityChangeDurationMs, 0, LinearEasing)
    )

    val targetVisibilityOffset = if (minimized) {
        when (location) {
            // When the main content is hidden, move it off screen completely
            OverlayLocation.Top -> IntOffset(0, -mainContentHeight)
            OverlayLocation.Bottom -> IntOffset(0, mainContentHeight)
            // A docked column swaps its content in place: nothing slides off screen.
            OverlayLocation.Left, OverlayLocation.Right -> IntOffset.Zero
        }
    } else {
        IntOffset.Zero
    }
    val visibilityOffset by animateIntOffsetAsState(
        targetVisibilityOffset,
        animationSpec = TweenSpec(VisibilityChangeDurationMs, 0, LinearEasing),
        finishedListener = {
            isCurrentlyAnimating = false
        }
    )

    // A docked column never hides behind a touch target, so it always reports nothing hidden.
    offsetCallback(if (location.isSide) 0f else visibilityOffset.y.toFloat(), size.value.height.toFloat())

    var verticalDragOffset by remember { mutableStateOf(0f) }

    val backgroundShape = when (location) {
        OverlayLocation.Top -> RoundedCornerShape(
            bottomStart = OverlayCornerRadius, bottomEnd = OverlayCornerRadius
        )
        OverlayLocation.Bottom -> RoundedCornerShape(
            topStart = OverlayCornerRadius, topEnd = OverlayCornerRadius
        )
        OverlayLocation.Left, OverlayLocation.Right -> RoundedCornerShape(OverlayCornerRadius)
    }
    val timer = @Composable {
        val showTimerWhenMinimizedFlow = remember {
            timerViewModel.showTimerWhenMinimized.onEach {
                Timber.i("Show Timer: $it")
            }
        }
        val showTimerWhenMinimized by showTimerWhenMinimizedFlow
            .collectAsState(initial = true)

        OverlayMinimizedContent(
            isMinimized = minimized,
            timerPaused = isTimerPaused,
            showTimerWhenMinimized = showTimerWhenMinimized,
            location = location,
            isTread = isTread,
            powerLabel = power,
            powerComparison = powerComparison,
            powerSourceLabel = if (displayedPower.source == com.spop.poverlay.sensor.power.DisplayPowerSource.Corrected) powerSource else null,
            contentAlpha = timerAlpha,
            timerLabel = timerLabel,
            cadenceLabel = rpm,
            speedLabel = speed,
            resistanceLabel = resistance,
            inclineLabel = incline,
            heartRateLabel = heartRate?.toString() ?: SensorValuePlaceholderText,
            showPowerField = showPowerCard,
            showCadenceField = showCadenceCard,
            showResistanceField = showResistanceCard,
            showInclineField = showInclineCard,
            showSpeedField = showSpeed,
            showHeartRateField = showHeartRate,
            flywheelLabel = flywheelLabel,
            heartRateColor = heartRateColor,
            onTap = { timerViewModel.onTimerTap() },
            onLongPress = { timerViewModel.onTimerLongPress() },
            onOpenSettings = { sensorViewModel.onOverlayDoubleTap() },
            onMinimizeToggle = { sensorViewModel.onOverlayPressed() },
            onLayout = onTimerLayout,
            emulationLabel = sensorViewModel.emulatedModel
        )
    }
    // Once the minimize slide has finished, the main content is fully off screen. Composing it
    // there still recomposed every stat card and rebuilt the chart twice a second for nothing,
    // so a same-sized placeholder stands in until the overlay is expanded again.
    // Keyed on the offset reaching its target, not on isCurrentlyAnimating, which can stay set
    // if a toggle never starts an animation.
    val mainContentHidden = minimized &&
        targetVisibilityOffset != IntOffset.Zero && visibilityOffset == targetVisibilityOffset
    var shrinkChart by remember { mutableStateOf(false) }
    val reportMainContentSize = Modifier.onSizeChanged {
        if (it.width != size.value.width || it.height != size.value.height) {
            size.value = it
            onLayout(size.value)
        }
    }
    val mainContent = @Composable {
        if (mainContentHidden) {
            val hiddenWidth = with(LocalDensity.current) { size.value.width.toDp() }
            Spacer(
                modifier = Modifier
                    .requiredHeight(contentHeight)
                    .requiredWidth(hiddenWidth)
                    .then(reportMainContentSize)
            )
        } else {
            Box(modifier = Modifier
                .requiredHeight(contentHeight)
                .wrapContentWidth(unbounded = true)
                .then(reportMainContentSize)
                .background(
                    color = BackgroundColorDefault,
                    shape = backgroundShape,
                )) {


                val rowAlignment = when (location) {
                    OverlayLocation.Bottom -> Alignment.Bottom
                    else -> Alignment.Top
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                OverlayMainContent(
                    modifier = Modifier
                        .wrapContentWidth(unbounded = true)
                        .padding(horizontal = 9.dp)
                        .padding(bottom = 5.dp),
                    rowAlignment = rowAlignment,
                    isTread = isTread,
                    power = power,
                    powerComparison = powerComparison,
                    powerSourceLabel = powerSource,
                    rpm = rpm,
                    // Paused only while the slide is actually moving: a docked column has no slide, so the flag set by
                    // its minimize toggle is never cleared, and the chart would stay frozen after undocking (review).
                    pauseChart = isCurrentlyAnimating && visibilityOffset != targetVisibilityOffset,
                    currentGraph = currentGraph,
                    selectedMetric = selectedMetric,
                    resistance = resistance,
                    speed = speed,
                    speedLabel = speedLabel,
                    heartRate = heartRate?.toString() ?: SensorValuePlaceholderText,
                    heartRateColor = heartRateColor,
                    heartRateZoneBands = heartRateZoneBands,
                    calories = calories,
                    maxPower = remember(maxPower) { "%.0f".format(maxPower) },
                    maxCadence = remember(maxCadence) { "%.0f".format(maxCadence) },
                    maxResistance = remember(maxResistance) { "%.0f".format(maxResistance) },
                    maxSpeed = remember(maxSpeed) { "%.1f".format(maxSpeed) },
                    maxPowerValue = maxPower,
                    maxCadenceValue = maxCadence,
                    maxResistanceValue = maxResistance,
                    maxSpeedValue = maxSpeed,
                    totalEnergy = remember(totalEnergy) { "%.0f".format(totalEnergy) },
                    totalDistance = remember(totalDistance, speedLabel) {
                        if (speedLabel == "mph") "%.2f".format(totalDistance) else "%.2f".format(totalDistance * 1.60934f)
                    },
                    distanceUnit = if (speedLabel == "mph") "mi" else "km",
                    avgCadence = remember(avgCadence) { "%.0f".format(avgCadence) },
                    avgResistance = remember(avgResistance) { "%.0f".format(avgResistance) },
                    maxHeartRate = remember(maxHeartRate) { "%.0f".format(maxHeartRate) },
                    avgHeartRate = remember(avgHeartRate) { "%.0f".format(avgHeartRate) },
                    showHeartRateCard = showHeartRateCard,
                    incline = incline,
                    showInclineCard = showInclineCard,
                    showPowerCard = showPowerCard,
                    showCadenceCard = showCadenceCard,
                    showResistanceCard = showResistanceCard,
                    onMetricSelected = { sensorViewModel.onMetricSelected(it) },
                    onSpeedUnitClicked = { sensorViewModel.onClickedSpeedUnit() },
                    onChartClicked = { sensorViewModel.onOverlayPressed() },
                    shrinkChart = shrinkChart,
                    onShrinkChartToggled = { shrinkChart = !shrinkChart },
                    showSpeedCard = showSpeed,
                    showCaloriesCard = showCalories,
                    showChart = showChart
                )
                }
            }
        }
    }


    // The overlay Box is laid out with unbounded constraints so its content can extend
    // past the window bounds. A Snackbar internally applies fillMaxWidth(), which under
    // an unbounded (infinite) max width crashes in measure ("Can't represent a size ...
    // in Constraints"). Cap the Snackbar to the screen width so it always has a bounded
    // width regardless of the unbounded parent.
    val maxSnackbarWidth = LocalConfiguration.current.screenWidthDp.dp
    // One drag handler for the whole overlay, so a drag carries on when the bar turns into a
    // column at a side edge (or back): the piece under the finger changes, the gesture doesn't.
    val currentLocation by rememberUpdatedState(location)
    var dragLocation by remember { mutableStateOf(location) }
    val dragModifier = Modifier.pointerInput(Unit) {
        var settleUntil = 0L
        detectDragGestures(onDrag = { change, offset ->
            val now = currentLocation
            if (now != dragLocation) {
                // Changed shape mid-drag. The window jumps to its new edge while the finger stays put, so the next
                // readings carry a jump the rider never made (it flipped the bar top/bottom, or blocked docking).
                // Ignore them until the window has settled, then start the top/bottom flip distance over.
                dragLocation = now
                settleUntil = change.uptimeMillis + ShapeChangeSettleMs
            }
            if (change.uptimeMillis < settleUntil) {
                change.consume()
                verticalDragOffset = 0f
            } else if (now.isSide) {
                change.consume()
                sideDragCallback(offset.x, offset.y)
            } else {
                horizontalDragCallback(offset.x)

                verticalDragOffset += offset.y
                verticalDragOffset = verticalDragCallback(verticalDragOffset)
            }
        }, onDragEnd = {
            verticalDragOffset = 0f
            onDragFinished()
        }, onDragCancel = {
            verticalDragOffset = 0f
            onDragFinished()
        })
    }
    Box(
        modifier = Modifier
            .wrapContentSize(unbounded = true)
            .then(if (errorMessage == null) dragModifier else Modifier)
    ) {
        errorMessage?.let {
            Snackbar(
                action = {
                    Button(onClick = { sensorViewModel.onDismissErrorPressed() }) {
                        Text("Dismiss")
                    }
                },
                backgroundColor = Color.White,
                modifier = Modifier
                    .widthIn(max = maxSnackbarWidth)
                    .padding(8.dp)
                    .zIndex(1f)
            ) {
                Text(it, color = Color.Black)
            }
            return@Box
        }
        if (location.isSide) {
            val showTimerWhenMinimized by timerViewModel.showTimerWhenMinimized.collectAsState(initial = true)
            OverlaySideContent(
                location = location,
                isMinimized = minimized,
                showTimerWhenMinimized = showTimerWhenMinimized,
                contentAlpha = timerAlpha,
                isTread = isTread,
                timerLabel = timerLabel,
                timerPaused = isTimerPaused,
                powerLabel = power,
                powerSourceLabel = if (displayedPower.source == com.spop.poverlay.sensor.power.DisplayPowerSource.Corrected) powerSource else null,
                cadenceLabel = rpm,
                resistanceLabel = resistance,
                inclineLabel = incline,
                speedLabel = speed,
                speedUnit = speedLabel,
                heartRateLabel = heartRate?.toString() ?: SensorValuePlaceholderText,
                heartRateColor = heartRateColor,
                caloriesLabel = calories,
                showPowerField = showPowerCard,
                showCadenceField = showCadenceCard,
                showResistanceField = showResistanceCard,
                showInclineField = showInclineCard,
                showSpeedField = showSpeed,
                showHeartRateField = showHeartRate,
                showCaloriesField = showCalories,
                flywheelLabel = flywheelLabel,
                emulationLabel = sensorViewModel.emulatedModel,
                onTimerTap = { timerViewModel.onTimerTap() },
                onTimerLongPress = { timerViewModel.onTimerLongPress() },
                onOpenSettings = { sensorViewModel.onOverlayDoubleTap() },
                onMinimizeToggle = { sensorViewModel.onOverlayPressed() },
                onLayout = {
                    if (it != size.value) {
                        size.value = it
                        onLayout(it)
                    }
                },
            )
            return@Box
        }
        Column(
            modifier = Modifier
                .wrapContentSize()
                .offset { visibilityOffset },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            when (location) {
                OverlayLocation.Bottom -> {
                    timer()
                    mainContent()
                }
                else -> {
                    mainContent()

                    timer()
                }
            }
        }
    }

}
