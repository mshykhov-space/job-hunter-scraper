package com.mshykhov.jobhunterscraper.infrastructure.source

import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunterscraper.application.SourceSchemaException
import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.application.model.ScrapeContext
import com.mshykhov.jobhunterscraper.application.model.SearchCriteria
import com.mshykhov.jobhunterscraper.application.model.SourceProxy
import com.mshykhov.jobhunterscraper.infrastructure.api.JobHunterClient
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import com.mshykhov.jobhunterscraper.infrastructure.http.SourceHttpClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class Web3CareerAdapterTest {
    private val httpClient = mockk<SourceHttpClient>()
    private val jobHunterClient = mockk<JobHunterClient>()

    @Test
    fun `parses JSON-LD and uses the optional source proxy`() {
        every { httpClient.get(any(), any(), any()) } returns fixture("fixtures/web3career/page.html")
        every { jobHunterClient.proxies(JobSource.WEB3CAREER) } returns
            listOf(
                SourceProxy(
                    "http://user:secret@proxy.test:8080",
                    "proxy.test",
                    8080,
                    "user",
                    "secret",
                    fingerprint =
                        mapOf(
                            "User-Agent" to "Browser",
                        ),
                ),
            )
        val adapter = Web3CareerAdapter(httpClient, ObjectMapper(), jobHunterClient, ScraperProperties(maxPages = 10))

        val page = adapter.fetch(ScrapeContext(SearchCriteria(listOf("Kotlin"), remoteOnly = true)))

        assertFalse(page.complete)
        assertEquals(mapOf("categoryIndex" to "0", "page" to "2"), page.checkpoint)
        verify { httpClient.get("https://web3.career/kotlin+remote-jobs", any(), match { it?.host == "proxy.test" }) }
        assertEquals("https://web3.career/senior-kotlin-engineer-acme/501", page.jobs.single().url)
        assertEquals("120000-160000 USD/YEAR", page.jobs.single().salary)
        assertEquals("Europe, Ukraine", page.jobs.single().location)
        assertEquals(true, page.jobs.single().remote)
        assertEquals("Kotlin", page.jobs.single().category)
    }

    @Test
    fun `fails instead of silently truncating at the page cap`() {
        every { httpClient.get(any(), any(), any()) } returns fixture("fixtures/web3career/page.html")
        every { jobHunterClient.proxies(JobSource.WEB3CAREER) } returns emptyList()
        val adapter = Web3CareerAdapter(httpClient, ObjectMapper(), jobHunterClient, ScraperProperties(maxPages = 1))

        assertFailsWith<SourceSchemaException> {
            adapter.fetch(ScrapeContext(SearchCriteria(listOf("Kotlin"))))
        }
    }

    @Test
    fun `adds the page query after the canonical first page`() {
        every { httpClient.get(any(), any(), any()) } returns fixture("fixtures/web3career/page.html")
        every { jobHunterClient.proxies(JobSource.WEB3CAREER) } returns emptyList()
        val adapter = Web3CareerAdapter(httpClient, ObjectMapper(), jobHunterClient, ScraperProperties(maxPages = 10))

        adapter.fetch(
            ScrapeContext(
                SearchCriteria(listOf("Kotlin"), remoteOnly = true),
                checkpoint = mapOf("categoryIndex" to "0", "page" to "2"),
            ),
        )

        verify { httpClient.get("https://web3.career/kotlin+remote-jobs?page=2", any(), null) }
    }

    private fun fixture(path: String): String = checkNotNull(javaClass.classLoader.getResource(path)).readText()
}
