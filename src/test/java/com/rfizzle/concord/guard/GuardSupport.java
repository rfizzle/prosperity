package com.rfizzle.concord.guard;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Shared plumbing for the hub-owned guard tests in this package. HUB-OWNED: propagated verbatim
 * from {@code rfizzle/concord} ({@code propagate/src/test/java/...}); never edit it in a member.
 *
 * <p>Every input arrives as a system property that {@code gradle/concord-common.gradle} sets on the
 * {@code test} task, as a project-relative path (the test JVM runs in the project directory). A
 * missing property fails loudly: a guard that silently walks nothing passes while enforcing
 * nothing.
 */
final class GuardSupport {

    private GuardSupport() {
    }

    /** A required system property, failing with the fix when {@code concord-common.gradle} is not applied. */
    static String property(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new AssertionError("system property '" + name + "' is not set — the test task must "
                    + "come from a build.gradle that applies gradle/concord-common.gradle");
        }
        return value;
    }

    static Path path(String propertyName) {
        return Path.of(property(propertyName));
    }

    /** The existing directories named by a path-separated property. */
    static List<Path> directories(String propertyName) {
        List<Path> dirs = new ArrayList<>();
        for (String entry : property(propertyName).split(File.pathSeparator)) {
            Path dir = Path.of(entry);
            if (Files.isDirectory(dir)) {
                dirs.add(dir);
            }
        }
        return dirs;
    }

    static JsonObject readJson(Path file) {
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new AssertionError("could not read " + file, e);
        }
    }

    /** Every {@code .java} file under the given roots. */
    static List<Path> javaFiles(List<Path> roots) {
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> tree = Files.walk(root)) {
                tree.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java")).forEach(files::add);
            } catch (IOException e) {
                throw new AssertionError("could not walk " + root, e);
            }
        }
        return files;
    }

    /** Source text with comments blanked out, so prose and commented-out code never count. */
    static String code(Path javaFile) {
        try {
            return stripComments(Files.readString(javaFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError("could not read " + javaFile, e);
        }
    }

    /**
     * Removes {@code //} and {@code /* *}{@code /} comments, leaving string and character literals
     * intact (a {@code "https://..."} literal is not a comment).
     */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i < n && !(source.charAt(i) == '*' && i + 1 < n && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i += 2;
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < n && source.charAt(i) != c) {
                    if (source.charAt(i) == '\\' && i + 1 < n) {
                        out.append(source.charAt(i));
                        i++;
                    }
                    out.append(source.charAt(i));
                    i++;
                }
                if (i < n) {
                    out.append(c);
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
