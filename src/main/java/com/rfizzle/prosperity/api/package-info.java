/**
 * Prosperity's public API — the only package other mods should reference.
 *
 * <p>Everything in this package is <strong>stable</strong> per the
 * <a href="https://github.com/rfizzle/concord/blob/master/API-STANDARD.md">Concord
 * API Standard v1</a>: signatures here survive minor and patch releases, and a
 * breaking change requires a major version bump plus a changelog entry naming
 * the broken signature. Everything <em>outside</em> this package — attachments,
 * loot internals, mixins, networking — is internal and may change without notice.
 *
 * <p>Every type here carries the suite's stability marker,
 * {@link com.rfizzle.prosperity.api.Stable}. It is a local annotation rather
 * than {@code @ApiStatus.Stable} (which has no member in
 * {@code org.jetbrains.annotations}); per the suite's no-shared-jar rule each
 * mod declares its own marker in its {@code api} package.
 *
 * <p>The surface is the loot-modifier hook: register a
 * {@link com.rfizzle.prosperity.api.LootModifierCallback} on its {@code EVENT} to
 * adjust the {@link com.rfizzle.prosperity.api.LootModifierContext} during loot
 * generation. Consume as a soft dependency only: compile against the mod with
 * {@code modCompileOnly} and guard every call site with
 * {@code FabricLoader.getInstance().isModLoaded("prosperity")}.
 */
package com.rfizzle.prosperity.api;
