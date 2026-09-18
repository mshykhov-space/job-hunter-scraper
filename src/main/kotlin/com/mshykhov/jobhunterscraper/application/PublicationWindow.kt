package com.mshykhov.jobhunterscraper.application

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Component
class PublicationWindow(
    private val clock: Clock,
) {
    fun accepts(
        publishedAt: String?,
        since: Instant?,
    ): Boolean {
        if (since == null) return true
        val precise = parsePrecise(publishedAt)
        if (precise != null) return !precise.isBefore(since)
        val date = parseDate(publishedAt) ?: return true
        val firstDay = since.atZone(ZoneOffset.UTC).toLocalDate()
        val lastDay = clock.instant().atZone(ZoneOffset.UTC).toLocalDate()
        return !date.isBefore(firstDay) && !date.isAfter(lastDay)
    }

    fun isPreciselyOlder(
        publishedAt: String?,
        since: Instant?,
    ): Boolean = since != null && parsePrecise(publishedAt)?.isBefore(since) == true

    private fun parsePrecise(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }.getOrNull()
    }

    private fun parseDate(value: String?): LocalDate? {
        if (value.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
    }
}
