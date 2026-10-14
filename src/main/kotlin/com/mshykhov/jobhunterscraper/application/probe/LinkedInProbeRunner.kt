package com.mshykhov.jobhunterscraper.application.probe

import com.mshykhov.jobhunterscraper.infrastructure.http.OutboundHttpException
import com.mshykhov.jobhunterscraper.infrastructure.source.LinkedInAdapter
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.net.URI

@Component
@Profile("source-probe")
class LinkedInProbeRunner(
    private val adapter: LinkedInAdapter,
) {
    fun run(request: LinkedInProbeRequest): LinkedInProbeResult =
        try {
            val jobs = adapter.probeSearch(request.category, request.location, request.remoteOnly, request.limit, request.hoursOld)
            LinkedInProbeResult(
                requested = request,
                outcome = if (jobs.isEmpty()) "EMPTY" else "SAMPLE_RETURNED",
                returnedCount = jobs.size,
                samples =
                    jobs.map { job ->
                        LinkedInProbeSample(
                            title = job.title.take(160),
                            company = job.company?.take(120),
                            url = publicUrl(job.url),
                            location = job.location?.take(120),
                            remote = job.remote,
                            publishedAt = job.publishedAt?.take(40),
                        )
                    },
            )
        } catch (error: OutboundHttpException) {
            LinkedInProbeResult(
                requested = request,
                outcome = if (error.status.value() == 429) "RATE_LIMITED" else "UPSTREAM_ERROR",
                returnedCount = 0,
                samples = emptyList(),
                error = "LinkedIn search unavailable",
                httpStatus = error.status.value(),
            )
        } catch (_: Exception) {
            LinkedInProbeResult(request, "UPSTREAM_ERROR", 0, emptyList(), "Search or proxy lookup failed")
        }

    private fun publicUrl(url: String): String? =
        runCatching {
            val uri = URI(url)
            if (uri.scheme != "https" ||
                uri.host?.lowercase() !in setOf("linkedin.com", "www.linkedin.com") ||
                !uri.path.startsWith("/jobs/view/")
            ) {
                null
            } else {
                URI("https", null, uri.host, -1, uri.path, null, null).toASCIIString().take(300)
            }
        }.getOrNull()
}
