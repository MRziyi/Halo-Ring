package com.halo.ring.adb

import android.util.Log
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date

/**
 * Generates the RSA-2048 keypair + self-signed X.509 cert that the ADB-over-Wi-Fi flow uses for
 * the TLS client side. Ported (in spirit) from the decompiled v2 `AdbConnection.java`'s
 * `generateRSAKey` + cert-builder logic, simplified with BouncyCastle's high-level builders.
 *
 * The cert is **self-signed**, valid for 365 days, with `CN=r08remote-client`. ADB on the glasses
 * side trusts any cert presented during pairing — we're proving the keypair is consistent across
 * the auth handshake, not establishing an external PKI.
 *
 * Threading: pure computation; ~50 ms one-shot cost. Generate once at first wizard run; cache the
 * encoded PKCS#8 + X.509 in DataStore so subsequent connects skip the cost.
 */
object AdbCrypto {

    private const val TAG = "AdbCrypto"

    /** Generates a fresh RSA-2048 keypair. */
    fun generateRsaKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        return gen.generateKeyPair()
    }

    /** Builds a self-signed X.509 v3 cert valid for [validDays] days. */
    fun selfSignCert(keyPair: KeyPair, commonName: String = "r08remote-client", validDays: Int = 365): X509Certificate {
        val now = Date()
        val end = Date(now.time + validDays * 24L * 3600L * 1000L)
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val name = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, commonName).build()

        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            name, serial, now, end, name, keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, false, BasicConstraints(false))

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    /**
     * ADB's old-style RSA public-key encoding used for the initial AUTH handshake (before TLS was
     * mandated in API 30+). The format is little-endian:
     *   uint32 modulus_size_words
     *   uint32 n0inv (Montgomery)
     *   uint32[64] modulus
     *   uint32[64] rr  (Montgomery R^2 mod n)
     *   uint32 publicExponent
     * For the wireless TLS flow we don't strictly need this — pairing uses the cert directly — but
     * the regular CONNECT/AUTH handshake on legacy USB ADB does. Ported here for completeness.
     */
    fun encodeAdbPublicKey(keyPair: KeyPair): ByteArray {
        val rsa = keyPair.public as RSAPublicKey
        val modulus = rsa.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = (r32 - modulus.modInverse(r32)).toInt()
        // Montgomery `rr = R^2 mod n` where R = 2^2048 for a 2048-bit modulus → R^2 = 2^4096.
        // Earlier this was 2^2048 mod n which adbd writes to adb_keys fine, then rejects on the
        // post-TLS auth check (adbd recomputes rr from the cert modulus before matching base64).
        val rr = BigInteger.ONE.shiftLeft(4096).mod(modulus)

        val baos = ByteArrayOutputStream()
        baos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(64).array())
        baos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n0inv).array())
        baos.write(toLittleEndianWords(modulus, 64))
        baos.write(toLittleEndianWords(rr, 64))
        baos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(rsa.publicExponent.toInt()).array())
        return baos.toByteArray()
    }

    /**
     * ASN.1 DigestInfo prefix for SHA-1. adbd verifies the legacy AUTH signature with
     * `RSA_verify(NID_sha1, token, 20, sig)`, which expects the signed blob to be
     * `DigestInfo(SHA1) || token`. We reproduce that with raw PKCS#1 v1.5 RSA.
     */
    private val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )

    /**
     * Sign the 20-byte AUTH token adbd sends during the **legacy (pre-TLS) RSA handshake** used by
     * the plain `service.adb.tcp.port` transport (our Wi-Fi-independent loopback port). adbd checks
     * the signature against the public keys in `/data/misc/adb/adb_keys`; our key is added there
     * during wireless pairing, so a trusted connection needs no on-device prompt.
     */
    fun signAdbToken(keyPair: KeyPair, token: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keyPair.private)
        return cipher.doFinal(SHA1_DIGEST_INFO + token)
    }

    /**
     * Big-endian → little-endian fixed-width byte array, taking only the last `bytes` bytes of
     * the BigInteger's two's-complement encoding. Java's [BigInteger.toByteArray] always emits a
     * leading sign byte for positive numbers whose high bit is set, so a 2048-bit modulus comes
     * back as 257 bytes (0x00 + 256-byte magnitude). We always want just the magnitude.
     */
    private fun toLittleEndianWords(n: BigInteger, words: Int): ByteArray {
        val bytes = words * 4
        val be = n.toByteArray()
        val le = ByteArray(bytes)
        // Copy at most `bytes` bytes from the END of `be` (= least-significant in two's-complement
        // big-endian order), reversing as we go to produce little-endian magnitude. Anything
        // shorter than `bytes` is naturally left-padded with the array's default zeros.
        val take = minOf(be.size, bytes)
        for (i in 0 until take) {
            le[i] = be[be.size - 1 - i]
        }
        return le
    }

    init {
        // Make sure BouncyCastle is in the JCA provider list. Idempotent.
        if (java.security.Security.getProvider("BC") == null) {
            java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            Log.d(TAG, "BouncyCastle provider registered")
        }
    }
}
