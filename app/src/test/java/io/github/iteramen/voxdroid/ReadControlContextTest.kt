package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadControlContextTest {
    @Test fun ownTryFieldRetainsOnlyDictation() {
        assertEquals(ReadControlContext.DictationOnly, readControlContext(true, true, false, false))
    }
    @Test fun ownSettingsAndCredentialsNeverOfferNarration() {
        assertEquals(ReadControlContext.Hidden, readControlContext(true, false, false, false))
    }
    @Test fun lockedAndPasswordContextsAlwaysHideControls() {
        for (own in listOf(false, true)) for (tryField in listOf(false, true)) {
            assertEquals(ReadControlContext.Hidden, readControlContext(own, tryField, true, false))
            assertEquals(ReadControlContext.Hidden, readControlContext(own, tryField, false, true))
        }
    }
    @Test fun externalAppsKeepTheirControls() {
        assertEquals(ReadControlContext.External, readControlContext(false, false, false, false))
    }
}
