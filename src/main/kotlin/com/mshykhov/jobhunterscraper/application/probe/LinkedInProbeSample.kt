package com.mshykhov.jobhunterscraper.application.probe

data class LinkedInProbeSample(
    val title: String,
    val company: String?,
    val url: String?,
    val location: String?,
    val remote: Boolean?,
    val publishedAt: String?,
)
