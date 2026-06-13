---
name: mc-tooltips
description: Correct placement and ordering of item/block tooltip text in Fabric Minecraft mods — appendHoverText vs ItemTooltipCallback, and why your lines must sit above third-party footers. TRIGGER when adding or editing tooltip text on items or blocks: overriding appendHoverText, registering an ItemTooltipCallback, building tooltip lines with Component.translatable/literal, or when a tooltip line appears in the wrong order (e.g. below a recipe-viewer "mod name" footer).
---

The user is adding or reordering text on an item or block tooltip. Getting the
**position** right is the whole job: there are two injection points, they fire
at different times, and choosing the wrong one makes your text land in the
wrong place — most visibly *below* the recipe-viewer mod-name footer instead of
above it.

## How a tooltip is assembled (1.21.x)

`ItemStack.getTooltipLines(context, player, flag)` builds the list top-to-bottom
in this fixed order:

1. **Hover name** (display name, rarity-coloured) — index 0
2. **`Item.appendHoverText(...)`** — your custom item description, gated behind
   the `HIDE_ADDITIONAL_TOOLTIP` component
3. **Component tooltips**, in order: `TRIM`, `STORED_ENCHANTMENTS`,
   `ENCHANTMENTS`, `DYED_COLOR`, **`LORE`**, attribute modifiers, `UNBREAKABLE`,
   `CAN_BREAK` / `CAN_PLACE_ON`
4. **Advanced info** (only with F3+H): durability, registry id, component count

Then, *after* the vanilla list is complete, Fabric fires:

5. **`ItemTooltipCallback.EVENT`** — every registered callback, in registration
   order, each handed the finished list

The single most important consequence: **`appendHoverText` runs in step 2
(early); every `ItemTooltipCallback` runs in step 5 (last).** Recipe viewers
(JEI, REI, EMI) add their "source mod" footer via an `ItemTooltipCallback`, so
that footer is appended at the very end. Anything you append in your *own*
callback lands next to theirs — and which side depends on non-deterministic
registration order.

## Decision: which injection point?

**You own the item or block → override `appendHoverText`.** This is the
idiomatic, deterministic placement. Your text renders in step 2, guaranteed
above lore, enchantments, advanced info, and every step-5 callback — including
the mod-name footer. This is how vanilla and well-behaved mods do it.

```java
public class ExtractionTomeItem extends Item {
    public ExtractionTomeItem(Properties properties) { super(properties); }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                               List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("info.mymod.extraction_tome")
                .withStyle(ChatFormatting.GRAY));
    }
}
```

**You own a *block* → you still need `appendHoverText`, but on a custom
`BlockItem`.** A held block's tooltip text comes from `BlockItem.appendHoverText`,
not the `Block`. The default `BlockItem` adds nothing, so register the block's
item as your subclass instead of `new BlockItem(...)`:

```java
public class ShelfBlockItem extends BlockItem {
    public ShelfBlockItem(Block block, Properties props) { super(block, props); }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                               List<Component> tooltip, TooltipFlag flag) {
        // stat / description lines here — runs in step 2, above any footer
    }
}
// registration:
Registry.register(BuiltInRegistries.ITEM, id, new ShelfBlockItem(block, props));
```

**You do *not* own the item (vanilla or another mod's), or you must position
relative to dynamic lines → use `ItemTooltipCallback`.** This is the only hook
that can touch items you didn't register. Legitimate uses: recolouring an
enchantment line, inserting an enchantment description right after its name,
hiding a line. When you use it, **never blindly append if position matters** —
compute an insertion index instead:

```java
ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
    // insert right after the matching enchantment line, not at the end
    for (int i = lines.size() - 1; i >= 0; i--) {
        if (lines.get(i).getString().equals(enchantName)) {
            lines.add(i + 1, descLine);
            break;
        }
    }
});
```

Note `lines.add(index, component)` — index 0 is the name; index 1 is just below
it. You still cannot guarantee placement relative to *another mod's* callback
output, because callback order is registration-dependent. If you need a hard
guarantee, you must own the item and use `appendHoverText`.

## The footer rule

The blue-italic source-mod footer belongs at the **very bottom**, by
convention. Don't try to push it down or replicate it — just put your own lines
in via `appendHoverText` so they naturally sit above it. If your descriptive
text renders *above* the footer, you're correct; if it renders *below*, you
added it through a step-5 callback when you should have used `appendHoverText`.

> Multiple recipe viewers loaded at once (e.g. JEI + REI + EMI together in a dev
> `modLocalRuntime` set) each append their **own** footer, so you'll see the mod
> name two or three times in the dev client. That is a dev-runtime artifact, not
> a bug in your mod — a shipped install runs a single viewer and shows one
> footer. Don't chase it.

## Version notes

- **1.20.5+ / 1.21.x:** `appendHoverText`'s second parameter is
  `Item.TooltipContext` (not `Level`). Lore lives in `DataComponents.LORE`, not
  NBT. Use `HIDE_ADDITIONAL_TOOLTIP` to suppress `appendHoverText` output and
  `HIDE_TOOLTIP` to suppress the whole tooltip.
- Mojang mappings: `appendHoverText`, `TooltipFlag`, `Component`. (Yarn names
  differ — `appendTooltip`, `TooltipContext`.)

## Guardrails

- Never add **item-specific descriptive text** through an `ItemTooltipCallback`
  when you own the item. It pushes recipe-viewer footers above your text and the
  ordering is non-deterministic. Use `appendHoverText`.
- For blocks, register a custom `BlockItem` subclass — overriding the `Block`
  does nothing for the held-item tooltip.
- In a callback, prefer a computed insertion index over `lines.add(...)` when
  the line's position carries meaning.
- Don't fight or duplicate the source-mod footer; structure your lines to sit
  above it instead.
- Keep styling consistent: descriptive lines `GRAY`, section headers a single
  accent colour. Match the rest of the mod.
