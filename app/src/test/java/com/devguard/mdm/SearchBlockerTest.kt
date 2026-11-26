// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Search keyword matching (SearchBlocker). Run: gradlew testDebugUnitTest */
class SearchBlockerTest {
    private fun kws(vararg k: String) = k.mapNotNull { SearchBlocker.normalizeKeyword(it) }

    @Test fun normalizesKeywords() {
        assertEquals("bad word", SearchBlocker.normalizeKeyword("  Bad   WORD "))
        assertEquals("porn*", SearchBlocker.normalizeKeyword("Porn*"))
        assertNull(SearchBlocker.normalizeKeyword("a"))       // too short
        assertNull(SearchBlocker.normalizeKeyword("  *  "))
    }

    @Test fun matchesWholeWordsAnywhereInASearch() {
        val k = kws("casino")
        assertEquals("casino", SearchBlocker.match("best casino near me", k))
        assertEquals("casino", SearchBlocker.match("CASINO", k))
        assertNull(SearchBlocker.match("casinos", k))           // whole word only
        assertNull(SearchBlocker.match("occasional", k))
    }

    @Test fun readsSearchUrlsAndOmniboxText() {
        val k = kws("bad word", "casino")
        assertEquals("bad word", SearchBlocker.match("https://www.google.com/search?q=some+bad+word+here&hl=en", k))
        assertEquals("casino", SearchBlocker.match("bing.com/search?q=online%20casino", k))
        assertEquals("casino", SearchBlocker.match("duckduckgo.com/?q=Casino!", k))
        assertNull(SearchBlocker.match("google.com/search?q=badword", k))   // phrase needs its words
    }

    @Test fun starMatchesWordStarts() {
        val k = kws("porn*")
        assertEquals("porn*", SearchBlocker.match("pornography facts", k))
        assertEquals("porn*", SearchBlocker.match("free porn", k))
        assertNull(SearchBlocker.match("sporn", k))
    }

    @Test fun ignoresBrokenEscapesAndOtherScripts() {
        assertEquals("casino", SearchBlocker.match("100% casino", kws("casino")))   // invalid %-escape: read as typed
        assertEquals("சூதாட்டம்", SearchBlocker.match("சூதாட்டம் விளையாட்டு", kws("சூதாட்டம்")))  // Tamil vowel signs kept
    }

    @Test fun whileTypingTheUnfinishedLastWordIsIgnored() {
        val k = kws("ass", "porn*")
        assertNull(SearchBlocker.match("ass", k, editing = true))               // may become "assignment"
        assertNull(SearchBlocker.match("homework ass", k, editing = true))
        assertEquals("ass", SearchBlocker.match("ass ", k, editing = true))     // word finished by a space
        assertEquals("ass", SearchBlocker.match("ass?", k, editing = true))     // ... or punctuation
        assertEquals("ass", SearchBlocker.match("ass help", k, editing = true)) // an earlier word is finished
        assertNull(SearchBlocker.match("assignment help", k, editing = true))
        assertEquals("porn*", SearchBlocker.match("porn", k, editing = true))   // * keywords match while typing
        assertEquals("ass", SearchBlocker.match("ass", k))                      // not editing: submitted text
    }

    @Test fun nothingToMatch() {
        assertNull(SearchBlocker.match("anything", emptyList()))
        assertNull(SearchBlocker.match("", kws("casino")))
    }
}
