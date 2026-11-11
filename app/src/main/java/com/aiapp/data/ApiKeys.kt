package com.aiapp.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aiapp.llm.ProviderId
import java.util.concurrent.ConcurrentHashMap

/**
 * API keys, encrypted with a key held in the Android Keystore. Never leaves the phone.
 *
 * Also supports **Multi-Key Rotation & Limit Flag Reset** (as shown in the Clozed Code + REA workflow):
 * - You can paste multiple API keys separated by commas or newlines for any provider.
 * - When a key hits a rate/quota limit (HTTP 429 / 402), [rotateKey] switches to the next key in the pool.
 * - Tapping "Reset Limit Flag" calls [resetLimitFlags] so all keys are marked fresh and ready to continue.
 */
class ApiKeys(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "api_keys",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** Returns the currently active API key for [provider] (rotates automatically if multiple keys are stored). */
    fun get(provider: ProviderId): String {
        val pool = allKeys(provider)
        if (pool.isEmpty()) return ""
        val idx = (activeKeyIndex[provider] ?: 0).mod(pool.size)
        return pool[idx]
    }

    /** Returns the raw saved string (which may contain 1 or multiple comma/newline-separated keys). */
    fun getRaw(provider: ProviderId): String = prefs.getString(provider.name, "").orEmpty()

    /** Parses all keys entered for [provider] (supports single key or multiple keys separated by newline/comma). */
    fun allKeys(provider: ProviderId): List<String> =
        getRaw(provider)
            .split(Regex("[\\r\\n,]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** Rotates to the next API key in the pool for [provider] when a rate limit is hit. Returns the new key, or null if only 1 key exists. */
    fun rotateKey(provider: ProviderId): String? {
        val pool = allKeys(provider)
        if (pool.size <= 1) return null
        val next = ((activeKeyIndex[provider] ?: 0) + 1).mod(pool.size)
        activeKeyIndex[provider] = next
        return pool[next]
    }

    /** Resets rate-limit rotation flags across all providers so tasks can immediately continue from where they stopped. */
    fun resetLimitFlags(provider: ProviderId? = null) {
        if (provider != null) {
            activeKeyIndex[provider] = 0
        } else {
            activeKeyIndex.clear()
        }
        limitResetCounter++
    }

    fun set(provider: ProviderId, key: String) {
        prefs.edit().putString(provider.name, key.trim()).apply()
        activeKeyIndex[provider] = 0
    }

    /** Other secrets, such as the GitHub token Build Mode uses. */
    fun getExtra(name: String): String = prefs.getString("extra_$name", "").orEmpty()

    fun setExtra(name: String, value: String) {
        prefs.edit().putString("extra_$name", value.trim()).apply()
    }

    companion object {
        private val activeKeyIndex = ConcurrentHashMap<ProviderId, Int>()
        @Volatile
        var limitResetCounter: Int = 0
            private set
    }
}
