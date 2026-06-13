---
name: mc-mixin-craft
description: Write, debug, and maintain Mixin injections and access wideners for Fabric Minecraft mods. TRIGGER when creating or editing *Mixin.java, *Accessor.java, *Invoker.java, *.mixins.json, *.accesswidener files, or when discussing injection points, @Inject, @Redirect, @ModifyVariable, or @Overwrite.
---

The user is writing or modifying Mixin code in a Fabric mod. Apply this guidance whenever mixin classes, accessors, invokers, mixins.json, or access wideners are being touched.

## Mixin class structure

Mixin classes are abstract, never instantiated. The stub constructor satisfies the Java compiler only — Mixin does not merge constructors into the target class. Extend the target's parent class to get lexical access to protected fields:

```java
@Mixin(AnvilMenu.class)
abstract class AnvilMenuMixin extends ItemCombinerMenu {

    // Stub constructor — satisfies Java, never called
    private AnvilMenuMixin(@Nullable MenuType<?> type, int id, Inventory inv,
                           ContainerLevelAccess access) {
        super(type, id, inv, access);
    }

    @Inject(method = "createResult", at = @At("RETURN"))
    private void mymod$onCreateResult(CallbackInfo ci) {
        // Can access this.inputSlots, this.resultSlots, this.player from parent
    }
}
```

## Injection method naming

Always prefix injected method names with `modid$` to avoid collisions with other mods:

```java
private void mymod$onCreateResult(CallbackInfo ci) { ... }
```

## @Unique fields

Fields added to the mixin class that don't exist on the target must be annotated `@Unique` and prefixed with `modid$`:

```java
@Unique
@Nullable
private AnvilResult mymod$pendingResult;

@Unique
private boolean mymod$takingResult;
```

## Injection types — when to use each

### @Inject (default choice)
Inserts code at a specific point. Non-destructive — other mods' mixins can coexist.

```java
@Inject(method = "targetMethod", at = @At("HEAD"))
private void mymod$beforeTarget(CallbackInfo ci) { ... }

@Inject(method = "targetMethod", at = @At("RETURN"))
private void mymod$afterTarget(CallbackInfo ci) { ... }

@Inject(method = "targetMethod", at = @At("TAIL"))
private void mymod$atEnd(CallbackInfo ci) { ... }
```

- `HEAD` — before any code runs. Use for guards, early-exit checks.
- `RETURN` — before every `return` opcode. Fires on all exit paths (including early returns).
- `TAIL` — before the final `return` only. Use when you only care about normal completion.

### @Inject with cancellation
For methods returning void:
```java
@Inject(method = "targetMethod", at = @At("HEAD"), cancellable = true)
private void mymod$cancel(CallbackInfo ci) {
    if (shouldCancel) ci.cancel();
}
```

For methods returning a value:
```java
@Inject(method = "targetMethod", at = @At("HEAD"), cancellable = true)
private void mymod$override(CallbackInfoReturnable<ItemStack> cir) {
    if (shouldOverride) cir.setReturnValue(ItemStack.EMPTY);
}
```

### @Inject at INVOKE
Target a specific method call within the target method:
```java
@Inject(method = "targetMethod",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/world/item/ItemStack;isEmpty()Z"))
private void mymod$beforeIsEmpty(CallbackInfo ci) { ... }
```

The `target` uses JVM internal descriptor format: `L<owner>;<name>(<params>)<return>`.

### @Redirect
Replaces a single method call. Use sparingly — only one mod can redirect the same call site.

```java
@Redirect(method = "targetMethod",
          at = @At(value = "INVOKE",
                   target = "Lnet/minecraft/world/item/ItemStack;getCount()I"))
private int mymod$modifyCount(ItemStack stack) {
    return stack.getCount() * 2;
}
```

### @ModifyVariable
Modify a local variable's value:
```java
@ModifyVariable(method = "targetMethod", at = @At("STORE"), ordinal = 0)
private int mymod$modifyLocalVar(int original) {
    return original + 10;
}
```

### @ModifyArg
Modify a single argument to a method call:
```java
@ModifyArg(method = "targetMethod",
           at = @At(value = "INVOKE",
                    target = "Lsome/Class;someMethod(II)V"),
           index = 1)
private int mymod$modifySecondArg(int original) {
    return original * 2;
}
```

### @Overwrite (last resort)
Replaces the entire method body. Incompatible with all other mods' mixins on the same method. Only use when no injection point can achieve the goal.

## Re-entrancy guard pattern

When your mixin's RETURN hook triggers code that re-enters the same method (e.g., setting a slot triggers `slotsChanged` which calls `createResult` again), use a boolean guard:

```java
@Unique
private boolean mymod$takingResult;

@Inject(method = "onTake", at = @At("HEAD"))
private void mymod$beginTake(Player player, ItemStack stack, CallbackInfo ci) {
    this.mymod$takingResult = true;
}

@Inject(method = "onTake", at = @At("TAIL"))
private void mymod$endTake(Player player, ItemStack stack, CallbackInfo ci) {
    this.mymod$takingResult = false;
    // ... do post-take work here
}

@Inject(method = "createResult", at = @At("RETURN"))
private void mymod$dispatch(CallbackInfo ci) {
    if (this.mymod$takingResult) return; // skip re-entrant call
    // ... dispatch logic
}
```

## Accessor mixin (read-only field/method access)

When you need to read a private field or call a private method but don't need to inject code:

```java
@Mixin(AnvilMenu.class)
public interface AnvilMenuAccessor {
    @Accessor("cost")
    DataSlot mymod$getCost();

    @Accessor("repairItemCountCost")
    void mymod$setRepairItemCountCost(int value);
}
```

Usage: `((AnvilMenuAccessor) (Object) menu).mymod$getCost()`

## Invoker mixin (call-only for methods)

```java
@Mixin(LivingEntity.class)
public interface LivingEntityLootInvoker {
    @Invoker("dropFromLootTable")
    void mymod$invokeDropFromLootTable(ServerLevel level, DamageSource source, boolean killedByPlayer);
}
```

## mixins.json configuration

```json
{
    "required": true,
    "minVersion": "0.8",
    "package": "com.example.mymod.mixin",
    "compatibilityLevel": "JAVA_21",
    "mixins": [
        "AnvilMenuAccessor",
        "AnvilMenuMixin",
        "EnchantmentTableBlockMixin"
    ],
    "client": [],
    "injectors": {
        "defaultRequire": 1
    }
}
```

Key settings:
- **`"mixins"`** — server + common mixins (applied on both sides)
- **`"client"`** — client-only mixins (renderers, screens, particle factories)
- **`"defaultRequire": 1`** — crash on startup if any injection target is missing. Catches broken mixins immediately instead of silently failing.

Reference from `fabric.mod.json`:
```json
{
    "mixins": [
        "mymod.mixins.json"
    ]
}
```

## Access wideners

For fields/methods you need to access from normal (non-mixin) code, use an access widener instead of an accessor mixin:

File: `src/main/resources/mymod.accesswidener`
```
accessWidener v2 named

# Make a private field accessible
accessible field net/minecraft/world/inventory/EnchantmentMenu enchantSlots Lnet/minecraft/world/Container;

# Make a private method accessible
accessible method net/minecraft/client/gui/screens/EnchantmentScreen renderBook Lnet/minecraft/client/gui/GuiGraphics;IIF)V

# Make a final field mutable
mutable field net/minecraft/world/level/block/EnchantingTableBlock BOOKSHELF_OFFSETS Ljava/util/List;
```

Declare in `build.gradle`:
```groovy
loom {
    accessWidenerPath = file("src/main/resources/mymod.accesswidener")
}
```

### AW vs accessor mixin

| Use case | Prefer |
|----------|--------|
| Read a field from non-mixin code | Access widener |
| Read a field only inside a mixin class | Accessor interface |
| Call a method from non-mixin code | Access widener |
| Invoke a method only inside a mixin | Invoker interface |
| Modify a final field | AW with `mutable` |

AW is simpler and has no runtime overhead. Use accessor/invoker when you want to avoid widening the field globally (e.g., for a very targeted read inside one mixin).

## Target scope — inject into the narrowest class

Always target the most specific class possible. An overly broad target means your injection runs on every instance in the game, gated only by an `instanceof` check you have to remember to add.

**Broken — targets all block placements to intercept one item:**
```java
@Mixin(BlockItem.class) // fires for every block placed in the game
public class VillagerPlacementMixin {
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void mymod$onUseOn(UseOnContext ctx, CallbackInfoReturnable<InteractionResult> cir) {
        if (!(ctx.getItemInHand().getItem() instanceof PlayerHeadItem)) return;
        // ...
    }
}
```

**Fixed — targets only the relevant item class:**
```java
@Mixin(PlayerHeadItem.class) // fires only for player head placements
```

Same principle for entity mixins: don't inject into `LivingEntity.doPush` with an `instanceof Villager` check when you can inject into `Villager` directly.

## Cancellation scope — don't cancel more than you mean to

`ci.cancel()` on a method that bundles multiple state changes discards ALL of them. If you only need to suppress one aspect, use a targeted injection instead.

**Broken — cancelling `setVillagerData` to prevent profession change also discards level/type changes:**
```java
@Inject(method = "setVillagerData", at = @At("HEAD"), cancellable = true)
private void mymod$lockProfession(net.minecraft.world.entity.npc.VillagerData data, CallbackInfo ci) {
    if (isLocked && data.getProfession() != currentProfession) ci.cancel(); // kills the entire update
}
```

**Fixed — modify only the profession field, preserve everything else:**
```java
@ModifyVariable(method = "setVillagerData", at = @At("HEAD"), argsOnly = true)
private net.minecraft.world.entity.npc.VillagerData mymod$preserveProfession(
        net.minecraft.world.entity.npc.VillagerData data) {
    if (isLocked && data.getProfession() != currentProfession) {
        return data.setProfession(currentProfession); // only override profession
    }
    return data;
}
```

## Priority on shared targets

When multiple mixins target the same method on the same class (e.g., several `@Inject(method = "mobInteract", at = @At("HEAD"))` across different mixin classes), add explicit `priority` to control ordering. Without it, ordering depends on declaration order in `mixins.json`, which is fragile and breaks silently on refactoring.

```java
@Mixin(value = Villager.class, priority = 900) // runs before default (1000)
public class VillagerPickupMixin { ... }

@Mixin(value = Villager.class, priority = 1100) // runs after default
public class VillagerFollowMixin { ... }
```

Lower numbers run first. Document the rationale for ordering (e.g., "pickup must check before follow because it consumes the interaction").

## Exception-safe context patterns (HEAD + RETURN pairs)

When using HEAD/RETURN injection pairs to bracket a method (e.g., setting a ThreadLocal flag on entry, clearing on exit), `@At("RETURN")` only fires on normal returns — not on exceptions. If the target method can throw, the exit hook never fires and state leaks for the thread's lifetime.

**Broken — exit never fires if target throws:**
```java
@Inject(method = "doSomething", at = @At("HEAD"))
private void mymod$enter(CallbackInfo ci) {
    MyContext.enter(); // sets ThreadLocal = true
}

@Inject(method = "doSomething", at = @At("RETURN"))
private void mymod$exit(CallbackInfo ci) {
    MyContext.exit(); // never called if doSomething() throws
}
```

**Fix — @WrapMethod (Mixin Extras) gives a natural try/finally:**
```java
@WrapMethod(method = "doSomething")
private void mymod$wrapDoSomething(Operation<Void> original) {
    MyContext.enter();
    try {
        original.call();
    } finally {
        MyContext.exit();
    }
}
```

**Fix — without Mixin Extras:** Restructure the context to be self-resetting (e.g., check a tick counter or frame ID rather than a boolean flag), or move the try/finally into the calling code rather than the mixin. Vanilla Mixin has no clean exception-catch injection point.

### ThreadLocal cleanup

Always use `ThreadLocal.remove()` instead of `set(null)` or `set(false)`. `remove()` deletes the entry from the thread-local map entirely. `set()` retains a dead entry for the thread's lifetime — on pooled server threads, that's forever.

## Guardrails

- **Never** target a broad superclass (`BlockItem`, `LivingEntity`) when a narrower subclass (`PlayerHeadItem`, `Villager`) is the actual target. Broad targets fire on every instance in the game.
- **Never** use `ci.cancel()` on a method that bundles multiple state changes when you only want to suppress one. Use `@ModifyVariable` or `@Redirect` on the specific field/call instead.
- **Always** add explicit `priority` when multiple mixin classes target the same method on the same class. Document the ordering rationale.
- **Never** `@Overwrite` when `@Inject` can achieve the goal. Overwrites block all other mods.
- **Never** inject into lambda synthetic methods without verifying the exact method name in the decompiled source. Lambda names are compiler-generated and brittle.
- **Always** use `defaultRequire: 1` in `mixins.json`. Silent mixin failures are the hardest bugs to diagnose.
- **Always** prefix `@Unique` fields and injected methods with `modid$`.
- **Always** verify method descriptors against current Mojmap mappings when targeting a specific MC version. Signatures shift between versions.
- **Never** put client-only mixins in the `"mixins"` array — they will crash dedicated servers. Use the `"client"` array.
- **Never** merge mixin constructors — they are stubs. Put initialization logic in `@Inject(method = "<init>", ...)` if you need to init `@Unique` state.
- **Never** use HEAD + RETURN injection pairs to bracket a method without considering the exception path. If the target method can throw, the RETURN hook is skipped and state (ThreadLocals, boolean guards) leaks. Use `@WrapMethod` with try/finally, or restructure the context to be self-resetting.
- **Always** use `ThreadLocal.remove()` for cleanup, never `set(null)` or `set(false)`.

## Version notes

- **1.20.5+:** Many method signatures changed with the Data Components rewrite. `ItemStack.getTag()` no longer exists. Verify all `@At(target = ...)` descriptors.
- **1.21+:** Enchantment-related mixins need special care — `EnchantmentHelper` was rewritten to use `Holder<Enchantment>`.
