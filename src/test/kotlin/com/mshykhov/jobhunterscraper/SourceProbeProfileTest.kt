package com.mshykhov.jobhunterscraper

import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.infrastructure.config.JobHunterProperties
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["scraper.enabled=true", "management.tracing.enabled=false"],
)
@ActiveProfiles("source-probe")
class SourceProbeProfileTest(
    @Autowired private val context: ApplicationContext,
    @Autowired private val properties: ScraperProperties,
    @Autowired private val jobHunterProperties: JobHunterProperties,
) {
    @Test
    fun `probe profile excludes the coordinator even when collection is enabled`() {
        assertFalse(context.containsBean("scrapeCoordinator"))
        assertEquals(1, properties.httpAttempts)
        assertEquals(Duration.ofSeconds(60), properties.timeout(JobSource.LINKEDIN))
        assertEquals(1, jobHunterProperties.attempts)
        assertEquals(Duration.ofSeconds(10), jobHunterProperties.timeout)
    }
}
