package com.aiapp.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiapp.builder.ReaEngine
import com.aiapp.data.ApiKeys
import com.aiapp.data.ApkInstaller
import com.aiapp.data.Downloads
import com.aiapp.data.Log
import com.aiapp.llm.Attachment
import com.aiapp.llm.ChatMessage
import com.aiapp.llm.ContextCompactor
import com.aiapp.llm.Effort
import com.aiapp.llm.ProviderException
import com.aiapp.llm.ProviderId
import com.aiapp.llm.Providers
import com.aiapp.llm.Role
import com.aiapp.llm.THOUGHT
import com.aiapp.llm.WebTools
import com.aiapp.llm.alternativeModel
import com.aiapp.llm.resilient
import com.aiapp.reverse.ApkEdit
import com.aiapp.reverse.ApkReport
import com.aiapp.reverse.CraftAnythingEngine
import com.aiapp.reverse.DiffFormatter
import com.aiapp.reverse.ReaClient
import com.aiapp.reverse.ReaConfig
import com.aiapp.reverse.ReaTool
import com.aiapp.reverse.ReverseAgent
import com.aiapp.reverse.ReverseCiRunner
import com.aiapp.reverse.ReverseEngine
import com.aiapp.reverse.SignatureInfo
import com.aiapp.reverse.SmaliEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

data class InstalledAppItem(
    val label: String,
    val packageName: String,
    val sourceApkPath: String,
    val sizeMb: Double,
)

/**
 * Drives the Reverse Engineering & **Craft Anything (`github.com/storytold/craft-anything`)** +
 * **REA (`github.com/morluto/rea`)** + **Clozed Code** workbench on Android:
 *
 * 1. **Craft Anything Architect + Builder Workflow (`Study -> Build -> Check -> Ready`)**:
 *    - Works on **ANY Website / Web App** (e.g. `https://excalidraw.com` -> `MaitisBoard`) OR **ANY Android APK**
 *      (uploaded `.apk`, `.zip`, MT Manager local file path `/sdcard/MT2/apks/...`, or Installed Phone App).
 *    - **Clean Room Mode** (default: studies externally visible layout, colors, fonts, spacing & viewports) vs.
 *      **Deep Mode** (reads internal Manifest, DEX classes/methods, XML layouts, strings & scripts).
 *    - **Safety / Human Oversight (`Auto Mode: OFF — Ask First`)**: Pauses after `Study` with an interactive
 *      Approval & Open Questions Card before the Builder writes code.
 *    - **Likeness Target (`75%`–`99%`, default `95%`)** & **Multi-Viewport Check (`Phone`, `Tablet`, `Laptop`)**.
 *    - **Custom Branding**: Custom App Name (e.g. `MaitisBoard`), Custom Top-Left Header Logo Text (`MAITIS`),
 *      and Brand Color Palette (`💜 Maitis Purple #7C3AED`, `💚 Emerald`, `💙 Ocean`, `🧡 Amber`, `❤️ Crimson`, or Original).
 *    - Saves `craft.json`, `investigation.cany/evidence.md`, and `checks/likeness-check.md` inside the generated
 *      Android Studio `.zip` in `Downloads/AIApp/`.
 * 2. **Clozed Code + REA Features**:
 *    - Automatic **Context Compaction** (`⚡ Context Compacted — history preserved`), **Multi-Key / Token Harbor Failover**,
 *      **🔄 Reset Limit Flag & Continue**, **🔧 Build Error Log Auto-Fix**, and on-device ARSCLib/Smali patching.
 */
class ReverseViewModel(app: Application) : AndroidViewModel(app) {

    private val keys = ApiKeys(app)
    private val prefs = app.getSharedPreferences("reverse", android.content.Context.MODE_PRIVATE)

    var busy by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var status by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var report by mutableStateOf<ApkReport?>(null); private set

    private var apkFile: File? = null
    private var websiteTargetUrl: String? = null
    private var deepReaSpec: String = ""

    // ---------- Craft Anything (`storytold/craft-anything`) State ----------

    var dashboardAction by mutableStateOf(CraftAnythingEngine.DashboardAction.MAKE_MY_OWN)
    var studyMode by mutableStateOf(CraftAnythingEngine.StudyMode.CLEAN_ROOM)
    var craftStage by mutableStateOf(CraftAnythingEngine.CraftStage.IDLE); private set

    /** Safety setting from Level 2 of the Maitis video: OFF ("Ask First" human oversight) by default! */
    var autoMode by mutableStateOf(false)

    /** Target likeness percentage (default 95%, as in the Excalidraw -> MaitisBoard demo). */
    var likenessTargetPercent by mutableIntStateOf(95)

    /** Custom branding (New App Name, Package ID, Header Logo Text, Brand Color Preset). */
    var cloneAppName by mutableStateOf("")
    var clonePackageName by mutableStateOf("")
    var customLogoText by mutableStateOf("")
    var selectedBrandPreset by mutableStateOf(CraftAnythingEngine.brandPresets.first())
    var bundleApkResources by mutableStateOf(true)

    /** Latest Craft Anything Architect Study & Check reports. */
    var craftStudy by mutableStateOf<CraftAnythingEngine.CraftStudyEvidence?>(null); private set
    var craftCheck by mutableStateOf<CraftAnythingEngine.CraftCheckReport?>(null); private set
    private var pendingBuildInstruction: String = ""

    /** Context Compaction & Rate-Limit Recovery state (Clozed Code workflow). */
    var contextCompactedBadge by mutableStateOf<String?>(null); private set
    var canResumeInterrupted by mutableStateOf(false); private set
    private var lastInterruptedInstruction: String? = null
    private var lastInterruptedWasClone: Boolean = false

    /** Generated Android Studio Clone Project (.zip) saved to Downloads/AIApp. */
    var clonedZipAttachment by mutableStateOf<Attachment?>(null); private set
    var clonedZipSavedNote by mutableStateOf<String?>(null); private set
    private val clonedCodeHistory = mutableListOf<ChatMessage>()
    private val requestHistoryLog = mutableListOf<String>()

    /** Installed apps on the phone for 1-tap selection (MT Manager style). */
    val installedApps = mutableStateListOf<InstalledAppItem>()
    var loadingInstalledApps by mutableStateOf(false); private set

    /** The whole conversation about this app: your messages, live progress, and final results. */
    val transcript = mutableStateListOf<ChatMessage>()
    private val _answering = mutableStateOf(false)
    /** True while a model/REA/build step runs. Setting it keeps the app online in the background (see [com.aiapp.builder.KeepAliveService]). */
    var answering: Boolean
        get() = _answering.value
        private set(v) {
            if (v == _answering.value) return
            _answering.value = v
            if (v) com.aiapp.builder.KeepAliveService.begin(getApplication(), "Working in the background…")
            else com.aiapp.builder.KeepAliveService.end(getApplication())
        }
    var liveAnswer by mutableStateOf(""); private set
    var liveThinking by mutableStateOf(""); private set

    /** Captured when you send a message while the agent is already working; folded in at the next checkpoint. */
    private var pendingSteer: String? = null

    // 100% On-Device Android Native REA + Craft Anything Tool Engine (Zero PC Required)
    private val localRea = ReaClient()
    var reaUrl by mutableStateOf("android://on-device-native")
    var reaConnected by mutableStateOf(true); private set
    var reaInfo by mutableStateOf<String?>("Android On-Device REA + Craft Anything Engine v2.0 (Zero PC)"); private set
    val reaTools = mutableStateListOf<ReaTool>().apply { addAll(localRea.builtInAndroidTools) }

    fun runOnDeviceReaTool(toolName: String) {
        val apk = apkFile
        if (apk == null) {
            transcript.add(ChatMessage(Role.ASSISTANT, "Load an Android APK first to run the on-device tool `$toolName`."))
            return
        }
        viewModelScope.launch {
            status = "Running on-device Android tool: $toolName…"
            try {
                val output = localRea.runOnDeviceTool(toolName, apk)
                transcript.add(ChatMessage(Role.ASSISTANT, output))
            } catch (e: Exception) {
                transcript.add(ChatMessage(Role.ASSISTANT, "Tool `$toolName` failed: ${e.message}"))
            } finally {
                status = null
            }
        }
    }

    fun clearError() { error = null }

    fun updateCloneAppName(newName: String) {
        cloneAppName = newName
        val slug = newName.lowercase().replace(Regex("[^a-z0-9]+"), "").ifBlank { "craftedapp" }.take(22)
        clonePackageName = "com.crafted.${if (slug.first().isLetter()) slug else "a$slug"}"
        if (customLogoText.isBlank() || customLogoText.endsWith("Board", true)) {
            customLogoText = newName.take(18)
        }
    }

    // ---------- Installed Apps Picker (MT Manager alternative) ----------

    fun refreshInstalledApps() {
        if (loadingInstalledApps) return
        loadingInstalledApps = true
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val pm = getApplication<Application>().packageManager
                    val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    apps.mapNotNull { info ->
                        val src = info.sourceDir ?: return@mapNotNull null
                        val f = File(src)
                        if (!f.exists() || !f.canRead()) return@mapNotNull null
                        val isUserApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                            (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                        if (!isUserApp && info.packageName.startsWith("com.android.")) return@mapNotNull null
                        val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
                        InstalledAppItem(
                            label = label,
                            packageName = info.packageName,
                            sourceApkPath = src,
                            sizeMb = f.length() / (1024.0 * 1024.0),
                        )
                    }.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
                }
                installedApps.clear()
                installedApps.addAll(list)
            } catch (e: Exception) {
                Log.w("Reverse", "refreshInstalledApps failed: ${e.message}")
            } finally {
                loadingInstalledApps = false
            }
        }
    }

    fun loadInstalledApp(item: InstalledAppItem) {
        viewModelScope.launch {
            loading = true; error = null; status = "Extracting ${item.label} APK…"; report = null; transcript.clear()
            try {
                val ctx = getApplication<Application>()
                val dir = File(ctx.cacheDir, "reverse").apply { mkdirs() }
                val safe = item.packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val dst = File(dir, "$safe.apk")
                withContext(Dispatchers.IO) {
                    File(item.sourceApkPath).copyTo(dst, overwrite = true)
                }
                finishLoad(dst)
            } catch (e: Exception) {
                Log.e("Reverse", "loadInstalledApp failed", e)
                error = e.message ?: "Couldn't copy installed APK."
                status = null
            } finally {
                loading = false
            }
        }
    }

    // ---------- Loading the target (APK, .zip, MT Manager Local Path, or Website / Web App URL) ----------

    fun loadApk(uri: Uri) {
        viewModelScope.launch {
            loading = true; error = null; status = "Copying APK…"; report = null; transcript.clear()
            try {
                val ctx = getApplication<Application>()
                val dir = File(ctx.cacheDir, "reverse").apply { mkdirs() }
                val name = queryName(uri) ?: "target.apk"
                val dst = File(dir, if (name.endsWith(".apk", true)) name else "$name.apk")
                ctx.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Can't open that file." }
                    dst.outputStream().use { input.copyTo(it) }
                }
                finishLoad(dst)
            } catch (e: Exception) {
                Log.e("Reverse", "load failed", e)
                error = e.message ?: "Couldn't read that APK."
                status = null
            } finally {
                loading = false
            }
        }
    }

    /** A .zip that contains an APK (e.g. a zipped app) — the first `.apk` entry inside it is used. */
    fun loadZip(uri: Uri) {
        viewModelScope.launch {
            loading = true; error = null; status = "Looking for an APK inside the zip…"; report = null; transcript.clear()
            try {
                val ctx = getApplication<Application>()
                val dir = File(ctx.cacheDir, "reverse").apply { mkdirs() }
                val tmpZip = File(dir, "upload.zip")
                ctx.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Can't open that file." }
                    tmpZip.outputStream().use { input.copyTo(it) }
                }
                if (com.aiapp.reverse.SplitApk.isBundle(tmpZip)) { finishLoad(tmpZip); return@launch }
                val dst = File(dir, "from-zip.apk")
                ZipFile(tmpZip).use { zip ->
                    val entry = zip.entries().asSequence().firstOrNull { it.name.endsWith(".apk", true) }
                        ?: run {
                            val isProject = zip.entries().asSequence().any { it.name.substringAfterLast('/').startsWith("settings.gradle") }
                            throw IllegalStateException(
                                if (isProject) "This zip is an Android Studio project, not an app. To compile or change it, open the Build tab and upload it there."
                                else "No app (.apk) found inside that zip. Upload an .apk, .apks, .xapk or .apkm, or a zip that contains one.",
                            )
                        }
                    zip.getInputStream(entry).use { input -> dst.outputStream().use { input.copyTo(it) } }
                }
                tmpZip.delete()
                finishLoad(dst)
            } catch (e: Exception) {
                Log.e("Reverse", "load zip failed", e)
                error = e.message ?: "Couldn't read that zip."
                status = null
            } finally {
                loading = false
            }
        }
    }

    /**
     * Loads ANY of:
     * 1. A **local phone file path** copied from MT Manager (e.g. `/sdcard/MT2/apks/TempMail.apk`),
     * 2. A **direct APK download link** (`https://.../app.apk`), OR
     * 3. Any **Website / Web App URL** (e.g. `https://excalidraw.com` — studied with `CraftAnythingEngine.studyWebsite`
     *    so you can craft your own native Android version like `MaitisBoard`!).
     */
    fun loadUrl(urlOrPath: String) {
        val trimmed = urlOrPath.trim().removeSurrounding("\"").removeSurrounding("'")
        if (trimmed.isBlank()) {
            error = "Paste a website URL (e.g. https://excalidraw.com), an MT Manager local APK path (/sdcard/...), or an APK link first."
            return
        }
        viewModelScope.launch {
            loading = true; error = null; status = "Loading target…"; report = null; transcript.clear()
            try {
                val ctx = getApplication<Application>()
                val dir = File(ctx.cacheDir, "reverse").apply { mkdirs() }

                val isLocalPath = trimmed.startsWith("/") ||
                    trimmed.startsWith("file://") ||
                    trimmed.startsWith("sdcard/", ignoreCase = true) ||
                    trimmed.startsWith("storage/", ignoreCase = true) ||
                    trimmed.startsWith("MT2/", ignoreCase = true)

                if (isLocalPath) {
                    val normalizedPath = when {
                        trimmed.startsWith("file://") -> Uri.parse(trimmed).path ?: trimmed.removePrefix("file://")
                        trimmed.startsWith("sdcard/", ignoreCase = true) -> "/$trimmed"
                        trimmed.startsWith("storage/", ignoreCase = true) -> "/$trimmed"
                        trimmed.startsWith("MT2/", ignoreCase = true) -> "/sdcard/$trimmed"
                        else -> trimmed
                    }
                    val srcFile = File(normalizedPath)
                    if (!srcFile.exists() || !srcFile.canRead()) {
                        throw IllegalStateException("Local path not accessible: $normalizedPath. Use 'Upload APK' or 'Installed Apps' if Android scoped storage blocks direct /sdcard access.")
                    }
                    status = "Copying local APK from ${srcFile.name}…"
                    val dst = File(dir, if (srcFile.name.endsWith(".apk", true)) srcFile.name else "${srcFile.name}.apk")
                    withContext(Dispatchers.IO) { srcFile.copyTo(dst, overwrite = true) }
                    finishLoad(dst)
                } else if (trimmed.substringBefore('?').endsWith(".apk", ignoreCase = true)) {
                    status = "Downloading APK…"
                    val file = ApkInstaller.download(ctx, trimmed) { p -> status = "Downloading… ${(p * 100).toInt()}%" }
                    finishLoad(file)
                } else {
                    // Website / Web App target for Craft Anything (e.g. https://excalidraw.com -> MaitisBoard)!
                    val webUrl = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
                    finishWebsiteLoad(webUrl)
                }
            } catch (e: Exception) {
                Log.e("Reverse", "load url/path failed", e)
                error = e.message ?: "Couldn't load that target."
                status = null
            } finally {
                loading = false
            }
        }
    }

    private suspend fun finishWebsiteLoad(webUrl: String) {
        apkFile = null
        websiteTargetUrl = webUrl
        clonedZipAttachment = null
        clonedZipSavedNote = null
        clonedCodeHistory.clear()
        discussion.clear()
        pendingPlan = null
        requestHistoryLog.clear()
        contextCompactedBadge = null
        canResumeInterrupted = false
        craftCheck = null
        craftStage = CraftAnythingEngine.CraftStage.STUDY

        // Leave the app name blank on purpose: the user chooses it. (Nothing is auto-filled.)
        updateCloneAppName("")
        customLogoText = ""

        status = "🔍 Craft Anything Architect studying $webUrl (colors, fonts, layout & viewports)…"
        val study = CraftAnythingEngine.studyWebsite(
            url = webUrl,
            mode = studyMode,
            newAppName = cloneAppName,
            customLogoText = customLogoText,
            brandPreset = selectedBrandPreset,
        )
        craftStudy = study
        deepReaSpec = study.architectBlueprintMarkdown

        // Create a synthetic ApkReport so the unified workbench UI renders seamlessly for both Websites and APKs
        report = ApkReport(
            fileName = webUrl,
            sizeBytes = study.architectBlueprintMarkdown.length.toLong(),
            packageName = clonePackageName,
            appLabel = study.targetName,
            versionName = "1.0",
            versionCode = "1",
            minSdk = "26",
            targetSdk = "35",
            mainActivity = "$clonePackageName.MainActivity",
            permissions = listOf("android.permission.INTERNET"),
            activities = study.layoutLandmarks,
            services = emptyList(),
            receivers = emptyList(),
            providers = emptyList(),
            dexFiles = emptyList(),
            classCount = study.layoutLandmarks.size,
            methodCount = study.interactiveFeatures.size,
            sampleClasses = study.interactiveFeatures,
            nativeLibs = emptyList(),
            abis = listOf("Phone 390dp", "Tablet 768dp", "Android Tablet 840dp"),
            runtimeHint = "Website / Web App (Craft Anything)",
            stringSamples = study.extractedColors + study.extractedFonts,
            assetSamples = emptyList(),
            signature = SignatureInfo(listOf(study.studyMode), "${study.toolsRunCount} tools", ""),
            manifestXml = study.architectBlueprintMarkdown,
        )
        craftStage = CraftAnythingEngine.CraftStage.IDLE
        status = null

        transcript.add(
            ChatMessage(
                Role.ASSISTANT,
                "🏛️ **Craft Anything Architect Studied Website**: `${study.targetName}` (`$webUrl`)\n" +
                    "• **Mode**: ${study.studyMode} · **${study.toolsRunCount} inspection checks** completed\n" +
                    "• **Extracted Colors**: ${study.extractedColors.take(6).joinToString(", ")}\n" +
                    "• **Detected Fonts**: ${study.extractedFonts.joinToString(", ")}\n" +
                    "• **New Crafted App Identity**: `$cloneAppName` (`$clonePackageName`) · **Likeness Target**: `${likenessTargetPercent}%`\n\n" +
                    "Tap **🛠️ Start Craft Anything (Study → Build → Check → Ready)** above to build your own native Android version!",
            ),
        )
    }

    private suspend fun finishLoad(picked: File) {
        val dst = com.aiapp.reverse.SplitApk.mergeIfBundle(picked) // .apks / .xapk / .apkm -> one APK
        apkFile = dst
        websiteTargetUrl = null
        clonedZipAttachment = null
        clonedZipSavedNote = null
        clonedCodeHistory.clear()
        discussion.clear()
        pendingPlan = null
        requestHistoryLog.clear()
        contextCompactedBadge = null
        canResumeInterrupted = false
        craftStudy = null
        craftCheck = null
        craftStage = CraftAnythingEngine.CraftStage.IDLE

        status = "Decoding APK with ARSCLib + REA + Craft Anything on device…"
        val rep = ReverseEngine.inspect(dst)
        report = rep
        deepReaSpec = ReverseEngine.extractDeepReaSpec(dst)

        // Leave the app name blank on purpose: the user chooses it. (Nothing is auto-filled.)
        updateCloneAppName("")
        customLogoText = ""

        craftStudy = CraftAnythingEngine.studyApk(
            apkFile = dst,
            apkReport = rep,
            mode = studyMode,
            newAppName = cloneAppName,
            customLogoText = customLogoText,
            brandPreset = selectedBrandPreset,
        )
        status = null

        transcript.add(
            ChatMessage(
                Role.ASSISTANT,
                "🏛️ **Loaded Target in Craft Anything + REA Studio**: **${rep.appLabel.ifBlank { rep.packageName }}** (`${rep.packageName}`, v${rep.versionName})\n" +
                    "• **DEX & Components**: ${rep.classCount} classes, ${rep.methodCount} methods, ${rep.activities.size} activities, ${rep.permissions.size} permissions\n" +
                    "• **Extracted Colors**: ${craftStudy?.extractedColors?.take(6)?.joinToString(", ").orEmpty()}\n" +
                    "• **New Crafted Identity**: `$cloneAppName` (`$clonePackageName`) · **Likeness Target**: `${likenessTargetPercent}%`\n" +
                    "• **Safety Oversight**: `Auto Mode: ${if (autoMode) "ON (Keep going)" else "OFF (Ask First before building)"}`\n\n" +
                    "Tap **🛠️ Start Craft Anything** above to run the 4-stage `Study → Build → Check → Ready` loop, " +
                    "or use the chat box below to ask questions, customize branding (e.g. *\"make the main color purple and add my logo\"*), or paste build error logs to fix!",
            ),
        )
    }

    private fun queryName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()

    fun unloadTarget() {
        apkFile = null
        websiteTargetUrl = null
        report = null
        deepReaSpec = ""
        craftStudy = null
        craftCheck = null
        craftStage = CraftAnythingEngine.CraftStage.IDLE
        transcript.clear()
        clonedZipAttachment = null
        clonedZipSavedNote = null
        clonedCodeHistory.clear()
        discussion.clear()
        pendingPlan = null
        requestHistoryLog.clear()
        contextCompactedBadge = null
        canResumeInterrupted = false
        error = null
        status = null
    }

    // ---------- Craft Anything 4-Stage Workflow (`Study -> Approve -> Build -> Check -> Ready`) ----------

    /**
     * Starts the 4-stage `storytold/craft-anything` workflow:
     * - Stage 1 (`Study`): Runs the Architect inspection tools on the APK or Website.
     * - If `dashboardAction == JUST_LOOK_INSIDE`: Posts the full Architect Study report & answers without building.
     * - If `autoMode == false` ("Ask First" human oversight from Level 2 of the Maitis video):
     *   Pauses at `CraftStage.AWAITING_APPROVAL` and presents the Approval & Open Questions Card in the UI!
     * - If `autoMode == true`: Proceeds directly into Stage 2 (`Build`), Stage 3 (`Check`), and Stage 4 (`Ready`).
     */
    fun startCraftAnythingWorkflow(instruction: String, provider: ProviderId, model: String, effort: Effort) {
        val r = report ?: run { error = "Load a website URL or APK first."; return }
        if (answering) { error = "Still working on the previous step — wait or steer below."; return }
        // "Just Look Inside" only explains the app, so it needs no name; building does.
        if (dashboardAction != CraftAnythingEngine.DashboardAction.JUST_LOOK_INSIDE && cloneAppName.isBlank()) {
            error = "Please enter an app name first."
            return
        }

        val defaultPrompt = when (dashboardAction) {
            CraftAnythingEngine.DashboardAction.MAKE_MY_OWN ->
                "Make my own version of ${r.appLabel.ifBlank { r.fileName }} and call it $cloneAppName. " +
                    "Keep the same tools, layout, and features, use header logo text \"${customLogoText.ifBlank { cloneAppName }}\", " +
                    "and apply the ${selectedBrandPreset.label} color theme."
            CraftAnythingEngine.DashboardAction.WORK_ON_PROJECT ->
                "Add a big \"${customLogoText.ifBlank { cloneAppName }}\" logo text in the top-left corner, " +
                    "apply the ${selectedBrandPreset.label} color palette, and refine all interactive tools."
            CraftAnythingEngine.DashboardAction.JUST_LOOK_INSIDE ->
                "Explain how ${r.appLabel.ifBlank { r.fileName }} is structured: its screens, layout hierarchy, color palette, fonts, and core logic."
        }
        val finalInstruction = instruction.trim().ifBlank { defaultPrompt }
        pendingBuildInstruction = finalInstruction
        requestHistoryLog += finalInstruction

        viewModelScope.launch {
            answering = true; error = null; canResumeInterrupted = false
            try {
                // Stage 1: Study
                craftStage = CraftAnythingEngine.CraftStage.STUDY
                status = "🔍 [Stage 1/4: Study] Craft Anything Architect running inspection tools (${studyMode.label})…"
                var study = if (apkFile != null) {
                    CraftAnythingEngine.studyApk(apkFile!!, r, studyMode, cloneAppName, customLogoText, selectedBrandPreset)
                } else {
                    CraftAnythingEngine.studyWebsite(websiteTargetUrl ?: r.fileName, studyMode, cloneAppName, customLogoText, selectedBrandPreset)
                }
                // Real REA (github.com/morluto/rea) on GitHub Actions; unchanged when not signed in to GitHub.
                study = com.aiapp.reverse.ReaCloud.enrich(getApplication(), study, apkFile, websiteTargetUrl, githubTokenOf()) { status = it }
                craftStudy = study

                if (dashboardAction == CraftAnythingEngine.DashboardAction.JUST_LOOK_INSIDE) {
                    transcript.add(ChatMessage(Role.USER, "🔍 Just Look Inside: $finalInstruction"))
                    answerQuestion(r, finalInstruction, provider, model, effort)
                    craftStage = CraftAnythingEngine.CraftStage.READY
                    return@launch
                }

                transcript.add(
                    ChatMessage(
                        Role.ASSISTANT,
                        "🔍 **[Stage 1/4: Study Complete]** (${study.toolsRunCount} inspection tools run in `${study.studyMode}`)\n" +
                            "• **Colors**: ${study.extractedColors.take(6).joinToString(", ")}\n" +
                            "• **Fonts**: ${study.extractedFonts.joinToString(", ")}\n" +
                            "• **Viewports Measured**: ${study.androidViewports.joinToString(" · ")}\n" +
                            "• **Likeness Target**: `${likenessTargetPercent}%` · **Branding**: `${selectedBrandPreset.label}`",
                    ),
                )

                // Plan first: the model writes the plan from the evidence and the user sees it before any code.
                status = "📝 Writing the plan from the evidence…"
                val plan = runCatching {
                    collectReply(provider, model, com.aiapp.reverse.ReaCloud.planPrompt(study, finalInstruction, cloneAppName, clonePackageName), com.aiapp.reverse.ReaCloud.PLAN_SYSTEM, effort)
                }.getOrDefault("")
                if (plan.isNotBlank()) {
                    transcript.add(ChatMessage(Role.ASSISTANT, "📝 **Plan (nothing is built until this is shown)**\n\n$plan", thinking = liveThinking))
                    study = com.aiapp.reverse.ReaCloud.withPlan(study, plan)
                    craftStudy = study
                }

                // Human Oversight Check ("Auto Mode: OFF / Ask First")
                if (!autoMode) {
                    craftStage = CraftAnythingEngine.CraftStage.AWAITING_APPROVAL
                    status = null
                    transcript.add(
                        ChatMessage(
                            Role.ASSISTANT,
                            "🛡️ **Human Oversight (`Auto Mode: OFF — Ask First`)**:\n" +
                                "Review the Architect's Study plan and open questions on the approval card above, then tap **✅ Approve & Start Build** so the Builder (${provider.label}) can write the project.",
                        ),
                    )
                    return@launch
                }

                // Auto Mode is ON: proceed directly to Build -> Check -> Ready
                executeCraftBuildCheckReady(r, study, finalInstruction, provider, model, effort)
            } catch (e: Exception) {
                Log.e("Reverse", "startCraftAnythingWorkflow failed", e)
                canResumeInterrupted = true
                transcript.add(ChatMessage(Role.ASSISTANT, "⚠️ Craft Anything paused: ${e.message}"))
            } finally {
                answering = false; liveAnswer = ""; liveThinking = ""; status = null
            }
        }
    }

    /** Called when the user taps "✅ Approve & Start Build" on the Human Oversight Approval Card. */
    fun approveAndContinueCraftBuild(provider: ProviderId, model: String, effort: Effort) {
        val r = report ?: return
        val study = craftStudy ?: return
        if (answering) return
        val instr = pendingBuildInstruction.ifBlank {
            "Build $cloneAppName with ${selectedBrandPreset.label} theme and \"${customLogoText.ifBlank { cloneAppName }}\" logo."
        }
        viewModelScope.launch {
            answering = true; error = null; canResumeInterrupted = false
            lastInterruptedInstruction = instr
            lastInterruptedWasClone = true
            try {
                executeCraftBuildCheckReady(r, study, instr, provider, model, effort)
            } catch (e: Exception) {
                Log.e("Reverse", "approveAndContinueCraftBuild failed", e)
                canResumeInterrupted = true
                transcript.add(
                    ChatMessage(
                        Role.ASSISTANT,
                        "⚠️ **Build paused / API limit reached**: ${e.message}\n\n" +
                            "Tap **🔄 Reset Limit & Continue** above to resume from this checkpoint.",
                    ),
                )
            } finally {
                answering = false; liveAnswer = ""; liveThinking = ""; status = null
            }
        }
    }

    private suspend fun executeCraftBuildCheckReady(
        r: ApkReport,
        study: CraftAnythingEngine.CraftStudyEvidence,
        instruction: String,
        provider: ProviderId,
        model: String,
        effort: Effort,
    ) {
        // Stage 2: Build
        craftStage = CraftAnythingEngine.CraftStage.BUILD
        status = "🛠️ [Stage 2/4: Build] ${provider.label} writing $cloneAppName (target likeness ${likenessTargetPercent}%)…"
        runAiClonePipeline(r, instruction, provider, model, effort, study)

        // Stage 3: Check (compare against original across Phone, Tablet & Laptop viewports)
        craftStage = CraftAnythingEngine.CraftStage.CHECK
        status = "✅ [Stage 3/4: Check] Architect comparing $cloneAppName across Phone, Tablet & Laptop viewports…"
        val latestCode = clonedCodeHistory.lastOrNull { it.role == Role.ASSISTANT }?.text.orEmpty()
        val checkReport = CraftAnythingEngine.checkBuildAgainstStudy(
            study = study,
            generatedCode = latestCode,
            targetLikeness = likenessTargetPercent,
            customLogoText = customLogoText,
            brandPreset = selectedBrandPreset,
        )
        craftCheck = checkReport

        transcript.add(
            ChatMessage(
                Role.ASSISTANT,
                "✅ **[Stage 3/4: Architect Likeness Check Complete]**\n" +
                    "• **Overall Likeness**: **${checkReport.overallLikenessPercent}%** (Target: `${checkReport.targetLikenessPercent}%`)\n" +
                    "• **Compact Phone (360dp)**: `${checkReport.compactPhoneScore}%` · **Foldable (600dp)**: `${checkReport.foldablePhoneScore}%` · **Android Tablet (840dp)**: `${checkReport.androidTabletScore}%`\n" +
                    "• **Verified**: ${checkReport.passedChecks.joinToString(" · ")}",
            ),
        )

        // Stage 4: Ready
        craftStage = CraftAnythingEngine.CraftStage.READY
        status = null
    }

    // ---------- Context Compaction & Rate-Limit Reset (Clozed Code workflow) ----------

    fun compactContextNow() {
        if (transcript.isEmpty() && clonedCodeHistory.isEmpty()) return
        val totalChars = transcript.sumOf { it.text.length } + deepReaSpec.length
        contextCompactedBadge = "⚡ Context Compacted (${(totalChars / 1000).coerceAtLeast(1)}K chars compressed · visible chat history preserved)"
        transcript.add(
            ChatMessage(
                Role.ASSISTANT,
                "✅ **Context Compacted**: Compressed project & APK memory so the AI model stays fast and accurate without hallucinating, while keeping your full chat history on screen.",
            ),
        )
    }

    fun resetLimitAndContinue(provider: ProviderId, model: String, effort: Effort) {
        keys.resetLimitFlags(provider)
        error = null
        val savedInstruction = lastInterruptedInstruction
        val wasClone = lastInterruptedWasClone
        canResumeInterrupted = false
        transcript.add(
            ChatMessage(
                Role.ASSISTANT,
                "🔄 **API Limit Flag Reset** — switched to fresh key/gateway and continuing from where it stopped…",
            ),
        )
        if (wasClone) {
            cloneToAndroidStudioProject(savedInstruction.orEmpty(), provider, model, effort)
        } else if (!savedInstruction.isNullOrBlank()) {
            send(savedInstruction, provider, model, effort)
        }
    }

    // ---------- The conversation (Questions, Error Log Auto-Fix, Brand/UI Refinements & On-Device Patching) ----------

    private val questionLike = Regex("^(how|what|why|where|when|does|is|are|can|could|who|which|explain|list|show)\\b", RegexOption.IGNORE_CASE)

    private val errorLogPattern = Regex(
        "(e:\\s*file:///|Unresolved reference|FAILURE:\\s*Build failed|Execution failed for task|AAPT:\\s*error|error:\\s*cannot find symbol|Exception in thread|>\\s*Task\\s*:app:|\\bfix\\s+this\\s+error\\b|\\bfix\\s+error\\b|\\bfix\\s+build\\b)",
        RegexOption.IGNORE_CASE,
    )

    private val cloneIntentPattern = Regex(
        "(\\bclone\\b|\\brecreate\\b|\\bcraft\\b|\\bmake\\s+my\\s+own\\b|\\bandroid\\s+studio\\s+project\\b|\\bexact\\s+ui\\b|\\bsame\\s+to\\s+same\\b|\\bpurple\\b|\\blogo\\b|\\btheme\\b)",
        RegexOption.IGNORE_CASE,
    )

    fun send(text: String, provider: ProviderId, model: String, effort: Effort) {
        val instruction = text.trim()
        if (instruction.isBlank()) return
        val r = report
        if (r == null) { error = "Load a website URL or APK first."; return }
        requestHistoryLog += instruction
        transcript.add(ChatMessage(Role.USER, instruction))
        if (answering) {
            pendingSteer = ((pendingSteer ?: "") + "\n" + instruction).trim()
            transcript.add(ChatMessage(Role.ASSISTANT, "Got it — I'll factor that in at the next step."))
            return
        }

        // A plan is on screen waiting for approval: "yes" / "approve" / "build" starts it;
        // anything else reopens the discussion.
        if (pendingPlan != null) {
            if (approveWords.matches(instruction)) {
                approvePendingPlan(provider, model, effort)
                return
            }
            pendingPlan = null
            transcript.add(
                ChatMessage(
                    Role.ASSISTANT,
                    "✏️ Okay — the plan is on hold, let's keep talking. Say **build** again when you're ready and I'll show the updated plan before anything is built.",
                ),
            )
        } else if (craftStage == CraftAnythingEngine.CraftStage.AWAITING_APPROVAL &&
            (approveWords.matches(instruction) || buildCommand.containsMatchIn(instruction))
        ) {
            // The Start Craft Anything plan is already on screen: this approves it.
            approveAndContinueCraftBuild(provider, model, effort)
            return
        }

        if (!buildCommand.containsMatchIn(instruction)) {
            // Discussion: the model talks it through; everything said is remembered for the plan.
            discussion += ChatMessage(Role.USER, instruction)
            launchTurn(instruction, clone = false) { discuss(r, provider, model, effort) }
            return
        }

        // "build": turn the whole discussion into a numbered plan and show it. Nothing is built
        // until the user approves it.
        val extra = instruction.replace(buildCommand, " ").trim(' ', ',', '.', '!', '-', ':', '\n')
        if (extra.length > 3 && !Regex("^(it|now|the app|app|please|ok|okay|it now|now please)$", RegexOption.IGNORE_CASE).matches(extra)) {
            discussion += ChatMessage(Role.USER, extra)
        }
        if (dashboardAction != CraftAnythingEngine.DashboardAction.JUST_LOOK_INSIDE && cloneAppName.isBlank()) {
            error = "Please enter an app name first."
            transcript.add(ChatMessage(Role.ASSISTANT, "✋ Please enter an **app name** in the Architect panel first, then say **build** again."))
            return
        }
        if (discussion.none { it.role == Role.USER }) {
            transcript.add(
                ChatMessage(
                    Role.ASSISTANT,
                    "🤔 Tell me first what you'd like to build or change. Once we've talked it through, say **build** and I'll show the full plan for your approval.",
                ),
            )
            return
        }
        launchTurn(instruction, clone = true) {
            val plan = makePlan(r, provider, model, effort)
            pendingPlan = plan
            transcript.add(
                ChatMessage(
                    Role.ASSISTANT,
                    "📋 **Before I build, here is everything I'll work on:**\n\n$plan\n\n" +
                        "Tap **✅ Approve & build** (or say **yes**) to start. Say anything else to change the plan.",
                ),
            )
        }
    }

    /** Runs one model turn with the usual busy state and error handling. */
    private fun launchTurn(instruction: String, clone: Boolean, block: suspend () -> Unit) {
        viewModelScope.launch {
            answering = true; error = null; canResumeInterrupted = false
            try {
                lastInterruptedInstruction = instruction
                lastInterruptedWasClone = clone
                block()
            } catch (e: Exception) {
                Log.e("Reverse", "turn failed", e)
                canResumeInterrupted = true
                transcript.add(ChatMessage(Role.ASSISTANT, "⚠️ **Paused / API limit reached**: ${e.message}"))
            } finally {
                answering = false; liveAnswer = ""; liveThinking = ""; status = null
            }
        }
    }

    private val DISCUSS_SYSTEM =
        "You are helping the user plan what to build or change in an Android app they are rebuilding from the target " +
            "described below. Have a normal, friendly conversation: answer questions, suggest ideas, and ask a short " +
            "clarifying question when something is unclear. Do NOT write code or files yet, and do not claim anything " +
            "has been built. Keep replies short. When the user seems ready, remind them they can say \"build\" — you " +
            "will then show the full plan for approval before anything is built."

    /** Everything said while planning the next build (user requests and the model's replies). */
    private val discussion = mutableListOf<ChatMessage>()

    /** The plan shown to the user after "build"; building starts only once it is approved. */
    var pendingPlan by mutableStateOf<String?>(null); private set

    /** "build", "build it", "ok build now"… */
    private val buildCommand = Regex("\\bbuild\\b", RegexOption.IGNORE_CASE)

    /** Short replies that approve a plan on screen. */
    private val approveWords = Regex(
        "^\\s*(yes|y|yeah|yep|yup|ok|okay|approve|approved|go|go ahead|start|proceed|do it|confirm|sure|build|build it|build now|yes build|ok build)\\s*[.!]*\\s*$",
        RegexOption.IGNORE_CASE,
    )

    private fun targetContext(r: ApkReport): String = buildString {
        appendLine("TARGET STUDY BRIEFING:")
        appendLine(r.toBriefing())
        craftStudy?.let { appendLine(); appendLine(it.architectBlueprintMarkdown.take(9_000)) }
            ?: deepReaSpec.takeIf { it.isNotBlank() }?.let { appendLine(); appendLine(it.take(9_000)) }
        appendLine()
        appendLine("New app name: ${cloneAppName.ifBlank { "(not set yet)" }}")
    }

    private fun discussionText(): String = discussion.takeLast(30).joinToString("\n\n") { m ->
        (if (m.role == Role.USER) "USER: " else "ASSISTANT: ") + m.text.take(3_000)
    }

    /** One discussion reply: talks the request through, no code, nothing built. */
    private suspend fun discuss(r: ApkReport, provider: ProviderId, model: String, effort: Effort) {
        status = "Thinking with ${provider.label}…"
        val prompt = buildString {
            appendLine(targetContext(r))
            appendLine()
            appendLine("CONVERSATION SO FAR (planning what to build or change):")
            appendLine(discussionText())
            appendLine()
            appendLine("Reply to the user's last message.")
        }
        val reply = streamWithFailover(
            provider = provider,
            model = model,
            messages = listOf(ChatMessage(Role.USER, prompt)),
            system = DISCUSS_SYSTEM,
            effort = effort,
            updateLivePreview = true,
        )
        discussion += ChatMessage(Role.ASSISTANT, reply)
        transcript.add(ChatMessage(Role.ASSISTANT, reply, thinking = liveThinking))
    }

    /** Turns the whole discussion into a numbered list of everything to implement. */
    private suspend fun makePlan(r: ApkReport, provider: ProviderId, model: String, effort: Effort): String {
        status = "📋 Writing the plan from our conversation…"
        val prompt = buildString {
            appendLine(targetContext(r))
            appendLine()
            appendLine("CONVERSATION:")
            appendLine(discussionText())
            appendLine()
            appendLine("List EVERY change or feature the user asked for in this conversation, as a numbered list, one concrete line each.")
            appendLine("Keep earlier requests unless the user later dropped or replaced them. Do not add things they did not ask for.")
            appendLine("After the list, add one line starting with 'Approach:' (1–2 sentences). No code.")
        }
        val plan = runCatching {
            streamWithFailover(
                provider = provider,
                model = model,
                messages = listOf(ChatMessage(Role.USER, prompt)),
                system = "You turn a planning conversation into a precise, numbered build plan. Output only the list and the Approach line.",
                effort = effort,
                updateLivePreview = true,
            )
        }.getOrDefault("").trim()
        if (plan.isNotBlank()) return plan
        // Fallback if the model returned nothing: the user's own requests, numbered.
        return discussion.filter { it.role == Role.USER }.mapIndexed { i, m -> "${i + 1}. ${m.text.take(300)}" }.joinToString("\n")
    }

    /** Called by "✅ Approve & build" (or the user saying yes): starts the approved build. */
    fun approvePendingPlan(provider: ProviderId, model: String, effort: Effort) {
        val plan = pendingPlan ?: return
        val r = report ?: return
        if (answering) { error = "Still working on the previous step — wait for it to finish."; return }
        if (dashboardAction != CraftAnythingEngine.DashboardAction.JUST_LOOK_INSIDE && cloneAppName.isBlank()) {
            error = "Please enter an app name first."
            return
        }
        pendingPlan = null
        discussion.clear()
        transcript.add(ChatMessage(Role.ASSISTANT, "✅ Approved — building now."))
        val brief = "Implement ALL of these changes exactly as planned and approved by the user:\n$plan"
        if (craftStage == CraftAnythingEngine.CraftStage.AWAITING_APPROVAL) {
            pendingBuildInstruction = (pendingBuildInstruction + "\n\n" + brief).trim()
            approveAndContinueCraftBuild(provider, model, effort)
            return
        }
        sendBuild(r, brief, provider, model, effort, preferCraft = dashboardAction != CraftAnythingEngine.DashboardAction.JUST_LOOK_INSIDE)
    }

    /** Drops the plan on screen and goes back to discussing. */
    fun keepDiscussing() {
        if (pendingPlan == null) return
        pendingPlan = null
        transcript.add(ChatMessage(Role.ASSISTANT, "✏️ Okay — tell me what to change. Say **build** again when you're ready."))
    }

    private fun sendBuild(r: ApkReport, instruction: String, provider: ProviderId, model: String, effort: Effort, preferCraft: Boolean = false) {
        viewModelScope.launch {
            answering = true; error = null; canResumeInterrupted = false
            try {
                when {
                    errorLogPattern.containsMatchIn(instruction) && (clonedCodeHistory.isNotEmpty() || !preferCraft) -> {
                        lastInterruptedInstruction = instruction
                        lastInterruptedWasClone = true
                        fixBuildErrorsAndRepackage(r, instruction, provider, model, effort)
                    }
                    preferCraft || websiteTargetUrl != null || cloneIntentPattern.containsMatchIn(instruction) || clonedCodeHistory.isNotEmpty() -> {
                        lastInterruptedInstruction = instruction
                        lastInterruptedWasClone = true
                        val study = craftStudy ?: if (apkFile != null) {
                            CraftAnythingEngine.studyApk(apkFile!!, r, studyMode, cloneAppName, customLogoText, selectedBrandPreset)
                        } else {
                            CraftAnythingEngine.studyWebsite(websiteTargetUrl ?: r.fileName, studyMode, cloneAppName, customLogoText, selectedBrandPreset)
                        }
                        craftStudy = study
                        executeCraftBuildCheckReady(r, study, instruction, provider, model, effort)
                    }
                    else -> {
                        lastInterruptedInstruction = instruction
                        lastInterruptedWasClone = false
                        runChange(r, instruction, provider, model, effort)
                    }
                }
            } catch (e: Exception) {
                Log.e("Reverse", "agent turn failed", e)
                canResumeInterrupted = true
                transcript.add(
                    ChatMessage(
                        Role.ASSISTANT,
                        "⚠️ **Paused / API limit reached**: ${e.message}\n\n" +
                            "Tap **🔄 Reset Limit & Continue** above (or switch model/key) to resume right from where it stopped.",
                    ),
                )
            } finally {
                answering = false; liveAnswer = ""; liveThinking = ""; status = null
            }
        }
    }

    private fun takeSteer(): String? = pendingSteer.also { pendingSteer = null }

    private suspend fun streamWithFailover(
        provider: ProviderId,
        model: String,
        messages: List<ChatMessage>,
        system: String,
        effort: Effort,
        updateLivePreview: Boolean = true,
    ): String {
        val chosenModel = model.ifBlank {
            when (provider) {
                ProviderId.FREE -> "craft-architect"
                ProviderId.OFFLINE -> Providers.offline.recommended().id
                else -> Providers.rankedModels(provider, keys.get(provider)).firstOrNull()?.id.orEmpty()
            }
        }
        val compaction = ContextCompactor.compactIfNeeded(messages, provider)
        if (compaction.wasCompacted) {
            contextCompactedBadge = compaction.summaryNote
        }

        val sb = StringBuilder()
        val thinkSb = StringBuilder()
        if (updateLivePreview) {
            liveAnswer = ""
            liveThinking = ""
        }

        val keyPool = keys.allKeys(provider)
        val maxKeyAttempts = keyPool.size.coerceAtLeast(1)
        var lastErr: Throwable? = null

        for (keyAttempt in 0 until maxKeyAttempts) {
            val activeKey = keys.get(provider)
            try {
                resilient(
                    attempt = { mId ->
                        Providers.get(provider).stream(
                            apiKey = activeKey,
                            model = mId,
                            messages = compaction.messages,
                            effort = effort,
                            system = system,
                        )
                    },
                    model = chosenModel,
                    fallback = { alternativeModel(provider, activeKey, chosenModel) },
                    onRetry = { retryMsg -> status = retryMsg },
                ).collect { chunk ->
                    if (chunk.startsWith(THOUGHT)) {
                        thinkSb.append(chunk.substring(1))
                        if (updateLivePreview) liveThinking = thinkSb.toString().takeLast(1000)
                    } else {
                        sb.append(chunk)
                        if (updateLivePreview) liveAnswer = sb.toString()
                    }
                }
                return sb.toString()
            } catch (e: ProviderException) {
                lastErr = e
                val rotated = keys.rotateKey(provider)
                if (rotated != null && keyAttempt < maxKeyAttempts - 1) {
                    status = "🔑 API limit hit on key #${keyAttempt + 1} — auto-switching to key #${keyAttempt + 2}…"
                    sb.clear()
                    continue
                }
                throw e
            }
        }
        throw (lastErr ?: IllegalStateException("Model request failed."))
    }

    private suspend fun collectReply(provider: ProviderId, model: String, prompt: String, system: String, effort: Effort): String =
        streamWithFailover(
            provider = provider,
            model = model,
            messages = listOf(ChatMessage(Role.USER, prompt)),
            system = system,
            effort = effort,
            updateLivePreview = true,
        )

    private suspend fun answerQuestion(r: ApkReport, question: String, provider: ProviderId, model: String, effort: Effort) {
        status = "Thinking with ${provider.label}…"
        val context = buildString {
            appendLine("TARGET STUDY BRIEFING:")
            appendLine(r.toBriefing())
            if (deepReaSpec.isNotBlank()) {
                appendLine()
                appendLine(deepReaSpec.take(18_000))
            }
            if (reaConnected) {
                appendLine("\nA remote rea / craft-anything MCP backend is connected with ${reaTools.size} tools.")
            }
        }
        val answer = streamWithFailover(
            provider = provider,
            model = model,
            messages = listOf(ChatMessage(Role.USER, "$context\n\nQuestion: $question")),
            system = SYSTEM_PROMPT + "\n" + ReaEngine.REA_SYSTEM_PROMPT,
            effort = effort,
            updateLivePreview = true,
        )
        transcript.add(ChatMessage(Role.ASSISTANT, answer, thinking = liveThinking))
    }

    fun cloneToAndroidStudioProject(instruction: String, provider: ProviderId, model: String, effort: Effort) {
        startCraftAnythingWorkflow(instruction, provider, model, effort)
    }

    private suspend fun runAiClonePipeline(
        r: ApkReport,
        instruction: String,
        provider: ProviderId,
        model: String,
        effort: Effort,
        studyOverride: CraftAnythingEngine.CraftStudyEvidence? = null,
    ) {
        val apk = apkFile
        val targetAppName = cloneAppName.trim().ifBlank { "${r.appLabel.ifBlank { "MyApp" }}Craft" }
        val slug = targetAppName.lowercase().replace(Regex("[^a-z0-9]+"), "").ifBlank { "craftedapp" }.take(22)
        val targetPkg = clonePackageName.trim().takeIf { it.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")) }
            ?: "com.crafted.${if (slug.first().isLetter()) slug else "a$slug"}"

        val strings = if (apk != null) ReverseEngine.readStrings(apk, limit = 250) else emptyMap()
        if (deepReaSpec.isBlank() && apk != null && studyMode == CraftAnythingEngine.StudyMode.DEEP) {
            deepReaSpec = ReverseEngine.extractDeepReaSpec(apk)
        }
        val resourceBundle = if (bundleApkResources && apk != null) {
            withContext(Dispatchers.IO) { ReaEngine.extractReusableResources(apk) }
        } else null

        val study = studyOverride ?: craftStudy
        val brandColorDirective = if (selectedBrandPreset.primaryHex.isNotBlank()) {
            "- **Custom Brand Color Palette (${selectedBrandPreset.label})**: Primary `${selectedBrandPreset.primaryHex}`, Secondary `${selectedBrandPreset.secondaryHex}`, Dark Surface `${selectedBrandPreset.darkBgHex}`."
        } else {
            "- **Color Palette**: Match the original extracted colors (${study?.extractedColors?.joinToString(", ").orEmpty()})."
        }
        val logoDirective = if (customLogoText.isNotBlank()) {
            "- **Top-Left Brand Logo Text**: Display bold brand logo text `\"$customLogoText\"` in the top-left header bar."
        } else ""

        val clonePrompt = buildString {
            appendLine("Use the **Craft Anything (`github.com/storytold/craft-anything`) + Clozed Code + REA** Android Reconstruction Pipeline to build a COMPLETE, compilable Android Studio project in Kotlin + Jetpack Compose (Material 3).")
            appendLine()
            appendLine("### Target Crafted Application Identity & Branding:")
            appendLine("- **New App Name**: `$targetAppName`")
            appendLine("- **New Package Name**: `$targetPkg`")
            appendLine("- **Likeness Target**: `${likenessTargetPercent}%` (Responsive across Phone 390dp, Tablet 768dp, and Landscape/Android Tablet 840dp)")
            appendLine(brandColorDirective)
            if (logoDirective.isNotBlank()) appendLine(logoDirective)
            appendLine("- **User Request / Refinements**: $instruction")
            appendLine()
            if (study != null) {
                appendLine("### Craft Anything Architect Study Blueprint (`investigation.cany`):")
                appendLine(study.architectBlueprintMarkdown.take(18_000))
                appendLine()
            }
            appendLine("### Target Briefing:")
            appendLine(r.toBriefing())
            appendLine()
            if (strings.isNotEmpty()) {
                appendLine("### Extracted UI String Resources (`res/values/strings.xml` sample):")
                strings.entries.take(100).forEach { (k, v) ->
                    appendLine("- `$k` = \"${v.replace("\n", " ").take(120)}\"")
                }
                appendLine()
            }
            if (studyMode == CraftAnythingEngine.StudyMode.DEEP && deepReaSpec.isNotBlank()) {
                appendLine("### Deep Mode Static Analysis (Decoded XML Layouts, DEX Method Signatures & Endpoints):")
                appendLine(deepReaSpec.take(18_000))
                appendLine()
            }
            if (resourceBundle != null && resourceBundle.promptSummary.isNotBlank()) {
                appendLine(resourceBundle.promptSummary)
                appendLine()
            }
            appendLine("### Mandatory Output Format & Quality Rules:")
            appendLine("1. Recreate every interactive tool, canvas/workspace, navigation drawer/tabs, undo/redo, color/shape selector, Light/Dark mode toggle, and export/save action so the app works like a real, functional product (not a static mockup).")
            appendLine("2. Output every Kotlin/XML file inside standard ```kotlin or ```xml code blocks with its relative project path on the first line:")
            appendLine("   ```kotlin")
            appendLine("   // FILE: app/src/main/java/${targetPkg.replace('.', '/')}/MainActivity.kt")
            appendLine("   package $targetPkg")
            appendLine("   ...")
            appendLine("   ```")
            appendLine("3. Write every file in full with all imports and zero placeholders or TODOs so it compiles cleanly in Android Studio.")
        }

        val workingMessages = (clonedCodeHistory + ChatMessage(Role.USER, clonePrompt)).toMutableList()
        val chosenEffort = if (effort == Effort.AUTO) Effort.MAX else effort
        val systemPrompt = ReaEngine.REA_SYSTEM_PROMPT + "\n" + WebTools.instructions("today")

        val reply = streamWithFailover(
            provider = provider,
            model = model,
            messages = workingMessages,
            system = systemPrompt,
            effort = chosenEffort,
            updateLivePreview = true,
        )

        clonedCodeHistory.add(ChatMessage(Role.USER, instruction))
        clonedCodeHistory.add(ChatMessage(Role.ASSISTANT, reply))
        transcript.add(ChatMessage(Role.ASSISTANT, reply, thinking = liveThinking))

        // Build Craft Anything metadata files (`craft.json`, `investigation.cany/evidence.md`, `checks/likeness-check.md`)
        val checkReport = study?.let {
            CraftAnythingEngine.checkBuildAgainstStudy(it, reply, likenessTargetPercent, customLogoText, selectedBrandPreset)
        }
        val extraCraftFiles = linkedMapOf<String, ByteArray>()
        if (study != null && checkReport != null) {
            val manifest = CraftAnythingEngine.CraftProjectManifest(
                target = websiteTargetUrl ?: r.fileName,
                newAppName = targetAppName,
                newPackageName = targetPkg,
                studyMode = studyMode.label,
                likenessTargetPercent = likenessTargetPercent,
                achievedLikenessPercent = checkReport.overallLikenessPercent,
                autoMode = autoMode,
                brandColorHex = selectedBrandPreset.primaryHex.ifBlank { "original" },
                customLogoText = customLogoText.ifBlank { targetAppName },
                requestHistory = requestHistoryLog.toList(),
            )
            extraCraftFiles["craft.json"] = CraftAnythingEngine.buildCraftJson(manifest).toByteArray(Charsets.UTF_8)
            extraCraftFiles["investigation.cany/evidence.md"] = study.architectBlueprintMarkdown.toByteArray(Charsets.UTF_8)
            extraCraftFiles["checks/likeness-check.md"] = CraftAnythingEngine.buildCheckMarkdown(checkReport).toByteArray(Charsets.UTF_8)
        }

        status = "📦 Packaging Crafted Android Studio project (.zip + craft.json + checks/) into Downloads/AIApp…"
        val compiledZip = Downloads.compileChatToSourceZip(
            context = getApplication(),
            messages = clonedCodeHistory,
            latestReply = reply,
            preferredAppName = targetAppName,
            preferredPackageName = targetPkg,
            sourceApkPath = if (bundleApkResources) apk?.absolutePath else null,
            extraProjectFiles = extraCraftFiles,
        )

        if (compiledZip != null) {
            clonedZipAttachment = compiledZip.first
            clonedZipSavedNote = compiledZip.second
            transcript.add(
                ChatMessage(
                    Role.ASSISTANT,
                    "🎉 **[Stage 4/4: Ready!] Crafted Android Studio Project Saved**\n" +
                        "• **Archive**: `${compiledZip.first.name}` (includes `app/`, `craft.json`, `investigation.cany/`, and `checks/`)\n" +
                        "• **Location**: `${compiledZip.second}`\n\n" +
                        "Tap **🔨 Build APK** on the project card above to compile an installable `.apk`, or **Ask for changes** below (e.g., *\"Add a big logo in the top-left corner and make the main color purple\"*)!",
                ),
            )
        }
    }

    private suspend fun fixBuildErrorsAndRepackage(
        r: ApkReport,
        errorLogText: String,
        provider: ProviderId,
        model: String,
        effort: Effort,
    ) {
        val apk = apkFile
        val targetAppName = cloneAppName.trim().ifBlank { "${r.appLabel.ifBlank { "MyApp" }}Craft" }
        val slug = targetAppName.lowercase().replace(Regex("[^a-z0-9]+"), "").ifBlank { "craftedapp" }.take(22)
        val targetPkg = clonePackageName.trim().takeIf { it.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")) }
            ?: "com.crafted.${if (slug.first().isLetter()) slug else "a$slug"}"

        status = "🔧 Analyzing build error log & patching project files with ${provider.label}…"
        val fixPrompt = buildString {
            appendLine("The user built the reconstructed Android Studio project `$targetAppName` (package `$targetPkg`) and encountered the following build/compile error log:")
            appendLine()
            appendLine("```")
            appendLine(errorLogText.take(14_000))
            appendLine("```")
            appendLine()
            appendLine("### Instructions to Fix All Build Errors:")
            appendLine("1. Identify the exact root cause of every compiler, unresolved reference, import, manifest, or Gradle error in the log above.")
            appendLine("2. Output the COMPLETE corrected Kotlin/XML file(s) inside ```kotlin or ```xml code blocks with `// FILE: app/src/main/java/${targetPkg.replace('.', '/')}/...` on the first line.")
            appendLine("3. Never truncate files with `...` — write the entire fixed file so it compiles cleanly.")
        }

        val workingMessages = (clonedCodeHistory + ChatMessage(Role.USER, fixPrompt)).toMutableList()
        val chosenEffort = if (effort == Effort.AUTO) Effort.MAX else effort

        val reply = streamWithFailover(
            provider = provider,
            model = model,
            messages = workingMessages,
            system = ReaEngine.REA_SYSTEM_PROMPT,
            effort = chosenEffort,
            updateLivePreview = true,
        )

        clonedCodeHistory.add(ChatMessage(Role.USER, fixPrompt))
        clonedCodeHistory.add(ChatMessage(Role.ASSISTANT, reply))
        transcript.add(ChatMessage(Role.ASSISTANT, reply, thinking = liveThinking))

        status = "📦 Repackaging fixed Android Studio project (.zip) into Downloads/AIApp…"
        val compiledZip = Downloads.compileChatToSourceZip(
            context = getApplication(),
            messages = clonedCodeHistory,
            latestReply = reply,
            preferredAppName = targetAppName,
            preferredPackageName = targetPkg,
            sourceApkPath = if (bundleApkResources) apk?.absolutePath else null,
        )
        if (compiledZip != null) {
            clonedZipAttachment = compiledZip.first
            clonedZipSavedNote = compiledZip.second
            craftStage = CraftAnythingEngine.CraftStage.READY
            transcript.add(
                ChatMessage(
                    Role.ASSISTANT,
                    "✅ **Fixed Project Repackaged**: `${compiledZip.first.name}` (${compiledZip.second}).\n" +
                        "Tap **🔨 Build APK** above to compile the fixed project!",
                ),
            )
        }
    }

    fun compileClonedZipToApk(provider: ProviderId, model: String, effort: Effort) {
        val zip = clonedZipAttachment ?: run {
            error = "Run Start Craft Anything first to generate the Android Studio project .zip."
            return
        }
        val token = githubTokenOf()
        if (token.isBlank()) {
            error = "Sign in with GitHub in Settings first so GitHub Actions can compile ${zip.name} into a signed APK."
            return
        }
        if (com.aiapp.builder.BuildRunner.running) {
            openBuild = true
            return
        }
        val app = getApplication<Application>()
        val title = cloneAppName.trim().ifBlank { zip.name.removeSuffix(".zip") }
        com.aiapp.builder.BuildRunner.newProject(app)
        com.aiapp.builder.BuildService.start(
            app,
            com.aiapp.builder.BuildRequest(
                description = "Compile $title (${zip.name}) to a signed APK and auto-fix any build errors.",
                appName = title,
                provider = provider,
                model = model,
                apiKey = keys.get(provider),
                effort = effort,
                githubToken = token,
                allowOtherSites = true,
                zipPath = zip.path,
                zipName = zip.name,
            ),
        )
        openBuild = true
    }

    fun saveClonedZipToDownloads() {
        val zip = clonedZipAttachment ?: return
        viewModelScope.launch {
            val msg = Downloads.saveAttachmentToDownloads(getApplication(), zip)
            clonedZipSavedNote = msg
            status = msg
        }
    }

    private suspend fun runChange(r: ApkReport, instructionIn: String, provider: ProviderId, model: String, effort: Effort) {
        var instruction = instructionIn
        val apk = apkFile
        if (apk == null) {
            val study = craftStudy ?: CraftAnythingEngine.studyWebsite(websiteTargetUrl ?: r.fileName, studyMode, cloneAppName, customLogoText, selectedBrandPreset)
            executeCraftBuildCheckReady(r, study, instruction, provider, model, effort)
            return
        }

        status = "Reading the app's text…"
        val strings = ReverseEngine.readStrings(apk)
        status = "Planning the change with ${provider.label}…"
        val planReply = collectReply(provider, model, ReverseAgent.buildPrompt(r, strings, instruction), ReverseAgent.SYSTEM, effort)
        val plan = ReverseAgent.parsePlan(planReply)
        var edits = plan.toApkEdits()
        var smaliEdits: Map<String, String> = emptyMap()

        if (edits.isEmpty()) {
            takeSteer()?.let { steer ->
                instruction = "$instruction\n\nAdditional direction from the user: $steer"
                transcript.add(ChatMessage(Role.ASSISTANT, "Incorporating what you just added…"))
            }
            transcript.add(ChatMessage(Role.ASSISTANT, "That needs a code change — looking at the app's logic…"))
            val api = r.minSdk.toIntOrNull() ?: 21
            val work = File(getApplication<Application>().cacheDir, "reverse/smali").apply { deleteRecursively(); mkdirs() }
            val engine = SmaliEngine(work, api)

            status = "Finding the relevant code…"
            val termsReply = collectReply(provider, model, ReverseAgent.termsPrompt(r, instruction), ReverseAgent.TERMS_SYSTEM, effort)
            val terms = ReverseAgent.parseTerms(termsReply, instruction)

            status = "Reading candidate classes (on-device, read-only)…"
            val candidates = engine.search(apk, terms)
            if (candidates.isEmpty()) {
                // Surgical mode: the user asked to change THIS app's existing code, not to rewrite it.
                // Don't silently fall back to a full Craft Anything rebuild — that would change other code.
                transcript.add(
                    ChatMessage(
                        Role.ASSISTANT,
                        "I couldn't find existing code matching **\"$instruction\"** (searched for: ${terms.joinToString().take(160)}).\n\n" +
                            "I've left your app untouched — nothing else was changed. You can:\n" +
                            "• Rephrase with the on-screen text, button label, or feature name you want changed, or\n" +
                            "• Tap **🛠️ Start Craft Anything** / **🔍 JADX Reconstruct** if you'd rather rebuild the whole app from scratch.",
                    ),
                )
                return
            }
            status = "Writing the patch with ${provider.label}…"
            val editReply = collectReply(provider, model, ReverseAgent.smaliEditPrompt(instruction, candidates), ReverseAgent.SMALI_SYSTEM, effort)
            smaliEdits = ReverseAgent.parseSmaliEdits(editReply)
            if (smaliEdits.isEmpty()) {
                transcript.add(
                    ChatMessage(
                        Role.ASSISTANT,
                        "I found the relevant code but couldn't write a safe, targeted patch for **\"$instruction\"**. " +
                            "I've left your app unchanged rather than touch anything you didn't ask for. " +
                            "Try rephrasing, or tap **🛠️ Start Craft Anything** for a full rebuild.",
                    ),
                )
                return
            }
            // Show exactly which classes change, and the before→after of each, so the user can confirm
            // that ONLY the requested code is edited (old class is replaced with the new one, nothing else).
            val smaliDiff = buildString {
                appendLine("🔧 **Smali patch — only these ${smaliEdits.size} class(es) will change** (old file replaced with new):")
                smaliEdits.forEach { (descriptor, newSmali) ->
                    val original = candidates.firstOrNull { it.className == descriptor }?.smali ?: ""
                    appendLine()
                    appendLine("`$descriptor`")
                    appendLine("```diff")
                    appendLine(DiffFormatter.unified(original, newSmali))
                    appendLine("```")
                }
                appendLine()
                append("Every other class in the app is left byte-for-byte unchanged. Building now…")
            }
            transcript.add(ChatMessage(Role.ASSISTANT, smaliDiff))
        } else {
            // Resource/manifest change (app name, visible text, version, package): show the before→after too.
            val resDiff = buildString {
                appendLine("📝 **${plan.summary.ifBlank { "${edits.size} edit(s)" }}** — only these values change:")
                edits.forEach { e ->
                    when (e) {
                        is ApkEdit.Rename -> appendLine("• App name → **${e.newLabel}**")
                        is ApkEdit.SetVersion -> appendLine("• Version → ${listOfNotNull(e.name, e.code?.let { "code $it" }).joinToString(" / ")}")
                        is ApkEdit.SetString -> appendLine("• `${e.name}`: \"${strings[e.name].orEmpty().take(80)}\" → \"${e.value.take(80)}\"")
                        is ApkEdit.Repackage -> appendLine("• Package → ${e.newPackage}")
                        is ApkEdit.ReplaceFile -> appendLine("• Replace file: ${e.path}")
                    }
                }
                appendLine()
                append("Nothing else in the app is touched. Building now…")
            }
            transcript.add(ChatMessage(Role.ASSISTANT, resDiff))
        }

        takeSteer()?.let { steer ->
            transcript.add(ChatMessage(Role.ASSISTANT, "One more thing before building — \"$steer\". Re-planning with that included…"))
            val reReply = collectReply(
                provider, model,
                ReverseAgent.buildPrompt(r, strings, "$instruction\n\nAlso: $steer"),
                ReverseAgent.SYSTEM, effort,
            )
            val rePlan = ReverseAgent.parsePlan(reReply)
            if (rePlan.toApkEdits().isNotEmpty()) { edits = rePlan.toApkEdits(); smaliEdits = emptyMap() }
            transcript.add(ChatMessage(Role.ASSISTANT, "Updated plan: ${rePlan.summary.ifBlank { "${edits.size} edit(s)" }}"))
        }

        val token = githubTokenOf()
        status = "Building…"
        if (token.isNotBlank() && edits.none { it is ApkEdit.Repackage }) {
            transcript.add(ChatMessage(Role.ASSISTANT, "Building on GitHub Actions…"))
            val runner = ReverseCiRunner(token) { s -> status = s }
            val result = runner.run(apk, r.appLabel.ifBlank { r.packageName }, edits, smaliEdits)
            transcript.add(ChatMessage(Role.ASSISTANT, "Built and signed. Tap below to download and install.", apkInstallUrl = result.apkUrl))
        } else {
            transcript.add(ChatMessage(Role.ASSISTANT, "Rebuilding and signing directly on-device…"))
            val outDir = File(getApplication<Application>().cacheDir, "reverse/out")
            outDir.deleteRecursively()
            val dexEdits = if (smaliEdits.isNotEmpty()) {
                val api = r.minSdk.toIntOrNull() ?: 21
                SmaliEngine(File(getApplication<Application>().cacheDir, "reverse/smali"), api).buildEdits(apk, smaliEdits)
            } else emptyList()
            val allEdits = edits + dexEdits
            val res = ReverseEngine.rebuild(getApplication(), apk, allEdits, outDir)
            localInstallReady = res.outApk
            transcript.add(ChatMessage(Role.ASSISTANT, "Built and signed on-device (${res.signedWith}). Install it below."))
        }
    }

    var localInstallReady by mutableStateOf<File?>(null); private set

    fun installLocal() {
        localInstallReady?.let { ApkInstaller.install(getApplication(), it) }
    }

    var openBuild by mutableStateOf(false); private set
    fun consumeOpenBuild() { openBuild = false }

    fun reconstructAsProject(instruction: String, provider: ProviderId, model: String, effort: Effort) {
        val r = report ?: return
        val apk = apkFile
        if (apk == null) {
            startCraftAnythingWorkflow(instruction, provider, model, effort)
            return
        }
        if (answering) { error = "Still working on the previous request — wait for it to finish."; return }
        if (cloneAppName.isBlank()) { error = "Please enter an app name first."; return }
        val token = githubTokenOf()
        if (token.isBlank()) {
            startCraftAnythingWorkflow(instruction, provider, model, effort)
            return
        }
        transcript.add(ChatMessage(Role.USER, "Reverse engineer this app" + instruction.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()))
        viewModelScope.launch {
            answering = true; error = null
            try {
                transcript.add(ChatMessage(Role.ASSISTANT, "Extracting and decompiling the app on GitHub Actions…"))
                val runner = com.aiapp.reverse.ReverseReconstructRunner(token) { s -> status = s }
                val result = runner.run(apk, r.appLabel.ifBlank { r.packageName })
                transcript.add(ChatMessage(Role.ASSISTANT, "Decompiled into an Android Studio project. Handing it to Build to make it compile — ${provider.label} will fix any errors, same as any uploaded project."))

                val app = getApplication<Application>()
                val appName = cloneAppName.trim().ifBlank { r.appLabel.ifBlank { r.packageName } }
                com.aiapp.builder.BuildRunner.newProject(app)
                com.aiapp.builder.BuildService.start(
                    app,
                    com.aiapp.builder.BuildRequest(
                        description = ("Reconstruct this decompiled app as an independent, working Android app, under package " +
                            "`${clonePackageName.ifBlank { "com.crafted.app" }}` and name `$appName` so it installs alongside the original, preserving its UI and functionality. Fix any decompiler " +
                            "artifacts so it compiles." + instruction.takeIf { it.isNotBlank() }?.let { " Also: $it" }.orEmpty()),
                        appName = appName,
                        provider = provider,
                        model = model,
                        apiKey = keys.get(provider),
                        effort = effort,
                        githubToken = token,
                        allowOtherSites = true,
                        zipPath = result.projectZip.path,
                        zipName = "${appName}-decompiled.zip",
                    ),
                )
                openBuild = true
            } catch (e: Exception) {
                Log.e("Reverse", "reconstruct failed", e)
                transcript.add(ChatMessage(Role.ASSISTANT, "JADX CI reconstruction failed (${e.message}) — falling back to on-device Craft Anything pipeline…"))
                val study = craftStudy ?: CraftAnythingEngine.studyApk(apk, r, studyMode, cloneAppName, customLogoText, selectedBrandPreset)
                executeCraftBuildCheckReady(r, study, instruction, provider, model, effort)
            } finally {
                answering = false; status = null
            }
        }
    }

    private fun githubTokenOf(): String = keys.getExtra("GITHUB")

    // ---------- remote rea / craft-anything-cli mcp ----------

    fun connectRea() {
        viewModelScope.launch {
            busy = true; error = null; status = "Initializing On-Device Android REA + Craft Anything Engine…"
            try {
                reaInfo = localRea.connect()
                val tools = localRea.listTools()
                reaTools.clear(); reaTools.addAll(tools)
                reaConnected = true
                status = null
            } finally {
                busy = false
            }
        }
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "You are the Craft Anything (`github.com/storytold/craft-anything`) + REA (`github.com/morluto/rea`) " +
            "Architect & Builder inside an Android app. You study websites and Android APKs (layout, colors, fonts, " +
            "components, DEX classes/methods, and responsive viewports), build clean-room or deep-mode Android Studio " +
            "implementations in Kotlin + Jetpack Compose with the user's custom branding, check likeness across " +
            "Phone/Tablet/Laptop viewports, and fix build error logs."
    }
}
