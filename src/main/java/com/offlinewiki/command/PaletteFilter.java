package com.offlinewiki.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Pure palette matching/ranking: title prefix beats title substring beats id substring. */
public final class PaletteFilter {

    private PaletteFilter() {}

    public static List<Command> filter(String query, Collection<Command> commands) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return List.copyOf(commands);
        }
        record Scored(Command command, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (Command c : commands) {
            String title = c.title().toLowerCase(Locale.ROOT);
            int score;
            if (title.startsWith(q)) {
                score = 0;
            } else if (title.contains(q)) {
                score = 1;
            } else if (c.id().toLowerCase(Locale.ROOT).contains(q)) {
                score = 2;
            } else {
                continue;
            }
            scored.add(new Scored(c, score));
        }
        scored.sort(Comparator.comparingInt(Scored::score)
                .thenComparing(s -> s.command().title()));
        return scored.stream().map(Scored::command).toList();
    }
}
