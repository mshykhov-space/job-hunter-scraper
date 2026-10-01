package com.mshykhov.jobhunterscraper.infrastructure.source

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunterscraper.application.PublicationWindow
import com.mshykhov.jobhunterscraper.application.SourceAdapter
import com.mshykhov.jobhunterscraper.application.SourceSchemaException
import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.application.model.ProxyEndpoint
import com.mshykhov.jobhunterscraper.application.model.ScrapeContext
import com.mshykhov.jobhunterscraper.application.model.ScrapePage
import com.mshykhov.jobhunterscraper.application.model.ScrapedJob
import com.mshykhov.jobhunterscraper.infrastructure.api.JobHunterClient
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import com.mshykhov.jobhunterscraper.infrastructure.http.SourceHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Component
class EuRemoteJobsAdapter(
    private val httpClient: SourceHttpClient,
    private val objectMapper: ObjectMapper,
    private val jobHunterClient: JobHunterClient,
    private val properties: ScraperProperties,
    private val publicationWindow: PublicationWindow,
) : SourceAdapter {
    override val source = JobSource.EUREMOTEJOBS

    override fun fetch(context: ScrapeContext): ScrapePage {
        val categoryIndex = context.checkpoint[CATEGORY_INDEX]?.toIntOrNull() ?: 0
        val page = context.checkpoint[PAGE]?.toIntOrNull() ?: 1
        if (categoryIndex < 0 || categoryIndex > context.criteria.categories.size) {
            throw SourceSchemaException("euremotejobs checkpoint has invalid category index")
        }
        if (categoryIndex == context.criteria.categories.size) {
            return ScrapePage(emptyList(), context.checkpoint, complete = true, fetchedCount = 0)
        }
        requirePageWithinCap(page, properties.maxPages, source.id)
        val category = context.criteria.categories[categoryIndex].trim()
        if (category.isEmpty()) return advanceCategory(categoryIndex, context.criteria.categories.size)

        val endpoint = properties.endpoint(source, DEFAULT_ENDPOINT).trimEnd('/')
        val sourceProxy =
            jobHunterClient.proxies(source).firstOrNull()
                ?: throw SourceSchemaException("euremotejobs proxy pool is empty")
        val proxy = sourceProxy.endpoint()
        val headers = HEADERS + sourceProxy.fingerprint
        val search = Jsoup.parse(httpClient.get("$endpoint/", headers, proxy))
        val technologies = search.select("input.erj-filter[data-param=search_technology]")
        if (technologies.isEmpty()) throw SourceSchemaException("euremotejobs search has no technology filters")
        val technology =
            technologies.map { it.attr("value") }.firstOrNull { it.equals(category, ignoreCase = true) }
                ?: return advanceCategory(categoryIndex, context.criteria.categories.size)
        val nonce =
            search.select("script").mapNotNull { PUBLIC_NONCE.find(it.data())?.groupValues?.get(1) }.singleOrNull()
                ?: throw SourceSchemaException("euremotejobs search has no public form nonce")
        val form =
            mapOf(
                "action" to "erj_ajax_search",
                "nonce" to nonce,
                "page" to page.toString(),
                "website" to "",
                "search_technology[]" to technology,
            ).entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val root =
            objectMapper.readSourceTree(
                httpClient.post("$endpoint/wp-admin/admin-ajax.php", form, headers + FORM_HEADER, proxy),
                source.id,
            )
        if (root.get("success")?.takeIf(JsonNode::isBoolean)?.booleanValue() != true) {
            throw SourceSchemaException("euremotejobs public search failed")
        }
        val data = root.requiredObject("data", source.id)
        val html =
            data.get("html")?.takeIf(JsonNode::isTextual)?.textValue()
                ?: throw SourceSchemaException("euremotejobs search response has no HTML")
        val hasMore =
            data.get("has_more")?.takeIf(JsonNode::isBoolean)?.booleanValue()
                ?: throw SourceSchemaException("euremotejobs search response has invalid pagination")
        val cards = Jsoup.parse(html).select("a.job-card-link")
        if (cards.size > PAGE_SIZE || cards.isEmpty() && (hasMore || html.isNotBlank() && !ZERO_RESULTS.containsMatchIn(html))) {
            throw SourceSchemaException("euremotejobs search response has invalid cards")
        }
        if (hasMore && page >= properties.maxPages) {
            throw SourceSchemaException("euremotejobs pagination exceeds configured maxPages=${properties.maxPages}")
        }
        val listings = cards.map { it to detailUrl(it.attr("href"), endpoint) }
        val fingerprint =
            MessageDigest
                .getInstance("SHA-256")
                .digest(listings.joinToString("\n") { (_, url) -> url }.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        if (cards.isNotEmpty() && fingerprint == context.checkpoint[PAGE_FINGERPRINT]) {
            throw SourceSchemaException("euremotejobs public search repeated a page")
        }
        val jobs =
            listings
                .distinctBy { (_, url) -> url }
                .filter { (card) -> acceptsCardDate(card, context.since) }
                .map { (card, url) -> mapListing(card, url, category, headers, proxy) }
                .filter { publicationWindow.accepts(it.publishedAt, context.since) }
        if (!hasMore) return advanceCategory(categoryIndex, context.criteria.categories.size, jobs, cards.size)
        return ScrapePage(
            jobs = jobs,
            checkpoint = mapOf(CATEGORY_INDEX to categoryIndex.toString(), PAGE to (page + 1).toString(), PAGE_FINGERPRINT to fingerprint),
            complete = false,
            fetchedCount = cards.size,
        )
    }

    private fun acceptsCardDate(
        card: Element,
        since: Instant?,
    ): Boolean {
        if (since == null) return true
        val date = runCatching { LocalDate.parse(card.selectFirst("time")?.attr("datetime")) }.getOrNull() ?: return true
        return !date.plusDays(1).isBefore(since.atOffset(ZoneOffset.UTC).toLocalDate())
    }

    private fun mapListing(
        card: Element,
        url: String,
        category: String,
        headers: Map<String, String>,
        proxy: ProxyEndpoint,
    ): ScrapedJob {
        val document = Jsoup.parse(httpClient.get(url, headers, proxy))
        val posting =
            document
                .select("script[type=application/ld+json]")
                .flatMap { jobPostings(objectMapper.readSourceTree(it.data(), source.id)) }
                .singleOrNull() ?: throw SourceSchemaException("euremotejobs detail has no unique JobPosting")
        val publishedAt = posting.optionalText("datePosted", source.id)
        return ScrapedJob(
            title = posting.requiredText("title", source.id),
            company = posting.path("hiringOrganization").optionalText("name", source.id),
            url = url,
            description = posting.requiredText("description", source.id),
            source = source,
            salary = card.selectFirst(".meta-salary")?.text()?.takeIf(String::isNotBlank),
            location = card.selectFirst(".meta-location")?.text()?.takeIf(String::isNotBlank),
            remote = posting.optionalText("jobLocationType", source.id)?.equals("TELECOMMUTE", ignoreCase = true),
            publishedAt = publishedAt?.let { runCatching { OffsetDateTime.parse(it).toInstant().toString() }.getOrDefault(it) },
            rawData = mapOf("datePosted" to publishedAt, "technology" to category),
            category = category.lowercase(),
        )
    }

    private fun jobPostings(node: JsonNode): List<JsonNode> =
        when {
            node.isArray -> node.flatMap(::jobPostings)
            node.path("@type").asText() == "JobPosting" -> listOf(node)
            node.path("@graph").isArray -> node.path("@graph").flatMap(::jobPostings)
            else -> emptyList()
        }

    private fun detailUrl(
        value: String,
        endpoint: String,
    ): String {
        val base = URI(endpoint)
        val uri = runCatching { base.resolve(value).normalize() }.getOrNull()
        if (
            uri == null ||
            uri.scheme != base.scheme ||
            uri.host != base.host ||
            uri.port != base.port ||
            uri.userInfo != null ||
            !uri.path.startsWith("/job/")
        ) {
            throw SourceSchemaException("euremotejobs card has an invalid source URL")
        }
        return uri.toString()
    }

    private fun advanceCategory(
        categoryIndex: Int,
        categoryCount: Int,
        jobs: List<ScrapedJob> = emptyList(),
        fetchedCount: Int = 0,
    ): ScrapePage {
        val next = categoryIndex + 1
        return ScrapePage(
            jobs = jobs,
            checkpoint = mapOf(CATEGORY_INDEX to next.toString(), PAGE to "1"),
            complete = next >= categoryCount,
            fetchedCount = fetchedCount,
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val DEFAULT_ENDPOINT = "https://euremotejobs.com"
        const val CATEGORY_INDEX = "categoryIndex"
        const val PAGE = "page"
        const val PAGE_FINGERPRINT = "pageFingerprint"
        const val PAGE_SIZE = 100
        val HEADERS = mapOf("User-Agent" to "JobHunter/1.0 (+https://github.com/mshykhov-space/job-hunter)")
        val FORM_HEADER = mapOf("Content-Type" to "application/x-www-form-urlencoded")
        val PUBLIC_NONCE = Regex("""\bvar\s+ERJ\s*=\s*\{.*?\bnonce\s*:\s*['"]([^'"]+)['"]""", RegexOption.DOT_MATCHES_ALL)
        val ZERO_RESULTS = Regex("No job listings found", RegexOption.IGNORE_CASE)
    }
}
