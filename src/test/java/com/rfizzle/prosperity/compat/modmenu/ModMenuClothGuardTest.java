package com.rfizzle.prosperity.compat.modmenu;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Mod Menu entrypoint against the Mod Menu-without-Cloth crash: the class Mod Menu
 * loads must never reference Cloth Config directly and must gate the Cloth-built screen behind
 * an {@code isModLoaded("cloth-config")} check. The client source set is not on the unit-test
 * classpath, so this reads the source.
 */
class ModMenuClothGuardTest {

    private static final Path ENTRYPOINT = Path.of(
            "src/client/java/com/rfizzle/prosperity/compat/modmenu/ModMenuIntegration.java");

    @Test
    void entrypointHasNoDirectClothReferences() throws IOException {
        String source = Files.readString(ENTRYPOINT, StandardCharsets.UTF_8);
        assertFalse(source.contains("me.shedaniel"), "the Mod Menu entrypoint must not import Cloth Config");
    }

    @Test
    void entrypointGuardsTheClothScreenBehindIsModLoaded() throws IOException {
        String source = Files.readString(ENTRYPOINT, StandardCharsets.UTF_8);
        int guard = source.indexOf("isModLoaded(\"cloth-config\")");
        int screen = source.indexOf("ProsperityConfigScreen::create");
        assertTrue(guard >= 0, "the entrypoint must check isModLoaded(\"cloth-config\")");
        assertTrue(screen > guard, "the Cloth screen must only be handed out after the guard");
    }
}
