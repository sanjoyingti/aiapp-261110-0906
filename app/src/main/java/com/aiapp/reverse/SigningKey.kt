package com.aiapp.reverse

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * Signing key used to re-sign rebuilt APKs so Android will install them.
 *
 * A dedicated RSA key is generated once in the device's AndroidKeyStore (hardware-backed where
 * available) and reused. Rebuilt APKs are therefore signed with a per-device debug-style key — they
 * are clearly re-signed by this app and are never passed off as the original author's release build.
 */
object SigningKey {
    private const val ALIAS = "aiapp_reverse_signing"

    fun load(): Pair<PrivateKey, List<X509Certificate>> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) generate()
        val key = ks.getKey(ALIAS, null) as PrivateKey
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        return key to listOf(cert)
    }

    private fun generate() {
        val notBefore = java.util.Date(0L)
        val notAfter = java.util.Date(4_102_444_800_000L) // year 2100
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=AI App Reverse, O=AI App"))
            .setCertificateSerialNumber(java.math.BigInteger.ONE)
            .setCertificateNotBefore(notBefore)
            .setCertificateNotAfter(notAfter)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(spec)
            generateKeyPair()
        }
    }
}
