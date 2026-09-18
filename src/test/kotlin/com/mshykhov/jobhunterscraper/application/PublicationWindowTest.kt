package com.mshykhov.jobhunterscraper.application

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PublicationWindowTest {
    private val window = PublicationWindow(Clock.fixed(NOW, ZoneOffset.UTC))

    @Test
    fun `accepts precise timestamps at or after boundary`() {
        assertTrue(window.accepts("2026-09-18T00:15:00Z", SINCE))
        assertTrue(window.accepts("2026-09-18T02:00:00+02:00", SINCE))
        assertTrue(window.accepts("2026-09-17T23:30:00Z", SINCE))
        assertFalse(window.accepts("2026-09-17T23:29:59.999Z", SINCE))
    }

    @Test
    fun `accepts date-only values whose UTC day intersects window`() {
        assertTrue(window.accepts("2026-09-17", SINCE))
        assertTrue(window.accepts("2026-09-18", SINCE))
        assertFalse(window.accepts("2026-09-16", SINCE))
        assertFalse(window.accepts("2026-09-19", SINCE))
    }

    @Test
    fun `keeps unknown values conservatively`() {
        assertTrue(window.accepts(null, SINCE))
        assertTrue(window.accepts("", SINCE))
        assertTrue(window.accepts("recently", SINCE))
        assertTrue(window.accepts("2026-09-17T23:00:00", SINCE))
    }

    @Test
    fun `only reports parseable precise timestamps as older`() {
        assertTrue(window.isPreciselyOlder("2026-09-17T23:29:59Z", SINCE))
        assertFalse(window.isPreciselyOlder("2026-09-17T23:30:00Z", SINCE))
        assertFalse(window.isPreciselyOlder("2026-09-17", SINCE))
        assertFalse(window.isPreciselyOlder("recently", SINCE))
        assertFalse(window.isPreciselyOlder("2020-01-01T00:00:00Z", null))
    }

    @Test
    fun `accepts every value when boundary is absent`() {
        assertTrue(window.accepts("2020-01-01T00:00:00Z", null))
        assertTrue(window.accepts("2020-01-01", null))
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-18T00:30:00Z")
        val SINCE: Instant = Instant.parse("2026-09-17T23:30:00Z")
    }
}
