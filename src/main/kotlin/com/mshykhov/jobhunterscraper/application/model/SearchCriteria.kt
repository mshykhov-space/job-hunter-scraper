package com.mshykhov.jobhunterscraper.application.model

data class SearchCriteria(
    val categories: List<String>,
    val locations: List<String> = emptyList(),
    val remoteOnly: Boolean = false,
)
