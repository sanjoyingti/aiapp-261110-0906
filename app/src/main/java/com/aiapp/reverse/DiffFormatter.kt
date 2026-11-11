package com.aiapp.reverse

/**
 * A tiny, dependency-free line diff used to SHOW the user exactly what a patch changes before it is
 * built, so they can confirm that only the requested code was touched (and nothing else).
 *
 * Produces a compact unified-style diff: unchanged lines are dropped, removed lines are prefixed
 * with `-` and added lines with `+`, with a few lines of surrounding context. This is for display
 * only — the real patch is still the full-class smali / resource edit the engine applies.
 */
object DiffFormatter {

    /** Builds a unified diff of [oldText] -> [newText]. [context] lines of unchanged text are kept around each change. */
    fun unified(oldText: String, newText: String, context: Int = 2, maxLines: Int = 160): String {
        val a = oldText.split("\n")
        val b = newText.split("\n")
        val ops = diffOps(a, b)

        // Mark which indices (in the merged op list) are within [context] of a change, so we only print hunks.
        val keep = BooleanArray(ops.size)
        for (i in ops.indices) {
            if (ops[i].tag != Tag.SAME) {
                for (j in (i - context).coerceAtLeast(0)..(i + context).coerceAtMost(ops.size - 1)) keep[j] = true
            }
        }

        val sb = StringBuilder()
        var printedGap = false
        var printed = 0
        for (i in ops.indices) {
            if (!keep[i]) {
                if (!printedGap && sb.isNotEmpty()) { sb.append("…\n"); printedGap = true }
                continue
            }
            printedGap = false
            val op = ops[i]
            val prefix = when (op.tag) { Tag.ADD -> "+ "; Tag.DEL -> "- "; Tag.SAME -> "  " }
            sb.append(prefix).append(op.line).append('\n')
            if (++printed >= maxLines) { sb.append("… (diff truncated)\n"); break }
        }
        return sb.toString().trimEnd('\n').ifBlank { "(no textual change)" }
    }

    private enum class Tag { SAME, ADD, DEL }
    private data class Op(val tag: Tag, val line: String)

    /** Classic LCS-based line diff. Inputs are small (single classes / resource blocks), so O(n*m) is fine. */
    private fun diffOps(a: List<String>, b: List<String>): List<Op> {
        val n = a.size; val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<Op>(n + m)
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out.add(Op(Tag.SAME, a[i])); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { out.add(Op(Tag.DEL, a[i])); i++ }
                else -> { out.add(Op(Tag.ADD, b[j])); j++ }
            }
        }
        while (i < n) { out.add(Op(Tag.DEL, a[i])); i++ }
        while (j < m) { out.add(Op(Tag.ADD, b[j])); j++ }
        return out
    }
}
