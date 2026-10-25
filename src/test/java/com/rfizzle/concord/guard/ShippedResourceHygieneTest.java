package com.rfizzle.concord.guard;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the boundary between what ships and what only ever serves a test.
 * HUB-OWNED: propagated verbatim from {@code rfizzle/concord}; never edit it in a member.
 *
 * <p>Gametest fixtures — structure templates and bespoke loot tables — resolve through the merged
 * {@code ResourceManager} by namespace, so they work from any loaded mod's resource root. They belong
 * in the gametest source set, whose manifest declares a separate {@code <modid>-gametest} mod that
 * never enters the jar. Keeping them out of the shipped roots is worth a guard on two counts: a loot
 * table in the mod's namespace is eagerly parsed and validated on every datapack reload on every
 * server purely to serve a test, and a structure template is listed for {@code /place template}
 * autocomplete, surfacing test fixtures to operators.
 *
 * <p>These assertions read the test classpath rather than the source tree, because the classpath is
 * what the jar is built from — it is the shipped artifact under test. That also means they cover
 * {@code src/main/generated}, which {@code concord-common.gradle} folds into main's resource roots.
 *
 * <p>Mod-agnostic: the mod id comes from the {@code concord.modid} system property and the gametest
 * fixtures are derived from {@code src/gametest/resources} ({@code concord.gametest.resources}), both
 * set by {@code gradle/concord-common.gradle}. A resource root is located by anchoring on a file known
 * to live at its top level: {@code <id>.mixins.json}, {@code <id>.client.mixins.json} or
 * {@code <id>.accesswidener}, whichever the mod has (the client source set ships its own root).
 */
class ShippedResourceHygieneTest {

    /** Path segment that marks a file as existing only to serve the gametest suite. */
    private static final String TEST_ONLY_SEGMENT = "gametest";

    /** Structure templates are a gametest-only format in every member — none ships structures of its own. */
    private static final String TEMPLATE_EXTENSION = ".snbt";

    private static String modId() {
        return GuardSupport.property("concord.modid");
    }

    /** Every gametest-only resource, derived from the gametest source set rather than a pinned list. */
    private static List<String> gametestOnlyResources() {
        Path gametestResources = GuardSupport.path("concord.gametest.resources");
        List<String> fixtures = new ArrayList<>();
        if (!Files.isDirectory(gametestResources)) {
            return fixtures;
        }
        try (Stream<Path> tree = Files.walk(gametestResources)) {
            tree.filter(Files::isRegularFile)
                    .map(gametestResources::relativize)
                    // The gametest manifest legitimately shares its name with the shipped one; the
                    // registration guard is what keeps the two apart.
                    .filter(relative -> !"fabric.mod.json".equals(relative.toString()))
                    .forEach(relative -> {
                        List<String> parts = new ArrayList<>();
                        relative.forEach(part -> parts.add(part.toString()));
                        fixtures.add("/" + String.join("/", parts));
                    });
        } catch (IOException e) {
            throw new AssertionError("could not walk " + gametestResources, e);
        }
        fixtures.sort(String::compareTo);
        return fixtures;
    }

    /** Every gametest-only resource must be absent from the shipped classpath. */
    @Test
    void gametestFixtures_areNotOnTheShippedClasspath() {
        // Resolving the roots first keeps this from passing vacuously against an empty classpath.
        shippedResourceRoots();

        for (String fixture : gametestOnlyResources()) {
            assertNull(ShippedResourceHygieneTest.class.getResource(fixture),
                    "test-only fixture is on the shipped classpath and will land in the jar: "
                            + fixture + " — it belongs in src/gametest/resources/");
        }
    }

    /** The general form of the same rule, so a fixture added under a new name is caught too. */
    @Test
    void noGametestPathSegment_anywhereInShippedResources() {
        List<String> offenders = scanShippedRoots(relative -> hasSegment(relative, TEST_ONLY_SEGMENT));

        assertTrue(offenders.isEmpty(),
                "shipped resources must not contain gametest-only files, but found "
                        + offenders.size() + ": " + offenders
                        + " — move them to src/gametest/resources/, which is on the "
                        + "runGametest classpath but never enters the jar");
    }

    /** Catches a template parked outside a {@code gametest/} directory, which the sweep above misses. */
    @Test
    void noStructureTemplates_anywhereInShippedResources() {
        List<String> offenders = scanShippedRoots(
                relative -> relative.getFileName().toString().endsWith(TEMPLATE_EXTENSION));

        assertTrue(offenders.isEmpty(),
                "shipped resources must not contain structure templates, but found "
                        + offenders.size() + ": " + offenders
                        + " — templates serve the gametest suite only and belong in "
                        + "src/gametest/resources/");
    }

    /**
     * Pins the walk against files that are genuinely shipped, so a root that resolved to an empty or
     * wrong directory cannot let the sweeps above pass by finding nothing at all.
     */
    @Test
    void theScanActuallyReachesTheShippedTree() {
        List<String> pinned = scanShippedRoots(relative -> relative.endsWith(Path.of("lang", "en_us.json")));

        assertTrue(pinned.contains(Path.of("assets", modId(), "lang", "en_us.json").toString()),
                "the shipped-resource walk did not find assets/" + modId() + "/lang/en_us.json, so the "
                        + "hygiene sweeps above are scanning the wrong tree and prove nothing; found: " + pinned);
    }

    /**
     * Pins the walk against the datagen tree too: generated files reach the jar only because
     * src/main/generated is a main resource root, and a walk that stopped seeing them would leave
     * every generated recipe, tag and loot table outside the sweeps above.
     */
    @Test
    void theScanReachesTheGeneratedTree() throws Exception {
        Path generated = Path.of("src", "main", "generated");
        Assumptions.assumeTrue(Files.isDirectory(generated), "this mod has no datagen output");
        Path sample;
        try (Stream<Path> tree = Files.walk(generated)) {
            sample = tree.filter(Files::isRegularFile)
                    .map(generated::relativize)
                    .filter(relative -> !hasSegment(relative, ".cache"))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        }
        Assumptions.assumeTrue(sample != null, "src/main/generated holds no generated files");

        List<String> pinned = scanShippedRoots(relative -> relative.equals(sample));
        assertTrue(pinned.contains(sample.toString()),
                "the shipped-resource walk did not find the generated file " + sample + ", so datagen "
                        + "output is no longer merged into the shipped resources (or the walk misses it)");
    }

    /** Walks every shipped root, collecting root-relative paths of files matching the rule. */
    private static List<String> scanShippedRoots(Predicate<Path> offending) {
        List<String> offenders = new ArrayList<>();

        for (Path root : shippedResourceRoots()) {
            try (Stream<Path> tree = Files.walk(root)) {
                tree.filter(Files::isRegularFile)
                        .map(root::relativize)
                        .filter(offending)
                        .map(Path::toString)
                        .forEach(offenders::add);
            } catch (Exception e) {
                fail("could not walk shipped resource root " + root, e);
            }
        }

        offenders.sort(String::compareTo);
        return offenders;
    }

    private static boolean hasSegment(Path relative, String segment) {
        for (Path part : relative) {
            if (segment.equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves every directory the shipped resources are processed into, failing rather than returning
     * an empty list — a guard that walks nothing passes while enforcing nothing.
     */
    private static List<Path> shippedResourceRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        List<String> anchors = List.of(
                "/" + modId() + ".mixins.json",
                "/" + modId() + ".client.mixins.json",
                "/" + modId() + ".accesswidener");

        for (String anchor : anchors) {
            URL resource = ShippedResourceHygieneTest.class.getResource(anchor);
            if (resource == null) {
                continue;
            }
            if (!"file".equals(resource.getProtocol())) {
                fail("expected the shipped resource root to be a directory on the test "
                        + "classpath, but " + anchor + " resolved to " + resource);
            }
            try {
                roots.add(Path.of(resource.toURI()).getParent());
            } catch (Exception e) {
                fail("could not resolve a filesystem path for " + resource, e);
            }
        }

        if (roots.isEmpty()) {
            fail("could not locate any of " + anchors + " on the test classpath — the mod ships none of "
                    + "the files this guard uses to find a shipped resource root");
        }
        boolean holdsManifest = roots.stream().anyMatch(root -> Files.exists(root.resolve("fabric.mod.json")));
        assertTrue(holdsManifest,
                "none of the resolved shipped resource roots " + roots + " holds fabric.mod.json — an anchor "
                        + "has most likely moved into a subdirectory, which would silently narrow this "
                        + "guard's coverage");

        return new ArrayList<>(roots);
    }
}
