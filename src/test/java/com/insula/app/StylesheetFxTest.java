package com.insula.app;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The design tokens actually parse.
 *
 * <p>JavaFX does not fail on a bad CSS value — it logs a warning and <em>drops that declaration</em>,
 * so the app runs, the tests pass, and one rule is silently missing. That is exactly how
 * {@code -fx-font-weight: 650} shipped: a plausible number that JavaFX does not accept (it takes
 * 100–900 in hundreds), which meant every state pill in the app was quietly not bold. Nothing in
 * the suite could see it; only reading the launch log did.
 *
 * <p>So the parser's own warnings are treated as failures here.
 */
class StylesheetFxTest {

    @Test
    void everyRuleInTheStylesheetParses() {
        List<String> complaints = new ArrayList<>();
        Handler collector = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    complaints.add(record.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        // The ROOT logger, not a named one: the warning's source class is CssParser but the
        // logger it goes through is not, and attaching to the guessed name is how the first
        // version of this test passed with the very bug it was written to catch.
        Logger root = Logger.getLogger("");
        Level previous = root.getLevel();
        root.setLevel(Level.ALL);
        root.addHandler(collector);
        try {
            // Parsed directly rather than through a Scene: JavaFX's StyleManager caches a parsed
            // stylesheet by URL for the life of the JVM, so once any other test in this run has
            // loaded it the warnings never fire again — another way to pass vacuously.
            java.net.URL sheet = StylesheetFxTest.class.getResource("/com/insula/app/insula.css");
            assertNotNull(sheet);
            FxTestSupport.runOnFx(() -> {
                try {
                    new javafx.css.CssParser().parse(sheet);
                } catch (java.io.IOException e) {
                    throw new AssertionError("the stylesheet could not be read", e);
                }
            });
        } finally {
            root.removeHandler(collector);
            root.setLevel(previous);
        }

        List<String> ours = complaints.stream()
                .filter(message -> message != null && message.contains("insula.css"))
                .toList();
        assertTrue(ours.isEmpty(), "the stylesheet has rules JavaFX silently drops:\n" + String.join("\n", ours));
    }
}
