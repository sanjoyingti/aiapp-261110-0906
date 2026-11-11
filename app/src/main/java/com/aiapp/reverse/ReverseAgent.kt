package com.aiapp.reverse

import kotlinx.serialization.json.Json

/**
 * Turns a plain-language instruction into a concrete [EditPlan] the engine can apply on-device.
 *
 * The model is given the decoded APK facts plus the app's real string resources, and asked to return
 * a small JSON plan of the edits that achieve the user's change. This covers the edits that are safe
 * and reliable to apply on a phone: visible text (string resources), app name, version and package.
 * Deeper logic changes (smali/bytecode) are out of scope here and are flagged for the rea backend.
 *
 * For apps the user is authorized to modify (their own, open-source, security research, modding).
 */
object ReverseAgent {

    private val json = Json { ignoreUnknownKeys = true }

    val SYSTEM =
        "You modify an existing Android APK on the user's phone for apps they are authorized to change. " +
        "You are given the decoded app and its string resources. Decide the smallest set of edits that " +
        "achieve the user's instruction, and reply with ONLY a JSON object, no prose, in this shape:\n" +
        "{\"summary\":\"one line\",\"edits\":[{\"type\":\"setString\",\"name\":\"res_name\",\"value\":\"new text\"}," +
        "{\"type\":\"rename\",\"label\":\"New App Name\"},{\"type\":\"version\",\"name\":\"1.2\",\"code\":12}," +
        "{\"type\":\"repackage\",\"pkg\":\"com.new.id\"}]}\n" +
        "Only use string resource names that appear in the provided list. If the change needs code/logic " +
        "edits you cannot express with these edit types, return an empty edits array and say so in summary."

    fun buildPrompt(report: ApkReport, strings: Map<String, String>, instruction: String): String = buildString {
        appendLine("DECODED APP:")
        appendLine(report.toBriefing())
        appendLine()
        appendLine("STRING RESOURCES (name = value):")
        strings.entries.take(400).forEach { (k, v) -> appendLine("$k = ${v.replace("\n", " ").take(200)}") }
        appendLine()
        appendLine("USER INSTRUCTION: $instruction")
        appendLine()
        appendLine("Reply with ONLY the JSON plan.")
    }

    // ---------- Code (smali) edits ----------

    val TERMS_SYSTEM =
        "You help locate code in a decompiled Android app. Given the user's change, reply with ONLY a " +
        "JSON object {\"terms\":[\"...\"]} of short identifiers or string literals likely to appear in the " +
        "relevant smali (class names, method names, ad/SDK names, resource ids, visible strings). 3-8 terms."

    val SMALI_SYSTEM =
        "You edit Android smali (Dalvik bytecode assembly) for apps the user is authorized to modify. " +
        "You are given whole classes. Return the FULL smali of ONLY the classes you change, each as:\n" +
        "=== CLASS: <descriptor> ===\n<full smali>\n=== END ===\n" +
        "Keep smali valid: correct .registers/.locals, label and type descriptors, and method signatures. " +
        "SURGICAL RULE: change ONLY the exact instructions needed for this one instruction. Every other line, " +
        "method, field, annotation and blank line of each returned class must be byte-for-byte identical to the " +
        "input — do not reformat, reorder, rename, or 'clean up' anything the user didn't ask about. Do not touch " +
        "any class you don't need to. Make the smallest possible change. If you cannot do it safely, return no blocks."

    fun termsPrompt(report: ApkReport, instruction: String): String =
        "App: ${report.packageName} (${report.runtimeHint}). Change requested: \"$instruction\".\n" +
        "Give the JSON search terms."

    fun parseTerms(reply: String, instruction: String): List<String> {
        val s = reply.indexOf('{'); val e = reply.lastIndexOf('}')
        val fromJson = if (s in 0 until e) runCatching { json.decodeFromString<TermList>(reply.substring(s, e + 1)).terms }.getOrNull() else null
        val terms = (fromJson ?: emptyList()).filter { it.isNotBlank() }
        // Fall back to instruction words if the model gave nothing usable.
        return terms.ifEmpty { instruction.split(Regex("[^A-Za-z0-9]+")).filter { it.length >= 4 } }.distinct().take(8)
    }

    fun smaliEditPrompt(instruction: String, candidates: List<SmaliEngine.SmaliClass>): String = buildString {
        appendLine("Instruction: $instruction")
        appendLine()
        appendLine("Candidate classes (full smali). Edit only what's needed:")
        candidates.forEach { c ->
            appendLine("=== CLASS: ${c.className} ===")
            appendLine(c.smali)
            appendLine("=== END ===")
        }
        appendLine()
        appendLine("Return the full smali of only the classes you change, in the same === CLASS === format.")
    }

    private val classBlock = Regex("""===\s*CLASS:\s*(\S+)\s*===\s*\n(.*?)\n===\s*END\s*===""", RegexOption.DOT_MATCHES_ALL)

    /** Parses the model's reply into className -> new full smali. */
    fun parseSmaliEdits(reply: String): Map<String, String> =
        classBlock.findAll(reply).associate { it.groupValues[1].trim() to it.groupValues[2] }

    /** Extracts the first JSON object from the model's reply and decodes it into an [EditPlan]. */
    fun parsePlan(reply: String): EditPlan {
        val text = reply.substringAfter("```json", reply).substringBefore("```").let {
            if (it.isBlank() || !it.contains("{")) reply else it
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return EditPlan(summary = "The model didn't return a usable plan.")
        return runCatching { json.decodeFromString<EditPlan>(text.substring(start, end + 1)) }
            .getOrElse { EditPlan(summary = "Couldn't read the model's plan: ${it.message}") }
    }
}
