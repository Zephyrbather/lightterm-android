package com.lightterm.data.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureCredentialStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val preferences = EncryptedSharedPreferences.create(
        context,
        "lightterm-secure-credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun savePassword(reference: String, password: String) {
        preferences.edit().putString(reference, password).apply()
    }

    fun deletePassword(reference: String?) {
        if (reference.isNullOrBlank()) {
            return
        }
        preferences.edit().remove(reference).apply()
    }

    fun readPassword(reference: String?): String? {
        if (reference.isNullOrBlank()) {
            return null
        }
        return preferences.getString(reference, null)
    }
}
