package com.mshykhov.jobhunterscraper.infrastructure.http

import org.springframework.http.HttpHeaders
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.pow

internal object HttpRetrySupport {
    fun retryDelay(
        headers: HttpHeaders,
        attempt: Int,
        initial: Duration,
        maximum: Duration,
        clock: Clock = Clock.systemUTC(),
    ): Duration {
        val retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER)?.let { parseRetryAfter(it, clock) }
        val exponential = initial.multipliedBy(2.0.pow((attempt - 1).toDouble()).toLong().coerceAtLeast(1))
        return minOf(retryAfter ?: exponential, maximum).coerceAtLeast(Duration.ZERO)
    }

    private fun parseRetryAfter(
        value: String,
        clock: Clock,
    ): Duration? =
        value.toLongOrNull()?.let { Duration.ofSeconds(it.coerceAtLeast(0)) }
            ?: runCatching {
                Duration.between(clock.instant(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
            }.getOrNull()
}
