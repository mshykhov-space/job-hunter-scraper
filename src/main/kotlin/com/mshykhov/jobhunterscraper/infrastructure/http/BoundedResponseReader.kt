package com.mshykhov.jobhunterscraper.infrastructure.http

import org.springframework.http.HttpHeaders
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

internal object BoundedResponseReader {
    fun read(
        body: InputStream,
        headers: HttpHeaders,
        maxBytes: Int,
    ): String {
        val contentLength = headers.contentLength
        if (contentLength > maxBytes) throw ResponseTooLargeException(maxBytes)

        val bytes = body.readNBytes(maxBytes + 1)
        if (bytes.size > maxBytes) throw ResponseTooLargeException(maxBytes)
        return bytes.toString(headers.contentType?.charset ?: StandardCharsets.UTF_8)
    }

    private fun ByteArray.toString(charset: Charset): String = String(this, charset)
}
