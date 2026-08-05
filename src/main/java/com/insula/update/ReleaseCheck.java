package com.insula.update;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;

/**
 * Whether a newer Insula has been published: parsing the answer, comparing it to what is running,
 * and deciding how often to ask.
 *
 * <p><b>This is about the application. {@link com.insula.catalog.UpdateCheck} is about archives</b> —
 * newer ZIM builds for what is on the shelf. They share a word and nothing else, which is why this
 * one is not called {@code UpdateCheck}: two classes of that name in one app is an invitation to
 * import the wrong one, and both compile.
 *
 * <p>Pure: no network, no toolkit, no clock. {@link ReleaseService} does the fetching.
 */
public final class ReleaseCheck {

    /** How often the background check runs at most. Once a day; a release is not urgent. */
    public static final long DEFAULT_INTERVAL_MS = 24L * 60 * 60 * 1000;

    /**
     * Caps on the payload the parser will accept, over and above {@link ReleaseService}'s byte cap.
     *
     * <p>A release body is user-written Markdown and can be long, but the depth and name limits are
     * what stop a crafted response from costing more to parse than it does to send.
     */
    private static final StreamReadConstraints CONSTRAINTS = StreamReadConstraints.builder()
            .maxNestingDepth(32)
            .maxStringLength(200_000)
            .maxNameLength(1_000)
            .build();

    private static final JsonFactory FACTORY =
            JsonFactory.builder().streamReadConstraints(CONSTRAINTS).build();

    private ReleaseCheck() {}

    /**
     * Reads a GitHub {@code /releases/latest} payload, or {@code null} when it does not describe a
     * release worth offering — a draft, a prerelease, malformed JSON, or anything with no tag.
     *
     * <p>A streaming token walk rather than a tree: the five fields wanted are top-level scalars,
     * and everything else in the payload (the assets array, two user objects, the Markdown body) is
     * skipped without being materialized. {@code null} on any failure, so a mangled or hostile
     * response is indistinguishable from "no update" to every caller — there is nothing useful for
     * the UI to say about a broken payload, and a thrown exception here would land on a background
     * thread during a check nobody asked for.
     */
    public static ReleaseInfo parseLatest(byte[] json) {
        if (json == null || json.length == 0) {
            return null;
        }
        String tag = "";
        String url = "";
        String name = "";
        try (JsonParser parser = FACTORY.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                switch (field) {
                    case "draft", "prerelease" -> {
                        if (value == JsonToken.VALUE_TRUE) {
                            return null; // a full release only — never nudge toward a draft
                        }
                    }
                    case "tag_name" -> tag = text(parser, value);
                    case "html_url" -> url = text(parser, value);
                    case "name" -> name = text(parser, value);
                    default -> parser.skipChildren(); // no-op on a scalar, skips whole arrays/objects
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        String version = normalizeVersion(tag);
        return version.isEmpty() ? null : new ReleaseInfo(version, url, name);
    }

    private static String text(JsonParser parser, JsonToken token) throws IOException {
        return token == JsonToken.VALUE_STRING ? parser.getText().strip() : "";
    }

    /** Strips a leading {@code v} or {@code V} from a tag: {@code v0.2.0} becomes {@code 0.2.0}. */
    public static String normalizeVersion(String tag) {
        String t = tag == null ? "" : tag.strip();
        if (!t.isEmpty() && (t.charAt(0) == 'v' || t.charAt(0) == 'V')) {
            return t.substring(1);
        }
        return t;
    }

    /**
     * Whether {@code latest} is strictly newer than {@code current}.
     *
     * <p>A blank current version means the build never went through Maven and has no version to
     * compare — {@code false}, because the honest answer to "is there something newer than
     * nothing?" is not "yes, download this".
     */
    public static boolean isNewer(String current, String latest) {
        if (latest == null || latest.isBlank() || current == null || current.isBlank()) {
            return false;
        }
        return compareVersions(latest.strip(), current.strip()) > 0;
    }

    /**
     * Compares two dotted versions numerically, so {@code 0.10.0} is newer than {@code 0.9.0} —
     * which lexical comparison gets backwards, and gets backwards exactly once per project, at the
     * tenth release.
     *
     * <p>Missing components count as zero ({@code 1.2} equals {@code 1.2.0}), and a non-numeric
     * component counts as zero rather than throwing: this runs on a release tag fetched over the
     * network, and a tag someone typed by hand should make the check quiet, not make it fail.
     */
    public static int compareVersions(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int cmp = Integer.compare(part(left, i), part(right, i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static int part(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index].strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Every reason the daily background check runs or does not, in one place.
     *
     * <p>It is a function rather than a chain of {@code if}s in the window because otherwise no test
     * can reach past the first gate: {@code snapshot} is true in every development build, tests
     * included, so a test driving the real controller exercises that gate and nothing behind it —
     * the offline gate could be deleted and it would still pass. Four conditions each of which
     * matters, none of which was reachable, is how a privacy switch stops working quietly.
     *
     * @param enabled the user's setting
     * @param workOffline their explicit "make no requests"; an app built to run without a
     *     connection cannot be the one thing that ignores it
     * @param snapshot an unreleased build, which is expected to differ from the latest release —
     *     pointing its author at an older version is noise
     */
    public static boolean shouldCheckInBackground(
            boolean enabled, boolean workOffline, boolean snapshot, long lastCheckMs, long nowMs, long intervalMs) {
        return enabled && !workOffline && !snapshot && isDue(lastCheckMs, nowMs, intervalMs);
    }

    /**
     * Whether a background check is due: never run, or the interval has passed since the last one.
     *
     * <p>A last-check stamp in the future counts as due too — that is a clock moved backwards, and
     * the alternative is an app that quietly stops checking until the calendar catches up.
     */
    public static boolean isDue(long lastCheckMs, long nowMs, long intervalMs) {
        if (lastCheckMs <= 0) {
            return true;
        }
        long elapsed = nowMs - lastCheckMs;
        return elapsed < 0 || elapsed >= intervalMs;
    }
}
