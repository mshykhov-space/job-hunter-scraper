package com.mshykhov.jobhunterscraper.infrastructure.source

import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunterscraper.application.SourceSchemaException
import com.mshykhov.jobhunterscraper.application.model.JobCheckRequest
import com.mshykhov.jobhunterscraper.application.model.JobCheckResult
import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.application.model.ScrapeContext
import com.mshykhov.jobhunterscraper.application.model.SearchCriteria
import com.mshykhov.jobhunterscraper.application.model.SourceProxy
import com.mshykhov.jobhunterscraper.infrastructure.api.JobHunterClient
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import com.mshykhov.jobhunterscraper.infrastructure.http.SourceHttpClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkedInAdapterTest {
    private val httpClient = mockk<SourceHttpClient>()
    private val jobHunterClient = mockk<JobHunterClient>()
    private val clock = Clock.fixed(Instant.parse("2026-09-18T15:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `checks discovery results and enriches selected jobs without credentials in query`() {
        val searchUrl = slot<String>()
        val enrichBody = slot<Any>()
        every { httpClient.get(capture(searchUrl), any(), any()) } returns fixture("fixtures/linkedin/search.json")
        every { httpClient.post(any(), capture(enrichBody), any(), any()) } returns fixture("fixtures/linkedin/enrich.json")
        every { jobHunterClient.checkJobs(any()) } returns
            JobCheckResult(
                newUrls = listOf("https://www.linkedin.com/jobs/view/101", "https://www.linkedin.com/jobs/view/102"),
                updatedUrls = emptyList(),
                unchangedUrls = emptyList(),
            )
        every { jobHunterClient.proxies(JobSource.LINKEDIN) } returns
            listOf(
                SourceProxy("http://user:secret@proxy.test:8080", "proxy.test", 8080, "user", "secret"),
                SourceProxy("http://user:secret@proxy2.test:8080", "proxy2.test", 8080, "user", "secret"),
            )
        val adapter = LinkedInAdapter(httpClient, ObjectMapper(), jobHunterClient, ScraperProperties(), clock)

        val page =
            adapter.fetch(
                ScrapeContext(
                    SearchCriteria(categories = listOf("Kotlin", "Java"), locations = listOf("Europe", "Ukraine"), remoteOnly = true),
                    since = Instant.parse("2026-09-16T14:59:59Z"),
                ),
            )

        assertFalse(page.complete)
        assertEquals(mapOf("categoryIndex" to "0", "locationIndex" to "1"), page.checkpoint)
        assertEquals(2, page.fetchedCount)
        assertEquals(2, page.jobs.size)
        assertEquals("Kotlin", page.jobs[0].category)
        assertEquals("Full description", page.jobs[0].description)
        assertEquals("Acme Inc.", page.jobs[0].company)
        assertEquals(true, page.jobs[0].remote)
        assertEquals(null, page.jobs[1].remote)
        assertEquals("80000-100000", page.jobs[1].salary)
        assertEquals("no_data", page.jobs[1].rawData["enrichmentStatus"])
        assertFalse(searchUrl.captured.contains("secret"))
        assertFalse(searchUrl.captured.contains("proxies="))
        assertTrue(searchUrl.captured.contains("hours_old=49"))
        assertTrue(enrichBody.captured.toString().contains("user:secret@proxy.test"))
        verify { jobHunterClient.checkJobs(match { it.size == 2 }) }
    }

    @Test
    fun `keeps unknown remote null and completes the last query`() {
        every { httpClient.get(any(), any(), any()) } returns fixture("fixtures/linkedin/search.json")
        every { httpClient.post(any(), any(), any(), any()) } returns
            """
            {
              "results": [
                {
                  "url": "https://www.linkedin.com/jobs/view/102",
                  "status": "success",
                  "data": {"description": "Backend role"}
                }
              ]
            }
            """.trimIndent()
        every { jobHunterClient.checkJobs(any()) } returns
            JobCheckResult(
                newUrls = listOf("https://www.linkedin.com/jobs/view/102"),
                updatedUrls = emptyList(),
                unchangedUrls = listOf("https://www.linkedin.com/jobs/view/101"),
            )
        every { jobHunterClient.proxies(JobSource.LINKEDIN) } returns
            listOf(SourceProxy("http://user:secret@proxy.test:8080", "proxy.test", 8080, "user", "secret"))
        val adapter = LinkedInAdapter(httpClient, ObjectMapper(), jobHunterClient, ScraperProperties(), clock)

        val page = adapter.fetch(ScrapeContext(SearchCriteria(listOf("Kotlin"))))

        assertTrue(page.complete)
        assertEquals(1, page.jobs.size)
        assertEquals(null, page.jobs.single().remote)
        assertEquals(2, page.fetchedCount)
    }

    @Test
    fun `chunks enrichment by proxy count and checkpoints processed URLs`() {
        every { httpClient.get(any(), any(), any()) } returns fixture("fixtures/linkedin/search.json")
        every { httpClient.post(any(), any(), any(), any()) } returnsMany
            listOf(
                enrichmentResponse("101"),
                enrichmentResponse("102"),
            )
        every { jobHunterClient.checkJobs(any()) } answers {
            JobCheckResult(
                newUrls = arg<List<JobCheckRequest>>(0).map { it.url },
                updatedUrls = emptyList(),
                unchangedUrls = emptyList(),
            )
        }
        every { jobHunterClient.proxies(JobSource.LINKEDIN) } returns
            listOf(SourceProxy("http://user:secret@proxy.test:8080", "proxy.test", 8080, "user", "secret"))
        val adapter = LinkedInAdapter(httpClient, ObjectMapper(), jobHunterClient, ScraperProperties(), clock)

        val page = adapter.fetch(ScrapeContext(SearchCriteria(listOf("Kotlin"))))

        assertFalse(page.complete)
        assertEquals("0", page.checkpoint["categoryIndex"])
        assertEquals("0", page.checkpoint["locationIndex"])
        assertEquals(32, page.checkpoint.getValue("processed").length)
        assertEquals(listOf("https://www.linkedin.com/jobs/view/101"), page.jobs.map { it.url })

        val finalPage = adapter.fetch(ScrapeContext(SearchCriteria(listOf("Kotlin")), checkpoint = page.checkpoint))

        assertTrue(finalPage.complete)
        assertEquals(listOf("https://www.linkedin.com/jobs/view/102"), finalPage.jobs.map { it.url })
    }

    @Test
    fun `continues a full discovery page at the next offset and completes on a partial page`() {
        val searchUrls = mutableListOf<String>()
        every { httpClient.get(capture(searchUrls), any(), any()) } returnsMany listOf(fullSearchPage(), "[]")
        every { jobHunterClient.checkJobs(any()) } answers {
            JobCheckResult(
                newUrls = emptyList(),
                updatedUrls = emptyList(),
                unchangedUrls = arg<List<JobCheckRequest>>(0).map { it.url },
            )
        }
        val adapter = LinkedInAdapter(httpClient, ObjectMapper(), jobHunterClient, ScraperProperties(), clock)

        val firstPage =
            adapter.fetch(
                ScrapeContext(
                    SearchCriteria(listOf("Kotlin")),
                    since = Instant.parse("2026-09-17T15:00:00Z"),
                ),
            )

        assertFalse(firstPage.complete)
        assertEquals(100, firstPage.fetchedCount)
        assertEquals(mapOf("categoryIndex" to "0", "locationIndex" to "0", "offset" to "100"), firstPage.checkpoint)

        val finalPage =
            adapter.fetch(
                ScrapeContext(
                    SearchCriteria(listOf("Kotlin")),
                    since = Instant.parse("2026-09-17T15:00:00Z"),
                    checkpoint = firstPage.checkpoint,
                ),
            )

        assertTrue(finalPage.complete)
        assertEquals(0, finalPage.fetchedCount)
        assertTrue(searchUrls[0].contains("results_wanted=100"))
        assertTrue(searchUrls[0].contains("offset=0"))
        assertTrue(searchUrls[0].contains("hours_old=24"))
        assertTrue(searchUrls[1].contains("offset=100"))
    }

    @Test
    fun `fails loudly when a full page reaches the configured pagination cap`() {
        every { httpClient.get(any(), any(), any()) } returns fullSearchPage()
        every { jobHunterClient.checkJobs(any()) } answers {
            JobCheckResult(
                newUrls = emptyList(),
                updatedUrls = emptyList(),
                unchangedUrls = arg<List<JobCheckRequest>>(0).map { it.url },
            )
        }
        val adapter = LinkedInAdapter(httpClient, ObjectMapper(), jobHunterClient, ScraperProperties(maxPages = 1), clock)

        assertFailsWith<SourceSchemaException> {
            adapter.fetch(ScrapeContext(SearchCriteria(listOf("Kotlin"))))
        }
    }

    @Test
    fun `fails the unit when enrichment returns a retryable status`() {
        every { httpClient.get(any(), any(), any()) } returns fixture("fixtures/linkedin/search.json")
        every { httpClient.post(any(), any(), any(), any()) } returns
            """
            {
              "results": [
                {
                  "url": "https://www.linkedin.com/jobs/view/101",
                  "status": "skipped",
                  "error": "All proxies exhausted"
                }
              ]
            }
            """.trimIndent()
        every { jobHunterClient.checkJobs(any()) } returns
            JobCheckResult(
                newUrls = listOf("https://www.linkedin.com/jobs/view/101"),
                updatedUrls = emptyList(),
                unchangedUrls = listOf("https://www.linkedin.com/jobs/view/102"),
            )
        every { jobHunterClient.proxies(JobSource.LINKEDIN) } returns
            listOf(SourceProxy("http://user:secret@proxy.test:8080", "proxy.test", 8080, "user", "secret"))
        val adapter = LinkedInAdapter(httpClient, ObjectMapper(), jobHunterClient, ScraperProperties(), clock)

        assertFailsWith<SourceSchemaException> {
            adapter.fetch(ScrapeContext(SearchCriteria(listOf("Kotlin"))))
        }
    }

    @Test
    fun `fails when the requested lookback exceeds the supported cap`() {
        val adapter = LinkedInAdapter(httpClient, ObjectMapper(), jobHunterClient, ScraperProperties(), clock)

        assertFailsWith<SourceSchemaException> {
            adapter.fetch(
                ScrapeContext(
                    SearchCriteria(listOf("Kotlin")),
                    since = Instant.parse("2026-08-18T15:00:00Z"),
                ),
            )
        }
    }

    private fun fixture(path: String): String = checkNotNull(javaClass.classLoader.getResource(path)).readText()

    private fun enrichmentResponse(jobId: String): String =
        """
        {
          "results": [
            {
              "url": "https://www.linkedin.com/jobs/view/$jobId",
              "status": "success",
              "data": {"description": "Full description"}
            }
          ]
        }
        """.trimIndent()

    private fun fullSearchPage(): String =
        (1..100).joinToString(prefix = "[", postfix = "]") { id ->
            """
            {
              "title": "Kotlin Developer $id",
              "company": "Company $id",
              "job_url": "https://www.linkedin.com/jobs/view/$id",
              "location": "Remote"
            }
            """.trimIndent()
        }
}
