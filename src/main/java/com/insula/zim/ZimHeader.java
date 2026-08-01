package com.insula.zim;

import java.io.IOException;

/**
 * The fixed 80-byte ZIM file header (all integers little-endian).
 *
 * <p>Layout: magic u32, majorVersion u16, minorVersion u16, uuid 16 bytes, entryCount u32,
 * clusterCount u32, pathPtrPos u64, titlePtrPos u64, clusterPtrPos u64, mimeListPos u64,
 * mainPage u32, layoutPage u32, checksumPos u64.
 */
public record ZimHeader(
        int majorVersion,
        int minorVersion,
        byte[] uuid,
        long entryCount,
        long clusterCount,
        long pathPtrPos,
        long titlePtrPos,
        long clusterPtrPos,
        long mimeListPos,
        long mainPage,
        long layoutPage,
        long checksumPos) {

    static final int MAGIC = 0x44D495A;
    static final long NO_PAGE = 0xFFFFFFFFL;

    static ZimHeader parse(LittleEndianFile in) throws IOException {
        long magic = in.u32(0);
        if (magic != MAGIC) {
            throw new ZimFormatException("Not a ZIM file (magic 0x" + Long.toHexString(magic) + ")");
        }
        int major = in.u16(4);
        if (major != 5 && major != 6) {
            throw new ZimFormatException("Unsupported ZIM major version " + major);
        }
        return new ZimHeader(
                major,
                in.u16(6),
                in.bytes(8, 16),
                in.u32(24),
                in.u32(28),
                in.u64(32),
                in.u64(40),
                in.u64(48),
                in.u64(56),
                in.u32(64),
                in.u32(68),
                in.u64(72));
    }

    /** Minor version 1+ switched to the C/M/W/X namespace scheme (older files use A/I/M/-). */
    public boolean newNamespaceScheme() {
        return minorVersion >= 1;
    }

    public boolean hasMainPage() {
        return mainPage != NO_PAGE;
    }
}
