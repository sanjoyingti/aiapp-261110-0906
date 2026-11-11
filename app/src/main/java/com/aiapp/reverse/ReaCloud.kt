package com.aiapp.reverse

import android.content.Context
import com.aiapp.builder.GitHubClient
import com.aiapp.data.Log
import com.aiapp.llm.Http
import com.aiapp.llm.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs the real REA toolkit (Reverse Engineer Anything, github.com/morluto/rea, MIT) for the
 * Reverse tab and adds what it finds to the Craft Anything study.
 *
 * REA is a desktop tool (Node.js, a Chrome browser, JADX with a JDK), so it cannot run inside an
 * Android app. Like JADX Reconstruct, the work is done on GitHub Actions:
 *
 * 1. a repository is created in the user's GitHub account (private when the sign-in allows it,
 *    otherwise public, like the app's other GitHub features),
 * 2. the APK (or the website address) is uploaded together with the workflow in `assets/rea/`,
 * 3. REA inspects the target on GitHub's runner and saves `evidence/rea-evidence.md`,
 * 4. the app downloads that report and puts it in front of the model.
 *
 * Without a GitHub sign-in, without internet, or if the run fails, the study is returned unchanged
 * and the app carries on with its on-device evidence.
 */
object ReaCloud {

    private const val TAG = "ReaCloud"
    private const val MARK_EVIDENCE = "<!-- rea-evidence -->"
    private const val MARK_PLAN = "<!-- approved-plan -->"

    /** Adds REA's evidence to [study]. Never throws. */
    suspend fun enrich(
        context: Context,
        study: CraftAnythingEngine.CraftStudyEvidence,
        apk: File?,
        websiteUrl: String?,
        githubToken: String,
        onStep: (String) -> Unit,
    ): CraftAnythingEngine.CraftStudyEvidence {
        if (githubToken.isBlank()) return study
        if (study.architectBlueprintMarkdown.contains(MARK_EVIDENCE)) return study
        if (apk == null && websiteUrl.isNullOrBlank()) return study
        val evidence = try {
            withTimeoutOrNull(16 * 60_000L) { Runner(context, githubToken, onStep).run(apk, websiteUrl) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "REA study failed: ${t.message}", t)
            onStep("REA study on GitHub didn't finish (${t.message?.take(120)}); using on-device evidence")
            null
        }
        if (evidence.isNullOrBlank()) return study
        return merge(study, evidence, isWebsite = apk == null)
    }

    private fun merge(
        study: CraftAnythingEngine.CraftStudyEvidence,
        evidence: String,
        isWebsite: Boolean,
    ): CraftAnythingEngine.CraftStudyEvidence {
        val shortEvidence = evidence.take(17_000)
        val original = study.architectBlueprintMarkdown
        // For websites the on-device study can only see the page's raw HTML; what REA observed in a
        // real browser replaces its generic component list.
        val kept = if (isWebsite) original.substringBefore("## 1. Native Android Compose Components to Build") else original
        val blueprint = buildString {
            appendLine(MARK_EVIDENCE)
            appendLine("The section below is REAL evidence collected by REA. Where it disagrees with anything later in this document, the REA evidence is correct.")
            appendLine()
            appendLine(shortEvidence)
            appendLine()
            appendLine("---")
            appendLine()
            append(kept)
        }
        var colors = study.extractedColors
        var fonts = study.extractedFonts
        if (isWebsite) {
            val rgb = Regex("rgba?\\((\\d{1,3}),\\s*(\\d{1,3}),\\s*(\\d{1,3})")
            val found = (lineAfter(evidence, "- background colours by use:") + " " + lineAfter(evidence, "- text colours by use:"))
                .let { rgb.findAll(it) }
                .map { m -> "#%02X%02X%02X".format(m.groupValues[1].toInt().coerceIn(0, 255), m.groupValues[2].toInt().coerceIn(0, 255), m.groupValues[3].toInt().coerceIn(0, 255)) }
                .distinct().take(12).toList()
            if (found.isNotEmpty()) colors = found
            val f = lineAfter(evidence, "- fonts by use:").substringBefore("; sizes:")
                .split(',').map { it.trim().replace(Regex("\\s+x\\d+$"), "") }.filter { it.isNotBlank() }.take(6)
            if (f.isNotEmpty()) fonts = f
        }
        val controls = evidence.lineSequence()
            .filter { it.startsWith("- button: ") || it.startsWith("- link: ") || it.startsWith("- textbox: ") || it.startsWith("- tab: ") || it.startsWith("- heading: ") }
            .map { it.removePrefix("- ").take(70) }.take(40).toList()
        return study.copy(
            architectBlueprintMarkdown = blueprint,
            extractedColors = colors,
            extractedFonts = fonts,
            layoutLandmarks = if (isWebsite && controls.isNotEmpty()) controls.take(20) else study.layoutLandmarks,
            interactiveFeatures = if (isWebsite && controls.isNotEmpty()) controls else study.interactiveFeatures,
        )
    }

    private fun lineAfter(text: String, prefix: String): String =
        text.lineSequence().firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim().orEmpty()

    // ---- Plan first ---------------------------------------------------------------------------------

    const val PLAN_SYSTEM: String =
        "You are a senior Android engineer planning how to rebuild an app or website as a new, original " +
            "Android app (Kotlin, Jetpack Compose, Material 3). Write a plan only. Do not write any code. " +
            "Use only what the evidence shows; when you have to guess, say it is an assumption. Be concrete and brief. " + "When the evidence lists backend hosts, endpoints or headers, the new app must call those same servers with the same request shape; " + "note where a server will likely reject a third-party client (app-signature attestation, certificate pinning, or a key that is not in the APK)."

    /** Prompt for the plan the user sees (and approves) before anything is built. */
    fun planPrompt(
        study: CraftAnythingEngine.CraftStudyEvidence,
        instruction: String,
        newAppName: String,
        newPackage: String,
    ): String = buildString {
        appendLine("Target studied: ${study.targetName} (${study.targetType}).")
        appendLine("The user asked: ${instruction.take(1_500)}")
        appendLine("New app name: `$newAppName`" + (if (newPackage.isNotBlank()) ", package `$newPackage`." else "."))
        appendLine()
        appendLine("Evidence collected about the target:")
        appendLine()
        appendLine(study.architectBlueprintMarkdown.replace(MARK_EVIDENCE, "").take(18_000))
        appendLine()
        appendLine("Write the plan in at most 450 words with exactly these headings:")
        appendLine("## What the target is")
        appendLine("## Screens to build")
        appendLine("## Features, screen by screen")
        appendLine("## Data and network (use the REAL base URLs, endpoints and headers from the evidence — do not invent them; wire the new app to the same servers)")
        appendLine("## Look and feel")
        appendLine("## Files I will write")
        appendLine("## What cannot be reproduced, and assumptions")
        appendLine("## Build order")
    }

    /** Puts the plan the user saw at the top of the study so the builder follows it. */
    fun withPlan(study: CraftAnythingEngine.CraftStudyEvidence, plan: String): CraftAnythingEngine.CraftStudyEvidence {
        val body = study.architectBlueprintMarkdown.substringAfter("$MARK_PLAN-end\n", study.architectBlueprintMarkdown)
        return study.copy(
            architectBlueprintMarkdown = "$MARK_PLAN\n# Plan shown to the user: follow it\n\n${plan.trim().take(4_000)}\n\n---\n$MARK_PLAN-end\n$body",
        )
    }

    // ---- GitHub side --------------------------------------------------------------------------------

    private class Runner(
        private val context: Context,
        private val token: String,
        private val onStep: (String) -> Unit,
    ) {
        private val gh = GitHubClient(token)
        private val api = "https://api.github.com"

        private fun asset(name: String): ByteArray = context.assets.open("rea/$name").use { it.readBytes() }

        private suspend fun call(method: String, path: String, body: String? = null, accept: String = "application/vnd.github+json"): String =
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url("$api$path")
                    .header("Authorization", "Bearer $token")
                    .header("Accept", accept)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .method(method, body?.toRequestBody("application/json".toMediaType()))
                    .build()
                Http.client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val msg = runCatching {
                            (Http.json.parseToJsonElement(text).jsonObject["message"] as? JsonPrimitive)?.contentOrNull
                        }.getOrNull() ?: "HTTP ${resp.code}"
                        throw ProviderException(resp.code, "GitHub: $msg")
                    }
                    text
                }
            }

        suspend fun run(apk: File?, websiteUrl: String?): String {
            onStep("🔬 REA: signing in to GitHub")
            val login = gh.login()

            val label = (apk?.nameWithoutExtension ?: websiteUrl.orEmpty().removePrefix("https://").removePrefix("http://"))
                .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "target" }.take(28)
            val name = "rea-study-$label-" + SimpleDateFormat("yyMMdd-HHmmss", Locale.US).format(Date())
            fun createBody(private: Boolean) = buildJsonObject {
                put("name", name)
                put("description", "REA study by AI App")
                put("private", private)
                put("auto_init", true)
            }.toString()
            onStep("🔬 REA: creating private GitHub repo $name")
            // Private first, so an uploaded APK stays out of public view. The app's GitHub sign-in asks
            // only for public-repo access, so GitHub may refuse; then a public repo is used, as the
            // app's other GitHub features do, and the status line says so.
            val createdText = try {
                call("POST", "/user/repos", createBody(private = true))
            } catch (e: ProviderException) {
                onStep("🔬 REA: this GitHub sign-in can't create private repos, using a public repo ($name)")
                call("POST", "/user/repos", createBody(private = false))
            }
            val created = Http.json.parseToJsonElement(createdText).jsonObject
            fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
            val repo = GitHubClient.Repo(
                owner = (created["owner"] as? JsonObject)?.str("login") ?: login,
                name = created.str("name") ?: name,
                branch = created.str("default_branch") ?: "main",
                url = created.str("html_url") ?: "https://github.com/$login/$name",
            )

            val files = linkedMapOf<String, ByteArray?>(
                ".github/workflows/rea-study.yml" to asset("rea-study.yml"),
                "rea_study.py" to asset("rea_study.py"),
                "capture.mjs" to asset("capture.mjs"),
            )
            val disk = linkedMapOf<String, File>()
            if (apk != null) {
                onStep("🔬 REA: uploading the APK (${"%.1f".format(apk.length() / 1_048_576.0)} MB)")
                disk["app.apk"] = apk
            } else {
                val url = websiteUrl!!.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
                files["target.txt"] = (url + "\n").toByteArray()
            }
            val sha = gh.commit(repo, files = files, message = "REA study", diskFiles = disk)

            onStep(
                if (apk != null) "🔬 REA: inspecting the APK on GitHub (package, classes, decompiled entry point)… a few minutes"
                else "🔬 REA: opening the site in a real browser on GitHub (screen, scripts, requests)… about 2 minutes",
            )
            var run: GitHubClient.Run? = null
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 14 * 60_000) {
                run = runCatching { gh.runFor(repo, sha, "rea-study.yml") }.getOrNull() ?: run
                if (run == null && System.currentTimeMillis() - start > 3 * 60_000) {
                    throw IllegalStateException("GitHub Actions didn't start for $name")
                }
                if (run?.status == "completed") break
                delay(8_000)
            }
            val done = run ?: throw IllegalStateException("GitHub Actions didn't start for $name")
            if (done.status != "completed") throw IllegalStateException("REA study timed out")
            if (done.conclusion != "success") {
                val why = runCatching { gh.failureSummary(repo, done.id) }.getOrDefault("")
                throw IllegalStateException("REA study failed. ${why.take(300)}")
            }

            onStep("🔬 REA: downloading the evidence")
            var evidence = ""
            for (attempt in 0 until 5) {
                evidence = runCatching {
                    call("GET", "/repos/${repo.owner}/${repo.name}/contents/evidence/rea-evidence.md", accept = "application/vnd.github.raw")
                }.getOrDefault("")
                if (evidence.isNotBlank()) break
                delay(3_000)
            }
            if (evidence.isBlank()) throw IllegalStateException("REA finished but its evidence file wasn't found")
            onStep("🔬 REA evidence received (${evidence.length / 1024} KB) · ${repo.url}")
            return evidence
        }
    }
}
