package com.spop.poverlay.control

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Debug ramp experiment ([RampTest]). The Bike+ is modeled as a target register: Grupetto's writes and the
 * rider's knob both set it (the latest wins), unless the bike ignores writes. Grupetto reads an echo of it
 * every 200 ms; the echo refreshes every [refreshMs] (optionally jittered) and shows the target as it was
 * [delayMs] before the refresh. Nov 5: echo changes 0.4-0.82 s apart, 0.2-0.83 s old at writes.
 */
class RampGuardTest {
    private var now = 1000L
    private val events = mutableListOf<Pair<Long, Int>>()  // target register history: writes and knob moves
    private val lines = mutableListOf<String>()
    private var ignoreWrites = false
    // The rider's hand on the knob wins over a write in the same 100 ms.
    private var knobThisTick = false
    private var echo = 40
    private var lastRefresh = 0L
    private var nextRefreshMs = 500L
    private val jitter = kotlin.random.Random(3)

    private fun bike(seed: Int = 1) = BikeControl(true, { now }, kotlin.random.Random(seed)) {
        if (!ignoreWrites && !knobThisTick) events.add(now to it); true
    }.apply { trace = { lines.add("[$now] $it") } }

    private fun targetAt(t: Long): Int = events.lastOrNull { it.first <= t }?.second ?: echo

    /** [knob] (ms since this ride started) -> a level the rider turns the knob to at that moment, or null. */
    private fun ride(control: BikeControl, ms: Int, refreshMs: List<Long> = listOf(500), delayMs: Long = 300,
                     cadence: Float = 90f, knob: (Int) -> Int? = { null }) {
        var t = 0
        while (t < ms) {
            now += 100; t += 100
            knobThisTick = false
            knob(t)?.let { events.add(now to it); knobThisTick = true }
            if (now - lastRefresh >= nextRefreshMs) {
                lastRefresh = now; echo = targetAt(now - delayMs)
                nextRefreshMs = refreshMs[jitter.nextInt(refreshMs.size)]
            }
            val watts = PowerModel.seedWatts(echo) * Math.pow(cadence / 90.0, 1.7).toFloat()
            if (t % 200 == 0) control.acceptSample(BikeSample(watts, cadence, echo, now))
            control.tick()
        }
    }

    private fun settle(control: BikeControl, level: Int) {
        echo = level; lastRefresh = now; events.clear(); events.add(now to level)
        repeat(150) { control.acceptSample(BikeSample(170f, 90f, level, now)); now += 100; control.tick() }
    }

    private fun time(line: String) = line.substringAfter("[").substringBefore("]").toLong()
    private fun rampWrites() = lines.filter { "why=ramp" in it }.map(::time)
    private fun maxGap(times: List<Long>) = times.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0L

    private fun setUp(test: RampTest, rate: Int, seed: Int = 1, start: Int = 40, base: Int = 170): BikeControl {
        lines.clear(); events.clear(); ignoreWrites = false
        val control = bike(seed)
        control.tune(2, .007f, rampRate = rate)
        while (control.state.value.rampTest != test) control.cycleRampTest()
        settle(control, start)
        assertTrue(control.localErg(base)); ride(control, 6_000)
        lines.clear()
        return control
    }

    private fun jump(test: RampTest, rate: Int, refreshMs: List<Long> = listOf(500), delayMs: Long = 300,
                     to: Int = 360): List<Long> {
        val control = setUp(test, rate)
        control.localErg(to)
        ride(control, 5_000, refreshMs, delayMs)
        assertEquals(lines.takeLast(15).joinToString("\n"), ControlMode.Erg, control.state.value.mode)
        assertTrue(lines.joinToString("\n"), lines.any { "ramp done" in it })
        return rampWrites()
    }

    private val jittered = listOf(400L, 600L, 600L, 800L)

    @Test fun guardForRate() {
        assertEquals(15, BikeControl.rampGuardFor(10))
        assertEquals(18, BikeControl.rampGuardFor(12))
        assertEquals(22, BikeControl.rampGuardFor(15))
        assertEquals(28, BikeControl.rampGuardFor(20))
        assertEquals(6, BikeControl.rampGuardFor(3))
    }

    /** Today's guard (5) stalls mid-ramp on a lagging echo, as on every Nov 5 jump. */
    @Test fun defaultGuardStallsOnLaggingEcho() {
        for (rate in listOf(10, 12, 15, 20)) {
            val gap = maxGap(jump(RampTest.OFF, rate))
            println("OFF rate=$rate maxGap=${gap}ms")
            assertTrue("rate $rate gap $gap", gap >= 200)
        }
        assertTrue("OFF logs no stall lines", lines.none { "ramp stall" in it })
    }

    /** Scaled guard: one write per 100 ms tick, at every preset, with steady and jittered (0.4-0.8 s, 400 ms old) echoes. */
    @Test fun scaledGuardNeverStalls() {
        for ((refresh, delay) in listOf(listOf(500L) to 300L, listOf(600L) to 300L, jittered to 400L))
            for (rate in listOf(10, 12, 15, 20)) {
                val gap = maxGap(jump(RampTest.GUARD_SCALED, rate, refresh, delay))
                println("SCALED refresh=$refresh delay=$delay rate=$rate maxGap=${gap}ms")
                assertTrue("rate $rate refresh $refresh gap $gap", gap <= 100)
                assertTrue(lines.none { "ramp stall" in it })
            }
    }

    /** The review's failing case: a 20+ level jump at 10/s (arm B's rate) on the jittered 400 ms-old echo. */
    @Test fun scaledGuardLongJumpAtTenPerSecond() {
        val control = setUp(RampTest.GUARD_SCALED, 10, start = 40, base = 150)
        control.localErg(420)
        ride(control, 6_000, jittered, 400)
        val jumpLine = lines.first { "action=jump" in it }
        val levels = jumpLine.substringAfter("level=").substringBefore(" ").toInt() to
            jumpLine.substringAfter("jump to=").substringBefore(" ").toInt()
        println("long jump $levels maxGap=${maxGap(rampWrites())}ms")
        assertTrue("jump $levels", levels.second - levels.first >= 20)
        assertTrue(maxGap(rampWrites()) <= 100)
    }

    /**
     * A knob turned back mid-ramp and held for 1.2 s. Below what the bike has already shown, both modes stop
     * through the ramp's knob check. Below the bike's target but above the lagging echo it looks like progress:
     * guard 5 stalls on it and stops (~2.2 s); the scaled guard keeps ramping, Grupetto's writes override the knob
     * once it is released, and the ramp ends at its goal with no level above the goal ever written. (A single short
     * turn is overwritten by the next write and goes unseen in every mode, base code included.)
     */
    @Test fun knobAgainstRampStops() {
        for (test in listOf(RampTest.OFF, RampTest.GUARD_SCALED)) for (belowEcho in listOf(true, false)) {
            val control = setUp(test, 10)
            val start = now
            control.localErg(360)
            var held: Int? = null
            ride(control, 4_000, knob = { t ->
                if (t in 800..2000) (held ?: ((if (belowEcho) echo else targetAt(now)) - 2).also { held = it }) else null
            })
            val stop = lines.firstOrNull { "stop reason" in it }
            println("$test knob-back belowEcho=$belowEcho to $held: ${stop?.let { time(it) - start - 800 }}ms after the turn: " +
                (lines.firstOrNull { "knob override" in it } ?: stop))
            if (belowEcho || test == RampTest.OFF) assertEquals(ControlMode.Manual, control.state.value.mode)
            if (belowEcho) assertTrue(lines.any { "ramp knob override" in it })
            if (!belowEcho && test == RampTest.GUARD_SCALED) {
                assertEquals(ControlMode.Erg, control.state.value.mode)
                val goal = lines.first { "action=jump" in it }.substringAfter("jump to=").substringBefore(" ").toInt()
                assertTrue(lines.any { "ramp done level=$goal" in it })
                assertTrue(lines.filter { "why=ramp" in it }.all { it.substringAfter("level=").substringBefore(" ").toInt() <= goal })
            }
        }
    }

    /** After the ramp: knob +3 or +8 is caught in both modes (the review measured 0.7-2.7 s); here within 3 s. */
    @Test fun knobAfterRampIsCaught() {
        for (test in listOf(RampTest.OFF, RampTest.GUARD_SCALED)) for (rate in listOf(10, 20)) for (delta in listOf(3, 8)) {
            val control = setUp(test, rate)
            control.localErg(360)
            var turnedAt = 0L
            ride(control, 6_000, knob = { t ->
                if (turnedAt == 0L && lines.any { "ramp done" in it }) { turnedAt = now; targetAt(now) + delta } else null
            })
            val stop = lines.firstOrNull { "stop reason" in it }
            val after = stop?.let { time(it) - turnedAt }
            println("$test rate=$rate knob+$delta after ramp: stopped ${after}ms after the turn")
            assertEquals(ControlMode.Manual, control.state.value.mode)
            assertTrue("$test $rate +$delta: $after ms", after!! <= 3_000)
        }
    }

    /** The bike ignores writes (mainboard not acknowledging): stopped in both modes, the scaled guard within 2.5 s + 1.5 s. */
    @Test fun bikeIgnoringWritesStops() {
        for (test in listOf(RampTest.OFF, RampTest.GUARD_SCALED)) for (rate in listOf(10, 20)) {
            val control = setUp(test, rate)
            ignoreWrites = true
            val start = now
            control.localErg(360)
            ride(control, 8_000)
            val stop = lines.firstOrNull { "stop reason" in it }
            val writes = lines.count { "why=ramp" in it }
            println("$test rate=$rate ignored writes: stopped ${stop?.let { time(it) - start }}ms, $writes ramp writes")
            assertEquals(ControlMode.Manual, control.state.value.mode)
            assertTrue(time(stop!!) - start <= 4_500)
        }
    }

    /**
     * BLIND: jumps of 8+ levels take arms from shuffled AABB blocks, with the arm's rate on the target line;
     * a jump cancelled by a new target returns its arm; drops and small jumps get none. Stalls are logged.
     */
    @Test fun blindArms() {
        val control = setUp(RampTest.BLIND, 20, seed = 7, base = 165)
        val arms = mutableListOf<String>()
        repeat(8) { i ->
            lines.clear()
            if (i == 2) {
                // Double target: 165 -> 300, then 362 after 300 ms. The first jump's arm must come back.
                control.localErg(300); ride(control, 300)
                control.localErg(362); ride(control, 20_000)
                assertEquals(lines.joinToString("\n"), 2, lines.count { "ramp arm=" in it && "returned" !in it })
                val armLines = lines.filter { "ramp arm=" in it && "returned" !in it }
                val returned = lines.single { "ramp arm returned=" in it }.substringAfter("returned=")
                assertEquals(returned, armLines[1].substringAfter("arm=").substringBefore(" "))
            } else {
                control.localErg(362); ride(control, 20_000)
            }
            assertEquals(ControlMode.Erg, control.state.value.mode)
            val armLine = lines.last { "ramp arm=" in it && "returned" !in it }
            arms += armLine.substringAfter("arm=").substringBefore(" ")
            val target = lines.last { "action=jump" in it }
            val gap = maxGap(rampWrites().filter { it >= time(target) })
            if ("arm=A" in armLine) {
                assertTrue(armLine, "rate=12/s guard=5" in armLine && "rate=12/s" in target && gap >= 200)
                assertTrue("arm A stalls are logged", lines.any { "ramp stall" in it })
            } else {
                assertTrue("$armLine gap=$gap", "rate=10/s guard=15" in armLine && "rate=10/s" in target && gap <= 100)
            }
            lines.clear()
            control.localErg(165); ride(control, 20_000)
            assertTrue("drop has no arm", lines.none { "ramp arm" in it })
            assertTrue("drop uses the chip rate", lines.any { "action=jump" in it && "rate=20/s" in it })
        }
        println("arms=$arms")
        assertEquals(4, arms.count { it == "A" })
        assertTrue(arms.chunked(4).all { it.count { a -> a == "A" } == 2 })
        // A small jump (under BLIND_MIN_LEVELS) gets no arm.
        lines.clear(); control.localErg(200); ride(control, 15_000)
        val small = lines.first { "action=jump" in it }
        val size = small.substringAfter("jump to=").substringBefore(" ").toInt() - small.substringAfter("level=").substringBefore(" ").toInt()
        assertTrue("jump of $size", size < BikeControl.BLIND_MIN_LEVELS)
        assertTrue(lines.none { "ramp arm" in it })
    }

    /** Leaving BLIND mid-ramp drops the arm's overrides: the rest of the ramp runs at the chip rate. */
    @Test fun cyclingOutOfBlindMidRampClearsArm() {
        val control = setUp(RampTest.BLIND, 20, seed = 7, base = 165)
        control.localErg(362); ride(control, 300)
        assertTrue(lines.any { "ramp arm=" in it })
        control.cycleRampTest()
        assertEquals(RampTest.OFF, control.state.value.mode.let { control.state.value.rampTest })
        val before = lines.count { "why=ramp" in it }
        ride(control, 300)
        val written = lines.filter { "why=ramp" in it }.drop(before)
        // At 20/s each 100 ms tick writes 2 levels (an arm at 10 or 12/s would write ~1).
        val levels = written.map { it.substringAfter("level=").substringBefore(" ").toInt() }
        println("after leaving BLIND: $levels")
        assertTrue(levels.zipWithNext { a, b -> b - a }.any { it >= 2 })
    }

    @Test fun cycleRampTestWrapsAndTraces() {
        val control = bike()
        assertEquals(RampTest.OFF, control.state.value.rampTest)
        assertEquals(RampTest.ONE_SHOT, control.cycleRampTest())
        assertEquals(RampTest.GUARD_SCALED, control.cycleRampTest())
        assertEquals(RampTest.BLIND, control.cycleRampTest())
        assertEquals(RampTest.OFF, control.cycleRampTest())
        assertEquals(4, lines.count { "tune rampTest=" in it })
    }

    private fun jumpLevels(): Pair<Int, Int> = lines.first { "action=jump" in it }.let {
        it.substringAfter(" level=").substringBefore(" ").toInt() to it.substringAfter("jump to=").substringBefore(" ").toInt()
    }

    /** ONE_SHOT, jumps up to 12 levels at 90 rpm, up and down: one write of the goal, no ramp writes, ERG keeps control. */
    @Test fun oneShotSingleWrite() {
        for ((from, to) in listOf(170 to 230, 170 to 260, 260 to 170)) for ((refresh, delay) in listOf(listOf(500L) to 300L, jittered to 400L)) {
            val control = setUp(RampTest.ONE_SHOT, 10, base = from)
            control.localErg(to)
            ride(control, 8_000, refresh, delay)
            val (a, b) = jumpLevels()
            println("ONE_SHOT $from->$to levels $a->$b refresh=$refresh")
            assertTrue("$a->$b", abs(b - a) in 2..BikeControl.ONE_SHOT_MAX_LEVELS)
            assertEquals(lines.joinToString("\n"), 1, lines.count { "oneshot write level=$b " in it })
            assertTrue(lines.none { "why=ramp" in it })
            assertTrue(lines.joinToString("\n"), lines.none { "write level=" in it && "oneshot" !in it && "why=trim" !in it })
            assertTrue(lines.any { "arrive level=$b" in it })
            assertEquals(lines.takeLast(10).joinToString("\n"), ControlMode.Erg, control.state.value.mode)
        }
    }

    /** Larger jumps, and lower cadence, fall back to the normal ramp. */
    @Test fun oneShotFallsBackToRamp() {
        var control = setUp(RampTest.ONE_SHOT, 10, base = 150)
        control.localErg(420)
        ride(control, 6_000)
        val (a, b) = jumpLevels()
        assertTrue("$a->$b", b - a > BikeControl.ONE_SHOT_MAX_LEVELS)
        assertTrue(lines.any { "oneshot skip" in it } && lines.any { "why=ramp" in it } && lines.any { "ramp done" in it })
        assertEquals(ControlMode.Erg, control.state.value.mode)

        lines.clear(); events.clear()
        control = bike(); control.tune(2, .007f, rampRate = 10)
        while (control.state.value.rampTest != RampTest.ONE_SHOT) control.cycleRampTest()
        settle(control, 40)
        assertTrue(control.localErg(150)); ride(control, 8_000, cadence = 80f)
        lines.clear()
        control.localErg(200)
        ride(control, 6_000, cadence = 80f)
        assertTrue(lines.joinToString("\n"), lines.any { "oneshot skip" in it && it.substringAfter("cad=").substringBefore(" ").toFloat() < BikeControl.ONE_SHOT_MIN_CADENCE })
        assertTrue(lines.none { "oneshot write" in it })
    }

    /** A knob turned and held away from the one-shot goal: the echo never arrives, so control stops within the ack window. */
    @Test fun oneShotKnobStops() {
        val control = setUp(RampTest.ONE_SHOT, 10, base = 170)
        control.localErg(230)
        var heldFrom = -1
        ride(control, 6_000) { t -> if (t in 300..6000) { if (heldFrom < 0) heldFrom = t; 42 } else null }
        val (a, b) = jumpLevels()
        assertTrue("$a->$b", b > a)
        assertEquals(lines.takeLast(10).joinToString("\n"), ControlMode.Manual, control.state.value.mode)
        val stopAt = time(lines.first { "stop" in it.lowercase() && "manually" in it })
        println("one-shot knob: stopped ${stopAt - time(lines.first { "oneshot write" in it })}ms after the write")
        assertTrue(stopAt - time(lines.first { "oneshot write" in it }) <= BikeControl.ONE_SHOT_ACK_MS + 300)
    }

    /** Review finding 2: braking right after a one-shot, with the stopped bike never finishing the write, keeps ERG. */
    @Test fun oneShotBrakeDuringWaitKeepsErg() {
        val control = setUp(RampTest.ONE_SHOT, 10, base = 170)
        val start = echo
        control.localErg(230)
        ride(control, 200)
        assertTrue(lines.any { "oneshot write" in it })
        events.add(now to start)  // the bike holds the old target while stopped
        ride(control, 3_000, cadence = 0f)
        assertTrue(lines.joinToString("\n"), lines.any { "oneshot cancelled (stopped)" in it })
        assertEquals(lines.takeLast(10).joinToString("\n"), ControlMode.Erg, control.state.value.mode)
        ride(control, 25_000)
        assertEquals(lines.takeLast(10).joinToString("\n"), ControlMode.Erg, control.state.value.mode)
        assertTrue(lines.count { "action=jump" in it } >= 2)
    }

    /** Finding 4: a target change during the wait is taken once the first echo arrives; ERG keeps control. */
    @Test fun oneShotTargetChangeDuringWait() {
        for ((refresh, delay) in listOf(listOf(500L) to 300L, listOf(800L) to 650L)) {
            val control = setUp(RampTest.ONE_SHOT, 10, base = 170)
            control.localErg(260); ride(control, 300, refresh, delay)
            control.localErg(200); ride(control, 8_000, refresh, delay)
            assertEquals(lines.takeLast(10).joinToString("\n"), ControlMode.Erg, control.state.value.mode)
            assertEquals(lines.joinToString("\n"), 2, lines.count { "oneshot write" in it })
        }
    }

    /** Finding 3: a small jump on the slowest echo (800 ms refresh, 650 ms old) arrives inside the flat window. */
    @Test fun oneShotSmallJumpSlowEcho() {
        val control = setUp(RampTest.ONE_SHOT, 10, base = 170)
        control.localErg(190); ride(control, 6_000, listOf(800L), 650L)
        val (a, b) = jumpLevels()
        println("small one-shot $a->$b")
        assertTrue("$a->$b", b - a in 1..4)
        assertTrue(lines.any { "oneshot write" in it } && lines.any { "arrive level=$b" in it })
        assertEquals(ControlMode.Erg, control.state.value.mode)
    }

    /** Finding 1: which ride jumps qualify at 90 rpm. 165->362 W is too large and ramps; 200<->320 W one-shots. */
    @Test fun oneShotWhichWorkoutJumpsQualify() {
        var control = setUp(RampTest.ONE_SHOT, 10, base = 165)
        control.localErg(362); ride(control, 6_000)
        println("165->362: " + lines.first { "oneshot" in it })
        assertTrue(lines.any { "oneshot skip" in it })
        for ((from, to) in listOf(200 to 320, 320 to 200, 200 to 300, 300 to 200)) {
            control = setUp(RampTest.ONE_SHOT, 10, base = from)
            control.localErg(to); ride(control, 6_000)
            println("$from->$to: " + lines.first { "oneshot" in it })
            assertTrue(lines.any { "oneshot write" in it })
        }
    }
}
