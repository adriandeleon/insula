package com.insula.app;

import javafx.scene.image.Image;
import javafx.stage.Stage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window icon is loaded from module resources, and AppIcon deliberately swallows a miss so a
 * bad path can never stop the window opening — which means a typo would ship as a blank icon and
 * no failure anywhere. This is the thing that notices.
 */
class AppIconFxTest {

    @Test
    void everyDeclaredSizeLoads() {
        FxTestSupport.runOnFx(() -> {
            Stage stage = new Stage();
            AppIcon.applyTo(stage);
            assertEquals(7, stage.getIcons().size(), "one image per declared size");
            for (Image icon : stage.getIcons()) {
                assertFalse(icon.isError(), "icon failed to decode: " + icon.getException());
                assertTrue(icon.getWidth() > 0 && icon.getWidth() == icon.getHeight(), "square");
            }
        });
    }

    @Test
    void theSizesAreTheOnesAsked() {
        FxTestSupport.runOnFx(() -> {
            Stage stage = new Stage();
            AppIcon.applyTo(stage);
            int[] widths = stage.getIcons().stream()
                    .mapToInt(i -> (int) i.getWidth())
                    .sorted()
                    .toArray();
            assertEquals("[16, 24, 32, 48, 64, 128, 256]", java.util.Arrays.toString(widths));
        });
    }
}
