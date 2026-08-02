package com.insula.server;

import java.util.Locale;

/**
 * Parses an HTTP {@code Range} header (RFC 9110 §14) against a known content length.
 *
 * <p>This is what makes seeking work. Without it a player asking for the middle of a video is
 * handed the whole file from byte zero and has to read forward to find its frame; a 25-minute
 * talk is unusable that way. It matters for both servers: the loopback one feeds external
 * players, and the LAN one feeds phones.
 *
 * <p>Deliberately narrow. A <b>single</b> range is honoured; a multi-range request is answered
 * with the full body, which the spec expressly allows ("a server MAY ignore the Range header")
 * and which no media player needs — implementing {@code multipart/byteranges} would be code
 * carried for nobody. A malformed or non-{@code bytes} header is likewise treated as absent
 * rather than rejected, because a broken header should not make a file unreachable.
 */
public final class ByteRanges {

    private ByteRanges() {}

    /** What the caller should do with the request. */
    public sealed interface Request {

        /** No usable range: send the whole body with 200. */
        record Full() implements Request {}

        /** Send bytes {@code [start, endInclusive]} with 206 and a {@code Content-Range}. */
        record Partial(long start, long endInclusive) implements Request {
            public long length() {
                return endInclusive - start + 1;
            }
        }

        /** The range lies outside the content: answer 416, reporting only the true length. */
        record Unsatisfiable() implements Request {}
    }

    public static final Request FULL = new Request.Full();

    /**
     * @param header the raw {@code Range} header value, or null when absent
     * @param totalLength the length of the body that would be sent for a 200
     */
    public static Request parse(String header, long totalLength) {
        if (header == null || totalLength < 0) {
            return FULL;
        }
        String value = header.strip().toLowerCase(Locale.ROOT);
        if (!value.startsWith("bytes=")) {
            return FULL; // an unknown unit is not an error; it just is not a range we can serve
        }
        String spec = value.substring("bytes=".length()).strip();
        if (spec.isEmpty() || spec.indexOf(',') >= 0) {
            return FULL; // multi-range: legal to ignore, and no player asks for it
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return FULL;
        }

        String fromText = spec.substring(0, dash).strip();
        String toText = spec.substring(dash + 1).strip();
        try {
            if (fromText.isEmpty()) {
                // "bytes=-N": the final N bytes. N == 0 asks for nothing, which is unsatisfiable.
                if (toText.isEmpty()) {
                    return FULL;
                }
                long suffix = Long.parseLong(toText);
                if (suffix <= 0) {
                    return new Request.Unsatisfiable();
                }
                if (totalLength == 0) {
                    return new Request.Unsatisfiable();
                }
                long start = Math.max(0, totalLength - suffix);
                return new Request.Partial(start, totalLength - 1);
            }

            long start = Long.parseLong(fromText);
            if (start < 0 || start >= totalLength) {
                // Past the end is the one case a client must be told about rather than quietly
                // given the whole file: it would otherwise read the start as the seek target.
                return new Request.Unsatisfiable();
            }
            long end = toText.isEmpty() ? totalLength - 1 : Long.parseLong(toText);
            if (end < start) {
                return FULL;
            }
            return new Request.Partial(start, Math.min(end, totalLength - 1));
        } catch (NumberFormatException e) {
            return FULL;
        }
    }

    /** The {@code Content-Range} value for a 206 response. */
    public static String contentRange(Request.Partial partial, long totalLength) {
        return "bytes " + partial.start() + "-" + partial.endInclusive() + "/" + totalLength;
    }

    /** The {@code Content-Range} value for a 416 response, which reports only the true length. */
    public static String unsatisfiedRange(long totalLength) {
        return "bytes */" + totalLength;
    }
}
