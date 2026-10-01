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
    private val mapper = jacksonObjectMapper()

    @Test
    fun `collects exact technology through public search and advances pages`() {
        val client =
            client(postResponse = { url, body ->
                assertEquals("https://euremotejobs.com/wp-admin/admin-ajax.php", url)
                assertTrue(body.toString().contains("search_technology%5B%5D=Java"))
                assertTrue(body.toString().contains("nonce=fixture-nonce"))
                assertTrue(body.toString().contains("page=1"))
                fixture("euremotejobs/search-page.json")
            })

        val page = adapter(client).fetch(context())

        val job = page.jobs.single()
        assertEquals("java", job.category)
        assertEquals("Java Senior Software Engineer", job.title)
        assertEquals("MariaDB Plc", job.company)
        assertTrue(job.location.orEmpty().contains("Poland"))
        assertTrue(job.description.startsWith("About MariaDB"))
        assertEquals(true, job.remote)
        assertEquals("2026-09-30T08:19:49Z", job.publishedAt)
        assertEquals("0", page.checkpoint["categoryIndex"])
        assertEquals("2", page.checkpoint["page"])
        assertFalse(page.complete)
        assertTrue(client.getUrls.none { it.contains("wp-json") })
        assertFalse(page.checkpoint.values.any { it.contains("fixture-nonce") })
    }

    @Test
    fun `treats missing exact technology as an empty category`() {
        val client = client(search = fixture("euremotejobs/search.html").replace("value=\"Java\"", "value=\"Rust\""))
        val page = adapter(client).fetch(context())

        assertTrue(page.jobs.isEmpty())
        assertTrue(page.complete)
        assertTrue(client.postUrls.isEmpty())
    }

    @Test
    fun `continues pagination without fetching details from older calendar days`() {
        val client = client()
        val page = adapter(client).fetch(context(Instant.parse("2026-10-02T03:30:00Z")))

        assertTrue(page.jobs.isEmpty())
        assertFalse(page.complete)
        assertEquals(1, client.getUrls.size)
        assertEquals(1, page.fetchedCount)
    }

    @Test
    fun `filters exact detail timestamp inside an eligible calendar day`() {
        val client = client()
        val page = adapter(client).fetch(context(Instant.parse("2026-09-30T08:30:00Z")))

        assertTrue(page.jobs.isEmpty())
        assertEquals(2, client.getUrls.size)
    }

    @Test
    fun `keeps local calendar dates eligible across UTC midnight`() {
        val response = fixture("euremotejobs/search-page.json").replace("2026-09-30", "2026-10-01")
        val detail = fixture("euremotejobs/job.html").replace("2026-09-30T10:19:49+02:00", "2026-10-01T00:19:49+02:00")
        val client = client(response = response, detail = detail)
        val adapter = adapter(client, now = Instant.parse("2026-09-30T23:00:00Z"))

        val page = adapter.fetch(context(Instant.parse("2026-09-30T22:00:00Z")))

        assertEquals("2026-09-30T22:19:49Z", page.jobs.single().publishedAt)
    }

    @Test
    fun `completes the category only when public search reports no more pages`() {
        val page = adapter(client(response = response(hasMore = false))).fetch(context())

        assertTrue(page.complete)
        assertEquals("1", page.checkpoint["categoryIndex"])
    }

    @Test
    fun `deduplicates cards within a page`() {
        val html =
            mapper
                .readTree(fixture("euremotejobs/search-page.json"))
                .path("data")
                .path("html")
                .asText()
        val page = adapter(client(response = response(html = html + html))).fetch(context())

        assertEquals(1, page.jobs.size)
        assertEquals(2, page.fetchedCount)
    }

    @Test
    fun `rejects repeated pages instead of advancing through duplicate results`() {
        val adapter = adapter(client())
        val first = adapter.fetch(context())

        assertThrows(SourceSchemaException::class.java) {
            adapter.fetch(context().copy(checkpoint = first.checkpoint))
        }
    }

    @Test
    fun `rejects incomplete or malformed search responses`() {
        val responses =
            listOf(
                "{\"success\":false,\"data\":\"Invalid request\"}",
                "{\"success\":true,\"data\":{\"html\":\"\",\"has_more\":\"false\"}}",
                response(html = "", hasMore = true),
                response(html = "<p>unexpected replacement page</p>", hasMore = false),
            )
        responses.forEach { response ->
            assertThrows(SourceSchemaException::class.java) { adapter(client(response = response)).fetch(context()) }
        }
    }

    @Test
    fun `accepts a legitimate empty final page`() {
        val page = adapter(client(response = response(html = "", hasMore = false))).fetch(context())

        assertTrue(page.complete)
        assertTrue(page.jobs.isEmpty())
    }

    @Test
    fun `rejects broken public search configuration`() {
        listOf(
            "<html><body>Not available</body></html>",
            fixture("euremotejobs/search.html").replace("nonce:", "missing:"),
        ).forEach { search ->
            assertThrows(SourceSchemaException::class.java) { adapter(client(search = search)).fetch(context()) }
        }
    }

    @Test
    fun `fails when another page would exceed the configured coverage cap`() {
        val adapter = adapter(client(), ScraperProperties(maxPages = 1))

        assertThrows(SourceSchemaException::class.java) { adapter.fetch(context()) }
    }

    @Test
    fun `rejects a detail link outside the configured source`() {
        val response = fixture("euremotejobs/search-page.json").replace("https://euremotejobs.com/job/", "https://unrelated.test/job/")
        val client = client(response = response)

        assertThrows(SourceSchemaException::class.java) { adapter(client).fetch(context()) }
        assertEquals(1, client.getUrls.size)
    }

    @Test
    fun `rejects missing JobPosting details`() {
        val client = client(detail = "<html><body>Unavailable</body></html>")

        assertThrows(SourceSchemaException::class.java) { adapter(client).fetch(context()) }
    }

    @Test
    fun `preserves unknown remote status and parses a JSON-LD graph`() {
        val posting =
            mapper.readTree(
                fixture("euremotejobs/job.html").substringAfter("<script type=\"application/ld+json\">").substringBefore("</script>"),
            )
        (posting as com.fasterxml.jackson.databind.node.ObjectNode).remove("jobLocationType")
        val detail = "<script type=\"application/ld+json\">${mapper.writeValueAsString(mapOf("@graph" to listOf(posting)))}</script>"

        val page = adapter(client(detail = detail)).fetch(context())

        assertEquals(null, page.jobs.single().remote)
    }

    private fun client(
        search: String = fixture("euremotejobs/search.html"),
        response: String = fixture("euremotejobs/search-page.json"),
        detail: String = fixture("euremotejobs/job.html"),
        postResponse: (String, Any) -> String = { _, _ -> response },
    ) = RecordingSourceHttpClient(
        getResponse = { url -> if (url.contains("/job/")) detail else search },
        postResponse = postResponse,
    )

    private fun response(
        html: String =
            mapper
                .readTree(fixture("euremotejobs/search-page.json"))
                .path("data")
                .path("html")
                .asText(),
        hasMore: Boolean = true,
    ) = mapper.writeValueAsString(mapOf("success" to true, "data" to mapOf("html" to html, "has_more" to hasMore)))

    private fun adapter(
        client: RecordingSourceHttpClient,
        properties: ScraperProperties = ScraperProperties(),
        now: Instant = Instant.parse("2026-10-01T04:30:00Z"),
    ) = EuRemoteJobsAdapter(
        client,
        mapper,
        properties,
        PublicationWindow(Clock.fixed(now, ZoneOffset.UTC)),
    )

    private fun context(since: Instant? = null) = ScrapeContext(SearchCriteria(categories = listOf("java")), since = since)
}
