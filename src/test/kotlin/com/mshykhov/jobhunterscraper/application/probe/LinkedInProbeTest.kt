package com.mshykhov.jobhunterscraper.application.probe

import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.application.model.ScrapedJob
import com.mshykhov.jobhunterscraper.infrastructure.http.OutboundHttpException
import com.mshykhov.jobhunterscraper.infrastructure.source.LinkedInAdapter
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LinkedInProbeTest {
    @Test
    fun `validates all arguments before starting a probe`() {
        assertFailsWith<IllegalArgumentException> {
            LinkedInProbeRequest.parse(
                arrayOf("--probe-linkedin", "--probe.category=Support", "--probe.location=Worldwide", "--probe.limit=11"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LinkedInProbeRequest.parse(
                arrayOf("--probe-linkedin", "--probe.category=Support", "--probe.location=Worldwide", "--job-hunter.password=secret"),
            )
        }
        val request = LinkedInProbeRequest.parse(arrayOf("--probe-linkedin", "--probe.category=Support", "--probe.location=Worldwide"))
        assertEquals(true, request.remoteOnly)
        assertEquals(5, request.limit)
        assertEquals(24, request.hoursOld)
    }

    @Test
    fun `rate limit output never includes upstream messages or credentials`() {
        val adapter = mockk<LinkedInAdapter>()
        val request = LinkedInProbeRequest("Support", "Worldwide", true, 5, 24)
        every { adapter.probeSearch(any(), any(), any(), any(), any()) } throws
            OutboundHttpException(HttpStatus.TOO_MANY_REQUESTS, target = "http://user:secret@proxy.test/jobs/search")

        val result = LinkedInProbeRunner(adapter).run(request)

        assertEquals("RATE_LIMITED", result.outcome)
        assertEquals(429, result.httpStatus)
        assertEquals(0, result.returnedCount)
        assertFalse(result.toString().contains("secret"))
        assertFalse(result.toString().contains("proxy.test"))
    }

    @Test
    fun `upstream server error includes only its numeric HTTP status`() {
        val adapter = mockk<LinkedInAdapter>()
        val request = LinkedInProbeRequest("Support", "Worldwide", true, 5, 24)
        every { adapter.probeSearch(any(), any(), any(), any(), any()) } throws
            OutboundHttpException(HttpStatus.BAD_GATEWAY, target = "http://user:secret@proxy.test/jobs/search")

        val result = LinkedInProbeRunner(adapter).run(request)

        assertEquals("UPSTREAM_ERROR", result.outcome)
        assertEquals(502, result.httpStatus)
        assertFalse(result.toString().contains("secret"))
    }

    @Test
    fun `sample omits description raw data and URL query`() {
        val adapter = mockk<LinkedInAdapter>()
        val request = LinkedInProbeRequest("Support", "Worldwide", true, 5, 24)
        every { adapter.probeSearch(any(), any(), any(), any(), any()) } returns
            listOf(
                ScrapedJob(
                    title = "Support Agent",
                    url = "https://www.linkedin.com/jobs/view/101?tracking=secret",
                    description = "Private description",
                    source = JobSource.LINKEDIN,
                    rawData = mapOf("secret" to "credential"),
                    category = "Support",
                ),
            )

        val result = LinkedInProbeRunner(adapter).run(request)

        assertEquals("SAMPLE_RETURNED", result.outcome)
        assertEquals("https://www.linkedin.com/jobs/view/101", result.samples.single().url)
        assertFalse(result.toString().contains("Private description"))
        assertFalse(result.toString().contains("credential"))
    }
}
