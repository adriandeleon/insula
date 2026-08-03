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
    private final Button retry = new Button("Retry");
    private final Button dismiss = new Button("✕");

    private boolean paused;

    DownloadRow(
            DownloadManager.Job job,
            String displayTitle,
            Consumer<Path> onOpen,
            Consumer<String> onStatus,
            Runnable onRetry,
            Runnable onDismiss) {
        super(5);
        this.job = job;
        this.onOpen = onOpen;

        title.setText(displayTitle);
        title.getStyleClass().add("card-title");
        bar.setMaxWidth(Double.MAX_VALUE);
        facts.getStyleClass().add("card-sub");

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
        // A failure keeps its row so the reason stays on screen, which only helps if the row can
        // also act on it: start again, or say you are done with it.
        retry.getStyleClass().add("primary");
        retry.setOnAction(e -> {
            if (onRetry != null) {
                onRetry.run();
            }
        });
        dismiss.setTooltip(new javafx.scene.control.Tooltip("Dismiss"));
        dismiss.setOnAction(e -> {
            if (onDismiss != null) {
                onDismiss.run();
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(10, title, spacer, retry, dismiss, pauseResume, cancel, open);
        top.setAlignment(Pos.CENTER_LEFT);

        setPadding(new Insets(10, 12, 10, 12));
        getStyleClass().add("rowcard");
        getChildren().addAll(top, bar, facts);
        update();
    }

    DownloadManager.Job job() {
        return job;
    }

    /** Called by the owner's 4 Hz tick. */
    /** One tooltip instance, retargeted — installing a new one per 4 Hz tick would churn popups. */
    private javafx.scene.control.Tooltip swarmTooltip;

    private void setTooltip(String text) {
        if (text == null) {
            if (swarmTooltip != null) {
                javafx.scene.control.Tooltip.uninstall(this, swarmTooltip);
                swarmTooltip = null;
            }
            return;
        }
        if (swarmTooltip == null) {
            swarmTooltip = new javafx.scene.control.Tooltip();
            swarmTooltip.setShowDelay(javafx.util.Duration.millis(300));
            javafx.scene.control.Tooltip.install(this, swarmTooltip);
        }
        swarmTooltip.setText(text);
    }

    void update() {
        ProgressSnapshot s = job.snapshot();
        double fraction = s.fraction();
        bar.setProgress(fraction < 0 ? ProgressBar.INDETERMINATE_PROGRESS : fraction);
        String swarmSummary = SwarmText.summary(s.swarm());
        facts.setText(Formats.progressLine(s, job.transportName(), job.sourceNoun())
                + (swarmSummary.isEmpty() ? "" : " · " + swarmSummary));
        // The full reading hangs off the row rather than crowding it: peers, seeds, mirrors, both
        // rates and the ratio are what you want when a transfer looks wrong, not while it is fine.
        String detail = SwarmText.detail(s);
        setTooltip(detail.isEmpty() ? null : detail);

        boolean active = !s.state().isTerminal();
        boolean verifying = s.state() == DownloadState.VERIFYING;
        pauseResume.setVisible(active && !verifying);
        pauseResume.setManaged(active && !verifying);
        pauseResume.setText(s.state() == DownloadState.PAUSED || paused ? "▶ Resume" : "⏸ Pause");
        cancel.setVisible(active && !verifying);
        cancel.setManaged(active && !verifying);
        open.setVisible(s.state() == DownloadState.COMPLETED);
        open.setManaged(s.state() == DownloadState.COMPLETED);
        boolean failed = s.state() == DownloadState.FAILED;
        retry.setVisible(failed);
        retry.setManaged(failed);
        dismiss.setVisible(failed);
        dismiss.setManaged(failed);

        // Verifying is amber and unskippable: a user who saw the bytes finish must not read the
        // hash pass as a hang. The colour is the kit's token, not a literal.
        bar.getStyleClass().remove("bar-amber");
        if (verifying) {
            bar.getStyleClass().add("bar-amber");
        }
        getStyleClass().removeAll("rowcard-arriving", "rowcard-verify", "rowcard-failed");
        getStyleClass().add(failed ? "rowcard-failed" : verifying ? "rowcard-verify" : "rowcard-arriving");
    }
}
