package com.mshykhov.jobhunterscraper.infrastructure.api

class ControlPlanePayloadException(
    operation: String,
) : RuntimeException("Job Hunter API returned an invalid payload for $operation")
