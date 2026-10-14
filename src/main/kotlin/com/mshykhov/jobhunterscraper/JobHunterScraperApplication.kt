package com.mshykhov.jobhunterscraper

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mshykhov.jobhunterscraper.application.probe.LinkedInProbeRequest
import com.mshykhov.jobhunterscraper.application.probe.LinkedInProbeResult
import com.mshykhov.jobhunterscraper.application.probe.LinkedInProbeRunner
import org.springframework.boot.Banner
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import kotlin.system.exitProcess

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class JobHunterScraperApplication

fun main(args: Array<String>) {
    if (args.any { it == "--probe-linkedin" || it.startsWith("--probe.") }) {
        runLinkedInProbe(args)
    } else {
        runApplication<JobHunterScraperApplication>(*args)
    }
}

private fun runLinkedInProbe(args: Array<String>) {
    val mapper = jacksonObjectMapper()
    val request =
        try {
            LinkedInProbeRequest.parse(args)
        } catch (error: IllegalArgumentException) {
            println(mapper.writeValueAsString(mapOf("outcome" to "INVALID_REQUEST", "error" to error.message)))
            exitProcess(2)
        }
    val result =
        try {
            SpringApplicationBuilder(JobHunterScraperApplication::class.java)
                .profiles("source-probe")
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run(
                    "--scraper.http-attempts=1",
                    "--scraper.source-timeouts.linkedin=60s",
                    "--job-hunter.attempts=1",
                    "--job-hunter.timeout=10s",
                    "--logging.level.root=OFF",
                    "--logging.level.com.mshykhov.jobhunterscraper=OFF",
                ).use { context -> context.getBean(LinkedInProbeRunner::class.java).run(request) }
        } catch (_: Exception) {
            LinkedInProbeResult(request, "UPSTREAM_ERROR", 0, emptyList(), "Probe startup failed")
        }
    println(mapper.writeValueAsString(result))
    exitProcess(if (result.outcome == "SAMPLE_RETURNED" || result.outcome == "EMPTY") 0 else 1)
}
