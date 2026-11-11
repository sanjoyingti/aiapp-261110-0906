package com.aiapp.llm

/**
 * Automatic Context Compaction engine (modeled after Clozed Code's "Context Compacted" workflow).
 *
 * When reverse-engineering a large APK or iterating across many code/error-fix turns, the raw
 * prompt history can grow too large for a model's context window (causing hallucinations or HTTP 400/413).
 *
 * [ContextCompactor] compresses older messages and large file/DEX dumps into a dense, structured
 * architectural memory block sent to the LLM while keeping the user's visible chat transcript on
 * screen 100% untouched.
 */
object ContextCompactor {

    data class CompactionResult(
        val messages: List<ChatMessage>,
        val wasCompacted: Boolean,
        val originalChars: Int,
        val compactedChars: Int,
        val summaryNote: String,
    )

    /**
     * Compacts [messages] if their combined text exceeds [maxChars] (default 42,000 chars for cloud
     * models, 14,000 chars for offline models). Always preserves the most recent [keepRecentTurns]
     * messages verbatim and condenses earlier turns into a structured `[CONTEXT COMPACTED MEMORY]`
     * message.
     */
    fun compactIfNeeded(
        messages: List<ChatMessage>,
        provider: ProviderId,
        maxChars: Int = if (provider == ProviderId.OFFLINE) 14_000 else 42_000,
        keepRecentTurns: Int = 4,
    ): CompactionResult {
        val totalChars = messages.sumOf { it.text.length + it.attachments.sumOf { a -> a.text?.length ?: 0 } }
        if (totalChars <= maxChars || messages.size <= 1) {
            if (messages.size == 1 && totalChars > maxChars) {
                // Single huge message: trim middle of giant dumps while keeping head & tail
                val only = messages.first()
                val trimmedText = compactSingleText(only.text, maxChars)
                return CompactionResult(
                    messages = listOf(only.copy(text = trimmedText)),
                    wasCompacted = true,
                    originalChars = totalChars,
                    compactedChars = trimmedText.length,
                    summaryNote = "⚡ Context Compacted (${totalChars / 1000}K → ${trimmedText.length / 1000}K chars · chat history preserved)",
                )
            }
            return CompactionResult(messages, false, totalChars, totalChars, "")
        }

        val recentCount = keepRecentTurns.coerceAtMost(messages.size - 1).coerceAtLeast(1)
        val older = messages.dropLast(recentCount)
        val recent = messages.takeLast(recentCount)

        val memorySummary = buildString {
            appendLine("[⚡ CONTEXT COMPACTED — STRUCTURED PROJECT & CONVERSATION MEMORY]")
            appendLine("Earlier turns (${older.size} messages, ${totalChars / 1000}K chars) were compacted to prevent context overflow and hallucination:")
            older.forEachIndexed { idx, msg ->
                val roleLabel = if (msg.role == Role.USER) "USER" else "ASSISTANT"
                val filesMentioned = Regex("""(?:===\s*FILE:\s*|//\s*FILE:\s*)([^\s=]+)""")
                    .findAll(msg.text)
                    .map { it.groupValues[1] }
                    .toList()
                val cleaned = msg.text.replace(Regex("```[\\s\\S]*?```"), "[code block omitted in compacted summary]")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                appendLine("- Turn ${idx + 1} ($roleLabel): ${cleaned.take(450)}")
                if (filesMentioned.isNotEmpty()) {
                    appendLine("  Files touched: ${filesMentioned.joinToString(", ").take(300)}")
                }
            }
            appendLine("[END OF COMPACTED MEMORY — CONTINUE SEAMLESSLY FROM HERE]")
        }

        val recentBudget = (maxChars - memorySummary.length).coerceAtLeast(8_000)
        val perRecentMax = (recentBudget / recent.size.coerceAtLeast(1)).coerceAtLeast(3_000)
        val compactedRecent = recent.map { m ->
            if (m.text.length > perRecentMax) m.copy(text = compactSingleText(m.text, perRecentMax)) else m
        }

        val out = listOf(ChatMessage(Role.USER, memorySummary)) + compactedRecent
        val newChars = out.sumOf { it.text.length }
        return CompactionResult(
            messages = out,
            wasCompacted = true,
            originalChars = totalChars,
            compactedChars = newChars,
            summaryNote = "⚡ Context Compacted (${totalChars / 1000}K → ${newChars / 1000}K chars · chat history preserved)",
        )
    }

    fun compactSingleText(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val head = (maxChars * 0.65).toInt()
        val tail = (maxChars * 0.30).toInt()
        return text.take(head) +
            "\n\n... [⚡ Context Compacted: ${text.length - head - tail} middle chars compressed to preserve accuracy] ...\n\n" +
            text.takeLast(tail)
    }
}
