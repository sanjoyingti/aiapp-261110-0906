package com.aiapp.reverse

import com.aiapp.data.Log
import com.reandroid.apk.ApkBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Split-APK support for the Reverse tab.
 *
 * Apps from the Play Store are usually delivered as several APKs (a base plus language, screen
 * density and CPU splits), packed as `.apks`, `.xapk` or `.apkm`. Each of those is a zip of APKs.
 * [mergeIfBundle] turns such a bundle into one normal APK so every other tool in the app (decoder,
 * smali, Craft Anything, JADX Reconstruct) sees the whole app, not only one part of it.
 *
 * Merging uses ARSCLib's `ApkBundle`, the same library the on-device decoder already uses.
 */
object SplitApk {

    private const val TAG = "SplitApk"

    private fun apkEntries(zip: ZipFile) = zip.entries().asSequence()
        .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
        .toList()

    /** True when [file] is a zip holding two or more APKs and is not itself an APK. */
    fun isBundle(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.getEntry("AndroidManifest.xml") == null && apkEntries(zip).size >= 2
        }
    }.getOrDefault(false)

    /**
     * Returns [file] unchanged when it is a normal APK. For a bundle, returns one merged APK next
     * to it; if merging fails, returns the bundle's base APK so the app can still be studied.
     */
    suspend fun mergeIfBundle(file: File): File = withContext(Dispatchers.IO) {
        if (!isBundle(file)) return@withContext file
        val work = File(file.parentFile, "splits").apply { deleteRecursively(); mkdirs() }
        val parts = mutableListOf<File>()
        try {
            ZipFile(file).use { zip ->
                for (entry in apkEntries(zip)) {
                    // Keep only the file name: never trust folder parts inside an archive.
                    val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                        .replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "part.apk" }
                    var dst = File(work, name)
                    var n = 2
                    while (dst.exists()) dst = File(work, "${name.removeSuffix(".apk")}-${n++}.apk")
                    zip.getInputStream(entry).use { input -> dst.outputStream().use { input.copyTo(it) } }
                    parts += dst
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Couldn't unpack the APK bundle: ${t.message}", t)
            return@withContext file
        }
        if (parts.isEmpty()) return@withContext file
        if (parts.size == 1) return@withContext parts[0]

        val base = baseOf(parts)
        val merged = File(file.parentFile, file.name.substringBeforeLast('.') + "-merged.apk")
        try {
            merged.delete()
            val bundle = ApkBundle()
            try {
                bundle.loadApkDirectory(work)
                val module = bundle.mergeModules()
                module.writeApk(merged)
                runCatching { module.close() }
            } finally {
                runCatching { bundle.close() }
            }
            if (merged.length() > 0) {
                Log.i(TAG, "Merged ${parts.size} split APKs into ${merged.name} (${merged.length() / 1024} KB)")
                merged
            } else {
                base
            }
        } catch (t: Throwable) {
            // Includes OutOfMemoryError on very large apps: fall back to the base APK.
            Log.e(TAG, "Couldn't merge ${parts.size} split APKs (${t.message}); using ${base.name} only", t)
            merged.delete()
            base
        }
    }

    /** The APK that holds the app's code: `base.apk` when present, otherwise the largest one with classes.dex. */
    private fun baseOf(parts: List<File>): File {
        parts.firstOrNull { it.name.equals("base.apk", true) }?.let { return it }
        val withCode = parts.filter { p ->
            runCatching { ZipFile(p).use { it.getEntry("classes.dex") != null } }.getOrDefault(false)
        }
        return (withCode.ifEmpty { parts }).maxByOrNull { it.length() } ?: parts[0]
    }
}
