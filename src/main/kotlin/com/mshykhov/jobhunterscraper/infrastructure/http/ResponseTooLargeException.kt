package com.mshykhov.jobhunterscraper.infrastructure.http

class ResponseTooLargeException(
    maxBytes: Int,
) : RuntimeException("HTTP response exceeds the configured $maxBytes byte limit")
