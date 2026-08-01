package com.offlinewiki.download;

import java.nio.file.Path;

/**
 * How a transfer ended. {@code file} is the (possibly partial) destination — a failed download
 * keeps its bytes so a resume or a re-verify can use them rather than starting from zero.
 */
public record DownloadResult(DownloadState state, Path file, long bytesWritten, String message, Throwable error) {

    public static DownloadResult success(Path file, long bytes) {
        return new DownloadResult(DownloadState.COMPLETED, file, bytes, "", null);
    }

    public static DownloadResult failure(Path file, long bytes, String message, Throwable error) {
        return new DownloadResult(DownloadState.FAILED, file, bytes, message, error);
    }

    public static DownloadResult cancelled(Path file, long bytes) {
        return new DownloadResult(DownloadState.CANCELLED, file, bytes, "Cancelled", null);
    }

    public boolean ok() {
        return state == DownloadState.COMPLETED;
    }
}
