package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.state.AutofillTrustStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rule that decides whether the "Always autofill" button is even shown. Every clause is a
 * separate reason to withhold it, so each gets its own case: withholding costs a tap, granting it
 * wrongly means a password typed into a page with nobody watching.
 */
class AutofillTrustTest {

    private fun offers(
        fillCount: Int = AutofillTrustStore.AlwaysAutofillThreshold,
        alreadyAlways: Boolean = false,
        isEnterpriseIdp: Boolean = true,
        isOnlyMatch: Boolean = true,
    ) = AutofillTrustStore.offersAlwaysAutofill(
        fillCount = fillCount,
        alreadyAlways = alreadyAlways,
        isEnterpriseIdp = isEnterpriseIdp,
        isOnlyMatch = isOnlyMatch,
    )

    @Test fun allConditionsMet_offers() = assertTrue(offers())

    @Test fun belowThreshold_withheld() =
        assertFalse(offers(fillCount = AutofillTrustStore.AlwaysAutofillThreshold - 1))

    @Test fun neverFilled_withheld() = assertFalse(offers(fillCount = 0))

    @Test fun wellAboveThreshold_offers() = assertTrue(offers(fillCount = 99))

    @Test fun alreadyOptedIn_notOfferedAgain() =
        assertFalse(offers(fillCount = 99, alreadyAlways = true))

    /** An ordinary website is out of scope no matter how often the user signs in there. */
    @Test fun notAnIdp_withheldEvenWhenWellUsed() =
        assertFalse(offers(fillCount = 99, isEnterpriseIdp = false))

    /** More than one saved credential means there is a choice to get wrong. */
    @Test fun multipleMatches_withheld() = assertFalse(offers(isOnlyMatch = false))

    @Test fun thresholdIsMoreThanOneUse() =
        assertTrue(AutofillTrustStore.AlwaysAutofillThreshold >= 2)
}
