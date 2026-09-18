package com.mshykhov.jobhunterscraper.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mshykhov.jobhunterscraper.application.model.JobCheckRequest
import com.mshykhov.jobhunterscraper.application.model.JobCheckResult
import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.application.model.RunClaim
import com.mshykhov.jobhunterscraper.application.model.ScrapeContext
import com.mshykhov.jobhunterscraper.application.model.ScrapePage
import com.mshykhov.jobhunterscraper.application.model.ScrapedJob
import com.mshykhov.jobhunterscraper.application.model.SearchCriteria
import com.mshykhov.jobhunterscraper.application.model.SourceProxy
import com.mshykhov.jobhunterscraper.infrastructure.api.JobHunterClient
import com.mshykhov.jobhunterscraper.infrastructure.api.LeaseLostException
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors

class SourceRunWorkerTest {
    private val heartbeatExecutor = Executors.newScheduledThreadPool(1)

    @AfterEach
    fun tearDown() {
        heartbeatExecutor.shutdownNow()
    }

    @Test
    fun `splits pages and advances checkpoint only on final acknowledged chunk`() {
        val jobs = (1..260).map(::job)
        val adapter = FakeAdapter { ScrapePage(jobs, mapOf("page" to "2"), complete = true, fetchedCount = 300) }
        val client = FakeClient(claim())
        val worker = worker(adapter, client)

        worker.poll(JobSource.DOU)

        assertEquals(listOf(250, 10), client.batches.map { it.jobs.size })
        assertEquals(listOf(mapOf("page" to "1"), mapOf("page" to "2")), client.batches.map { it.checkpoint })
        assertEquals(listOf(0, 300), client.batches.map { it.fetchedCount })
        assertEquals(
            2,
            client.batches
                .map { it.batchId }
                .distinct()
                .size,
        )
        assertEquals(1, client.completed)
        assertEquals(0, client.failed)
    }

    @Test
    fun `heartbeat lease loss during fetch fences batch and failure writes`() {
        val registry = observationRegistry()
        val adapter =
            FakeAdapter {
                Thread.sleep(60)
                assertEquals("scrape.page", registry.currentObservation?.contextView?.name)
                ScrapePage(listOf(job(1)), emptyMap(), complete = true)
            }
        val client = FakeClient(claim())
        client.onHeartbeat = {
            client.heartbeatObservation = registry.currentObservation
            throw LeaseLostException(HttpStatus.CONFLICT, "heartbeat")
        }
        val worker = worker(adapter, client, registry, heartbeatInterval = Duration.ofMillis(5))

        worker.poll(JobSource.DOU)

        assertTrue(client.heartbeats > 0)
        assertNotNull(client.heartbeatObservation)
        assertEquals("scrape.run", client.heartbeatObservation?.contextView?.name)
        assertTrue(client.batches.isEmpty())
        assertEquals(0, client.failed)
        assertEquals(0, client.completed)
    }

    @Test
    fun `non advancing page fails run without another fetch`() {
        val adapter = FakeAdapter { ScrapePage(emptyList(), mapOf("page" to "1"), complete = false) }
        val client = FakeClient(claim())
        val worker = worker(adapter, client)

        worker.poll(JobSource.DOU)

        assertEquals(1, adapter.fetches)
        assertTrue(client.batches.isEmpty())
        assertEquals(listOf("COVERAGE_INCOMPLETE"), client.failureReasons)
    }

    @Test
    fun `recreated worker reuses deterministic receipt id after partial page crash`() {
        val jobs = (1..260).map(::job)
        val adapter = FakeAdapter { ScrapePage(jobs, mapOf("page" to "2"), complete = true) }
        val client = FakeClient(claim()).apply { failBatchAt = 2 }

        worker(adapter, client).poll(JobSource.DOU)
        client.failBatchAt = null
        worker(adapter, client).poll(JobSource.DOU)

        assertEquals(4, client.batchAttempts.size)
        assertEquals(client.batchAttempts[0].batchId, client.batchAttempts[2].batchId)
        assertEquals(client.batchAttempts[1].batchId, client.batchAttempts[3].batchId)
    }

    private fun worker(
        adapter: SourceAdapter,
        client: FakeClient,
        registry: ObservationRegistry = observationRegistry(),
        heartbeatInterval: Duration = Duration.ofHours(1),
    ): SourceRunWorker {
        val properties =
            ScraperProperties(
                enabled = true,
                sources = setOf(JobSource.DOU),
                heartbeatInterval = heartbeatInterval,
                maxPages = 3,
            )
        return SourceRunWorker(
            listOf(adapter),
            client,
            properties,
            registry,
            SimpleMeterRegistry(),
            heartbeatExecutor,
            ScraperAvailability(properties, FIXED_CLOCK),
            BatchIdFactory(jacksonObjectMapper()),
            BatchPartitioner(jacksonObjectMapper(), properties),
        )
    }

    private fun observationRegistry(): ObservationRegistry =
        ObservationRegistry.create().also { registry ->
            registry.observationConfig().observationHandler(
                object : ObservationHandler<Observation.Context> {
                    override fun supportsContext(context: Observation.Context): Boolean = true
                },
            )
        }

    private fun claim() =
        RunClaim(
            UUID.randomUUID(),
            UUID.randomUUID(),
            JobSource.DOU,
            SearchCriteria(listOf("JAVA")),
            mapOf("page" to "1"),
            null,
            Instant.now().plusSeconds(300),
        )

    private fun job(index: Int) =
        ScrapedJob(
            title = "Job $index",
            url = "https://example.test/$index",
            source = JobSource.DOU,
            category = "JAVA",
        )

    private class FakeAdapter(
        private val response: (ScrapeContext) -> ScrapePage,
    ) : SourceAdapter {
        override val source = JobSource.DOU
        var fetches = 0

        override fun fetch(context: ScrapeContext): ScrapePage {
            fetches++
            return response(context)
        }
    }

    private class FakeClient(
        private val nextClaim: RunClaim,
    ) : JobHunterClient {
        val batches = mutableListOf<Batch>()
        val failureReasons = mutableListOf<String>()
        var completed = 0
        var failed = 0
        var heartbeats = 0
        var heartbeatObservation: Observation? = null
        var onHeartbeat: () -> Unit = {}
        var failBatchAt: Int? = null
        val batchAttempts = mutableListOf<Batch>()
        private var batchCalls = 0

        override fun claim(source: JobSource): RunClaim = nextClaim

        override fun heartbeat(claim: RunClaim) {
            heartbeats++
            onHeartbeat()
        }

        override fun batch(
            claim: RunClaim,
            batchId: UUID,
            jobs: List<ScrapedJob>,
            checkpoint: Map<String, String>,
            fetchedCount: Int,
        ): Int {
            val batch = Batch(batchId, jobs, checkpoint, fetchedCount)
            batchAttempts += batch
            batchCalls++
            if (batchCalls == failBatchAt) throw RuntimeException("simulated process failure")
            batches += batch
            return jobs.size
        }

        override fun complete(claim: RunClaim) {
            completed++
        }

        override fun fail(
            claim: RunClaim,
            reason: String,
        ) {
            failed++
            failureReasons += reason
        }

        override fun proxies(source: JobSource): List<SourceProxy> = emptyList()

        override fun checkJobs(jobs: List<JobCheckRequest>) = JobCheckResult(emptyList(), emptyList(), emptyList())
    }

    private data class Batch(
        val batchId: UUID,
        val jobs: List<ScrapedJob>,
        val checkpoint: Map<String, String>,
        val fetchedCount: Int,
    )

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-18T00:00:00Z"), ZoneOffset.UTC)
    }
}
