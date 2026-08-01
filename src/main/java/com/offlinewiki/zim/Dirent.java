package com.offlinewiki.zim;

import java.io.IOException;

/**
 * One directory entry. Layout at its offset: u16 mimetype ({@code 0xFFFF} = redirect,
 * {@code 0xFFFE}/{@code 0xFFFD} = legacy link-target/deleted), u8 parameter length,
 * u8 namespace char, u32 revision, then {@code u32 cluster + u32 blob} for content or
 * {@code u32 redirectIndex} for redirects, then path{@code \0}title{@code \0}
 * (empty title means the path is the title).
 */
public record Dirent(
        long index,
        int mimeType,
        char namespace,
        long clusterNumber,
        long blobNumber,
        long redirectIndex,
        String path,
        String title) {

    static Dirent parse(LittleEndianFile in, long index, long offset) throws IOException {
        int mimeType = in.u16(offset);
        char namespace = (char) in.u8(offset + 3);
        long pos = offset + 8; // past mimetype, paramLen, namespace, revision
        long cluster = -1;
        long blob = -1;
        long redirect = -1;
        if (mimeType == MimeList.REDIRECT) {
            redirect = in.u32(pos);
            pos += 4;
        } else if (mimeType == MimeList.LINK_TARGET || mimeType == MimeList.DELETED) {
            // legacy entries carry no content pointer
        } else {
            cluster = in.u32(pos);
            blob = in.u32(pos + 4);
            pos += 8;
        }
        LittleEndianFile.ZString path = in.zeroTerminated(pos);
        LittleEndianFile.ZString title = in.zeroTerminated(path.end());
        String effectiveTitle = title.value().isEmpty() ? path.value() : title.value();
        return new Dirent(index, mimeType, namespace, cluster, blob, redirect, path.value(), effectiveTitle);
    }

    public boolean isRedirect() {
        return mimeType == MimeList.REDIRECT;
    }

    public boolean hasContent() {
        return mimeType < MimeList.DELETED;
    }

    /** The full addressable path, e.g. {@code "C/Foo"} or {@code "A/Foo.html"}. */
    public String fullPath() {
        return namespace + "/" + path;
    }
}
