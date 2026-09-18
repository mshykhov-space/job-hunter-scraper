package com.mshykhov.jobhunterscraper.infrastructure.source

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
import org.jsoup.parser.Parser
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Component
class DouAdapter(
    private val httpClient: SourceHttpClient,
    private val properties: ScraperProperties,
    private val publicationWindow: PublicationWindow,
) : SourceAdapter {
    override val source = JobSource.DOU

    override fun fetch(context: ScrapeContext): ScrapePage {
        if (context.criteria.categories.isEmpty()) return ScrapePage(emptyList(), emptyMap(), complete = true)

        val categoryIndex = context.checkpoint[CHECKPOINT_CATEGORY]?.toIntOrNull() ?: 0
        if (categoryIndex !in context.criteria.categories.indices) {
            throw SourceSchemaException("Invalid DOU category checkpoint")
        }
        val category = context.criteria.categories[categoryIndex]
        val url =
            "${properties.endpoint(source, DEFAULT_ENDPOINT)}?category=" +
                URLEncoder.encode(category, StandardCharsets.UTF_8)
        val document = Jsoup.parse(httpClient.get(url, BROWSER_HEADERS), "", Parser.xmlParser())
        val channel =
            document.selectFirst("rss > channel")
                ?: throw SourceSchemaException("DOU response is not an RSS channel")

        val items = channel.select("item")
        val jobs =
            items.map { item ->
                val rawTitle =
                    item
                        .selectFirst("title")
                        ?.text()
                        ?.trim()
                        .orEmpty()
                val rawUrl =
                    item
                        .selectFirst("link")
                        ?.text()
                        ?.trim()
                        .orEmpty()
                if (rawTitle.isBlank() || rawUrl.isBlank()) {
                    throw SourceSchemaException("DOU RSS item is missing title or link")
                }
                val parsedTitle = parseTitle(rawTitle)
                val description = item.selectFirst("description")?.text().orEmpty()
                val publishedAt = normalizePublishedAt(item.selectFirst("pubDate")?.text()?.takeIf(String::isNotBlank))
                ScrapedJob(
                    title = parsedTitle.title,
                    company = parsedTitle.company,
                    url = rawUrl.replace(UTM_SUFFIX, ""),
                    description = description,
                    source = source,
                    salary = parsedTitle.salary,
                    location = parsedTitle.location,
                    remote = parsedTitle.remote,
                    publishedAt = publishedAt,
                    rawData =
                        mapOf(
                            "title" to rawTitle,
                            "link" to rawUrl,
                            "description" to description,
                            "pubDate" to publishedAt,
                        ),
                    category = category,
                )
            }.filter { publicationWindow.accepts(it.publishedAt, context.since) }

        val nextCategory = categoryIndex + 1
        return ScrapePage(
            jobs = jobs,
            checkpoint =
                if (nextCategory <
                    context.criteria.categories.size
                ) {
                    mapOf(CHECKPOINT_CATEGORY to nextCategory.toString())
                } else {
                    emptyMap()
                },
            complete = nextCategory >= context.criteria.categories.size,
            fetchedCount = items.size,
        )
    }

    private fun parseTitle(rawTitle: String): ParsedTitle {
        val delimiterIndex = rawTitle.indexOf(" в ")
        if (delimiterIndex < 0) return ParsedTitle(rawTitle, null, null, null, null)

        val title = rawTitle.substring(0, delimiterIndex).trim()
        val remainder = rawTitle.substring(delimiterIndex + 3).trim()
        val suffixMatch = COMPANY_SUFFIX.find(remainder)
        val companyEnd = suffixMatch?.range?.last?.plus(1) ?: remainder.indexOf(',').takeIf { it >= 0 } ?: remainder.length
        val company = remainder.substring(0, companyEnd).trim().takeIf(String::isNotBlank)
        val details = remainder.substring(companyEnd).removePrefix(",").trim()
        var salary: String? = null
        var remote: Boolean? = null
        val locations = mutableListOf<String>()
        details.split(',').map(String::trim).filter(String::isNotBlank).forEach { detail ->
            when {
                SALARY.containsMatchIn(detail) -> salary = detail
                REMOTE.containsMatchIn(detail) -> remote = true
                ABROAD.containsMatchIn(detail) -> Unit
                else -> locations.add(detail)
            }
        }
        return ParsedTitle(title, company, salary, locations.joinToString(", ").takeIf(String::isNotBlank), remote)
    }

    private fun normalizePublishedAt(value: String?): String? =
        value?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString() }.getOrDefault(it)
        }

    private data class ParsedTitle(
        val title: String,
        val company: String?,
        val salary: String?,
        val location: String?,
        val remote: Boolean?,
    )

    private companion object {
        const val DEFAULT_ENDPOINT = "https://jobs.dou.ua/vacancies/feeds/"
        const val CHECKPOINT_CATEGORY = "categoryIndex"
        const val UTM_SUFFIX = "?utm_source=jobsrss"
        val COMPANY_SUFFIX =
            Regex(
                ",\\s*(?:Inc\\.|Ltd\\.|LLC|GmbH|d\\.o\\.o\\.|S\\.A\\.|s\\.r\\.o\\.|AB|BV|PLC|Corp\\.|Co\\.|Group|Limited)",
                RegexOption.IGNORE_CASE,
            )
        val SALARY = Regex("(?:від|до)\\s*\\$[\\d\\s]|^\\$[\\d]", RegexOption.IGNORE_CASE)
        val REMOTE = Regex("віддалено|remote", RegexOption.IGNORE_CASE)
        val ABROAD = Regex("за кордоном", RegexOption.IGNORE_CASE)
        val BROWSER_HEADERS = mapOf("User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131 Safari/537.36")
    }
}
