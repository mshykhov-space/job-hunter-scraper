package com.mshykhov.jobhunterscraper.application.model

import java.time.Instant
import java.util.UUID

class RunClaim(
    val runId: UUID,
    val leaseToken: UUID,
    val source: JobSource,
    val criteria: SearchCriteria,
    val checkpoint: Map<String, String>,
    val since: Instant?,
    val leaseExpiresAt: Instant,
    val attempt: Int = 1,
)
