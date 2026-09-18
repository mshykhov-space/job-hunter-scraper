package com.mshykhov.jobhunterscraper.infrastructure.source

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunterscraper.application.PublicationWindow
import com.mshykhov.jobhunterscraper.application.SourceAdapter
import com.mshykhov.jobhunterscraper.application.SourceSchemaException
import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.application.model.ScrapeContext
import com.mshykhov.jobhunterscraper.application.model.ScrapePage
import com.mshykhov.jobhunterscraper.application.model.ScrapedJob
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import com.mshykhov.jobhunterscraper.infrastructure.http.SourceHttpClient
import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime

@Component
class DjinniAdapter(
    private val httpClient: SourceHttpClient,
    private val objectMapper: ObjectMapper,
    private val properties: ScraperProperties,
    private val publicationWindow: PublicationWindow,
) : SourceAdapter {
    override val source = JobSource.DJINNI

    override fun fetch(context: ScrapeContext): ScrapePage {
        if (context.criteria.categories.isEmpty()) return ScrapePage(emptyList(), emptyMap(), complete = true)
        val position = position(context)
        val category = context.criteria.categories[position.categoryIndex]
        val endpoint = properties.endpoint(source, DEFAULT_ENDPOINT)
        val url =
            "$endpoint?primary_keyword=${URLEncoder.encode(category, StandardCharsets.UTF_8)}" +
                "&page=${position.page}"
        val document = Jsoup.parse(httpClient.get(url))
        val scripts = document.select("script[type=application/ld+json]")
        if (scripts.isEmpty()) throw SourceSchemaException("Djinni response has no JSON-LD")

        val postings =
            scripts
                .flatMap { script -> parseJsonLd(script.data().ifBlank { script.html() }) }
                .filter { it.path("@type").asText() == "JobPosting" }
        val urls =
            document
                .select("a[href]")
                .map { it.attr("href") }
                .filter { JOB_PATH.matches(it) }
                .distinctBy { JOB_PATH.matchEntire(it)!!.groupValues[1] }
        if (postings.size > urls.size && postings.any { it.path("url").asText().isBlank() }) {
            throw SourceSchemaException("Djinni JSON-LD jobs do not match listing URLs")
        }

        val jobs =
            postings
                .mapIndexed { index, posting -> parsePosting(posting, urls.getOrNull(index), category) }
                .filter { publicationWindow.accepts(windowTimestamp(it.publishedAt), context.since) }
        val hasNext = document.selectFirst("a[rel=next], a[aria-label=next]") != null
        if (hasNext && position.page >= properties.maxPages) {
            throw SourceSchemaException("Djinni pagination exceeds ${properties.maxPages} pages")
        }
        return page(context, position, jobs, hasNext, postings.size)
    }

    private fun parseJsonLd(value: String): List<JsonNode> {
        val root =
            try {
                objectMapper.readTree(value)
            } catch (error: Exception) {
                throw SourceSchemaException("Djinni JSON-LD is invalid: ${error.message}")
            }
        return if (root.isArray) root.toList() else listOf(root)
    }

    private fun parsePosting(
        posting: JsonNode,
        listedPath: String?,
        category: String,
    ): ScrapedJob {
        val title = posting.path("title").asText().trim()
        val structuredUrl =
            posting
                .path("url")
                .asText()
                .trim()
                .takeIf(String::isNotBlank)
        val url = structuredUrl ?: listedPath?.let { URI(DEFAULT_ORIGIN).resolve(it).toString() }
        if (title.isBlank() || url.isNullOrBlank()) {
            throw SourceSchemaException("Djinni job is missing title or URL")
        }
        val locationType =
            posting
                .path("jobLocationType")
                .asText()
                .trim()
                .takeIf(String::isNotBlank)
        val location =
            posting
                .path("applicantLocationRequirements")
                .path("name")
                .asText()
                .trim()
                .takeIf(String::isNotBlank)
                ?: posting
                    .path("jobLocation")
                    .path("address")
                    .path("addressLocality")
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
            url = url,
            description = posting.path("description").asText(),
            source = source,
            location = location,
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

    private fun position(context: ScrapeContext): Position {
        val categoryIndex = context.checkpoint[CHECKPOINT_CATEGORY]?.toIntOrNull() ?: 0
        val page = context.checkpoint[CHECKPOINT_PAGE]?.toIntOrNull() ?: 1
        if (categoryIndex !in context.criteria.categories.indices || page !in 1..properties.maxPages) {
            throw SourceSchemaException("Invalid Djinni checkpoint")
        }
        return Position(categoryIndex, page)
    }

    private fun windowTimestamp(value: String?): String? =
        value?.let {
            runCatching { LocalDateTime.parse(it).toLocalDate().toString() }.getOrDefault(it)
        }

    private fun page(
        context: ScrapeContext,
        position: Position,
        jobs: List<ScrapedJob>,
        hasNext: Boolean,
        fetchedCount: Int,
    ): ScrapePage {
        if (hasNext) {
            return ScrapePage(
                jobs,
                mapOf(CHECKPOINT_CATEGORY to position.categoryIndex.toString(), CHECKPOINT_PAGE to (position.page + 1).toString()),
                complete = false,
                fetchedCount = fetchedCount,
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
            fetchedCount = fetchedCount,
        )
    }

    private data class Position(
        val categoryIndex: Int,
        val page: Int,
    )

    private companion object {
        const val DEFAULT_ENDPOINT = "https://djinni.co/jobs/"
        const val DEFAULT_ORIGIN = "https://djinni.co"
        const val CHECKPOINT_CATEGORY = "categoryIndex"
        const val CHECKPOINT_PAGE = "page"
        val JOB_PATH = Regex("/jobs/(\\d+)-[^/]+/")
        val MAP_TYPE = object : TypeReference<Map<String, Any?>>() {}
    }
}
