package com.mshykhov.jobhunterscraper.application.model

data class ScrapedJob(
    val title: String,
    val company: String? = null,
    val url: String,
    val description: String = "",
    val source: JobSource,
    val salary: String? = null,
    val location: String? = null,
    val remote: Boolean? = null,
    val publishedAt: String? = null,
    val rawData: Map<String, Any?> = emptyMap(),
    val category: String,
)
