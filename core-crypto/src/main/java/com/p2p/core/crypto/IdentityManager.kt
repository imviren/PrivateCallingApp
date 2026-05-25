package com.p2p.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityManager @Inject constructor() {

    private val keyStoreAlias = "identity_key"

    init {
        ensureKeyPairExists()
    }

    private fun ensureKeyPairExists() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(keyStoreAlias)) {
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore"
            )
            val spec = KeyGenParameterSpec.Builder(
                keyStoreAlias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }
    }

    private fun getPublicKey(): PublicKey? {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val certificate = keyStore.getCertificate(keyStoreAlias)
        return certificate?.publicKey
    }

    fun getPublicKeyBase64(): String {
        val pubKey = getPublicKey() ?: return ""
        return Base64.encodeToString(pubKey.encoded, Base64.NO_WRAP)
    }

    /**
     * Generates a symmetric 6-digit Short Authentication String (SAS) code
     * based on our public key and the remote public key.
     */
    fun sasSixDigits(remotePublicKeyBase64: String): String {
        val localPubBytes = getPublicKey()?.encoded ?: return "000000"
        val remotePubBytes = try {
            Base64.decode(remotePublicKeyBase64, Base64.NO_WRAP)
        } catch (e: Exception) {
            return "000000"
        }

        // Sort keys to ensure both sides arrive at the exact same SAS code
        val sortedKeys = listOf(localPubBytes, remotePubBytes).sortedWith { a, b ->
            val minLen = minOf(a.size, b.size)
            var cmp = 0
            for (i in 0 until minLen) {
                cmp = a[i].compareTo(b[i])
                if (cmp != 0) break
            }
            if (cmp == 0) a.size.compareTo(b.size) else cmp
        }

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sortedKeys[0])
        digest.update(sortedKeys[1])
        val hash = digest.digest()

        // Extract a 6-digit number from hash bytes
        // Combine 4 bytes into a positive integer
        val value = ((hash[0].toInt() and 0xFF) shl 24) or
                    ((hash[1].toInt() and 0xFF) shl 16) or
                    ((hash[2].toInt() and 0xFF) shl 8) or
                    (hash[3].toInt() and 0xFF)
        val positiveValue = value and 0x7FFFFFFF
        val digits = positiveValue % 1000000
        return String.format(java.util.Locale.US, "%06d", digits)
    }
}
