import java.io.*;
import java.util.*;


/** Minimal zipalign implementation: aligns stored entries' data to `align` bytes. */
public final class ZipAlign {
    static final int DEFAULT_ALIGN = 4;

    static final class Entry {
        int flags, method, time, date;
        long crc, csize, usize;
        byte[] name, extra, comment;
        long localOff, newOffset;
        long dataOff;
    }

    public static void main(String[] args) throws Exception {
        int align = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_ALIGN;
        align(new File(args[0]), new File(args[1]), align);
        System.out.println("aligned -> " + args[1]);
    }

    public static void align(File in, File out, int align) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(in, "r");
        try {
            long[] eocd = findEocd(raf);
            if (eocd == null) throw new IOException("EOCD not found");
            long eocdPos = eocd[0];
            raf.seek(eocdPos + 10);
            int total = readU16(raf);
            long cdSize = readU32(raf);
            long cdOff = readU32(raf);
            if (total == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) {
                throw new IOException("zip64 not supported");
            }
            long cdStartPos = raf.getFilePointer(), cdEndPos;
            raf.seek(eocdPos - 12); // read comment length before EOCD? offset 20 in EOCD
            raf.seek(eocdPos + 20);
            int commentLen = readU16(raf);
            byte[] eocdComment = new byte[commentLen];
            raf.readFully(eocdComment);

            // ---- parse central directory ----
            List<Entry> list = new ArrayList<>(total);
            raf.seek(cdOff);
            for (int i = 0; i < total; i++) {
                long centralPos = raf.getFilePointer();
                long sig = readU32(raf);
                if (sig != 0x02014b50L) throw new IOException("bad central header at " + centralPos + " sig=" + Long.toHexString(sig));
                raf.skipBytes(4);
                Entry e = new Entry();
                e.flags = readU16(raf);
                e.method = readU16(raf);
                e.time = readU16(raf);
                e.date = readU16(raf);
                e.crc = readU32(raf);
                e.csize = readU32(raf);
                e.usize = readU32(raf);
                int nameLen = readU16(raf);
                int extraLen = readU16(raf);
                int commentLen2 = readU16(raf);
                raf.skipBytes(4); // disk start + int attr
                raf.skipBytes(4); // ext attr
                e.localOff = readU32(raf);
                e.name = new byte[nameLen];
                raf.readFully(e.name);
                e.extra = new byte[extraLen];
                raf.readFully(e.extra);
                e.comment = new byte[commentLen2];
                raf.readFully(e.comment);
                if (e.csize == 0xFFFFFFFFL || e.usize == 0xFFFFFFFFL || e.localOff == 0xFFFFFFFFL) {
                    throw new IOException("zip64 entry not supported: " + new String(e.name, "UTF-8"));
                }
                list.add(e);
            }

            // ---- read local headers, locate data, then rewrite ----
            for (Entry e : list) {
                raf.seek(e.localOff);
                if (readU32(raf) != 0x04034b50L) throw new IOException("bad local header");
                raf.skipBytes(22);
                int ln = readU16(raf);
                int le = readU16(raf);
                e.dataOff = e.localOff + 30 + ln + le;
            }

            if (out.exists()) out.delete();
            CountingOutputStream cos = new CountingOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 16));
            OutputStream os = cos;
            try {
                for (Entry e : list) {
                    byte[] extra = e.extra;
                    if (e.method == 0) {
                        int pad = (int) ((align - ((cos.count() + 30 + e.name.length + extra.length) % align)) % align);
                        if (pad > 0) {
                            if (pad < 4) pad += align;
                            int n = pad - 4;
                            byte[] ne = new byte[extra.length + pad];
                            System.arraycopy(extra, 0, ne, 0, extra.length);
                            int o = extra.length;
                            ne[o] = (byte) 0x35; ne[o + 1] = (byte) 0xD9; // 0xD935 alignment id
                            ne[o + 2] = (byte) (n & 0xFF); ne[o + 3] = (byte) ((n >> 8) & 0xFF);
                            extra = ne;
                        }
                    }
                    e.newOffset = cos.count();
                    // local header (bit 3 cleared, sizes known)
                    writeU32(os, 0x04034b50L);
                    writeU16(os, 20);
                    writeU16(os, e.flags & ~8);
                    writeU16(os, e.method);
                    writeU16(os, e.time);
                    writeU16(os, e.date);
                    writeU32(os, e.crc);
                    writeU32(os, e.method == 0 ? e.csize : e.csize);
                    writeU32(os, e.method == 0 ? e.usize : e.usize);
                    writeU16(os, e.name.length);
                    writeU16(os, extra.length);
                    os.write(e.name);
                    os.write(extra);
                    // copy compressed data
                    raf.seek(e.dataOff);
                    long left = e.csize;
                    byte[] buf = new byte[1 << 16];
                    while (left > 0) {
                        int n = (int) Math.min(buf.length, left);
                        raf.readFully(buf, 0, n);
                        os.write(buf, 0, n);
                        left -= n;
                    }
                }
                long cdStart = cos.count();
                for (Entry e : list) {
                    writeU32(os, 0x02014b50L);
                    writeU16(os, 20); // made by
                    writeU16(os, 20); // needed
                    writeU16(os, e.flags & ~8);
                    writeU16(os, e.method);
                    writeU16(os, e.time);
                    writeU16(os, e.date);
                    writeU32(os, e.crc);
                    writeU32(os, e.csize);
                    writeU32(os, e.usize);
                    writeU16(os, e.name.length);
                    writeU16(os, e.extra.length);
                    writeU16(os, e.comment.length);
                    writeU16(os, 0); // disk
                    writeU16(os, 0); // int attr
                    writeU32(os, 0); // ext attr
                    writeU32(os, e.newOffset);
                    os.write(e.name);
                    os.write(e.extra);
                    os.write(e.comment);
                }
                long cdEnd = cos.count();
                writeU32(os, 0x06054b50L);
                writeU16(os, 0); writeU16(os, 0);
                writeU16(os, list.size()); writeU16(os, list.size());
                writeU32(os, cdEnd - cdStart); writeU32(os, cdStart);
                writeU16(os, eocdComment.length);
                os.write(eocdComment);
            } finally {
                os.close();
            }
        } finally {
            raf.close();
        }
    }

    static long[] findEocd(RandomAccessFile raf) throws IOException {
        long len = raf.length();
        long start = Math.max(0, len - 66560);
        byte[] buf = new byte[(int) (len - start)];
        raf.seek(start);
        raf.readFully(buf);
        for (int i = buf.length - 22; i >= 0; i--) {
            if (buf[i] == 0x50 && buf[i + 1] == 0x4b && buf[i + 2] == 0x05 && buf[i + 3] == 0x06) {
                int cl = (buf[i + 20] & 0xFF) | ((buf[i + 21] & 0xFF) << 8);
                if (i + 22 + cl == buf.length) return new long[]{start + i};
            }
        }
        return null;
    }

    static int readU16(RandomAccessFile raf) throws IOException {
        int a = raf.read(), b = raf.read();
        if (b < 0) throw new EOFException();
        return a | (b << 8);
    }

    static long readU32(RandomAccessFile raf) throws IOException {
        long a = readU16(raf), b = readU16(raf);
        return a | (b << 16);
    }

    static void writeU16(OutputStream os, int v) throws IOException {
        os.write(v & 0xFF); os.write((v >>> 8) & 0xFF);
    }

    static void writeU32(OutputStream os, long v) throws IOException {
        os.write((int) (v & 0xFF)); os.write((int) ((v >>> 8) & 0xFF));
        os.write((int) ((v >>> 16) & 0xFF)); os.write((int) ((v >>> 24) & 0xFF));
    }

    static final class CountingOutputStream extends FilterOutputStream {
        private long count;
        CountingOutputStream(OutputStream out) { super(out); }
        long count() { return count; }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }
}
