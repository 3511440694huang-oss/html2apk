package com.html2apk.builder.engine

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * 纯 Java 版 zipalign：把 STORED 条目的数据区对齐到 4 字节。
 *
 * 为什么不用 zipalign 可执行文件：项目原先打包的 zipalign 是 ET_EXEC 非 PIE，
 * Android 10+ 直接拒绝执行（unexpected e_type: 2），导致每次构建都失败。
 * 这里按 zip 结构重写（等价于 zipalign -f 4），不依赖任何外部二进制。
 */
object ZipAligner {

    private const val ALIGN = 4

    private class Entry {
        var flags = 0
        var method = 0
        var time = 0
        var date = 0
        var crc = 0L
        var csize = 0L
        var usize = 0L
        var name: ByteArray = ByteArray(0)
        var extra: ByteArray = ByteArray(0)
        var comment: ByteArray = ByteArray(0)
        var localOff = 0L
        var newOffset = 0L
        var dataOff = 0L
    }

    fun align(input: File, output: File, alignment: Int = ALIGN) {
        if (!input.isFile) throw IOException("APK 不存在: $input")
        val raf = RandomAccessFile(input, "r")
        try {
            val eocd = findEocd(raf) ?: throw IOException("不是有效的 zip/apk（找不到 EOCD）")
            raf.seek(eocd + 10)
            val total = readU16(raf)
            val cdSize = readU32(raf)
            val cdOff = readU32(raf)
            if (total == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) {
                throw IOException("暂不支持 zip64 APK")
            }
            raf.seek(eocd + 20)
            val commentLen = readU16(raf)
            val eocdComment = ByteArray(commentLen)
            raf.readFully(eocdComment)

            val list = ArrayList<Entry>(total)
            raf.seek(cdOff)
            for (i in 0 until total) {
                val sig = readU32(raf)
                if (sig != 0x02014b50L) throw IOException("中央目录损坏（sig=${java.lang.Long.toHexString(sig)}）")
                raf.skipBytes(4)
                val e = Entry()
                e.flags = readU16(raf)
                e.method = readU16(raf)
                e.time = readU16(raf)
                e.date = readU16(raf)
                e.crc = readU32(raf)
                e.csize = readU32(raf)
                e.usize = readU32(raf)
                val nameLen = readU16(raf)
                val extraLen = readU16(raf)
                val commentLen2 = readU16(raf)
                raf.skipBytes(4)
                raf.skipBytes(4)
                e.localOff = readU32(raf)
                e.name = ByteArray(nameLen)
                raf.readFully(e.name)
                e.extra = ByteArray(extraLen)
                raf.readFully(e.extra)
                e.comment = ByteArray(commentLen2)
                raf.readFully(e.comment)
                if (e.csize == 0xFFFFFFFFL || e.usize == 0xFFFFFFFFL || e.localOff == 0xFFFFFFFFL) {
                    throw IOException("暂不支持 zip64 条目: ${String(e.name, Charsets.UTF_8)}")
                }
                list.add(e)
            }

            // 定位每个条目的数据起点（本地头里的 name/extra 长度可能和中央目录不同）
            for (e in list) {
                raf.seek(e.localOff)
                if (readU32(raf) != 0x04034b50L) throw IOException("本地文件头损坏")
                raf.skipBytes(22)
                val ln = readU16(raf)
                val le = readU16(raf)
                e.dataOff = e.localOff + 30 + ln + le
            }

            if (output.exists() && !output.delete()) throw IOException("无法覆盖输出文件: $output")
            val cos = CountingOutputStream(
                BufferedOutputStream(FileOutputStream(output), 64 * 1024)
            )
            try {
                for (e in list) {
                    var extra = e.extra
                    if (e.method == 0) {
                        var pad = ((alignment - ((cos.count() + 30 + e.name.size + extra.size) % alignment)) % alignment).toInt()
                        if (pad > 0) {
                            if (pad < 4) pad += alignment
                            val n = pad - 4
                            val ne = ByteArray(extra.size + pad)
                            System.arraycopy(extra, 0, ne, 0, extra.size)
                            var o = extra.size
                            ne[o++] = 0x35
                            ne[o++] = 0xD9.toByte()
                            ne[o++] = (n and 0xFF).toByte()
                            ne[o] = ((n shr 8) and 0xFF).toByte()
                            extra = ne
                        }
                    }
                    e.newOffset = cos.count()
                    writeU32(cos, 0x04034b50L)
                    writeU16(cos, 20)
                    writeU16(cos, e.flags and 8.inv())
                    writeU16(cos, e.method)
                    writeU16(cos, e.time)
                    writeU16(cos, e.date)
                    writeU32(cos, e.crc)
                    writeU32(cos, e.csize)
                    writeU32(cos, e.usize)
                    writeU16(cos, e.name.size)
                    writeU16(cos, extra.size)
                    cos.write(e.name)
                    cos.write(extra)

                    raf.seek(e.dataOff)
                    var left = e.csize
                    val buf = ByteArray(64 * 1024)
                    while (left > 0) {
                        val n = minOf(buf.size.toLong(), left).toInt()
                        raf.readFully(buf, 0, n)
                        cos.write(buf, 0, n)
                        left -= n
                    }
                }

                val cdStart = cos.count()
                for (e in list) {
                    writeU32(cos, 0x02014b50L)
                    writeU16(cos, 20)
                    writeU16(cos, 20)
                    writeU16(cos, e.flags and 8.inv())
                    writeU16(cos, e.method)
                    writeU16(cos, e.time)
                    writeU16(cos, e.date)
                    writeU32(cos, e.crc)
                    writeU32(cos, e.csize)
                    writeU32(cos, e.usize)
                    writeU16(cos, e.name.size)
                    writeU16(cos, e.extra.size)
                    writeU16(cos, e.comment.size)
                    writeU16(cos, 0)
                    writeU16(cos, 0)
                    writeU32(cos, 0)
                    writeU32(cos, e.newOffset)
                    cos.write(e.name)
                    cos.write(e.extra)
                    cos.write(e.comment)
                }
                val cdEnd = cos.count()

                writeU32(cos, 0x06054b50L)
                writeU16(cos, 0)
                writeU16(cos, 0)
                writeU16(cos, list.size)
                writeU16(cos, list.size)
                writeU32(cos, cdEnd - cdStart)
                writeU32(cos, cdStart)
                writeU16(cos, eocdComment.size)
                cos.write(eocdComment)
            } finally {
                cos.close()
            }
        } finally {
            raf.close()
        }
    }

    /** 校验 APK 内 STORED 条目（尤其 resources.arsc）是否对齐 */
    fun verify(apk: File, alignment: Int = ALIGN): List<String> {
        val bad = ArrayList<String>()
        RandomAccessFile(apk, "r").use { raf ->
            val eocd = findEocd(raf) ?: return bad
            raf.seek(eocd + 10)
            val total = readU16(raf)
            readU32(raf) // cdSize
            val cdOff = readU32(raf)
            raf.seek(cdOff)
            for (i in 0 until total) {
                if (readU32(raf) != 0x02014b50L) break
                raf.skipBytes(4)
                raf.skipBytes(2) // flags
                val method = readU16(raf)
                raf.skipBytes(4) // time + date
                raf.skipBytes(4) // crc
                raf.skipBytes(8) // csize + usize
                val nameLen = readU16(raf)
                val extraLen = readU16(raf)
                val commentLen = readU16(raf)
                raf.skipBytes(8) // disk + internal/external attrs
                val localOff = readU32(raf)
                val nameBytes = ByteArray(nameLen)
                raf.readFully(nameBytes)
                raf.skipBytes(extraLen + commentLen)
                if (method == 0) {
                    val save = raf.filePointer
                    raf.seek(localOff)
                    if (readU32(raf) == 0x04034b50L) {
                        raf.skipBytes(22)
                        val ln = readU16(raf)
                        val le = readU16(raf)
                        val dataOff = localOff + 30 + ln + le
                        if (dataOff % alignment != 0L) {
                            bad.add("${String(nameBytes, Charsets.UTF_8)} @$dataOff")
                        }
                    }
                    raf.seek(save)
                }
            }
        }
        return bad
    }

    private fun findEocd(raf: RandomAccessFile): Long? {
        val len = raf.length()
        val start = maxOf(0L, len - 66560L)
        val buf = ByteArray((len - start).toInt())
        raf.seek(start)
        raf.readFully(buf)
        for (i in buf.size - 22 downTo 0) {
            if (buf[i] == 0x50.toByte() && buf[i + 1] == 0x4b.toByte() &&
                buf[i + 2] == 0x05.toByte() && buf[i + 3] == 0x06.toByte()
            ) {
                val cl = (buf[i + 20].toInt() and 0xFF) or ((buf[i + 21].toInt() and 0xFF) shl 8)
                if (i + 22 + cl == buf.size) return start + i
            }
        }
        return null
    }

    private fun readU16(raf: RandomAccessFile): Int {
        val a = raf.read()
        val b = raf.read()
        if (b < 0) throw IOException("EOF")
        return a or (b shl 8)
    }

    private fun readU32(raf: RandomAccessFile): Long {
        val a = readU16(raf).toLong()
        val b = readU16(raf).toLong()
        return a or (b shl 16)
    }

    private fun writeU16(os: OutputStream, v: Int) {
        os.write(v and 0xFF)
        os.write((v ushr 8) and 0xFF)
    }

    private fun writeU32(os: OutputStream, v: Long) {
        os.write((v and 0xFF).toInt())
        os.write(((v ushr 8) and 0xFF).toInt())
        os.write(((v ushr 16) and 0xFF).toInt())
        os.write(((v ushr 24) and 0xFF).toInt())
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        private var count = 0L

        fun count(): Long = count

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
}
