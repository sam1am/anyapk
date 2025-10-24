package com.anyapk.installer

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import android.sun.security.x509.*
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.*

class AdbConnectionManager private constructor(private val context: Context) : AbsAdbConnectionManager() {

    companion object {
        @Volatile
        private var INSTANCE: AdbConnectionManager? = null

        fun getInstance(context: Context): AdbConnectionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AdbConnectionManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        private const val PREFS_NAME = "adb_keys"
        private const val KEY_PRIVATE = "private_key"
        private const val KEY_CERT = "certificate"
    }

    private var mPrivateKey: PrivateKey? = null
    private var mCertificate: Certificate? = null
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        setApi(Build.VERSION.SDK_INT)
        loadOrGenerateKeys()
    }

    private fun loadOrGenerateKeys() {
        // Try to load existing keys
        val privateKeyStr = prefs.getString(KEY_PRIVATE, null)
        val certStr = prefs.getString(KEY_CERT, null)

        if (privateKeyStr != null && certStr != null) {
            try {
                // Load existing keys
                mPrivateKey = loadPrivateKey(privateKeyStr)
                mCertificate = loadCertificate(certStr)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Generate new keys
        generateAndSaveKeys()
    }

    private fun generateAndSaveKeys() {
        try {
            // Generate key pair
            val keySize = 2048
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(keySize, SecureRandom.getInstance("SHA1PRNG"))
            val keyPair = keyPairGenerator.generateKeyPair()
            val publicKey = keyPair.public
            mPrivateKey = keyPair.private

            // Generate certificate
            val subject = "CN=anyapk"
            val algorithmName = "SHA512withRSA"
            val expiryDate = System.currentTimeMillis() + (365L * 24 * 60 * 60 * 1000 * 10) // 10 years

            val certificateExtensions = CertificateExtensions()
            certificateExtensions.set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(
                KeyIdentifier(publicKey).identifier))

            val x500Name = X500Name(subject)
            val notBefore = Date()
            val notAfter = Date(expiryDate)

            certificateExtensions.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
            val certificateValidity = CertificateValidity(notBefore, notAfter)

            val x509CertInfo = X509CertInfo()
            x509CertInfo.set("version", CertificateVersion(2))
            x509CertInfo.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Integer.MAX_VALUE))
            x509CertInfo.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithmName)))
            x509CertInfo.set("subject", CertificateSubjectName(x500Name))
            x509CertInfo.set("key", CertificateX509Key(publicKey))
            x509CertInfo.set("validity", certificateValidity)
            x509CertInfo.set("issuer", CertificateIssuerName(x500Name))
            x509CertInfo.set("extensions", certificateExtensions)

            val x509CertImpl = X509CertImpl(x509CertInfo)
            x509CertImpl.sign(mPrivateKey, algorithmName)
            mCertificate = x509CertImpl

            // Save keys
            saveKeys()

        } catch (e: Exception) {
            throw RuntimeException("Failed to generate ADB keys", e)
        }
    }

    private fun saveKeys() {
        // In production, consider using Android KeyStore for better security
        prefs.edit()
            .putString(KEY_PRIVATE, Base64.encodeToString(mPrivateKey!!.encoded, Base64.DEFAULT))
            .putString(KEY_CERT, Base64.encodeToString(mCertificate!!.encoded, Base64.DEFAULT))
            .apply()
    }

    private fun loadPrivateKey(encoded: String): PrivateKey {
        val keyBytes = Base64.decode(encoded, Base64.DEFAULT)
        val keyFactory = java.security.KeyFactory.getInstance("RSA")
        return keyFactory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes))
    }

    private fun loadCertificate(encoded: String): Certificate {
        val certBytes = Base64.decode(encoded, Base64.DEFAULT)
        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        return certFactory.generateCertificate(certBytes.inputStream())
    }

    public override fun getPrivateKey(): PrivateKey = mPrivateKey!!

    public override fun getCertificate(): Certificate = mCertificate!!

    public override fun getDeviceName(): String = "anyapk"
}
