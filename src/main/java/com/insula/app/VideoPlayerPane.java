package com.insula.app;

import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;

/**
 * Plays a video over the article, using JavaFX's own media stack rather than the WebView's.
 *
 * <p>That choice is forced, and was measured rather than assumed. Playing the on-demand HLS stream
 * through an inline {@code <video>} inside a real archive page crashed the process roughly half
 * the time — a SIGSEGV in {@code libjfxwebkit}'s paint pulse ({@code WebPage.twkUpdateContent}),
 * with the same page and a plain MP4 surviving every run. WebView is single-process, so that
 * crash takes the whole app with it. JavaFX's {@link MediaPlayer} plays the identical stream with
 * no WebKit involvement, so the video moves out of the page and sits above it.
 *
 * <p>The controls are deliberately minimal — play/pause, a scrubber, elapsed time, close — because
 * the interesting behaviour lives in the stream, not here.
 */
final class VideoPlayerPane {

    private final StackPane root = new StackPane();
    private final MediaPlayer player;
    private final Slider scrubber = new Slider();
    private final Label elapsed = new Label("0:00");
    private final Label total = new Label("0:00");
    private final Button playPause = new Button("⏸");

    private boolean scrubbing;
    private final ChangeListener<Duration> timeListener;

    VideoPlayerPane(String streamUrl, String title, Runnable onClose) {
        player = new MediaPlayer(new Media(streamUrl));
        MediaView view = new MediaView(player);
        view.setPreserveRatio(true);

        StackPane stage = new StackPane(view);
        stage.setStyle("-fx-background-color: black;");
        VBox.setVgrow(stage, Priority.ALWAYS);
        // The picture tracks the pane rather than the media's intrinsic size, so a small talk does
        // not sit as a postage stamp in the middle of a large window.
        view.fitWidthProperty().bind(stage.widthProperty());
        view.fitHeightProperty().bind(stage.heightProperty());

        playPause.setOnAction(e -> togglePlay());
        scrubber.setMin(0);
        scrubber.setMax(1);
        scrubber.setValue(0);
        HBox.setHgrow(scrubber, Priority.ALWAYS);
        scrubber.setOnMousePressed(e -> scrubbing = true);
        scrubber.setOnMouseReleased(e -> {
            seekToFraction(scrubber.getValue());
            scrubbing = false;
        });

        Button close = new Button("✕");
        close.setOnAction(e -> onClose.run());
        Label name = new Label(title == null ? "" : title);
        name.setStyle("-fx-text-fill: white;");
        elapsed.setStyle("-fx-text-fill: white;");
        total.setStyle("-fx-text-fill: white;");

        HBox controls = new HBox(10, playPause, elapsed, scrubber, total, close);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(8, 12, 8, 12));
        controls.setStyle("-fx-background-color: #101216;");

        HBox header = new HBox(name);
        header.setPadding(new Insets(8, 12, 8, 12));
        header.setStyle("-fx-background-color: #101216;");

        VBox box = new VBox(header, stage, controls);
        box.setStyle("-fx-background-color: #101216;");
        root.getChildren().add(box);
        root.setStyle("-fx-background-color: rgba(0,0,0,0.85);");
        StackPane.setMargin(box, new Insets(24));

        timeListener = (obs, old, now) -> {
            if (!scrubbing) {
                double seconds = totalSeconds();
                scrubber.setValue(seconds > 0 ? now.toSeconds() / seconds : 0);
            }
            elapsed.setText(format(now.toSeconds()));
        };
        player.currentTimeProperty().addListener(timeListener);
        player.setOnReady(() -> total.setText(format(totalSeconds())));
        player.setOnEndOfMedia(() -> playPause.setText("▶"));
        player.play();
    }

    Region node() {
        return root;
    }

    private void togglePlay() {
        if (player.getStatus() == MediaPlayer.Status.PLAYING) {
            player.pause();
            playPause.setText("▶");
        } else {
            player.play();
            playPause.setText("⏸");
        }
    }

    private void seekToFraction(double fraction) {
        double seconds = totalSeconds();
        if (seconds > 0) {
            player.seek(Duration.seconds(Math.max(0, Math.min(1, fraction)) * seconds));
        }
    }

    private double totalSeconds() {
        Duration duration = player.getMedia().getDuration();
        return duration == null || duration.isUnknown() || duration.isIndefinite() ? 0 : duration.toSeconds();
    }

    static String format(double seconds) {
        if (seconds < 0 || Double.isNaN(seconds)) {
            return "0:00";
        }
        long whole = (long) seconds;
        long hours = whole / 3600;
        long minutes = (whole % 3600) / 60;
        long secs = whole % 60;
        return hours > 0
                ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
                : String.format(java.util.Locale.ROOT, "%d:%02d", minutes, secs);
    }

    double currentSecondsForTest() {
        return player.getCurrentTime() == null ? 0 : player.getCurrentTime().toSeconds();
    }

    void seekSecondsForTest(double seconds) {
        player.seek(Duration.seconds(seconds));
    }

    /** Stops playback and releases the player; a session left running would keep encoding. */
    void dispose() {
        player.currentTimeProperty().removeListener(timeListener);
        player.stop();
        player.dispose();
    }
}
