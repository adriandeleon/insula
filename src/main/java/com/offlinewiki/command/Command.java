package com.offlinewiki.command;

/** A user-invokable action: stable id, palette title, and the action itself. */
public record Command(String id, String title, Runnable action) {

    public static Command of(String id, String title, Runnable action) {
        return new Command(id, title, action);
    }
}
