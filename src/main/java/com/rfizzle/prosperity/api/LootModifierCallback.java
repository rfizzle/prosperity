package com.rfizzle.prosperity.api;

// TODO: event registration via EventFactory per SPEC §4
@Stable
@FunctionalInterface
public interface LootModifierCallback {
    void modify(LootModifierContext context);
}
