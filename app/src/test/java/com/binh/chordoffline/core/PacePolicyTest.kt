package com.binh.chordoffline.core

import org.junit.Assert.*
import org.junit.Test

class PacePolicyTest {
    @Test fun neverBelowOneSecondNorAboveMaxOption() {
        assertEquals(1_000L, PacePolicy.clamp(0))
        assertEquals(1_000L, PacePolicy.clamp(200))
        assertEquals(3_000L, PacePolicy.clamp(60_000))
        assertEquals(1_500L, PacePolicy.clamp(1_500))
        assertTrue(PacePolicy.OPTIONS.all { it >= PacePolicy.MIN })
    }

    @Test fun slowServerMeansLongerWait() {
        assertEquals(2_000L, PacePolicy.effectiveDelay(2_000, 450))      // trả lời nhanh -> theo cài đặt
        assertEquals(4_200L, PacePolicy.effectiveDelay(1_000, 4_200))    // chậm -> nghỉ bằng thời gian trả lời
        assertEquals(10_000L, PacePolicy.effectiveDelay(1_000, 60_000))  // tối đa 10 giây
    }

    @Test fun etaAndLabels() {
        assertEquals(15L, PacePolicy.etaMinutes(341, 2_000))   // 341 × 2.6 s ≈ 14.8 phút
        assertEquals(21L, PacePolicy.etaMinutes(341, 3_000))
        assertEquals(0L, PacePolicy.etaMinutes(0, 2_000))
        assertEquals("2 giây", PacePolicy.label(2_000))
        assertEquals("1,5 giây", PacePolicy.label(1_500))
    }
}
