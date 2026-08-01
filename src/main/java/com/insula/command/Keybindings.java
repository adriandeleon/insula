package com.insula.command;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import javafx.scene.Scene;
import javafx.scene.input.KeyCombination;

/** Chord → command bindings; installs scene accelerators and feeds the palette's shortcut hints. */
public final class Keybindings {

    private final Map<String, KeyCombination> byCommand = new LinkedHashMap<>();

    public void bind(String commandId, KeyCombination combination) {
        byCommand.put(commandId, combination);
    }

    public void install(Scene scene, CommandRegistry registry) {
        byCommand.forEach((id, combo) -> scene.getAccelerators().put(combo, () -> registry.run(id)));
    }

    /** Ids of every bound command — lets a test assert no binding points at a missing command. */
    public Collection<String> commandIds() {
        return byCommand.keySet();
    }

    /** Platform display text ("Ctrl+Shift+P", "⇧⌘P"), or "" when the command has no binding. */
    public String displayFor(String commandId) {
        KeyCombination combo = byCommand.get(commandId);
        return combo == null ? "" : combo.getDisplayText();
    }
}
