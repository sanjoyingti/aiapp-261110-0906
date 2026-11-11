package com.aiapp.reverse

/**
 * Reverse Engineering tab — data types.
 *
 * This feature is a mobile reverse-engineering / APK-analysis workbench, the same category of tool
 * as the MIT-licensed `github.com/morluto/rea`, Apktool, and JADX. It is meant for apps you are
 * authorized to inspect and modify: your own apps, open-source apps, security research, learning,
 * interoperability, and personal modding. It is not for cloning and redistributing someone else's
 * copyrighted app.
 */

/** A decoded, human-readable snapshot of an APK produced entirely on the phone. */
data class ApkReport(
    val fileName: String,
    val sizeBytes: Long,
    val packageName: String,
    val appLabel: String,
    val versionName: String,
    val versionCode: String,
    val minSdk: String,
    val targetSdk: String,
    val mainActivity: String,
    val permissions: List<String>,
    val activities: List<String>,
    val services: List<String>,
    val receivers: List<String>,
    val providers: List<String>,
    val dexFiles: List<String>,
    val classCount: Int = 0,
    val methodCount: Int = 0,
    val sampleClasses: List<String> = emptyList(),
    val nativeLibs: List<String>,
    val abis: List<String>,
    val runtimeHint: String,
    val stringSamples: List<String>,
    val assetSamples: List<String>,
    val signature: SignatureInfo,
    val manifestXml: String,
) {
    /** A compact, model-friendly briefing of everything the on-device decoder found. */
    fun toBriefing(): String = buildString {
        appendLine("APK: $fileName (${"%.1f".format(sizeBytes / 1_048_576.0)} MB)")
        appendLine("package=$packageName  label=$appLabel  version=$versionName ($versionCode)")
        appendLine("minSdk=$minSdk  targetSdk=$targetSdk  runtime=$runtimeHint  abis=${abis.joinToString()}")
        appendLine("mainActivity=$mainActivity")
        appendLine("signing: ${signature.summary}")
        appendLine("permissions (${permissions.size}): ${permissions.joinToString().take(1200)}")
        appendLine("activities (${activities.size}): ${activities.take(60).joinToString()}")
        appendLine("services (${services.size}): ${services.take(40).joinToString()}")
        appendLine("receivers (${receivers.size}): ${receivers.take(40).joinToString()}")
        appendLine("providers (${providers.size}): ${providers.take(20).joinToString()}")
        appendLine("dex: ${dexFiles.joinToString()}  classes=$classCount methods=$methodCount")
        if (sampleClasses.isNotEmpty()) appendLine("classes (sample): ${sampleClasses.take(60).joinToString().take(1500)}")
        appendLine("native libs (${nativeLibs.size}): ${nativeLibs.take(40).joinToString()}")
        if (stringSamples.isNotEmpty()) appendLine("string resources (sample): ${stringSamples.take(80).joinToString(" | ").take(1500)}")
        if (assetSamples.isNotEmpty()) appendLine("assets (sample): ${assetSamples.take(40).joinToString()}")
    }
}

data class SignatureInfo(
    val schemes: List<String>,
    val certSubject: String,
    val certSha256: String,
) {
    val summary get() = (schemes.ifEmpty { listOf("unsigned/unknown") }.joinToString("+")) +
        if (certSubject.isNotBlank()) "  cert=$certSubject" else ""
}

/** One edit the agent (or the user) wants applied to a decoded APK before it is rebuilt. */
sealed interface ApkEdit {
    /** Change the visible app name (android:label). */
    data class Rename(val newLabel: String) : ApkEdit
    /** Change applicationId-style package so the result installs alongside the original. */
    data class Repackage(val newPackage: String) : ApkEdit
    /** Replace a string resource value by name. */
    data class SetString(val name: String, val value: String, val locale: String = "") : ApkEdit
    /** Replace a packaged file's bytes (e.g. an asset, drawable, or res file) by its full path. */
    data class ReplaceFile(val path: String, val bytes: ByteArray) : ApkEdit
    /** Bump versionName / versionCode. */
    data class SetVersion(val name: String?, val code: Int?) : ApkEdit
}

/** The outcome of a rebuild: a signed APK on disk, plus what was changed. */
data class RebuildResult(
    val outApk: java.io.File,
    val appliedNotes: List<String>,
    val signedWith: String,
)

/** One edit the model proposes, in its JSON reply. [type] is rename|version|setString|repackage|replaceFile. */
@kotlinx.serialization.Serializable
data class PlannedEdit(
    val type: String,
    val label: String? = null,
    val name: String? = null,
    val value: String? = null,
    val code: Int? = null,
    val pkg: String? = null,
)

/** Search terms the model wants to look for in the app's smali, for a code/logic change. */
@kotlinx.serialization.Serializable
data class TermList(val terms: List<String> = emptyList())

/** The model's structured plan for an instruction: a human summary plus the edits to apply. */
@kotlinx.serialization.Serializable
data class EditPlan(
    val summary: String = "",
    val edits: List<PlannedEdit> = emptyList(),
) {
    /** Maps the planned edits to the engine's edit types (unknown/unsupported ones are dropped). */
    fun toApkEdits(): List<ApkEdit> = edits.mapNotNull { e ->
        when (e.type.lowercase()) {
            "rename" -> e.label?.let { ApkEdit.Rename(it) }
            "version" -> if (e.name != null || e.code != null) ApkEdit.SetVersion(e.name, e.code) else null
            "setstring", "string" -> if (e.name != null && e.value != null) ApkEdit.SetString(e.name, e.value) else null
            "repackage" -> e.pkg?.let { ApkEdit.Repackage(it) }
            else -> null
        }
    }
}

/** Connection settings for a remote `rea` MCP server the user runs on their own PC/server. */
data class ReaConfig(
    val baseUrl: String,
    val headerName: String = "",
    val headerValue: String = "",
) {
    val isSet get() = baseUrl.isNotBlank()
}

/** One tool exposed by the remote rea server. */
data class ReaTool(val name: String, val description: String)
