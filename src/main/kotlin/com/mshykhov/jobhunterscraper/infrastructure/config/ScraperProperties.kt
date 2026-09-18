package com.mshykhov.jobhunterscraper.infrastructure.config

import com.mshykhov.jobhunterscraper.application.model.JobSource
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("scraper")
data class ScraperProperties(
    val enabled: Boolean = false,
    val sources: Set<JobSource> = JobSource.entries.toSet(),
    val endpoints: Map<String, String> = emptyMap(),
    val workerId: String = System.getenv("HOSTNAME") ?: "local",
    val pollInterval: Duration = Duration.ofSeconds(15),
    val maxParallelSources: Int = 8,
    val maxPages: Int = 1000,
    val heartbeatInterval: Duration = Duration.ofSeconds(30),
    val httpTimeout: Duration = Duration.ofSeconds(30),
    val httpAttempts: Int = 3,
    val retryDelay: Duration = Duration.ofSeconds(1),
    val maxRetryDelay: Duration = Duration.ofMinutes(2),
    val maxResponseBytes: Int = 16 * 1024 * 1024,
) {
    init {
        require(
            maxParallelSources in 1..JobSource.entries.size,
        ) { "scraper.max-parallel-sources must be between 1 and ${JobSource.entries.size}" }
        require(maxPages > 0) { "scraper.max-pages must be positive" }
        require(httpAttempts > 0) { "scraper.http-attempts must be positive" }
        require(maxResponseBytes > 0) { "scraper.max-response-bytes must be positive" }
        require(!heartbeatInterval.isNegative && !heartbeatInterval.isZero) { "scraper.heartbeat-interval must be positive" }
        require(!pollInterval.isNegative && !pollInterval.isZero) { "scraper.poll-interval must be positive" }
    }

    fun endpoint(
        source: JobSource,
        default: String,
    ): String = endpoints[source.id] ?: default
}
