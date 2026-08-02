package com.insula.app;

import javafx.scene.control.Label;

import com.insula.download.DownloadState;
import com.insula.download.ProgressSnapshot;

/**
 * The state vocabulary — one pill meaning one thing on every surface.
 *
 * <p>The design kit is explicit that a Catalog card, a Library row and the Home strip must never
 * tell different stories about the same archive, so the mapping from pipeline state to words and
 * colour lives here rather than being re-decided at each call site. Two rules come from the kit
 * and are easy to lose by accident:
 *
 * <ul>
 *   <li><b>Verifying is never folded into Downloading.</b> Integrity is the product's promise, so
 *       it gets its own visible amber state rather than hiding inside a progress bar.
 *   <li><b>A quarantined file advertises the cost of the repair, not the size of the loss</b> —
 *       "Repair · 12 MB", because the file was kept and only the bad pieces come back.
 * </ul>
 */
final class Pills {

    /** The kit's five tones. */
    enum Tone {
        ACCENT("pill-accent"),
        AMBER("pill-amber"),
        CORAL("pill-coral"),
        OK("pill-ok"),
        NEUTRAL("pill-neutral");

        private final String styleClass;

        Tone(String styleClass) {
            this.styleClass = styleClass;
        }

        String styleClass() {
            return styleClass;
        }
    }

    private Pills() {}

    static Label of(String text, Tone tone) {
        Label pill = new Label(text);
        pill.getStyleClass().addAll("pill", tone.styleClass());
        return pill;
    }

    static Label verified() {
        return of("✓ In library", Tone.OK);
    }

    static Label verifying(int percent) {
        return of("Verifying · SHA-256 · " + percent + "%", Tone.AMBER);
    }

    /** The repair's price, per the kit — never the file's size. */
    static Label repair(String repairSize) {
        return of("Repair · " + repairSize, Tone.CORAL);
    }

    /** Accepting an update is a bandwidth decision, so the pill names the build and the price. */
    static Label update(String build, String size) {
        return of("Update · " + build + " · " + size, Tone.ACCENT);
    }

    static Label transport(String name, int sources, String sourceNoun) {
        return of(sources > 0 ? name + " · " + sources + " " + sourceNoun : name, Tone.NEUTRAL);
    }

    /** The pill for a live download, which is where the verifying/downloading split matters most. */
    static Label forDownload(ProgressSnapshot snapshot) {
        DownloadState state = snapshot.state();
        return switch (state) {
            case VERIFYING -> verifying((int) Math.round(Math.max(0, snapshot.fraction()) * 100));
            case COMPLETED -> verified();
            case QUARANTINED -> of("Quarantined", Tone.CORAL);
            case FAILED -> of("Failed", Tone.CORAL);
            case PAUSED -> of("Paused", Tone.NEUTRAL);
            case CANCELLED -> of("Cancelled", Tone.NEUTRAL);
            case QUEUED -> of("Queued", Tone.NEUTRAL);
            case CONNECTING -> of("Connecting…", Tone.ACCENT);
            case DOWNLOADING ->
                of("Downloading · " + (int) Math.round(Math.max(0, snapshot.fraction()) * 100) + "%", Tone.ACCENT);
        };
    }
}
