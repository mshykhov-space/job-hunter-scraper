package com.mshykhov.jobhunterscraper.infrastructure.source

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunterscraper.application.SourceAdapter
import com.mshykhov.jobhunterscraper.application.SourceSchemaException
import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.application.model.ScrapeContext
import com.mshykhov.jobhunterscraper.application.model.ScrapePage
import com.mshykhov.jobhunterscraper.application.model.ScrapedJob
import com.mshykhov.jobhunterscraper.infrastructure.api.JobHunterClient
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import com.mshykhov.jobhunterscraper.infrastructure.http.SourceHttpClient
import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import java.net.URI
import java.util.Locale

@Component
class Web3CareerAdapter(
    private val httpClient: SourceHttpClient,
    private val objectMapper: ObjectMapper,
    private val jobHunterClient: JobHunterClient,
    private val properties: ScraperProperties,
) : SourceAdapter {
    override val source = JobSource.WEB3CAREER

    override fun fetch(context: ScrapeContext): ScrapePage {
        if (context.criteria.categories.isEmpty()) return ScrapePage(emptyList(), emptyMap(), complete = true)
        val position = position(context)
        val category = context.criteria.categories[position.categoryIndex]
        val slug = category.lowercase(Locale.ROOT).trim().replace(WHITESPACE, "-")
        val remoteSegment = if (context.criteria.remoteOnly) "+remote" else ""
        val endpoint = properties.endpoint(source, DEFAULT_ENDPOINT).removeSuffix("/")
        val pageQuery = if (position.page == 1) "" else "?page=${position.page}"
        val url = "$endpoint/$slug$remoteSegment-jobs$pageQuery"
        val proxy = jobHunterClient.proxies(source).firstOrNull()
        val headers = DEFAULT_HEADERS + (proxy?.fingerprint ?: emptyMap())
        val document = Jsoup.parse(httpClient.get(url, headers, proxy?.endpoint()))
        val scripts = document.select("script[type=application/ld+json]")
        if (scripts.isEmpty()) throw SourceSchemaException("Web3Career response has no JSON-LD")

        val postings =
            scripts
                .flatMap { script -> parseJsonLd(script.data().ifBlank { script.html() }) }
                .filter { it.path("@type").asText() == "JobPosting" }
        if (postings.isEmpty() && !ZERO_RESULTS.containsMatchIn(document.text())) {
            throw SourceSchemaException("Web3Career response has no JobPosting records")
        }
        val paths =
            document
                .select("a[href]")
                .map { it.attr("href") }
                .filter(JOB_PATH::matches)
                .distinctBy { JOB_PATH.matchEntire(it)!!.groupValues[1] }
        if (postings.size > paths.size) {
            throw SourceSchemaException("Web3Career JSON-LD jobs do not match listing URLs")
        }
        val jobs = postings.mapIndexed { index, posting -> parsePosting(posting, paths[index], category) }

        val hasNext = document.selectFirst("a[rel=next], a[aria-label=next]") != null
        if (hasNext && position.page >= properties.maxPages) {
            throw SourceSchemaException("Web3Career pagination exceeds ${properties.maxPages} pages")
        }
        return page(context, position, jobs, hasNext)
    }

    private fun parseJsonLd(value: String): List<JsonNode> {
        val root =
            try {
                objectMapper.readTree(value)
            } catch (error: Exception) {
                throw SourceSchemaException("Web3Career JSON-LD is invalid: ${error.message}")
            }
        return if (root.isArray) root.toList() else listOf(root)
    }

    private fun parsePosting(
        posting: JsonNode,
        path: String,
        category: String,
    ): ScrapedJob {
        val title = posting.path("title").asText().trim()
        if (title.isBlank()) throw SourceSchemaException("Web3Career job is missing title")
        val locationType =
            posting
                .path("jobLocationType")
                .asText()
                .trim()
                .takeIf(String::isNotBlank)
        return ScrapedJob(
            title = title,
            company =
                posting
                    .path("hiringOrganization")
                    .path("name")
                    .asText()
                    .trim()
                    .takeIf(String::isNotBlank),
            url = URI(DEFAULT_ENDPOINT).resolve(path).toString(),
            description = posting.path("description").asText(),
            source = source,
            salary = salary(posting),
            location = locations(posting.path("applicantLocationRequirements")).takeIf(String::isNotBlank),
            remote = locationType?.equals("TELECOMMUTE", ignoreCase = true),
            publishedAt =
                posting
                    .path("datePosted")
                    .asText()
                    .trim()
                    .takeIf(String::isNotBlank),
            rawData = objectMapper.convertValue(posting, MAP_TYPE),
            category = category,
        )
    }

    private fun salary(posting: JsonNode): String? {
        val baseSalary = posting.path("baseSalary")
        val value = baseSalary.path("value")
        if (!value.isObject) return null
        val min =
            value
                .path("minValue")
                .asText()
                .trim()
                .takeIf(String::isNotBlank)
        val max =
            value
                .path("maxValue")
                .asText()
                .trim()
                .takeIf(String::isNotBlank)
        if (min == null && max == null) return null
        val amount = listOfNotNull(min, max).joinToString("-")
        val currency =
            baseSalary
                .path("currency")
                .asText()
                .trim()
                .ifBlank { "USD" }
        val unit =
            value
                .path("unitText")
                .asText()
                .trim()
                .ifBlank { "YEAR" }
        return "$amount $currency/$unit"
    }

    private fun locations(node: JsonNode): String =
        when {
            node.isArray ->
                node
                    .mapNotNull {
                        it
                            .path("name")
                            .asText()
                            .trim()
                            .takeIf(String::isNotBlank)
                    }.joinToString(", ")
            node.isObject -> node.path("name").asText().trim()
            else -> ""
        }

    private fun position(context: ScrapeContext): Position {
        val categoryIndex = context.checkpoint[CHECKPOINT_CATEGORY]?.toIntOrNull() ?: 0
        val page = context.checkpoint[CHECKPOINT_PAGE]?.toIntOrNull() ?: 1
        if (categoryIndex !in context.criteria.categories.indices || page !in 1..properties.maxPages) {
            throw SourceSchemaException("Invalid Web3Career checkpoint")
        }
        return Position(categoryIndex, page)
    }

    private fun page(
        context: ScrapeContext,
        position: Position,
        jobs: List<ScrapedJob>,
        hasNext: Boolean,
    ): ScrapePage {
        if (hasNext) {
            return ScrapePage(
                jobs,
                mapOf(CHECKPOINT_CATEGORY to position.categoryIndex.toString(), CHECKPOINT_PAGE to (position.page + 1).toString()),
                complete = false,
            )
        }
        val nextCategory = position.categoryIndex + 1
        return ScrapePage(
            jobs,
            if (nextCategory < context.criteria.categories.size) {
                mapOf(CHECKPOINT_CATEGORY to nextCategory.toString(), CHECKPOINT_PAGE to "1")
            } else {
                emptyMap()
            },
            complete = nextCategory >= context.criteria.categories.size,
        )
    }

    private data class Position(
        val categoryIndex: Int,
        val page: Int,
    )

    private companion object {
        const val DEFAULT_ENDPOINT = "https://web3.career"
        const val CHECKPOINT_CATEGORY = "categoryIndex"
        const val CHECKPOINT_PAGE = "page"
        val JOB_PATH = Regex("/[\\w][\\w.-]*(?:-[\\w.-]+)*/(\\d+)")
        val ZERO_RESULTS = Regex("\\b0 jobs? found\\b", RegexOption.IGNORE_CASE)
        val WHITESPACE = Regex("\\s+")
        val MAP_TYPE = object : TypeReference<Map<String, Any?>>() {}
        val DEFAULT_HEADERS =
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
                "Accept-Language" to "en-US,en;q=0.9",
            )
    }
}
