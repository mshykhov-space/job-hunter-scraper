package com.mshykhov.jobhunterscraper.infrastructure.source

import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.application.model.SourceProxy
import com.mshykhov.jobhunterscraper.infrastructure.api.JobHunterClient
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import com.mshykhov.jobhunterscraper.infrastructure.http.SourceHttpClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LinkedInSearchProbeTest {
    private val httpClient = mockk<SourceHttpClient>()
    private val client = mockk<JobHunterClient>()
    private val adapter = LinkedInAdapter(httpClient, ObjectMapper(), client, ScraperProperties(), mockk())

    @Test
    fun `search probe sends literal Worldwide query and only reads one source proxy`() {
        every { client.probeProxy(JobSource.LINKEDIN) } returns
            SourceProxy("http://user:secret@proxy.test:8080", "proxy.test", 8080, "user", "secret")
        every { httpClient.post(any(), any(), any(), any()) } returns
            """[{"title":"Support Agent","job_url":"https://www.linkedin.com/jobs/view/101","company":"Example","location":"Worldwide","is_remote":true}]"""

        val jobs = adapter.probeSearch("operations assistant", "Worldwide", remoteOnly = true, limit = 3, hoursOld = 24)

        assertEquals(1, jobs.size)
        assertEquals("Support Agent", jobs.single().title)
        verify(exactly = 1) { client.probeProxy(JobSource.LINKEDIN) }
        verify(exactly = 1) {
            httpClient.post(
                match { it.endsWith("/jobs/search") },
                match<Map<String, Any>> {
                    it["search_term"] == "operations assistant" &&
                        it["location"] == "Worldwide" &&
                        it["is_remote"] == true &&
                        it["results_wanted"] == 3 &&
                        it["hours_old"] == 24 &&
                        it["offset"] == 0
                },
                any(),
                any(),
            )
        }
        verify(exactly = 0) { client.checkJobs(any()) }
        verify(exactly = 0) { client.claim(any()) }
    }
}
