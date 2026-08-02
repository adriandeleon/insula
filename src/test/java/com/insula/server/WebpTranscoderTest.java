package com.insula.server;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The WebP → displayable conversion. The fixtures are authored (a gradient and an alpha glyph),
 * not lifted from an archive, so they carry no licence question and exercise both WebP variants:
 * lossy VP8 without alpha and lossless VP8L with it.
 */
class WebpTranscoderTest {

    private static byte[] fixture(String name) throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/webp/" + name));
    }

    @Test
    void theFixturesAreRealWebpSoThisTestCannotPassVacuously() throws Exception {
        for (String name : new String[] {"gradient.webp", "glyph.webp"}) {
            byte[] bytes = fixture(name);
            assertEquals("RIFF", new String(bytes, 0, 4, StandardCharsets.US_ASCII), name);
            assertEquals("WEBP", new String(bytes, 8, 4, StandardCharsets.US_ASCII), name);
        }
    }

    @Test
    void aPhotoWithoutAlphaBecomesJpeg() throws Exception {
        WebpTranscoder.Transcoded result = WebpTranscoder.convert(fixture("gradient.webp"));
        assertEquals("image/jpeg", result.mimeType());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertNotNull(decoded, "the output must be readable by a standard decoder");
        assertEquals(64, decoded.getWidth());
        assertEquals(48, decoded.getHeight());
    }

    @Test
    void animageWithAlphaBecomesPngSoTransparencySurvives() throws Exception {
        // A signature or logo re-encoded as JPEG would gain a white box; that is the whole reason
        // the policy branches on alpha rather than always taking the smaller format.
        WebpTranscoder.Transcoded result = WebpTranscoder.convert(fixture("glyph.webp"));
        assertEquals("image/png", result.mimeType());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertNotNull(decoded);
        assertTrue(decoded.getColorModel().hasAlpha(), "alpha must survive the round trip");
        assertEquals(0, decoded.getRGB(0, 0) >>> 24, "the transparent corner is still transparent");
    }

    @Test
    void nonWebpBytesPassThroughUntouched() {
        WebpTranscoder transcoder = new WebpTranscoder();
        byte[] png = {1, 2, 3, 4};
        WebpTranscoder.Transcoded result = transcoder.transcode("k", png, "image/png");
        assertArrayEquals(png, result.bytes());
        assertEquals("image/png", result.mimeType());
        assertEquals(0, transcoder.cachedCountForTest(), "a pass-through must not occupy the cache");
    }

    @Test
    void undecodableBytesDegradeToTheOriginalRatherThanFailing() {
        // Serving the original reproduces today's behaviour (an empty box); throwing would turn a
        // cosmetic problem into a broken page.
        WebpTranscoder transcoder = new WebpTranscoder();
        byte[] junk = "RIFF____WEBPnonsense".getBytes(StandardCharsets.US_ASCII);
        WebpTranscoder.Transcoded result = transcoder.transcode("k", junk, "image/webp");
        assertArrayEquals(junk, result.bytes());
        assertEquals("image/webp", result.mimeType());
    }

    @Test
    void repeatedRequestsAreServedFromTheCache() throws Exception {
        WebpTranscoder transcoder = new WebpTranscoder();
        byte[] webp = fixture("gradient.webp");
        WebpTranscoder.Transcoded first = transcoder.transcode("a", webp, "image/webp");
        WebpTranscoder.Transcoded second = transcoder.transcode("a", webp, "image/webp");
        assertEquals(1, transcoder.cachedCountForTest());
        assertTrue(first == second, "the second hit must return the cached instance, not re-decode");
    }

    @Test
    void theCacheIsBoundedByBytesNotEntries() throws Exception {
        // Images vary hugely in size, so an entry count would let a handful of large photos pin
        // far more memory than intended — the same reason ClusterStore counts bytes.
        WebpTranscoder transcoder = new WebpTranscoder(4096);
        byte[] webp = fixture("gradient.webp");
        for (int i = 0; i < 40; i++) {
            transcoder.transcode("img-" + i, webp, "image/webp");
        }
        assertTrue(
                transcoder.cachedBytesForTest() <= 4096,
                "cache held " + transcoder.cachedBytesForTest() + " bytes over a 4096 budget");
        assertTrue(transcoder.cachedCountForTest() < 40, "eviction must actually have happened");
    }
}
