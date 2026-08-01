package com.offlinewiki.command;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRegistryTest {

    @Test
    void runsRegisteredCommandById() {
        AtomicInteger runs = new AtomicInteger();
        CommandRegistry registry = new CommandRegistry();
        registry.register("test.run", "Run It", runs::incrementAndGet);

        assertTrue(registry.run("test.run"));
        assertEquals(1, runs.get());
    }

    @Test
    void unknownIdIsANoOp() {
        CommandRegistry registry = new CommandRegistry();
        assertFalse(registry.run("nope"));
    }

    @Test
    void preservesRegistrationOrderAndAllowsOverride() {
        CommandRegistry registry = new CommandRegistry();
        registry.register("a", "A", () -> {});
        registry.register("b", "B", () -> {});
        assertEquals(List.of("a", "b"), registry.all().stream().map(Command::id).toList());

        AtomicInteger replacement = new AtomicInteger();
        registry.register("a", "A2", replacement::incrementAndGet);
        assertEquals(2, registry.all().size(), "re-registering an id replaces rather than duplicates");
        registry.run("a");
        assertEquals(1, replacement.get());
    }
}
