package com.mshykhov.jobhunterscraper.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunterscraper.application.model.RunClaim
import com.mshykhov.jobhunterscraper.application.model.ScrapePage
import com.mshykhov.jobhunterscraper.application.model.ScrapedJob
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class BatchPartitioner(
    private val objectMapper: ObjectMapper,
    private val properties: ScraperProperties,
) {
    fun partition(
        claim: RunClaim,
        previousCheckpoint: Map<String, String>,
        page: ScrapePage,
    ): List<List<ScrapedJob>> {
        if (page.jobs.isEmpty()) return listOf(emptyList())
        val sizingCheckpoint =
            listOf(previousCheckpoint, page.checkpoint)
                .maxBy { objectMapper.writeValueAsBytes(it).size }
        val chunks = mutableListOf<List<ScrapedJob>>()
        var current = mutableListOf<ScrapedJob>()
        var currentJobsBytes = 0
        val envelopeBytes = size(claim, emptyList(), sizingCheckpoint, page.fetchedCount)
        page.jobs.forEach { job ->
            val jobBytes = objectMapper.writeValueAsBytes(job).size
            val candidateBytes = envelopeBytes + currentJobsBytes + jobBytes + if (current.isEmpty()) 0 else 1
            if (current.isNotEmpty() && (current.size == MAX_BATCH_SIZE || candidateBytes > properties.maxBatchBytes)) {
                chunks += current
                current = mutableListOf(job)
                currentJobsBytes = jobBytes
            } else {
                current += job
                currentJobsBytes += jobBytes + if (current.size == 1) 0 else 1
            }
            if (envelopeBytes + currentJobsBytes > properties.maxBatchBytes) {
                throw ScrapeRunException("A job exceeds the configured batch request size")
            }
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    private fun size(
        claim: RunClaim,
        jobs: List<ScrapedJob>,
        checkpoint: Map<String, String>,
        fetchedCount: Int,
    ): Int =
        objectMapper
            .writeValueAsBytes(
                mapOf(
                    "leaseToken" to claim.leaseToken,
                    "batchId" to PLACEHOLDER_BATCH_ID,
                    "jobs" to jobs,
                    "checkpoint" to checkpoint,
                    "fetchedCount" to fetchedCount,
                ),
            ).size

    private companion object {
        val PLACEHOLDER_BATCH_ID: UUID = UUID(0, 0)
        const val MAX_BATCH_SIZE = 250
    }
}
