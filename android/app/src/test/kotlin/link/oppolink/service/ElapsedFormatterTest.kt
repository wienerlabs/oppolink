package link.oppolink.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Boundary tests for [formatElapsed]. The notification ticker uses this
 * to render elapsed call time. Boundaries that mattered the most in
 * field testing:
 *
 *  - 0 ms (just-started call) -> "00:00"
 *  - 59:59 (under-hour cap)   -> "59:59"
 *  - 60:00 (hour spillover)   -> "1:00:00"
 *  - 90:00 (one + half hour)  -> "1:30:00"
 */
class ElapsedFormatterTest {
    @Test fun zero() {
        assertEquals("00:00", formatElapsed(0))
    }

    @Test fun seconds_only() {
        assertEquals("00:07", formatElapsed(7_000))
    }

    @Test fun single_minute_boundary() {
        assertEquals("01:00", formatElapsed(60_000))
    }

    @Test fun mm_ss_padding() {
        assertEquals("05:09", formatElapsed(5L * 60_000 + 9_000))
    }

    @Test fun fifty_nine_fifty_nine() {
        assertEquals("59:59", formatElapsed(59L * 60_000 + 59_000))
    }

    @Test fun hour_boundary_spills_to_h_mm_ss() {
        assertEquals("1:00:00", formatElapsed(60L * 60_000))
    }

    @Test fun ninety_minutes() {
        assertEquals("1:30:00", formatElapsed(90L * 60_000))
    }

    @Test fun multi_hour() {
        // 3h 7m 42s -> 3:07:42
        val ms = 3L * 3_600_000 + 7L * 60_000 + 42_000
        assertEquals("3:07:42", formatElapsed(ms))
    }

    @Test fun negative_clamps_to_zero() {
        // Wall-clock drift (SystemClock.elapsedRealtime never goes
        // backwards, but a future caller might pass a difference that
        // does); we should still render something sane instead of an
        // exception or a negative count.
        assertEquals("00:00", formatElapsed(-1_500))
    }

    @Test fun sub_second_rounds_down() {
        // 999 ms is still less than a full second; show 00:00.
        assertEquals("00:00", formatElapsed(999))
    }
}
