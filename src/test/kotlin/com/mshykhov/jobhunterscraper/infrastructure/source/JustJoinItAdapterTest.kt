package com.mshykhov.jobhunterscraper.infrastructure.source

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mshykhov.jobhunterscraper.application.SourceSchemaException
import com.mshykhov.jobhunterscraper.application.model.ScrapeContext
import com.mshykhov.jobhunterscraper.application.model.SearchCriteria
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JustJoinItAdapterTest {
    @Test
    fun `maps remote JVM jobs without matching JavaScript and uses original salary`() {
        val client = RecordingSourceHttpClient(getResponse = { fixture("justjoinit/page.json") })
        val page = adapter(client).fetch(context())

        assertEquals(listOf("Senior Backend Engineer", "Kotlin Engineering Manager"), page.jobs.map { it.title })
        assertEquals(listOf("java", "kotlin"), page.jobs.map { it.category })
        assertEquals("PLN 20000–25000/month", page.jobs.first().salary)
        assertTrue(page.jobs.all { it.remote == true })
        assertTrue(page.complete)
        assertEquals(4, page.fetchedCount)
        assertTrue(client.getUrls.single().contains("experienceLevels=senior"))
        assertTrue(client.getUrls.single().contains("experienceLevels=manager"))
    }

    @Test
    fun `advances the cursor from response metadata`() {
        val response =
            fixture("justjoinit/page.json").replace(
                "\"next\": {\"cursor\": null, \"itemsCount\": 0}",
                "\"next\": {\"cursor\": 100, \"itemsCount\": 100}",
            )
        val page = adapter(RecordingSourceHttpClient(getResponse = { response })).fetch(context())

        assertEquals(mapOf("from" to "100"), page.checkpoint)
        assertEquals(false, page.complete)
    }

    @Test
    fun `rejects malformed response instead of treating it as empty`() {
        val adapter = adapter(RecordingSourceHttpClient(getResponse = { "{\"error\":\"unavailable\"}" }))

        assertThrows(SourceSchemaException::class.java) { adapter.fetch(context()) }
    }

    @Test
    fun `fails when response requires more pages than the configured coverage cap`() {
        val response =
            fixture("justjoinit/page.json").replace(
                "\"next\": {\"cursor\": null, \"itemsCount\": 0}",
                "\"next\": {\"cursor\": 100, \"itemsCount\": 100}",
            )
        val adapter =
            JustJoinItAdapter(
                RecordingSourceHttpClient(getResponse = { response }),
                jacksonObjectMapper(),
                ScraperProperties(maxPages = 1),
            )

        assertThrows(SourceSchemaException::class.java) { adapter.fetch(context()) }
    }

    private fun adapter(client: RecordingSourceHttpClient) = JustJoinItAdapter(client, jacksonObjectMapper(), ScraperProperties())

    private fun context() = ScrapeContext(SearchCriteria(categories = listOf("Java", "Kotlin")))
}
