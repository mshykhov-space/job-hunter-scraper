package com.mshykhov.jobhunterscraper

import com.mshykhov.jobhunterscraper.infrastructure.api.AuthentikTokenProvider
import com.mshykhov.jobhunterscraper.infrastructure.config.JobHunterProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "scraper.enabled=false",
        "job-hunter.auth-enabled=true",
        "job-hunter.client-id=",
        "job-hunter.username=",
        "job-hunter.password=",
        "management.tracing.enabled=false",
    ],
)
class RuntimeApplicationTest(
    @Autowired private val context: ApplicationContext,
    @Autowired private val jobHunterProperties: JobHunterProperties,
) {
    @Test
    fun `disabled runtime boots without credentials`() {
        assertNotNull(context.getBean(AuthentikTokenProvider::class.java))
        assertEquals("http://localhost:8095", jobHunterProperties.apiUrl)
    }
}
