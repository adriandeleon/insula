package com.insula.app;

import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import io.nayuki.qrcodegen.QrCode;

/**
 * Renders a QR code (Nayuki's generator, MIT) into a JavaFX image. The quiet-zone border is part
 * of the image on purpose — scanners need it, and a caller placing the image against a dark
 * background would otherwise destroy scannability.
 */
final class QrImage {

    static final int DEFAULT_SCALE = 6;
    static final int DEFAULT_BORDER = 3;

    private QrImage() {}

    static WritableImage render(String text) {
        return render(text, DEFAULT_SCALE, DEFAULT_BORDER);
    }

    static WritableImage render(String text, int scale, int border) {
        QrCode qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int modules = qr.size + 2 * border;
        WritableImage image = new WritableImage(modules * scale, modules * scale);
        PixelWriter writer = image.getPixelWriter();
        for (int py = 0; py < modules * scale; py++) {
            int my = py / scale - border;
            for (int px = 0; px < modules * scale; px++) {
                int mx = px / scale - border;
                writer.setColor(px, py, qr.getModule(mx, my) ? Color.BLACK : Color.WHITE);
            }
        }
        return image;
    }
}
