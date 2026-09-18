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
class JustJoinItAdapter(
    private val httpClient: SourceHttpClient,
    private val objectMapper: ObjectMapper,
    private val properties: ScraperProperties,
) : SourceAdapter {
    override val source = JobSource.JUSTJOINIT

    override fun fetch(context: ScrapeContext): ScrapePage {
        val offset = context.checkpoint[CHECKPOINT]?.toIntOrNull() ?: 0
        if (offset < 0 || offset % PAGE_SIZE != 0) {
            throw SourceSchemaException("justjoinit checkpoint has invalid offset")
        }
        val pageNumber = offset / PAGE_SIZE + 1
        requirePageWithinCap(pageNumber, properties.maxPages, source.id)

        val root = objectMapper.readSourceTree(httpClient.get(buildUrl(offset)), source.id)
        if (!root.isObject) throw SourceSchemaException("justjoinit response must be an object")
        val data = root.requiredArray("data", source.id)
        if (data.size() > PAGE_SIZE) throw SourceSchemaException("justjoinit page exceeds $PAGE_SIZE items")
        val meta = root.requiredObject("meta", source.id)
        if (meta.requiredInt("from", source.id) != offset) {
            throw SourceSchemaException("justjoinit response cursor does not match request")
        }
        meta.requiredInt("totalItems", source.id)
        val next = meta.requiredObject("next", source.id)
        val nextItems = next.requiredInt("itemsCount", source.id)
        val nextCursor =
            next
                .get("cursor")
                ?.takeUnless(JsonNode::isNull)
                ?.takeIf(JsonNode::isIntegralNumber)
                ?.intValue()
        if (nextItems > 0 && (data.isEmpty || nextCursor == null || nextCursor <= offset)) {
            throw SourceSchemaException("justjoinit response has an invalid next cursor")
        }

        val jobs = data.mapNotNull { mapOffer(it, context) }
        val hasNext = nextItems > 0
        if (hasNext && pageNumber >= properties.maxPages) {
            throw SourceSchemaException("justjoinit pagination exceeds configured maxPages=${properties.maxPages}")
        }
        return ScrapePage(
            jobs = jobs,
            checkpoint = mapOf(CHECKPOINT to (nextCursor ?: offset).toString()),
            complete = !hasNext,
            fetchedCount = data.size(),
        )
    }

    private fun mapOffer(
        offer: JsonNode,
        context: ScrapeContext,
    ): ScrapedJob? {
        if (!offer.isObject) throw SourceSchemaException("justjoinit offer must be an object")
        val title = offer.requiredText("title", source.id)
        val slug = offer.requiredText("slug", source.id)
        val workplaceType = offer.requiredText("workplaceType", source.id)
        val experienceLevel = offer.requiredText("experienceLevel", source.id)
        val publishedAt = offer.requiredText("publishedAt", source.id)
        val skills =
            offer.requiredArray("requiredSkills", source.id).map { skill ->
                if (!skill.isObject) throw SourceSchemaException("justjoinit skill must be an object")
                skill.requiredText("name", source.id)
            }
        val employmentTypes = offer.requiredArray("employmentTypes", source.id)
        if (workplaceType != "remote") return null
        val category = matchCategory(context.criteria.categories, listOf(title) + skills) ?: return null

        val originalSalary =
            employmentTypes.firstOrNull { employment ->
                if (!employment.isObject) throw SourceSchemaException("justjoinit employment type must be an object")
                employment.optionalText("currencySource", source.id) == "original"
            }
        val salary =
            originalSalary?.let { employment ->
                salaryRange(
                    currency = employment.requiredText("currency", source.id),
                    from = employment.get("from"),
                    to = employment.get("to"),
                    unit = employment.requiredText("unit", source.id),
                )
            }
        return ScrapedJob(
            title = title,
            company = offer.optionalText("companyName", source.id),
            url = "https://justjoin.it/job-offer/$slug",
            source = source,
            salary = salary,
            location = "Remote",
            remote = true,
            publishedAt = publishedAt,
            rawData =
                mapOf(
                    "guid" to offer.optionalText("guid", source.id),
                    "experienceLevel" to experienceLevel,
                    "category" to
                        offer
                            .get("category")
                            ?.get("key")
                            ?.takeIf(JsonNode::isTextual)
                            ?.asText(),
                ),
            category = category,
        )
    }

    private fun buildUrl(offset: Int): String =
        properties.endpoint(source, DEFAULT_ENDPOINT).trimEnd('?') +
            "?from=$offset&itemsCount=$PAGE_SIZE" +
            "&experienceLevels=senior&experienceLevels=manager" +
            "&sortBy=publishedAt&orderBy=descending"

    private companion object {
        const val DEFAULT_ENDPOINT = "https://justjoin.it/api/candidate-api/offers"
        const val CHECKPOINT = "from"
        const val PAGE_SIZE = 100
    }
}
