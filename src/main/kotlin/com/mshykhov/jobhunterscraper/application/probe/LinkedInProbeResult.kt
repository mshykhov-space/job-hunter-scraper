package com.mshykhov.jobhunterscraper.application.probe

data class LinkedInProbeResult(
    val requested: LinkedInProbeRequest,
    val outcome: String,
    val returnedCount: Int,
    val samples: List<LinkedInProbeSample>,
    val error: String? = null,
    val httpStatus: Int? = null,
)
