package com.mshykhov.jobhunterscraper.infrastructure.source

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mshykhov.jobhunterscraper.application.SourceSchemaException
import com.mshykhov.jobhunterscraper.application.model.ScrapeContext
import com.mshykhov.jobhunterscraper.application.model.SearchCriteria
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LandingJobsAdapterTest {
    @Test
    fun `maps exact technology categories and preserves unknown remote`() {
        val page =
            adapter(RecordingSourceHttpClient(getResponse = { fixture("landingjobs/page.json") }))
                .fetch(context())

        assertEquals(listOf("Senior Java Developer", "Kotlin Developer"), page.jobs.map { it.title })
        assertEquals(listOf("java", "kotlin"), page.jobs.map { it.category })
        assertEquals(false, page.jobs.first().remote)
        assertNull(page.jobs.last().remote)
        assertEquals("Lisbon", page.jobs.first().location)
        assertEquals("DE", page.jobs.last().location)
        assertEquals("Build services\n\nJava", page.jobs.first().description)
        assertTrue(page.complete)
        assertEquals(3, page.fetchedCount)
    }

    @Test
    fun `rejects malformed response instead of treating it as an empty feed`() {
        val adapter = adapter(RecordingSourceHttpClient(getResponse = { "{\"data\":[]}" }))

        assertThrows(SourceSchemaException::class.java) { adapter.fetch(context()) }
    }

    @Test
    fun `remote-only criteria exclude explicit false and preserve unknown remote`() {
        val page =
            adapter(RecordingSourceHttpClient(getResponse = { fixture("landingjobs/page.json") }))
                .fetch(context(remoteOnly = true))

        assertEquals(listOf("Kotlin Developer"), page.jobs.map { it.title })
        assertNull(page.jobs.single().remote)
    }

    @Test
    fun `accepts remote offer when locations is omitted`() {
        val response =
            """[{"id":4,"title":"Remote Java Developer","url":"https://landing.jobs/at/acme/remote-java-developer","tags":["Java"],"remote":true,"published_at":"2026-09-18T12:00:00Z"}]"""

        val job = adapter(RecordingSourceHttpClient(getResponse = { response })).fetch(context()).jobs.single()

        assertEquals(true, job.remote)
        assertEquals("Remote", job.location)
    }

    @Test
    fun `fails rather than truncating a full final allowed page`() {
        val mapper = jacksonObjectMapper()
        val offer = mapper.readTree(fixture("landingjobs/page.json")).first()
        val fullPage = mapper.writeValueAsString(List(50) { offer })
        val adapter =
            LandingJobsAdapter(
                RecordingSourceHttpClient(getResponse = { fullPage }),
                mapper,
                ScraperProperties(maxPages = 1),
            )

        assertThrows(SourceSchemaException::class.java) { adapter.fetch(context()) }
    }

    private fun adapter(client: RecordingSourceHttpClient) = LandingJobsAdapter(client, jacksonObjectMapper(), ScraperProperties())

    private fun context(remoteOnly: Boolean = false) =
        ScrapeContext(SearchCriteria(categories = listOf("Java", "Kotlin"), remoteOnly = remoteOnly))
}
