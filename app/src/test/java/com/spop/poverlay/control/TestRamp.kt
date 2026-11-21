package com.spop.poverlay.control

/**
 * These tests were written against a 10 levels/s ramp; the app default is now 15/s.
 * Pinning the rate keeps their timing checks about the control logic, not the default.
 */
internal fun BikeControl.atTestRamp(): BikeControl =
    apply { tune(state.value.shiftSize, state.value.gain, rampRate = 10) }
