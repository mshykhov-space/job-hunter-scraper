package com.mshykhov.jobhunterscraper.application.model

data class ScrapePage(
    val jobs: List<ScrapedJob>,
    val checkpoint: Map<String, String>,
    val complete: Boolean,
    val fetchedCount: Int = jobs.size,
)
