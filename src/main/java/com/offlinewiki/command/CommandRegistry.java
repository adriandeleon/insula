package com.offlinewiki.command;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every user-facing action is registered here; the command palette, keybindings and toolbar all
 * dispatch through it (never wire UI handlers to logic directly when a command fits).
 */
public final class CommandRegistry {

    private final Map<String, Command> commands = new LinkedHashMap<>();

    public void register(String id, String title, Runnable action) {
        commands.put(id, Command.of(id, title, action));
    }

    /** Registration order is preserved — it is the palette's empty-query order. */
    public Collection<Command> all() {
        return commands.values();
    }

    /** Runs a command by id; unknown ids are a no-op (returns false). */
    public boolean run(String id) {
        Command command = commands.get(id);
        if (command == null) {
            return false;
        }
        command.action().run();
        return true;
    }
}
