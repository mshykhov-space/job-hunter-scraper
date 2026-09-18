package com.mshykhov.jobhunterscraper.infrastructure.source

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mshykhov.jobhunterscraper.application.PublicationWindow
import com.mshykhov.jobhunterscraper.application.SourceSchemaException
import com.mshykhov.jobhunterscraper.application.model.ScrapeContext
import com.mshykhov.jobhunterscraper.application.model.SearchCriteria
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class EuRemoteJobsAdapterTest {
    @Test
    fun `selects the exact tag and advances WordPress pages`() {
        val client =
            RecordingSourceHttpClient(getResponse = { url ->
                if (url.contains("job_listing_tag?")) fixture("euremotejobs/tags.json") else fixture("euremotejobs/listings.json")
            })
        val page = adapter(client).fetch(context())

        assertEquals(1, page.jobs.size)
        assertEquals("java", page.jobs.single().category)
        assertEquals("Acme", page.jobs.single().company)
        assertEquals("EMEA", page.jobs.single().location)
        assertEquals("<p>Build a reliable platform.</p>", page.jobs.single().description)
        assertEquals(true, page.jobs.single().remote)
        assertEquals("2026-09-18T10:21:23Z", page.jobs.single().publishedAt)
        assertEquals(mapOf("categoryIndex" to "0", "page" to "2"), page.checkpoint)
        assertFalse(page.complete)
        assertTrue(client.getUrls.last().contains("job_listing_tag=229"))
    }

    @Test
    fun `treats no exact tag as a legitimate empty category`() {
        val tags = "[{\"id\":999,\"name\":\"JavaScript\"}]"
        val client = RecordingSourceHttpClient(getResponse = { tags })
        val page = adapter(client).fetch(context())

        assertTrue(page.jobs.isEmpty())
        assertTrue(page.complete)
        assertEquals(1, client.getUrls.size)
    }

    @Test
    fun `rejects malformed tag response instead of silently skipping it`() {
        val adapter = adapter(RecordingSourceHttpClient(getResponse = { "{\"error\":\"unavailable\"}" }))

        assertThrows(SourceSchemaException::class.java) { adapter.fetch(context()) }
    }

    @Test
    fun `passes since to WordPress and filters a stale response defensively`() {
        val client =
            RecordingSourceHttpClient(getResponse = { url ->
                if (url.contains("job_listing_tag?")) fixture("euremotejobs/tags.json") else fixture("euremotejobs/listings.json")
            })

        val page = adapter(client).fetch(context(Instant.parse("2026-09-18T10:30:00Z")))

        assertTrue(page.jobs.isEmpty())
        assertTrue(client.getUrls.last().contains("after=2026-09-18T10%3A30%3A00Z"))
    }

    @Test
    fun `fails when WordPress reports more pages than the configured coverage cap`() {
        val client =
            RecordingSourceHttpClient(getResponse = { url ->
                if (url.contains("job_listing_tag?")) fixture("euremotejobs/tags.json") else fixture("euremotejobs/listings.json")
            })
        val adapter = EuRemoteJobsAdapter(client, jacksonObjectMapper(), ScraperProperties(maxPages = 1), publicationWindow())

        assertThrows(SourceSchemaException::class.java) { adapter.fetch(context()) }
    }

    private fun adapter(client: RecordingSourceHttpClient) =
        EuRemoteJobsAdapter(client, jacksonObjectMapper(), ScraperProperties(), publicationWindow())

    private fun context(since: Instant? = null) = ScrapeContext(SearchCriteria(categories = listOf("Java")), since = since)

    private fun publicationWindow() = PublicationWindow(Clock.fixed(Instant.parse("2026-09-18T12:30:00Z"), ZoneOffset.UTC))
}
