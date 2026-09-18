package com.mshykhov.jobhunterscraper.application.model

data class JobCheckRequest(
    val url: String,
    val title: String? = null,
    val company: String? = null,
    val salary: String? = null,
    val location: String? = null,
    val publishedAt: String? = null,
)
