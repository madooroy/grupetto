package com.spop.poverlay.control

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

/**
 * Nov 21 (Grassy Ridge -3): 30-s efforts at 362 W that landed ~23 W off hunted 52<->57 levels (322-413 W)
 * while cadence held 97-103 rpm. The trim moved levels per watt of error, and a level is ~13 W at 362 W
 * against ~6 W at 165 W, so with the bike's power reporting ~0.4 s late the loop overshot at effort power.
 */
class ErgHuntTest {
    private var now = 1000L
    private var level = 40
    private val history = ArrayDeque<Pair<Long, Int>>()
    private val control = BikeControl(true, { now }) { level = it; true }

    /** Real bike a little off the controller model (efforts land ~+23 W, as on Nov 21); its power reading trails the level by 0.4 s (matches the Nov 21 hunting: ~20 moves, sd ~30 W). */
    private fun watts(cadence: Float): Float {
        val shown = history.lastOrNull { it.first <= now - 400 }?.second ?: level
        return PowerModel.seedWatts(shown) * 0.985f * (cadence / 90f).pow(1.7f)
    }
    private fun cmd(vararg b: Int) = control.procedure("ble:t", b.map { it.toByte() }.toByteArray())
    private fun target(w: Int) = cmd(5, w and 255, w shr 8)

    /** Rides [seconds] and returns (level moves, lowest power) for seconds 5..28 after the start. */
    private fun ride(seconds: Int, cadence: Float): Pair<Int, Float> {
        val p = mutableListOf<Float>(); var moves = 0; var last = level
        repeat(seconds * 10) { i ->
            now += 100
            history.addLast(now to level); while (history.size > 40) history.removeFirst()
            if (i % 2 == 0) control.acceptSample(BikeSample(watts(cadence), cadence, level, now))
            control.tick()
            if (i in 50 until 280) { p += watts(cadence); if (level != last) moves++ }
            last = level
        }
        return moves to (p.minOrNull() ?: 0f)
    }

    @Test fun effortThatLandsHighSettlesWithoutHunting() {
        ride(2, 90f); assertEquals(1, cmd(0).result)
        target(165); ride(60, 90f)
        val worst = (1..6).map { target(362); val r = ride(30, 100f); target(165); ride(30, 92f); r }
        // Old trim: 13 moves and a dip to 307 W after landing at 405 W. Now it walks straight down to the target.
        // The first effort lands ~60 W high on an unlearned model: one step past the target, then back (old: 22 moves).
        assertTrue("first effort hunted (moves, lowest W): $worst", worst[0].first <= 8 && worst[0].second >= 330f)
        assertTrue("hunting at 362 W (moves, lowest W): $worst", worst.drop(1).all { it.first <= 4 && it.second >= 340f })
    }
}
