package com.halo.ring.adb

import android.net.ssl.SSLSockets
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Android Wireless ADB pairing client. Connects to `adb-tls-pairing._tcp` on the target device,
 * sends the in-app RSA public key encrypted under a key derived from `(pairingCode ‖ TLS keying
 * material)`, and on success the server (adbd) adds the public key to its `/data/misc/adb/adb_keys`
 * trust list.
 *
 * After this, [AdbConnection] (TODO — A-2 step 2) can `adb connect` to the `adb-tls-connect`
 * port and run `sync push` / `shell` over the trusted RSA key.
 *
 * Reference: AOSP `packages/modules/adb/pairing_connection/pairing_connection.cpp` + the v2
 * decompiled `AdbPairingClient.java`. Constants below match both.
 *
 * Protocol on the wire (after TLS handshake):
 * ```
 *   ┌─────┬─────────┬──────────────┬─────────────────┐
 *   │ ver │  type   │   length     │     payload     │
 *   │ 1 B │  1 B    │   4 B BE     │  `length` B     │
 *   └─────┴─────────┴──────────────┴─────────────────┘
 *      1     0|1       0..65536       encrypted peer info (AES-128-GCM)
 *
 *   ver = 1 (PROTOCOL_VERSION)
 *   type = 1 (MSG_TYPE_PEER_INFO)
 * ```
 *
 * AES-GCM uses a per-direction monotonic IV (uint64-LE in the first 8 bytes of a 12-byte nonce,
 * remaining 4 bytes are zero). Encryption and decryption have their own independent counters.
 */
class AdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairingCode: String,
    private val keyPair: KeyPair,
) {

    sealed class Result {
        object Success : Result()
        data class Failure(val message: String) : Result()
    }

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var encIv: Long = 0
    private var decIv: Long = 0

    /**
     * Run the full pairing sequence:
     *
     *   1. TCP + TLS 1.3 handshake (self-signed cert; server cert not verified).
     *   2. Export TLS keying material via RFC 5705 (Android 12+ public API).
     *   3. SPAKE2 exchange (BoringSSL Ed25519-based PAKE; we call BoringSSL directly via JNI):
     *      password = pairingCode ‖ tlsKeyMaterial  (per AOSP `pairing_connection.cpp`)
     *      → send our 32-byte SPAKE2 message (type=0)
     *      → receive server's 32-byte SPAKE2 message (type=0)
     *      → process → 64-byte SHA-512 transcript key
     *      → first 16 bytes = AES-128-GCM key
     *   4. Encrypt + send our peer info (type=1, RSA public key payload).
     *   5. Receive + decrypt server's peer info. If decryption succeeds, both sides agreed on
     *      the AES key (i.e. the pairing code was right) and adbd has already added our key to
     *      `/data/misc/adb/adb_keys`. This is the success signal.
     *
     * Blocking I/O throughout — runs on [Dispatchers.IO].
     */
    suspend fun pair(): Result = withContext(Dispatchers.IO) {
        var spake: NativeSpake2? = null
        try {
            // 1. TLS
            if (!connectTls()) {
                return@withContext Result.Failure("TLS handshake to $host:$port failed")
            }
            val ssl = socket as SSLSocket

            // 2. TLS keying material
            val tlsKeyMaterial = exportTlsKeyMaterial(ssl)
                ?: return@withContext Result.Failure(
                    "Could not export TLS keying material (need Android 12+ / SSLSockets API)")
            Log.i(TAG, "TLS keying material: ${tlsKeyMaterial.size} bytes")

            // 3. SPAKE2 exchange.
            //    password = code + tls_material per AOSP pairing_connection.cpp
            val password = pairingCode.toByteArray(Charsets.UTF_8) + tlsKeyMaterial
            spake = NativeSpake2(
                NativeSpake2.Role.Alice,
                CLIENT_NAME.toByteArray(Charsets.UTF_8),
                SERVER_NAME.toByteArray(Charsets.UTF_8),
            )
            val ourSpakeMsg = spake.generateMessage(password)
            check(ourSpakeMsg.size == 32) { "SPAKE2 msg size = ${ourSpakeMsg.size} (expected 32)" }
            sendPairingMessage(MSG_TYPE_SPAKE2, ourSpakeMsg)
            Log.i(TAG, "sent SPAKE2 msg (32 B)")

            val (sType, sBody) = receivePairingMessage()
                ?: return@withContext Result.Failure("server closed before sending SPAKE2 msg")
            if (sType != MSG_TYPE_SPAKE2 || sBody.size != 32) {
                return@withContext Result.Failure(
                    "expected SPAKE2 msg (type=0, len=32) from server, got type=$sType len=${sBody.size}")
            }
            val sharedKey = spake.processMessage(sBody)
            // AOSP `aes_128_gcm.cpp`: AES-128 key = HKDF-SHA256(sharedKey, salt=null,
            // info="adb pairing_auth aes-128-gcm key", length=16). The info string is the
            // 32 ASCII chars WITHOUT trailing NUL (C sizeof(info)-1 excludes the literal NUL).
            val aesKey = ByteArray(AES_KEY_SIZE).also {
                val hkdf = HKDFBytesGenerator(SHA256Digest())
                hkdf.init(HKDFParameters(sharedKey, null, HKDF_INFO.toByteArray(Charsets.UTF_8)))
                hkdf.generateBytes(it, 0, AES_KEY_SIZE)
            }

            // 4. Encrypt + send peer info
            val peerInfo = buildPeerInfo()
            val ciphertext = aesGcmEncrypt(aesKey, peerInfo)
                ?: return@withContext Result.Failure("AES-GCM encrypt of peer info failed")
            sendPairingMessage(MSG_TYPE_PEER_INFO, ciphertext)
            Log.i(TAG, "sent peer info (${peerInfo.size}B plaintext → ${ciphertext.size}B cipher)")

            // 5. Receive + decrypt peer info
            val (rType, rCipher) = receivePairingMessage()
                ?: return@withContext Result.Failure("server closed before sending peer info")
            if (rType != MSG_TYPE_PEER_INFO) {
                return@withContext Result.Failure("unexpected message type from server: $rType")
            }
            val rPlain = aesGcmDecrypt(aesKey, rCipher)
                ?: return@withContext Result.Failure(
                    "server peer info decryption failed — likely wrong pairing code or SPAKE2 mismatch")
            Log.i(TAG, "server peer info decrypted OK (${rPlain.size}B); pairing complete")
            return@withContext Result.Success
        } catch (e: Exception) {
            Log.e(TAG, "pair() exception: ${e.message}", e)
            return@withContext Result.Failure("exception: ${e.message}")
        } finally {
            try { spake?.close() } catch (_: Exception) {}
            disconnect()
        }
    }

    // ── TLS connect ───────────────────────────────────────────────────────────────────────────

    private fun connectTls(): Boolean = try {
        val raw = Socket(host, port).apply {
            soTimeout = 15_000
            tcpNoDelay = true
        }

        // Bundle our (RSA keypair, self-signed cert) into a PKCS12 KeyStore. The KeyStore needs
        // a password — value is arbitrary; lives only in memory for the lifetime of this
        // KeyManagerFactory.
        val pass = "adbkeypass".toCharArray()
        val cert: X509Certificate = AdbCrypto.selfSignCert(keyPair, commonName = "LinuxTransport")
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("adbkey", keyPair.private, pass, arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(ks, pass) }

        // Trust ALL server certs — adbd's cert is self-signed and not in any chain we'd
        // recognize; we're authenticating via the pairing code + TLS keying material, not
        // PKIX.
        val trustAll = arrayOf<X509TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

        val ctx = SSLContext.getInstance("TLSv1.3").apply {
            init(kmf.keyManagers, trustAll, SecureRandom())
        }
        val ssl = (ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket).apply {
            try { enabledProtocols = arrayOf("TLSv1.3") } catch (_: Exception) {}
            try {
                val params = sslParameters
                params.serverNames = emptyList()    // adbd doesn't use SNI
                sslParameters = params
            } catch (_: Exception) {}
        }
        ssl.startHandshake()
        socket = ssl
        inputStream = ssl.inputStream
        outputStream = ssl.outputStream
        Log.i(TAG, "TLS handshake to $host:$port complete (${ssl.session.protocol}, ${ssl.session.cipherSuite})")
        true
    } catch (e: Exception) {
        Log.e(TAG, "connectTls failed: ${e.message}", e)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        false
    }

    // ── TLS keying-material export (RFC 5705) ─────────────────────────────────────────────────

    private fun exportTlsKeyMaterial(ssl: SSLSocket): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.e(TAG, "exportKeyingMaterial requires Android 12+ (current SDK ${Build.VERSION.SDK_INT})")
            return null
        }
        return try {
            // Label has the NUL terminator embedded — the byte sequence is exactly
            //   { 'a','d','b','-','l','a','b','e','l', 0x00 }
            // matching what adbd expects per pairing_connection.cpp.
            SSLSockets.exportKeyingMaterial(ssl, TLS_EXPORTER_LABEL, null, 64)
        } catch (e: Exception) {
            Log.e(TAG, "exportKeyingMaterial failed: ${e.message}", e)
            null
        }
    }

    // ── Peer info: 8192-byte buffer with our public key encoded for adbd ──────────────────────

    private fun buildPeerInfo(): ByteArray {
        val pubKeyBase64 = encodeAdbPublicKeyBase64()
        val keyLine = "$pubKeyBase64 halo-ring@phone\n"
        val buf = ByteArray(PEER_INFO_SIZE)
        buf[0] = PEER_TYPE_RSA_PUBLIC_KEY      // adbd peer-type byte: 0 = RSA pubkey
        val payload = keyLine.toByteArray(Charsets.UTF_8)
        System.arraycopy(payload, 0, buf, 1, minOf(payload.size, PEER_INFO_SIZE - 1))
        return buf
    }

    private fun encodeAdbPublicKeyBase64(): String =
        Base64.encodeToString(AdbCrypto.encodeAdbPublicKey(keyPair), Base64.NO_WRAP)

    // ── Pairing wire framing ──────────────────────────────────────────────────────────────────

    private fun sendPairingMessage(type: Int, data: ByteArray) {
        val out = outputStream ?: throw IOException("not connected")
        val header = ByteBuffer.allocate(HEADER_SIZE).apply {
            put(PROTOCOL_VERSION.toByte())
            put(type.toByte())
            order(ByteOrder.BIG_ENDIAN).putInt(2, data.size)  // length is BE at offset 2
        }.array()
        out.write(header)
        out.write(data)
        out.flush()
        Log.d(TAG, "→ pairing msg type=$type len=${data.size}")
    }

    private fun receivePairingMessage(): Pair<Int, ByteArray>? {
        val input = inputStream ?: throw IOException("not connected")
        val header = ByteArray(HEADER_SIZE)
        readFully(input, header)
        val ver = header[0].toInt() and 0xFF
        val type = header[1].toInt() and 0xFF
        val len = ByteBuffer.wrap(header, 2, 4).order(ByteOrder.BIG_ENDIAN).int
        Log.d(TAG, "← pairing msg ver=$ver type=$type len=$len")
        if (ver != PROTOCOL_VERSION) {
            Log.w(TAG, "unexpected protocol version $ver (expected $PROTOCOL_VERSION)")
        }
        if (len < 0 || len > MAX_PAYLOAD) {
            Log.e(TAG, "invalid payload length $len")
            return null
        }
        val body = ByteArray(len)
        readFully(input, body)
        return type to body
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) throw IOException("stream closed after $read/${buf.size} bytes")
            read += n
        }
    }

    // ── AES-128-GCM with monotonic uint64-LE IV ──────────────────────────────────────────────

    private fun aesGcmEncrypt(key: ByteArray, plaintext: ByteArray): ByteArray? {
        val nonce = ivForCounter(encIv); encIv++
        return aesGcm(key, nonce, plaintext, encrypt = true)
    }

    private fun aesGcmDecrypt(key: ByteArray, ciphertext: ByteArray): ByteArray? {
        val nonce = ivForCounter(decIv); decIv++
        return aesGcm(key, nonce, ciphertext, encrypt = false)
    }

    private fun ivForCounter(counter: Long): ByteArray {
        val nonce = ByteArray(GCM_IV_SIZE)
        ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN).putLong(counter)
        // bytes 8..11 remain zero — matches adbd's IV scheme
        return nonce
    }

    private fun aesGcm(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray? = try {
        val cipher = GCMBlockCipher(AESEngine())
        cipher.init(encrypt, AEADParameters(KeyParameter(key), GCM_TAG_BITS, iv))
        val out = ByteArray(cipher.getOutputSize(data.size))
        val len = cipher.processBytes(data, 0, data.size, out, 0)
        cipher.doFinal(out, len)
        out
    } catch (e: Exception) {
        Log.e(TAG, "AES-GCM ${if (encrypt) "encrypt" else "decrypt"} failed: ${e.message}", e)
        null
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────────────────────

    private fun disconnect() {
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        inputStream = null; outputStream = null; socket = null
    }

    companion object {
        private const val TAG = "AdbPairingClient"

        // Protocol constants (must match adbd / AOSP / the decompiled reference).
        private const val PROTOCOL_VERSION = 1
        private const val MSG_TYPE_SPAKE2 = 0
        private const val MSG_TYPE_PEER_INFO = 1
        private const val HEADER_SIZE = 6
        private const val MAX_PAYLOAD = 65536
        private const val PEER_INFO_SIZE = 8192
        private const val PEER_TYPE_RSA_PUBLIC_KEY: Byte = 0

        // SPAKE2 party names — must match adbd's expectation. NUL-terminated (16 bytes when
        // UTF-8 encoded), because AOSP `pairing_auth.cpp` passes `sizeof("adb pair client")`
        // which is 16 in C (the string literal includes the NUL terminator).
        private const val CLIENT_NAME = "adb pair client" + "\u0000"
        private const val SERVER_NAME = "adb pair server" + "\u0000"

        // AES-128-GCM constants.
        private const val AES_KEY_SIZE = 16            // AES-128
        private const val GCM_IV_SIZE = 12
        private const val GCM_TAG_BITS = 128

        // HKDF info string — matches AOSP `aes_128_gcm.cpp` byte-for-byte. 32 ASCII chars, NO
        // trailing NUL (the C code passes `sizeof(info)-1` which excludes the literal NUL).
        private const val HKDF_INFO = "adb pairing_auth aes-128-gcm key"

        // RFC 5705 exporter label. adbd expects the literal "adb-label" + NUL,
        // i.e. 10 bytes when UTF-8-encoded. The Kotlin compiler emits the embedded
        // NUL byte at runtime; this source file contains no NULs (the 6-char escape
        // sequence below is plain ASCII).
        private const val TLS_EXPORTER_LABEL = "adb-label" + "\u0000"
    }
}
