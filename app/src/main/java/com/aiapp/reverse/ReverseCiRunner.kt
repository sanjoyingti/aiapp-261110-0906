package com.aiapp.reverse

import com.aiapp.builder.GitHubClient
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rebuilds an APK with model-made edits using GitHub Actions instead of on-device libraries: the
 * model's job is only to decide the edits; apktool, aapt2, zipalign and apksigner (all present on
 * GitHub's `ubuntu-latest` runners) do the decode/patch/rebuild/sign/release — the same split the
 * Build tab already uses for writing whole apps.
 *
 * Supports the edits that are safe to express as a text patch: rename, version, string resources,
 * and whole-class smali replacements (the model's smali text is handed to apktool directly — it
 * reassembles the DEX itself, so no on-device disassembly/reassembly is needed for this path).
 * Repackaging isn't supported here (same limitation as the on-device path: apktool doesn't rewrite
 * every internal reference to a renamed package).
 */
class ReverseCiRunner(
    private val githubToken: String,
    private val onStep: (String) -> Unit = {},
) {
    private val gh = GitHubClient(githubToken)
    private val json = Json { prettyPrint = false }

    data class CiResult(val apkUrl: String, val repoUrl: String)

    suspend fun run(apk: File, appLabelHint: String, edits: List<ApkEdit>, smaliEdits: Map<String, String>): CiResult {
        if (edits.isEmpty() && smaliEdits.isEmpty()) throw IllegalArgumentException("No changes to apply.")

        onStep("Signing in to GitHub")
        gh.login()

        val slug = appLabelHint.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "app" }.take(30)
        val repoName = "aireverse-$slug-" + SimpleDateFormat("yyMMdd-HHmmss", Locale.US).format(Date())
        onStep("Creating GitHub repo $repoName")
        val repo = gh.createRepo(repoName, "Reverse-engineered patch built by AI App")

        val patch = buildPatchJson(edits, smaliEdits)

        onStep("Uploading the original APK (${"%.1f".format(apk.length() / 1_048_576.0)} MB)")
        val sha = gh.commit(
            repo,
            files = mapOf(
                ".github/workflows/reverse-build.yml" to WORKFLOW_YML.toByteArray(),
                "patch.json" to patch.toByteArray(),
            ),
            diskFiles = mapOf("app.apk" to apk),
            message = "Reverse-engineering patch",
        )

        onStep("Waiting for GitHub Actions to decode, patch, rebuild and sign…")
        val run = waitForRun(repo, sha) ?: throw IllegalStateException(
            "GitHub Actions didn't start. Check that Actions is enabled for $repoName, or try again.",
        )
        if (run.status != "completed") {
            throw IllegalStateException("Timed out waiting for the build. Check $repoName on GitHub directly.")
        }
        if (run.conclusion != "success") {
            val errors = gh.failureSummary(repo, run.id)
            throw IllegalStateException("The rebuild failed:\n${errors.take(1500)}")
        }

        onStep("Fetching the signed APK")
        val apkUrl = gh.latestApkUrl(repo)
            ?: throw IllegalStateException("The build succeeded, but GitHub hasn't published the APK yet. Open $repoName's Releases in a minute.")
        return CiResult(apkUrl, repo.url)
    }

    private fun buildPatchJson(edits: List<ApkEdit>, smaliEdits: Map<String, String>): String {
        var newLabel: String? = null
        var newVersionName: String? = null
        var newVersionCode: Int? = null
        val strings = mutableListOf<Pair<String, String>>()
        for (e in edits) when (e) {
            is ApkEdit.Rename -> newLabel = e.newLabel
            is ApkEdit.SetVersion -> { e.name?.let { newVersionName = it }; e.code?.let { newVersionCode = it } }
            is ApkEdit.SetString -> strings += e.name to e.value
            is ApkEdit.Repackage, is ApkEdit.ReplaceFile -> Unit // not supported on the CI path yet
        }
        val obj = buildJsonObject {
            newLabel?.let { put("label", it) }
            newVersionName?.let { put("versionName", it) }
            newVersionCode?.let { put("versionCode", it) }
            putJsonArray("strings") {
                strings.forEach { (name, value) -> addJsonObject { put("name", name); put("value", value) } }
            }
            putJsonArray("smaliClasses") {
                smaliEdits.forEach { (descriptor, smali) -> addJsonObject { put("descriptor", descriptor); put("smali", smali) } }
            }
        }
        return obj.toString()
    }

    private suspend fun waitForRun(repo: GitHubClient.Repo, sha: String): GitHubClient.Run? {
        var run: GitHubClient.Run? = null
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 35 * 60_000) {
            run = runCatching { gh.runFor(repo, sha, "reverse-build.yml") }.getOrNull() ?: run
            if (run == null && System.currentTimeMillis() - start > 3 * 60_000) return null
            if (run?.status == "completed") return run
            delay(10_000)
        }
        return run
    }

    companion object {
        /**
         * Runs entirely on GitHub's runner: `apktool d` decodes, a small Python script applies the
         * patch (manifest label/version, string resources, whole-class smali overwrites), `apktool b`
         * reassembles (including DEX — no on-device smali/baksmali needed), then zipalign + apksigner
         * (both ship with the runner's Android SDK) produce the signed APK, published as a release.
         */
        private val WORKFLOW_YML = """
            name: Reverse-engineering rebuild
            on: push
            permissions:
              contents: write
            jobs:
              rebuild:
                runs-on: ubuntu-latest
                timeout-minutes: 30
                steps:
                  - uses: actions/checkout@v4
                  - name: Decode with apktool
                    run: |
                      set -e
                      curl -sSL -o apktool.jar https://github.com/iBotPeaches/Apktool/releases/download/v2.11.1/apktool_2.11.1.jar
                      java -jar apktool.jar d app.apk -o decoded -f || { echo "::error::apktool decode failed"; exit 1; }
                  - name: Apply patch
                    run: |
                      set -e
                      python3 - <<'PYEOF'
                      import json, re, os, glob

                      with open("patch.json") as f:
                          patch = json.load(f)

                      manifest_path = "decoded/AndroidManifest.xml"
                      with open(manifest_path, "r", encoding="utf-8") as f:
                          manifest = f.read()

                      label = patch.get("label")
                      if label:
                          if re.search(r'android:label="[^"]*"', manifest):
                              manifest = re.sub(r'android:label="[^"]*"', 'android:label="%s"' % label, manifest, count=1)
                          else:
                              manifest = manifest.replace("<application ", '<application android:label="%s" ' % label, 1)

                      version_name = patch.get("versionName")
                      if version_name:
                          if re.search(r'android:versionName="[^"]*"', manifest):
                              manifest = re.sub(r'android:versionName="[^"]*"', 'android:versionName="%s"' % version_name, manifest, count=1)
                          else:
                              manifest = manifest.replace("<manifest ", '<manifest android:versionName="%s" ' % version_name, 1)

                      version_code = patch.get("versionCode")
                      if version_code:
                          vc = str(version_code)
                          if re.search(r'android:versionCode="[^"]*"', manifest):
                              manifest = re.sub(r'android:versionCode="[^"]*"', 'android:versionCode="%s"' % vc, manifest, count=1)
                          else:
                              manifest = manifest.replace("<manifest ", '<manifest android:versionCode="%s" ' % vc, 1)

                      with open(manifest_path, "w", encoding="utf-8") as f:
                          f.write(manifest)

                      # String resources: patch only the default locale's strings.xml, so translations
                      # in values-*/strings.xml aren't overwritten with the same (often English) text.
                      strings = {s["name"]: s["value"] for s in patch.get("strings", [])}
                      if strings:
                          for path in glob.glob("decoded/res/values/strings.xml"):
                              with open(path, "r", encoding="utf-8") as f:
                                  text = f.read()
                              changed = False
                              for name, value in strings.items():
                                  pattern = re.compile(r'(<string name="%s"[^>]*>)(.*?)(</string>)' % re.escape(name), re.DOTALL)
                                  escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                                  new_text, n = pattern.subn(r"\1" + escaped.replace("\\", "\\\\") + r"\3", text)
                                  if n:
                                      text = new_text
                                      changed = True
                              if changed:
                                  with open(path, "w", encoding="utf-8") as f:
                                      f.write(text)

                      # Whole-class smali overwrites: find the existing file across every smali*/ dir,
                      # or create it under the primary smali/ dir if the class is new.
                      def descriptor_to_rel_path(descriptor):
                          inner = descriptor.strip("L;")
                          return inner + ".smali"

                      for entry in patch.get("smaliClasses", []):
                          rel = descriptor_to_rel_path(entry["descriptor"])
                          existing = None
                          for base in sorted(glob.glob("decoded/smali*")):
                              candidate = os.path.join(base, rel)
                              if os.path.isfile(candidate):
                                  existing = candidate
                                  break
                          target = existing or os.path.join("decoded/smali", rel)
                          os.makedirs(os.path.dirname(target), exist_ok=True)
                          with open(target, "w", encoding="utf-8") as f:
                              f.write(entry["smali"])
                      PYEOF
                  - name: Rebuild with apktool
                    run: |
                      set -e
                      java -jar apktool.jar b decoded -o out-unsigned.apk 2>&1 | tee build.log
                      if [ ! -f out-unsigned.apk ]; then
                        echo "::error::apktool rebuild produced no APK"
                        tail -60 build.log | while IFS= read -r l; do echo "::error::${'$'}{l//%/%25}"; done
                        exit 1
                      fi
                  - name: Align and sign
                    run: |
                      set -e
                      BT=${'$'}(ls -d "${'$'}ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)
                      "${'$'}{BT}zipalign" -f -p 4 out-unsigned.apk out-aligned.apk
                      keytool -genkeypair -v -keystore rev.jks -storepass android -keypass android \
                        -alias rev -keyalg RSA -keysize 2048 -validity 10000 \
                        -dname "CN=AI App Reverse, O=AI App" 2>&1 | tail -5
                      "${'$'}{BT}apksigner" sign --ks rev.jks --ks-pass pass:android --key-pass pass:android \
                        --v1-signing-enabled true --v2-signing-enabled true \
                        --out signed.apk out-aligned.apk
                  - uses: softprops/action-gh-release@v2
                    with:
                      tag_name: reverse-${'$'}{{ github.run_number }}
                      name: Reverse build ${'$'}{{ github.run_number }}
                      files: signed.apk
                      make_latest: true
        """.trimIndent()
    }
}
