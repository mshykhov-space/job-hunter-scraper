package com.mshykhov.jobhunterscraper.infrastructure.source

import com.mshykhov.jobhunterscraper.application.PublicationWindow
import com.mshykhov.jobhunterscraper.application.SourceSchemaException
import com.mshykhov.jobhunterscraper.application.model.ScrapeContext
import com.mshykhov.jobhunterscraper.application.model.SearchCriteria
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import com.mshykhov.jobhunterscraper.infrastructure.http.SourceHttpClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DouAdapterTest {
    private val httpClient = mockk<SourceHttpClient>()
    private val publicationWindow = PublicationWindow(Clock.fixed(Instant.parse("2026-09-17T08:00:00Z"), ZoneOffset.UTC))
    private val adapter = DouAdapter(httpClient, ScraperProperties(), publicationWindow)

    @Test
    fun `parses RSS fields and advances to the next category`() {
        every { httpClient.get(any(), any(), any()) } returns fixture("fixtures/dou/feed.xml")

        val page = adapter.fetch(ScrapeContext(SearchCriteria(listOf("Kotlin", "Java"))))

        assertFalse(page.complete)
        assertEquals(mapOf("categoryIndex" to "1"), page.checkpoint)
        assertEquals(2, page.fetchedCount)
        assertEquals("Senior Kotlin Engineer", page.jobs[0].title)
        assertEquals("Acme, Inc.", page.jobs[0].company)
        assertEquals("Київ", page.jobs[0].location)
        assertEquals("до $5000", page.jobs[0].salary)
        assertEquals(true, page.jobs[0].remote)
        assertNull(page.jobs[1].remote)
        assertEquals("Kotlin", page.jobs[1].category)
        assertEquals("2026-09-17T07:00:00Z", page.jobs[0].publishedAt)
        verify { httpClient.get(match { "category=Kotlin" in it }, any(), any()) }
    }

    @Test
    fun `filters an older RSS timestamp but keeps an unknown publication time`() {
        every { httpClient.get(any(), any(), any()) } returns fixture("fixtures/dou/feed.xml")

        val page =
            adapter.fetch(
                ScrapeContext(
                    SearchCriteria(listOf("Kotlin")),
                    since = Instant.parse("2026-09-17T07:30:00Z"),
                ),
            )

        assertEquals(listOf("Backend Engineer"), page.jobs.map { it.title })
        assertEquals(2, page.fetchedCount)
    }

    @Test
    fun `rejects a non RSS response`() {
        every { httpClient.get(any(), any(), any()) } returns "<html><body>blocked</body></html>"

        assertFailsWith<SourceSchemaException> {
            adapter.fetch(ScrapeContext(SearchCriteria(listOf("Kotlin"))))
        }
    }

    private fun fixture(path: String): String = checkNotNull(javaClass.classLoader.getResource(path)).readText()
}
