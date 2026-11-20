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
        assertEquals(RampTest.GUARD_SCALED, control.cycleRampTest())
        assertEquals(RampTest.STEPS, control.cycleRampTest())
        assertEquals(RampTest.BLIND, control.cycleRampTest())
        assertEquals(RampTest.OFF, control.cycleRampTest())
        assertEquals(4, lines.count { "tune rampTest=" in it })
    }

    // ---- STEPS: front-loaded steps, one per mainboard look ----

    @Test fun frontLoadedStepsPlan() {
        assertArrayEquals(intArrayOf(48, 53, 56), BikeControl.frontLoadedSteps(39, 56))
        assertArrayEquals(intArrayOf(47, 42, 39), BikeControl.frontLoadedSteps(56, 39))
        assertArrayEquals(intArrayOf(52, 55), BikeControl.frontLoadedSteps(47, 55))
        assertArrayEquals(intArrayOf(43, 44), BikeControl.frontLoadedSteps(40, 44))
        assertArrayEquals(intArrayOf(40, 48, 53, 55), BikeControl.frontLoadedSteps(30, 55))
        for (from in 0..100) for (to in 0..100) {
            if (abs(to - from) < BikeControl.STEPS_MIN_LEVELS) continue
            val plan = BikeControl.frontLoadedSteps(from, to)
            val sizes = (listOf(from) + plan.toList()).zipWithNext { a, b -> abs(b - a) }
            assertEquals("$from->$to ${plan.toList()}", to, plan.last())
            assertTrue("$from->$to ${plan.toList()}", plan.size in 2..BikeControl.STEPS_MAX)
            assertTrue("$from->$to strictly monotonic ${plan.toList()}", sizes.all { it > 0 } &&
                (listOf(from) + plan.toList()).zipWithNext { a, b -> (b - a) * (to - from) > 0 }.all { it })
            assertTrue("$from->$to front-loaded ${plan.toList()}", sizes.zipWithNext { a, b -> a >= b }.all { it })
        }
    }

    private fun levelOf(line: String) = line.substringAfter("level=").substringBefore(" ").toInt()
    private fun planOf() = lines.last { "steps plan=" in it }.substringAfter("plan=").substringBefore(" ").split(",").map(String::toInt)

    /**
     * A big jump up and back down: every planned step is written once, in order, each only after the echo shows the
     * last one, the bike's target register holds exactly the plan, and ERG stays on. Steady and jittered echoes.
     */
    @Test fun stepsWriteOnePlannedStepPerLook() {
        for ((refresh, delay) in listOf(listOf(500L) to 300L, listOf(600L) to 200L, jittered to 400L)) {
            val control = setUp(RampTest.STEPS, 15, base = 165)
            val writesBefore = events.size
            val start = now
            control.localErg(362); ride(control, 6_000, refresh, delay)
            assertEquals(lines.takeLast(15).joinToString("\n"), ControlMode.Erg, control.state.value.mode)
            val plan = planOf()
            val stepLines = lines.filter { "why=step" in it }
            println("STEPS up refresh=$refresh delay=$delay plan=$plan writes=${stepLines.map(::time).map { it - start }} " +
                lines.filter { "step took" in it }.map { it.substringAfter("after=") })
            assertTrue("plan $plan", plan.size >= 2)
            assertEquals(plan, stepLines.map(::levelOf))
            assertEquals(plan.size - 1, lines.count { "step took" in it })
            assertTrue(lines.any { "ramp done level=${plan.last()}" in it && "steps=${plan.size}" in it })
            assertEquals("bike target register holds exactly the plan, each written once", plan, events.drop(writesBefore).map { it.second })
            assertTrue("no ramp writes in STEPS", lines.none { "why=ramp" in it })
            // Each step waits for the previous one: ~one echo refresh each, never more than two.
            assertTrue(time(stepLines.last()) - time(stepLines.first()) <= (plan.size - 1) * 2L * refresh.max())

            lines.clear()
            control.localErg(165); ride(control, 15_000, refresh, delay)
            assertEquals(ControlMode.Erg, control.state.value.mode)
            val down = planOf()
            assertEquals(down, lines.filter { "why=step" in it }.map(::levelOf))
            assertTrue("down plan $down", down.zipWithNext { a, b -> b < a }.all { it })
            assertTrue("drop arrives", lines.any { "ramp done level=${down.last()}" in it })
        }
    }

    /** The knob turned back while a step is pending stops ERG (the ramp's knob check still runs in STEPS). */
    @Test fun stepsKnobBackStops() {
        val control = setUp(RampTest.STEPS, 10, base = 165)
        control.localErg(362)
        var held: Int? = null
        ride(control, 4_000, knob = { t -> if (t in 800..2400) (held ?: (echo - 3).also { held = it }) else null })
        println("STEPS knob back to $held: " + (lines.firstOrNull { "knob override" in it } ?: lines.firstOrNull { "stop reason" in it }))
        assertEquals(ControlMode.Manual, control.state.value.mode)
    }

    /** The bike ignores writes: the first step is never taken, so STEPS stops within RAMP_ACK_MAX_MS (+ one tick). */
    @Test fun stepsBikeIgnoringWritesStops() {
        val control = setUp(RampTest.STEPS, 10, base = 165)
        ignoreWrites = true
        val start = now
        control.localErg(362); ride(control, 6_000)
        val stop = lines.firstOrNull { "stop reason" in it }
        println("STEPS ignored writes: stopped ${stop?.let { time(it) - start }}ms, ${lines.count { "why=step" in it }} step writes")
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertEquals("first write + one retry", 2, lines.count { "why=step" in it })
        assertEquals(1, lines.count { "why=step" in it && "retry" in it })
        assertTrue(time(stop!!) - start <= BikeControl.RAMP_ACK_MAX_MS + 300)
    }

    /** A new target mid-steps cancels the plan; the new jump gets its own plan and finishes. */
    @Test fun stepsTargetChangeMidJump() {
        val control = setUp(RampTest.STEPS, 10, base = 165)
        control.localErg(300); ride(control, 400)
        assertTrue(lines.any { "steps plan=" in it })
        lines.clear()
        control.localErg(362); ride(control, 8_000)
        assertEquals(ControlMode.Erg, control.state.value.mode)
        assertTrue(lines.joinToString("\n"), lines.any { "ramp cancelled" in it })
        val plan = planOf()
        assertEquals(plan, lines.filter { "why=step" in it }.map(::levelOf).takeLast(plan.size))
        assertTrue(lines.any { "ramp done level=${plan.last()}" in it })
    }

    /** Leaving STEPS mid-jump does not change the jump in flight; the next jump ramps normally. */
    @Test fun cyclingOutOfStepsMidJump() {
        val control = setUp(RampTest.STEPS, 10, base = 165)
        control.localErg(362); ride(control, 200)
        control.cycleRampTest()
        assertEquals(RampTest.BLIND, control.state.value.rampTest)
        ride(control, 6_000)
        assertEquals(ControlMode.Erg, control.state.value.mode)
        assertTrue(lines.any { "ramp done" in it && "steps=" in it })
        assertTrue(lines.none { "why=ramp" in it })
    }
}
