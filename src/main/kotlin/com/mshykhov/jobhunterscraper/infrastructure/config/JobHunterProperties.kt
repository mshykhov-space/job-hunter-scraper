package com.mshykhov.jobhunterscraper.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("job-hunter")
data class JobHunterProperties(
    val apiUrl: String = "http://localhost:8081",
    val authEnabled: Boolean = true,
    val tokenUrl: String = "https://auth.gaynance.com/application/o/token/",
    val clientId: String = "",
    val username: String = "",
    val password: String = "",
    val scope: String = "profile job-hunter-api",
    val timeout: Duration = Duration.ofSeconds(15),
    val attempts: Int = 3,
    val retryDelay: Duration = Duration.ofSeconds(1),
    val maxRetryDelay: Duration = Duration.ofMinutes(2),
    val maxResponseBytes: Int = 4 * 1024 * 1024,
) {
    init {
        require(attempts > 0) { "job-hunter.attempts must be positive" }
        require(maxResponseBytes > 0) { "job-hunter.max-response-bytes must be positive" }
    }
}
