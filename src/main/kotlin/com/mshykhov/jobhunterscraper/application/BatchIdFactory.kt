package com.mshykhov.jobhunterscraper.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.mshykhov.jobhunterscraper.application.model.ScrapedJob
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

@Component
class BatchIdFactory(
    private val objectMapper: ObjectMapper,
) {
    fun create(
        runId: UUID,
        previousCheckpoint: Map<String, String>,
        outgoingCheckpoint: Map<String, String>,
        chunkIndex: Int,
        jobs: List<ScrapedJob>,
        fetchedCount: Int,
    ): UUID {
        val payload =
            objectMapper.valueToTree<JsonNode>(
                mapOf(
                    "runId" to runId.toString(),
                    "previousCheckpoint" to previousCheckpoint,
                    "outgoingCheckpoint" to outgoingCheckpoint,
                    "chunkIndex" to chunkIndex,
                    "jobs" to jobs,
                    "fetchedCount" to fetchedCount,
                ),
            )
        val bytes = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(canonical(payload))).copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }

    private fun canonical(node: JsonNode): JsonNode =
        when {
            node.isObject ->
                ObjectNode(JsonNodeFactory.instance).also { result ->
                    node
                        .fieldNames()
                        .asSequence()
                        .sorted()
                        .forEach { name -> result.set<JsonNode>(name, canonical(node.get(name))) }
                }
            node.isArray ->
                ArrayNode(JsonNodeFactory.instance).also { result -> node.forEach { child -> result.add(canonical(child)) } }
            else -> node
        }
}
