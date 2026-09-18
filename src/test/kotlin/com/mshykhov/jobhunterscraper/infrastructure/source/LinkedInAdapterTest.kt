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
    fun `searches and enriches with proxy credentials only in request bodies`() {
        val searchUrl = slot<String>()
        val searchBody = slot<Any>()
        val enrichBody = slot<Any>()
        every { httpClient.post(capture(searchUrl), capture(searchBody), any(), any()) } returns
            fixture("fixtures/linkedin/search.json")
        every { httpClient.post(match { it.endsWith("/jobs/enrich") }, capture(enrichBody), any(), any()) } returns
            fixture("fixtures/linkedin/enrich.json")
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
        assertEquals("http://127.0.0.1:8000/jobs/search", searchUrl.captured)
        val capturedSearchBody = searchBody.captured as Map<*, *>
        assertEquals(49, capturedSearchBody["hours_old"])
        assertEquals(2, (capturedSearchBody["proxies"] as List<*>).size)
        assertTrue(capturedSearchBody.toString().contains("user:secret@proxy.test"))
        assertTrue(enrichBody.captured.toString().contains("user:secret@proxy.test"))
        verify { jobHunterClient.checkJobs(match { it.size == 2 }) }
    }

    @Test
    fun `keeps unknown remote null and completes the last query`() {
        every { httpClient.post(match { it.endsWith("/jobs/search") }, any(), any(), any()) } returns
            fixture("fixtures/linkedin/search.json")
        every { httpClient.post(match { it.endsWith("/jobs/enrich") }, any(), any(), any()) } returns
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
        every { httpClient.post(match { it.endsWith("/jobs/search") }, any(), any(), any()) } returns
            fixture("fixtures/linkedin/search.json")
        every { httpClient.post(match { it.endsWith("/jobs/enrich") }, any(), any(), any()) } returnsMany
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
        assertEquals(32, page.checkpoint.getValue("processed0").length)
        assertEquals(listOf("https://www.linkedin.com/jobs/view/101"), page.jobs.map { it.url })

        val legacyCheckpoint = page.checkpoint - "processed0" + ("processed" to page.checkpoint.getValue("processed0"))
        val restartedAdapter = LinkedInAdapter(httpClient, ObjectMapper(), jobHunterClient, ScraperProperties(), clock)
        val finalPage = restartedAdapter.fetch(ScrapeContext(SearchCriteria(listOf("Kotlin")), checkpoint = legacyCheckpoint))

        assertTrue(finalPage.complete)
        assertEquals(listOf("https://www.linkedin.com/jobs/view/102"), finalPage.jobs.map { it.url })
        verify(exactly = 2) { httpClient.post(match { it.endsWith("/jobs/search") }, any(), any(), any()) }
    }

    @Test
    fun `splits a large processed checkpoint into API bounded values`() {
        every { httpClient.post(match { it.endsWith("/jobs/search") }, any(), any(), any()) } returns fullSearchPage()
        every { httpClient.post(match { it.endsWith("/jobs/enrich") }, any(), any(), any()) } returnsMany
            listOf(
                enrichmentResponse(1..20),
                enrichmentResponse(21..40),
                enrichmentResponse(41..60),
            )
        every { jobHunterClient.checkJobs(any()) } answers {
            JobCheckResult(
                newUrls = arg<List<JobCheckRequest>>(0).map { it.url },
                updatedUrls = emptyList(),
                unchangedUrls = emptyList(),
            )
        }
        every { jobHunterClient.proxies(JobSource.LINKEDIN) } returns
            (1..20).map { id ->
                SourceProxy("http://user:secret@proxy$id.test:8080", "proxy$id.test", 8080, "user", "secret")
            }
        val adapter = LinkedInAdapter(httpClient, ObjectMapper(), jobHunterClient, ScraperProperties(), clock)
        var context = ScrapeContext(SearchCriteria(listOf("Kotlin")))

        repeat(3) {
            val page = adapter.fetch(context)
            context = context.copy(checkpoint = page.checkpoint)
        }

        assertEquals(
            50,
            context.checkpoint
                .getValue("processed0")
                .split(',')
                .size,
        )
        assertEquals(
            10,
            context.checkpoint
                .getValue("processed1")
                .split(',')
                .size,
        )
        assertTrue(context.checkpoint.values.all { it.length <= 2048 })
        verify(exactly = 1) { httpClient.post(match { it.endsWith("/jobs/search") }, any(), any(), any()) }
    }

    @Test
    fun `refetches the initial page for a new run`() {
        every { httpClient.post(match { it.endsWith("/jobs/search") }, any(), any(), any()) } returns fullSearchPage()
        every { httpClient.post(match { it.endsWith("/jobs/enrich") }, any(), any(), any()) } returns enrichmentResponse(1..20)
        every { jobHunterClient.checkJobs(any()) } answers {
            JobCheckResult(
                newUrls = arg<List<JobCheckRequest>>(0).map { it.url },
                updatedUrls = emptyList(),
                unchangedUrls = emptyList(),
            )
        }
        every { jobHunterClient.proxies(JobSource.LINKEDIN) } returns
            (1..20).map { id ->
                SourceProxy("http://user:secret@proxy$id.test:8080", "proxy$id.test", 8080, "user", "secret")
            }
        val adapter = LinkedInAdapter(httpClient, ObjectMapper(), jobHunterClient, ScraperProperties(), clock)
        val initial = ScrapeContext(SearchCriteria(listOf("Kotlin")))

        adapter.fetch(initial)
        adapter.fetch(initial)

        verify(exactly = 2) { httpClient.post(match { it.endsWith("/jobs/search") }, any(), any(), any()) }
    }

    @Test
    fun `continues a full discovery page at the next offset and completes on a partial page`() {
        val searchBodies = mutableListOf<Any>()
        every { httpClient.post(match { it.endsWith("/jobs/search") }, capture(searchBodies), any(), any()) } returnsMany
            listOf(fullSearchPage(), "[]")
        every { jobHunterClient.checkJobs(any()) } answers {
            JobCheckResult(
                newUrls = emptyList(),
                updatedUrls = emptyList(),
                unchangedUrls = arg<List<JobCheckRequest>>(0).map { it.url },
            )
        }
        every { jobHunterClient.proxies(JobSource.LINKEDIN) } returns
            listOf(SourceProxy("http://user:secret@proxy.test:8080", "proxy.test", 8080, "user", "secret"))
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
        assertEquals(100, (searchBodies[0] as Map<*, *>)["results_wanted"])
        assertEquals(0, (searchBodies[0] as Map<*, *>)["offset"])
        assertEquals(24, (searchBodies[0] as Map<*, *>)["hours_old"])
        assertEquals(100, (searchBodies[1] as Map<*, *>)["offset"])
        verify(exactly = 2) { httpClient.post(match { it.endsWith("/jobs/search") }, any(), any(), any()) }
    }

    @Test
    fun `uses a one hour initial window and expands it from context since`() {
        val searchBodies = mutableListOf<Any>()
        every { httpClient.post(match { it.endsWith("/jobs/search") }, capture(searchBodies), any(), any()) } returns "[]"
        every { jobHunterClient.proxies(JobSource.LINKEDIN) } returns
            listOf(SourceProxy("http://user:secret@proxy.test:8080", "proxy.test", 8080, "user", "secret"))
        val adapter = LinkedInAdapter(httpClient, ObjectMapper(), jobHunterClient, ScraperProperties(), clock)

        adapter.fetch(ScrapeContext(SearchCriteria(listOf("Kotlin"))))
        adapter.fetch(
            ScrapeContext(
                SearchCriteria(listOf("Kotlin")),
                since = Instant.parse("2026-09-17T14:00:00Z"),
            ),
        )

        assertEquals(listOf(1, 25), searchBodies.map { (it as Map<*, *>)["hours_old"] })
    }

    @Test
    fun `fails loudly when a full page reaches the configured pagination cap`() {
        every { httpClient.post(match { it.endsWith("/jobs/search") }, any(), any(), any()) } returns fullSearchPage()
        every { jobHunterClient.checkJobs(any()) } answers {
            JobCheckResult(
                newUrls = emptyList(),
                updatedUrls = emptyList(),
                unchangedUrls = arg<List<JobCheckRequest>>(0).map { it.url },
            )
        }
        every { jobHunterClient.proxies(JobSource.LINKEDIN) } returns
            listOf(SourceProxy("http://user:secret@proxy.test:8080", "proxy.test", 8080, "user", "secret"))
        val adapter = LinkedInAdapter(httpClient, ObjectMapper(), jobHunterClient, ScraperProperties(maxPages = 1), clock)

        assertFailsWith<SourceSchemaException> {
            adapter.fetch(ScrapeContext(SearchCriteria(listOf("Kotlin"))))
        }
    }

    @Test
    fun `fails before search when the proxy pool is empty`() {
        every { jobHunterClient.proxies(JobSource.LINKEDIN) } returns emptyList()
        val adapter = LinkedInAdapter(httpClient, ObjectMapper(), jobHunterClient, ScraperProperties(), clock)

        assertFailsWith<SourceSchemaException> {
            adapter.fetch(ScrapeContext(SearchCriteria(listOf("Kotlin"))))
        }

        verify(exactly = 0) { httpClient.post(any(), any(), any(), any()) }
    }

    @Test
    fun `fails the unit when enrichment returns a retryable status`() {
        every { httpClient.post(match { it.endsWith("/jobs/search") }, any(), any(), any()) } returns
            fixture("fixtures/linkedin/search.json")
        every { httpClient.post(match { it.endsWith("/jobs/enrich") }, any(), any(), any()) } returns
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

    private fun enrichmentResponse(jobIds: IntRange): String =
        jobIds.joinToString(prefix = "{\"results\":[", postfix = "]}") { jobId ->
            """{"url":"https://www.linkedin.com/jobs/view/$jobId","status":"success","data":{"description":"Full"}}"""
        }

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
