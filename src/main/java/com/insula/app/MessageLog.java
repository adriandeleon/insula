package com.insula.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * A session-only history of the status-bar messages.
 *
 * <p>The status bar shows one message at a time and never clears it — each is <em>replaced</em> by
 * the next. So something that mattered ("Could not reach the catalog", "Moved 6 archives") is gone
 * the moment anything routine follows it, with no trace the reader would notice. Keeping the last
 * few hundred and letting them click the line to read back is the cheapest possible fix.
 *
 * <p>Not persisted: this is what happened in front of you just now, not a record. Bounded so a
 * long session cannot grow without limit, and blank messages — which are how the line is
 * <em>cleared</em> — are not entries.
 *
 * <p>Pure model, no JavaFX, so the cap and the ordering can be tested without a toolkit.
 */
public final class MessageLog {

    /** Maximum retained messages; older ones are evicted. */
    public static final int MAX_ENTRIES = 200;

    /** One message and when it was shown. */
    public record Entry(long epochMillis, String text) {}

    // Oldest first: appended at the tail, evicted from the head.
    private final Deque<Entry> entries = new ArrayDeque<>();

    /** Records a message. No-ops for null or blank — those clear the line rather than say anything. */
    public void add(String message, long epochMillis) {
        if (message == null || message.isBlank()) {
            return;
        }
        Entry last = entries.peekLast();
        if (last != null && last.text().equals(message)) {
            return; // the same message twice running is one thing happening, not two
        }
        entries.addLast(new Entry(epochMillis, message));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
    }

    public void add(String message) {
        add(message, System.currentTimeMillis());
    }

    /** A snapshot, newest first — the order the list shows them in. */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>(entries);
        Collections.reverse(out);
        return out;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }
}
