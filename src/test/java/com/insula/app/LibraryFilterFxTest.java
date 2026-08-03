package com.insula.app;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.Scene;
import javafx.scene.control.TextField;

import com.insula.download.DownloadManager;
import com.insula.download.HttpMultiSourceTransport;
import com.insula.download.TransportSelector;
import com.insula.library.Library;
import com.insula.library.LibraryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shelf filter, against a real pane. */
class LibraryFilterFxTest {

    private static Library libraryWith(Path dir, String... files) throws Exception {
        Library library = Library.load(dir.resolve("library.properties"));
        for (String name : files) {
            Path f = Files.createFile(dir.resolve(name));
            library.put(new LibraryEntry(f, name.replace(".zim", ""), 10, "", true, 1));
        }
        return library;
    }

    private static LibraryPane paneFor(Path dir, Library library) {
        DownloadManager downloads =
                new DownloadManager(new TransportSelector(new HttpMultiSourceTransport()), library, dir);
        LibraryPane pane =
                new LibraryPane(downloads, library, p -> {}, s -> {}, () -> new LibraryPane.DiskInfo(0, 0, 0, 0));
        new Scene(pane.node(), 900, 700);
        pane.activate();
        pane.node().applyCss();
        pane.node().layout();
        return pane;
    }

    private static TextField filterOf(LibraryPane pane) {
        return (TextField) pane.node().lookupAll(".text-field").stream()
                .filter(n -> n instanceof TextField t && "Filter archives".equals(t.getPromptText()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void typingNarrowsTheShelf(@TempDir Path dir) throws Exception {
        Library library = libraryWith(dir, "mdwiki_en_all.zim", "ted_mul_tech.zim", "wikipedia_en.zim");
        FxTestSupport.runOnFx(() -> {
            LibraryPane pane = paneFor(dir, library);
            assertEquals(3, pane.deviceRowsForTest());
            filterOf(pane).setText("ted");
            assertEquals(1, pane.deviceRowsForTest(), "only the matching archive is shown");
            filterOf(pane).setText("");
            assertEquals(3, pane.deviceRowsForTest(), "clearing brings them all back");
        });
    }

    @Test
    void aFilterMatchingNothingStillLeavesTheFieldOnScreen(@TempDir Path dir) throws Exception {
        // Otherwise the control that produced the empty shelf disappears with it, and there is no
        // way back to the archives.
        Library library = libraryWith(dir, "mdwiki_en_all.zim");
        FxTestSupport.runOnFx(() -> {
            LibraryPane pane = paneFor(dir, library);
            filterOf(pane).setText("nothing matches this");
            assertEquals(0, pane.deviceRowsForTest());
            assertTrue(filterOf(pane).isVisible());
        });
    }
}
