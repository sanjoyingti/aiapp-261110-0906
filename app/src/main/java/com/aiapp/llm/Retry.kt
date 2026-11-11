package com.aiapp.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Busy or rate-limited servers: worth waiting a moment, rotating API key/gateway, or switching model. */
fun ProviderException.isTemporary() = code == 402 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504 ||
    (message?.contains("high demand", true) == true) ||
    (message?.contains("overloaded", true) == true) ||
    (message?.contains("rate limit", true) == true) ||
    (message?.contains("quota", true) == true)

/**
 * Runs the request; if the server is busy or rate-limited before any text arrives, waits and tries
 * again (2 s, 5 s, 10 s). If [fallback] is given, switches to a fallback model or rotated key.
 */
fun resilient(
    attempt: (model: String) -> Flow<String>,
    model: String,
    fallback: (suspend () -> String?)? = null,
    onRetry: (String) -> Unit = {},
): Flow<String> = flow {
    val waits = listOf(1_500L, 4_000L, 8_000L)
    var current = model
    for (i in 0..waits.size) {
        var emitted = false
        try {
            attempt(current).collect {
                emitted = true
                emit(it)
            }
            return@flow
        } catch (e: ProviderException) {
            if (emitted || !e.isTemporary() || i == waits.size) throw e
            if (i >= 1 && fallback != null) {
                val other = fallback()
                if (other != null && other != current) {
                    onRetry("API limit/busy on $current — switching to $other…")
                    current = other
                    continue
                }
            }
            onRetry("API limit/busy — auto-switching & retrying in ${waits[i] / 1000} s…")
            delay(waits[i])
        }
    }
}

/** A different, usually less busy model from the same provider (Flash/Haiku/mini/Coder/R1 first). */
suspend fun alternativeModel(provider: ProviderId, apiKey: String, current: String): String? {
    if (provider == ProviderId.OFFLINE) return null
    if (provider == ProviderId.FREE) {
        val freeCycle = listOf("rea-agent", "qwen-coder", "deepseek-reasoning", "glm-5.3-flash", "evil", "deepseek", "openai-large", "openai-fast")
        return freeCycle.firstOrNull { it != current }
    }
    val ids = runCatching { Providers.rankedModels(provider, apiKey).map { it.id } }.getOrNull() ?: return null
    val fast = listOf("flash", "haiku", "mini", "sonnet", "coder", "deepseek", "qwen")
    val bad = listOf("lite", "image", "tts", "audio", "live", "realtime", "embed", "transcribe", "search")
    return ids.firstOrNull { id -> id != current && fast.any { id.contains(it) } && bad.none { id.contains(it) } }
        ?: ids.firstOrNull { it != current && bad.none { b -> it.contains(b) } }
}
