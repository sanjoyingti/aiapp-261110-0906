package com.aiapp.reverse

import com.aiapp.builder.GitHubClient
import com.aiapp.llm.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reconstructs an APK as an independent, openable Android Studio project: decompiles it with jadx
 * (`--export-gradle`, verified on GitHub's `ubuntu-latest` runner) on GitHub Actions, downloads the
 * resulting project, and hands it to the existing Build-tab pipeline — the same "upload a project,
 * fix compile errors against the model until it builds" loop already used for uploaded .zip projects.
 *
 * This is a decompile-to-source-reconstruction tool, the same category as jadx/apktool/Ghidra-based
 * workflows, for apps the user is authorized to work on: their own apps, open-source apps, security
 * research, learning. It is not a way to redistribute someone else's app — the output is a new,
 * independent project the user then builds and modifies themselves.
 */
class ReverseReconstructRunner(
    private val githubToken: String,
    private val onStep: (String) -> Unit = {},
) {
    private val gh = GitHubClient(githubToken)

    data class Result(val projectZip: File, val repoUrl: String)

    suspend fun run(apk: File, appLabelHint: String): Result {
        onStep("Signing in to GitHub")
        gh.login()

        val slug = appLabelHint.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "app" }.take(30)
        val repoName = "airecon-$slug-" + SimpleDateFormat("yyMMdd-HHmmss", Locale.US).format(Date())
        onStep("Creating GitHub repo $repoName")
        val repo = gh.createRepo(repoName, "Decompiled by AI App for reconstruction")

        onStep("Uploading the APK (${"%.1f".format(apk.length() / 1_048_576.0)} MB)")
        val sha = gh.commit(
            repo,
            files = mapOf(".github/workflows/decompile.yml" to ReconstructWorkflow.YML.toByteArray()),
            diskFiles = mapOf("app.apk" to apk),
            message = "Decompile for reconstruction",
        )

        onStep("Decompiling with jadx on GitHub Actions (this can take a few minutes for a big app)…")
        val run = waitForRun(repo, sha) ?: throw IllegalStateException(
            "GitHub Actions didn't start. Check that Actions is enabled for $repoName, or try again.",
        )
        if (run.status != "completed") throw IllegalStateException("Timed out waiting for decompilation. Check $repoName on GitHub directly.")
        if (run.conclusion != "success") {
            val errors = gh.failureSummary(repo, run.id)
            throw IllegalStateException("Decompilation failed:\n${errors.take(1500)}")
        }

        onStep("Downloading the decompiled project")
        val zipUrl = gh.latestAssetUrl(repo, ".zip")
            ?: throw IllegalStateException("The job succeeded, but GitHub hasn't published the project yet. Open $repoName's Releases in a minute.")
        val out = downloadFile(zipUrl, File(apk.parentFile, "${slug}-project.zip"))
        return Result(out, repo.url)
    }

    private suspend fun downloadFile(url: String, dst: File): File = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Download failed (HTTP ${resp.code}).")
            val body = resp.body ?: throw IllegalStateException("Download failed (empty).")
            dst.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        }
        dst
    }

    private suspend fun waitForRun(repo: GitHubClient.Repo, sha: String): GitHubClient.Run? {
        var run: GitHubClient.Run? = null
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 35 * 60_000) {
            run = runCatching { gh.runFor(repo, sha, "decompile.yml") }.getOrNull() ?: run
            if (run == null && System.currentTimeMillis() - start > 3 * 60_000) return null
            if (run?.status == "completed") return run
            delay(10_000)
        }
        return run
    }

    companion object {
        private val WORKFLOW_YML = """
            name: Decompile for reconstruction
            on: push
            permissions:
              contents: write
            jobs:
              decompile:
                runs-on: ubuntu-latest
                timeout-minutes: 30
                steps:
                  - uses: actions/checkout@v4
                  - name: Decompile with jadx (export as an Android Studio project)
                    run: |
                      set -e
                      curl -sSL -o jadx.zip https://github.com/skylot/jadx/releases/download/v1.5.3/jadx-1.5.3.zip
                      unzip -q jadx.zip -d jadx
                      chmod +x jadx/bin/jadx
                      ./jadx/bin/jadx -e --export-gradle-type android-app -d decompiled app.apk 2>&1 | tee jadx.log || true
                      if [ ! -d decompiled ]; then
                        echo "::error::jadx produced no output"
                        tail -60 jadx.log | while IFS= read -r l; do echo "::error::${'$'}{l//%/%25}"; done
                        exit 1
                      fi
                  - name: Zip the project
                    run: cd decompiled && zip -qr ../project.zip . && cd ..
                  - uses: softprops/action-gh-release@v2
                    with:
                      tag_name: reconstruct-${'$'}{{ github.run_number }}
                      name: Reconstructed project ${'$'}{{ github.run_number }}
                      files: project.zip
                      make_latest: true
        """.trimIndent()
    }
}
