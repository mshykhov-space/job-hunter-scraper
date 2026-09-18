package com.mshykhov.jobhunterscraper.infrastructure.source

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunterscraper.application.SourceSchemaException
import org.jsoup.Jsoup

internal fun ObjectMapper.readSourceTree(
    raw: String,
    source: String,
): JsonNode =
    try {
        readTree(raw) ?: throw SourceSchemaException("$source returned an empty response")
    } catch (exception: SourceSchemaException) {
        throw exception
    } catch (exception: Exception) {
        throw SourceSchemaException("$source returned invalid JSON")
    }

internal fun JsonNode.requiredObject(
    field: String,
    source: String,
): JsonNode =
    get(field)?.takeIf(JsonNode::isObject)
        ?: throw SourceSchemaException("$source response is missing object '$field'")

internal fun JsonNode.requiredArray(
    field: String,
    source: String,
): JsonNode =
    get(field)?.takeIf(JsonNode::isArray)
        ?: throw SourceSchemaException("$source response is missing array '$field'")

internal fun JsonNode.requiredText(
    field: String,
    source: String,
): String =
    get(field)?.takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank)
        ?: throw SourceSchemaException("$source response is missing text '$field'")

internal fun JsonNode.optionalText(
    field: String,
    source: String,
): String? {
    val value = get(field) ?: return null
    if (value.isNull) return null
    if (!value.isTextual) throw SourceSchemaException("$source response has invalid text '$field'")
    return value.asText().takeIf(String::isNotBlank)
}

internal fun JsonNode.requiredInt(
    field: String,
    source: String,
): Int =
    get(field)?.takeIf(JsonNode::isIntegralNumber)?.intValue()
        ?: throw SourceSchemaException("$source response is missing integer '$field'")

internal fun matchCategory(
    categories: List<String>,
    values: Iterable<String>,
): String? {
    val haystack = values.joinToString(" ")
    return categories
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .sortedByDescending(String::length)
        .firstOrNull { category ->
            Regex(
                "(?<![\\p{L}\\p{N}])${Regex.escape(category)}(?![\\p{L}\\p{N}])",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(haystack)
        }?.lowercase()
}

internal fun htmlToText(value: String?): String = value?.let { Jsoup.parse(it).text() }.orEmpty().trim()

internal fun JsonNode.decimalText(): String? =
    takeIf(JsonNode::isNumber)
        ?.decimalValue()
        ?.stripTrailingZeros()
        ?.toPlainString()

internal fun salaryRange(
    currency: String,
    from: JsonNode?,
    to: JsonNode?,
    unit: String,
    type: String? = null,
): String? {
    val fromValue = from?.decimalText()
    val toValue = to?.decimalText()
    if (fromValue == null && toValue == null) return null
    val range =
        when {
            fromValue != null && toValue != null -> "$fromValue–$toValue"
            toValue != null -> "up to $toValue"
            else -> "from $fromValue"
        }
    val suffix = type?.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()
    return "${currency.uppercase()} $range/${unit.lowercase()}$suffix"
}

internal fun requirePageWithinCap(
    page: Int,
    maxPages: Int,
    source: String,
) {
    if (page !in 1..maxPages) {
        throw SourceSchemaException("$source pagination exceeds configured maxPages=$maxPages")
    }
}
