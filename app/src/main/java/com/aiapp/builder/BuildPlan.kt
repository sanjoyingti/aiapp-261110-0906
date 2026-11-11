package com.aiapp.builder

import androidx.compose.runtime.mutableStateListOf

/**
 * One shared, numbered list of everything the user has asked for, from Chat, the Reverse tab and the
 * Build tab. It is shown at the top of the Build screen, and when a build starts it is written into
 * the build's first message ("It will work on: 1… 2… 3…") and handed to the build agent, so a single
 * Build implements all of it. The list is cleared once a build has started.
 */
object BuildPlan {
    val items = mutableStateListOf<String>()

    /** Short replies that are not requests ("build", "ok", "thanks"…). */
    private val notARequest = Regex(
        "^\\s*(build|build it|build now|start|go|ok|okay|yes|no|thanks?|thank you|hi|hello|hey|continue|retry|done)\\s*[.!?]*\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** Adds [text] unless it is empty, a short non-request reply, or already listed. */
    fun add(text: String) {
        val t = text.trim()
        if (t.length < 4 || notARequest.matches(t)) return
        if (items.none { it.equals(t, ignoreCase = true) }) items.add(t)
    }

    fun remove(index: Int) {
        if (index in items.indices) items.removeAt(index)
    }

    fun clear() = items.clear()

    const val BRIEF_HEADER = "Implement ALL of these requirements in the app:"

    /**
     * The build's description with the numbered work list attached. A description that already is a
     * numbered brief is left as it is.
     */
    fun describe(description: String): String {
        val list = items.toList()
        val task = description.trim()
        if (list.isEmpty() || task.startsWith(BRIEF_HEADER)) return task
        return buildString {
            append(task)
            append("\n\n📋 It will work on:\n")
            append(list.mapIndexed { i, r -> "${i + 1}. $r" }.joinToString("\n"))
        }
    }
}
