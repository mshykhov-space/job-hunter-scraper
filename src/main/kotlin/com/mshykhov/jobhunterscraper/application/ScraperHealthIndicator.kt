package com.mshykhov.jobhunterscraper.application

import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

@Component
class ScraperHealthIndicator(
    private val properties: ScraperProperties,
    private val availability: ScraperAvailability,
) : HealthIndicator {
    override fun health(): Health {
        if (!properties.enabled) return Health.up().withDetail("enabled", false).build()
        val unavailable = availability.unavailableSources()
        return if (unavailable.isEmpty()) {
            Health
                .up()
                .withDetail("enabled", true)
                .withDetail("sources", properties.sources.size)
                .build()
        } else {
            Health
                .outOfService()
                .withDetail("enabled", true)
                .withDetail("unavailableSources", unavailable.map { it.id }.sorted())
                .build()
        }
    }
}
