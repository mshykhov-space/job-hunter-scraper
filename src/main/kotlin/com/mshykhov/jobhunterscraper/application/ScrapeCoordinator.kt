package com.mshykhov.jobhunterscraper.application

import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.infrastructure.config.ScraperProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

@Component
@ConditionalOnProperty(prefix = "scraper", name = ["enabled"], havingValue = "true")
class ScrapeCoordinator(
    private val properties: ScraperProperties,
    private val worker: SourceRunWorker,
    private val sourceExecutor: Executor,
) {
    private val active = ConcurrentHashMap.newKeySet<JobSource>()

    @Scheduled(fixedDelayString = "\${scraper.poll-interval:15s}")
    fun poll() {
        properties.sources.forEach(::submit)
    }

    private fun submit(source: JobSource) {
        if (!active.add(source)) return
        try {
            sourceExecutor.execute {
                try {
                    worker.poll(source)
                } finally {
                    active.remove(source)
                }
            }
        } catch (_: RejectedExecutionException) {
            active.remove(source)
            logger.debug { "Source executor is full source=${source.id}" }
        }
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
