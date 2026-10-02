package cc.khixang.axonhub.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import cc.khixang.axonhub.network.AxonException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.jvm.JvmOverloads

class SecureCredentialStore @JvmOverloads constructor(context: Context, private val keyProvider: (() -> SecretKey)? = null) {
    private val prefs = context.getSharedPreferences("axonhub_credentials", Context.MODE_PRIVATE)
    private val alias = "axonhub_credentials_aes_gcm_v1"

    private fun key(): SecretKey {
        keyProvider?.let { return it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }

    @Synchronized fun put(account: String, value: String) {
        if (value.isBlank()) throw AxonException.InvalidCredentials
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val combined = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(account, Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }

    @Synchronized fun get(account: String): String {
        val encoded = prefs.getString(account, null) ?: throw AxonException.Unauthorized
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= 12) throw AxonException.Unauthorized
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
            String(cipher.doFinal(combined.copyOfRange(12, combined.size)), Charsets.UTF_8)
        } catch (e: AxonException) { throw e }
        catch (_: Exception) { throw AxonException.Unauthorized }
    }

    fun remove(account: String) { prefs.edit().remove(account).apply() }
    fun removePrefix(prefix: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
    }
}
