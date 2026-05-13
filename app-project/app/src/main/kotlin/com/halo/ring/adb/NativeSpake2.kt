package com.halo.ring.adb

/**
 * Thin Kotlin wrapper around BoringSSL's SPAKE2 (loaded from Android's system libcrypto.so).
 *
 * Why this exists: the pure-Java spake2-java port has an open, unfixed correctness bug
 * (MuntashirAkon/spake2-java#1 — Alice/Bob shared keys don't always agree, traced to
 * EdDSA-Java group ops). We need byte-for-byte compatibility with adbd's BoringSSL, so we
 * call BoringSSL directly via a tiny JNI shim that dlopens libcrypto.so at runtime.
 *
 * Lifecycle: construct → generateMessage(password) → exchange 32-byte msgs over the wire
 * → processMessage(theirMsg) returns the 64-byte shared key → call close() to free the
 * native context.
 */
class NativeSpake2(role: Role, myName: ByteArray, theirName: ByteArray) : AutoCloseable {
    enum class Role(val native: Int) { Alice(0), Bob(1) }

    private var ctx: Long = nativeNew(role.native, myName, theirName)
    private var closed = false

    fun generateMessage(password: ByteArray): ByteArray {
        check(!closed) { "NativeSpake2 already closed" }
        return nativeGenerate(ctx, password)
    }

    fun processMessage(theirMsg: ByteArray): ByteArray {
        check(!closed) { "NativeSpake2 already closed" }
        return nativeProcess(ctx, theirMsg)
    }

    override fun close() {
        if (!closed && ctx != 0L) {
            nativeFree(ctx)
            ctx = 0
            closed = true
        }
    }

    companion object {
        init { System.loadLibrary("halo_spake2") }
        @JvmStatic private external fun nativeNew(role: Int, myName: ByteArray, theirName: ByteArray): Long
        @JvmStatic private external fun nativeGenerate(ctx: Long, password: ByteArray): ByteArray
        @JvmStatic private external fun nativeProcess(ctx: Long, theirMsg: ByteArray): ByteArray
        @JvmStatic private external fun nativeFree(ctx: Long)
    }
}
