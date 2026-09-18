package com.mshykhov.jobhunterscraper.infrastructure.source

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mshykhov.jobhunterscraper.application.PublicationWindow
import com.mshykhov.jobhunterscraper.application.SourceSchemaException
import com.mshykhov.jobhunterscraper.application.model.ScrapeContext
import com.mshykhov.jobhunterscraper.application.model.SearchCriteria
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class JustJoinItAdapterTest {
    @Test
    fun `maps remote JVM jobs without matching JavaScript and uses original salary`() {
        val client = successfulClient()
        val page = adapter(client).fetch(context())

        assertEquals(listOf("Senior Backend Engineer", "Kotlin Engineering Manager"), page.jobs.map { it.title })
        assertEquals(listOf("java", "kotlin"), page.jobs.map { it.category })
        assertEquals("PLN 20000–25000/month", page.jobs.first().salary)
        assertTrue(page.jobs.all { it.description == "Build reliable JVM services. Required skills: Java and Kotlin." })
        assertTrue(page.jobs.all { it.remote == true })
        assertTrue(page.complete)
        assertEquals(4, page.fetchedCount)
        assertTrue(client.getUrls.first().contains("experienceLevels=senior"))
        assertTrue(client.getUrls.first().contains("experienceLevels=manager"))
        assertEquals(
            listOf(
                "https://justjoin.it/api/candidate-api/offers/senior-java-engineer",
                "https://justjoin.it/api/candidate-api/offers/remote-kotlin-manager",
            ),
            client.getUrls.drop(1),
        )
    }

    @Test
    fun `advances the cursor from response metadata`() {
        val mapper = jacksonObjectMapper()
        val response = mapper.readTree(fixture("justjoinit/page.json"))
        (response["meta"] as com.fasterxml.jackson.databind.node.ObjectNode).put("totalItems", 200)
        (response["meta"]["next"] as com.fasterxml.jackson.databind.node.ObjectNode).put("cursor", 100).put("itemsCount", 4)
        val page =
            adapter(
                RecordingSourceHttpClient(
                    getResponse = { url ->
                        if (url.contains("?from=")) response.toString() else fixture("justjoinit/detail.json")
                    },
                ),
            ).fetch(context())

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
        val mapper = jacksonObjectMapper()
        val response = mapper.readTree(fixture("justjoinit/page.json"))
        (response["meta"] as com.fasterxml.jackson.databind.node.ObjectNode).put("totalItems", 200)
        (response["meta"]["next"] as com.fasterxml.jackson.databind.node.ObjectNode).put("cursor", 100).put("itemsCount", 4)
        val adapter =
            JustJoinItAdapter(
                RecordingSourceHttpClient(getResponse = { response.toString() }),
                mapper,
                ScraperProperties(maxPages = 1),
                publicationWindow(),
            )

        assertThrows(SourceSchemaException::class.java) { adapter.fetch(context()) }
    }

    @Test
    fun `rejects experience levels outside the requested senior and manager set`() {
        val response = fixture("justjoinit/page.json").replace("\"experienceLevel\": \"manager\"", "\"experienceLevel\": \"junior\"")

        val page =
            adapter(
                RecordingSourceHttpClient(
                    getResponse = { url ->
                        if (url.contains("?from=")) response else fixture("justjoinit/detail.json")
                    },
                ),
            ).fetch(context())

        assertEquals(listOf("Senior Backend Engineer"), page.jobs.map { it.title })
    }

    @Test
    fun `propagates detail failures before returning an advanced checkpoint`() {
        val client =
            RecordingSourceHttpClient(
                getResponse = { url ->
                    if (url.contains("?from=")) fixture("justjoinit/page.json") else error("detail unavailable")
                },
            )

        assertThrows(IllegalStateException::class.java) { adapter(client).fetch(context()) }
        assertEquals(2, client.getUrls.size)
    }

    @Test
    fun `stops a published-at sorted feed after a wholly stale page without detail requests`() {
        val mapper = jacksonObjectMapper()
        val response = mapper.readTree(fixture("justjoinit/page.json"))
        response.path("data").forEach { (it as ObjectNode).put("publishedAt", "2026-09-18T10:00:00Z") }
        (response.path("meta") as ObjectNode).put("totalItems", 104)
        val client = RecordingSourceHttpClient(getResponse = { response.toString() })

        val page = adapter(client).fetch(context(Instant.parse("2026-09-18T11:30:00Z")))

        assertTrue(page.jobs.isEmpty())
        assertTrue(page.complete)
        assertEquals(1, client.getUrls.size)
    }

    @Test
    fun `rejects an empty detail description`() {
        val client =
            RecordingSourceHttpClient(
                getResponse = { url ->
                    if (url.contains("?from=")) fixture("justjoinit/page.json") else "{\"body\":\"<p><br></p>\"}"
                },
            )

        assertThrows(SourceSchemaException::class.java) { adapter(client).fetch(context()) }
    }

    private fun adapter(client: RecordingSourceHttpClient) =
        JustJoinItAdapter(client, jacksonObjectMapper(), ScraperProperties(), publicationWindow())

    private fun successfulClient() =
        RecordingSourceHttpClient(
            getResponse = { url ->
                if (url.contains("?from=")) fixture("justjoinit/page.json") else fixture("justjoinit/detail.json")
            },
        )

    private fun context(since: Instant? = null) = ScrapeContext(SearchCriteria(categories = listOf("Java", "Kotlin")), since = since)

    private fun publicationWindow() = PublicationWindow(Clock.fixed(Instant.parse("2026-09-18T12:30:00Z"), ZoneOffset.UTC))
}
