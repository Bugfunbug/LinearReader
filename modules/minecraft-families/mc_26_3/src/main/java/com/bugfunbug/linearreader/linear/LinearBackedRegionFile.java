package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearStats;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import sun.misc.Unsafe;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * 1.21.5+ variant of LinearBackedRegionFile. The only material change from the
 * older shared implementation is the RegionFile constructor signature required
 * by the compiler; runtime instances are still allocated with Unsafe.
 */
public final class LinearBackedRegionFile extends RegionFile {
    private static final int STREAM_COPY_BUFFER_SIZE = 8192;
    private static final ThreadLocal<byte[]> TL_STREAM_COPY_BUFFER =
            ThreadLocal.withInitial(() -> new byte[STREAM_COPY_BUFFER_SIZE]);

    private static final Unsafe UNSAFE;
    private static final long INFO_OFFSET;
    private static final long VERSION_OFFSET;
    private static final RegionStorageInfo LINEAR_STORAGE_INFO =
            new RegionStorageInfo("linearreader", Level.OVERWORLD, "region");

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
            INFO_OFFSET = objectFieldOffsetByType(RegionStorageInfo.class);
            VERSION_OFFSET = objectFieldOffsetByType(RegionFileVersion.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static long objectFieldOffsetByType(Class<?> fieldType) throws NoSuchFieldException {
        for (Field field : RegionFile.class.getDeclaredFields()) {
            if (field.getType() == fieldType) {
                return UNSAFE.objectFieldOffset(field);
            }
        }
        throw new NoSuchFieldException(fieldType.getName());
    }

    private LinearRegionFile linear;

    @SuppressWarnings("DataFlowIssue")
    private LinearBackedRegionFile() throws IOException {
        super(LINEAR_STORAGE_INFO, null, null, false);
    }

    public static LinearBackedRegionFile create(LinearRegionFile linear) throws IOException {
        try {
            LinearBackedRegionFile inst =
                    (LinearBackedRegionFile) UNSAFE.allocateInstance(LinearBackedRegionFile.class);
            inst.linear = linear;
            UNSAFE.putObject(inst, INFO_OFFSET, LINEAR_STORAGE_INFO);
            UNSAFE.putObject(inst, VERSION_OFFSET, RegionFileVersion.getSelected());
            return inst;
        } catch (InstantiationException e) {
            throw new IOException("[LinearReader] Cannot allocate LinearBackedRegionFile", e);
        }
    }

    @Override
    public synchronized DataInputStream getChunkDataInputStream(ChunkPos pos) throws IOException {
        return linear.read(pos);
    }

    @Override
    public DataOutputStream getChunkDataOutputStream(ChunkPos pos) throws IOException {
        return linear.write(pos);
    }

    @Override
    public boolean hasChunk(ChunkPos pos) {
        return linear.hasChunk(pos);
    }

    @Override
    public synchronized void close() {
    }

    public void clearChunk(ChunkPos pos) {
        linear.clearChunk(pos);
    }

    public void writeFromBuffer(ChunkPos pos, ByteBuffer buf) throws IOException {
        IdleRecompressor.notifyIO();

        ByteBuffer chunkBuf = buf.duplicate();
        if (chunkBuf.remaining() < 5) {
            throw new IOException("[LinearReader] Chunk buffer too short for " + pos
                    + ": " + chunkBuf.remaining() + " byte(s)");
        }

        int header = chunkBuf.getInt();
        if (header <= 0) {
            throw new IOException("[LinearReader] Invalid chunk header length for " + pos + ": " + header);
        }

        int compressionType = chunkBuf.get() & 0xFF;
        int compressedLen = header - 1;
        if (compressedLen > chunkBuf.remaining()) {
            throw new IOException("[LinearReader] Chunk buffer truncated for " + pos
                    + ": expected " + compressedLen + " compressed byte(s), found "
                    + chunkBuf.remaining());
        }

        boolean statsEnabled = LinearStats.isEnabled();
        long startedNs = statsEnabled ? System.nanoTime() : 0L;
        try (OutputStream out = linear.openChunkBytesOutputStream(pos, compressedLen);
             InputStream in = openChunkDataStream(compressionType, chunkBuf.slice(), compressedLen)) {
            copyStream(in, out);
        }
        if (statsEnabled) {
            LinearStats.recordChunkWrite(System.nanoTime() - startedNs);
        }
    }

    private static InputStream openChunkDataStream(int type, ByteBuffer chunkBuf, int len) throws IOException {
        InputStream raw = new ByteBufferInputStream(chunkBuf, len);
        switch (type) {
            case 1:
                return new GZIPInputStream(raw);
            case 2:
                return new InflaterInputStream(raw);
            case 3:
                return raw;
            default:
                raw.close();
                throw new IOException(
                        "[LinearReader] Unsupported MC compression type in c2me write path: " + type
                                + " — please report this.");
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] copyBuffer = TL_STREAM_COPY_BUFFER.get();
        int n;
        while ((n = in.read(copyBuffer)) >= 0) {
            if (n == 0) {
                continue;
            }
            out.write(copyBuffer, 0, n);
        }
    }

    private static final class ByteBufferInputStream extends InputStream {
        private final ByteBuffer buffer;

        private ByteBufferInputStream(ByteBuffer source, int len) {
            ByteBuffer view = source.duplicate();
            int limit = Math.min(view.position() + len, view.limit());
            view.limit(limit);
            this.buffer = view.slice();
        }

        @Override
        public int read() {
            if (!buffer.hasRemaining()) {
                return -1;
            }
            return buffer.get() & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (!buffer.hasRemaining()) {
                return -1;
            }
            int toRead = Math.min(len, buffer.remaining());
            buffer.get(b, off, toRead);
            return toRead;
        }
    }
}
