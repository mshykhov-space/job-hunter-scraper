package com.mshykhov.jobhunterscraper.infrastructure.api

import com.mshykhov.jobhunterscraper.application.model.JobCheckRequest
import com.mshykhov.jobhunterscraper.application.model.JobCheckResult
import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.application.model.RunClaim
import com.mshykhov.jobhunterscraper.application.model.ScrapedJob
import com.mshykhov.jobhunterscraper.application.model.SourceProxy
import java.util.UUID

interface JobHunterClient {
    fun claim(source: JobSource): RunClaim?

    fun heartbeat(claim: RunClaim)

    fun batch(
        claim: RunClaim,
        batchId: UUID,
        jobs: List<ScrapedJob>,
        checkpoint: Map<String, String>,
        fetchedCount: Int,
    ): Int

    fun complete(claim: RunClaim)

    fun fail(
        claim: RunClaim,
        reason: String,
    )

    fun proxies(source: JobSource): List<SourceProxy>

    fun checkJobs(jobs: List<JobCheckRequest>): JobCheckResult
}
