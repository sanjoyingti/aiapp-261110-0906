package com.aiapp.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** A model that runs on the phone with no internet, through Google's LiteRT-LM runtime. */
data class OfflineModel(
    val id: String,
    val name: String,
    /** Hugging Face repo holding the .litertlm file. */
    val repo: String,
    val approxSizeGb: Double,
    /** Phones with less RAM than this get the smaller model recommended. */
    val minRamGb: Int,
    /** Optional known filename in the Hugging Face repo for direct download fallback. */
    val defaultFileName: String = "",
    /** Human-readable description of the model's strengths (REA, coding, unrestricted chat). */
    val description: String = "",
    val isCodingFocused: Boolean = false,
    val isUnrestricted: Boolean = false,
)

object OfflineCatalog {
    /**
     * Complete built-in catalog of free, download-and-use on-device models (no API key or PC required).
     * Includes all Gemma 4 Abliterated/Heretic, SuperGemma 4, Qwen 2.5 Coder, DeepSeek R1 Distill,
     * and official LiteRT-LM models from our previous app.
     */
    val builtIn = listOf(
        OfflineModel(
            id = "khoido-gemma4-e4b-heretic",
            name = "★ Gemma 4 E4B Heretic Flagship (8.35 GB · 12 GB+ RAM · REA & Code)",
            repo = "khoido/gemma-4-E4B-it-ultra-uncensored-heretic-litertlm",
            approxSizeGb = 8.35,
            minRamGb = 12,
            defaultFileName = "model.litertlm",
            description = "Full-precision Gemma 4 E4B Heretic LiteRT-LM build for high-RAM flagship Android devices (12 GB+ RAM).",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "huihui-gemma4-e4b-abliterated",
            name = "★ Huihui Gemma 4 E4B Abliterated (7.8 GB Multimodal · 10 GB+ RAM)",
            repo = "vokash3/Huihui-gemma-4-E4B-it-abliterated-LiteRT-LM",
            approxSizeGb = 7.8,
            minRamGb = 10,
            defaultFileName = "Huihui-gemma-4-E4B-it-abliterated.litertlm",
            description = "High-precision LiteRT-LM conversion of Huihui-ai/Huihui-gemma-4-E4B-it-abliterated with multimodal support.",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "duoneural-gemma4-e4b-abliterated",
            name = "★ Gemma 4 E4B Abliterated (3.7 GB · 8 GB+ RAM · Unrestricted & Coding)",
            repo = "DuoNeural/Gemma-4-Abliterated-LiteRT",
            approxSizeGb = 3.7,
            minRamGb = 8,
            defaultFileName = "Gemma-4-E4B-Abliterated.litertlm",
            description = "Abliterated Gemma 4 E4B for Android (8 GB+ RAM) supporting open-ended chat, REA analysis, and Android coding offline.",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "supergemma4-e4b-abliterated",
            name = "★ SuperGemma 4 E4B Abliterated (3.65 GB · 8 GB+ RAM · Chat & Code)",
            repo = "typomonster/supergemma4-e4b-abliterated-litert-lm",
            approxSizeGb = 3.65,
            minRamGb = 8,
            defaultFileName = "supergemma4-e4b-abliterated.litertlm",
            description = "On-device LiteRT-LM build of Jiunsong/supergemma4-e4b-abliterated for fast on-device chat and coding.",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "gemma-4-e4b-abliterated",
            name = "★ Gemma 4 E4B MTP Abliterated (3.7 GB · Fast Multi-Token Prediction)",
            repo = "olekk/gemma-4-E4B-it-abliterated-litert-lm",
            approxSizeGb = 3.7,
            minRamGb = 8,
            defaultFileName = "gemma-4-E4B-it-abliterated.litertlm",
            description = "Abliterated Gemma 4 E4B with Multi-Token Prediction (MTP), 128K context, and coding/REA capabilities.",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "gemma-4-e4b",
            name = "★ Gemma 4 E4B Official (3.7 GB · 8 GB+ RAM · Best Official Offline)",
            repo = "litert-community/gemma-4-E4B-it-litert-lm",
            approxSizeGb = 3.7,
            minRamGb = 8,
            defaultFileName = "gemma-4-E4B-it.litertlm",
            description = "Official Google LiteRT-LM Gemma 4 E4B model for 8 GB+ RAM phones.",
            isCodingFocused = true,
        ),
        OfflineModel(
            id = "gemma-4-e2b-heretic",
            name = "★ Gemma 4 E2B Heretic (2.5 GB · 4 GB+ RAM Phones · Unrestricted Chat)",
            repo = "jamarag/gemma-4-E2B-it-ultra-uncensored-heretic-litertlm",
            approxSizeGb = 2.5,
            minRamGb = 4,
            defaultFileName = "model.litertlm",
            description = "Heretic fine-tune of Gemma 4 E2B converted to LiteRT-LM for open-ended chat and coding on 4 GB+ RAM devices.",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "gemma-4-e2b-uncensored-max",
            name = "★ Gemma 4 E2B Uncensored-MAX (2.4 GB · 4 GB+ RAM Phones)",
            repo = "PeppX/gemma-4-e2b-uncensored-litertlm",
            approxSizeGb = 2.4,
            minRamGb = 4,
            defaultFileName = "gemma-4-E2B-it-Uncensored-MAX.litertlm",
            description = "Uncensored-MAX fine-tune (prithivMLmods/gemma-4-E2B-it-Uncensored-MAX) with 32K context window.",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "duoneural-gemma4-e2b-abliterated",
            name = "★ Gemma 4 E2B Abliterated (2.5 GB · 4 GB+ RAM)",
            repo = "DuoNeural/Gemma-4-Abliterated-LiteRT",
            approxSizeGb = 2.5,
            minRamGb = 4,
            defaultFileName = "Gemma-4-E2B-Abliterated.litertlm",
            description = "Abliterated Gemma 4 E2B for fast, private on-device chat and coding.",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "gemma-4-e2b",
            name = "Gemma 4 E2B Official (2.6 GB · Smaller Phones · 4 GB+ RAM)",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            approxSizeGb = 2.6,
            minRamGb = 4,
            defaultFileName = "gemma-4-E2B-it.litertlm",
            description = "Official Google LiteRT-LM Gemma 4 E2B model for 4 GB+ RAM phones.",
            isCodingFocused = true,
        ),
        OfflineModel(
            id = "qwen25-coder-1.5b-uncensored",
            name = "★ Qwen 2.5 Coder 1.5B Uncensored (1.7 GB · Fast Open-Weight Coder)",
            repo = "Shaurya2020/Qwen2.5-1.5B-Instruct-uncensored-litert",
            approxSizeGb = 1.7,
            minRamGb = 4,
            defaultFileName = "Qwen2.5-1.5B-Instruct-uncensored_dynamic_wi8_afp32_ekv1280.litertlm",
            description = "Uncensored Qwen 2.5 Coder 1.5B fine-tune for fast on-device code generation, REA analysis, and bug fixing.",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "qwen25-coder-1.5b",
            name = "Qwen 2.5 Coder 1.5B (1.6 GB · Dedicated Android & Kotlin Coder)",
            repo = "litert-community/Qwen2.5-Coder-1.5B-Instruct",
            approxSizeGb = 1.6,
            minRamGb = 4,
            defaultFileName = "Qwen2.5-Coder-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.litertlm",
            description = "Alibaba Qwen 2.5 Coder 1.5B optimized for on-device Kotlin, Java, and XML coding.",
            isCodingFocused = true,
        ),
        OfflineModel(
            id = "deepseek-r1-1.5b",
            name = "DeepSeek R1 Distill Qwen 1.5B (1.7 GB · Step-by-Step Reasoning)",
            repo = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
            approxSizeGb = 1.7,
            minRamGb = 4,
            defaultFileName = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.litertlm",
            description = "DeepSeek R1 Distill 1.5B with on-device chain-of-thought reasoning.",
            isCodingFocused = true,
        ),
        OfflineModel(
            id = "gemma-3-1b",
            name = "Gemma 3 1B (1.0 GB · Fastest, Any Phone · 3 GB+ RAM)",
            repo = "litert-community/Gemma3-1B-IT",
            approxSizeGb = 1.0,
            minRamGb = 3,
            defaultFileName = "gemma3-1b-it-int4.litertlm",
            description = "Ultra-lightweight Gemma 3 1B model that runs on any Android phone.",
        ),
    )

    /** "https://huggingface.co/owner/repo/…" or "owner/repo" → "owner/repo". */
    fun parseRepo(input: String): String? {
        val s = input.trim().removePrefix("https://").removePrefix("http://").removePrefix("huggingface.co/")
            .removePrefix("hf.co/").trim('/')
        val parts = s.split('/')
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        val ok = Regex("^[A-Za-z0-9._-]+$")
        return if (ok.matches(parts[0]) && ok.matches(parts[1])) "${parts[0]}/${parts[1]}" else null
    }

    fun custom(repo: String) = OfflineModel(
        id = "hf-" + repo.lowercase().replace(Regex("[^a-z0-9]+"), "-"),
        name = repo.substringAfter('/') + " (added)",
        repo = repo,
        approxSizeGb = 0.0,
        minRamGb = 0,
    )
}

/**
 * Downloads, stores and runs offline models. Model files live in the app's private storage, so
 * uninstalling the app removes them.
 */
class OfflineProvider(context: Context) : LlmProvider {
    override val id = ProviderId.OFFLINE

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "models").apply { mkdirs() }
    private val prefs = appContext.getSharedPreferences("offline_models", Context.MODE_PRIVATE)

    /** Optional Hugging Face token, for models that need you to accept a licence first. */
    var hfToken: String = ""

    /** Repos the user added, in the order they were added. */
    fun customRepos(): List<String> = prefs.getString("custom", "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun allModels(): List<OfflineModel> = OfflineCatalog.builtIn + customRepos().map { OfflineCatalog.custom(it) }

    fun byId(id: String): OfflineModel? = allModels().firstOrNull { it.id == id }

    /** Adds a model from a Hugging Face link; returns it, or null if the link isn't valid. */
    fun addCustom(link: String): OfflineModel? {
        val repo = OfflineCatalog.parseRepo(link) ?: return null
        val list = customRepos()
        if (repo !in list && OfflineCatalog.builtIn.none { it.repo.equals(repo, true) }) {
            prefs.edit().putString("custom", (list + repo).joinToString("\n")).apply()
        }
        return OfflineCatalog.custom(repo)
    }

    fun removeCustom(model: OfflineModel) {
        prefs.edit().putString("custom", customRepos().filter { it != model.repo }.joinToString("\n")).apply()
    }

    private fun hfHeaders(): Map<String, String> =
        if (hfToken.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $hfToken")

    private val engineLock = Mutex()
    private var engine: Engine? = null
    private var engineModelId: String? = null

    fun file(model: OfflineModel) = File(dir, "${model.id}.litertlm")

    fun isDownloaded(model: OfflineModel) = file(model).exists()

    /** Total RAM in GB, used to recommend a model. */
    fun deviceRamGb(): Int {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return Math.round(info.totalMem / 1_073_741_824.0).toInt()
    }

    fun recommended(): OfflineModel {
        val ram = deviceRamGb()
        return OfflineCatalog.builtIn.firstOrNull { ram >= it.minRamGb } ?: OfflineCatalog.builtIn.last()
    }

    override suspend fun listModels(apiKey: String): List<ModelInfo> =
        allModels().map { m ->
            val suffix = if (isDownloaded(m)) "" else " · download in Settings"
            ModelInfo(m.id, m.name + suffix)
        }

    /** Finds the .litertlm file name in the Hugging Face repo, with fallback to [OfflineModel.defaultFileName]. */
    private suspend fun remoteFileName(model: OfflineModel): String {
        val info = try {
            Http.getJson("https://huggingface.co/api/models/${model.repo}", hfHeaders())
        } catch (e: ProviderException) {
            if (model.defaultFileName.isNotBlank()) return model.defaultFileName
            if (e.code == 401 || e.code == 403) {
                throw ProviderException(e.code, "This model needs a Hugging Face token: accept its licence on huggingface.co, then add your token in Settings.")
            }
            if (e.code == 404) throw ProviderException(404, "Model ${model.repo} not found on Hugging Face.")
            throw e
        } catch (_: Throwable) {
            if (model.defaultFileName.isNotBlank()) return model.defaultFileName
            throw IOException("Couldn't reach Hugging Face to inspect ${model.repo}.")
        }
        val names = (info["siblings"] as? JsonArray).orEmpty().mapNotNull {
            ((it as? JsonObject)?.get("rfilename") as? JsonPrimitive)?.contentOrNull
        }.filter { it.endsWith(".litertlm") }
        if (model.defaultFileName.isNotBlank() && model.defaultFileName in names) {
            return model.defaultFileName
        }
        // Prefer the general build over web or chip-specific ones.
        val chipTags = listOf("web", "qualcomm", "mediatek", "sm8", "mt6", "npu", "ios", "tpu")
        return names.firstOrNull { n -> chipTags.none { n.lowercase().contains(it) } }
            ?: names.firstOrNull()
            ?: model.defaultFileName.takeIf { it.isNotBlank() }
            ?: throw IOException("${model.repo} has no .litertlm file. Only LiteRT-LM models work offline (search Hugging Face for \"litert-lm\").")
    }

    /**
     * Downloads [model], resuming a partial download if one exists. [onProgress] gets 0..1.
     * Cancelling the coroutine stops the download and keeps the partial file for next time.
     */
    suspend fun download(model: OfflineModel, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val target = file(model)
        if (target.exists()) return@withContext
        val part = File(dir, "${model.id}.part")
        val name = remoteFileName(model)
        val url = "https://huggingface.co/${model.repo}/resolve/main/$name"
        val already = if (part.exists()) part.length() else 0L

        val request = Request.Builder().url(url)
            .apply { hfHeaders().forEach { (k, v) -> header(k, v) } }
            .apply { if (already > 0) header("Range", "bytes=$already-") }
            .build()
        Http.client.newCall(request).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                throw ProviderException(resp.code, "This model needs a Hugging Face token: accept its licence on huggingface.co, then add your token in Settings.")
            }
            if (!resp.isSuccessful) throw ProviderException(resp.code, "Download failed (HTTP ${resp.code}).")
            val body = resp.body ?: throw IOException("Empty download.")
            val resumed = resp.code == 206
            val start = if (resumed) already else 0L
            val total = start + body.contentLength().coerceAtLeast(0L)
            RandomAccessFile(part, "rw").use { out ->
                if (!resumed) out.setLength(0)
                out.seek(start)
                val buf = ByteArray(1 shl 16)
                var written = start
                var lastReport = 0L
                body.byteStream().use { input ->
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (total > 0 && written - lastReport > 4_000_000) {
                            lastReport = written
                            onProgress(written.toFloat() / total)
                        }
                    }
                }
            }
        }
        if (!part.renameTo(target)) throw IOException("Couldn't save the model file.")
        onProgress(1f)
    }

    suspend fun delete(model: OfflineModel) = engineLock.withLock {
        if (engineModelId == model.id) closeEngine()
        file(model).delete()
        File(dir, "${model.id}.part").delete()
    }

    @Volatile private var lastUsed = 0L
    private val idleScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    private var idleJob: kotlinx.coroutines.Job? = null

    /** Unloads the model after 3 idle minutes, so it doesn't keep gigabytes of RAM busy. */
    private fun scheduleIdleRelease() {
        lastUsed = System.currentTimeMillis()
        idleJob?.cancel()
        idleJob = idleScope.launch {
            kotlinx.coroutines.delay(3 * 60_000L)
            release()
        }
    }

    /** Frees the loaded model now (unless it's busy answering). Loads again on next use. */
    fun release() {
        if (engineLock.tryLock()) {
            try {
                closeEngine()
            } finally {
                engineLock.unlock()
            }
        }
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        engineModelId = null
    }

    /** How much text (prompt + reply, in tokens) the offline model can hold; more RAM allows more. */
    fun contextTokens(): Int = when {
        deviceRamGb() >= 11 -> 32_768
        deviceRamGb() >= 7 -> 16_384
        else -> 8_192
    }

    /** Keeps the newest messages that fit (with ContextCompactor first), leaving room for the reply. */
    private fun fit(messages: List<ChatMessage>, system: String): List<ChatMessage> {
        val compacted = ContextCompactor.compactIfNeeded(messages, ProviderId.OFFLINE).messages
        var budget = (contextTokens() - 2_500) * 3 - system.length
        val kept = ArrayDeque<ChatMessage>()
        for (m in compacted.asReversed()) {
            val t = AttachmentContent.text(m, AttachmentContent.Target.TEXT_ONLY, false)
            if (t.length > budget) {
                if (kept.isEmpty()) kept.addFirst(m.copy(text = t.takeLast(budget.coerceAtLeast(2_000)), attachments = emptyList()))
                break
            }
            budget -= t.length
            kept.addFirst(m)
        }
        return kept.toList()
    }

    /** Loads [modelId] into memory now (e.g. when voice mode starts). */
    suspend fun preload(modelId: String) {
        val m = byId(modelId) ?: return
        if (!isDownloaded(m)) return
        engineLock.withLock { loadEngine(m) }
    }

    /** Loads the model once and keeps it in memory; tries the GPU first and falls back to the CPU. */
    private fun loadEngine(model: OfflineModel): Engine {
        engine?.let { if (engineModelId == model.id) return it }
        closeEngine()
        val path = file(model).absolutePath
        val cache = appContext.cacheDir.path
        val loaded = try {
            Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), maxNumTokens = contextTokens(), cacheDir = cache)).also { it.initialize() }
        } catch (gpu: Throwable) {
            Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), maxNumTokens = contextTokens(), cacheDir = cache)).also { it.initialize() }
        }
        engine = loaded
        engineModelId = model.id
        return loaded
    }

    override fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String> = flow {
        val m = byId(model) ?: throw ProviderException(400, "Unknown offline model.")
        if (!isDownloaded(m)) {
            throw ProviderException(404, "Download ${m.name} in Settings → Offline models first.")
        }
        val fitted = fit(messages, system)
        val last = fitted.lastOrNull { it.role == Role.USER } ?: return@flow
        val history = fitted.dropLast(1).map {
            val t = AttachmentContent.text(it, AttachmentContent.Target.TEXT_ONLY, false)
            if (it.role == Role.USER) Message.user(t) else Message.model(t)
        }
        engineLock.withLock {
            val eng = loadEngine(m)
            val config = ConversationConfig(
                systemInstruction = Contents.of(system + effortHint(effort)),
                initialMessages = history,
            )
            scheduleIdleRelease()
            try {
                eng.createConversation(config).use { conversation ->
                    conversation.sendMessageAsync(AttachmentContent.text(last, AttachmentContent.Target.TEXT_ONLY, false))
                        .collect { emit(it.toString()) }
                }
            } catch (e: Exception) {
                if (e.message?.contains("template", ignoreCase = true) == true) {
                    throw ProviderException(500, "${m.name} isn't compatible with this app (its chat template fails to load). Pick a different offline model in Settings.")
                }
                throw e
            }
        }
    }.flowOn(Dispatchers.Default)

    /** Small on-device models have no reasoning switch, so the Brain level becomes an instruction. */
    private fun effortHint(effort: Effort): String = when (effort) {
        Effort.LOW -> " Keep answers short and direct."
        Effort.HIGH -> " Think the problem through step by step before giving the final answer."
        Effort.MAX -> " Reason very carefully step by step, check your work, then give the final answer."
        else -> ""
    }
}
