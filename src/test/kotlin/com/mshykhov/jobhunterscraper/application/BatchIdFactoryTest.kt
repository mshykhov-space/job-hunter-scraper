package com.mshykhov.jobhunterscraper.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.application.model.ScrapedJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class BatchIdFactoryTest {
    private val factory = BatchIdFactory(jacksonObjectMapper())
    private val runId = UUID.randomUUID()
    private val jobs =
        listOf(
            ScrapedJob(
                title = "Engineer",
                url = "https://example.test/job",
                source = JobSource.DOU,
                rawData = mapOf("nested" to mapOf("b" to 2, "a" to 1)),
                category = "JAVA",
            ),
        )

    @Test
    fun `same canonical request is stable across factory instances`() {
        val first = factory.create(runId, mapOf("page" to "1"), mapOf("page" to "2"), 0, jobs, 5)
        val second = BatchIdFactory(jacksonObjectMapper()).create(runId, mapOf("page" to "1"), mapOf("page" to "2"), 0, jobs, 5)

        assertEquals(first, second)
    }

    @Test
    fun `checkpoint and fetched count participate in request identity`() {
        val baseline = factory.create(runId, mapOf("page" to "1"), mapOf("page" to "2"), 0, jobs, 5)

        assertNotEquals(baseline, factory.create(runId, mapOf("page" to "1"), mapOf("page" to "3"), 0, jobs, 5))
        assertNotEquals(baseline, factory.create(runId, mapOf("page" to "1"), mapOf("page" to "2"), 0, jobs, 6))
    }
}
