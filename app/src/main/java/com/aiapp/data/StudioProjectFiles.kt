package com.aiapp.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.aiapp.builder.ProjectTemplate
import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Role
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Makes every generated Android Studio project .zip complete and up to date before it is saved.
 *
 * What it does (all additive; the existing packaging code only calls [complete] / [writeExtras]):
 *
 * 1. Keeps the whole project together: files from earlier replies stay in the zip and a file the
 *    model rewrote replaces its old version (the newest copy of a path always wins), so a "fix"
 *    round no longer produces a zip that holds only the changed files.
 * 2. Looks up the newest stable Android Gradle Plugin, Kotlin and matching Gradle on the internet
 *    (Google Maven, Maven Central, services.gradle.org) and writes them into the build files.
 * 3. Downloads the Gradle wrapper for that version (gradlew, gradlew.bat, gradle-wrapper.jar,
 *    gradle-wrapper.properties) and verifies the jar's checksum. Copies bundled in the app's assets
 *    are used when the phone is offline.
 * 4. Adds any missing build file (settings, root/app build script, gradle.properties, manifest,
 *    proguard rules, .gitignore) and creates stand-ins for resources the code refers to but the
 *    project doesn't define (strings, colors, themes, icons, xml), a common cause of failed builds.
 * 5. Removes older copies of the same .zip from Downloads/AIApp and the app's private storage.
 *
 * Nothing here ever throws: if a step fails the project is saved the way it was before.
 */
object StudioProjectFiles {

    private const val TAG = "StudioProjectFiles"

    /** Environment the core logic needs; kept abstract so it can run without Android. */
    class Env(
        val cacheDir: File?,
        val openAsset: (String) -> InputStream?,
        val http: (String) -> ByteArray? = { url -> httpGet(url) },
    )

    data class Toolchain(
        val agp: String?,
        val kotlin: String?,
        val gradle: String,
        val online: Boolean,
    )

    // ---------------------------------------------------------------------------------------------
    // Entry points (the only functions the existing code calls)
    // ---------------------------------------------------------------------------------------------

    /** Hook for `Downloads.compileChatToSourceZip`: completes [files] in place just before zipping. */
    fun complete(
        context: Context,
        files: MutableMap<String, ByteArray>,
        messages: List<ChatMessage>,
        latestReply: String,
        appName: String,
        packageName: String,
        zipName: String,
        imported: Boolean,
    ) {
        runCatching {
            val replies = buildList {
                messages.forEach { if (it.role == Role.ASSISTANT && it.text.isNotBlank()) add(it.text) }
                if (latestReply.isNotBlank() && lastOrNull() != latestReply) add(latestReply)
            }
            completeCore(envOf(context), files, replies, appName, packageName, imported)
        }.onFailure { Log.e(TAG, "Couldn't complete the project: ${it.message}", it) }
        runCatching { deleteOldCopies(context, zipName) }
    }

    /**
     * Hook for `Downloads.exportProjectZip`: writes the files a project needs but [existing] lacks
     * (Gradle wrapper, proguard rules, …) into the open [zos]. Existing entries are left alone.
     */
    fun writeExtras(
        context: Context,
        zos: ZipOutputStream,
        rootFolder: String,
        existing: Map<String, String>,
        appName: String,
        packageName: String,
        zipFileName: String,
    ) {
        runCatching {
            val work = linkedMapOf<String, ByteArray>()
            existing.forEach { (k, v) -> work[k.trimStart('/')] = v.toByteArray(Charsets.UTF_8) }
            val before = work.keys.toSet()
            // "imported = true": the entries are already written, so only add what is missing.
            completeCore(envOf(context), work, emptyList(), appName, packageName, imported = true)
            for ((path, bytes) in work.toSortedMap()) {
                if (path in before) continue
                zos.putNextEntry(ZipEntry("$rootFolder/$path"))
                zos.write(bytes)
                zos.closeEntry()
            }
        }.onFailure { Log.e(TAG, "Couldn't add the Gradle wrapper: ${it.message}", it) }
        runCatching { deleteOldCopies(context, zipFileName) }
    }

    private fun envOf(context: Context) = Env(
        cacheDir = File(context.filesDir, "studio-toolchain").apply { mkdirs() },
        openAsset = { name -> runCatching { context.assets.open("studio/$name") }.getOrNull() },
    )

    // ---------------------------------------------------------------------------------------------
    // Core (no Android types below this line except in deleteOldCopies)
    // ---------------------------------------------------------------------------------------------

    fun completeCore(
        env: Env,
        files: MutableMap<String, ByteArray>,
        replies: List<String>,
        appName: String,
        packageName: String,
        imported: Boolean,
    ): Toolchain {
        val notes = mutableListOf<String>()

        step("merge") { mergeEarlierReplies(files, replies, packageName, notes) }
        step("base files") { addMissingBaseFiles(files, appName, packageName, notes) }

        val currentAgp = findAgpVersion(files)
        val tc = runCatching { resolveToolchain(env, currentAgp, upgrade = !imported) }
            .getOrElse { Toolchain(null, null, gradleFor(currentAgp ?: "8.7.3", emptyList(), emptyMap()), false) }

        if (!imported) {
            step("versions") { applyVersions(files, tc, notes) }
            step("agp9") { adaptToBuiltInKotlin(files, notes) }
            step("namespace") { fixNamespaceAndManifest(files, packageName, notes) }
        }
        if (!imported) {
            step("components") { addMissingComponents(files, packageName, notes) }
            step("libraries") { addMissingLibraries(files, notes) }
        }
        step("resources") { addMissingResources(files, appName, packageName, notes) }
        step("imports") { reportUnresolvedImports(files, packageName, notes) }
        step("wrapper") { addGradleWrapper(env, files, tc, replaceProperties = !imported, notes = notes) }
        if (!imported) step("workflow") { useWrapperInWorkflows(files) }
        step("readme") { files["ANDROID_STUDIO_README.md"] = readme(appName, files, tc, notes).toByteArray() }
        return tc
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "Step '$name' skipped: ${t.message}") }
        }
    }

    // ---- 1. Keep earlier files, newest copy wins --------------------------------------------------

    private val packageRegex = Regex("^\\s*package\\s+([a-zA-Z0-9_.]+)", RegexOption.MULTILINE)

    /** Same placement rules as `Downloads.compileChatToSourceZip` uses for the latest reply. */
    private fun normalizePath(rawPath: String, code: String, pkgPath: String): String {
        val clean = rawPath.trim().trimStart('/', '\\')
        return when {
            clean.equals("settings.gradle.kts", true) || clean.equals("settings.gradle", true) -> clean
            clean.equals("build.gradle.kts", true) || clean.equals("build.gradle", true) ->
                if (code.contains("com.android.application") && code.contains("namespace")) "app/build.gradle.kts" else clean
            clean.equals("gradle.properties", true) -> "gradle.properties"
            clean.endsWith("AndroidManifest.xml", true) -> "app/src/main/AndroidManifest.xml"
            clean.startsWith("app/") || clean.startsWith("gradle/") || clean.startsWith(".github/") -> clean
            clean.startsWith("src/main/") -> "app/$clean"
            clean.endsWith(".kt") || clean.endsWith(".java") -> {
                val filePkg = packageRegex.find(code)?.groupValues?.get(1)?.replace('.', '/') ?: pkgPath
                "app/src/main/java/$filePkg/${clean.substringAfterLast('/')}"
            }
            clean.endsWith(".xml") -> {
                val fileName = clean.substringAfterLast('/')
                if (code.contains("<manifest")) "app/src/main/AndroidManifest.xml"
                else if (code.contains("<resources")) "app/src/main/res/values/$fileName"
                else "app/src/main/res/layout/$fileName"
            }
            else -> "app/src/main/$clean"
        }
    }

    private fun mergeEarlierReplies(
        files: MutableMap<String, ByteArray>,
        replies: List<String>,
        packageName: String,
        notes: MutableList<String>,
    ) {
        if (replies.size < 2) return
        val pkgPath = packageName.replace('.', '/')
        val merged = linkedMapOf<String, String>()
        val fromLatest = mutableSetOf<String>()
        replies.forEachIndexed { index, reply ->
            val latest = index == replies.lastIndex
            for ((raw, code) in Downloads.extractCodeFilesFromText(reply)) {
                if (code.isBlank()) continue
                if (!latest) {
                    // Skip guessed names and loose snippets from older replies.
                    if (Regex("^Screen\\d+\\.kt$").matches(raw.trim())) continue
                    if (raw.endsWith(".xml") && !code.contains("xmlns:") && !code.contains("<resources")) continue
                }
                var body = code
                val path = normalizePath(raw, code, pkgPath)
                if (path.endsWith(".kt") && !packageRegex.containsMatchIn(body)) body = "package $packageName\n\n$body"
                merged.remove(path) // re-insert so the newest copy is also last in order
                merged[path] = body
                if (latest) fromLatest += path else fromLatest -= path
            }
        }
        if (merged.isEmpty()) return

        var kept = 0
        var replaced = 0
        for ((path, body) in merged) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val old = files[path]
            if (old == null) kept++ else if (!old.contentEquals(bytes) && path in fromLatest) replaced++
            files[path] = bytes
        }

        // The packager renames the first Kotlin file of a reply to MainActivity.kt when that reply
        // has no MainActivity. In a fix round that creates a wrong duplicate; drop it.
        val realMain = files.entries.any { it.key.endsWith(".kt") && String(it.value).contains("class MainActivity") }
        if (realMain) {
            val mergedBodies = merged.values.map { it.substringAfter("\n\n", it).trim() }.toSet() + merged.values.map { it.trim() }
            val wrong = files.entries.filter { (path, bytes) ->
                if (!path.endsWith("/MainActivity.kt")) return@filter false
                val text = String(bytes)
                if (text.contains("class MainActivity")) return@filter false
                val t = text.trim()
                merged[path]?.trim() != t && (t in mergedBodies || t.substringAfter("\n\n", t).trim() in mergedBodies)
            }.map { it.key }
            wrong.forEach { files.remove(it) }
        }
        if (kept > 0 || replaced > 0) {
            notes += "Kept $kept file(s) from earlier steps and replaced $replaced older file(s) with the newest version."
        }
    }

    // ---- 2. Missing base files -------------------------------------------------------------------

    private fun String.isGradleFile() =
        endsWith(".gradle") || endsWith(".gradle.kts") || endsWith("libs.versions.toml")

    private fun text(files: Map<String, ByteArray>, path: String): String? = files[path]?.let { String(it, Charsets.UTF_8) }

    /** Folder of the application module ("app" unless the project says otherwise). */
    private fun appModule(files: Map<String, ByteArray>): String {
        val applies = Regex("com\\.android\\.application[\"']?\\s*\\)?(?!\\s*version)(?![^\\n]*apply\\s*\\(?\\s*false)")
        files.keys.filter { it.endsWith("/build.gradle.kts") || it.endsWith("/build.gradle") }
            .sortedBy { it.length }
            .forEach { p ->
                val t = text(files, p).orEmpty()
                if (applies.containsMatchIn(t) || t.contains("libs.plugins.android.application")) return p.substringBeforeLast('/')
            }
        return "app"
    }

    private fun addMissingBaseFiles(
        files: MutableMap<String, ByteArray>,
        appName: String,
        packageName: String,
        notes: MutableList<String>,
    ) {
        val template = ProjectTemplate.files(appName, packageName, emptyList())
        val module = appModule(files)
        val added = mutableListOf<String>()
        fun addIfMissing(path: String, alt: String?, content: () -> String?) {
            if (files.containsKey(path) || (alt != null && files.containsKey(alt))) return
            val c = content() ?: return
            files[path] = c.toByteArray(Charsets.UTF_8)
            added += path
        }
        addIfMissing("settings.gradle.kts", "settings.gradle") { template["settings.gradle.kts"] }
        addIfMissing("build.gradle.kts", "build.gradle") { template["build.gradle.kts"] }
        addIfMissing("$module/build.gradle.kts", "$module/build.gradle") { template["app/build.gradle.kts"] }
        addIfMissing("gradle.properties", null) { template["gradle.properties"] }
        addIfMissing(".gitignore", null) { template[".gitignore"] }
        addIfMissing("$module/src/main/AndroidManifest.xml", null) { ProjectTemplate.defaultManifest(appName) }
        addIfMissing("$module/proguard-rules.pro", null) {
            "# Add project specific ProGuard rules here.\n# https://developer.android.com/build/shrink-code\n"
        }

        // AndroidX must be switched on or no AndroidX/Compose project builds.
        val props = text(files, "gradle.properties").orEmpty()
        if (!Regex("(?m)^\\s*android\\.useAndroidX\\s*=").containsMatchIn(props)) {
            files["gradle.properties"] = (props.trimEnd() + "\nandroid.useAndroidX=true\n").toByteArray()
        }
        if (added.isNotEmpty()) notes += "Added missing project files: ${added.joinToString()}."
    }

    // ---- 3. Latest toolchain ----------------------------------------------------------------------

    /** Android Gradle Plugin → Gradle version it is released and tested with (developer.android.com). */
    private val builtInAgpToGradle = mapOf(
        "9.5" to "9.6.0", "9.4" to "9.6.0", "9.3" to "9.5.0", "9.2" to "9.4.1", "9.1" to "9.3.1", "9.0" to "9.1.0",
        "8.13" to "8.13", "8.12" to "8.13", "8.11" to "8.13", "8.10" to "8.11.1", "8.9" to "8.11.1",
        "8.8" to "8.10.2", "8.7" to "8.9", "8.6" to "8.7", "8.5" to "8.7", "8.4" to "8.6", "8.3" to "8.4",
        "8.2" to "8.2", "8.1" to "8.0", "8.0" to "8.0", "7.4" to "7.5", "7.3" to "7.4", "7.2" to "7.3.3",
        "7.1" to "7.2", "7.0" to "7.0",
    )

    private const val FALLBACK_GRADLE = "8.14.3"
    private const val CACHE_MS = 3L * 60 * 60 * 1000

    @Volatile private var cached: Triple<Long, List<String>, Triple<String?, String?, Map<String, String>>>? = null

    private val stable = Regex("^\\d+(\\.\\d+){1,3}$")

    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun majorMinor(v: String) = v.split('.').take(2).joinToString(".")

    fun latestStableFromMavenMetadata(xml: String): String? =
        Regex("<version>([^<]+)</version>").findAll(xml).map { it.groupValues[1].trim() }
            .filter { stable.matches(it) }
            .sortedWith { a, b -> compareVersions(a, b) }
            .lastOrNull()

    /** Stable Gradle releases from services.gradle.org/versions/all (no snapshots, RCs, milestones or broken builds). */
    fun stableGradleVersions(json: String): List<String> =
        Regex("\\{[^{}]*\\}").findAll(json).mapNotNull { m ->
            val o = m.value
            val v = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(o)?.groupValues?.get(1) ?: return@mapNotNull null
            fun flag(name: String) = Regex("\"$name\"\\s*:\\s*true").containsMatchIn(o)
            fun filled(name: String) = Regex("\"$name\"\\s*:\\s*\"[^\"]+\"").containsMatchIn(o)
            if (!stable.matches(v) || flag("snapshot") || flag("nightly") || flag("releaseNightly") ||
                flag("broken") || flag("activeRc") || filled("rcFor") || filled("milestoneFor")
            ) null else v
        }.toList()

    /** Reads the AGP → Gradle table from developer.android.com so new releases work without an app update. */
    fun parseAgpGradleTable(html: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        Regex("<td[^>]*>\\s*(\\d+\\.\\d+)\\s*</td>\\s*<td[^>]*>\\s*(\\d+\\.\\d+(?:\\.\\d+)?)\\s*</td>").findAll(html).forEach { m ->
            val agp = m.groupValues[1]
            val gradle = m.groupValues[2]
            val am = agp.substringBefore('.').toIntOrNull() ?: return@forEach
            val gm = gradle.substringBefore('.').toIntOrNull() ?: return@forEach
            if (am >= 7 && gm >= am - 1 && gm <= am + 1 && agp !in out) out[agp] = gradle
        }
        return out
    }

    /** The Gradle version to use with [agp]: the tested release line, at its newest patch when known. */
    fun gradleFor(agp: String, stableGradle: List<String>, liveTable: Map<String, String>): String {
        val mm = majorMinor(agp)
        val base = liveTable[mm] ?: builtInAgpToGradle[mm]
        val major = agp.substringBefore('.')
        if (base == null) {
            // Unknown (newer) plugin: newest stable Gradle of the same major line, else the newest overall.
            return stableGradle.filter { it.substringBefore('.') == major }.maxWithOrNull { a, b -> compareVersions(a, b) }
                ?: stableGradle.maxWithOrNull { a, b -> compareVersions(a, b) }
                ?: FALLBACK_GRADLE
        }
        val patch = stableGradle.filter { majorMinor(it) == majorMinor(base) && compareVersions(it, base) >= 0 }
            .maxWithOrNull { a, b -> compareVersions(a, b) }
        return patch ?: base
    }

    private fun resolveToolchain(env: Env, currentAgp: String?, upgrade: Boolean): Toolchain {
        val now = System.currentTimeMillis()
        var c = cached?.takeIf { now - it.first < CACHE_MS }
        var online = c != null
        if (c == null) {
            val gradleJson = env.http("https://services.gradle.org/versions/all")?.let { String(it) }
            val agpXml = env.http("https://dl.google.com/android/maven2/com/android/tools/build/gradle/maven-metadata.xml")?.let { String(it) }
            val kotlinXml = env.http("https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml")?.let { String(it) }
            val tableHtml = env.http("https://developer.android.com/build/releases/about-agp")?.let { String(it) }
            val gradles = gradleJson?.let { stableGradleVersions(it) }.orEmpty()
            val agp = agpXml?.let { latestStableFromMavenMetadata(it) }
            val kotlin = kotlinXml?.let { latestStableFromMavenMetadata(it) }
            val table = tableHtml?.let { parseAgpGradleTable(it) }.orEmpty()
            if (agp != null || gradles.isNotEmpty()) {
                c = Triple(now, gradles, Triple(agp, kotlin, table))
                cached = c
                online = true
                saveDiskCache(env, gradles, agp, kotlin, table)
            } else {
                c = loadDiskCache(env)
            }
        }
        val gradles = c?.second.orEmpty()
        val latestAgp = c?.third?.first
        val latestKotlin = c?.third?.second
        val table = c?.third?.third.orEmpty()

        val agp = when {
            upgrade && latestAgp != null && (currentAgp == null || compareVersions(latestAgp, currentAgp) > 0) -> latestAgp
            else -> currentAgp
        }
        val gradle = gradleFor(agp ?: "8.7.3", gradles, table)
        return Toolchain(
            agp = if (upgrade) agp else null,
            // Kotlin moves together with the plugin; KSP versions are tied to Kotlin, so leave those projects alone.
            kotlin = if (upgrade && latestAgp != null) latestKotlin else null,
            gradle = gradle,
            online = online,
        )
    }

    private fun saveDiskCache(env: Env, gradles: List<String>, agp: String?, kotlin: String?, table: Map<String, String>) {
        val dir = env.cacheDir ?: return
        runCatching {
            File(dir, "versions.txt").writeText(
                buildString {
                    appendLine("agp=${agp.orEmpty()}")
                    appendLine("kotlin=${kotlin.orEmpty()}")
                    appendLine("gradle=${gradles.joinToString(",")}")
                    appendLine("table=${table.entries.joinToString(",") { "${it.key}:${it.value}" }}")
                },
            )
        }
    }

    private fun loadDiskCache(env: Env): Triple<Long, List<String>, Triple<String?, String?, Map<String, String>>>? {
        val f = env.cacheDir?.let { File(it, "versions.txt") }?.takeIf { it.exists() } ?: return null
        return runCatching {
            val kv = f.readLines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
            val table = kv["table"].orEmpty().split(',').mapNotNull { e -> e.split(':').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            Triple(
                0L,
                kv["gradle"].orEmpty().split(',').filter { it.isNotBlank() },
                Triple(kv["agp"]?.ifBlank { null }, kv["kotlin"]?.ifBlank { null }, table),
            )
        }.getOrNull()
    }

    // ---- 4. Write versions into the build files ---------------------------------------------------

    private val agpPluginVersion = Regex(
        "(id\\s*\\(?\\s*[\"']com\\.android\\.(?:application|library|test|dynamic-feature)[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"'])(\\d[^\"']*)([\"'])",
    )
    private val agpClasspath = Regex("(com\\.android\\.tools\\.build:gradle:)(\\d[A-Za-z0-9.\\-]*)")
    private val agpCatalog = Regex("(?m)^(\\s*(?:agp|androidGradlePlugin|android-gradle-plugin|gradle-plugin)\\s*=\\s*\")(\\d[^\"]*)(\")")
    private val kotlinPluginVersion = Regex(
        "((?:id\\s*\\(?\\s*[\"']org\\.jetbrains\\.kotlin\\.[A-Za-z.\\-]+[\"']\\s*\\)?|kotlin\\(\\s*\"[a-z.\\-]+\"\\s*\\))\\s*version\\s*\\(?\\s*[\"'])(\\d[^\"']*)([\"'])",
    )
    private val kotlinClasspath = Regex("(org\\.jetbrains\\.kotlin:kotlin-gradle-plugin:)(\\d[A-Za-z0-9.\\-]*)")
    private val kotlinCatalog = Regex("(?m)^(\\s*kotlin\\s*=\\s*\")(\\d[^\"]*)(\")")

    fun findAgpVersion(files: Map<String, ByteArray>): String? {
        for ((path, bytes) in files) {
            if (!path.isGradleFile()) continue
            val t = String(bytes, Charsets.UTF_8)
            (agpPluginVersion.find(t)?.groupValues?.get(2)
                ?: agpClasspath.find(t)?.groupValues?.get(2)
                ?: agpCatalog.find(t)?.groupValues?.get(2))?.let { return it }
        }
        return null
    }

    private fun applyVersions(files: MutableMap<String, ByteArray>, tc: Toolchain, notes: MutableList<String>) {
        var agpFrom: String? = null
        var kotlinFrom: String? = null
        fun bump(text: String, regex: Regex, target: String, threeGroups: Boolean, onChange: (String) -> Unit): String =
            regex.replace(text) { m ->
                val cur = m.groupValues[2]
                if (stable.matches(cur) && compareVersions(target, cur) > 0) {
                    onChange(cur)
                    m.groupValues[1] + target + (if (threeGroups) m.groupValues[3] else "")
                } else m.value
            }
        val usesKsp = files.any { (p, b) -> p.isGradleFile() && String(b).contains("com.google.devtools.ksp") }
        for (path in files.keys.toList()) {
            if (!path.isGradleFile()) continue
            val before = text(files, path) ?: continue
            var t = before
            tc.agp?.let { v ->
                t = bump(t, agpPluginVersion, v, true) { agpFrom = it }
                t = bump(t, agpClasspath, v, false) { agpFrom = it }
                t = bump(t, agpCatalog, v, true) { agpFrom = it }
            }
            tc.kotlin?.takeIf { !usesKsp }?.let { v ->
                t = bump(t, kotlinPluginVersion, v, true) { kotlinFrom = it }
                t = bump(t, kotlinClasspath, v, false) { kotlinFrom = it }
                t = bump(t, kotlinCatalog, v, true) { kotlinFrom = it }
            }
            if (t != before) files[path] = t.toByteArray(Charsets.UTF_8)
        }
        agpFrom?.let { notes += "Android Gradle Plugin updated $it → ${tc.agp}." }
        kotlinFrom?.let { notes += "Kotlin updated $it → ${tc.kotlin}." }
    }

    /**
     * Android Gradle Plugin 9+ compiles Kotlin itself and refuses the separate
     * `org.jetbrains.kotlin.android` plugin, so remove it (or, for kapt projects, keep the old
     * behaviour with the documented opt-out switches).
     */
    private fun adaptToBuiltInKotlin(files: MutableMap<String, ByteArray>, notes: MutableList<String>) {
        val agp = findAgpVersion(files) ?: return
        val major = agp.substringBefore('.').toIntOrNull() ?: return
        if (major < 9) return
        val gradleFiles = files.keys.filter { it.isGradleFile() }
        val usesKapt = gradleFiles.any { p -> text(files, p).orEmpty().let { it.contains("kapt") } }
        if (usesKapt && major == 9) {
            var props = text(files, "gradle.properties").orEmpty().trimEnd()
            if (!props.contains("android.builtInKotlin")) props += "\nandroid.builtInKotlin=false"
            if (!props.contains("android.newDsl")) props += "\nandroid.newDsl=false"
            files["gradle.properties"] = (props + "\n").toByteArray()
            notes += "This project uses kapt, so built-in Kotlin is switched off in gradle.properties (works on AGP 9.x)."
            return
        }
        val pluginLine = Regex(
            "(?m)^[ \\t]*(?:id\\s*\\(?\\s*[\"']org\\.jetbrains\\.kotlin\\.android[\"']\\s*\\)?|kotlin\\(\\s*\"android\"\\s*\\)|" +
                "alias\\(\\s*libs\\.plugins\\.(?:kotlin\\.android|jetbrains\\.kotlin\\.android|kotlinAndroid)\\s*\\)|" +
                "apply\\s+plugin:\\s*[\"'](?:kotlin-android|org\\.jetbrains\\.kotlin\\.android)[\"'])" +
                "[^\\n]*\\r?\\n?",
        )
        val kotlinOptions = Regex("(?m)^[ \\t]*kotlinOptions\\s*\\{[^{}]*\\}[ \\t]*\\r?\\n?")
        var changed = false
        for (p in gradleFiles) {
            if (p.endsWith(".toml")) continue
            val before = text(files, p) ?: continue
            val after = kotlinOptions.replace(pluginLine.replace(before, ""), "")
            if (after != before) {
                files[p] = after.toByteArray(Charsets.UTF_8)
                changed = true
            }
        }
        if (changed) notes += "Removed the separate Kotlin Android plugin: AGP $agp has Kotlin built in."
    }

    private fun fixNamespaceAndManifest(files: MutableMap<String, ByteArray>, packageName: String, notes: MutableList<String>) {
        val module = appModule(files)
        val buildPath = listOf("$module/build.gradle.kts", "$module/build.gradle").firstOrNull { files.containsKey(it) } ?: return
        var build = text(files, buildPath) ?: return
        if (!Regex("\\bnamespace\\b").containsMatchIn(build)) {
            val kts = buildPath.endsWith(".kts")
            val line = if (kts) "namespace = \"$packageName\"" else "namespace '$packageName'"
            val m = Regex("(?m)^android\\s*\\{[ \\t]*\\r?\\n").find(build)
            if (m != null) {
                build = build.replaceRange(m.range, m.value + "    $line\n")
                files[buildPath] = build.toByteArray(Charsets.UTF_8)
                notes += "Added the missing namespace to $buildPath."
            }
        }
        val manifestPath = "$module/src/main/AndroidManifest.xml"
        val manifest = text(files, manifestPath) ?: return
        if (Regex("\\bnamespace\\b").containsMatchIn(build)) {
            val cleaned = Regex("(<manifest\\b[^>]*?)\\s+package\\s*=\\s*\"[^\"]*\"").replace(manifest, "$1")
            if (cleaned != manifest) {
                files[manifestPath] = cleaned.toByteArray(Charsets.UTF_8)
                notes += "Removed the outdated package attribute from AndroidManifest.xml (the namespace is set in Gradle)."
            }
        }
    }

    // ---- 5. Resources that are used but not defined -----------------------------------------------

    private val stubTypes = setOf("string", "color", "dimen", "bool", "integer", "style", "drawable", "mipmap", "xml")

    private fun addMissingResources(
        files: MutableMap<String, ByteArray>,
        appName: String,
        packageName: String,
        notes: MutableList<String>,
    ) {
        val module = appModule(files)
        val resRoot = "$module/src/main/res/"
        val defined = HashMap<String, MutableSet<String>>()
        fun define(type: String, name: String) {
            defined.getOrPut(type) { HashSet() }.add(name.replace('.', '_'))
        }
        val valueTag = Regex("<(string|color|dimen|bool|integer|style|drawable|plurals|string-array|integer-array|array|attr|id)\\s+[^>]*?name\\s*=\\s*\"([^\"]+)\"")
        val itemTag = Regex("<item\\s+[^>]*?type\\s*=\\s*\"(\\w+)\"[^>]*?name\\s*=\\s*\"([^\"]+)\"|<item\\s+[^>]*?name\\s*=\\s*\"([^\"]+)\"[^>]*?type\\s*=\\s*\"(\\w+)\"")
        for ((path, bytes) in files) {
            if (!path.contains("/src/main/res/")) continue
            val rel = path.substringAfter("/src/main/res/")
            val dir = rel.substringBefore('/')
            val type = dir.substringBefore('-')
            if (type == "values") {
                val t = String(bytes, Charsets.UTF_8)
                valueTag.findAll(t).forEach { m ->
                    val tag = m.groupValues[1]
                    define(if (tag.endsWith("array")) "array" else tag, m.groupValues[2])
                }
                itemTag.findAll(t).forEach { m ->
                    if (m.groupValues[1].isNotEmpty()) define(m.groupValues[1], m.groupValues[2])
                    else define(m.groupValues[4], m.groupValues[3])
                }
            } else {
                define(type, rel.substringAfterLast('/').substringBefore('.'))
            }
        }

        val missing = LinkedHashMap<String, LinkedHashSet<String>>() // type → names as referenced
        val notStubbed = LinkedHashSet<String>()
        fun need(type: String, name: String) {
            if (defined[type]?.contains(name.replace('.', '_')) == true) return
            if (type in stubTypes) missing.getOrPut(type) { LinkedHashSet() }.add(name)
            else if (type != "id" && type != "attr") notStubbed += "@$type/$name"
        }
        val xmlRef = Regex("(?<![\\w:+])@(string|color|dimen|bool|integer|style|drawable|mipmap|xml|font|raw|layout|menu|anim|array)/([A-Za-z0-9_.]+)")
        val codeRef = Regex("(?<![\\w.])R\\.(string|color|dimen|bool|integer|style|drawable|mipmap|xml|font|raw|layout|menu|array)\\.([A-Za-z0-9_]+)")
        val foreignR = Regex("(?m)^\\s*import\\s+(?!${Regex.escape(packageName)}\\.R\\b)[\\w.]+\\.R\\s*;?\\s*$")
        for ((path, bytes) in files) {
            if (!path.startsWith("$module/src/main/")) continue
            when {
                path.endsWith(".xml") -> xmlRef.findAll(String(bytes, Charsets.UTF_8)).forEach { need(it.groupValues[1], it.groupValues[2]) }
                path.endsWith(".kt") || path.endsWith(".java") -> {
                    val t = String(bytes, Charsets.UTF_8)
                    if (foreignR.containsMatchIn(t)) continue
                    codeRef.findAll(t).forEach { need(it.groupValues[1], it.groupValues[2]) }
                }
            }
        }
        if (missing.isEmpty() && notStubbed.isEmpty()) return

        val gradleText = files.filterKeys { it.isGradleFile() }.values.joinToString("\n") { String(it) }
        val themeParent = when {
            gradleText.contains("com.google.android.material:material") -> "Theme.MaterialComponents.DayNight.NoActionBar"
            gradleText.contains("androidx.appcompat:appcompat") -> "Theme.AppCompat.DayNight.NoActionBar"
            else -> "android:Theme.Material.Light.NoActionBar"
        }
        fun words(name: String) = name.replace('.', '_').split('_').filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("'", "\\'").replace("\"", "\\\"")

        val values = StringBuilder()
        var count = 0
        missing["string"]?.forEach { n ->
            val v = if (n == "app_name") appName else words(n)
            values.appendLine("    <string name=\"$n\">${esc(v)}</string>"); count++
        }
        missing["color"]?.forEach { n ->
            val l = n.lowercase()
            val v = when {
                "white" in l || l.startsWith("on") || "background" in l || "surface" in l -> "#FFFFFFFF"
                "black" in l || "text" in l -> "#FF000000"
                else -> "#FF6750A4"
            }
            values.appendLine("    <color name=\"$n\">$v</color>"); count++
        }
        missing["dimen"]?.forEach { values.appendLine("    <dimen name=\"$it\">16dp</dimen>"); count++ }
        missing["bool"]?.forEach { values.appendLine("    <bool name=\"$it\">false</bool>"); count++ }
        missing["integer"]?.forEach { values.appendLine("    <integer name=\"$it\">0</integer>"); count++ }
        missing["style"]?.forEach { values.appendLine("    <style name=\"$it\" parent=\"$themeParent\" />"); count++ }
        if (values.isNotEmpty()) {
            var name = "studio_generated.xml"
            var i = 2
            while (files.containsKey("${resRoot}values/$name")) name = "studio_generated_${i++}.xml"
            files["${resRoot}values/$name"] =
                ("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- Stand-ins for resources the code uses but the project did not define. Edit freely. -->\n" +
                    "<resources>\n$values</resources>\n").toByteArray(Charsets.UTF_8)
        }
        val icon = """
            <?xml version="1.0" encoding="utf-8"?>
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="108dp"
                android:height="108dp"
                android:viewportWidth="108"
                android:viewportHeight="108">
                <path android:fillColor="#FF6750A4" android:pathData="M0,0h108v108h-108z" />
                <path android:fillColor="#FFFFFFFF" android:pathData="M54,32a22,22 0,1 0,0.01 0z" />
            </vector>
        """.trimIndent() + "\n"
        missing["drawable"]?.forEach { files["${resRoot}drawable/$it.xml"] = icon.toByteArray(); count++ }
        missing["mipmap"]?.forEach { files["${resRoot}mipmap-anydpi/$it.xml"] = icon.toByteArray(); count++ }
        missing["xml"]?.forEach { n ->
            val l = n.lowercase()
            val head = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            val body = when {
                "path" in l || "provider" in l ->
                    "<paths>\n    <cache-path name=\"cache\" path=\".\" />\n    <files-path name=\"files\" path=\".\" />\n</paths>\n"
                "network" in l ->
                    "<network-security-config>\n    <base-config cleartextTrafficPermitted=\"false\" />\n</network-security-config>\n"
                "extraction" in l ->
                    "<data-extraction-rules>\n    <cloud-backup />\n    <device-transfer />\n</data-extraction-rules>\n"
                "backup" in l -> "<full-backup-content />\n"
                "shortcut" in l -> "<shortcuts xmlns:android=\"http://schemas.android.com/apk/res/android\" />\n"
                "locale" in l -> "<locale-config xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <locale android:name=\"en\" />\n</locale-config>\n"
                else -> "<resources />\n"
            }
            files["${resRoot}xml/$n.xml"] = (head + body).toByteArray(); count++
        }
        if (count > 0) notes += "Created $count stand-in resource(s) that the code refers to but the project did not define (see res/values/studio_generated*.xml, drawable, mipmap, xml)."
        if (notStubbed.isNotEmpty()) {
            notes += "Still missing and not created automatically (add these files yourself or ask for a fix): ${notStubbed.take(25).joinToString()}."
        }
    }

    // ---- 5b. Checks after generation: manifest classes, libraries, imports ---------------------------

    private val declRegex = Regex("\\b(?:class|object|interface|typealias)\\s+([A-Za-z_]\\w*)")
    private val memberRegex = Regex("\\b(?:fun|val|var)\\s+(?:<[^>]+>\\s*)?(?:[\\w<>?,.\\s]+?\\.)?([A-Za-z_]\\w*)")

    /** package → names declared in it (classes, objects, top-level functions and properties). */
    private fun declarations(files: Map<String, ByteArray>, module: String): Map<String, Set<String>> {
        val out = HashMap<String, MutableSet<String>>()
        for ((path, bytes) in files) {
            if (!path.startsWith("$module/src/") || !(path.endsWith(".kt") || path.endsWith(".java"))) continue
            val t = String(bytes, Charsets.UTF_8)
            val pkg = packageRegex.find(t)?.groupValues?.get(1) ?: ""
            val set = out.getOrPut(pkg) { HashSet() }
            declRegex.findAll(t).forEach { set += it.groupValues[1] }
            if (path.endsWith(".kt")) memberRegex.findAll(t).forEach { set += it.groupValues[1] }
            set += path.substringAfterLast('/').substringBeforeLast('.')
        }
        return out
    }

    private fun namespaceOf(files: Map<String, ByteArray>, module: String, fallback: String): String {
        val build = text(files, "$module/build.gradle.kts") ?: text(files, "$module/build.gradle")
        return build?.let { Regex("\\bnamespace\\s*=?\\s*[\"']([A-Za-z0-9_.]+)[\"']").find(it)?.groupValues?.get(1) } ?: fallback
    }

    /**
     * Every activity, service, receiver, provider and application class named in the manifest must
     * exist, or the app fails to build or crashes at launch. Missing ones get a small working class.
     */
    private fun addMissingComponents(files: MutableMap<String, ByteArray>, packageName: String, notes: MutableList<String>) {
        val module = appModule(files)
        val manifest = text(files, "$module/src/main/AndroidManifest.xml") ?: return
        val ns = namespaceOf(files, module, packageName)
        val decl = declarations(files, module)
        val tag = Regex("<(application|activity|service|receiver|provider)\\b[^>]*?android:name\\s*=\\s*\"([^\"]+)\"")
        val created = mutableListOf<String>()
        for (m in tag.findAll(manifest)) {
            val kind = m.groupValues[1]
            val raw = m.groupValues[2]
            if (raw.contains('$') || raw.startsWith("@")) continue
            val fqcn = when {
                raw.startsWith(".") -> ns + raw
                !raw.contains('.') -> "$ns.$raw"
                else -> raw
            }
            if (listOf("android.", "androidx.", "com.google.", "kotlin.", "java.").any { fqcn.startsWith(it) }) continue
            val pkg = fqcn.substringBeforeLast('.')
            val simple = fqcn.substringAfterLast('.')
            if (!Regex("^[A-Za-z_]\\w*$").matches(simple)) continue
            if (decl[pkg]?.contains(simple) == true) continue
            val body = when (kind) {
                "application" -> "class $simple : android.app.Application()\n"
                "activity" -> """
                    class $simple : android.app.Activity() {
                        override fun onCreate(savedInstanceState: android.os.Bundle?) {
                            super.onCreate(savedInstanceState)
                            setContentView(
                                android.widget.TextView(this).apply {
                                    text = "$simple"
                                    textSize = 20f
                                    setPadding(48, 96, 48, 48)
                                },
                            )
                        }
                    }
                """.trimIndent() + "\n"
                "service" -> """
                    class $simple : android.app.Service() {
                        override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null
                    }
                """.trimIndent() + "\n"
                "receiver" -> """
                    class $simple : android.content.BroadcastReceiver() {
                        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {}
                    }
                """.trimIndent() + "\n"
                else -> """
                    class $simple : android.content.ContentProvider() {
                        override fun onCreate(): Boolean = true
                        override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor? = null
                        override fun getType(uri: android.net.Uri): String? = null
                        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
                        override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
                        override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
                    }
                """.trimIndent() + "\n"
            }
            val path = "$module/src/main/java/${pkg.replace('.', '/')}/$simple.kt"
            if (files.containsKey(path)) continue
            files[path] = ("package $pkg\n\n// Stand-in created automatically: the manifest names this class but the project had no code for it.\n$body")
                .toByteArray(Charsets.UTF_8)
            created += fqcn
        }
        if (created.isNotEmpty()) {
            notes += "The manifest names ${created.size} class(es) the project had no code for; simple stand-ins were created: ${created.joinToString()}."
        }
    }

    /** import prefix → library to add when the code uses it but no build file lists it. */
    private val knownLibraries = listOf(
        "coil.compose" to "io.coil-kt:coil-compose:2.7.0",
        "coil" to "io.coil-kt:coil:2.7.0",
        "androidx.datastore.preferences" to "androidx.datastore:datastore-preferences:1.1.1",
        "androidx.lifecycle.compose" to "androidx.lifecycle:lifecycle-runtime-compose:2.8.7",
        "androidx.constraintlayout.compose" to "androidx.constraintlayout:constraintlayout-compose:1.0.1",
        "androidx.constraintlayout.widget" to "androidx.constraintlayout:constraintlayout:2.1.4",
        "androidx.appcompat" to "androidx.appcompat:appcompat:1.7.0",
        "com.google.android.material" to "com.google.android.material:material:1.12.0",
        "androidx.work" to "androidx.work:work-runtime-ktx:2.9.1",
        "androidx.media3.exoplayer" to "androidx.media3:media3-exoplayer:1.4.1",
        "androidx.media3.ui" to "androidx.media3:media3-ui:1.4.1",
        "androidx.webkit" to "androidx.webkit:webkit:1.12.1",
        "androidx.core.splashscreen" to "androidx.core:core-splashscreen:1.0.1",
        "androidx.biometric" to "androidx.biometric:biometric:1.1.0",
        "androidx.browser" to "androidx.browser:browser:1.8.0",
        "androidx.documentfile" to "androidx.documentfile:documentfile:1.0.1",
        "androidx.fragment" to "androidx.fragment:fragment-ktx:1.8.5",
        "androidx.preference" to "androidx.preference:preference-ktx:1.2.1",
        "androidx.recyclerview" to "androidx.recyclerview:recyclerview:1.3.2",
        "androidx.viewpager2" to "androidx.viewpager2:viewpager2:1.1.0",
        "androidx.swiperefreshlayout" to "androidx.swiperefreshlayout:swiperefreshlayout:1.1.0",
        "androidx.cardview" to "androidx.cardview:cardview:1.0.0",
        "androidx.security.crypto" to "androidx.security:security-crypto:1.1.0-alpha06",
        "com.google.accompanist.permissions" to "com.google.accompanist:accompanist-permissions:0.36.0",
        "com.google.accompanist.systemuicontroller" to "com.google.accompanist:accompanist-systemuicontroller:0.36.0",
        "com.airbnb.lottie.compose" to "com.airbnb.android:lottie-compose:6.5.2",
        "retrofit2.converter.gson" to "com.squareup.retrofit2:converter-gson:2.11.0",
        "retrofit2" to "com.squareup.retrofit2:retrofit:2.11.0",
        "com.google.gson" to "com.google.code.gson:gson:2.11.0",
        "okhttp3.logging" to "com.squareup.okhttp3:logging-interceptor:4.12.0",
        "okhttp3" to "com.squareup.okhttp3:okhttp:4.12.0",
        "org.jsoup" to "org.jsoup:jsoup:1.18.1",
        "com.bumptech.glide" to "com.github.bumptech.glide:glide:4.16.0",
        "androidx.navigation.compose" to "androidx.navigation:navigation-compose:2.8.5",
        "androidx.compose.material.icons" to "androidx.compose.material:material-icons-extended:1.7.8",
        "kotlinx.serialization.json" to "org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3",
        "kotlinx.coroutines" to "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0",
    )

    /** Adds libraries the code imports but the build file forgot (a frequent "Unresolved reference" cause). */
    private fun addMissingLibraries(files: MutableMap<String, ByteArray>, notes: MutableList<String>) {
        val module = appModule(files)
        val buildPath = listOf("$module/build.gradle.kts", "$module/build.gradle").firstOrNull { files.containsKey(it) } ?: return
        val gradleText = files.filterKeys { it.isGradleFile() }.values.joinToString("\n") { String(it, Charsets.UTF_8) }
        val imports = HashSet<String>()
        val importRegex = Regex("(?m)^\\s*import\\s+([A-Za-z0-9_.]+)")
        for ((path, bytes) in files) {
            if (!path.startsWith("$module/src/") || !(path.endsWith(".kt") || path.endsWith(".java"))) continue
            importRegex.findAll(String(bytes, Charsets.UTF_8)).forEach { imports += it.groupValues[1] }
        }
        val toAdd = LinkedHashSet<String>()
        for ((prefix, coordinate) in knownLibraries) {
            if (imports.none { it == prefix || it.startsWith("$prefix.") }) continue
            val groupArtifact = coordinate.substringBeforeLast(':')
            val artifact = groupArtifact.substringAfter(':')
            // Listed already, directly or through a version catalog entry for the same artifact.
            if (gradleText.contains(groupArtifact) || Regex("name\\s*=\\s*\"${Regex.escape(artifact)}\"").containsMatchIn(gradleText)) continue
            toAdd += coordinate
        }
        if (toAdd.isEmpty()) return
        val build = text(files, buildPath) ?: return
        val m = Regex("(?m)^dependencies\\s*\\{[ \\t]*\\r?\\n").find(build) ?: return
        val kts = buildPath.endsWith(".kts")
        val lines = toAdd.joinToString("") { c -> if (kts) "    implementation(\"$c\")\n" else "    implementation '$c'\n" }
        files[buildPath] = build.replaceRange(m.range, m.value + lines).toByteArray(Charsets.UTF_8)
        notes += "Added ${toAdd.size} library line(s) the code imports but the build file did not list: ${toAdd.joinToString()}."
    }

    /** Lists imports of the app's own classes that no file declares, so they can be fixed before building. */
    private fun reportUnresolvedImports(files: Map<String, ByteArray>, packageName: String, notes: MutableList<String>) {
        val module = appModule(files)
        val ns = namespaceOf(files, module, packageName)
        val decl = declarations(files, module)
        if (decl.isEmpty()) return
        val roots = setOf(ns, packageName).filter { it.isNotBlank() }
        val importRegex = Regex("(?m)^\\s*import\\s+(?:static\\s+)?([A-Za-z0-9_.]+)(\\.\\*)?")
        val missing = LinkedHashMap<String, String>() // import → first file using it
        for ((path, bytes) in files) {
            if (!path.startsWith("$module/src/") || !(path.endsWith(".kt") || path.endsWith(".java"))) continue
            for (m in importRegex.findAll(String(bytes, Charsets.UTF_8))) {
                val name = m.groupValues[1]
                if (roots.none { name == it || name.startsWith("$it.") }) continue
                val parts = name.split('.')
                if (m.groupValues[2].isNotEmpty()) {
                    if (decl.containsKey(name) || decl[name.substringBeforeLast('.')]?.contains(parts.last()) == true) continue
                } else {
                    if (parts.any { it == "R" || it == "BuildConfig" || it == "databinding" }) continue
                    // Accept a top-level name, or a nested one (pkg.Outer.Inner).
                    var found = false
                    for (cut in parts.size - 1 downTo 1) {
                        val pkg = parts.take(cut).joinToString(".")
                        if (decl[pkg]?.contains(parts[cut]) == true) { found = true; break }
                    }
                    if (found) continue
                }
                missing.putIfAbsent(name, path.substringAfterLast('/'))
            }
        }
        if (missing.isNotEmpty()) {
            notes += "Imports that point to code not present in the project (${missing.size}); ask for a fix round for these: " +
                missing.entries.take(30).joinToString { "${it.key} (used in ${it.value})" } + "."
        }
    }

    // ---- 6. Gradle wrapper -------------------------------------------------------------------------

    private fun tagFor(gradle: String): String {
        val parts = gradle.split('.').toMutableList()
        while (parts.size < 3) parts += "0"
        return "v" + parts.joinToString(".")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun cachedOrFetch(env: Env, cacheName: String, url: String, valid: (ByteArray) -> Boolean): ByteArray? {
        val f = env.cacheDir?.let { File(it, cacheName) }
        f?.takeIf { it.exists() && it.length() > 0 }?.let { runCatching { it.readBytes() }.getOrNull() }?.takeIf(valid)?.let { return it }
        val bytes = env.http(url)?.takeIf(valid) ?: return null
        runCatching { f?.writeBytes(bytes) }
        return bytes
    }

    private fun asset(env: Env, name: String): ByteArray? =
        runCatching { env.openAsset(name)?.use { it.readBytes() } }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun addGradleWrapper(
        env: Env,
        files: MutableMap<String, ByteArray>,
        tc: Toolchain,
        replaceProperties: Boolean,
        notes: MutableList<String>,
    ) {
        val propsPath = "gradle/wrapper/gradle-wrapper.properties"
        val existingVersion = text(files, propsPath)?.let {
            Regex("distributionUrl=.*?gradle-([0-9][0-9.]*[0-9])-(?:bin|all)\\.zip").find(it)?.groupValues?.get(1)
        }
        val keep = existingVersion != null && (!replaceProperties || compareVersions(existingVersion, tc.gradle) >= 0)
        val gradle = if (keep) existingVersion!! else tc.gradle
        val tag = tagFor(gradle)
        val raw = "https://raw.githubusercontent.com/gradle/gradle/$tag"
        val added = mutableListOf<String>()

        if (!keep) {
            val distSha = env.http("https://services.gradle.org/distributions/gradle-$gradle-bin.zip.sha256")
                ?.let { String(it).trim() }?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) }
            files[propsPath] = buildString {
                appendLine("distributionBase=GRADLE_USER_HOME")
                appendLine("distributionPath=wrapper/dists")
                if (distSha != null) appendLine("distributionSha256Sum=$distSha")
                appendLine("distributionUrl=https\\://services.gradle.org/distributions/gradle-$gradle-bin.zip")
                appendLine("networkTimeout=10000")
                appendLine("validateDistributionUrl=true")
                appendLine("zipStoreBase=GRADLE_USER_HOME")
                appendLine("zipStorePath=wrapper/dists")
            }.toByteArray()
            added += propsPath
        }

        val jarPath = "gradle/wrapper/gradle-wrapper.jar"
        if (!files.containsKey(jarPath)) {
            val isJar = { b: ByteArray -> b.size > 10_000 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() }
            var jar = cachedOrFetch(env, "gradle-wrapper-$gradle.jar", "$raw/gradle/wrapper/gradle-wrapper.jar", isJar)
            if (jar != null) {
                // Check the download against Gradle's published checksum when it can be reached.
                val expected = env.http("https://services.gradle.org/distributions/gradle-$gradle-wrapper.jar.sha256")
                    ?.let { String(it).trim() }?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) }
                if (expected != null && expected != sha256(jar)) {
                    env.cacheDir?.let { File(it, "gradle-wrapper-$gradle.jar").delete() }
                    jar = null
                }
            }
            (jar ?: asset(env, "gradle-wrapper.jar"))?.let { files[jarPath] = it; added += jarPath }
        }
        if (!files.containsKey("gradlew")) {
            val ok = { b: ByteArray -> String(b.copyOf(minOf(b.size, 64))).startsWith("#!") }
            (cachedOrFetch(env, "gradlew-$gradle", "$raw/gradlew", ok) ?: asset(env, "gradlew"))?.let { files["gradlew"] = it; added += "gradlew" }
        }
        if (!files.containsKey("gradlew.bat")) {
            val ok = { b: ByteArray -> String(b.copyOf(minOf(b.size, 400))).contains("@rem", ignoreCase = true) }
            (cachedOrFetch(env, "gradlew-$gradle.bat", "$raw/gradlew.bat", ok) ?: asset(env, "gradlew.bat"))?.let { files["gradlew.bat"] = it; added += "gradlew.bat" }
        }
        if (added.isNotEmpty()) notes += "Gradle wrapper (Gradle $gradle): ${added.joinToString()}."
    }

    /** The bundled CI workflow pins its own Gradle; make it use the project's wrapper so both always match. */
    private fun useWrapperInWorkflows(files: MutableMap<String, ByteArray>) {
        if (!files.containsKey("gradlew")) return
        val call = Regex("(?<![\\w./])gradle (assemble\\w+)")
        for (p in files.keys.filter { it.startsWith(".github/workflows/") && (it.endsWith(".yml") || it.endsWith(".yaml")) }) {
            val before = text(files, p) ?: continue
            val after = call.replace(before) { "chmod +x gradlew && ./gradlew ${it.groupValues[1]}" }
            if (after != before) files[p] = after.toByteArray(Charsets.UTF_8)
        }
    }

    // ---- 7. Read-me inside the zip -----------------------------------------------------------------

    private fun readme(appName: String, files: Map<String, ByteArray>, tc: Toolchain, notes: List<String>): String = buildString {
        val gradle = text(files, "gradle/wrapper/gradle-wrapper.properties")
            ?.let { Regex("gradle-([0-9][0-9.]*[0-9])-(?:bin|all)\\.zip").find(it)?.groupValues?.get(1) } ?: tc.gradle
        appendLine("# $appName: open in Android Studio")
        appendLine()
        appendLine("1. Unzip, then in Android Studio choose **File > Open** and pick this folder.")
        appendLine("2. Wait for the Gradle sync (the first one downloads Gradle $gradle and the libraries).")
        appendLine("3. **Build > Build APK(s)**, or press Run.")
        appendLine()
        appendLine("## Build tools in this project")
        appendLine()
        appendLine("- Gradle: $gradle (wrapper included: `gradlew`, `gradlew.bat`, `gradle/wrapper/`)")
        findAgpVersion(files)?.let { appendLine("- Android Gradle Plugin: $it") }
        tc.kotlin?.let { appendLine("- Kotlin: $it") }
        appendLine("- JDK: 17 or newer (Android Studio's bundled JDK is fine)")
        appendLine(
            if (tc.online) "- Versions were checked online when this zip was made."
            else "- The phone was offline when this zip was made, so the versions already in the project were kept.",
        )
        appendLine()
        appendLine("`local.properties` is not included on purpose: Android Studio creates it with the path to your own SDK.")
        appendLine("On macOS/Linux run `chmod +x gradlew` once before using the command line.")
        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("## What was added or changed automatically")
            appendLine()
            notes.forEach { appendLine("- $it") }
        }
    }

    // ---- 8. Remove older copies of the same zip ----------------------------------------------------

    private fun deleteOldCopies(context: Context, zipName: String) {
        // Private copies made for the in-app project card ("<time>_<zipName>").
        runCatching {
            File(context.filesDir, "attachments").listFiles()
                ?.filter { it.isFile && it.name.endsWith("_$zipName") }
                ?.forEach { it.delete() }
        }
        // Copies in Downloads/AIApp: "<name>.zip" and the system's "<name> (1).zip" duplicates.
        val base = zipName.substringBeforeLast('.')
        val ext = zipName.substringAfterLast('.', "")
        val same = Regex("^" + Regex.escape(base) + "( \\(\\d+\\))?\\." + Regex.escape(ext) + "$")
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val ids = mutableListOf<Long>()
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("%" + Environment.DIRECTORY_DOWNLOADS + "/AIApp%", "$base%"),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (same.matches(c.getString(1).orEmpty())) ids += c.getLong(0)
                }
            }
            ids.forEach { id ->
                runCatching { context.contentResolver.delete(android.content.ContentUris.withAppendedId(uri, id), null, null) }
            }
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AIApp").listFiles()
                ?.filter { same.matches(it.name) }
                ?.forEach { it.delete() }
        }
    }

    // ---- HTTP ---------------------------------------------------------------------------------------

    /** Small GET with redirects and short timeouts; returns null on any failure. */
    fun httpGet(url: String): ByteArray? {
        var current = url
        repeat(5) {
            val conn = runCatching { URL(current).openConnection() as HttpURLConnection }.getOrNull() ?: return null
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "AIApp-StudioProjectFiles")
                val code = conn.responseCode
                if (code in 300..399) {
                    current = conn.getHeaderField("Location")?.let { URL(URL(current), it).toString() } ?: return null
                    return@repeat
                }
                if (code != 200) return null
                val out = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > 8 * 1024 * 1024) return null
                    }
                }
                return out.toByteArray()
            } catch (t: Throwable) {
                return null
            } finally {
                runCatching { conn.disconnect() }
            }
        }
        return null
    }
}
