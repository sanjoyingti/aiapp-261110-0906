package com.aiapp.reverse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jf.baksmali.Adaptors.ClassDefinition
import org.jf.baksmali.BaksmaliOptions
import org.jf.baksmali.formatter.BaksmaliWriter
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File
import java.io.StringWriter
import java.util.zip.ZipFile

/**
 * On-device DEX bytecode editing: disassemble `classesN.dex` to smali, let the model patch classes,
 * reassemble, and hand back new DEX bytes for the rebuild pipeline to repackage and re-sign.
 *
 * This is the same disassemble/patch/reassemble workflow Apktool and rea use, done on the phone with
 * Google's smali/baksmali (dexlib2). For apps the user is authorized to modify (their own apps,
 * open-source apps, security research, personal modding).
 *
 * Note: disassembly is memory- and CPU-heavy. Large apps may be slow; work on one target at a time.
 */
class SmaliEngine(private val workDir: File, private val apiLevel: Int = 21) {

    /** A disassembled class the model can read and patch. [className] is the DEX descriptor, e.g. "La/b/C;". */
    data class SmaliClass(val dexEntry: String, val className: String, val smali: String)

    private val opcodes: Opcodes = Opcodes.forApi(apiLevel)

    /** Disassembles one DEX into className -> smali for every class it defines. */
    private fun disassembleDex(dexBytes: ByteArray): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val dex = DexBackedDexFile(opcodes, dexBytes)
        val options = BaksmaliOptions().apply { apiLevel = this@SmaliEngine.apiLevel }
        for (classDef in dex.classes) {
            val sw = StringWriter()
            BaksmaliWriter(sw).use { writer -> ClassDefinition(options, classDef).writeTo(writer) }
            out[classDef.type] = sw.toString()
        }
        return out
    }

    /**
     * Searches every `classesN.dex` for classes whose smali contains any of [terms] (case-insensitive),
     * returning up to [maxClasses] of them with their full smali, capped at [charBudget] total chars.
     */
    suspend fun search(apk: File, terms: List<String>, maxClasses: Int = 6, charBudget: Int = 48_000): List<SmaliClass> =
        withContext(Dispatchers.IO) {
            if (terms.isEmpty()) return@withContext emptyList()
            val needles = terms.map { it.lowercase() }.filter { it.length >= 2 }
            val hits = mutableListOf<SmaliClass>()
            var used = 0
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements() && hits.size < maxClasses) {
                    val e = entries.nextElement()
                    if (!e.name.matches(Regex("""classes\d*\.dex"""))) continue
                    val bytes = zip.getInputStream(e).use { it.readBytes() }
                    val classes = runCatching { disassembleDex(bytes) }.getOrNull() ?: continue
                    for ((name, smali) in classes) {
                        if (hits.size >= maxClasses || used >= charBudget) break
                        val low = smali.lowercase()
                        if (needles.any { low.contains(it) }) {
                            hits += SmaliClass(e.name, name, smali)
                            used += smali.length
                        }
                    }
                }
            }
            hits
        }

    /**
     * Applies [editsByClass] (className -> new full smali) and returns one [ApkEdit.ReplaceFile] per
     * affected DEX, each carrying the fully reassembled DEX bytes (edited + untouched classes).
     */
    suspend fun buildEdits(apk: File, editsByClass: Map<String, String>): List<ApkEdit.ReplaceFile> =
        withContext(Dispatchers.IO) {
            if (editsByClass.isEmpty()) return@withContext emptyList()
            val result = mutableListOf<ApkEdit.ReplaceFile>()
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (!e.name.matches(Regex("""classes\d*\.dex"""))) continue
                    val bytes = zip.getInputStream(e).use { it.readBytes() }
                    val classes = runCatching { disassembleDex(bytes) }.getOrNull() ?: continue
                    val affected = editsByClass.keys.filter { it in classes }
                    if (affected.isEmpty()) continue
                    affected.forEach { classes[it] = editsByClass.getValue(it) }
                    val newDex = assembleDex(e.name, classes.values.toList())
                    result += ApkEdit.ReplaceFile(e.name, newDex)
                }
            }
            result
        }

    /** Reassembles a full set of class smali texts into one DEX and returns its bytes. */
    private fun assembleDex(dexEntry: String, smaliTexts: List<String>): ByteArray {
        val dir = File(workDir, "smali-${dexEntry.replace('.', '_')}").apply { deleteRecursively(); mkdirs() }
        val paths = smaliTexts.mapIndexed { i, text ->
            File(dir, "c$i.smali").apply { writeText(text) }.absolutePath
        }
        val outDex = File(workDir, "out-$dexEntry")
        val options = SmaliOptions().apply {
            apiLevel = this@SmaliEngine.apiLevel
            outputDexFile = outDex.absolutePath
            jobs = 1
        }
        val ok = Smali.assemble(options, paths)
        if (!ok || !outDex.exists()) throw IllegalStateException("Reassembly of $dexEntry failed (invalid smali).")
        return outDex.readBytes()
    }
}
