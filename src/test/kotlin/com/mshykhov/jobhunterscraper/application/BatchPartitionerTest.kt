package com.mshykhov.jobhunterscraper.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.application.model.RunClaim
import com.mshykhov.jobhunterscraper.application.model.ScrapePage
import com.mshykhov.jobhunterscraper.application.model.ScrapedJob
import com.mshykhov.jobhunterscraper.application.model.SearchCriteria
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class BatchPartitionerTest {
    @Test
    fun `splits a page by serialized request bytes`() {
        val partitioner = BatchPartitioner(jacksonObjectMapper(), ScraperProperties(maxBatchBytes = 900))
        val page = ScrapePage(listOf(job(1, 400), job(2, 400)), emptyMap(), complete = true)

        val chunks = partitioner.partition(claim(), emptyMap(), page)

        assertEquals(listOf(1, 1), chunks.map(List<*>::size))
    }

    @Test
    fun `rejects a single job larger than the API request cap`() {
        val partitioner = BatchPartitioner(jacksonObjectMapper(), ScraperProperties(maxBatchBytes = 500))
        val page = ScrapePage(listOf(job(1, 1000)), emptyMap(), complete = true)

        assertThrows(ScrapeRunException::class.java) { partitioner.partition(claim(), emptyMap(), page) }
    }

    private fun claim() =
        RunClaim(
            UUID.randomUUID(),
            UUID.randomUUID(),
            JobSource.DOU,
            SearchCriteria(listOf("JAVA")),
            emptyMap(),
            null,
            Instant.now().plusSeconds(300),
        )

    private fun job(
        index: Int,
        descriptionSize: Int,
    ) = ScrapedJob(
        title = "Job $index",
        url = "https://example.test/$index",
        description = "x".repeat(descriptionSize),
        source = JobSource.DOU,
        category = "JAVA",
    )
}
