package dev.appolon.memoswear.core

import dev.appolon.memoswear.core.time.TimeFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TimeFormatTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val locale = Locale.US
    private val now = Instant.parse("2026-11-27T14:30:00Z") // 15:30 in Paris

    private fun label(text: String, use24h: Boolean = true) =
        TimeFormat.relative(Instant.parse(text), now, zone, locale, use24h)

    @Test
    fun `the last hour reads as minutes ago`() {
        assertEquals("Now", label("2026-11-27T14:30:00Z"))
        assertEquals("Now", label("2026-11-27T14:29:30Z"))
        assertEquals("5m ago", label("2026-11-27T14:25:00Z"))
        assertEquals("59m ago", label("2026-11-27T13:31:00Z"))
    }

    @Test
    fun `earlier today reads as a clock time`() {
        assertEquals("09:05", label("2026-11-27T08:05:00Z"))
        assertEquals("9:05 AM", label("2026-11-27T08:05:00Z", use24h = false))
    }

    @Test
    fun `older notes read as dates that keep getting more precise`() {
        assertEquals("Yesterday", label("2026-11-26T14:00:00Z"))
        assertEquals("3 Nov", label("2026-11-03T10:00:00Z"))
        assertEquals("Dec 25, 2025", label("2025-12-25T10:00:00Z"))
    }

    @Test
    fun `midnight in Paris decides which day a note belongs to`() {
        // 23:30 UTC on the 26th is already the 27th in Paris, so it is earlier today, not yesterday.
        assertEquals("00:30", label("2026-11-26T23:30:00Z"))
    }

    @Test
    fun `full stamps name the date and the time`() {
        assertEquals("Nov 3, 2026, 09:05", TimeFormat.full(Instant.parse("2026-11-03T08:05:00Z"), zone, locale))
        assertEquals("Nov 3, 2026, 9:05 AM", TimeFormat.full(Instant.parse("2026-11-03T08:05:00Z"), zone, locale, use24h = false))
    }
}

class UserMessagesTest {
    @Test
    fun `api errors become one plain line`() {
        fun error(code: String, status: Int) = dev.appolon.memoswear.data.api.ApiException(code, "server words", status)
        assertEquals("Signed out", UserMessages.forApi(error("unauthenticated", 401)))
        assertEquals("Signed out", UserMessages.forApi(error("16", 401)))
        assertEquals("Not allowed", UserMessages.forApi(error("permission_denied", 403)))
        assertEquals("Not found", UserMessages.forApi(error("5", 404)))
        assertEquals("Too many requests, try again later", UserMessages.forApi(error("8", 429)))
        assertEquals("Server error (502)", UserMessages.forApi(error("http_502", 502)))
    }

    @Test
    fun `an invalid argument shows the server's own explanation`() {
        val error = dev.appolon.memoswear.data.api.ApiException("invalid_argument", "unmatched username and password", 400)
        assertEquals("Unmatched username and password", UserMessages.forApi(error))
    }

    @Test
    fun `connection failures are offline and everything else is not`() {
        org.junit.Assert.assertTrue(java.net.UnknownHostException("x").let(UserMessages::isOffline))
        assertFalse(UserMessages.isOffline(dev.appolon.memoswear.data.api.ApiException("x", "y", 500)))
        assertFalse(UserMessages.isOffline(IllegalStateException()))
        assertEquals("Can't reach the server", UserMessages.forThrowable(java.net.UnknownHostException("host")))
        assertEquals("The server took too long to answer", UserMessages.forThrowable(java.net.SocketTimeoutException("x")))
    }
}
