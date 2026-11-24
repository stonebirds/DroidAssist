package com.leefly.droidassist.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal object Security {
    private val random = SecureRandom()

    fun token(byteCount: Int = 32): String = ByteArray(byteCount).also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun numericCode(length: Int): String = buildString(length) {
        repeat(length) { append(random.nextInt(10)) }
    }

    fun password(length: Int = 8): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    fun hash(secret: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val digest = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(secret.toCharArray(), salt, 120_000, 256)).encoded
        return listOf(salt, digest).joinToString(":") { Base64.getEncoder().encodeToString(it) }
    }

    fun verify(secret: String, encoded: String): Boolean {
        val parts = encoded.split(':')
        if (parts.size != 2) return false
        val salt = runCatching { Base64.getDecoder().decode(parts[0]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull() ?: return false
        val actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(secret.toCharArray(), salt, 120_000, expected.size * 8)).encoded
        return MessageDigest.isEqual(expected, actual)
    }
}
