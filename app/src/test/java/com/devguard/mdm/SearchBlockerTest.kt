// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Search keyword list (SearchBlocker). Run: gradlew testDebugUnitTest */
class SearchBlockerTest {
    @Test fun normalizesKeywords() {
        assertEquals("bad word", SearchBlocker.normalizeKeyword("  Bad   WORD "))
        assertEquals("porn*", SearchBlocker.normalizeKeyword("Porn*"))
        assertNull(SearchBlocker.normalizeKeyword("a"))       // too short
        assertNull(SearchBlocker.normalizeKeyword("  *  "))
    }

    @Test fun cleansTextToLowercaseWords() {
        assertEquals("online casino", SearchBlocker.clean("online%20casino"))
        assertEquals("bad word", SearchBlocker.clean("Bad+Word!"))
        assertEquals("100 casino", SearchBlocker.clean("100% casino"))   // invalid %-escape: read as typed
        assertEquals("சூதாட்டம்", SearchBlocker.clean("சூதாட்டம்"))        // combining marks kept (Tamil)
    }
}
