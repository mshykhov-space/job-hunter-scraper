package com.mshykhov.jobhunterscraper.infrastructure.source

import com.mshykhov.jobhunterscraper.application.model.ProxyEndpoint
import com.mshykhov.jobhunterscraper.infrastructure.http.SourceHttpClient

internal class RecordingSourceHttpClient(
    private val getResponse: (String) -> String = { error("Unexpected GET $it") },
    private val postResponse: (String, Any) -> String = { url, _ -> error("Unexpected POST $url") },
) : SourceHttpClient {
    val getUrls = mutableListOf<String>()
    val postUrls = mutableListOf<String>()

    override fun get(
        url: String,
        headers: Map<String, String>,
        proxy: ProxyEndpoint?,
    ): String {
        getUrls += url
        return getResponse(url)
    }

    override fun post(
        url: String,
        body: Any,
        headers: Map<String, String>,
        proxy: ProxyEndpoint?,
    ): String {
        postUrls += url
        return postResponse(url, body)
    }
}

internal fun fixture(path: String): String =
    checkNotNull(object {}.javaClass.getResource("/fixtures/$path")) { "Missing fixture $path" }.readText()
