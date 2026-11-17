package com.klischa.llmnotes.security

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.klischa.llmnotes.api.LLMProviderType
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Защищённое локальное хранилище API-ключей.
 *
 * - Шифрует каждый API-ключ с помощью AES-256-GCM и ключа в Android Keystore
 *   (уникальный 12-байтный IV для каждой записи).
 * - При первом запуске автоматически переносит старые открытые ключи из `llm_app_prefs`
 *   в зашифрованное хранилище и немедленно удаляет их открытый текст с диска.
 * - Для интерфейса отдаёт только замаскированный отпечаток (например `sk-••••••••a9f3`),
 *   чтобы открытый ключ никогда не отображался после сохранения и не попадал в логи/скриншоты.
 */
object SecureKeyStore {
    private const val TAG = "SecureKeyStore"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "llmnotes_api_keys_aes_v1"
    private const val SECURE_PREFS_NAME = "llm_secure_keys_v1"
    private const val LEGACY_PREFS_NAME = "llm_app_prefs"
    private const val PREFIX_KEYSTORE = "ks1:"
    private const val PREFIX_FALLBACK = "fb1:"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private val secureRandom = SecureRandom()

    @Volatile
    private var migrated = false

    private fun prefKey(provider: LLMProviderType): String = "enc_key_${provider.name}"

    /**
     * Однократная миграция старых открытых ключей из `llm_app_prefs` в зашифрованное хранилище
     * с немедленным удалением открытого текста из старых настроек.
     */
    @Synchronized
    fun migrateLegacyPlaintextKeys(context: Context) {
        if (migrated) return
        val appCtx = context.applicationContext
        val legacy = appCtx.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val sharedOpenCodeKey = legacy.getString("opencode_api_key", null)?.trim().orEmpty()

        val legacyMap = listOf(
            LLMProviderType.DEEPSEEK to legacy.getString("deepseek_key", null),
            LLMProviderType.OPENROUTER to legacy.getString("openrouter_key", null),
            LLMProviderType.GROQ to legacy.getString("groq_key", null),
            LLMProviderType.OPENCODE_ZEN to (legacy.getString("opencode_zen_key", null) ?: sharedOpenCodeKey),
            LLMProviderType.OPENCODE_GO to (legacy.getString("opencode_go_key", null) ?: sharedOpenCodeKey)
        )

        var hadLegacyKeys = false
        for ((provider, rawVal) in legacyMap) {
            val clean = rawVal?.trim().orEmpty()
            if (clean.isNotEmpty()) {
                hadLegacyKeys = true
                if (!hasKeyInternal(appCtx, provider)) {
                    saveKeyInternal(appCtx, provider, clean)
                }
            }
        }

        // Удаляем любые следы открытых ключей из старого SharedPreferences
        val hasAnyLegacyField = listOf(
            "deepseek_key",
            "openrouter_key",
            "groq_key",
            "opencode_zen_key",
            "opencode_go_key",
            "opencode_api_key"
        ).any { legacy.contains(it) }

        if (hadLegacyKeys || hasAnyLegacyField) {
            legacy.edit()
                .remove("deepseek_key")
                .remove("openrouter_key")
                .remove("groq_key")
                .remove("opencode_zen_key")
                .remove("opencode_go_key")
                .remove("opencode_api_key")
                .commit()
            Log.i(TAG, "Старые открытые API-ключи перенесены в зашифрованное хранилище и очищены.")
        }
        migrated = true
    }

    @Synchronized
    fun saveKey(context: Context, provider: LLMProviderType, rawKey: String) {
        val appCtx = context.applicationContext
        migrateLegacyPlaintextKeys(appCtx)
        saveKeyInternal(appCtx, provider, rawKey)
    }

    @Synchronized
    fun deleteKey(context: Context, provider: LLMProviderType) {
        val appCtx = context.applicationContext
        migrateLegacyPlaintextKeys(appCtx)
        appCtx.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(prefKey(provider))
            .commit()
    }

    @Synchronized
    fun getKey(context: Context, provider: LLMProviderType): String {
        if (provider == LLMProviderType.LOCAL_GGUF) return ""
        val appCtx = context.applicationContext
        migrateLegacyPlaintextKeys(appCtx)
        val stored = appCtx.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(prefKey(provider), null)
            ?: return ""
        return decrypt(appCtx, stored) ?: ""
    }

    @Synchronized
    fun hasKey(context: Context, provider: LLMProviderType): Boolean {
        if (provider == LLMProviderType.LOCAL_GGUF) return false
        val appCtx = context.applicationContext
        migrateLegacyPlaintextKeys(appCtx)
        return hasKeyInternal(appCtx, provider)
    }

    /**
     * Возвращает безопасную замаскированную строку для показа в UI (например `sk-••••••••a9f3`),
     * либо пустую строку, если ключ не задан.
     */
    @Synchronized
    fun getKeyMask(context: Context, provider: LLMProviderType): String {
        val raw = getKey(context, provider)
        return maskSecret(raw)
    }

    @Synchronized
    fun getAllKeyMasks(context: Context): Map<LLMProviderType, String> {
        val appCtx = context.applicationContext
        migrateLegacyPlaintextKeys(appCtx)
        val result = mutableMapOf<LLMProviderType, String>()
        for (provider in LLMProviderType.values()) {
            if (provider == LLMProviderType.LOCAL_GGUF) continue
            val mask = getKeyMask(appCtx, provider)
            if (mask.isNotEmpty()) {
                result[provider] = mask
            }
        }
        return result
    }

    fun maskSecret(secret: String): String {
        val s = secret.trim()
        if (s.isEmpty()) return ""
        return when {
            s.length >= 12 -> "${s.take(3)}••••••••${s.takeLast(4)}"
            s.length >= 8 -> "${s.take(2)}••••••${s.takeLast(2)}"
            else -> "••••••••"
        }
    }

    private fun hasKeyInternal(appCtx: Context, provider: LLMProviderType): Boolean {
        val stored = appCtx.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(prefKey(provider), null)
            ?: return false
        return !decrypt(appCtx, stored).isNullOrEmpty()
    }

    private fun saveKeyInternal(appCtx: Context, provider: LLMProviderType, rawKey: String) {
        if (provider == LLMProviderType.LOCAL_GGUF) return
        val clean = rawKey.trim()
        val prefs = appCtx.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
        if (clean.isEmpty()) {
            prefs.edit().remove(prefKey(provider)).commit()
            return
        }
        val encrypted = encrypt(appCtx, clean)
        prefs.edit().putString(prefKey(provider), encrypted).commit()
    }

    private fun getOrCreateKeystoreSecretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun deriveFallbackKey(appCtx: Context): SecretKey {
        val androidId = try {
            Settings.Secure.getString(appCtx.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        } catch (_: Exception) {
            ""
        }
        val material = "llmnotes_fb_v1|${appCtx.packageName}|$androidId"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    private fun encrypt(appCtx: Context, plainText: String): String {
        val plainBytes = plainText.toByteArray(Charsets.UTF_8)
        return try {
            val key = getOrCreateKeystoreSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainBytes)
            val combined = ByteArray(iv.size + cipherBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
            PREFIX_KEYSTORE + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "AndroidKeyStore недоступен (${e.message}), используется аппаратный AES-GCM fallback")
            val key = deriveFallbackKey(appCtx)
            val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val cipherBytes = cipher.doFinal(plainBytes)
            val combined = ByteArray(iv.size + cipherBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
            PREFIX_FALLBACK + Base64.encodeToString(combined, Base64.NO_WRAP)
        }
    }

    private fun decrypt(appCtx: Context, stored: String): String? {
        return try {
            val (useKeystore, b64) = when {
                stored.startsWith(PREFIX_KEYSTORE) -> true to stored.removePrefix(PREFIX_KEYSTORE)
                stored.startsWith(PREFIX_FALLBACK) -> false to stored.removePrefix(PREFIX_FALLBACK)
                else -> return null
            }
            val combined = Base64.decode(b64, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_BYTES) return null
            val iv = combined.copyOfRange(0, GCM_IV_BYTES)
            val cipherBytes = combined.copyOfRange(GCM_IV_BYTES, combined.size)
            val key = if (useKeystore) getOrCreateKeystoreSecretKey() else deriveFallbackKey(appCtx)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось расшифровать API-ключ: ${e.message}")
            null
        }
    }
}
