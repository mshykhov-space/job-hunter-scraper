package com.mshykhov.jobhunterscraper.infrastructure.api

class ControlPlaneTransportException(
    operation: String,
    cause: Throwable,
) : RuntimeException("Job Hunter API transport failed for $operation", cause)
