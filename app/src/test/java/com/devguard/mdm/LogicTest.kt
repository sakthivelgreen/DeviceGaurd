// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Unit tests for pure logic (A-04). Run: gradlew testDebugUnitTest */
class LogicTest {

    // ---------- ScreenTime.isActive ----------

    /** A Calendar on a given weekday (Calendar.MONDAY …) at hh:mm. */
    private fun at(weekday: Int, hh: Int, mm: Int): Calendar = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 23, hh, mm, 0) // 23 Nov 2026 is a Monday
        add(Calendar.DAY_OF_YEAR, (weekday - Calendar.MONDAY + 7) % 7)
    }

    private fun schedule(days: Int, start: Int, end: Int, enabled: Boolean = true) =
        ScreenTime.Schedule("id", "Test", days, start, end, emptySet(), emptySet(), enabled)

    private val MON = 1
    private val TUE = 2
    private val ALL = 127

    @Test fun dayScheduleIsActiveInsideItsWindow() {
        val s = schedule(MON, 9 * 60, 17 * 60)
        assertTrue(ScreenTime.isActive(s, at(Calendar.MONDAY, 9, 0)))
        assertTrue(ScreenTime.isActive(s, at(Calendar.MONDAY, 16, 59)))
        assertFalse(ScreenTime.isActive(s, at(Calendar.MONDAY, 17, 0))) // end is exclusive
        assertFalse(ScreenTime.isActive(s, at(Calendar.MONDAY, 8, 59)))
        assertFalse(ScreenTime.isActive(s, at(Calendar.TUESDAY, 10, 0))) // other day
    }

    @Test fun overnightScheduleBelongsToTheDayItStarts() {
        val s = schedule(MON, 22 * 60, 6 * 60) // Monday 22:00 → Tuesday 06:00
        assertTrue(ScreenTime.isActive(s, at(Calendar.MONDAY, 23, 0)))
        assertTrue(ScreenTime.isActive(s, at(Calendar.TUESDAY, 5, 59)))
        assertFalse(ScreenTime.isActive(s, at(Calendar.TUESDAY, 6, 0)))
        assertFalse(ScreenTime.isActive(s, at(Calendar.TUESDAY, 23, 0))) // Tuesday isn't selected
        assertFalse(ScreenTime.isActive(s, at(Calendar.MONDAY, 5, 0)))   // Sunday night isn't selected
    }

    @Test fun sundayIsBitSix() {
        val s = schedule(1 shl 6, 0, 60)
        assertTrue(ScreenTime.isActive(s, at(Calendar.SUNDAY, 0, 30)))
        assertFalse(ScreenTime.isActive(s, at(Calendar.SATURDAY, 0, 30)))
    }

    @Test fun disabledEmptyOrZeroLengthSchedulesNeverApply() {
        assertFalse(ScreenTime.isActive(schedule(ALL, 0, 23 * 60, enabled = false), at(Calendar.MONDAY, 12, 0)))
        assertFalse(ScreenTime.isActive(schedule(0, 0, 23 * 60), at(Calendar.MONDAY, 12, 0)))
        assertFalse(ScreenTime.isActive(schedule(ALL, 600, 600), at(Calendar.MONDAY, 10, 0)))
        assertTrue(ScreenTime.isActive(schedule(MON or TUE, 0, 23 * 60), at(Calendar.TUESDAY, 12, 0)))
    }

    // ---------- Totp (RFC 6238 test vectors, SHA-1, last 6 of the 8 digits) ----------

    private val rfcSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" // base32 of "12345678901234567890"

    @Test fun totpMatchesRfcVectors() {
        assertEquals(1L, Totp.matchStep(rfcSecret, "287082", 59_000L))                        // 94287082
        assertEquals(1111111109L / 30, Totp.matchStep(rfcSecret, "081804", 1111111109_000L))  // 07081804
        assertEquals(1234567890L / 30, Totp.matchStep(rfcSecret, "005924", 1234567890_000L))  // 89005924
    }

    @Test fun totpAcceptsOneStepOfClockDriftOnly() {
        val t = 1111111109_000L
        assertNotNull(Totp.matchStep(rfcSecret, "081804", t + 30_000L)) // phone 30 s ahead
        assertNotNull(Totp.matchStep(rfcSecret, "081804", t - 30_000L)) // phone 30 s behind
        assertNull(Totp.matchStep(rfcSecret, "081804", t + 90_000L))    // 3 steps away
    }

    @Test fun totpRejectsMalformedCodes() {
        assertNull(Totp.matchStep(rfcSecret, "28708", 59_000L))
        assertNull(Totp.matchStep(rfcSecret, "abcdef", 59_000L))
        assertEquals(1L, Totp.matchStep(rfcSecret, "287 082", 59_000L)) // spaces are ignored
    }

    @Test fun newTotpSecretIsBase32() {
        val s = Totp.newSecret()
        assertEquals(32, s.length) // 20 random bytes
        assertTrue(s.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" })
    }

    // ---------- Blocklist.parse ----------

    @Test fun blocklistParsesPackageNamesAndPlayLinks() {
        assertEquals("com.example.app", Blocklist.parse("com.example.app"))
        assertEquals("com.example.app", Blocklist.parse("  com.example.app  "))
        assertEquals("com.zhiliaoapp.musically", Blocklist.parse("https://play.google.com/store/apps/details?id=com.zhiliaoapp.musically&hl=en"))
        assertEquals("com.whatsapp", Blocklist.parse("https://play.google.com/store/apps/details?hl=en&id=com.whatsapp"))
    }

    @Test fun blocklistRejectsNonPackages() {
        assertNull(Blocklist.parse("whatsapp"))
        assertNull(Blocklist.parse("https://example.com/page"))
        assertNull(Blocklist.parse("1com.bad.start"))
        assertNull(Blocklist.parse(""))
    }

    // ---------- WebFilter.normalize ----------

    @Test fun normalizeKeepsOnlyTheDomain() {
        assertEquals("example.com", WebFilter.normalize("https://www.Example.com/path?q=1"))
        assertEquals("example.com", WebFilter.normalize("http://example.com:8080"))
        assertEquals("m.youtube.com", WebFilter.normalize("m.youtube.com."))
        assertEquals("sub.domain.co.in", WebFilter.normalize("  SUB.domain.co.in  "))
    }

    @Test fun normalizeRejectsNonDomains() {
        assertNull(WebFilter.normalize("localhost"))
        assertNull(WebFilter.normalize("not a site"))
        assertNull(WebFilter.normalize(""))
    }

    // ---------- Prefs.pinProblem ----------

    @Test fun pinRules() {
        assertNull(Prefs.pinProblem("482915", "482915"))
        assertNotNull(Prefs.pinProblem("4829", "4829"))           // too short
        assertNotNull(Prefs.pinProblem("4829153748291", "4829153748291")) // 13 digits
        assertNotNull(Prefs.pinProblem("48291a", "48291a"))       // not digits
        assertNotNull(Prefs.pinProblem("777777", "777777"))       // all the same
        assertNotNull(Prefs.pinProblem("123456", "123456"))       // in a row
        assertNotNull(Prefs.pinProblem("876543", "876543"))       // in a row, down
        assertNotNull(Prefs.pinProblem("482915", "482916"))       // don't match
    }
}
