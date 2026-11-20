package com.spop.poverlay.control

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Review of 8f0827e (RampTest.STEPS). Same bike model as [RampGuardTest]: a target register set by Grupetto's
 * writes and the knob (latest wins, the knob wins inside its own 100 ms), read back as an echo that refreshes every
 * 500 ms and shows the register as it was 300 ms earlier. One addition: [dropWrite] loses a single write.
 *
 * Independent review (Fable) of 8f0827e. 1-3 failed on 8f0827e and pass after the fix; 5-9 cover gaps the commit's
 * tests missed. (Review test 4 checked the old RampGuardTest.smallJumpUsesRamp, now replaced by 5, and was removed.)
 */
class StepsReviewTest {
    private var now = 1000L
    private val events = mutableListOf<Pair<Long, Int>>()
    private val lines = mutableListOf<String>()
    private var dropWrite: (Int) -> Boolean = { false }
    private var knobThisTick = false
    private var echo = 40
    private var lastRefresh = 0L

    private fun bike() = BikeControl(true, { now }, kotlin.random.Random(1)) {
        if (!knobThisTick && !dropWrite(it)) events.add(now to it); true
    }.apply { trace = { lines.add("[$now] $it") } }

    private fun targetAt(t: Long): Int = events.lastOrNull { it.first <= t }?.second ?: echo

    private fun ride(control: BikeControl, ms: Int, cadence: (Int) -> Float = { 90f }, knob: (Int) -> Int? = { null }) {
        var t = 0
        while (t < ms) {
            now += 100; t += 100
            knobThisTick = false
            knob(t)?.let { events.add(now to it); knobThisTick = true }
            if (now - lastRefresh >= 500) { lastRefresh = now; echo = targetAt(now - 300) }
            val rpm = cadence(t)
            val watts = PowerModel.seedWatts(echo) * Math.pow(rpm / 90.0, 1.7).toFloat()
            if (t % 200 == 0) control.acceptSample(BikeSample(watts, rpm, echo, now))
            control.tick()
        }
    }

    private fun setUp(test: RampTest, base: Int = 165): BikeControl {
        lines.clear(); events.clear(); dropWrite = { false }
        val control = bike()
        control.tune(2, .007f, rampRate = 10)
        while (control.state.value.rampTest != test) control.cycleRampTest()
        echo = 40; lastRefresh = now; events.add(now to 40)
        repeat(150) { control.acceptSample(BikeSample(170f, 90f, 40, now)); now += 100; control.tick() }
        assertTrue(control.localErg(base)); ride(control, 6_000)
        lines.clear()
        return control
    }

    private fun time(line: String) = line.substringAfter("[").substringBefore("]").toLong()
    private fun levelOf(line: String) = line.substringAfter("level=").substringBefore(" ").toInt()
    private fun fromOf(line: String) = line.substringAfter("from=").substringBefore(" ").toInt()
    private fun plan() = lines.last { "steps plan=" in it }.substringAfter("plan=").substringBefore(" ").split(",").map(String::toInt)
    private fun stepWrites() = lines.filter { "why=step" in it }
    private fun log() = lines.filter { "snap" !in it }.joinToString("\n")

    // ---------- failed on 8f0827e ----------

    /**
     * 1. Up-jump 45 -> 59 (plan 52, 57, 59). The rider gives the knob a quick turn WITH the jump, to 62, 0.3 s in.
     * stepTick takes "echo >= step" as the bike having taken the step (BikeControl.kt:625), so the knob's 62
     * acknowledges step 1 and, 100 ms later on the same reading, step 2: both remaining steps are written 100 ms
     * apart with no board look between them, 57 and 59 are written below the 62 the bike reports, and ERG stays on.
     */
    @Test fun stepsKnobTurnedWithTheJumpIsNotAnAcknowledgement() {
        val control = setUp(RampTest.STEPS)
        control.localErg(362)
        ride(control, 8_000, knob = { t -> if (t in 300..400) 62 else null })
        val plan = plan()
        val took = lines.filter { "step took" in it }
        assertEquals("plan", listOf(52, 57, 59), plan)
        took.forEachIndexed { i, line ->
            assertEquals("a step counts as taken only when the echo shows that step:\n${log()}", plan[i], levelOf(line))
        }
        val gaps = stepWrites().map(::time).zipWithNext { a, b -> b - a }
        assertTrue("one step per look, but step writes were $gaps ms apart:\n${log()}", gaps.all { it >= 300 })
        assertTrue("no step written below what the bike reports on an up-jump:\n${log()}",
            stepWrites().all { levelOf(it) > fromOf(it) })
    }

    /**
     * 2. The same on a drop, where it matters most: 59 -> 42 (plan 50, 45, 42) and the rider turns the knob further
     * down, to 30. STEPS writes 45 and then 42 over the rider's hand, 100 ms apart, and ERG stays on. The normal ramp
     * (OFF) stops ERG on the same input ("ramp knob override").
     */
    @Test fun stepsDropKnobTurnedFurtherDownMustNotRaiseResistance() {
        val control = setUp(RampTest.STEPS)
        control.localErg(362); ride(control, 12_000)
        lines.clear()
        control.localErg(165)
        ride(control, 8_000, knob = { t -> if (t in 300..400) 30 else null })
        assertEquals("plan", listOf(50, 45, 42), plan())
        val raised = stepWrites().filter { levelOf(it) > fromOf(it) }
        assertTrue("on a drop STEPS raised the resistance above what the bike reported:\n${raised.joinToString("\n")}",
            raised.isEmpty())
        assertEquals("a knob turn mid-jump stops ERG, as in OFF", ControlMode.Manual, control.state.value.mode)
    }

    /**
     * 3. Each step is written exactly once, so one write the bike never takes ends ERG 2.5 s later and leaves the
     * bike on an intermediate step (52, not 59). The normal ramp shrugs the same loss off (see 8). Whether the
     * Bike+ ever loses a lone write is not something this model can tell.
     */
    @Test fun stepsOneLostWriteMustNotEndErgShortOfTheGoal() {
        val control = setUp(RampTest.STEPS)
        var lost = false
        dropWrite = { level -> (!lost && level == 57).also { if (it) lost = true } }
        control.localErg(362); ride(control, 8_000)
        assertTrue("the write of 57 was lost", lost)
        assertEquals("ERG still on:\n${log()}", ControlMode.Erg, control.state.value.mode)
        assertEquals("bike ends on the planned final level", 59, targetAt(now))
    }

    // ---------- coverage the commit lacked ----------

    /** 5. What smallJumpUsesRamp meant to prove, with a target that really jumps: 165 -> 200 W is 2 levels, ramped. */
    @Test fun smallJumpReallyUsesRamp() {
        val control = setUp(RampTest.STEPS)
        control.localErg(200); ride(control, 6_000)
        val jump = lines.first { "action=jump" in it }
        val size = abs(jump.substringAfter("jump to=").substringBefore(" ").toInt() - levelOf(jump))
        assertTrue("jump of $size", size in 1 until BikeControl.STEPS_MIN_LEVELS)
        assertTrue(lines.none { "steps plan=" in it || "why=step" in it })
        assertEquals(size, lines.count { "why=ramp" in it })
        assertEquals(ControlMode.Erg, control.state.value.mode)
    }

    /** 6. The plan over the whole 0-100 range (the commit checks 0-80), including both limits. */
    @Test fun frontLoadedStepsWholeRange() {
        for (from in 0..100) for (to in 0..100) {
            if (abs(to - from) < BikeControl.STEPS_MIN_LEVELS) continue
            val plan = BikeControl.frontLoadedSteps(from, to).toList()
            val all = listOf(from) + plan
            val sizes = all.zipWithNext { a, b -> abs(b - a) }
            assertEquals("$from->$to $plan", to, plan.last())
            assertTrue("$from->$to $plan", plan.size in 2..BikeControl.STEPS_MAX && plan.all { it in 0..100 })
            assertTrue("$from->$to $plan", all.zipWithNext { a, b -> (b - a) * (to - from) > 0 }.all { it })
            assertTrue("$from->$to $plan", sizes.zipWithNext { a, b -> a >= b }.all { it })
        }
        assertArrayEquals(intArrayOf(3, 4), BikeControl.frontLoadedSteps(0, 4))
        assertArrayEquals(intArrayOf(97, 95), BikeControl.frontLoadedSteps(100, 95))
    }

    /** 7. Up and down, unconditionally: the register holds exactly the plan, the jump arrives, and the later trim starts from the goal. */
    @Test fun stepsUpAndDownArriveAndTrimFromTheGoal() {
        val control = setUp(RampTest.STEPS)
        for (watts in listOf(362, 165)) {
            lines.clear(); val before = events.size
            control.localErg(watts); ride(control, 5_000)
            val plan = plan()
            assertEquals(plan, events.drop(before).map { it.second })
            assertTrue(lines.any { "arrive level=${plan.last()}" in it })
            ride(control, 15_000)
            assertEquals(ControlMode.Erg, control.state.value.mode)
            lines.firstOrNull { "why=trim" in it }?.let { assertEquals(plan.last(), fromOf(it)) }
        }
    }

    /** 8. The normal ramp survives the lost write that stops STEPS in 3. */
    @Test fun normalRampSurvivesOneLostWrite() {
        val control = setUp(RampTest.OFF)
        var lost = false
        dropWrite = { level -> (!lost && level == 57).also { if (it) lost = true } }
        control.localErg(362); ride(control, 8_000)
        assertTrue(lost)
        assertEquals(ControlMode.Erg, control.state.value.mode)
        assertEquals(59, targetAt(now))
    }

    /** 9. Pedalling stops mid-plan: the plan is dropped, nothing is written while stopped, and the restart plans afresh to the same goal. */
    @Test fun stepsCadenceStopMidPlanReplansAfterRestart() {
        val control = setUp(RampTest.STEPS)
        control.localErg(362)
        ride(control, 30_000, cadence = { t -> if (t in 500..3000) 10f else 90f })
        assertEquals(ControlMode.Erg, control.state.value.mode)
        assertEquals(2, lines.count { "steps plan=" in it })
        assertTrue(lines.any { "ramp cancelled" in it })
        assertEquals(59, targetAt(now))
        assertEquals(listOf(52, 57, 59), stepWrites().map(::levelOf))
    }
}
