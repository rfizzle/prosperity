package com.rfizzle.concord.guard;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bans calls to the vanilla gametest factory that is deprecated for removal.
 * HUB-OWNED: propagated verbatim from {@code rfizzle/concord}; never edit it in a member.
 *
 * <p>{@code GameTestHelper#makeMockServerPlayerInLevel} is {@code @Deprecated(forRemoval = true)}
 * in 1.21.1 with no vanilla replacement, so every call site becomes a hard compile break on the next
 * Minecraft bump. The shared {@code com.rfizzle.concord.gametest.MockPlayers} replicates it from
 * public APIs; tests call that instead. Only a file named {@code MockPlayers.java} may mention the
 * vanilla method (the helper documents what it replaces).
 *
 * <p>Input: {@code concord.sourceDirs.all}, set by {@code gradle/concord-common.gradle}.
 */
class DeprecatedMockPlayerTest {

    /** Split so this file's own source never contains the name it bans elsewhere. */
    private static final String DEPRECATED_FACTORY = "make" + "MockServerPlayerInLevel";

    private static final Pattern CALL = Pattern.compile("\\b" + DEPRECATED_FACTORY + "\\b");

    @Test
    void noCallToTheDeprecatedMockServerPlayerFactory() {
        List<Path> roots = GuardSupport.directories("concord.sourceDirs.all");
        assertTrue(!roots.isEmpty(), "no source directories resolved — the guard would walk nothing");

        List<String> offenders = new ArrayList<>();
        for (Path file : GuardSupport.javaFiles(roots)) {
            if (file.getFileName().toString().equals("MockPlayers.java")) {
                continue;
            }
            if (CALL.matcher(GuardSupport.code(file)).find()) {
                offenders.add(file.toString());
            }
        }
        offenders.sort(String::compareTo);

        assertTrue(offenders.isEmpty(),
                DEPRECATED_FACTORY + " is deprecated for removal; use "
                        + "com.rfizzle.concord.gametest.MockPlayers.serverPlayerInLevel(helper) instead. "
                        + offenders.size() + " file(s) still call it: " + offenders);
    }
}
