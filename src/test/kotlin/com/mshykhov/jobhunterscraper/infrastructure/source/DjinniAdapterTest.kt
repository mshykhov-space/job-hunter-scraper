package com.mshykhov.jobhunterscraper.infrastructure.source

import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunterscraper.application.PublicationWindow
import com.mshykhov.jobhunterscraper.application.SourceSchemaException
import com.mshykhov.jobhunterscraper.application.model.ScrapeContext
import com.mshykhov.jobhunterscraper.application.model.SearchCriteria
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import com.mshykhov.jobhunterscraper.infrastructure.http.SourceHttpClient
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DjinniAdapterTest {
    private val httpClient = mockk<SourceHttpClient>()
    private val publicationWindow = PublicationWindow(Clock.fixed(Instant.parse("2026-09-17T12:00:00Z"), ZoneOffset.UTC))

    @Test
    fun `parses JSON-LD and keeps category while pagination advances`() {
        every { httpClient.get(any(), any(), any()) } returns fixture("fixtures/djinni/page.html")
        val adapter = DjinniAdapter(httpClient, ObjectMapper(), ScraperProperties(maxPages = 10), publicationWindow)

        val page = adapter.fetch(ScrapeContext(SearchCriteria(listOf("Kotlin", "Java"))))

        assertFalse(page.complete)
        assertEquals(mapOf("categoryIndex" to "0", "page" to "2"), page.checkpoint)
        assertEquals(2, page.jobs.size)
        assertEquals("https://djinni.co/jobs/101-senior-kotlin-engineer/", page.jobs[0].url)
        assertEquals(true, page.jobs[0].remote)
        assertEquals(false, page.jobs[1].remote)
        assertEquals("Kyiv", page.jobs[1].location)
        assertEquals("Kotlin", page.jobs[1].category)
        assertNull(page.jobs[1].publishedAt)
    }

    @Test
    fun `fails instead of silently truncating at the page cap`() {
        every { httpClient.get(any(), any(), any()) } returns fixture("fixtures/djinni/page.html")
        val adapter = DjinniAdapter(httpClient, ObjectMapper(), ScraperProperties(maxPages = 1), publicationWindow)

        assertFailsWith<SourceSchemaException> {
            adapter.fetch(ScrapeContext(SearchCriteria(listOf("Kotlin"))))
        }
    }

    @Test
    fun `filters a precise older timestamp but keeps an unknown timestamp`() {
        every { httpClient.get(any(), any(), any()) } returns
            fixture("fixtures/djinni/page.html").replace("2026-09-17T10:00:00", "2026-09-17T10:00:00Z")
        val adapter = DjinniAdapter(httpClient, ObjectMapper(), ScraperProperties(maxPages = 10), publicationWindow)

        val page =
            adapter.fetch(
                ScrapeContext(
                    SearchCriteria(listOf("Kotlin")),
                    since = Instant.parse("2026-09-17T11:00:00Z"),
                ),
            )

        assertEquals(listOf("Backend Engineer"), page.jobs.map { it.title })
        assertEquals(2, page.fetchedCount)
    }

    private fun fixture(path: String): String = checkNotNull(javaClass.classLoader.getResource(path)).readText()
}
