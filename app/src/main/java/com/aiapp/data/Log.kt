package com.aiapp.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A simple in-app error/event log the user can read and share, so failures can be reported with the
 * exact message and stack trace. Kept in memory (bounded) and mirrored to a file so it survives
 * restarts. Nothing leaves the phone unless the user shares it.
 */
object Log {
    private const val MAX = 800
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val entries = ArrayDeque<Entry>()
    private var file: File? = null

    data class Entry(val time: Long, val level: String, val tag: String, val message: String) {
        override fun toString() = "${SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(time))} $level/$tag: $message"
    }

    fun init(context: Context) {
        synchronized(this) {
            if (file != null) return
            file = File(context.filesDir, "app-errors.log")
            runCatching {
                file?.takeIf { it.exists() }?.readLines()?.takeLast(MAX)?.forEach { line ->
                    entries.addLast(Entry(0L, "", "", line))
                }
            }
        }
    }

    fun e(tag: String, message: String, t: Throwable? = null) = add("E", tag, message, t)
    fun w(tag: String, message: String) = add("W", tag, message, null)
    fun i(tag: String, message: String) = add("I", tag, message, null)

    private fun add(level: String, tag: String, message: String, t: Throwable?) {
        val full = buildString {
            append(message)
            if (t != null) {
                append(" :: ").append(t.javaClass.simpleName).append(": ").append(t.message)
                t.stackTrace.take(8).forEach { append("\n    at ").append(it.toString()) }
                t.cause?.let { c -> append("\n  caused by ").append(c.javaClass.simpleName).append(": ").append(c.message) }
            }
        }
        val entry = Entry(System.currentTimeMillis(), level, tag, full)
        synchronized(this) {
            entries.addLast(entry)
            while (entries.size > MAX) entries.removeFirst()
            runCatching { file?.appendText(entry.toString() + "\n") }
        }
    }

    fun list(): List<Entry> = synchronized(this) { entries.toList().asReversed() }

    fun dump(): String = synchronized(this) { entries.joinToString("\n") { it.toString() } }

    fun clear() = synchronized(this) {
        entries.clear()
        runCatching { file?.writeText("") }
    }
}
