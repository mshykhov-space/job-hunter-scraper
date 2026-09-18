package com.mshykhov.jobhunterscraper.application

import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.application.model.RunClaim
import com.mshykhov.jobhunterscraper.application.model.ScrapeContext
import com.mshykhov.jobhunterscraper.application.model.ScrapePage
import com.mshykhov.jobhunterscraper.infrastructure.api.JobHunterClient
import com.mshykhov.jobhunterscraper.infrastructure.api.LeaseLostException
import com.mshykhov.jobhunterscraper.infrastructure.api.SourceDisabledException
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Component
class SourceRunWorker(
    adapters: List<SourceAdapter>,
    private val client: JobHunterClient,
    private val properties: ScraperProperties,
    private val observationRegistry: ObservationRegistry,
    private val meterRegistry: MeterRegistry,
    private val heartbeatExecutor: ScheduledExecutorService,
    private val availability: ScraperAvailability,
    private val batchIdFactory: BatchIdFactory,
    private val batchPartitioner: BatchPartitioner,
) {
    private val adapters = adapters.associateBy(SourceAdapter::source)

    fun poll(source: JobSource) {
        val adapter = checkNotNull(adapters[source]) { "No adapter configured for ${source.id}" }
        val claim =
            try {
                client.claim(source).also { availability.controlPlaneSucceeded(source) }
            } catch (failure: RuntimeException) {
                availability.controlPlaneFailed(source)
                record(source, OUTCOME_CONTROL_PLANE_FAILURE)
                logger.error(failure) { "Failed to claim source=${source.id}" }
                return
            } ?: run {
                record(source, OUTCOME_NO_WORK)
                return
            }

        if (claim.source != source) {
            runCatching { client.fail(claim, REASON_INVALID_CLAIM) }
            record(source, OUTCOME_FAILURE)
            return
        }
        execute(claim, adapter)
    }

    private fun execute(
        claim: RunClaim,
        adapter: SourceAdapter,
    ) {
        val runObservation =
            Observation
                .createNotStarted("scrape.run", observationRegistry)
                .contextualName("scrape ${claim.source.id}")
                .lowCardinalityKeyValue("source", claim.source.id)
                .highCardinalityKeyValue("run.id", claim.runId.toString())
                .start()
        val heartbeatFailure = AtomicReference<RuntimeException>()
        val heartbeat =
            heartbeatExecutor.scheduleAtFixedRate(
                runObservation.wrap(
                    Runnable {
                        try {
                            client.heartbeat(claim)
                            availability.controlPlaneSucceeded(claim.source)
                        } catch (failure: RuntimeException) {
                            availability.controlPlaneFailed(claim.source)
                            heartbeatFailure.compareAndSet(null, failure)
                        }
                    },
                ),
                properties.heartbeatInterval.toMillis(),
                properties.heartbeatInterval.toMillis(),
                TimeUnit.MILLISECONDS,
            )

        var outcome = OUTCOME_FAILURE
        try {
            runObservation.scoped { scrape(claim, adapter, heartbeatFailure) }
            outcome = OUTCOME_SUCCESS
        } catch (failure: LeaseLostException) {
            outcome = OUTCOME_LEASE_LOST
            runObservation.error(failure)
            logger.warn { "Lease lost source=${claim.source.id} runId=${claim.runId}" }
        } catch (failure: SourceDisabledException) {
            outcome = OUTCOME_SOURCE_DISABLED
            runObservation.error(failure)
            logger.info { "Source disabled during scrape source=${claim.source.id} runId=${claim.runId}" }
        } catch (failure: RuntimeException) {
            runObservation.error(failure)
            if (Thread.currentThread().isInterrupted) {
                outcome = OUTCOME_INTERRUPTED
                logger.info { "Scrape interrupted source=${claim.source.id} runId=${claim.runId}" }
            } else {
                val reason = failureReason(failure)
                runCatching { client.fail(claim, reason) }
                    .onFailure { logger.error(it) { "Failed to report source failure source=${claim.source.id} runId=${claim.runId}" } }
                logger.error(failure) { "Scrape failed source=${claim.source.id} runId=${claim.runId} reason=$reason" }
            }
        } finally {
            heartbeat.cancel(true)
            runObservation.lowCardinalityKeyValue("outcome", outcome)
            runObservation.stop()
            record(claim.source, outcome)
        }
    }

    private fun scrape(
        claim: RunClaim,
        adapter: SourceAdapter,
        heartbeatFailure: AtomicReference<RuntimeException>,
    ) {
        var context = ScrapeContext(claim.criteria, claim.checkpoint, claim.since)
        for (pageNumber in 1..properties.maxPages) {
            checkHeartbeat(heartbeatFailure)
            val page = fetchPage(claim, adapter, context, pageNumber)
            validatePage(claim.source, context, page)
            checkHeartbeat(heartbeatFailure)
            deliverPage(claim, context, page, heartbeatFailure)
            context = context.copy(checkpoint = page.checkpoint)
            if (page.complete) {
                checkHeartbeat(heartbeatFailure)
                client.complete(claim)
                return
            }
        }
        throw ScrapeRunException("Source pagination exceeded the configured limit")
    }

    private fun fetchPage(
        claim: RunClaim,
        adapter: SourceAdapter,
        context: ScrapeContext,
        pageNumber: Int,
    ): ScrapePage {
        val observation =
            Observation
                .createNotStarted("scrape.page", observationRegistry)
                .parentObservation(observationRegistry.currentObservation)
                .contextualName("scrape page ${claim.source.id}")
                .lowCardinalityKeyValue("source", claim.source.id)
                .highCardinalityKeyValue("page.number", pageNumber.toString())
        observation.start()
        return try {
            observation.openScope().use { adapter.fetch(context) }
        } catch (failure: RuntimeException) {
            observation.error(failure)
            throw failure
        } finally {
            observation.stop()
        }
    }

    private fun validatePage(
        source: JobSource,
        context: ScrapeContext,
        page: ScrapePage,
    ) {
        if (page.fetchedCount < 0 || page.fetchedCount < page.jobs.size) {
            throw ScrapeRunException("Source returned invalid fetched count")
        }
        if (page.jobs.any { it.source != source }) {
            throw ScrapeRunException("Source returned jobs for another source")
        }
        if (!page.complete && page.checkpoint == context.checkpoint) {
            throw ScrapeRunException("Source did not advance its checkpoint")
        }
    }

    private fun deliverPage(
        claim: RunClaim,
        context: ScrapeContext,
        page: ScrapePage,
        heartbeatFailure: AtomicReference<RuntimeException>,
    ) {
        val chunks = batchPartitioner.partition(claim, context.checkpoint, page)
        chunks.forEachIndexed { index, jobs ->
            checkHeartbeat(heartbeatFailure)
            val finalChunk = index == chunks.lastIndex
            val checkpoint = if (finalChunk) page.checkpoint else context.checkpoint
            val fetchedCount = if (finalChunk) page.fetchedCount else 0
            client.batch(
                claim = claim,
                batchId =
                    batchIdFactory.create(
                        claim.runId,
                        context.checkpoint,
                        checkpoint,
                        index,
                        jobs,
                        fetchedCount,
                    ),
                jobs = jobs,
                checkpoint = checkpoint,
                fetchedCount = fetchedCount,
            )
            checkHeartbeat(heartbeatFailure)
        }
    }

    private fun checkHeartbeat(failure: AtomicReference<RuntimeException>) {
        failure.get()?.let { throw it }
    }

    private fun failureReason(failure: RuntimeException): String =
        when (failure) {
            is SourceSchemaException -> REASON_SCHEMA
            is ScrapeRunException -> REASON_COVERAGE
            else -> REASON_SOURCE
        }

    private fun record(
        source: JobSource,
        outcome: String,
    ) {
        meterRegistry.counter("scraper.runs", "source", source.id, "outcome", outcome).increment()
    }

    private companion object {
        val logger = KotlinLogging.logger {}
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_NO_WORK = "no_work"
        const val OUTCOME_FAILURE = "failure"
        const val OUTCOME_LEASE_LOST = "lease_lost"
        const val OUTCOME_CONTROL_PLANE_FAILURE = "control_plane_failure"
        const val OUTCOME_INTERRUPTED = "interrupted"
        const val OUTCOME_SOURCE_DISABLED = "source_disabled"
        const val REASON_INVALID_CLAIM = "INVALID_CLAIM"
        const val REASON_SCHEMA = "SOURCE_SCHEMA"
        const val REASON_COVERAGE = "COVERAGE_INCOMPLETE"
        const val REASON_SOURCE = "SOURCE_FAILURE"
    }
}
