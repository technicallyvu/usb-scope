package com.technicallyvu.scope.core.fixture

import com.technicallyvu.scope.core.driver.PacketSink
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LoggedPacket(val timestampNanos: Long, val bytes: ByteArray)

private val MAGIC = byteArrayOf(0x55, 0x50, 0x4B, 0x54) // "UPKT"
private const val VERSION = 1

/** Appends raw bulk-IN packets to a `.upkt` stream. Thread-confined to the read loop. */
class PacketLogWriter(out: OutputStream) : PacketSink, AutoCloseable {
    private val out = BufferedOutputStream(out, 1 shl 16)
    private val header = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)

    init {
        this.out.write(MAGIC)
        this.out.write(VERSION)
    }

    override fun onPacket(packet: ByteArray, len: Int, timestampNanos: Long) {
        header.clear()
        header.putLong(timestampNanos).putShort(len.toShort())
        out.write(header.array(), 0, 10)
        out.write(packet, 0, len)
    }

    override fun close() = out.close()
}

object PacketLogReader {
    fun read(input: InputStream): List<LoggedPacket> {
        val data = DataInputStream(input.buffered())
        val magic = ByteArray(4)
        data.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "not a .upkt packet log" }
        val version = data.read()
        require(version == VERSION) { "unsupported .upkt version $version" }
        val result = ArrayList<LoggedPacket>()
        val header = ByteArray(10)
        while (true) {
            try {
                data.readFully(header)
            } catch (e: EOFException) {
                return result
            }
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val ts = bb.long
            val len = bb.short.toInt() and 0xFFFF
            val bytes = ByteArray(len)
            data.readFully(bytes)
            result += LoggedPacket(ts, bytes)
        }
    }
}
