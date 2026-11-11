package com.aiapp.llm

import com.aiapp.data.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 100% Free, Zero-API-Key, Multi-Gateway Cloud AI Provider ("Free cloud / Token Harbor").
 *
 * Integrates all models from our previous app and automatically rotates across 10 independent
 * keyless OpenAI-compatible gateways when any upstream endpoint hits a rate limit (HTTP 429/402/5xx),
 * matching the Token Harbor auto-switch workflow shown in Clozed Code + REA:
 * 1. OpenCode Zen / Inference (`GLM-5.3-Flash Free 321B` — AI Video Editor V1 & Multimodal Coder)
 * 2. Kilo AI Gateway & Mirror (`Nemotron 3 Ultra 550B`, `Venice Dolphin 24B`, `Reflection 501B`, `North Code`, `GLM-5.3-Flash`)
 * 3. Zydit AI Gateway (`DeepSeek R1`, `Dolphin 3.0`, `Gemma 4 31B Pro`)
 * 4. VLM Run Gateway (`Qwen 3.8 27B`, `Gemma 4 26B`)
 * 5. OVHcloud AI Endpoints (`Qwen 3.8 27B`, `Qwen 3.5 397B`, `GPT-OSS 120B`, `Llama 3.3 70B`, `Mistral Small 3.2 24B`)
 * 6. LLM7.io Public API (`Mistral Nemo 2407`, `Codestral Latest`, `GPT-OSS 20B`)
 * 7. BazaarLink & Pollinations (`Reflection 501B`, `GPT-OSS 20B`)
 */
class FreeCloudProvider : LlmProvider {
    override val id = ProviderId.FREE

    private val jsonType = "application/json".toMediaType()

    private val fastClient = Http.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private data class UpstreamRoute(
        val name: String,
        val endpoint: String,
        val remoteModel: String,
        val headers: Map<String, String>,
    )

    companion object {
        private const val OPENCODE_INFERENCE_URL = "https://opencode.ai/inference/openai/v1/chat/completions"
        private const val OPENCODE_ZEN_URL = "https://opencode.ai/zen/v1/chat/completions"
        private const val LLM7_URL = "https://api.llm7.io/v1/chat/completions"
        private const val ZYDIT_URL = "https://api.zydit.in/v3/chat/completions"
        private const val VLM_RUN_URL = "https://gateway.vlm.run/v1/openai/chat/completions"
        private const val OVH_URL = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/chat/completions"
        private const val KILO_URL = "https://api.kilo.ai/api/gateway/chat/completions"
        private const val KILO_MIRROR_URL = "https://ai-gateway.kilo.ai/api/v1/chat/completions"
        private const val BAZAAR_URL = "https://api.bazaarlink.ai/v1/chat/completions"
        private const val POLLINATIONS_URL = "https://text.pollinations.ai/openai"
        private const val POLLINATIONS_GEN_URL = "https://gen.pollinations.ai/v1/chat/completions"

        @Volatile var customRouterEndpoint: String = ""
        @Volatile var customRouterKey: String = ""
        @Volatile var customRouterModel: String = ""

        private val sessionUuid = UUID.randomUUID().toString()
        private val opencodeSessionId = "ses_" + sessionUuid.replace("-", "").take(24)

        private val opencodeHeaders = mapOf(
            "Authorization" to "Bearer public",
            "User-Agent" to "opencode/2.0.32",
            "x-opencode-session" to opencodeSessionId,
            "x-session-id" to sessionUuid,
        )

        private val opencodeEmptyBearerHeaders = mapOf(
            "Authorization" to "Bearer ",
            "User-Agent" to "opencode/2.0.32",
            "x-opencode-session" to opencodeSessionId,
            "x-session-id" to sessionUuid,
        )

        private val llm7Headers = mapOf(
            "Authorization" to "Bearer unused",
            "User-Agent" to Http.DEFAULT_UA,
        )

        private val zyditHeaders = mapOf(
            "User-Agent" to Http.DEFAULT_UA,
        )

        private val vlmHeaders = mapOf(
            "Authorization" to "Bearer vlmrun",
            "User-Agent" to Http.DEFAULT_UA,
        )

        private val ovhHeaders = mapOf(
            "User-Agent" to Http.DEFAULT_UA,
        )

        private val kiloHeaders = mapOf(
            "User-Agent" to "Kilo-Code/4.0.0",
            "HTTP-Referer" to "https://kilocode.ai",
            "X-Title" to "Kilo Code",
        )

        private val bazaarHeaders = mapOf(
            "User-Agent" to Http.DEFAULT_UA,
        )

        private val pollinationsHeaders = mapOf(
            "User-Agent" to Http.DEFAULT_UA,
            "Referer" to "https://pollinations.ai/",
        )
    }

    /**
     * Complete catalog of flagship free models (all working with Chat, Build, Create, and REA Reverse Engineering).
     */
    private val curatedModels = listOf(
        ModelInfo(
            id = "gpt-oss-120b",
            name = "★ GPT-OSS 120B — Best Free Coder (Apache-2.0, tool-calling, keyless · $0)",
        ),
        ModelInfo(
            id = "craft-architect",
            name = "★ Craft Anything + Claude Code Architect (9Router / Radox AI / Token Harbor · $0)",
        ),
        ModelInfo(
            id = "rea-agent",
            name = "★ REA & Clozed Code Agent (DeepSeek R1 / Qwen 3.8 / Nemotron 550B · Free)",
        ),
        ModelInfo(
            id = "glm-5.3-flash",
            name = "★ GLM-5.3-Flash Free 321B (AI Video Editor V1, REA & Multimodal Code · $0)",
        ),
        ModelInfo(
            id = "evil",
            name = "★ Dolphin 3.0 / Venice 24B / Reflection 501B (Unrestricted Flagship & REA · Free)",
        ),
        ModelInfo(
            id = "uncensored-roleplay",
            name = "★ Mistral Nemo 2407 / Dolphin Mistral 24B & Llama 3.3 70B (Creative & Roleplay · Free)",
        ),
        ModelInfo(
            id = "heretic-uncensored",
            name = "★ Gemma 4 31B Pro / Heretic 501B (Unrestricted Open-Ended Chat & Code · Free)",
        ),
        ModelInfo(
            id = "qwen-coder",
            name = "★ Qwen 3.8 Coder 27B / 397B & Codestral (Best Android Studio & APK Cloner · Free)",
        ),
        ModelInfo(
            id = "deepseek-reasoning",
            name = "★ DeepSeek R1 / Nemotron 3 Ultra 550B (Claude-Style Deep Thinking & REA · Free)",
        ),
        ModelInfo(
            id = "deepseek",
            name = "★ DeepSeek V3 / Cohere North Code & Qwen 3.6 (Fast Full-Stack Coding · Free)",
        ),
        ModelInfo(
            id = "openai-reasoning",
            name = "★ OpenAI GPT-OSS 120B Reasoning (Advanced Logic & Android Architect · Free)",
        ),
        ModelInfo(
            id = "openai-large",
            name = "★ Gemma 4 26B / Nemotron Super 120B (Multimodal & App Builder · Free)",
        ),
        ModelInfo(
            id = "mistral",
            name = "★ Mistral Small 3.2 24B & Llama 3.3 70B (Open-Weight Coder · Free)",
        ),
        ModelInfo(
            id = "openai-fast",
            name = "★ GPT-OSS 20B Fast (Instant Keyless Cloud · Free)",
        ),
    )

    private val modelEndpoints = listOf(
        "https://api.llm7.io/v1/models",
        "https://text.pollinations.ai/models",
    )

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        val seenIds = curatedModels.map { it.id }.toMutableSet()
        val extra = mutableListOf<ModelInfo>()
        for (url in modelEndpoints) {
            val discovered = runCatching {
                val raw = Http.getJsonArray(url, emptyMap())
                raw.mapNotNull { el ->
                    when (el) {
                        is JsonPrimitive -> el.contentOrNull?.let { ModelInfo(it, it) }
                        is JsonObject -> {
                            val name = (el["name"] ?: el["id"])?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            val type = el["type"]?.jsonPrimitive?.contentOrNull
                            if (type != null && type != "chat" && type != "text") return@mapNotNull null
                            ModelInfo(name, el["description"]?.jsonPrimitive?.contentOrNull ?: name)
                        }
                        else -> null
                    }
                }
            }.getOrElse { emptyList() }
            for (m in discovered) {
                if (seenIds.add(m.id)) extra += m
            }
            if (extra.isNotEmpty()) break
        }
        return curatedModels + extra
    }

    private fun routesFor(model: String): List<UpstreamRoute> {
        val opencodeGlmInf = UpstreamRoute("OpenCode-GLM-5.3-Flash-Free", OPENCODE_INFERENCE_URL, "glm-5.3-flash-free", opencodeHeaders)
        val opencodeGlmZen = UpstreamRoute("OpenCodeZen-GLM-5.3-Flash-Free", OPENCODE_ZEN_URL, "glm-5.3-flash-free", opencodeEmptyBearerHeaders)
        val kiloGlm53 = UpstreamRoute("Kilo-GLM-5.3-Flash", KILO_URL, "z-ai/glm-5.3-flash", kiloHeaders)
        val kiloGlm53Free = UpstreamRoute("Kilo-GLM-5.3-Flash-Free", KILO_URL, "z-ai/glm-5.3-flash:free", kiloHeaders)

        val llm7Nemo = UpstreamRoute("LLM7-Mistral-Nemo-2407", LLM7_URL, "mistral-Nemo-Instruct-2407", llm7Headers)
        val llm7Codestral = UpstreamRoute("LLM7-Codestral-Latest", LLM7_URL, "codestral-latest", llm7Headers)
        val llm7Gpt20 = UpstreamRoute("LLM7-GPT-OSS-20B", LLM7_URL, "gpt-oss:20b", llm7Headers)

        val zyditDolphin3 = UpstreamRoute("Zydit-Dolphin3-Latest", ZYDIT_URL, "dolphin3:latest", zyditHeaders)
        val zyditGemma31 = UpstreamRoute("Zydit-Gemma4-31B-Pro", ZYDIT_URL, "gemma4-31b-it-pro", zyditHeaders)
        val zyditDeepSeekR1 = UpstreamRoute("Zydit-DeepSeek-R1", ZYDIT_URL, "deepseek-r1:latest", zyditHeaders)

        val vlmQwen38 = UpstreamRoute("VLMRun-Qwen3.8-27B", VLM_RUN_URL, "qwen/qwen3.8-27b", vlmHeaders)
        val vlmGemma4 = UpstreamRoute("VLMRun-Gemma4-26B", VLM_RUN_URL, "google/gemma-4-26b-a4b-it", vlmHeaders)

        val ovhQwen38 = UpstreamRoute("OVH-Qwen3.8-27B", OVH_URL, "Qwen3.8-27B", ovhHeaders)
        val ovhQwen397 = UpstreamRoute("OVH-Qwen3.5-397B", OVH_URL, "Qwen3.5-397B-A17B", ovhHeaders)
        val ovhQwen36 = UpstreamRoute("OVH-Qwen3.6-27B", OVH_URL, "Qwen3.6-27B", ovhHeaders)
        val ovhGpt120 = UpstreamRoute("OVH-GPT-OSS-120B", OVH_URL, "gpt-oss-120b", ovhHeaders)
        val ovhGpt20 = UpstreamRoute("OVH-GPT-OSS-20B", OVH_URL, "gpt-oss-20b", ovhHeaders)
        val ovhMistral = UpstreamRoute("OVH-Mistral-3.2-24B", OVH_URL, "Mistral-Small-3.2-24B-Instruct-2506", ovhHeaders)
        val ovhLlama70 = UpstreamRoute("OVH-Llama-3.3-70B", OVH_URL, "Meta-Llama-3_3-70B-Instruct", ovhHeaders)

        val kiloVeniceDolphin = UpstreamRoute("Kilo-Venice-Dolphin-24B", KILO_URL, "cognitivecomputations/dolphin-mistral-24b-venice-edition:free", kiloHeaders)
        val kiloAuto = UpstreamRoute("Kilo-Auto-Free", KILO_URL, "kilo-auto/free", kiloHeaders)
        val kiloNorthCode = UpstreamRoute("Kilo-North-Code", KILO_URL, "cohere/north-mini-code:free", kiloHeaders)
        val kiloNemotronUltra = UpstreamRoute("Kilo-Nemotron-550B", KILO_URL, "nvidia/nemotron-3-ultra-550b-a55b:free", kiloHeaders)
        val kiloNemotronSuper = UpstreamRoute("Kilo-Nemotron-120B", KILO_URL, "nvidia/nemotron-3.5-super-vl:free", kiloHeaders)
        val kiloNemotronFast = UpstreamRoute("Kilo-Nemotron-Lightning", KILO_URL, "nvidia/nemotron-3.5-lightning:free", kiloHeaders)
        val kiloNemotronReason = UpstreamRoute("Kilo-Nemotron-Reasoning", KILO_URL, "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free", kiloHeaders)
        val kiloBeam = UpstreamRoute("Kilo-Reflection-501B", KILO_URL, "reflection/beam-free", kiloHeaders)
        val kiloStealth = UpstreamRoute("Kilo-Stealth-Alpha", KILO_URL, "stealth/crouton-alpha", kiloHeaders)
        val kiloGlyph = UpstreamRoute("Kilo-Stealth-Glyph", KILO_URL, "stealth/glyph-cluster", kiloHeaders)
        val kiloOpenRouterFree = UpstreamRoute("Kilo-OpenRouter-Free", KILO_URL, "openrouter/free", kiloHeaders)
        val kiloMirrorAuto = UpstreamRoute("KiloMirror-Auto-Free", KILO_MIRROR_URL, "kilo-auto/free", kiloHeaders)

        val bazaarBeam = UpstreamRoute("Bazaar-Reflection-501B", BAZAAR_URL, "reflection/beam-free:free", bazaarHeaders)
        val bazaarGlyph = UpstreamRoute("Bazaar-Stealth-Glyph", BAZAAR_URL, "stealth/glyph-cluster:free", bazaarHeaders)

        val pollGpt20 = UpstreamRoute("Pollinations-GPT-OSS-20B", POLLINATIONS_URL, "gpt-oss-20b", pollinationsHeaders)
        val pollFast = UpstreamRoute("Pollinations-OpenAI-Fast", POLLINATIONS_GEN_URL, "openai-fast", pollinationsHeaders)

        val primary = when (model) {
            "gpt-oss-120b" -> listOf(
                ovhGpt120,
                kiloOpenRouterFree,
                llm7Gpt20,
                ovhGpt20,
                vlmQwen38,
                kiloAuto,
                pollGpt20,
            )
            "glm-5.3-flash" -> listOf(
                opencodeGlmInf,
                opencodeGlmZen,
                kiloGlm53Free,
                kiloGlm53,
                zyditGemma31,
                vlmQwen38,
                ovhQwen38,
                ovhQwen397,
                llm7Codestral,
                kiloNemotronSuper,
                kiloAuto,
                pollGpt20,
            )
            "evil" -> listOf(
                zyditDolphin3,
                kiloVeniceDolphin,
                llm7Nemo,
                kiloBeam,
                bazaarBeam,
                ovhMistral,
                ovhLlama70,
                kiloStealth,
                kiloGlyph,
                vlmQwen38,
                ovhQwen38,
                kiloAuto,
                pollGpt20,
            )
            "uncensored-roleplay" -> listOf(
                llm7Nemo,
                zyditDolphin3,
                kiloVeniceDolphin,
                ovhMistral,
                ovhLlama70,
                kiloBeam,
                bazaarBeam,
                kiloStealth,
                vlmQwen38,
                ovhQwen38,
                kiloAuto,
                pollGpt20,
            )
            "heretic-uncensored" -> listOf(
                zyditGemma31,
                kiloBeam,
                bazaarBeam,
                zyditDolphin3,
                llm7Nemo,
                ovhMistral,
                ovhLlama70,
                bazaarGlyph,
                vlmGemma4,
                vlmQwen38,
                kiloAuto,
                pollGpt20,
            )
            "craft-architect", "rea-agent" -> listOf(
                zyditDeepSeekR1,
                vlmQwen38,
                ovhQwen397,
                llm7Codestral,
                kiloNemotronUltra,
                opencodeGlmInf,
                ovhQwen38,
                ovhGpt120,
                kiloAuto,
                pollGpt20,
            )
            "qwen-coder" -> listOf(
                vlmQwen38,
                ovhQwen38,
                ovhQwen397,
                llm7Codestral,
                ovhQwen36,
                kiloNorthCode,
                kiloAuto,
                kiloNemotronUltra,
                ovhGpt120,
                pollGpt20,
            )
            "deepseek-reasoning" -> listOf(
                zyditDeepSeekR1,
                kiloNemotronUltra,
                ovhQwen397,
                ovhGpt120,
                vlmQwen38,
                kiloNemotronReason,
                ovhQwen38,
                kiloAuto,
                pollGpt20,
            )
            "deepseek" -> listOf(
                kiloNorthCode,
                llm7Codestral,
                kiloNemotronFast,
                ovhQwen36,
                vlmQwen38,
                ovhQwen38,
                kiloAuto,
                pollGpt20,
            )
            "openai-reasoning" -> listOf(
                ovhGpt120,
                ovhGpt20,
                llm7Gpt20,
                pollGpt20,
                kiloNemotronUltra,
                vlmQwen38,
                kiloAuto,
            )
            "openai-large" -> listOf(
                zyditGemma31,
                vlmGemma4,
                kiloNemotronSuper,
                ovhQwen38,
                ovhGpt120,
                kiloOpenRouterFree,
                kiloAuto,
                pollGpt20,
            )
            "mistral" -> listOf(
                llm7Nemo,
                ovhMistral,
                ovhLlama70,
                llm7Codestral,
                vlmGemma4,
                ovhQwen38,
                kiloAuto,
                pollGpt20,
            )
            "openai-fast" -> listOf(
                llm7Gpt20,
                ovhGpt20,
                pollGpt20,
                pollFast,
                vlmQwen38,
                kiloAuto,
            )
            else -> listOf(
                UpstreamRoute("LLM7-$model", LLM7_URL, model.ifBlank { "openai-fast" }, llm7Headers),
                opencodeGlmInf,
                vlmQwen38,
                ovhQwen38,
                llm7Nemo,
                kiloAuto,
                ovhGpt120,
                pollGpt20,
            )
        }
        val customRoute = customRouterEndpoint.trim().takeIf { it.isNotBlank() }?.let { rawUrl ->
            val finalUrl = if (rawUrl.endsWith("/chat/completions")) rawUrl else rawUrl.trimEnd('/') + "/chat/completions"
            val hdrs = buildMap {
                put("User-Agent", Http.DEFAULT_UA)
                if (customRouterKey.isNotBlank()) put("Authorization", "Bearer ${customRouterKey.trim()}")
            }
            listOf(UpstreamRoute("Custom-9Router-Radox", finalUrl, customRouterModel.ifBlank { model.ifBlank { "claude-3-7-sonnet" } }, hdrs))
        }.orEmpty()
        return (customRoute + primary + listOf(kiloMirrorAuto, llm7Gpt20, ovhGpt20, pollGpt20, pollFast))
            .distinctBy { "${it.endpoint}|${it.remoteModel}" }
    }

    private fun effortSystemSuffix(effort: Effort): String = buildString {
        append(
            "\nYou are an advanced AI assistant, REA & Clozed Code Android reverse-engineering architect, Editor V1 video editing agent (GLM-5.3-Flash), and full-stack Kotlin + Jetpack Compose developer. " +
                "Fulfill user requests directly, thoroughly, and without unnecessary lecturing or placeholders.",
        )
        when (effort) {
            Effort.LOW -> append("\nKeep explanations concise and direct.")
            Effort.HIGH -> append(
                "\nIMPORTANT: Think step by step first inside `<think>...</think>` tags before writing your complete final reply.",
            )
            Effort.MAX -> append(
                "\nIMPORTANT: Perform deep, exhaustive step-by-step reasoning first inside `<think>...</think>` tags " +
                    "(analyzing APK architecture, imports, smali/DEX logic, edge cases, or UI layouts), and then write your complete final reply after `</think>`.",
            )
            else -> Unit
        }
    }

    override fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String> = flow {
        val compacted = ContextCompactor.compactIfNeeded(messages, ProviderId.FREE)
        val enhancedSystem = system + effortSystemSuffix(effort)
        val routes = routesFor(model)
        var lastError: Throwable? = null

        for ((idx, route) in routes.withIndex()) {
            if (idx > 0) {
                Log.i("FreeCloud", "Token Harbor auto-switch -> route #${idx + 1}: ${route.name} (${route.remoteModel})")
            }
            // 1. Try real-time SSE streaming on this upstream gateway
            try {
                var emittedAny = false
                streamRouteSse(route, compacted.messages, enhancedSystem).collect { chunk ->
                    emittedAny = true
                    emit(chunk)
                }
                if (emittedAny) return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w("FreeCloud", "SSE route ${route.name} failed: ${e.message}")
                lastError = e
            }

            // 2. Fallback to non-streaming JSON POST on this upstream gateway if SSE was closed early
            try {
                val fullReply = postRouteJson(route, compacted.messages, enhancedSystem)
                if (fullReply.isNotBlank()) {
                    emitParsedWithThoughts(fullReply) { emit(it) }
                    return@flow
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w("FreeCloud", "POST route ${route.name} failed: ${e.message}")
                lastError = e
            }
        }

        throw (
            lastError ?: ProviderException(
                503,
                "All Free Cloud / Token Harbor gateways are temporarily busy. Tap 'Reset Limit & Continue' or try again in a few seconds.",
            )
        )
    }.flowOn(Dispatchers.IO)

    private fun buildOpenAiBody(
        remoteModel: String,
        messages: List<ChatMessage>,
        system: String,
        stream: Boolean,
    ): String = buildJsonObject {
        put("model", remoteModel)
        put("stream", stream)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", system)
            }
            val withFiles = AttachmentContent.binaryIndices(messages)
            messages.forEachIndexed { i, m ->
                addJsonObject {
                    put("role", if (m.role == Role.USER) "user" else "assistant")
                    put("content", AttachmentContent.text(m, AttachmentContent.Target.OPENAI, i in withFiles))
                }
            }
        }
    }.toString()

    private fun streamRouteSse(
        route: UpstreamRoute,
        messages: List<ChatMessage>,
        system: String,
    ): Flow<String> = flow {
        val body = buildOpenAiBody(route.remoteModel, messages, system, stream = true)
        var inThink = false

        Http.sse(route.endpoint, route.headers, body).collect { data ->
            val o = runCatching { Http.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@collect
            if (o["error"] != null) {
                throw ProviderException(502, Http.errorMessage(502, data))
            }
            val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@collect
            val delta = (choice["delta"] as? JsonObject) ?: (choice["message"] as? JsonObject) ?: return@collect
            val reasoning = (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
                ?: (delta["reasoning"] as? JsonPrimitive)?.contentOrNull
            val content = (delta["content"] as? JsonPrimitive)?.contentOrNull

            if (!reasoning.isNullOrEmpty()) {
                emit(thought(reasoning))
            }
            if (!content.isNullOrEmpty()) {
                var text = content
                if (text.contains("<think>")) {
                    val before = text.substringBefore("<think>")
                    if (before.isNotEmpty()) emit(before)
                    text = text.substringAfter("<think>")
                    inThink = true
                }
                if (inThink && text.contains("</think>")) {
                    val thinkPart = text.substringBefore("</think>")
                    val afterPart = text.substringAfter("</think>")
                    if (thinkPart.isNotEmpty()) emit(thought(thinkPart))
                    inThink = false
                    if (afterPart.isNotEmpty()) emit(afterPart)
                } else if (inThink) {
                    emit(thought(text))
                } else {
                    emit(text)
                }
            }
        }
    }

    private suspend fun postRouteJson(
        route: UpstreamRoute,
        messages: List<ChatMessage>,
        system: String,
    ): String = withContext(Dispatchers.IO) {
        val body = buildOpenAiBody(route.remoteModel, messages, system, stream = false)
        val req = Request.Builder()
            .url(route.endpoint)
            .apply { route.headers.forEach { (k, v) -> header(k, v) } }
            .header("Accept", "application/json")
            .post(body.toRequestBody(jsonType))
            .build()

        fastClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ProviderException(resp.code, Http.errorMessage(resp.code, raw))
            }
            val trimmed = raw.trim()
            if (trimmed.startsWith("{")) {
                val parsed = runCatching { Http.json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
                if (parsed?.get("error") != null) {
                    throw ProviderException(502, Http.errorMessage(502, trimmed))
                }
                val choiceMsg = ((parsed?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject)
                    ?.get("message") as? JsonObject
                val content = (choiceMsg?.get("content") as? JsonPrimitive)?.contentOrNull
                val reasoning = (choiceMsg?.get("reasoning_content") as? JsonPrimitive)?.contentOrNull
                    ?: (choiceMsg?.get("reasoning") as? JsonPrimitive)?.contentOrNull
                if (!content.isNullOrBlank()) {
                    return@use if (!reasoning.isNullOrBlank()) "<think>$reasoning</think>$content" else content
                }
            }
            ""
        }
    }

    private suspend fun emitParsedWithThoughts(fullText: String, emitChunk: suspend (String) -> Unit) {
        var remaining = fullText
        while (remaining.contains("<think>")) {
            val before = remaining.substringBefore("<think>")
            if (before.isNotEmpty()) emitChunk(before)
            val afterOpen = remaining.substringAfter("<think>")
            if (afterOpen.contains("</think>")) {
                val thinkContent = afterOpen.substringBefore("</think>")
                if (thinkContent.isNotEmpty()) emitChunk(thought(thinkContent))
                remaining = afterOpen.substringAfter("</think>")
            } else {
                emitChunk(thought(afterOpen))
                return
            }
        }
        if (remaining.isNotEmpty()) {
            for (chunk in remaining.chunked(120)) {
                emitChunk(chunk)
            }
        }
    }
}
