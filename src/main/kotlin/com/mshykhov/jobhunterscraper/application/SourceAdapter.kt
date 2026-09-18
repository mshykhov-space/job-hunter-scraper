package com.mshykhov.jobhunterscraper.application

import com.mshykhov.jobhunterscraper.application.model.JobSource
import com.mshykhov.jobhunterscraper.application.model.ScrapeContext
import com.mshykhov.jobhunterscraper.application.model.ScrapePage

interface SourceAdapter {
    val source: JobSource

    fun fetch(context: ScrapeContext): ScrapePage
}
