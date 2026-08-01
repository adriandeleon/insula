package com.insula.zim;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Thread-safe positional ("pread") little-endian reads over a {@link FileChannel}.
 * All multi-byte integers in the ZIM format are little-endian.
 */
final class LittleEndianFile implements AutoCloseable {

    private final FileChannel channel;
    private final long size;

    LittleEndianFile(Path file) throws IOException {
        this.channel = FileChannel.open(file, StandardOpenOption.READ);
        this.size = channel.size();
    }

    long size() {
        return size;
    }

    byte[] bytes(long pos, int len) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(len);
        while (buf.hasRemaining()) {
            int n = channel.read(buf, pos + buf.position());
            if (n < 0) {
                throw new EOFException("Unexpected EOF at " + (pos + buf.position()) + " (wanted " + len + " bytes)");
            }
        }
        return buf.array();
    }

    int u8(long pos) throws IOException {
        return bytes(pos, 1)[0] & 0xFF;
    }

    int u16(long pos) throws IOException {
        return buffer(pos, 2).getShort() & 0xFFFF;
    }

    long u32(long pos) throws IOException {
        return buffer(pos, 4).getInt() & 0xFFFFFFFFL;
    }

    long u64(long pos) throws IOException {
        return buffer(pos, 8).getLong();
    }

    private ByteBuffer buffer(long pos, int len) throws IOException {
        return ByteBuffer.wrap(bytes(pos, len)).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** A zero-terminated UTF-8 string plus the offset of the byte after its terminator. */
    record ZString(String value, long end) {}

    ZString zeroTerminated(long pos) throws IOException {
        var out = new java.io.ByteArrayOutputStream(32);
        long p = pos;
        while (true) {
            int chunkLen = (int) Math.min(256, size - p);
            if (chunkLen <= 0) {
                throw new EOFException("Unterminated string at " + pos);
            }
            byte[] chunk = bytes(p, chunkLen);
            for (int i = 0; i < chunk.length; i++) {
                if (chunk[i] == 0) {
                    out.write(chunk, 0, i);
                    return new ZString(out.toString(StandardCharsets.UTF_8), p + i + 1);
                }
            }
            out.write(chunk, 0, chunk.length);
            p += chunk.length;
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
