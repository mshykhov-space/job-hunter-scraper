package com.mshykhov.jobhunterscraper.infrastructure.source

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunterscraper.application.SourceAdapter
import com.mshykhov.jobhunterscraper.application.SourceSchemaException
import com.mshykhov.jobhunterscraper.application.model.JobCheckRequest
import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.application.model.ScrapeContext
import com.mshykhov.jobhunterscraper.application.model.ScrapePage
import com.mshykhov.jobhunterscraper.application.model.ScrapedJob
import com.mshykhov.jobhunterscraper.application.model.SourceProxy
import com.mshykhov.jobhunterscraper.infrastructure.api.JobHunterClient
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import com.mshykhov.jobhunterscraper.infrastructure.http.SourceHttpClient
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.HexFormat

@Component
class LinkedInAdapter(
    private val httpClient: SourceHttpClient,
    private val objectMapper: ObjectMapper,
    private val jobHunterClient: JobHunterClient,
    private val properties: ScraperProperties,
    private val clock: Clock,
) : SourceAdapter {
    override val source = JobSource.LINKEDIN

    override fun fetch(context: ScrapeContext): ScrapePage {
        if (context.criteria.categories.isEmpty()) return ScrapePage(emptyList(), emptyMap(), complete = true)
        val locations = context.criteria.locations.ifEmpty { listOf("") }
        val position = position(context, locations)
        val category = context.criteria.categories[position.categoryIndex]
        val location = locations[position.locationIndex]
        val endpoint = properties.endpoint(source, DEFAULT_ENDPOINT).removeSuffix("/")
        val processedHashes = processedHashes(context)
        val searchUrl =
            "$endpoint/jobs?${searchParameters(category, location, context.criteria.remoteOnly, hoursOld(context), position.offset)}"
        val searchJobs = parseSearch(httpClient.get(searchUrl))
        if (searchJobs.size > PAGE_SIZE) {
            throw SourceSchemaException("LinkedIn search returned more than the requested $PAGE_SIZE results")
        }

        val candidates =
            searchJobs
                .map { parseCandidate(it, category) }
                .distinctBy(ScrapedJob::url)
                .filterNot { hash(it.url) in processedHashes }
        val enrichment = enrich(endpoint, candidates)
        if (enrichment.hasRemaining) {
            val nextProcessed = (processedHashes + enrichment.processedHashes).sorted()
            return ScrapePage(
                enrichment.jobs,
                mapOf(
                    CHECKPOINT_CATEGORY to position.categoryIndex.toString(),
                    CHECKPOINT_LOCATION to position.locationIndex.toString(),
                    CHECKPOINT_OFFSET to position.offset.toString(),
                    CHECKPOINT_PROCESSED to nextProcessed.joinToString(","),
                ),
                complete = false,
                fetchedCount = searchJobs.size,
            )
        }
        return nextPage(context, position, locations.size, enrichment.jobs, searchJobs.size)
    }

    private fun searchParameters(
        category: String,
        location: String,
        remoteOnly: Boolean,
        hoursOld: Int,
        offset: Int,
    ): String =
        listOf(
            "site" to "linkedin",
            "search_term" to category,
            "location" to location,
            "is_remote" to remoteOnly.toString(),
            "results_wanted" to PAGE_SIZE.toString(),
            "offset" to offset.toString(),
            "hours_old" to hoursOld.toString(),
            "description_format" to "markdown",
            "linkedin_fetch_description" to "false",
        ).joinToString("&") { (key, value) -> "$key=${encode(value)}" }

    private fun parseSearch(body: String): List<JsonNode> {
        val root =
            try {
                objectMapper.readTree(body)
            } catch (error: Exception) {
                throw SourceSchemaException("LinkedIn JobSpy search response is invalid: ${error.message}")
            }
        if (!root.isArray) throw SourceSchemaException("LinkedIn JobSpy search response is not an array")
        return root.toList()
    }

    private fun parseCandidate(
        node: JsonNode,
        category: String,
    ): ScrapedJob {
        val title = node.path("title").asText().trim()
        val url = node.path("job_url").asText().trim()
        if (title.isBlank() || url.isBlank()) throw SourceSchemaException("LinkedIn search job is missing title or URL")
        val min =
            node
                .path("min_amount")
                .takeUnless(JsonNode::isMissingNode)
                ?.takeUnless(JsonNode::isNull)
                ?.asText()
        val max =
            node
                .path("max_amount")
                .takeUnless(JsonNode::isMissingNode)
                ?.takeUnless(JsonNode::isNull)
                ?.asText()
        val currency =
            node
                .path("currency")
                .asText()
                .trim()
                .takeIf(String::isNotBlank)
        val currencySuffix = currency?.let { " $it" }.orEmpty()
        val salary =
            when {
                min != null && max != null -> "$min-$max$currencySuffix"
                min != null -> "$min+$currencySuffix"
                else -> null
            }
        return ScrapedJob(
            title = title,
            company =
                node
                    .path("company")
                    .asText()
                    .trim()
                    .takeIf(String::isNotBlank),
            url = url,
            source = source,
            salary = salary,
            location =
                node
                    .path("location")
                    .asText()
                    .trim()
                    .takeIf(String::isNotBlank),
            remote = node.path("is_remote").takeIf(JsonNode::isBoolean)?.booleanValue(),
            publishedAt =
                node
                    .path("date_posted")
                    .asText()
                    .trim()
                    .takeIf(String::isNotBlank),
            rawData = objectMapper.convertValue(node, MAP_TYPE),
            category = category,
        )
    }

    private fun enrich(
        endpoint: String,
        candidates: List<ScrapedJob>,
    ): Enrichment {
        if (candidates.isEmpty()) return Enrichment(emptyList(), hasRemaining = false, processedHashes = emptySet())
        val check =
            jobHunterClient.checkJobs(
                candidates.map {
                    JobCheckRequest(it.url, it.title, it.company, it.salary, it.location, it.publishedAt)
                },
            )
        val checkedUrls = (check.newUrls + check.updatedUrls + check.unchangedUrls).toSet()
        if (checkedUrls != candidates.map(ScrapedJob::url).toSet()) {
            throw SourceSchemaException("LinkedIn check response does not cover every candidate")
        }
        val selectedUrls = (check.newUrls + check.updatedUrls).toSet()
        val selected = candidates.filter { it.url in selectedUrls }
        val unchangedHashes = check.unchangedUrls.map(::hash).toSet()
        if (selected.isEmpty()) return Enrichment(emptyList(), hasRemaining = false, processedHashes = unchangedHashes)
        val proxies = jobHunterClient.proxies(source)
        if (proxies.isEmpty()) throw SourceSchemaException("LinkedIn enrichment requires at least one proxy")
        val chunk = selected.take(minOf(proxies.size, MAX_ENRICHMENT_CHUNK))

        val response =
            httpClient.post(
                "$endpoint/jobs/enrich",
                mapOf(
                    "jobs" to
                        chunk.map {
                            mapOf(
                                "url" to it.url,
                                "job_id" to jobId(it.url),
                                "date_posted" to (it.publishedAt ?: ""),
                            )
                        },
                    "proxies" to proxies.map(::proxyBody),
                    "delay_min" to 7,
                    "delay_max" to 15,
                ),
            )
        return Enrichment(
            jobs = mergeEnrichment(chunk, response),
            hasRemaining = selected.size > chunk.size,
            processedHashes = unchangedHashes + chunk.map { hash(it.url) },
        )
    }

    private fun mergeEnrichment(
        selected: List<ScrapedJob>,
        body: String,
    ): List<ScrapedJob> {
        val root =
            try {
                objectMapper.readTree(body)
            } catch (error: Exception) {
                throw SourceSchemaException("LinkedIn enrichment response is invalid: ${error.message}")
            }
        val results = root.path("results")
        if (!results.isArray) throw SourceSchemaException("LinkedIn enrichment response has no results array")
        val byUrl = results.associateBy { it.path("url").asText() }
        val missing = selected.map(ScrapedJob::url).filterNot(byUrl::containsKey)
        if (missing.isNotEmpty()) throw SourceSchemaException("LinkedIn enrichment response omitted requested jobs")
        val retryable =
            selected.mapNotNull { job ->
                byUrl
                    .getValue(job.url)
                    .path("status")
                    .asText()
                    .takeIf { it !in TERMINAL_ENRICHMENT_STATUSES }
            }
        if (retryable.isNotEmpty()) {
            throw SourceSchemaException("LinkedIn enrichment contains retryable failures")
        }

        return selected.map { job ->
            val result = byUrl.getValue(job.url)
            val status = result.path("status").asText().ifBlank { "unknown" }
            val data = result.path("data")
            if (status != "success" || !data.isObject) {
                return@map job.copy(
                    rawData =
                        job.rawData +
                            mapOf(
                                "enrichmentStatus" to status,
                                "enrichmentError" to result.path("error").asText().takeIf(String::isNotBlank),
                            ),
                )
            }
            val rawData =
                job.rawData +
                    objectMapper.convertValue<Map<String, Any?>>(data, MAP_TYPE) +
                    mapOf("enrichmentStatus" to status)
            job.copy(
                company =
                    data
                        .path("company_name")
                        .asText()
                        .trim()
                        .takeIf(String::isNotBlank) ?: job.company,
                description = data.path("description").asText(),
                salary =
                    data
                        .path("salary")
                        .asText()
                        .trim()
                        .takeIf(String::isNotBlank) ?: job.salary,
                location =
                    data
                        .path("location")
                        .asText()
                        .trim()
                        .takeIf(String::isNotBlank) ?: job.location,
                publishedAt =
                    data
                        .path("published_at")
                        .asText()
                        .trim()
                        .takeIf(String::isNotBlank) ?: job.publishedAt,
                rawData = rawData,
            )
        }
    }

    private fun position(
        context: ScrapeContext,
        locations: List<String>,
    ): Position {
        val categoryIndex = context.checkpoint[CHECKPOINT_CATEGORY]?.toIntOrNull() ?: 0
        val locationIndex = context.checkpoint[CHECKPOINT_LOCATION]?.toIntOrNull() ?: 0
        val offset = context.checkpoint[CHECKPOINT_OFFSET]?.toIntOrNull() ?: 0
        if (categoryIndex !in context.criteria.categories.indices || locationIndex !in locations.indices) {
            throw SourceSchemaException("Invalid LinkedIn checkpoint")
        }
        if (offset < 0 || offset % PAGE_SIZE != 0 || offset / PAGE_SIZE >= searchPageLimit()) {
            throw SourceSchemaException("Invalid LinkedIn offset checkpoint")
        }
        return Position(categoryIndex, locationIndex, offset)
    }

    private fun processedHashes(context: ScrapeContext): Set<String> {
        val raw = context.checkpoint[CHECKPOINT_PROCESSED].orEmpty()
        if (raw.isBlank()) return emptySet()
        val hashes = raw.split(',').toSet()
        if (hashes.size > PAGE_SIZE || hashes.any { !PROCESSED_HASH.matches(it) }) {
            throw SourceSchemaException("Invalid LinkedIn processed checkpoint")
        }
        return hashes
    }

    private fun nextPage(
        context: ScrapeContext,
        position: Position,
        locationCount: Int,
        jobs: List<ScrapedJob>,
        fetchedCount: Int,
    ): ScrapePage {
        if (fetchedCount == PAGE_SIZE) {
            val nextPage = position.offset / PAGE_SIZE + 1
            if (nextPage >= searchPageLimit()) {
                throw SourceSchemaException("LinkedIn search reached the ${searchPageLimit() * PAGE_SIZE} result coverage cap")
            }
            return ScrapePage(
                jobs,
                mapOf(
                    CHECKPOINT_CATEGORY to position.categoryIndex.toString(),
                    CHECKPOINT_LOCATION to position.locationIndex.toString(),
                    CHECKPOINT_OFFSET to (position.offset + PAGE_SIZE).toString(),
                ),
                complete = false,
                fetchedCount = fetchedCount,
            )
        }
        val nextLocation = position.locationIndex + 1
        val nextCategory = if (nextLocation >= locationCount) position.categoryIndex + 1 else position.categoryIndex
        val complete = nextCategory >= context.criteria.categories.size
        val checkpoint =
            when {
                complete -> emptyMap()
                nextLocation < locationCount ->
                    mapOf(
                        CHECKPOINT_CATEGORY to position.categoryIndex.toString(),
                        CHECKPOINT_LOCATION to nextLocation.toString(),
                    )
                else -> mapOf(CHECKPOINT_CATEGORY to nextCategory.toString(), CHECKPOINT_LOCATION to "0")
            }
        return ScrapePage(jobs, checkpoint, complete, fetchedCount)
    }

    private fun proxyBody(proxy: SourceProxy): Map<String, Any> =
        mapOf(
            "url" to proxy.url,
            "fingerprint" to proxy.fingerprint,
        )

    private fun jobId(url: String): String = runCatching { URI(url).path.trimEnd('/').substringAfterLast('/') }.getOrDefault("")

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun hash(url: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.toByteArray(StandardCharsets.UTF_8)), 0, HASH_BYTES)

    private fun hoursOld(context: ScrapeContext): Int {
        val since = context.since ?: return MIN_HOURS_OLD
        val elapsed = Duration.between(since, clock.instant())
        val hours = Math.ceilDiv(elapsed.seconds.coerceAtLeast(0), SECONDS_PER_HOUR).coerceAtLeast(MIN_HOURS_OLD.toLong())
        if (hours > MAX_HOURS_OLD) {
            throw SourceSchemaException("LinkedIn lookback exceeds the supported $MAX_HOURS_OLD hours")
        }
        return hours.toInt()
    }

    private fun searchPageLimit(): Int = minOf(properties.maxPages, LINKEDIN_OFFSET_LIMIT / PAGE_SIZE)

    private data class Position(
        val categoryIndex: Int,
        val locationIndex: Int,
        val offset: Int,
    )

    private data class Enrichment(
        val jobs: List<ScrapedJob>,
        val hasRemaining: Boolean,
        val processedHashes: Set<String>,
    )

    private companion object {
        const val DEFAULT_ENDPOINT = "http://127.0.0.1:8000"
        const val CHECKPOINT_CATEGORY = "categoryIndex"
        const val CHECKPOINT_LOCATION = "locationIndex"
        const val CHECKPOINT_OFFSET = "offset"
        const val CHECKPOINT_PROCESSED = "processed"
        const val PAGE_SIZE = 100
        const val LINKEDIN_OFFSET_LIMIT = 1000
        const val MAX_ENRICHMENT_CHUNK = 20
        const val MIN_HOURS_OLD = 24
        const val MAX_HOURS_OLD = 24L * 30
        const val SECONDS_PER_HOUR = 3600L
        const val HASH_BYTES = 16
        val PROCESSED_HASH = Regex("[0-9a-f]{32}")
        val TERMINAL_ENRICHMENT_STATUSES = setOf("success", "no_data", "not_found")
        val MAP_TYPE = object : TypeReference<Map<String, Any?>>() {}
    }
}
