package com.kevshupp.kevmusic.desktop.soulseek

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Codificación del protocolo de Soulseek (documentado por Nicotine+): enteros little-endian, cadenas con
 * longitud uint32 delante. Mensajes del servidor y de pares 'P': `uint32 longitud, uint32 código, datos`;
 * mensajes de inicio y distribuidos: `uint32 longitud, uint8 código, datos`.
 */
class MsgWriter {
    private val out = ByteArrayOutputStream()

    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun bool(v: Boolean) = u8(if (v) 1 else 0)
    fun u32(v: Int) = apply { repeat(4) { out.write((v ushr (8 * it)) and 0xFF) } }
    fun u32(v: Long) = u32(v.toInt())
    fun u64(v: Long) = apply { repeat(8) { out.write(((v ushr (8 * it)) and 0xFF).toInt()) } }
    fun str(s: String) = apply { val b = s.toByteArray(Charsets.UTF_8); u32(b.size); out.write(b) }
    fun bytes(b: ByteArray) = apply { out.write(b) }

    fun toByteArray(): ByteArray = out.toByteArray()

    /** Mensaje con código uint32 (servidor y pares). */
    fun frame(code: Int): ByteArray {
        val body = out.toByteArray()
        return MsgWriter().u32(body.size + 4).u32(code).bytes(body).toByteArray()
    }

    /** Mensaje con código uint8 (inicio de conexión y red distribuida). */
    fun frameByte(code: Int): ByteArray {
        val body = out.toByteArray()
        return MsgWriter().u32(body.size + 1).u8(code).bytes(body).toByteArray()
    }

    /** Cuerpo comprimido con zlib (resultados de búsqueda, lista de archivos, contenido de carpeta). */
    fun frameZlib(code: Int): ByteArray {
        val packed = deflate(out.toByteArray())
        return MsgWriter().u32(packed.size + 4).u32(code).bytes(packed).toByteArray()
    }
}

class MsgReader(private val buf: ByteArray, var pos: Int = 0) {
    val remaining get() = buf.size - pos

    private fun need(n: Int) { if (remaining < n) throw EOFException("mensaje corto") }
    fun u8(): Int { need(1); return buf[pos++].toInt() and 0xFF }
    fun bool() = u8() != 0
    fun u32(): Long {
        need(4)
        var v = 0L
        for (i in 0 until 4) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }
    fun i32() = u32().toInt()
    fun u64(): Long {
        need(8)
        var v = 0L
        for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }
    fun str(): String {
        val n = u32()
        if (n > remaining) throw EOFException("cadena corta")
        val s = decodeString(buf, pos, n.toInt())
        pos += n.toInt()
        return s
    }
    /** IPv4 enviada como uint32: el byte más alto es el primer octeto. */
    fun ip(): String {
        val v = u32()
        return "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"
    }
    fun rest(): ByteArray = buf.copyOfRange(pos, buf.size).also { pos = buf.size }
}

/** UTF-8; los clientes antiguos mandan Latin-1. */
private fun decodeString(buf: ByteArray, offset: Int, len: Int): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(buf, offset, len)).toString()
} catch (e: java.nio.charset.CharacterCodingException) {
    String(buf, offset, len, Charsets.ISO_8859_1)
}

fun deflate(data: ByteArray): ByteArray {
    val d = Deflater()
    d.setInput(data); d.finish()
    val out = ByteArrayOutputStream(data.size / 2 + 64)
    val chunk = ByteArray(64 * 1024)
    while (!d.finished()) out.write(chunk, 0, d.deflate(chunk))
    d.end()
    return out.toByteArray()
}

fun inflate(data: ByteArray, limit: Int = 128 * 1024 * 1024): ByteArray {
    val inf = Inflater()
    inf.setInput(data)
    val out = ByteArrayOutputStream(data.size * 4)
    val chunk = ByteArray(64 * 1024)
    try {
        while (!inf.finished()) {
            val n = inf.inflate(chunk)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(chunk, 0, n)
            if (out.size() > limit) throw IOException("mensaje demasiado grande")
        }
    } finally {
        inf.end()
    }
    return out.toByteArray()
}

/** Lee un mensaje enmarcado (`uint32 longitud` + cuerpo). */
fun DataInputStream.readFrame(max: Int = 64 * 1024 * 1024): ByteArray {
    val b = ByteArray(4)
    readFully(b)
    val len = (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
    if (len < 1 || len > max) throw IOException("longitud no válida: $len")
    val body = ByteArray(len.toInt())
    readFully(body)
    return body
}

fun InputStream.readU32(): Long {
    val b = ByteArray(4)
    DataInputStream(this).readFully(b)
    return MsgReader(b).u32()
}

fun InputStream.readU64(): Long {
    val b = ByteArray(8)
    DataInputStream(this).readFully(b)
    return MsgReader(b).u64()
}
