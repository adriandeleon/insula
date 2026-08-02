package com.insula.server;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Converts WebP images to something JavaFX's WebView can actually display.
 *
 * <p><b>Why this exists.</b> Every modern Kiwix ZIM stores its images as WebP — mwoffliner
 * recompresses them and keeps the original {@code .jpg}/{@code .png} file name, so the archive
 * says {@code Walt_Disney_1946.JPG} while the bytes are {@code RIFF....WEBP} (measured on a 2021
 * Bitcoin archive: 502 WebP against 1 PNG). JavaFX's WebView cannot decode WebP: the image loads
 * "successfully" and reports {@code naturalWidth == 0}, which paints as an empty box rather than
 * a broken-image icon — the whole infobox photo silently missing, with nothing in any log.
 *
 * <p>Decoding is TwelveMonkeys' pure-Java WebP reader (no native library), and the re-encode picks
 * by content: <b>an image with alpha becomes PNG</b> (a signature or logo whose transparency must
 * survive), <b>anything else becomes JPEG</b>. That split is measured, not aesthetic — across six
 * photos from a real archive, PNG came out 8.0× the original WebP bytes and JPEG 1.8×, and these
 * bytes cross a socket into the same process.
 *
 * <p>Failure is non-destructive: an image this cannot decode is served unchanged, so a format we
 * mishandle degrades to exactly today's behaviour rather than to an error page.
 *
 * <p>{@link LanServer} deliberately does <em>not</em> use this — its clients are real browsers,
 * which have supported WebP for years and are better served the smaller original.
 */
final class WebpTranscoder {

    private static final Logger LOG = Logger.getLogger(WebpTranscoder.class.getName());

    static final String WEBP_MIME = "image/webp";

    /** Quality for the photo path. 0.85 is visually transparent at ~1.8× the WebP size. */
    private static final float JPEG_QUALITY = 0.85f;

    /** Transcoded images are held so revisits and back/forward do not re-decode. */
    static final long MAX_CACHE_BYTES = 32L * 1024 * 1024;

    /** Beyond this an image is served as-is: a decode would cost more memory than it is worth. */
    private static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;

    /** The re-encoded bytes and the MIME type they should now be served under. */
    record Transcoded(byte[] bytes, String mimeType) {}

    /** Access-ordered; eviction is driven by {@link #cachedBytes}. */
    private final Map<String, Transcoded> cache = new LinkedHashMap<>(16, 0.75f, true);

    private long cachedBytes;
    private final long maxCacheBytes;

    WebpTranscoder() {
        this(MAX_CACHE_BYTES);
    }

    /** Test seam: a small budget makes eviction observable without a large archive. */
    WebpTranscoder(long maxCacheBytes) {
        this.maxCacheBytes = maxCacheBytes;
        // ImageIO's disk cache would spill temp files for every image we touch; we are already
        // holding the bytes in memory and bounding them ourselves.
        ImageIO.setUseCache(false);
    }

    static boolean isWebp(String mimeType) {
        return WEBP_MIME.equalsIgnoreCase(mimeType);
    }

    /**
     * The displayable form of a WebP blob, cached by {@code key}. Returns the input unchanged when
     * it is not WebP, is too large, or cannot be decoded.
     */
    synchronized Transcoded transcode(String key, byte[] data, String mimeType) {
        if (!isWebp(mimeType) || data.length > MAX_SOURCE_BYTES) {
            return new Transcoded(data, mimeType);
        }
        Transcoded hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        Transcoded result;
        try {
            result = convert(data);
        } catch (IOException | RuntimeException e) {
            // Never fail the request over this: the original bytes are what we have today.
            LOG.log(Level.FINE, "WebP transcode failed for " + key, e);
            return new Transcoded(data, mimeType);
        }
        admit(key, result);
        return result;
    }

    /** Decode + re-encode, with no caching. Package-visible so tests can drive it directly. */
    static Transcoded convert(byte[] webp) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(webp));
        if (image == null) {
            throw new IOException("no ImageIO reader accepted the bytes");
        }
        return image.getColorModel().hasAlpha()
                ? new Transcoded(encodePng(image), "image/png")
                : new Transcoded(encodeJpeg(image), "image/jpeg");
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("no PNG writer");
        }
        return out.toByteArray();
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        BufferedImage rgb = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB && image.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            // The JPEG writer refuses several of the types the WebP reader produces.
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(image, 0, 0, Color.WHITE, null);
            g.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(JPEG_QUALITY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(rgb, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private void admit(String key, Transcoded value) {
        if (value.bytes().length > maxCacheBytes) {
            return; // one oversized image must not evict everything else
        }
        cache.put(key, value);
        cachedBytes += value.bytes().length;
        var it = cache.entrySet().iterator();
        while (cachedBytes > maxCacheBytes && it.hasNext()) {
            var eldest = it.next();
            if (eldest.getKey().equals(key)) {
                continue; // never evict what we were just asked for
            }
            cachedBytes -= eldest.getValue().bytes().length;
            it.remove();
        }
    }

    synchronized long cachedBytesForTest() {
        return cachedBytes;
    }

    synchronized int cachedCountForTest() {
        return cache.size();
    }
}
