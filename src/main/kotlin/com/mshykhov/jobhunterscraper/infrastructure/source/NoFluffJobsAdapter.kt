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
import java.time.Instant

@Component
class NoFluffJobsAdapter(
    private val httpClient: SourceHttpClient,
    private val objectMapper: ObjectMapper,
    private val properties: ScraperProperties,
) : SourceAdapter {
    override val source = JobSource.NOFLUFFJOBS

    override fun fetch(context: ScrapeContext): ScrapePage {
        val page = context.checkpoint[CHECKPOINT]?.toIntOrNull() ?: 1
        requirePageWithinCap(page, properties.maxPages, source.id)

        val root =
            objectMapper.readSourceTree(
                httpClient.post(
                    searchUrl(page),
                    mapOf(
                        "criteriaSearch" to
                            mapOf(
                                "category" to listOf("backend"),
                                "seniority" to listOf("senior"),
                            ),
                        "page" to page,
                    ),
                ),
                source.id,
            )
        if (!root.isObject) throw SourceSchemaException("nofluffjobs response must be an object")
        val postings = root.requiredArray("postings", source.id)
        val totalPages = root.requiredInt("totalPages", source.id)
        val totalCount = root.requiredInt("totalCount", source.id)
        if (totalPages < 0 || totalCount < 0) {
            throw SourceSchemaException("nofluffjobs response has invalid pagination metadata")
        }
        if (totalPages > properties.maxPages) {
            throw SourceSchemaException("nofluffjobs pagination exceeds configured maxPages=${properties.maxPages}")
        }

        val seenRemote = mutableSetOf<String>()
        val jobs =
            postings.mapNotNull { posting ->
                mapPosting(posting, context)?.takeIf { job ->
                    job.remote != true || seenRemote.add("${job.title.lowercase()}|${job.company?.lowercase().orEmpty()}")
                }
            }
        val complete = page >= totalPages
        return ScrapePage(
            jobs = jobs,
            checkpoint = mapOf(CHECKPOINT to if (complete) page.toString() else (page + 1).toString()),
            complete = complete,
            fetchedCount = postings.size(),
        )
    }

    private fun mapPosting(
        posting: JsonNode,
        context: ScrapeContext,
    ): ScrapedJob? {
        if (!posting.isObject) throw SourceSchemaException("nofluffjobs posting must be an object")
        val title = posting.requiredText("title", source.id)
        val slug = posting.requiredText("url", source.id)
        val technology = posting.optionalText("technology", source.id)
        val tileValues = parseTileValues(posting)
        val category = matchCategory(context.criteria.categories, listOfNotNull(title, technology) + tileValues) ?: return null
        val locationNode = posting.get("location")
        if (locationNode != null && !locationNode.isNull && !locationNode.isObject) {
            throw SourceSchemaException("nofluffjobs response has invalid object 'location'")
        }
        val remote =
            locationNode?.get("fullyRemote")?.let { value ->
                if (!value.isBoolean) throw SourceSchemaException("nofluffjobs response has invalid boolean 'fullyRemote'")
                value.booleanValue()
            }
        if (context.criteria.remoteOnly && remote != true) return null
        val detail = objectMapper.readSourceTree(httpClient.get(detailUrl(slug)), source.id)
        val description = composeDescription(detail)

        return ScrapedJob(
            title = title,
            company = posting.optionalText("name", source.id),
            url = "https://nofluffjobs.com/job/$slug",
            description = description,
            source = source,
            salary = parseSalary(posting.get("salary")),
            location = parseLocation(locationNode, remote),
            remote = remote,
            publishedAt = parsePublishedAt(posting.get("posted")),
            rawData =
                mapOf(
                    "id" to posting.optionalText("id", source.id),
                    "technology" to technology,
                    "seniority" to parseTextArray(posting.get("seniority"), "seniority"),
                    "help4Ua" to
                        posting.get("help4Ua")?.takeUnless(JsonNode::isNull)?.let {
                            objectMapper.convertValue(it, Any::class.java)
                        },
                ),
            category = category,
        )
    }

    private fun parseTileValues(posting: JsonNode): List<String> {
        val tiles = posting.get("tiles") ?: return emptyList()
        if (!tiles.isObject) throw SourceSchemaException("nofluffjobs response has invalid object 'tiles'")
        val values = tiles.get("values") ?: return emptyList()
        if (!values.isArray) throw SourceSchemaException("nofluffjobs response has invalid array 'values'")
        return values.map { tile ->
            if (!tile.isObject) throw SourceSchemaException("nofluffjobs tile must be an object")
            tile.requiredText("value", source.id)
        }
    }

    private fun parseSalary(value: JsonNode?): String? {
        if (value == null || value.isNull) return null
        if (!value.isObject) throw SourceSchemaException("nofluffjobs response has invalid object 'salary'")
        return salaryRange(
            currency = value.requiredText("currency", source.id),
            from = value.get("from"),
            to = value.get("to"),
            unit = value.optionalText("period", source.id) ?: "month",
            type = value.optionalText("type", source.id),
        )
    }

    private fun parseLocation(
        location: JsonNode?,
        remote: Boolean?,
    ): String? {
        if (remote == true) return "Remote"
        if (location == null || location.isNull) return null
        val places = location.get("places") ?: return null
        if (!places.isArray) throw SourceSchemaException("nofluffjobs response has invalid array 'places'")
        return places
            .map { place ->
                if (!place.isObject) throw SourceSchemaException("nofluffjobs place must be an object")
                place.optionalText("city", source.id)
            }.filterNotNull()
            .filterNot { it.equals("Remote", ignoreCase = true) }
            .joinToString(", ")
            .takeIf(String::isNotBlank)
    }

    private fun parsePublishedAt(value: JsonNode?): String? {
        if (value == null || value.isNull) return null
        if (!value.isIntegralNumber) throw SourceSchemaException("nofluffjobs response has invalid timestamp 'posted'")
        return Instant.ofEpochMilli(value.longValue()).toString()
    }

    private fun composeDescription(detail: JsonNode): String {
        if (!detail.isObject) throw SourceSchemaException("nofluffjobs detail response must be an object")
        val specs = detail.requiredObject("specs", source.id)
        val requirements = detail.requiredObject("requirements", source.id)
        val tasks = parseTextArray(specs.get("dailyTasks"), "dailyTasks").map(::htmlToText).filter(String::isNotBlank)
        val musts = parseRequirementValues(requirements.get("musts"), "musts")
        val nices = parseRequirementValues(requirements.get("nices"), "nices")
        val parts = mutableListOf<String>()
        if (tasks.isNotEmpty()) parts += "Daily tasks:\n" + tasks.joinToString("\n") { "- $it" }
        htmlToText(requirements.optionalText("description", source.id)).takeIf(String::isNotBlank)?.let {
            parts += "Requirements:\n$it"
        }
        if (musts.isNotEmpty()) parts += "Must have: ${musts.joinToString(", ")}"
        if (nices.isNotEmpty()) parts += "Nice to have: ${nices.joinToString(", ")}"
        return parts.joinToString("\n\n")
    }

    private fun parseTextArray(
        value: JsonNode?,
        field: String,
    ): List<String> {
        if (value == null || value.isNull) return emptyList()
        if (!value.isArray) throw SourceSchemaException("nofluffjobs response has invalid array '$field'")
        return value.map { entry ->
            if (!entry.isTextual) throw SourceSchemaException("nofluffjobs $field entry must be text")
            entry.asText()
        }
    }

    private fun parseRequirementValues(
        value: JsonNode?,
        field: String,
    ): List<String> {
        if (value == null || value.isNull) return emptyList()
        if (!value.isArray) throw SourceSchemaException("nofluffjobs response has invalid array '$field'")
        return value.map { entry ->
            if (!entry.isObject) throw SourceSchemaException("nofluffjobs $field entry must be an object")
            entry.requiredText("value", source.id)
        }
    }

    private fun searchUrl(page: Int): String {
        val endpoint = properties.endpoint(source, DEFAULT_ENDPOINT).trimEnd('/')
        return "$endpoint/search/posting?region=pl&page=$page&salaryCurrency=PLN&salaryPeriod=month&language=en"
    }

    private fun detailUrl(slug: String): String = "${properties.endpoint(source, DEFAULT_ENDPOINT).trimEnd('/')}/posting/$slug"

    private companion object {
        const val DEFAULT_ENDPOINT = "https://nofluffjobs.com/api"
        const val CHECKPOINT = "page"
    }
}
