package com.spop.poverlay.overlay.composables

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.R
import com.spop.poverlay.overlay.BackgroundColorDefault
import com.spop.poverlay.overlay.OverlayLocation
import com.spop.poverlay.overlay.OverlayCornerRadius

/**
 * Dragging is handled by [Overlay] for the bar and the column together.
 *
 * The overlay docked against the left or right edge: the same numbers as the bar, stacked.
 * The chart is left out (no room in a column). Same background and the same 50% fade when
 * minimized as the bar. Expanded shows units and calories; minimized shows numbers only.
 */
@Composable
fun OverlaySideContent(
    location: OverlayLocation,
    isMinimized: Boolean,
    showTimerWhenMinimized: Boolean,
    contentAlpha: Float,
    isTread: Boolean,
    timerLabel: String,
    timerPaused: Boolean,
    powerLabel: String,
    powerSourceLabel: String?,
    cadenceLabel: String,
    resistanceLabel: String,
    inclineLabel: String,
    speedLabel: String,
    speedUnit: String,
    heartRateLabel: String,
    heartRateColor: Color,
    caloriesLabel: String,
    showPowerField: Boolean,
    showCadenceField: Boolean,
    showResistanceField: Boolean,
    showInclineField: Boolean,
    showSpeedField: Boolean,
    showHeartRateField: Boolean,
    showCaloriesField: Boolean,
    flywheelLabel: kotlinx.coroutines.flow.Flow<String?>?,
    emulationLabel: String?,
    rampLabel: String? = null,
    onRampTap: () -> Unit = {},
    onTimerTap: () -> Unit,
    onTimerLongPress: () -> Unit,
    onOpenSettings: () -> Unit,
    onMinimizeToggle: () -> Unit,
    onLayout: (IntSize) -> Unit,
) {
    val onLeft = location != OverlayLocation.Right
    val shape = if (onLeft) RoundedCornerShape(topEnd = OverlayCornerRadius, bottomEnd = OverlayCornerRadius)
    else RoundedCornerShape(topStart = OverlayCornerRadius, bottomStart = OverlayCornerRadius)
    // Minimize tucks the column toward its edge; expand opens it away from the edge.
    val towardEdge = if (onLeft) Icons.Filled.KeyboardArrowLeft else Icons.Filled.KeyboardArrowRight
    val awayFromEdge = if (onLeft) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowLeft
    val showUnits = !isMinimized

    Column(
        modifier = Modifier
            .alpha(contentAlpha)
            .onSizeChanged(onLayout)
            .background(BackgroundColorDefault, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .width(if (isMinimized) 76.dp else 96.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Open settings",
                tint = Color.White,
                modifier = Modifier.size(20.dp).clickable { onOpenSettings() },
            )
            Icon(
                imageVector = if (isMinimized) awayFromEdge else towardEdge,
                contentDescription = if (isMinimized) "Expand" else "Minimize",
                tint = Color.White,
                modifier = Modifier.size(24.dp).clickable { onMinimizeToggle() },
            )
        }
        if (emulationLabel != null) Text("DEMO", color = Color(0xFFFFCC44), fontSize = 11.sp)
        // "Timer when minimized" off means off, as on the bar.
        if (!isMinimized || showTimerWhenMinimized) {
            // Paused: the timer blinks, as on the bar.
            val pausedBlink = if (timerPaused) {
                androidx.compose.animation.core.rememberInfiniteTransition().animateFloat(
                    initialValue = 1f,
                    targetValue = 0.6f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(500, easing = androidx.compose.animation.core.LinearEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                    )
                ).value
            } else 1f
            SideField(
                modifier = Modifier
                    .alpha(pausedBlink)
                    .semantics { if (timerPaused) stateDescription = "Paused" }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onTimerTap() }, onLongPress = { onTimerLongPress() })
                    },
                value = timerLabel,
                unit = null,
                iconDrawable = R.drawable.ic_timer,
                large = false,
            )
        }
        if (showPowerField) SideField(value = powerLabel, unit = if (showUnits) powerSourceLabel ?: "watts" else null,
            iconDrawable = R.drawable.ic_power, large = showUnits)
        if (showCadenceField) SideField(value = cadenceLabel, unit = if (showUnits) "rpm" else null,
            iconDrawable = R.drawable.ic_cadence, large = showUnits)
        if (showResistanceField) SideField(value = resistanceLabel, unit = if (showUnits) "%" else null,
            iconDrawable = R.drawable.ic_resistance, large = showUnits)
        // Incline reuses ic_speed, as on the bar.
        if (isTread && showInclineField) SideField(value = inclineLabel, unit = if (showUnits) "% incline" else null,
            iconDrawable = R.drawable.ic_speed, large = showUnits)
        if (showSpeedField) SideField(value = speedLabel, unit = if (showUnits) speedUnit else null,
            iconDrawable = R.drawable.ic_speed, large = showUnits)
        if (showHeartRateField) SideField(value = heartRateLabel, unit = if (showUnits) "bpm" else null,
            iconDrawable = R.drawable.ic_hrm, large = showUnits, textColor = heartRateColor)
        if (showCaloriesField && showUnits) SideField(value = caloriesLabel, unit = "kcal",
            iconDrawable = R.drawable.ic_calories, large = true)
        if (rampLabel != null) {
            // Tap to step the ERG ramp speed mid-ride, as on the bar.
            val rampTap by androidx.compose.runtime.rememberUpdatedState(onRampTap)
            Text(
                rampLabel,
                color = Color(0xFF6EE7B7),
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier
                    .background(Color(0x5552728F), RoundedCornerShape(6.dp))
                    .pointerInput(Unit) { detectTapGestures(onTap = { rampTap() }) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        if (flywheelLabel != null) FlywheelChip(flywheelLabel, leadingSpace = false)
    }
}

@Composable
private fun SideField(
    value: String,
    unit: String?,
    iconDrawable: Int,
    large: Boolean,
    modifier: Modifier = Modifier,
    textColor: Color = Color.White,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                modifier = Modifier.requiredHeight(20.dp).requiredWidth(16.dp).padding(vertical = 3.dp),
                painter = painterResource(id = iconDrawable),
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(value, color = textColor, fontSize = if (large) 22.sp else 18.sp, maxLines = 1)
        }
        if (unit != null) {
            Text(unit, color = Color.LightGray, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(start = 22.dp))
        }
    }
}
