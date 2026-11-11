package com.aiapp.reverse

import com.aiapp.builder.ReaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile

/**
 * 100% On-Device Android Native REA (`morluto/rea`) + Craft Anything (`storytold/craft-anything`) Tool Engine.
 *
 * Converted from the desktop Windows/Rust/Node implementations to run **entirely on Android**
 * (zero PC, zero Windows executables, zero external Node/Rust servers required).
 *
 * Exposes all core Android reverse-engineering, static analysis, Smali disassembly, JNI inspection,
 * and UI/Design-System study tools directly on the user's phone.
 */
class ReaClient(
    @Suppress("unused") private val config: ReaConfig = ReaConfig("android://on-device"),
) {

    val builtInAndroidTools: List<ReaTool> = listOf(
        ReaTool(
            name = "inspect_android_package",
            description = "On-device ARSCLib + AXML decode of AndroidManifest.xml, package ID, SDK targets, permissions, activities, services, receivers & providers.",
        ),
        ReaTool(
            name = "inspect_apk_signature",
            description = "On-device APK Signing Block (v1 JAR / v2 / v3 / v3.1) verifier, X.509 signer certificate parser & SHA-256 artifact digest.",
        ),
        ReaTool(
            name = "apktool_resource_analysis",
            description = "On-device decoder for res/layout/*.xml view trees, res/values/strings.xml, color tokens, fonts, drawables & bundled assets.",
        ),
        ReaTool(
            name = "inspect_dex_classes",
            description = "On-device Multi-DEX (classes*.dex) class hierarchy & domain package extractor using dexlib2.",
        ),
        ReaTool(
            name = "inspect_android_methods",
            description = "On-device DEX method signature decompiler (ClassName.method(params): ReturnType).",
        ),
        ReaTool(
            name = "inspect_android_fields",
            description = "On-device DEX field declaration extractor (ClassName.field: Type).",
        ),
        ReaTool(
            name = "decompile_class_to_smali",
            description = "On-device baksmali disassembler: searches and disassembles matching DEX classes into human-readable Smali assembly.",
        ),
        ReaTool(
            name = "inspect_native_jni_libraries",
            description = "On-device ELF shared library (.so) inspector across arm64-v8a & armeabi-v7a plus runtime detection (Kotlin/Compose, Flutter, Unity IL2CPP, React Native).",
        ),
        ReaTool(
            name = "extract_network_endpoints",
            description = "On-device scanner for REST/HTTP/HTTPS/WebSocket URLs, API routes, and deep-link schemes inside DEX string pools.",
        ),
        ReaTool(
            name = "craft_study_android_ui",
            description = "On-device Craft Anything Android UI Architect: extracts color hex palette, typography/fonts, screen layouts & Android Phone/Foldable/Tablet viewports.",
        ),
        ReaTool(
            name = "extract_reusable_apk_resources",
            description = "On-device extractor that copies original APK icons, drawables (.png/.webp), fonts (.ttf/.otf), res/raw/*, and assets/* into the rebuilt Android Studio project.",
        ),
    )

    /** Connects to the built-in Android on-device engine (zero PC needed). */
    suspend fun connect(): String = withContext(Dispatchers.Default) {
        "Android On-Device REA + Craft Anything Engine v2.0 (Native Kotlin · Zero PC)"
    }

    /** Lists all built-in on-device Android analysis & reconstruction tools. */
    suspend fun listTools(): List<ReaTool> = withContext(Dispatchers.Default) {
        builtInAndroidTools
    }

    /** Executes an on-device Android REA / Craft Anything tool directly against [apkFile] on the phone. */
    suspend fun runOnDeviceTool(toolName: String, apkFile: File?, query: String = ""): String = withContext(Dispatchers.IO) {
        if (apkFile == null || !apkFile.exists()) {
            return@withContext "Load an Android APK on your phone first to run `$toolName`."
        }
        val summary = ReaEngine.inspectApk(apkFile)
        val report = ReverseEngine.inspect(apkFile)

        when (toolName) {
            "inspect_android_package" -> buildString {
                appendLine("### [On-Device Tool: `inspect_android_package`]")
                appendLine(report.toBriefing())
                if (summary.decodedManifestXml.isNotBlank()) {
                    appendLine("\n```xml")
                    appendLine(summary.decodedManifestXml.take(4_000))
                    appendLine("```")
                }
            }
            "inspect_apk_signature" -> buildString {
                val sig = summary.signatureReport
                appendLine("### [On-Device Tool: `inspect_apk_signature`]")
                appendLine("- **APK File**: `${apkFile.name}` (${"%.2f".format(summary.fileSizeMb)} MB)")
                appendLine("- **SHA-256 Digest**: `${sig.sha256Digest}`")
                appendLine("- **Signing Schemes**: ${sig.schemesSummary}")
                if (sig.signerSubject.isNotBlank()) appendLine("- **Signer Subject**: `${sig.signerSubject}`")
                if (sig.signerIssuer.isNotBlank()) appendLine("- **Signer Issuer**: `${sig.signerIssuer}`")
                if (sig.certSha256.isNotBlank()) appendLine("- **Certificate SHA-256**: `${sig.certSha256}`")
            }
            "apktool_resource_analysis" -> buildString {
                val strings = ReverseEngine.readStrings(apkFile, limit = 80)
                appendLine("### [On-Device Tool: `apktool_resource_analysis`]")
                appendLine("- **Decoded XML Layouts**: ${summary.layouts.size} (${summary.decodedLayouts.size} full XML trees decoded)")
                appendLine("- **Drawables / Mipmaps**: ${summary.drawablesCount}")
                appendLine("- **Bundled Assets**: ${summary.assets.size}")
                appendLine("\n#### Sample String Resources (`res/values/strings.xml`):")
                strings.entries.take(40).forEach { (k, v) ->
                    appendLine("- `$k` = \"${v.replace("\n", " ").take(100)}\"")
                }
                if (summary.decodedLayouts.isNotEmpty()) {
                    appendLine("\n#### Decoded Layout XML Sample:")
                    summary.decodedLayouts.entries.take(3).forEach { (name, xml) ->
                        appendLine("##### `res/layout/$name`")
                        appendLine("```xml\n${xml.take(1_200)}\n```")
                    }
                }
            }
            "inspect_dex_classes" -> buildString {
                appendLine("### [On-Device Tool: `inspect_dex_classes`]")
                appendLine("- **DEX Files**: ${summary.dexFileCount} (`classes*.dex`)")
                appendLine("- **Total Classes**: ${summary.classesCount} (${summary.appClasses.size} domain classes)")
                summary.appClasses.take(80).forEach { appendLine("- `$it`") }
            }
            "inspect_android_methods" -> buildString {
                appendLine("### [On-Device Tool: `inspect_android_methods`]")
                appendLine("- **Total Methods**: ${summary.methodsCount} (showing ${summary.methodSignatures.size} domain signatures)")
                summary.methodSignatures.take(80).forEach { appendLine("- `$it`") }
            }
            "inspect_android_fields" -> buildString {
                appendLine("### [On-Device Tool: `inspect_android_fields`]")
                appendLine("- **Total Fields**: ${summary.fieldsCount} (showing ${summary.fieldSignatures.size} domain fields)")
                summary.fieldSignatures.take(60).forEach { appendLine("- `$it`") }
            }
            "decompile_class_to_smali" -> {
                val searchTerms = query.split(Regex("[^A-Za-z0-9_]+")).filter { it.length >= 3 }
                    .ifEmpty { listOf("MainActivity", "Application", "Activity") }
                val workDir = File(apkFile.parentFile, "smali_tool_tmp").apply { mkdirs() }
                val engine = SmaliEngine(workDir, report.minSdk.toIntOrNull() ?: 21)
                val hits = engine.search(apkFile, searchTerms, maxClasses = 3, charBudget = 12_000)
                buildString {
                    appendLine("### [On-Device Tool: `decompile_class_to_smali` (search: ${searchTerms.joinToString()})]")
                    if (hits.isEmpty()) {
                        appendLine("No classes matched `${searchTerms.joinToString()}`.")
                    } else {
                        hits.forEach { c ->
                            appendLine("#### `${c.className}` (${c.dexEntry})")
                            appendLine("```smali\n${c.smali.take(3_500)}\n```")
                        }
                    }
                }
            }
            "inspect_native_jni_libraries" -> buildString {
                appendLine("### [On-Device Tool: `inspect_native_jni_libraries`]")
                appendLine("- **Detected Runtime**: ${report.runtimeHint}")
                appendLine("- **Supported ABIs**: ${report.abis.joinToString().ifBlank { "Pure JVM / DEX (All Android ABIs)" }}")
                if (summary.nativeLibraries.isEmpty()) {
                    appendLine("- No native `.so` libraries bundled (100% Kotlin/Java DEX).")
                } else {
                    summary.nativeLibraries.forEach { appendLine("- `lib/$it`") }
                }
            }
            "extract_network_endpoints" -> buildString {
                appendLine("### [On-Device Tool: `extract_network_endpoints`]")
                if (summary.networkUrls.isEmpty()) {
                    appendLine("No hardcoded HTTP/HTTPS/WS URLs found in DEX string pools.")
                } else {
                    summary.networkUrls.forEach { appendLine("- `$it`") }
                }
            }
            "craft_study_android_ui" -> {
                val study = CraftAnythingEngine.studyApk(
                    apkFile = apkFile,
                    apkReport = report,
                    mode = CraftAnythingEngine.StudyMode.DEEP,
                    newAppName = "${report.appLabel.ifBlank { "MyApp" }}Craft",
                    customLogoText = report.appLabel.take(16),
                    brandPreset = CraftAnythingEngine.brandPresets.first(),
                )
                study.architectBlueprintMarkdown
            }
            "extract_reusable_apk_resources" -> {
                val bundle = ReaEngine.extractReusableResources(apkFile)
                "### [On-Device Tool: `extract_reusable_apk_resources`]\n" +
                    "Extracted **${bundle.files.size} reusable Android resources** (drawables, mipmaps, fonts, raw media & assets) ready for Android Studio bundling." +
                    bundle.promptSummary
            }
            else -> summary.fullMarkdownSpec
        }
    }

    /** Backwards-compatible overload if called with JSON arguments. */
    suspend fun callTool(name: String, arguments: JsonObject): String {
        val path = arguments["path"]?.jsonPrimitive?.contentOrNull
        val query = arguments["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val f = path?.let { File(it) }?.takeIf { it.exists() }
        return runOnDeviceTool(name, f, query)
    }
}
