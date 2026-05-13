package com.halo.ring.core.adb

import java.io.DataInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One ADB protocol message — 24-byte header + optional payload. Wire format
 * (https://android.googlesource.com/platform/system/core/+/refs/heads/master/adb/protocol.txt):
 *
 *   command (uint32 LE)     | A_SYNC=0x434e5953, A_CNXN=0x4e584e43, A_OPEN=0x4e45504f, ...
 *   arg0    (uint32 LE)
 *   arg1    (uint32 LE)
 *   data_length (uint32 LE)
 *   data_checksum (uint32 LE)   ← unused in protocol version 0x01000001+, set to 0
 *   magic   (uint32 LE)         ← command XOR 0xffffffff
 *   payload (data_length bytes)
 *
 * The CRC is legacy and ignored by modern adbd. We always send 0 and don't verify on read.
 */
data class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = EMPTY,
) {
    fun write(out: OutputStream) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(payload.size)
        header.putInt(0)                                  // crc — ignored
        header.putInt(command xor 0xFFFFFFFF.toInt())     // magic
        out.write(header.array())
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    override fun equals(other: Any?): Boolean =
        other is AdbMessage && command == other.command && arg0 == other.arg0 &&
            arg1 == other.arg1 && payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var h = command
        h = 31 * h + arg0
        h = 31 * h + arg1
        h = 31 * h + payload.contentHashCode()
        return h
    }

    companion object {
        private val EMPTY = ByteArray(0)

        // Command codes — ASCII letters as little-endian uint32.
        const val A_CNXN: Int = 0x4e584e43.toInt()   // "CNXN" — connection setup
        const val A_AUTH: Int = 0x48545541.toInt()   // "AUTH" — RSA challenge/response
        const val A_OPEN: Int = 0x4e45504f.toInt()   // "OPEN" — open a remote service
        const val A_OKAY: Int = 0x59414b4f.toInt()   // "OKAY" — acknowledge stream
        const val A_CLSE: Int = 0x45534c43.toInt()   // "CLSE" — close stream
        const val A_WRTE: Int = 0x45545257.toInt()   // "WRTE" — write data to stream
        const val A_STLS: Int = 0x534c5453.toInt()   // "STLS" — start TLS (wireless ADB)

        // Connection version + max payload (per protocol v2).
        const val VERSION_V2: Int = 0x01000001
        const val MAX_PAYLOAD: Int = 1 shl 20             // 1 MiB

        /** Reads exactly one [AdbMessage] from the stream, blocking until complete. */
        fun read(stream: DataInputStream): AdbMessage {
            val header = ByteArray(24)
            stream.readFully(header)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = buf.int
            val arg0 = buf.int
            val arg1 = buf.int
            val length = buf.int
            buf.int  // crc — ignored
            val magic = buf.int
            require(magic == cmd xor 0xFFFFFFFF.toInt()) { "ADB header magic mismatch: $cmd / $magic" }
            require(length in 0..MAX_PAYLOAD) { "ADB payload length out of range: $length" }
            val payload = if (length == 0) EMPTY else ByteArray(length).also { stream.readFully(it) }
            return AdbMessage(cmd, arg0, arg1, payload)
        }
    }
}
