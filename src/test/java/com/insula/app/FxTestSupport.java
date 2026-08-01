package com.insula.app;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

/**
 * Boots the JavaFX toolkit once for UI tests, on JavaFX 26's built-in Headless Glass platform
 * (set via surefire system properties) so no display is needed.
 */
final class FxTestSupport {

    private static boolean started;

    private FxTestSupport() {}

    static synchronized void startToolkit() {
        if (started) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        await(latch, "toolkit startup");
        Platform.setImplicitExit(false);
        started = true;
    }

    /** Runs on the FX thread and rethrows whatever it threw, so a failure surfaces as a test failure. */
    static <T> T callOnFx(Callable<T> action) {
        startToolkit();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        await(latch, "FX action");
        if (error.get() != null) {
            throw new AssertionError("FX action failed", error.get());
        }
        return result.get();
    }

    static void runOnFx(Runnable action) {
        callOnFx(() -> {
            action.run();
            return null;
        });
    }

    private static void await(CountDownLatch latch, String what) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for " + what);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for " + what, e);
        }
    }
}
