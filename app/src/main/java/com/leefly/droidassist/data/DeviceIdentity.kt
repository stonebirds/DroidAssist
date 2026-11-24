package com.leefly.droidassist.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Base64
import java.util.UUID

class DeviceIdentity(private val context: Context) {
    private val alias = "droidassist-device-identity"

    val installationId: String
        get() {
            val preferences = context.getSharedPreferences("device_identity", Context.MODE_PRIVATE)
            return preferences.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
                preferences.edit().putString("installation_id", it).apply()
            }
        }

    val displayName: String
        get() = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()

    fun publicKey(): String {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    ).setDigests(KeyProperties.DIGEST_SHA256).build()
                )
                generateKeyPair()
            }
        }
        return Base64.getEncoder().encodeToString(store.getCertificate(alias).publicKey.encoded)
    }
}
