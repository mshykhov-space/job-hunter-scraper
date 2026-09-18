package com.mshykhov.jobhunterscraper.infrastructure.http

class SourceTransportException(
    target: String,
) : RuntimeException("HTTP transport failed for $target")
