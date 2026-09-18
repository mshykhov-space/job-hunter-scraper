package com.mshykhov.jobhunterscraper

import com.mshykhov.jobhunterscraper.infrastructure.api.AuthentikTokenProvider
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
) {
    @Test
    fun `disabled runtime boots without credentials`() {
        assertNotNull(context.getBean(AuthentikTokenProvider::class.java))
    }
}
