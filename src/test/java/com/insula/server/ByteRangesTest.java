package com.insula.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ByteRangesTest {

    private static ByteRanges.Request.Partial partial(String header, long total) {
        return assertInstanceOf(ByteRanges.Request.Partial.class, ByteRanges.parse(header, total));
    }

    @Test
    void anAbsentOrForeignHeaderMeansTheWholeBody() {
        assertInstanceOf(ByteRanges.Request.Full.class, ByteRanges.parse(null, 100));
        assertInstanceOf(ByteRanges.Request.Full.class, ByteRanges.parse("items=0-10", 100));
        assertInstanceOf(ByteRanges.Request.Full.class, ByteRanges.parse("bytes=", 100));
        assertInstanceOf(ByteRanges.Request.Full.class, ByteRanges.parse("bytes=abc-def", 100));
    }

    @Test
    void closedRangesAreClampedToTheContent() {
        ByteRanges.Request.Partial mid = partial("bytes=10-19", 100);
        assertEquals(10, mid.start());
        assertEquals(19, mid.endInclusive());
        assertEquals(10, mid.length());

        // A player commonly asks for more than exists at the tail; that is not an error.
        ByteRanges.Request.Partial past = partial("bytes=90-999", 100);
        assertEquals(90, past.start());
        assertEquals(99, past.endInclusive());
        assertEquals(10, past.length());
    }

    @Test
    void anOpenEndedRangeRunsToTheEnd() {
        ByteRanges.Request.Partial open = partial("bytes=50-", 100);
        assertEquals(50, open.start());
        assertEquals(99, open.endInclusive());

        // "bytes=0-" is what a browser opens a video with; it must be a 206, not a 200, or the
        // player never learns that seeking is available.
        ByteRanges.Request.Partial whole = partial("bytes=0-", 100);
        assertEquals(0, whole.start());
        assertEquals(99, whole.endInclusive());
    }

    @Test
    void aSuffixRangeCountsBackFromTheEnd() {
        // MP4 players read the tail first to find the moov atom, so this path is load-bearing.
        ByteRanges.Request.Partial tail = partial("bytes=-20", 100);
        assertEquals(80, tail.start());
        assertEquals(99, tail.endInclusive());

        // A suffix longer than the file is the whole file, not an error.
        ByteRanges.Request.Partial all = partial("bytes=-500", 100);
        assertEquals(0, all.start());
        assertEquals(99, all.endInclusive());
    }

    @Test
    void aRangeBeyondTheEndIsRefusedRatherThanQuietlyServedFromZero() {
        // Serving byte 0 for a seek past the end would look to the player like a successful seek
        // to the wrong place — worse than an honest 416.
        assertInstanceOf(ByteRanges.Request.Unsatisfiable.class, ByteRanges.parse("bytes=100-200", 100));
        assertInstanceOf(ByteRanges.Request.Unsatisfiable.class, ByteRanges.parse("bytes=-0", 100));
        assertInstanceOf(ByteRanges.Request.Unsatisfiable.class, ByteRanges.parse("bytes=0-", 0));
    }

    @Test
    void multiRangeFallsBackToTheWholeBody() {
        // Legal per the spec, and no media player asks for it — so multipart/byteranges is code
        // we would carry for nobody.
        assertInstanceOf(ByteRanges.Request.Full.class, ByteRanges.parse("bytes=0-9,20-29", 100));
    }

    @Test
    void reversedRangesAreIgnoredNotRefused() {
        assertInstanceOf(ByteRanges.Request.Full.class, ByteRanges.parse("bytes=50-10", 100));
    }

    @Test
    void headerCasingAndWhitespaceAreTolerated() {
        ByteRanges.Request.Partial p = partial("  BYTES= 10 - 19 ", 100);
        assertEquals(10, p.start());
        assertEquals(19, p.endInclusive());
    }

    @Test
    void headersReadBackInTheFormClientsExpect() {
        assertEquals("bytes 10-19/100", ByteRanges.contentRange(new ByteRanges.Request.Partial(10, 19), 100));
        assertEquals("bytes */100", ByteRanges.unsatisfiedRange(100));
    }
}
