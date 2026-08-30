package com.rfizzle.prosperity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier-1 guard that the gametest suites on disk and the {@code fabric-gametest}
 * entrypoints in the companion manifest stay in lockstep (mc-mod-testing).
 *
 * <p>Registration fails silently in both directions: an unregistered
 * {@code FabricGameTest} never runs and never warns, so a suite can rot for
 * months while CI stays green; a stale entrypoint naming a deleted class crashes
 * the run at startup. Both are checked here rather than discovered later.
 *
 * <p>The gametest source set is not on the test classpath, so its classes cannot
 * be enumerated reflectively — the guard reads the source tree instead, walking
 * it recursively so suites in subpackages are not missed. A suite is identified
 * by the interface it implements rather than a filename suffix: the source set
 * also holds helpers ({@code MockPlayers}, fixtures, recorders),
 * and a suffix-only match would flag those as unregistered while letting a
 * mis-named suite slip past both sides of the comparison at once.
 *
 * <p>A suite that picks the interface up from an abstract base rather than
 * declaring it is rejected by design: it reads as a non-suite while still being
 * named {@code *GameTest}, so the naming check fails it loudly instead of
 * letting it register implicitly.
 */
class GametestRegistrationTest {
    private static final Path GAMETEST_SOURCES = Path.of("src/gametest/java");
    private static final Path GAMETEST_MANIFEST = Path.of("src/gametest/resources/fabric.mod.json");
    private static final Path SHIPPED_MANIFEST = Path.of("src/main/resources/fabric.mod.json");

    /** Matches a class's {@code implements} clause naming FabricGameTest. */
    private static final Pattern IMPLEMENTS_FABRIC_GAMETEST =
            Pattern.compile("implements\\s+[^{]*\\bFabricGameTest\\b");

    /** Fully-qualified names of every class under the gametest tree, mapped to its source text. */
    private static TreeMap<String, String> gametestSources() {
        TreeMap<String, String> sources = new TreeMap<>();
        try (Stream<Path> tree = Files.walk(GAMETEST_SOURCES)) {
            tree.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String relative = GAMETEST_SOURCES.relativize(p).toString();
                String className = relative.substring(0, relative.length() - ".java".length())
                        .replace(java.io.File.separatorChar, '.');
                try {
                    sources.put(className, Files.readString(p, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new AssertionError("could not walk " + GAMETEST_SOURCES, e);
        } catch (UncheckedIOException e) {
            throw new AssertionError("could not read a source file under " + GAMETEST_SOURCES, e.getCause());
        }
        return sources;
    }

    private static boolean isSuite(String source) {
        return IMPLEMENTS_FABRIC_GAMETEST.matcher(source).find();
    }

    private static Set<String> suitesOnDisk() {
        TreeSet<String> suites = new TreeSet<>();
        gametestSources().forEach((className, source) -> {
            if (isSuite(source)) {
                suites.add(className);
            }
        });
        return suites;
    }

    private static Set<String> declaredEntrypoints() {
        try {
            JsonObject manifest = JsonParser.parseString(
                    Files.readString(GAMETEST_MANIFEST, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject entrypoints = manifest.getAsJsonObject("entrypoints");
            assertNotNull(entrypoints, GAMETEST_MANIFEST + " declares no entrypoints object");
            JsonArray entries = entrypoints.getAsJsonArray("fabric-gametest");
            assertNotNull(entries, GAMETEST_MANIFEST + " declares no fabric-gametest entrypoints"
                    + " — every gametest suite would silently stop running");
            TreeSet<String> declared = new TreeSet<>();
            for (JsonElement entry : entries) {
                declared.add(entry.getAsString());
            }
            return declared;
        } catch (IOException e) {
            throw new AssertionError("could not read " + GAMETEST_MANIFEST, e);
        }
    }

    @Test
    void everySuiteOnDiskIsRegistered() {
        TreeSet<String> unregistered = new TreeSet<>(suitesOnDisk());
        unregistered.removeAll(declaredEntrypoints());
        assertTrue(unregistered.isEmpty(),
                "gametest suites exist but are not declared in " + GAMETEST_MANIFEST
                        + " — they will silently never run: " + unregistered);
    }

    @Test
    void everyRegisteredEntrypointIsASuiteOnDisk() {
        TreeSet<String> dangling = new TreeSet<>(declaredEntrypoints());
        dangling.removeAll(suitesOnDisk());
        assertTrue(dangling.isEmpty(),
                GAMETEST_MANIFEST + " declares entrypoints that are not FabricGameTest classes on"
                        + " disk — the gametest run will fail to load them: " + dangling);
    }

    @Test
    void suiteNamingConventionHoldsInBothDirections() {
        // Matching suites by interface closes the "helper flagged as unregistered" hole; enforcing
        // the name closes the other one, where a suite called FooTests goes missing from the source
        // tree scan and the manifest at the same time and the guard above stays green.
        TreeSet<String> misnamedSuites = new TreeSet<>();
        TreeSet<String> impostors = new TreeSet<>();
        gametestSources().forEach((className, source) -> {
            boolean suite = isSuite(source);
            boolean named = className.endsWith("GameTest");
            if (suite && !named) {
                misnamedSuites.add(className);
            } else if (!suite && named) {
                impostors.add(className);
            }
        });
        assertTrue(misnamedSuites.isEmpty(),
                "FabricGameTest implementors must be named *GameTest: " + misnamedSuites);
        assertTrue(impostors.isEmpty(),
                "classes named *GameTest must implement FabricGameTest: " + impostors);
    }

    /** The mod ids the given manifest declares in its {@code depends} block. */
    private static Set<String> declaredDependencies(Path manifest) {
        try {
            JsonObject parsed = JsonParser.parseString(
                    Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject depends = parsed.getAsJsonObject("depends");
            assertNotNull(depends, manifest + " declares no depends object");
            return new TreeSet<>(depends.keySet());
        } catch (IOException e) {
            throw new AssertionError("could not read " + manifest, e);
        }
    }

    @Test
    void gametestManifestDependsOnExactlyTheMainMod() {
        // Set equality, not containment. The companion mod exists only to carry the
        // entrypoints, and it already inherits the real dependency set — loader, Minecraft,
        // Java, fabric-api — transitively through `prosperity`. Restating any of them here
        // creates a second place to bump on a version move, and the copy that gets forgotten
        // fails the gametest run at load against a manifest nobody thinks to look at. A
        // missing dependency is self-announcing; an extra one is not, so exclusivity is the
        // half worth asserting.
        assertEquals(
                Set.of("prosperity"),
                declaredDependencies(GAMETEST_MANIFEST),
                GAMETEST_MANIFEST + " must depend on the main mod alone.");
    }

    @Test
    void shippedManifestDeclaresNoGametestEntrypoints() {
        // The dev runtime's fabric-gametest-api-v1 initializer is ungated: it instantiates every
        // declared fabric-gametest class on any server launch, and the default server/client run
        // sets do not carry the gametest source set. Entrypoints here break runServer outright.
        try {
            JsonObject shipped = JsonParser.parseString(
                    Files.readString(SHIPPED_MANIFEST, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject entrypoints = shipped.getAsJsonObject("entrypoints");
            assertNotNull(entrypoints, SHIPPED_MANIFEST + " declares no entrypoints object");
            assertFalse(entrypoints.has("fabric-gametest"),
                    "fabric-gametest entrypoints belong in " + GAMETEST_MANIFEST
                            + ", not the shipped manifest");
        } catch (IOException e) {
            throw new AssertionError("could not read " + SHIPPED_MANIFEST, e);
        }
    }
}
