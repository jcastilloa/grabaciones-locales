/*
 * Persists only MEGA's opaque session token. The AES key never leaves Android Keystore.
 */
package org.jcastillo.grabaciones.integrations.mega

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MegaSessionVault(context: Context, storageNamespace: String? = null) {
    private val appContext = context.applicationContext
    private val suffix = storageNamespace?.let {
        require(it.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid session storage namespace" }
        "-$it"
    }.orEmpty()
    private val accountPrefs = appContext.getSharedPreferences("$ACCOUNT_PREFERENCES$suffix", Context.MODE_PRIVATE)
    private val encryptedSession = AtomicFile(File(appContext.noBackupFilesDir, "$SESSION_FILE$suffix"))
    private val keyAlias = "$KEY_ALIAS$suffix"

    fun accountEmail(): String? = accountPrefs.getString(KEY_ACCOUNT_EMAIL, null)

    fun hasSession(): Boolean = runCatching { readSession() != null }.getOrDefault(false)

    @Synchronized
    fun save(session: String, email: String) {
        require(session.isNotBlank()) { "MEGA returned an empty session" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(session.toByteArray(Charsets.UTF_8))
        var output: FileOutputStream? = null
        try {
            output = encryptedSession.startWrite()
            val data = DataOutputStream(output)
            data.writeInt(FORMAT_VERSION)
            data.writeInt(cipher.iv.size)
            data.write(cipher.iv)
            data.writeInt(ciphertext.size)
            data.write(ciphertext)
            data.flush()
            encryptedSession.finishWrite(output)
        } catch (error: Exception) {
            output?.let(encryptedSession::failWrite)
            throw error
        }
        check(accountPrefs.edit().putString(KEY_ACCOUNT_EMAIL, email).commit()) {
            "Could not persist MEGA account metadata"
        }
    }

    @Synchronized
    fun readSession(): String? {
        val file = encryptedSession.baseFile
        if (!file.isFile) return null
        return try {
            DataInputStream(FileInputStream(file)).use { input ->
                require(input.readInt() == FORMAT_VERSION) { "Unsupported encrypted session format" }
                val ivLength = input.readInt()
                require(ivLength in 12..16) { "Invalid encrypted session IV" }
                val iv = ByteArray(ivLength).also(input::readFully)
                val ciphertextLength = input.readInt()
                require(ciphertextLength in 1..MAX_CIPHERTEXT_BYTES) { "Invalid encrypted session size" }
                val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
                cipher.doFinal(ciphertext).toString(Charsets.UTF_8).takeIf(String::isNotBlank)
            }
        } catch (_: Exception) {
            clear()
            null
        }
    }

    @Synchronized
    fun clear() {
        runCatching { encryptedSession.delete() }
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            keyStore.deleteEntry(keyAlias)
        }
        runCatching { accountPrefs.edit().remove(KEY_ACCOUNT_EMAIL).commit() }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ACCOUNT_PREFERENCES = "mega_account"
        const val KEY_ACCOUNT_EMAIL = "account_email"
        const val SESSION_FILE = "mega-session.bin"
        const val KEY_ALIAS = "grabaciones_locales_mega_session_v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = 1
        const val MAX_CIPHERTEXT_BYTES = 16 * 1024
    }
}
