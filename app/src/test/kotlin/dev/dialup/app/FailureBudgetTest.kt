// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FailureBudgetTest {
    @Test
    fun theFirstFourFailuresRetryAndTheFifthGivesUp() {
        val budget = FailureBudget(limit = 5)
        repeat(4) { assertEquals(FailureStep.RETRY, budget.fail(), "failure ${it + 1}") }
        assertEquals(FailureStep.GIVE_UP, budget.fail())
        assertEquals(5, budget.attempts)
    }

    @Test
    fun theDefaultLimitIsTheBridgeLimit() {
        val budget = FailureBudget()
        repeat(BRIDGE_FAILURE_LIMIT - 1) { assertEquals(FailureStep.RETRY, budget.fail()) }
        assertEquals(FailureStep.GIVE_UP, budget.fail())
    }

    @Test
    fun afterGivingUpFurtherFailuresAreQuiet() {
        val budget = FailureBudget(limit = 5)
        repeat(5) { budget.fail() }
        assertEquals(FailureStep.ALREADY_GAVE_UP, budget.fail())
        assertEquals(FailureStep.ALREADY_GAVE_UP, budget.fail())
        assertEquals(5, budget.attempts, "a quiet failure is not counted")
    }

    @Test
    fun aResetStartsTheCountAgain() {
        val budget = FailureBudget(limit = 5)
        repeat(3) { budget.fail() }
        budget.reset()
        assertEquals(0, budget.attempts)
        repeat(4) { assertEquals(FailureStep.RETRY, budget.fail()) }
        assertEquals(FailureStep.GIVE_UP, budget.fail())
    }

    @Test
    fun aResetAfterGivingUpAllowsANewRun() {
        val budget = FailureBudget(limit = 2)
        assertEquals(FailureStep.RETRY, budget.fail())
        assertEquals(FailureStep.GIVE_UP, budget.fail())
        budget.reset()
        assertEquals(FailureStep.RETRY, budget.fail())
        assertEquals(FailureStep.GIVE_UP, budget.fail())
    }

    @Test
    fun aLimitOfOneGivesUpAtOnce() {
        val budget = FailureBudget(limit = 1)
        assertEquals(FailureStep.GIVE_UP, budget.fail())
        assertEquals(FailureStep.ALREADY_GAVE_UP, budget.fail())
    }

    @Test
    fun aLimitBelowOneIsRefused() {
        assertFailsWith<IllegalArgumentException> { FailureBudget(limit = 0) }
        assertFailsWith<IllegalArgumentException> { FailureBudget(limit = -3) }
    }
}
