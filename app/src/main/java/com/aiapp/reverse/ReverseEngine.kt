package com.aiapp.reverse

import android.content.Context
import com.aiapp.builder.ReaEngine
import com.android.apksig.ApkSigner
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

/**
 * On-device APK reverse-engineering engine.
 *
 * Decodes, inspects, edits and re-signs APKs entirely on the phone using ARSCLib (Android-safe,
 * pure-JVM decode/encode of AndroidManifest + resources + archive), `dexlib2`, `ReaEngine`
 * (`github.com/morluto/rea` native port for XML layouts, DEX method/field signatures & reusable resource
 * bundling), and Google's `apksig`.
 *
 * Intended for apps you are authorized to analyse and modify: your own apps, open-source apps,
 * security research, learning, interoperability, and personal modding.
 */
object ReverseEngine {

    // ---------- Inspect ----------

    suspend fun inspect(apk: File): ApkReport = withContext(Dispatchers.IO) {
        val module = ApkModule.loadApkFile(apk)
        val manifest: AndroidManifestBlock? = runCatching { module.androidManifest }.getOrNull()
        val xml = runCatching { manifest?.serializeToXml() }.getOrNull().orEmpty()

        val permissions = runCatching { manifest?.usesPermissions?.toList() }.getOrNull().orEmpty()
        // Prefer the structured manifest tree; fall back to scanning the serialized XML.
        val app = runCatching { manifest?.applicationElement }.getOrNull()
        val activities = componentsOf(app, "activity").ifEmpty { componentNames(xml, "activity") }
        val services = componentsOf(app, "service").ifEmpty { componentNames(xml, "service") }
        val receivers = componentsOf(app, "receiver").ifEmpty { componentNames(xml, "receiver") }
        val providers = componentsOf(app, "provider").ifEmpty { componentNames(xml, "provider") }

        // Archive-level facts straight from the zip (robust, no decode needed).
        val dexFiles = mutableListOf<String>()
        val nativeLibs = sortedSetOf<String>()
        val abis = sortedSetOf<String>()
        val assets = mutableListOf<String>()
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                when {
                    name.matches(Regex("""classes\d*\.dex""")) -> dexFiles += name
                    name.startsWith("lib/") && name.endsWith(".so") -> {
                        nativeLibs += name.substringAfterLast('/')
                        name.removePrefix("lib/").substringBefore('/').let { if (it.isNotBlank()) abis += it }
                    }
                    name.startsWith("assets/") && !name.endsWith("/") -> assets += name.removePrefix("assets/")
                }
            }
        }

        val (classCount, methodCount, sampleClasses) = dexStats(apk, dexFiles, pkg = runCatching { manifest?.packageName }.getOrNull().orEmpty())
        val sampledStrings = runCatching {
            readStrings(apk, limit = 100).entries.map { (k, v) -> "$k=${v.replace("\n", " ").take(80)}" }
        }.getOrDefault(emptyList())

        ApkReport(
            fileName = apk.name,
            sizeBytes = apk.length(),
            packageName = runCatching { manifest?.packageName }.getOrNull().orEmpty().ifBlank { "unknown" },
            appLabel = runCatching { manifest?.applicationLabelString }.getOrNull().orEmpty(),
            versionName = runCatching { manifest?.versionName }.getOrNull().orEmpty().ifBlank { "?" },
            versionCode = runCatching { manifest?.versionCode?.toString() }.getOrNull().orEmpty().ifBlank { "?" },
            minSdk = runCatching { manifest?.minSdkVersion?.toString() }.getOrNull().orEmpty().ifBlank { "?" },
            targetSdk = runCatching { manifest?.targetSdkVersion?.toString() }.getOrNull().orEmpty().ifBlank { "?" },
            mainActivity = runCatching { manifest?.mainActivityClassName }.getOrNull().orEmpty(),
            permissions = permissions.sorted(),
            activities = activities,
            services = services,
            receivers = receivers,
            providers = providers,
            dexFiles = dexFiles.sorted(),
            classCount = classCount,
            methodCount = methodCount,
            sampleClasses = sampleClasses,
            nativeLibs = nativeLibs.toList(),
            abis = abis.toList(),
            runtimeHint = runtimeHint(nativeLibs, assets, dexFiles),
            stringSamples = sampledStrings,
            assetSamples = assets.take(60),
            signature = signatureOf(apk),
            manifestXml = xml.take(60_000),
        )
    }

    /**
     * Produces the deep REA + Clozed Code architectural & UI evidence specification for [apk]
     * (decoded `res/layout/ *.xml` trees, DEX method signatures, field declarations, discovered URLs,
     * UI strings, and bundled original resource inventory) so ANY model can clone or modify the app accurately.
     */
    suspend fun extractDeepReaSpec(apk: File): String = withContext(Dispatchers.IO) {
        runCatching {
            val reaSummary = ReaEngine.inspectApk(apk)
            val bundle = ReaEngine.extractReusableResources(apk)
            reaSummary.fullMarkdownSpec + bundle.promptSummary
        }.getOrElse { "" }
    }

    private fun componentNames(xml: String, tag: String): List<String> =
        Regex("""<$tag\b[^>]*\bandroid:name="([^"]+)"""")
            .findAll(xml).map { it.groupValues[1] }.distinct().toList()

    /** Counts classes/methods across every DEX and samples class names from the app's own package. */
    private fun dexStats(apk: File, dexFiles: List<String>, pkg: String): Triple<Int, Int, List<String>> {
        if (dexFiles.isEmpty()) return Triple(0, 0, emptyList())
        var classes = 0
        var methods = 0
        val sample = LinkedHashSet<String>()
        val prefix = if (pkg.isNotBlank()) "L" + pkg.replace('.', '/') else null
        runCatching {
            ZipFile(apk).use { zip ->
                for (name in dexFiles) {
                    val entry = zip.getEntry(name) ?: continue
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val dex = runCatching { DexBackedDexFile(Opcodes.getDefault(), bytes) }.getOrNull() ?: continue
                    for (cls in dex.classes) {
                        classes++
                        methods += runCatching { cls.methods.count() }.getOrDefault(0)
                        if (sample.size < 120 && (prefix == null || cls.type.startsWith(prefix))) {
                            sample += cls.type.removePrefix("L").removeSuffix(";").replace('/', '.')
                        }
                    }
                }
            }
        }
        return Triple(classes, methods, sample.toList())
    }

    /** Component names read from the parsed manifest tree (robust when serialize-to-XML is unavailable). */
    private fun componentsOf(app: com.reandroid.arsc.chunk.xml.ResXmlElement?, tag: String): List<String> {
        if (app == null) return emptyList()
        val out = LinkedHashSet<String>()
        runCatching {
            val it = app.getElements(tag)
            while (it.hasNext()) {
                it.next().searchAttributeByName("name")?.valueString?.let { n -> if (n.isNotBlank()) out += n }
            }
        }
        return out.toList()
    }

    /**
     * Reads the app's string resources (name -> value, default locale) so the model can be shown the
     * exact UI text it can change. Bounded so a huge app doesn't flood the prompt.
     */
    suspend fun readStrings(apk: File, limit: Int = 400): Map<String, String> = withContext(Dispatchers.IO) {
        val out = linkedMapOf<String, String>()
        runCatching {
            val table = ApkModule.loadApkFile(apk).tableBlock ?: return@runCatching
            for (pkg in table.listPackages()) {
                val it = pkg.getResources("string")
                while (it.hasNext() && out.size < limit) {
                    val res = it.next()
                    val name = res.name ?: continue
                    val entry = res.iterator().asSequence().firstOrNull { e -> !e.isComplex && e.valueAsString != null }
                    val value = entry?.valueAsString ?: continue
                    if (name !in out) out[name] = value
                }
                if (out.size >= limit) break
            }
        }
        out
    }

    /** Replaces a string resource's value across every locale it is defined in. Returns true if found. */
    private fun applyStringEdit(table: TableBlock?, name: String, value: String): Boolean {
        if (table == null) return false
        var changed = false
        for (pkg in table.listPackages()) {
            val res = pkg.getResources("string")
            while (res.hasNext()) {
                val entry = res.next()
                if (entry.name != name) continue
                entry.iterator().forEach { e ->
                    if (!e.isComplex) { runCatching { e.resValue.setValueAsString(value) }; changed = true }
                }
            }
        }
        return changed
    }

    private fun runtimeHint(libs: Set<String>, assets: List<String>, dex: List<String>): String = when {
        libs.any { it.contains("flutter") } || assets.any { it.startsWith("flutter_assets") } -> "Flutter"
        libs.any { it.contains("il2cpp") } || assets.any { it.endsWith("global-metadata.dat") } -> "Unity (IL2CPP)"
        libs.any { it.contains("unity") } -> "Unity (Mono)"
        libs.any { it.contains("reactnativejni") } -> "React Native"
        libs.any { it.contains("hermes") } -> "React Native (Hermes)"
        dex.isNotEmpty() -> "Android (Java/Kotlin)"
        else -> "Unknown"
    }

    private fun signatureOf(apk: File): SignatureInfo {
        val schemes = mutableListOf<String>()
        var subject = ""
        var sha256 = ""
        runCatching {
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                var hasV1 = false
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val n = e.name.uppercase()
                    if (n.startsWith("META-INF/") && (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))) {
                        hasV1 = true
                        if (subject.isBlank()) {
                            runCatching {
                                val cert = zip.getInputStream(e).use {
                                    CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
                                }
                                subject = cert.subjectX500Principal.name
                                sha256 = cert.encoded.sha256Hex()
                            }
                        }
                    }
                }
                if (hasV1) schemes += "v1"
            }
        }
        // v2/v3 live in the APK Signing Block; ARSCLib surfaces it when present.
        runCatching {
            val module = ApkModule.loadApkFile(apk)
            if (module.apkSignatureBlock != null) schemes += "v2/v3"
        }
        return SignatureInfo(schemes, subject, sha256)
    }

    // ---------- Edit + rebuild + re-sign ----------

    /**
     * Applies [edits] to [apk] and writes a freshly signed APK. Manifest-level edits (rename,
     * repackage, version) and whole-file replacements are supported on-device; richer edits are
     * handled through the remote `rea` backend.
     */
    suspend fun rebuild(
        context: Context,
        apk: File,
        edits: List<ApkEdit>,
        outDir: File,
    ): RebuildResult = withContext(Dispatchers.IO) {
        val notes = mutableListOf<String>()
        val module = ApkModule.loadApkFile(apk)
        val manifest = module.androidManifest
        val replacements = mutableMapOf<String, ByteArray>()

        for (edit in edits) when (edit) {
            is ApkEdit.Rename -> { manifest.setApplicationLabel(edit.newLabel); notes += "Renamed app to \"${edit.newLabel}\"" }
            is ApkEdit.Repackage -> { manifest.packageName = edit.newPackage; notes += "Set package to ${edit.newPackage} (manifest-level)" }
            is ApkEdit.SetVersion -> {
                edit.name?.let { manifest.versionName = it }
                edit.code?.let { manifest.versionCode = it }
                notes += "Version -> ${edit.name ?: "(unchanged)"} / ${edit.code ?: "(unchanged)"}"
            }
            is ApkEdit.ReplaceFile -> { replacements[edit.path] = edit.bytes; notes += "Replaced ${edit.path} (${edit.bytes.size} bytes)" }
            is ApkEdit.SetString -> {
                val ok = applyStringEdit(module.tableBlock, edit.name, edit.value)
                notes += if (ok) "Set string '${edit.name}' = \"${edit.value.take(60)}\"" else "String '${edit.name}' not found"
            }
        }

        outDir.mkdirs()
        val stageApk = File(outDir, "rebuilt-unsigned.apk")
        module.writeApk(stageApk)

        // Apply whole-file replacements by rewriting the staged archive (standard zip, then re-sign).
        val toReplace = replacements.ifEmpty { null }
        val rebuilt = if (toReplace == null) stageApk else rewriteZip(stageApk, toReplace, File(outDir, "rebuilt-patched.apk"))

        val minSdk = runCatching { manifest.minSdkVersion ?: 21 }.getOrDefault(21)
        val outApk = File(outDir, "rebuilt-signed.apk")
        signApk(rebuilt, outApk, minSdk)
        RebuildResult(outApk, notes, "debug key (SHA-256 ${debugCertSha().take(16)}…)")
    }

    private fun rewriteZip(src: File, replacements: Map<String, ByteArray>, out: File): File {
        java.util.zip.ZipInputStream(src.inputStream().buffered()).use { zin ->
            java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zout ->
                var e = zin.nextEntry
                while (e != null) {
                    val name = e.name
                    zout.putNextEntry(java.util.zip.ZipEntry(name))
                    if (replacements.containsKey(name)) zout.write(replacements.getValue(name)) else zin.copyTo(zout)
                    zout.closeEntry()
                    e = zin.nextEntry
                }
            }
        }
        return out
    }

    // ---------- Signing (debug PKCS12 embedded as Base64; Android can read PKCS12) ----------

    private fun signApk(input: File, output: File, minSdk: Int) {
        val (key, chain) = SigningKey.load()
        val signer = ApkSigner.SignerConfig.Builder("rev", key, chain).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(minSdk.coerceAtLeast(21))
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .build()
            .sign()
    }

    fun debugCertSha(): String = runCatching {
        SigningKey.load().second.first().encoded.sha256Hex()
    }.getOrDefault("")

    private fun ByteArray.sha256Hex(): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(this)
            .joinToString("") { "%02x".format(it) }
}
