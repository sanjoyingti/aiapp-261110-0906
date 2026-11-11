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

/**
 * The biggest open-weight & unrestricted models (DeepSeek R1, Qwen 3.8 Coder, GLM-5.3-Flash,
 * Nemotron 3 Ultra 550B, Dolphin Venice 24B, Kimi K2, Llama 3.3 70B, Gemma 4 31B and others),
 * hosted in the cloud by OpenRouter, so no PC is needed.
 */
class OpenModelsProvider : LlmProvider {
    override val id = ProviderId.OPEN

    private val base = "https://openrouter.ai/api/v1"

    private fun headers(key: String) = mapOf(
        "Authorization" to "Bearer $key",
        "X-OpenRouter-Title" to "AI App",
    )

    /** Curated flagship OpenRouter models (including :free models for REA, coding & open-ended chat). */
    private val curatedOpenModels = listOf(
        // --- Verified-live free picks (Nov 2026). OpenRouter's free roster rotates, so the auto-router
        // is listed first as the most reliable: it always routes to a currently-free model. ---
        ModelInfo("openrouter/free", "★ Auto-Free Router (Free · always routes to a live free model — most reliable for coding)"),
        ModelInfo("openai/gpt-oss-120b:free", "★ GPT-OSS 120B (Free · strongest free coder right now — Apache-2.0, tool-calling)"),
        ModelInfo("cohere/north-mini-code:free", "★ Cohere North Mini Code (Free · fast agentic coding / smali patching)"),
        ModelInfo("deepseek/deepseek-r1:free", "★ DeepSeek R1 671B Reasoning (Free · REA & Android Architect)"),
        ModelInfo("qwen/qwen3-coder:free", "★ Qwen 3 Coder 480B (Free · Best APK Cloner & Kotlin Builder)"),
        ModelInfo("z-ai/glm-5.3-flash:free", "★ GLM-5.3-Flash 321B (Free · Editor V1 Video & Multimodal Code)"),
        ModelInfo("nvidia/nemotron-3-ultra-550b-a55b:free", "★ NVIDIA Nemotron 3 Ultra 550B (Free · Deep Thinking & REA)"),
        ModelInfo("cognitivecomputations/dolphin-mistral-24b-venice-edition:free", "★ Dolphin Mistral 24B Venice Edition (Free · Unrestricted)"),
        ModelInfo("google/gemma-4-31b-it:free", "★ Gemma 4 31B Instruct (Free · Multimodal & Coding)"),
        ModelInfo("meta-llama/llama-3.3-70b-instruct:free", "★ Llama 3.3 70B Instruct (Free · Full-Stack Coding)"),
        ModelInfo("moonshotai/kimi-k2:free", "★ Moonshot Kimi K2 (Free · Agentic Coding & REA)"),
        ModelInfo("deepseek/deepseek-chat-v3-0324:free", "★ DeepSeek V3 0324 (Free · Fast Code & Smali Patching)"),
        ModelInfo("mistralai/mistral-nemo:free", "★ Mistral Nemo 12B (Free · Creative & Roleplay)"),
    )

    /** Makers of open-weight & unrestricted models on OpenRouter. */
    private val openVendors = listOf(
        "moonshotai/", "deepseek/", "z-ai/", "qwen/", "minimax/", "meta-llama/", "mistralai/",
        "google/gemma", "openai/gpt-oss", "nvidia/", "microsoft/phi", "stepfun", "baidu/", "xiaomi/",
        "cognitivecomputations/", "nousresearch/", "gryphe/", "sao10k/", "thedrummer/", "neversleep/",
        "cohere/", "anthracite-org/", "eva-unit-01/", "openrouter/", "arliai/", "featherless/",
    )

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        val remote = runCatching {
            val data = Http.getJson("$base/models", headers(apiKey))["data"] as? JsonArray ?: return@runCatching emptyList()
            data.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val lower = id.lowercase()
                if (openVendors.none { lower.startsWith(it) }) return@mapNotNull null
                val outputs = ((o["architecture"] as? JsonObject)?.get("output_modalities") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if (outputs != null && "text" !in outputs) return@mapNotNull null
                ModelInfo(id, (o["name"] as? JsonPrimitive)?.contentOrNull ?: id)
            }
        }.getOrDefault(emptyList())

        val seen = curatedOpenModels.map { it.id }.toMutableSet()
        return curatedOpenModels + remote.filter { seen.add(it.id) }
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
        val compacted = ContextCompactor.compactIfNeeded(messages, ProviderId.OPEN).messages
        val level = effortValue(effort)
        val attempts = mutableListOf<() -> Flow<String>>()
        if (level != null) attempts += { request(apiKey, model, compacted, system, level) }
        if (level == "xhigh") attempts += { request(apiKey, model, compacted, system, "high") }
        attempts += { request(apiKey, model, compacted, system, null) }
        return fallbackChain(attempts)
    }

    private fun request(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        system: String,
        effort: String?,
    ): Flow<String> {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
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
            if (effort != null) put("reasoning_effort", effort)
            put("include_reasoning", true)
        }.toString()

        return Http.sse("$base/chat/completions", headers(apiKey), body).mapNotNull { data -> parse(data) }
    }

    private fun parse(data: String): String? {
        val o = Http.json.parseToJsonElement(data).jsonObject
        (o["error"] as? JsonObject)?.let { err ->
            throw ProviderException(500, (err["message"] as? JsonPrimitive)?.contentOrNull ?: "Model error")
        }
        val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val delta = choice["delta"] as? JsonObject ?: return null
        val content = (delta["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val reasoning = (delta["reasoning"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when {
            content.isNotEmpty() -> content
            reasoning.isNotEmpty() -> thought(reasoning)
            else -> null
        }
    }
}
