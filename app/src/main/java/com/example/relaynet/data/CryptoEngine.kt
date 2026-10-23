package com.example.relaynet.data

import android.content.Context
import android.util.Base64
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hybrid Cryptography Engine combining RSA-2048 Asymmetric Key Exchange with
 * AES-256 in Counter Mode (AES/CTR/NoPadding).
 *
 * Why AES-CTR?
 * 1. Stream Cipher semantics: Turns the AES block cipher into a stream cipher, avoiding padding overhead.
 * 2. Parallelizable & Hardware Accelerated: Leverages ARMv8-A Cryptography extensions on mobile CPUs.
 * 3. High Performance: Drastically reduces battery consumption and latency compared to pure asymmetric RSA.
 * 4. Infinite length payload support: Eliminates RSA's ~200 byte limit for mesh payloads.
 */
class CryptoEngine(private val context: Context) {

    private val PREFS_NAME = "relaynet_crypto_prefs"
    private val KEY_MY_PUBLIC = "my_public_key"
    private val KEY_MY_PRIVATE = "my_private_key"
    private val PEER_KEY_PREFIX = "peer_pub_"

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        ensureKeypairGenerated()
    }

    private fun ensureKeypairGenerated() {
        if (!prefs.contains(KEY_MY_PUBLIC) || !prefs.contains(KEY_MY_PRIVATE)) {
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(2048)
            val pair = keyGen.generateKeyPair()

            val pubBase64 = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
            val privBase64 = Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP)

            prefs.edit()
                .putString(KEY_MY_PUBLIC, pubBase64)
                .putString(KEY_MY_PRIVATE, privBase64)
                .apply()
        }
    }

    fun getMyPublicKeyBase64(): String {
        return prefs.getString(KEY_MY_PUBLIC, "") ?: ""
    }

    fun savePeerPublicKey(peerId: String, peerPublicKeyBase64: String) {
        prefs.edit().putString(PEER_KEY_PREFIX + peerId, peerPublicKeyBase64).apply()
    }

    fun getPeerPublicKey(peerId: String): String {
        return prefs.getString(PEER_KEY_PREFIX + peerId, "") ?: ""
    }

    /**
     * Encrypts plaintext using AES-256-CTR with a random 16-byte IV.
     * The 256-bit AES key is encrypted with the recipient's RSA-2048 Public Key.
     */
    fun encryptForPeer(plainText: String, peerId: String): String {
        return try {
            val peerPubKeyBase64 = prefs.getString(PEER_KEY_PREFIX + peerId, null)
            if (peerPubKeyBase64.isNullOrEmpty()) {
                // If peer's key not yet exchanged, send plainText
                return plainText
            }

            // 1. Generate random 256-bit AES symmetric key
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            val aesSecretKey = keyGen.generateKey()

            // 2. Generate random 16-byte Initialization Vector (IV / Nonce + Counter)
            val iv = ByteArray(16)
            SecureRandom().nextBytes(iv)
            val ivSpec = IvParameterSpec(iv)

            // 3. Encrypt payload using AES in CTR Mode (AES/CTR/NoPadding)
            val aesCipher = Cipher.getInstance("AES/CTR/NoPadding")
            aesCipher.init(Cipher.ENCRYPT_MODE, aesSecretKey, ivSpec)
            val cipherTextBytes = aesCipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            // 4. Encrypt the 256-bit AES key with recipient's RSA-2048 Public Key
            val peerKeyBytes = Base64.decode(peerPubKeyBase64, Base64.NO_WRAP)
            val keyFactory = KeyFactory.getInstance("RSA")
            val peerRsaPublicKey = keyFactory.generatePublic(X509EncodedKeySpec(peerKeyBytes))

            val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            rsaCipher.init(Cipher.ENCRYPT_MODE, peerRsaPublicKey)
            val encryptedAesKey = rsaCipher.doFinal(aesSecretKey.encoded)

            // 5. Serialize into standard format: "ENC:CTR:<EncryptedAESKey>:<IV>:<Ciphertext>"
            val encKeyBase64 = Base64.encodeToString(encryptedAesKey, Base64.NO_WRAP)
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val cipherTextBase64 = Base64.encodeToString(cipherTextBytes, Base64.NO_WRAP)

            "ENC:CTR:$encKeyBase64:$ivBase64:$cipherTextBase64"
        } catch (e: Exception) {
            e.printStackTrace()
            plainText
        }
    }

    /**
     * Decrypts an AES-CTR hybrid payload using local RSA Private Key + AES/CTR/NoPadding.
     */
    fun decryptMessage(encryptedPayload: String): String {
        return try {
            if (!encryptedPayload.startsWith("ENC:CTR:")) {
                // Fallback / legacy check for backward compatibility with pure RSA
                return decryptLegacyRSA(encryptedPayload)
            }

            val parts = encryptedPayload.split(":")
            if (parts.size < 5) return encryptedPayload

            val encKeyBase64 = parts[2]
            val ivBase64 = parts[3]
            val cipherTextBase64 = parts[4]

            // 1. Decrypt AES Key using my RSA Private Key
            val privKeyBase64 = prefs.getString(KEY_MY_PRIVATE, null) ?: return encryptedPayload
            val privKeyBytes = Base64.decode(privKeyBase64, Base64.NO_WRAP)
            val keyFactory = KeyFactory.getInstance("RSA")
            val myRsaPrivateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privKeyBytes))

            val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            rsaCipher.init(Cipher.DECRYPT_MODE, myRsaPrivateKey)
            val decryptedAesKeyBytes = rsaCipher.doFinal(Base64.decode(encKeyBase64, Base64.NO_WRAP))
            val aesSecretKey = SecretKeySpec(decryptedAesKeyBytes, "AES")

            // 2. Decrypt Ciphertext using AES-CTR with the recovered IV
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val ivSpec = IvParameterSpec(iv)
            val aesCipher = Cipher.getInstance("AES/CTR/NoPadding")
            aesCipher.init(Cipher.DECRYPT_MODE, aesSecretKey, ivSpec)

            val decryptedBytes = aesCipher.doFinal(Base64.decode(cipherTextBase64, Base64.NO_WRAP))
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            encryptedPayload
        }
    }

    private fun decryptLegacyRSA(encryptedPayload: String): String {
        return try {
            val privKeyBase64 = prefs.getString(KEY_MY_PRIVATE, null) ?: return encryptedPayload
            val keyBytes = Base64.decode(privKeyBase64, Base64.NO_WRAP)
            val keyFactory = KeyFactory.getInstance("RSA")
            val privKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes))

            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.DECRYPT_MODE, privKey)
            val decryptedBytes = cipher.doFinal(Base64.decode(encryptedPayload, Base64.NO_WRAP))
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            encryptedPayload
        }
    }
}
