package com.mshykhov.jobhunterscraper.infrastructure.source

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunterscraper.application.SourceAdapter
import com.mshykhov.jobhunterscraper.application.SourceSchemaException
import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.application.model.ScrapeContext
import com.mshykhov.jobhunterscraper.application.model.ScrapePage
import com.mshykhov.jobhunterscraper.application.model.ScrapedJob
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import com.mshykhov.jobhunterscraper.infrastructure.http.SourceHttpClient
import org.springframework.stereotype.Component

@Component
class LandingJobsAdapter(
    private val httpClient: SourceHttpClient,
    private val objectMapper: ObjectMapper,
    private val properties: ScraperProperties,
) : SourceAdapter {
    override val source = JobSource.LANDINGJOBS

    override fun fetch(context: ScrapeContext): ScrapePage {
        val offset = context.checkpoint[CHECKPOINT]?.toIntOrNull() ?: 0
        if (offset < 0 || offset % PAGE_SIZE != 0) {
            throw SourceSchemaException("landingjobs checkpoint has invalid offset")
        }
        val pageNumber = offset / PAGE_SIZE + 1
        requirePageWithinCap(pageNumber, properties.maxPages, source.id)
        val root = objectMapper.readSourceTree(httpClient.get(buildUrl(offset)), source.id)
        if (!root.isArray) throw SourceSchemaException("landingjobs response must be an array")
        if (root.size() > PAGE_SIZE) throw SourceSchemaException("landingjobs page exceeds $PAGE_SIZE items")

        val jobs = root.mapNotNull { mapOffer(it, context) }
        val hasNext = root.size() == PAGE_SIZE
        if (hasNext && pageNumber >= properties.maxPages) {
            throw SourceSchemaException("landingjobs pagination exceeds configured maxPages=${properties.maxPages}")
        }
        return ScrapePage(
            jobs = jobs,
            checkpoint = mapOf(CHECKPOINT to if (hasNext) (offset + PAGE_SIZE).toString() else offset.toString()),
            complete = !hasNext,
            fetchedCount = root.size(),
        )
    }

    private fun mapOffer(
        offer: JsonNode,
        context: ScrapeContext,
    ): ScrapedJob? {
        if (!offer.isObject) throw SourceSchemaException("landingjobs offer must be an object")
        val title = offer.requiredText("title", source.id)
        val url = offer.requiredText("url", source.id)
        val publishedAt = offer.requiredText("published_at", source.id)
        val tags =
            offer.requiredArray("tags", source.id).map { tag ->
                if (!tag.isTextual) throw SourceSchemaException("landingjobs tag must be text")
                tag.asText()
            }
        val locations = offer.requiredArray("locations", source.id)
        val category = matchCategory(context.criteria.categories, tags) ?: return null
        val remote =
            offer.get("remote")?.let { value ->
                if (!value.isBoolean) throw SourceSchemaException("landingjobs response has invalid boolean 'remote'")
                value.booleanValue()
            }
        if (context.criteria.remoteOnly && remote == false) return null
        val location =
            if (remote == true) {
                "Remote"
            } else {
                locations
                    .map { entry ->
                        if (!entry.isObject) throw SourceSchemaException("landingjobs location must be an object")
                        entry.optionalText("city", source.id) ?: entry.optionalText("country_code", source.id)
                    }.filterNotNull()
                    .joinToString(", ")
                    .takeIf(String::isNotBlank)
            }
        val from = optionalNumber(offer, "gross_salary_low")
        val to = optionalNumber(offer, "gross_salary_high")
        val salary =
            if (from != null || to != null) {
                salaryRange(
                    currency = offer.optionalText("currency_code", source.id) ?: "EUR",
                    from = from,
                    to = to,
                    unit = "year",
                )
            } else {
                null
            }
        val description =
            listOf(
                htmlToText(offer.optionalText("role_description", source.id)),
                htmlToText(offer.optionalText("main_requirements", source.id)),
            ).filter(String::isNotBlank).joinToString("\n\n")

        return ScrapedJob(
            title = title,
            company = companyFromUrl(url),
            url = url,
            description = description,
            source = source,
            salary = salary,
            location = location,
            remote = remote,
            publishedAt = publishedAt,
            rawData =
                mapOf(
                    "id" to offer.get("id")?.takeIf(JsonNode::isNumber)?.numberValue(),
                    "tags" to tags,
                    "type" to offer.optionalText("type", source.id),
                ),
            category = category,
        )
    }

    private fun optionalNumber(
        node: JsonNode,
        field: String,
    ): JsonNode? {
        val value = node.get(field) ?: return null
        if (value.isNull) return null
        if (!value.isNumber) throw SourceSchemaException("landingjobs response has invalid number '$field'")
        return value
    }

    private fun companyFromUrl(url: String): String? =
        COMPANY_PATH
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.replace('-', ' ')

    private fun buildUrl(offset: Int): String {
        val endpoint = properties.endpoint(source, DEFAULT_ENDPOINT)
        val separator = if ('?' in endpoint) '&' else '?'
        return "$endpoint${separator}limit=$PAGE_SIZE&offset=$offset"
    }

    private companion object {
        const val DEFAULT_ENDPOINT = "https://landing.jobs/api/v1/jobs"
        const val CHECKPOINT = "offset"
        const val PAGE_SIZE = 50
        val COMPANY_PATH = Regex("https?://(?:www\\.)?landing\\.jobs/at/([^/]+)/", RegexOption.IGNORE_CASE)
    }
}
