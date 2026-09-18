package com.mshykhov.jobhunterscraper.infrastructure.source

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mshykhov.jobhunterscraper.application.SourceSchemaException
import com.mshykhov.jobhunterscraper.application.model.ScrapeContext
import com.mshykhov.jobhunterscraper.application.model.SearchCriteria
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoFluffJobsAdapterTest {
    @Test
    fun `enriches matching jobs and advances through all reported pages`() {
        val client =
            RecordingSourceHttpClient(
                getResponse = { fixture("nofluffjobs/detail.json") },
                postResponse = { _, _ -> fixture("nofluffjobs/search.json") },
            )
        val page = adapter(client).fetch(context())

        assertEquals(1, page.jobs.size)
        assertEquals("Senior JVM Engineer", page.jobs.single().title)
        assertEquals("java", page.jobs.single().category)
        assertEquals("PLN 20000–23520/month (b2b)", page.jobs.single().salary)
        assertTrue(
            page.jobs
                .single()
                .description
                .contains("Build reliable services"),
        )
        assertTrue(
            page.jobs
                .single()
                .description
                .contains("Strong JVM experience"),
        )
        assertEquals(mapOf("page" to "2"), page.checkpoint)
        assertFalse(page.complete)
        assertEquals(2, page.fetchedCount)
        assertEquals(1, client.getUrls.size)
    }

    @Test
    fun `propagates detail request failures without ingesting an empty description`() {
        val client =
            RecordingSourceHttpClient(
                getResponse = { throw IllegalStateException("detail unavailable") },
                postResponse = { _, _ -> fixture("nofluffjobs/search.json") },
            )

        assertThrows(IllegalStateException::class.java) { adapter(client).fetch(context()) }
    }

    @Test
    fun `rejects malformed detail response`() {
        val client =
            RecordingSourceHttpClient(
                getResponse = { "{\"message\":\"temporary error\"}" },
                postResponse = { _, _ -> fixture("nofluffjobs/search.json") },
            )

        assertThrows(SourceSchemaException::class.java) { adapter(client).fetch(context()) }
    }

    @Test
    fun `preserves unknown remote state instead of inferring false`() {
        val search = fixture("nofluffjobs/search.json").replace("\"fullyRemote\": true", "\"covidTimeRemotely\": false")
        val client =
            RecordingSourceHttpClient(
                getResponse = { fixture("nofluffjobs/detail.json") },
                postResponse = { _, _ -> search },
            )

        val job = adapter(client).fetch(context()).jobs.single()

        assertEquals(null, job.remote)
    }

    @Test
    fun `fails when upstream reports more pages than the configured coverage cap`() {
        val client = RecordingSourceHttpClient(postResponse = { _, _ -> fixture("nofluffjobs/search.json") })
        val adapter = NoFluffJobsAdapter(client, jacksonObjectMapper(), ScraperProperties(maxPages = 1))

        assertThrows(SourceSchemaException::class.java) { adapter.fetch(context()) }
        assertTrue(client.getUrls.isEmpty())
    }

    private fun adapter(client: RecordingSourceHttpClient) = NoFluffJobsAdapter(client, jacksonObjectMapper(), ScraperProperties())

    private fun context() = ScrapeContext(SearchCriteria(categories = listOf("Java")))
}
