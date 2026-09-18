package com.mshykhov.jobhunterscraper.infrastructure.config

import com.mshykhov.jobhunterscraper.infrastructure.http.SanitizedClientRequestObservationConvention
import org.springframework.boot.web.client.RestClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.support.ContextPropagatingTaskDecorator
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.time.Clock
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

@Configuration
class RuntimeConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun sanitizedHttpObservationCustomizer(): RestClientCustomizer =
        RestClientCustomizer { builder -> builder.observationConvention(SanitizedClientRequestObservationConvention()) }

    @Bean
    fun sourceExecutor(properties: ScraperProperties): Executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = properties.maxParallelSources
            maxPoolSize = properties.maxParallelSources
            queueCapacity = 0
            setThreadNamePrefix("scrape-source-")
            setTaskDecorator(ContextPropagatingTaskDecorator())
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(30)
            initialize()
        }

    @Bean(destroyMethod = "shutdownNow")
    fun heartbeatExecutor(properties: ScraperProperties): ScheduledExecutorService =
        Executors.newScheduledThreadPool(properties.maxParallelSources) { runnable ->
            Thread(runnable, "scrape-heartbeat").apply { isDaemon = true }
        }
}
