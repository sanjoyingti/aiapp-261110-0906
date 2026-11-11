package com.aiapp.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class OpenAiProvider : LlmProvider {
    override val id = ProviderId.OPENAI

    private val base = "https://api.openai.com/v1"

    private fun headers(key: String) = mapOf("Authorization" to "Bearer $key")

    private val excluded = listOf(
        "audio", "realtime", "tts", "transcribe", "image", "search", "embedding",
        "moderation", "whisper", "dall-e", "instruct", "codex",
    )

    private val reasoningPattern = Regex("^(o\\d|gpt-5)")

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        val data = Http.getJson("$base/models", headers(apiKey))["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { el -> ((el as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
            .filter { id ->
                (id.startsWith("gpt") || id.startsWith("chatgpt") || Regex("^o\\d").containsMatchIn(id)) &&
                    excluded.none { id.contains(it) }
            }
            .map { ModelInfo(it, it) }
    }

    private fun effortValue(effort: Effort): String? = when (effort) {
        Effort.LOW -> "low"
        Effort.MEDIUM -> "medium"
        Effort.HIGH -> "high"
        Effort.MAX -> "xhigh"
        Effort.AUTO -> null
    }

    override fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String> {
        val messages = ContextCompactor.compactIfNeeded(messages, ProviderId.OPENAI).messages
        val level = effortValue(effort)
        val attempts = mutableListOf<() -> Flow<String>>()
        if (level != null) attempts += { request(apiKey, model, messages, system, level) }
        // Models without the top level fall back to "high".
        if (level == "xhigh") attempts += { request(apiKey, model, messages, system, "high") }
        attempts += { request(apiKey, model, messages, system, null) }
        return fallbackChain(attempts)
    }

    private fun request(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        system: String,
        effort: String?,
    ): Flow<String> {
        val reasoning = reasoningPattern.containsMatchIn(model)
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", if (reasoning) "developer" else "system")
                    put("content", system)
                }
                val withFiles = AttachmentContent.binaryIndices(messages)
                messages.forEachIndexed { i, m ->
                    addJsonObject {
                        put("role", if (m.role == Role.USER) "user" else "assistant")
                        put("content", AttachmentContent.openAi(m, i in withFiles))
                    }
                }
            }
            if (reasoning && effort != null) put("reasoning_effort", effort)
        }.toString()

        return Http.sse("$base/chat/completions", headers(apiKey), body).mapNotNull { data -> parse(data) }
    }

    private fun parse(data: String): String? {
        val o = Http.json.parseToJsonElement(data).jsonObject
        val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val delta = choice["delta"] as? JsonObject ?: return null
        return (delta["content"] as? JsonPrimitive)?.contentOrNull
    }
}
