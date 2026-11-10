package com.spop.poverlay.control

import org.junit.Assert.*
import org.junit.Test

/**
 * Debug ramp experiment ([RampTest]). The Bike+ target echo is modeled as on the Nov 5 ramp ride:
 * the bike service refreshes its copy of the target every [refreshMs] and then shows the level written
 * [delayMs] before that refresh; Grupetto reads it every 200 ms. With refresh 500 / delay 300 the echo
 * is 0.2-0.9 s old at a write (Nov 5 measured 0.2-0.83 s over 95 writes).
 */
class RampGuardTest {
    private var now = 1000L
    private val writes = mutableListOf<Pair<Long, Int>>()
    private val lines = mutableListOf<String>()

    private fun bike(seed: Int = 1) = BikeControl(true, { now }, kotlin.random.Random(seed)) { writes.add(now to it); true }
        .apply { trace = { lines.add("[$now] $it") } }

    // The bike service's copy of the target as Grupetto reads it; carried across ride() calls.
    private var echo = 40
    private var lastRefresh = 0L

    private fun targetAt(t: Long, start: Int): Int = writes.lastOrNull { it.first <= t }?.second ?: start

    /**
     * Ride [ms] at [cadence]. [knob] may override the echo (time since start -> level or null).
     * Returns the ramp write times.
     */
    private fun ride(control: BikeControl, ms: Int, start: Int, refreshMs: Long = 500, delayMs: Long = 300,
                     cadence: Float = 90f, knob: (Int, Int) -> Int? = { _, _ -> null }) {
        var t = 0
        while (t < ms) {
            now += 100; t += 100
            if (now - lastRefresh >= refreshMs) { lastRefresh = now; echo = targetAt(now - delayMs, start) }
            val shown = knob(t, echo) ?: echo
            val watts = PowerModel.seedWatts(shown) * Math.pow(cadence / 90.0, 1.7).toFloat()
            if (t % 200 == 0) control.acceptSample(BikeSample(watts, cadence, shown, now))
            control.tick()
        }
    }

    private fun settle(control: BikeControl, level: Int) {
        echo = level; lastRefresh = now
        repeat(150) { control.acceptSample(BikeSample(170f, 90f, level, now)); now += 100; control.tick() }
    }

    private fun rampWrites() = lines.filter { "why=ramp" in it }.map { it.substringAfter("[").substringBefore("]").toLong() }
    private fun maxGap(times: List<Long>) = times.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0L

    private fun jump(test: RampTest, rate: Int, refreshMs: Long = 500, delayMs: Long = 300): List<Long> {
        lines.clear(); writes.clear()
        val control = bike()
        control.tune(2, .007f, rampRate = rate)
        while (control.state.value.rampTest != test) control.cycleRampTest()
        settle(control, 40)
        assertTrue(control.localErg(170))
        ride(control, 3_000, 40, refreshMs, delayMs)
        lines.clear()
        control.localErg(360)
        ride(control, 4_000, 40, refreshMs, delayMs)
        assertEquals(lines.takeLast(15).joinToString("\n"), ControlMode.Erg, control.state.value.mode)
        assertTrue(lines.joinToString("\n"), lines.any { "ramp done" in it })
        return rampWrites()
    }

    @Test fun guardForRateCoversMeasuredEchoAge() {
        assertEquals(10, BikeControl.rampGuardFor(10))
        assertEquals(12, BikeControl.rampGuardFor(12))
        assertEquals(15, BikeControl.rampGuardFor(15))
        assertEquals(19, BikeControl.rampGuardFor(20))
        assertEquals(BikeControl.RAMP_ECHO_LAG_LEVELS, BikeControl.rampGuardFor(3))
    }

    /** Today's guard (5) stalls mid-ramp on a lagging echo, as on every Nov 5 jump. */
    @Test fun defaultGuardStallsOnLaggingEcho() {
        for (rate in listOf(10, 12, 15, 20)) {
            val gap = maxGap(jump(RampTest.OFF, rate))
            println("OFF rate=$rate maxGap=${gap}ms")
            assertTrue("rate $rate gap $gap", gap >= 200)
        }
    }

    /** Scaled guard: one write per 100 ms tick all the way, at every preset. */
    @Test fun scaledGuardNeverStallsOnLaggingEcho() {
        for (rate in listOf(10, 12, 15, 20)) {
            val times = jump(RampTest.GUARD_SCALED, rate)
            println("SCALED rate=$rate writes=${times.size} maxGap=${maxGap(times)}ms")
            assertTrue("rate $rate gap ${maxGap(times)}", maxGap(times) <= 100)
        }
    }

    /** The review's harsher echo (refresh every 600 ms): no stall up to 15/s; 20/s reported, not asserted. */
    @Test fun scaledGuardWithSlowerEchoRefresh() {
        for (rate in listOf(10, 12, 15)) {
            val gap = maxGap(jump(RampTest.GUARD_SCALED, rate, refreshMs = 600))
            println("SCALED refresh600 rate=$rate maxGap=${gap}ms")
            assertTrue("rate $rate gap $gap", gap <= 100)
        }
        println("SCALED refresh600 rate=20 maxGap=${maxGap(jump(RampTest.GUARD_SCALED, 20, refreshMs = 600))}ms")
    }

    /** A knob turned back against the ramp mid-ramp still stops ERG through the ramp's knob check, guard or not. */
    @Test fun knobAgainstRampStillStops() {
        for (test in listOf(RampTest.OFF, RampTest.GUARD_SCALED)) {
            lines.clear(); writes.clear()
            val control = bike()
            control.tune(2, .007f, rampRate = 10)
            while (control.state.value.rampTest != test) control.cycleRampTest()
            settle(control, 40)
            assertTrue(control.localErg(170)); ride(control, 3_000, 40)
            lines.clear()
            control.localErg(360)
            // 0.8 s into the ramp (echo has started to climb) the rider turns the knob 2 levels back and holds it.
            var held: Int? = null
            ride(control, 3_000, 40, knob = { t, echo -> if (t >= 800) (held ?: (echo - 2).also { held = it }) else null })
            println("$test knob-against: " + lines.filter { "knob override" in it || "stop reason" in it || "ramp done" in it }.joinToString(" | "))
            assertEquals(lines.takeLast(10).joinToString("\n"), ControlMode.Manual, control.state.value.mode)
            assertTrue(lines.joinToString("\n"), lines.any { "ramp knob override" in it })
        }
    }

    /** A knob turned with the ramp, past the goal, is still caught (here within 3 s). */
    @Test fun knobWithRampPastGoalIsCaught() {
        for (test in listOf(RampTest.OFF, RampTest.GUARD_SCALED)) {
            lines.clear(); writes.clear()
            val control = bike()
            control.tune(2, .007f, rampRate = 15)
            while (control.state.value.rampTest != test) control.cycleRampTest()
            settle(control, 40)
            assertTrue(control.localErg(170)); ride(control, 3_000, 40)
            lines.clear()
            val start = now
            control.localErg(360)
            // Rider spins the knob up to 75 from 0.4 s.
            ride(control, 6_000, 40, knob = { t, _ -> if (t >= 400) 75 else null })
            val stop = lines.firstOrNull { "stop reason" in it }
            println("$test knob-with-ramp: stopped ${stop?.substringAfter("[")?.substringBefore("]")?.toLong()?.minus(start)} ms after the target: ${stop ?: "no stop"}")
            assertEquals(ControlMode.Manual, control.state.value.mode)
            val at = stop!!.substringAfter("[").substringBefore("]").toLong()
            assertTrue("$test stopped after ${at - start} ms", at - start <= 3_000)
        }
    }

    /**
     * BLIND: every up-jump (the first ERG target included) takes the next arm from shuffled A/B pairs and uses
     * that arm's rate and guard; drops get no arm and run as normal.
     */
    @Test fun blindArmsAreBalancedAndApplied() {
        val control = bike(seed = 7)
        control.tune(2, .007f, rampRate = 20)
        control.cycleRampTest(); control.cycleRampTest()
        assertEquals(RampTest.BLIND, control.state.value.rampTest)
        settle(control, 40)
        assertTrue(control.localErg(170)); ride(control, 3_000, 40)
        val arms = lines.filter { "ramp arm=" in it }.map { it.substringAfter("arm=").substringBefore(" ") }.toMutableList()
        repeat(6) {
            lines.clear()
            control.localErg(360); ride(control, 20_000, 40)
            assertEquals(ControlMode.Erg, control.state.value.mode)
            val armLine = lines.single { "ramp arm=" in it }
            arms += armLine.substringAfter("arm=").substringBefore(" ")
            val gap = maxGap(rampWrites())
            if ("arm=A" in armLine) assertTrue("$armLine gap=$gap", "rate=12/s guard=5" in armLine && gap >= 200)
            else assertTrue("$armLine gap=$gap", "rate=10/s guard=10" in armLine && gap <= 100)
            lines.clear()
            control.localErg(170); ride(control, 20_000, 40)
            assertTrue("drop has no arm", lines.none { "ramp arm=" in it })
        }
        println("arms=$arms")
        assertEquals(7, arms.size)
        assertTrue(arms.chunked(2).filter { it.size == 2 }.all { it.toSet() == setOf("A", "B") })
    }

    @Test fun cycleRampTestWrapsAndTraces() {
        val control = bike()
        assertEquals(RampTest.OFF, control.state.value.rampTest)
        assertEquals(RampTest.GUARD_SCALED, control.cycleRampTest())
        assertEquals(RampTest.BLIND, control.cycleRampTest())
        assertEquals(RampTest.OFF, control.cycleRampTest())
        assertEquals(3, lines.count { "tune rampTest=" in it })
    }
}
