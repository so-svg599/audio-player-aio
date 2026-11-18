package com.so.svg599.musique

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Stores optional WebDAV credentials encrypted with an Android Keystore-backed key. */
object SecureCredentials {
    data class Saved(val server: String, val email: String, val password: String)

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        "pcloud_webdav_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(context: Context, server: String, email: String, password: String) {
        prefs(context).edit().putString("server", server).putString("email", email)
            .putString("password", password).putBoolean("remember", true).apply()
    }

    fun load(context: Context): Saved? {
        val p = prefs(context)
        if (!p.getBoolean("remember", false)) return null
        val server = p.getString("server", null) ?: return null
        val email = p.getString("email", null) ?: return null
        val password = p.getString("password", null) ?: return null
        return Saved(server, email, password)
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
