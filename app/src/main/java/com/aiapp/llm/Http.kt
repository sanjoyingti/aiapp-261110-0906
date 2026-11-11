package com.aiapp.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val jsonType = "application/json".toMediaType()

    /** Browser-like User-Agent for sites that refuse unknown clients. */
    const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    /** GETs [url] and returns the body as text (blocking; call it off the main thread). Throws on HTTP errors. */
    fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        return client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ProviderException(resp.code, errorMessage(resp.code, body))
            body
        }
    }

    suspend fun getJson(url: String, headers: Map<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ProviderException(resp.code, errorMessage(resp.code, body))
            json.parseToJsonElement(body).jsonObject
        }
    }

    suspend fun getJsonArray(url: String, headers: Map<String, String>): kotlinx.serialization.json.JsonArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ProviderException(resp.code, errorMessage(resp.code, body))
            json.parseToJsonElement(body) as kotlinx.serialization.json.JsonArray
        }
    }

    /** POSTs [body] and emits the payload of every server-sent-event `data:` line. Cancelling the flow cancels the request. */
    fun sse(url: String, headers: Map<String, String>, body: String): Flow<String> = callbackFlow {
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(jsonType))
            .build()
        val call = client.newCall(request)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(if (call.isCanceled()) null else e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val text = resp.body?.string().orEmpty()
                        close(ProviderException(resp.code, errorMessage(resp.code, text)))
                        return
                    }
                    try {
                        val source = resp.body!!.source()
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            if (data.isNotEmpty()) trySendBlocking(data)
                        }
                        close()
                    } catch (e: IOException) {
                        close(if (call.isCanceled()) null else e)
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }

    fun errorMessage(code: Int, body: String): String {
        val message = runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            when (val err = root["error"]) {
                is JsonObject -> (err["message"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> err.contentOrNull
                else -> null
            }
        }.getOrNull()
        return message ?: "HTTP $code"
    }
}
