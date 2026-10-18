package dev.davplayer.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** The whole connection document is encrypted, including usernames and server addresses. */
class ConnectionStore(context: Context) {
    private val preferences = context.getSharedPreferences("connections", Context.MODE_PRIVATE)

    fun load(): List<ServerConnection> {
        val value = preferences.getString("encrypted", null) ?: return emptyList()
        val envelope = JSONObject(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)))
        val plaintext = cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP))
        val array = JSONArray(String(plaintext, Charsets.UTF_8))
        return (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            ServerConnection.create(
                entry.getString("name"), entry.getString("url"), entry.getString("username"),
                entry.getString("password"), entry.getString("id"),
            )
        }
    }

    // Check commit's Boolean result so the UI never reports a failed disk write as saved.
    @SuppressLint("UseKtx")
    fun save(servers: List<ServerConnection>) {
        val array = JSONArray()
        servers.filterNot { it.isDemo }.forEach { server ->
            array.put(JSONObject().put("id", server.id).put("name", server.name).put("url", server.rootUrl)
                .put("username", server.username).put("password", server.password))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        val envelope = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(data, Base64.NO_WRAP))
        check(preferences.edit().putString("encrypted", envelope.toString()).commit()) { "无法保存连接配置" }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    companion object { private const val KEY_ALIAS = "davplayer.connections.v1" }
}
