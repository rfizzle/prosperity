---
name: mc-onboarding
description: One-shot teaching moments for Fabric Minecraft mods — first-run onboarding lines gated on persisted per-player flags, burned only when actually shown, with keybind-resolving hints and toast+sound+chat milestones. TRIGGER when adding a first-time explanation, tutorial message, discovery hint, or milestone celebration: "show this once per player", a hint that names a keybind, a level/tier/unlock moment that should toast and play a sound, or a one-time message that repeats when it shouldn't.
---

The user is teaching the player something exactly once — a first-level-up
explanation, a "press this key" discovery hint, a milestone celebration.
Success has two halves: the line fires **once per player, ever** (not once
per session, not once per climb through a threshold), and it fires **through
the right channel** at the moment the player can act on it. Both halves fail
quietly: a repeated tutorial line reads as spam, and a one-shot that burned
itself without rendering is a tutorial the player never gets.

## Gate on persisted flags, never progress heuristics

The tempting gate — "show the intro when `level == 1`", "hint on the first
tier" — is a progress heuristic, and progress is not monotonic. Any mod with
decay, death penalties, or resets will walk a player back through the same
boundary, and the heuristic re-fires the "one-time" line every climb.

Gate on a **persisted per-player boolean** instead, stored in the mod's
saved player state (see **mc-persistence** for where that state lives and
how it saves). Expose it as a `hasSeenX()` / `markXSeen()` pair:

```java
public boolean hasSeenLevelUpIntro(UUID uuid) {
    return getPlayerData(uuid).seenLevelUpIntro;
}

/** Idempotent; only dirties the save on the first call. */
public void markLevelUpIntroSeen(UUID uuid) {
    PlayerData pd = getPlayerData(uuid);
    if (!pd.seenLevelUpIntro) {
        pd.seenLevelUpIntro = true;
        setDirty();
    }
}
```

Persist with write-only-when-set discipline: default `false`, and only
write the NBT key once the flag is set, so a save where no player has ever
hit the moment stays byte-identical to one written before the flag existed.
On load, `tag.getBoolean(...)` reads a missing key as `false` for free.

```java
// save()
if (pd.seenLevelUpIntro) {
    playerTag.putBoolean(NBT_SEEN_LEVEL_UP_INTRO_KEY, true);
}
// load()
pd.seenLevelUpIntro = playerTag.getBoolean(NBT_SEEN_LEVEL_UP_INTRO_KEY);
```

## Burn the flag only when the line actually rendered

Do not mark the flag where you *decide* to show the line — mark it where the
line is *actually sent*. Message paths have degenerate branches (a cap
message replacing the normal one, a channel suppressed by config, an empty
headline), and if one of those swallows the sentence while the flag still
burns, the player has spent their one-shot on nothing. Have the send method
report whether the teaching line made it out:

```java
boolean wantIntro = !state.hasSeenLevelUpIntro(player.getUUID());
boolean introShown = sendLevelUpMessage(player, newLevel, wantIntro);
if (introShown) {
    state.markLevelUpIntroSeen(player.getUUID());
}
```

```java
/** Returns true only when the one-time teaching sentence was appended. */
private static boolean sendLevelUpMessage(ServerPlayer player, int newLevel, boolean showIntro) {
    boolean cappedNow = newLevel >= maxLevel;   // degenerate path: cap line replaces intro
    MutableComponent message = cappedNow
            ? Component.translatable("message.mymod.level_max")
            : Component.translatable("message.mymod.level_up", newLevel);
    boolean introShown = showIntro && !cappedNow;
    if (introShown) {
        message = message.append(CommonComponents.SPACE)
                .append(Component.translatable("message.mymod.level_up_intro"));
    }
    player.sendSystemMessage(message.withStyle(ChatFormatting.GREEN));
    return introShown;
}
```

The unburned flag means the intro simply rides the *next* qualifying moment
instead of vanishing.

## Discovery hints: name keybinds with Component.keybind

A hint that says "press V" is wrong for every player who rebound V. Pass
`Component.keybind` as a translation argument — the *client* resolves it to
the player's actual current binding at render time, so the server never
needs to know (and cannot know) what the key is:

```java
player.sendSystemMessage(Component.translatable(
        "message.mymod.detail_hint",
        Component.keybind("key.mymod.peek_detail").withStyle(ChatFormatting.GOLD))
        .withStyle(ChatFormatting.YELLOW));
state.markDetailHintSeen(player.getUUID());
```

This is how you advertise a hold-to-peek HUD panel (see **mc-hud**) or any
client keybind the player would otherwise never discover. Fire the hint at
the first moment the panel has something worth peeking at — not at login,
when it competes with join noise and means nothing yet.

## Milestone moments: toast + sound + chat, sent to one player

A milestone worth celebrating (tier crossed, system unlocked) bundles three
channels that land on the same tick: an advancement grant for the toast, a
sound sting, and a chat line carrying the actual information.

```java
MyModCriteria.TIER_REACHED.trigger(player, newTier);   // advancement → toast
player.connection.send(new ClientboundSoundPacket(
        MyModSounds.TIER_UP, SoundSource.PLAYERS,
        player.getX(), player.getY(), player.getZ(),
        1.0f, 1.0f, player.getRandom().nextLong()));
player.sendSystemMessage(Component.translatable("message.mymod.tier_up", newTier)
        .withStyle(ChatFormatting.RED));
```

The sound goes **straight to the player's connection** because both obvious
alternatives are wrong: `Level#playSound(null, …)` broadcasts to everyone
nearby, turning one player's milestone into an area-wide mystery noise, and
`Player#playNotifySound` is a client-only no-op on a dedicated server. The
advancement tree doubles as the mod's other tutorial channel — its
descriptions restate what each milestone means, browsable after the moment
has scrolled away (criteria and tree design live in **mc-advancements**).

## One concept per moment, escalating channels

Each teaching line carries exactly one idea; a second sentence halves the
retention of both. Spread concepts across the progression instead, and match
the channel to the weight of the moment: a routine event gets a plain chat
line, a discovery hint gets a styled chat line with the resolved keybind, a
milestone gets the full toast + sound + chat bundle. Every teaching string
is a `Component.translatable` key in the lang file — never a literal — so
the tutorial localizes like everything else.

## Guardrails

- Never gate a one-time message on level, tier, stat, or any other progress
  value. Progress goes down as well as up; only a persisted flag survives a
  decay-and-reclimb without repeating.
- Never mark the seen-flag on a code path where the sentence might not have
  rendered. Burn it from the send site's return value, not the decision site.
- Never hardcode a key name ("press V") in a hint. `Component.keybind`
  resolves the player's real binding client-side.
- Never play a milestone sound with `Level#playSound(null, …)` (broadcasts
  to bystanders) or `playNotifySound` (silent on dedicated servers). Send a
  `ClientboundSoundPacket` on `player.connection`.
- `markXSeen` must be idempotent and only `setDirty()` on the first call;
  persist flags write-only-when-set so untouched saves stay byte-identical.
- Teaching lines are translatable keys, one concept each. If a moment needs
  two sentences, it is two moments.
