package dev.glasswidget

import org.junit.Assert.assertEquals
import org.junit.Test

class StepDeltaTest {
    @Test fun addsOnlyNewSteps() {
        assertEquals(45L, dailySteps(true, true, 40, 1200, 1205))
    }

    @Test fun newDayDoesNotIncludeYesterday() {
        assertEquals(0L, dailySteps(false, true, 40, 1200, 1210))
    }

    @Test fun rebootRetainsDailyTotalWithoutCountingNewBaseline() {
        assertEquals(40L, dailySteps(true, false, 40, 1200, 9))
    }

    @Test fun sensorResetDoesNotSubtractSteps() {
        assertEquals(40L, dailySteps(true, true, 40, 1200, 9))
    }
}
