package com.insula.app;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import com.insula.config.Settings;
import com.insula.library.LibraryEntry;
import com.insula.library.Shelf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Renders the shelf at an ultrawide size and writes a PNG, so a layout change can be <em>looked
 * at</em> rather than inferred from assertions.
 *
 * <p>Skipped unless {@code -Dinsula.shot=<path>} is given, so it costs a normal run nothing. It
 * earns its place: the first version of the column layout passed every assertion — no row lost,
 * order preserved, handles present — and still looked wrong, because what was wrong was the empty
 * half-width column beside every one-archive group. No assertion was going to say that.
 *
 * <pre>./mvnw test -Dtest=ShelfShotFxTest -Dinsula.shot=/tmp/shelf.png</pre>
 */
class ShelfShotFxTest {

    private static final long HOUR = 3_600_000L;

    @Test
    void renderWideShelf(@TempDir Path dir) throws Exception {
        String out = System.getProperty("insula.shot");
        org.junit.jupiter.api.Assumptions.assumeTrue(out != null, "only runs when -Dinsula.shot is given");

        FxTestSupport.runOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController controller = new ReaderController(stage, null, settings, dir);
            Scene scene = new Scene(controller.root(), 1970, 1000);
            // The same tokens the app attaches, or the shot shows unstyled boxes and tells you
            // nothing about how the layout actually reads.
            scene.getStylesheets()
                    .add(ReaderController.class.getResource("insula.css").toExternalForm());
            atlantafx.base.theme.Theme theme = new atlantafx.base.theme.PrimerLight();
            javafx.application.Application.setUserAgentStylesheet(theme.getUserAgentStylesheet());
            stage.setScene(scene);

            var library = controller.libraryForTest();
            String[][] seed = {
                {"wikipedia_en_computer_maxi_2026-06.zim", "Computer by Wikipedia", "909000000"},
                {"alpinelinux_en_all_maxi_2026-07.zim", "Alpine Linux Wiki", "3000000"},
                {"bitcoin_en_all_maxi_2021-03.zim", "Bitcoin Wiki", "17000000"},
                {"gutenberg_en_lcc-t_2026-03.zim", "Project Gutenberg Library", "12300000000"},
                {"gutenberg_en_lcc-j_2026-03.zim", "Project Gutenberg Library", "435000000"},
                {"gutenberg_en_lcc-k_2026-03.zim", "Project Gutenberg Library", "235000000"},
                {"ted_mul_tech_2025-10.zim", "TED tech", "113000000"},
                {"wikipedia_en_comics_maxi_2026-07.zim", "Comics by Wikipedia", "630000000"},
                {"wikipedia_en_ray-charles_maxi_2026-08.zim", "Ray Charles", "2900000"},
                {"wikipedia_en_knots_maxi_2026-07.zim", "Knots by Wikipedia", "17600000"},
                {"wikipedia_en_100_2026-07.zim", "Wikipedia 100", "317000000"},
                {"wikipedia_en_maths_maxi_2026-06.zim", "Mathematics by Wikipedia", "1200000000"},
                {"wikivoyage_en_all_maxi_2026-06.zim", "Wikivoyage", "1100000000"},
                {"getbootstrap.com_en_all_2026-06.zim", "Bootstrap", "46900000"},
                {"ifixit_en_all_2025-12.zim", "iFixit", "3570000000"},
                {"installgentoo_en_all_maxi_2019-09.zim", "InstallGentoo Wiki", "9400000"},
                {"openstreetmap-wiki_en_all_maxi_2026-07.zim", "OpenStreetMap Wiki", "1400000000"},
                {"theworldfactbook_en_all_2026-02.zim", "The World Factbook", "600000000"},
            };
            for (int i = 0; i < seed.length; i++) {
                library.put(new LibraryEntry(
                        dir.resolve(seed[i][0]),
                        seed[i][1],
                        Long.parseLong(seed[i][2]),
                        "h",
                        true,
                        (seed.length - i) * HOUR,
                        i == 0,
                        i,
                        ""));
            }
            controller.commandsForTest().run("library.open");
            LibraryPane pane = controller.libraryPaneForTest();
            pane.setArrangement(Shelf.GroupBy.THEME, Shelf.SortBy.CUSTOM);
            controller.root().applyCss();
            controller.root().layout();
            pane.setShelfWidthForTest(1970 - 28);
            controller.root().applyCss();
            controller.root().layout();

            WritableImage image = scene.snapshot(null);
            try {
                javax.imageio.ImageIO.write(
                        toBuffered(image),
                        "png",
                        Files.createDirectories(Path.of(out).getParent())
                                .resolve(Path.of(out).getFileName())
                                .toFile());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            controller.dispose();
        });
    }

    private static java.awt.image.BufferedImage toBuffered(WritableImage image) {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        java.awt.image.BufferedImage out =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var reader = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        return out;
    }
}
