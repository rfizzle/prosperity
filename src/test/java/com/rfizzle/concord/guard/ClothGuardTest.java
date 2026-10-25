package com.rfizzle.concord.guard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Cloth Config is an optional integration, so nothing the loader touches at startup may link it.
 * HUB-OWNED: propagated verbatim from {@code rfizzle/concord}; never edit it in a member.
 *
 * <p>Two rules, both of which a member shipped broken before this guard existed:
 * <ul>
 *   <li>The class registered as the {@code modmenu} entrypoint in {@code fabric.mod.json} imports no
 *       Cloth type. Mod Menu loads that class whether or not Cloth is installed, so an import there
 *       is a {@code NoClassDefFoundError} for every player without Cloth. The Cloth-using builder
 *       belongs in its own class, reached only behind an {@code isModLoaded("cloth-config")} check.
 *   <li>If any production source imports Cloth, {@code fabric.mod.json} {@code suggests}
 *       {@code cloth-config}, so the mod pages and launchers tell players the config screen needs it.
 * </ul>
 *
 * <p>Inputs: {@code concord.main.manifest} and {@code concord.sourceDirs.production}, set by
 * {@code gradle/concord-common.gradle}.
 */
class ClothGuardTest {

    /** Split so this file's own source never contains the package it forbids elsewhere. */
    private static final String CLOTH_PACKAGE = "me.shedaniel." + "clothconfig2";

    private static final Pattern CLOTH_IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?" + Pattern.quote(CLOTH_PACKAGE) + "\\b");

    @Test
    void modMenuEntrypointImportsNoClothType() {
        JsonObject manifest = GuardSupport.readJson(GuardSupport.path("concord.main.manifest"));
        List<Path> roots = GuardSupport.directories("concord.sourceDirs.production");
        assertTrue(!roots.isEmpty(), "no production source directories resolved — the guard would walk nothing");

        for (String entrypoint : modMenuEntrypoints(manifest)) {
            Path source = findSource(roots, entrypoint);
            if (source == null) {
                fail("the modmenu entrypoint '" + entrypoint + "' in fabric.mod.json has no source file under "
                        + roots + " — the guard cannot check it");
            }
            String code = GuardSupport.code(source);
            assertTrue(!code.contains(CLOTH_PACKAGE),
                    "the modmenu entrypoint " + entrypoint + " (" + source + ") references " + CLOTH_PACKAGE
                            + ". Mod Menu loads this class even when Cloth Config is absent, so players without "
                            + "Cloth crash. Move the Cloth screen into its own class and reach it only behind "
                            + "FabricLoader.getInstance().isModLoaded(\"cloth-config\").");
        }
    }

    @Test
    void aModThatImportsClothSuggestsIt() {
        JsonObject manifest = GuardSupport.readJson(GuardSupport.path("concord.main.manifest"));
        List<Path> roots = GuardSupport.directories("concord.sourceDirs.production");
        assertTrue(!roots.isEmpty(), "no production source directories resolved — the guard would walk nothing");

        List<Path> importers = new ArrayList<>();
        for (Path file : GuardSupport.javaFiles(roots)) {
            if (CLOTH_IMPORT.matcher(GuardSupport.code(file)).find()) {
                importers.add(file);
            }
        }
        if (importers.isEmpty()) {
            return;
        }

        boolean suggested = manifest.has("suggests")
                && manifest.getAsJsonObject("suggests").has("cloth-config");
        assertTrue(suggested,
                "fabric.mod.json does not list \"cloth-config\" under \"suggests\", but " + importers.size()
                        + " source file(s) import Cloth Config (first: " + importers.get(0) + ")");
    }

    /** The class names registered under {@code entrypoints.modmenu}; either plain strings or {@code {"value": ...}}. */
    private static List<String> modMenuEntrypoints(JsonObject manifest) {
        List<String> names = new ArrayList<>();
        if (!manifest.has("entrypoints")) {
            return names;
        }
        JsonObject entrypoints = manifest.getAsJsonObject("entrypoints");
        if (!entrypoints.has("modmenu")) {
            return names;
        }
        for (JsonElement element : entrypoints.getAsJsonArray("modmenu")) {
            String value = element.isJsonObject()
                    ? element.getAsJsonObject().get("value").getAsString()
                    : element.getAsString();
            // "pkg.Class::method" names a method reference; the class is the part before it.
            int methodRef = value.indexOf("::");
            names.add(methodRef >= 0 ? value.substring(0, methodRef) : value);
        }
        return names;
    }

    private static Path findSource(List<Path> roots, String className) {
        // A nested class lives in its outermost class's file.
        int nested = className.indexOf('$');
        String outer = nested >= 0 ? className.substring(0, nested) : className;
        String relative = outer.replace('.', '/') + ".java";
        for (Path root : roots) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
