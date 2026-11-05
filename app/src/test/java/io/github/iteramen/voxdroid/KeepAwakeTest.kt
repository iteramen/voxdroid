package io.github.iteramen.voxdroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAwakeTest {

    @Test
    fun aTakeInFlightKeepsTheScreenOn() {
        for (state in listOf(TakeState.Recording, TakeState.Working(Stage.Stt), TakeState.Working(Stage.Polish))) {
            assertTrue("$state", keepsScreenOn(state))
        }
    }

    @Test
    fun anErrorOrIdleLetsTheScreenTimeOut() {
        val errors = listOf(TakeState.Error("stt: timeout", canRetry = true), TakeState.Error("mic: unavailable", canRetry = false))
        for (state in errors + TakeState.Idle) {
            assertFalse("$state", keepsScreenOn(state))
        }
    }
}
