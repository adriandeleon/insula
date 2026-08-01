package com.insula.app;

import java.nio.file.Path;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.insula.download.DownloadManager;
import com.insula.download.DownloadState;
import com.insula.download.ProgressSnapshot;

/**
 * One download as a row — the single component the spec asks for, shared wherever downloads
 * appear (the Library's "Arriving" section today; a Store chip popover later).
 *
 * <p>Built once per job and <b>updated in place</b> by the 4 Hz sampler: rebuilding rows per tick
 * would churn nodes four times a second for nothing. The states rendered here map 1:1 to
 * {@link DownloadState} — no state exists on screen that the pipeline does not have.
 */
final class DownloadRow extends VBox {

    private final DownloadManager.Job job;
    private final Consumer<Path> onOpen;

    private final Label title = new Label();
    private final ProgressBar bar = new ProgressBar(0);
    private final Label facts = new Label();
    private final Button pauseResume = new Button();
    private final Button cancel = new Button("✕");
    private final Button open = new Button("Open");

    private boolean paused;

    DownloadRow(DownloadManager.Job job, String displayTitle, Consumer<Path> onOpen, Consumer<String> onStatus) {
        super(5);
        this.job = job;
        this.onOpen = onOpen;

        title.setText(displayTitle);
        title.setStyle("-fx-font-weight: bold;");
        bar.setMaxWidth(Double.MAX_VALUE);
        facts.setStyle("-fx-opacity: 0.7; -fx-font-size: 0.9em;");

        pauseResume.setOnAction(e -> {
            if (paused) {
                job.resume();
                paused = false;
            } else {
                job.pause();
                paused = true;
            }
            update();
        });
        cancel.setOnAction(e -> {
            job.cancel();
            onStatus.accept("Cancelled " + displayTitle);
        });
        open.setOnAction(e -> onOpen.accept(job.destination()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(10, title, spacer, pauseResume, cancel, open);
        top.setAlignment(Pos.CENTER_LEFT);

        setPadding(new Insets(10, 12, 10, 12));
        setStyle("-fx-background-color: -color-bg-default; -fx-border-color: -color-border-default;"
                + " -fx-border-radius: 8; -fx-background-radius: 8;");
        getChildren().addAll(top, bar, facts);
        update();
    }

    DownloadManager.Job job() {
        return job;
    }

    /** Called by the owner's 4 Hz tick. */
    void update() {
        ProgressSnapshot s = job.snapshot();
        double fraction = s.fraction();
        bar.setProgress(fraction < 0 ? ProgressBar.INDETERMINATE_PROGRESS : fraction);
        facts.setText(Formats.progressLine(s));

        boolean active = !s.state().isTerminal();
        boolean verifying = s.state() == DownloadState.VERIFYING;
        pauseResume.setVisible(active && !verifying);
        pauseResume.setManaged(active && !verifying);
        pauseResume.setText(s.state() == DownloadState.PAUSED || paused ? "▶ Resume" : "⏸ Pause");
        cancel.setVisible(active && !verifying);
        cancel.setManaged(active && !verifying);
        open.setVisible(s.state() == DownloadState.COMPLETED);
        open.setManaged(s.state() == DownloadState.COMPLETED);

        // Verifying is amber and unskippable: a user who saw the bytes finish must not read the
        // hash pass as a hang.
        bar.setStyle(verifying ? "-fx-accent: #a16207;" : "");
    }
}
