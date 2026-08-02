package com.insula.app;

import java.io.InputStream;

import javafx.scene.text.Font;

/**
 * Loads the bundled Inter faces before any scene is built, so "Inter" in the stylesheet resolves
 * on every OS instead of silently falling back to whatever the platform calls its default.
 * Inter is the kit's one UI family (OFL-1.1, attributed in NOTICE); article text keeps the
 * serif stack inside WebView and is unaffected.
 */
final class Fonts {

    private static final String[] FACES = {
        "Inter-Regular.ttf", "Inter-Medium.ttf", "Inter-SemiBold.ttf", "Inter-Bold.ttf", "Inter-Italic.ttf"
    };

    private Fonts() {}

    static void load() {
        for (String face : FACES) {
            try (InputStream in = Fonts.class.getResourceAsStream("/com/insula/fonts/inter/" + face)) {
                if (in != null) {
                    Font.loadFont(in, 14);
                }
            } catch (Exception e) {
                // A failed font load falls back to the system family; never fatal.
            }
        }
    }
}
