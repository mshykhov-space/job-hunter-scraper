package com.mshykhov.jobhunterscraper.application.model

import java.time.Instant

data class ScrapeContext(
    val criteria: SearchCriteria,
    val checkpoint: Map<String, String> = emptyMap(),
    val since: Instant? = null,
)
