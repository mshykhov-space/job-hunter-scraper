package com.mshykhov.jobhunterscraper.application

import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class ScraperAvailability(
    private val properties: ScraperProperties,
    private val clock: Clock,
) {
    private val states = ConcurrentHashMap<JobSource, SourceState>()

    fun controlPlaneSucceeded(source: JobSource) {
        states[source] = SourceState(clock.instant(), null)
    }

    fun controlPlaneFailed(source: JobSource) {
        states.compute(source) { _, current -> SourceState(current?.lastSuccess, clock.instant()) }
    }

    fun unavailableSources(now: Instant = clock.instant()): Set<JobSource> {
        if (!properties.enabled) return emptySet()
        val staleAfter = maxOf(properties.pollInterval.multipliedBy(3), Duration.ofMinutes(1))
        return properties.sources.filterTo(mutableSetOf()) { source ->
            val state = states[source]
            val lastSuccess = state?.lastSuccess
            lastSuccess == null ||
                Duration.between(lastSuccess, now) > staleAfter ||
                state.lastFailure?.isAfter(lastSuccess) == true
        }
    }

    private data class SourceState(
        val lastSuccess: Instant?,
        val lastFailure: Instant?,
    )
}
