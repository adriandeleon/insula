package com.insula.command;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * Chords bound to more than one command, with the ids that share them.
     *
     * <p>A collision is silent and destructive: {@link #install} puts chords into a map, so the
     * last binding simply wins and the other command becomes unreachable by keyboard without any
     * error. This makes that a property a test can assert.
     */
    public Map<KeyCombination, List<String>> conflicts() {
        Map<KeyCombination, List<String>> byChord = new LinkedHashMap<>();
        byCommand.forEach((id, combo) ->
                byChord.computeIfAbsent(combo, c -> new java.util.ArrayList<>()).add(id));
        Map<KeyCombination, List<String>> clashes = new LinkedHashMap<>();
        byChord.forEach((combo, ids) -> {
            if (ids.size() > 1) {
                clashes.put(combo, List.copyOf(ids));
            }
        });
        return clashes;
    }

    /** The binding itself, for a menu accelerator; null when the command has none. */
    public KeyCombination combinationFor(String commandId) {
        return byCommand.get(commandId);
    }

    /** Platform display text ("Ctrl+Shift+P", "⇧⌘P"), or "" when the command has no binding. */
    public String displayFor(String commandId) {
        KeyCombination combo = byCommand.get(commandId);
        return combo == null ? "" : combo.getDisplayText();
    }
}
