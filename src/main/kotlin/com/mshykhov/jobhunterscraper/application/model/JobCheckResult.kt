package com.mshykhov.jobhunterscraper.application.model

data class JobCheckResult(
    val newUrls: List<String>,
    val updatedUrls: List<String>,
    val unchangedUrls: List<String>,
)
