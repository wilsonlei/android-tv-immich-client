package com.immichclient.tv

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShufflerTest {
    /** Simulate slideshow: pick next, show (mark), repeat. */
    private fun play(n: Int, start: Int, steps: Int, seed: Int, allowed: (Int) -> Boolean = { true }): List<Int> {
        val s = Shuffler(Random(seed))
        val shown = mutableListOf(start)
        var current = start
        repeat(steps) {
            val d = s.next(n, current, allowed)
            if (d < 0) return shown
            s.mark(d); current = d; shown += d
        }
        return shown
    }

    @Test fun everyPhotoOncePerRound() {
        for (seed in 0 until 200) {
            val n = 1 + seed % 40 + 1
            val r = play(n, start = seed % n, steps = n - 1, seed = seed)
            assertEquals("seed $seed", n, r.size)
            assertEquals("seed $seed: duplicate in first round", n, r.toSet().size)
        }
    }

    @Test fun nextRoundsAlsoCompleteAndNoImmediateRepeat() {
        val n = 25
        val r = play(n, start = 3, steps = n * 4 - 1, seed = 7)
        // round 1 = n photos; after that the last one of the previous round counts as seen, so rounds of n - 1
        assertEquals(n, r.take(n).toSet().size)
        r.drop(n).chunked(n - 1).filter { it.size == n - 1 }.forEach { assertEquals(n - 1, it.toSet().size) }
        r.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
    }

    @Test fun skippedItems() {
        val n = 30
        val r = play(n, start = 0, steps = 40, seed = 1) { it % 3 != 0 } // skip "videos"
        val firstRound = r.drop(1).take(19) // 20 allowed, start (0) did not count
        assertTrue(firstRound.none { it % 3 == 0 })
        assertEquals(19, firstRound.toSet().size)
    }

    @Test fun nothingPossible() {
        assertEquals(-1, Shuffler().next(1, 0) { true })
        assertEquals(-1, Shuffler().next(5, 0) { false })
    }

    @Test fun manuallyViewedPhotoCountsAsSeen() {
        val s = Shuffler(Random(3))
        val n = 10
        s.next(n, 0) { true }
        s.mark(5) // manually browsed to 5 during the slideshow
        val r = mutableListOf<Int>(); var h = 5
        repeat(8) { val d = s.next(n, h) { true }; s.mark(d); r += d; h = d }
        assertEquals((1..9).toSet() - 5, r.toSet())
    }
}
