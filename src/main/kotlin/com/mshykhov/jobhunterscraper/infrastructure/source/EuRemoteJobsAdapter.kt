package com.mshykhov.jobhunterscraper.infrastructure.source

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
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Component
class EuRemoteJobsAdapter(
    private val httpClient: SourceHttpClient,
    private val objectMapper: ObjectMapper,
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
        if (category.isEmpty()) return advanceEmptyCategory(categoryIndex, context.criteria.categories.size)
        val tagId =
            resolveTagId(category)
                ?: return advanceEmptyCategory(categoryIndex, context.criteria.categories.size)

        val root = objectMapper.readSourceTree(httpClient.get(listingsUrl(tagId, page, context)), source.id)
        if (!root.isObject) throw SourceSchemaException("euremotejobs listings response must be an object")
        val status = root.requiredInt("status", source.id)
        if (status !in 200..299) throw SourceSchemaException("euremotejobs listings returned status $status")
        val headers = root.requiredObject("headers", source.id)
        val totalPages = headers.requiredInt("X-WP-TotalPages", source.id)
        if (totalPages < 0) throw SourceSchemaException("euremotejobs response has invalid total pages")
        if (totalPages > properties.maxPages) {
            throw SourceSchemaException("euremotejobs pagination exceeds configured maxPages=${properties.maxPages}")
        }
        val body = root.requiredArray("body", source.id)
        if (body.size() > PAGE_SIZE) throw SourceSchemaException("euremotejobs page exceeds $PAGE_SIZE items")
        if (page > totalPages && body.size() > 0) {
            throw SourceSchemaException("euremotejobs response page exceeds reported total pages")
        }

        val jobs = body.mapNotNull { mapListing(it, category, tagId, context) }
        val categoryComplete = page >= totalPages
        val nextCategoryIndex = if (categoryComplete) categoryIndex + 1 else categoryIndex
        val complete = nextCategoryIndex >= context.criteria.categories.size
        return ScrapePage(
            jobs = jobs,
            checkpoint =
                mapOf(
                    CATEGORY_INDEX to nextCategoryIndex.toString(),
                    PAGE to if (categoryComplete) "1" else (page + 1).toString(),
                ),
            complete = complete,
            fetchedCount = body.size(),
        )
    }

    private fun resolveTagId(category: String): Int? {
        val root = objectMapper.readSourceTree(httpClient.get(tagsUrl(category)), source.id)
        if (!root.isArray) throw SourceSchemaException("euremotejobs tag response must be an array")
        return root
            .map { tag ->
                if (!tag.isObject) throw SourceSchemaException("euremotejobs tag must be an object")
                tag.requiredInt("id", source.id) to tag.requiredText("name", source.id)
            }.firstOrNull { (_, name) -> name.trim().equals(category, ignoreCase = true) }
            ?.first
    }

    private fun mapListing(
        listing: JsonNode,
        category: String,
        tagId: Int,
        context: ScrapeContext,
    ): ScrapedJob? {
        if (!listing.isObject) throw SourceSchemaException("euremotejobs listing must be an object")
        val publishedAt =
            listing.optionalText("date_gmt", source.id)?.let { "${it}Z" }
                ?: listing.optionalText("date", source.id)
        if (!publicationWindow.accepts(publishedAt, context.since)) return null
        val title = listing.requiredObject("title", source.id).requiredText("rendered", source.id)
        val content = listing.requiredObject("content", source.id).requiredText("rendered", source.id)
        val meta = listing.requiredObject("meta", source.id)
        val terms = listing.requiredObject("_embedded", source.id).requiredArray("wp:term", source.id)
        val flattenedTerms =
            terms.flatMap { group ->
                if (!group.isArray) throw SourceSchemaException("euremotejobs term group must be an array")
                group.map { term ->
                    if (!term.isObject) throw SourceSchemaException("euremotejobs term must be an object")
                    term
                }
            }
        val regions =
            flattenedTerms
                .filter { it.requiredText("taxonomy", source.id) == "job_listing_region" }
                .map { it.requiredText("name", source.id) }

        return ScrapedJob(
            title = htmlToText(title),
            company = meta.optionalText("_company_name", source.id),
            url = listing.requiredText("link", source.id),
            description = content,
            source = source,
            location = regions.joinToString(", ").takeIf(String::isNotBlank),
            remote = true,
            publishedAt = publishedAt,
            rawData =
                mapOf(
                    "id" to listing.get("id")?.takeIf(JsonNode::isIntegralNumber)?.longValue(),
                    "tagId" to tagId,
                ),
            category = category.lowercase(),
        )
    }

    private fun advanceEmptyCategory(
        categoryIndex: Int,
        categoryCount: Int,
    ): ScrapePage {
        val next = categoryIndex + 1
        return ScrapePage(
            jobs = emptyList(),
            checkpoint = mapOf(CATEGORY_INDEX to next.toString(), PAGE to "1"),
            complete = next >= categoryCount,
            fetchedCount = 0,
        )
    }

    private fun tagsUrl(category: String): String = "${baseUrl()}/job_listing_tag?search=${encode(category)}&per_page=$TAG_SEARCH_SIZE"

    private fun listingsUrl(
        tagId: Int,
        page: Int,
        context: ScrapeContext,
    ): String {
        val after = context.since?.let { "&after=${encode(it.toString())}" }.orEmpty()
        return "${baseUrl()}/job-listings?per_page=$PAGE_SIZE&page=$page&_embed=1&_envelope=1" +
            "&job_listing_tag=$tagId&orderby=date&order=desc$after"
    }

    private fun baseUrl(): String = properties.endpoint(source, DEFAULT_ENDPOINT).trimEnd('/')

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val DEFAULT_ENDPOINT = "https://euremotejobs.com/wp-json/wp/v2"
        const val CATEGORY_INDEX = "categoryIndex"
        const val PAGE = "page"
        const val PAGE_SIZE = 100
        const val TAG_SEARCH_SIZE = 100
    }
}
