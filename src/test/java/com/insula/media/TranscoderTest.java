package com.insula.media;

import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure half of in-app video: the duration probe and progress parsing. */
class TranscoderTest {

    @Test
    void durationIsReadFromFfprobesBareOutput() {
        assertEquals(1533.616, Transcoder.parseDurationSeconds("1533.616000\n").getAsDouble(), 0.001);
        assertEquals(OptionalDouble.empty(), Transcoder.parseDurationSeconds("N/A"));
        assertEquals(OptionalDouble.empty(), Transcoder.parseDurationSeconds(""));
        assertEquals(OptionalDouble.empty(), Transcoder.parseDurationSeconds(null));
        // A live stream reports 0, which is not a usable total.
        assertEquals(OptionalDouble.empty(), Transcoder.parseDurationSeconds("0.000000"));
    }

    @Test
    void progressLinesYieldSecondsEncoded() {
        assertEquals(
                12.345678,
                Transcoder.parseProgressSeconds("out_time_us=12345678").getAsDouble(),
                1e-6);
        assertEquals(OptionalDouble.empty(), Transcoder.parseProgressSeconds("frame=42"));
        assertEquals(OptionalDouble.empty(), Transcoder.parseProgressSeconds("out_time_us=N/A"));
        // ffmpeg emits a negative value before the first frame lands.
        assertEquals(OptionalDouble.empty(), Transcoder.parseProgressSeconds("out_time_us=-1"));
        assertTrue(Transcoder.isProgressEnd("progress=end"));
        assertFalse(Transcoder.isProgressEnd("progress=continue"));
    }

    @Test
    void percentIsClampedAndSafeWithoutADuration() {
        assertEquals(50, Transcoder.percent(50, 100));
        assertEquals(100, Transcoder.percent(150, 100), "encoding past the probed duration still reads as done");
        assertEquals(0, Transcoder.percent(-5, 100));
        assertEquals(0, Transcoder.percent(10, 0), "an unknown duration must not divide by zero");
    }
}
