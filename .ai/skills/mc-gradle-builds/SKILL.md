---
name: mc-gradle-builds
description: Best practices for running Gradle builds, tests, gametests, and coverage in Fabric Minecraft mods. Prevents wasted reruns from partial output capture; documents the hub-owned gradle/concord-common.gradle every member applies (version, gametest/datagen wiring, merged unit+gametest JaCoCo report, packaging excludes). TRIGGER when running any Gradle command (build, test, runGametest, runDatagen, assemble, check), when inspecting build output/reports, when wiring or reading JaCoCo coverage, or when editing repositories/dependencies in build.gradle.
---

The user is running a Gradle build, test, or gametest in a Fabric mod. Follow these rules to avoid losing output and forcing expensive reruns.

## Why this skill exists

Gradle builds are slow (compilation, bootstrap, gametest server spin-up). Partial-read shell commands on build output lose critical information and force full reruns. Every rerun costs minutes.

## Rule 1: Never use partial-read shell commands on build output

**Banned patterns:**
- `tail -n N` / `tail -f` on build logs or piped Gradle output
- `head -n N` on build logs
- `grep` as the first pass on build output (fine after you have the full picture)
- Piping Gradle through `awk`, `sed`, or `cut`

These discard context you will need. A `tail -20` on a failed build misses the compilation error 200 lines up.

## Rule 2: Capture full output

**Preferred — direct Bash with timeout:**
```bash
./gradlew test 2>&1
```
Set `timeout: 600000` (10 minutes) on the Bash call for slow builds.

**For long builds — background with tee:**
```bash
./gradlew test 2>&1 | tee /tmp/mymod-test-output.txt
```
Use `run_in_background: true`. When notified of completion, `Read` the output file.

## Rule 3: Read reports instead of re-running

After a test run completes, reports are on disk:

| Report | Path |
|--------|------|
| HTML test report | `build/reports/tests/test/index.html` |
| JUnit XML results | `build/test-results/test/*.xml` |
| Gametest XML | Path set by `-Dfabric-api.gametest.report-file` |
| Merged coverage (unit + gametest) | `build/reports/jacoco/jacocoMergedReport/html/index.html` |
| Build failure log | Full console output from Bash call |

Use the `Read` tool to inspect reports — never re-run just to see results.

## Rule 4: If output is truncated, read the log — don't rerun

1. Check if piped to a file (e.g., `/tmp/mymod-test-output.txt`) — `Read` it.
2. Check HTML/XML reports under `build/`.
3. Only rerun as a last resort, and pipe to a file first.

## Rule 5: When a build fails

1. Read the **full** error output first.
2. If truncated, read report files under `build/`.
3. Fix the issue.
4. Rerun **only the specific failing task** (`:test` not `:build` if only tests failed).
5. For compilation errors, run `:compileJava` alone first.

## Gradle command patterns

```bash
# Full build (compile + test + jar)
./gradlew build 2>&1

# Unit + fabric-loader-junit tests only
./gradlew test 2>&1

# Gametest (starts a server — slower)
./gradlew runGametest 2>&1

# Data generation
./gradlew runDatagen 2>&1

# Single test class
./gradlew test --tests "com.example.mymod.SomeTest" 2>&1

# Single test method
./gradlew test --tests "com.example.mymod.SomeTest.specificMethod" 2>&1

# Clean + build (when class files are stale)
./gradlew clean build 2>&1

# Compile only (fast check before full test suite)
./gradlew compileJava 2>&1

# Compile client source set
./gradlew compileClientJava 2>&1

# Verify datagen is idempotent
./gradlew verifyDatagenIdempotent 2>&1
```

## The shared build logic: `gradle/concord-common.gradle`

Everything in a member's `build.gradle` that is not specific to that mod lives in one
hub-owned file, `gradle/concord-common.gradle`. It is propagated verbatim into every
member by the `concord-sync` PR (it is `propagate/gradle/concord-common.gradle` in the
hub), so a fix to it is one hub commit plus eight auto-merged sync PRs instead of eight
hand-written ones. **Never edit it in a member** — the next sync overwrites the edit.
Change it in `rfizzle/concord`.

A member applies it after `plugins {}` and before `dependencies {}`:

```groovy
plugins {
    id 'fabric-loom' version "${loom_version}"
    id 'jacoco'
}

apply from: 'gradle/concord-common.gradle'

repositories { /* only what the shared base does not cover */ }

loom {
    accessWidenerPath = file("src/main/resources/mymod.accesswidener")   // if it has one
}

dependencies { /* the mod's own */ }

// mod-specific tasks, extra test inputs, ...
```

It is keyed on `archives_base_name` in `gradle.properties` (`mod_id` overrides it if a
member ever needs a different id) and assumes the Java package is `com.rfizzle.<id>`.

What the hub file **owns**:

| Block | What it does |
|---|---|
| Version | `version = computeModVersion()` from `git describe` (bounded by a 10s timeout that warns), `group`, and `printVersion` — see "SemVer from the pushed tag" |
| Repositories | The shared base only: `mavenCentral()`, Shedaniel (cloth), Modrinth, Bai |
| Loom | `splitEnvironmentSourceSets()`, the `datagen` and `gametest` run configs, the `gametest` source set and its `gametestImplementation`/`gametestRuntimeOnly` configurations, `src/main/generated` as a resource root |
| `runGametest` | The JaCoCo agent, the always-execute pin, and an `onlyIf` that skips the run until a real `@GameTest` exists; sets `-Dconcord.modid` for the shared `MockPlayers` |
| `test` | `useJUnitPlatform()`, the `testRuntimeClasspath` fabric-api exclusion, the `concord.*` system properties and declared inputs the shared guard tests read (see `mc-mod-testing`) |
| Coverage | `jacocoTestReport` and `jacocoMergedReport` — see "Coverage" |
| Datagen | `cleanDatagenCache`, `verifyDatagenIdempotent` (with `--no-optional-locks`) — see the `mc-datagen` skill |
| Packaging | `JavaCompile` release, `java { withSourcesJar() }`, the `fabric.mod.json` version expand, the `LICENSE` rename, and `**/.gitkeep` + `**/.cache` excludes on `processResources`, `processClientResources`, `jar`, and `sourcesJar` |

What the member **keeps**: `plugins {}` (it must declare `fabric-loom` and `jacoco` — the
file fails fast with a message if they are missing), `dependencies {}`, any repository
beyond the base (TerraformersMC, BlameJared, Architectury, FTB, the GitHub-release ivy
repo, ...), `accessWidenerPath`, and every mod-specific task or test input. The member
configures the same tasks the hub file did (`test { inputs.files(...) }`,
`repositories { maven { ... } }`); Gradle merges the blocks. Opt-in wiring stays per member
too — for example the "client output on the test classpath" lines below, which some members
deliberately omit.

Rules that follow from the file being shared:

- **A fix is a hub PR.** If a member needs something the file does not do, the answer is
  a change to the file that every member can take, not a local fork of the block.
- **No `Task.project` at execution.** Gradle 10 removes it. Capture every project value
  into a local at configuration time (`def resolvedVersion = project.version.toString()`)
  and use the local inside `doLast`, `filesMatching`, `rename`, and any closure whose
  delegate is a task. Reading `project.foo` inside
  `tasks.withType(JavaCompile).configureEach { ... }` counts: the delegate is the task.
  `./gradlew build --warning-mode=all` surfaces any that slip in; `mod-ci.yml` passes it.
- **Mod id literals.** The only place the id appears in the shared file is
  `archives_base_name`. A member `build.gradle` should contain no mod-id literal beyond
  `accessWidenerPath`.

## Fabric Loom configuration

`gradle/concord-common.gradle` owns the Loom wiring: `splitEnvironmentSourceSets()`
(separating `main/` common code from `client/`), the `datagen` and `gametest` run configs,
the `gametest` source set with its own configurations, and `src/main/generated` as a
resource root. The member adds only what is its own:

```groovy
loom {
    accessWidenerPath = file("src/main/resources/mymod.accesswidener")
}
```

The gametest source set carries its own `src/gametest/resources/fabric.mod.json` declaring a
`<modid>-gametest` mod, which is where the `fabric-gametest` entrypoints belong — putting them
in the shipped manifest breaks every dev run set. See `mc-mod-testing`, "Registering the suite,"
for the manifest and the literal-version requirement that comes with it.

## Coverage: merge unit-test and gametest execution data

JaCoCo's default `jacocoTestReport` instruments only the `test` task, so code
exercised exclusively in-game — event handlers, commands, registration, anything
a gametest drives — reads as 0% even when it is thoroughly gametested. The
merged report is the mod's real coverage number; read per-package coverage
from it, never from the unit-test-only report.

`gradle/concord-common.gradle` wires all of it. The member declares `id 'jacoco'` in
`plugins {}` and nothing else. What the hub file does:

- Pins `jacoco { toolVersion = '0.8.12' }`. The pin keeps the number comparable across
  members and reproducible between a local run and CI; an unpinned JaCoCo tracks whatever
  the Gradle version ships.
- Attaches the agent to `runGametest` (Loom's run task is a `JavaExec`, so the task
  extension applies), with an `includes` filter of `com.rfizzle.<id>.*` so Minecraft and
  Fabric classes are not instrumented, and pins `outputs.upToDateWhen { false }`. The agent's
  exec file is an `@OutputFile`, which would otherwise make the run up-to-date-checkable and
  silently skip the whole in-world suite on a repeat run, re-emitting the previous coverage
  number as fresh. The task is matched lazily by name because Loom registers `runGametest`
  during `afterEvaluate`.
- Registers `jacocoMergedReport`, merging `build/jacoco/test.exec` and `runGametest.exec`
  over `src/main`. It `dependsOn classes` (without it, `gradlew jar jacocoMergedReport` fails
  implicit-dependency validation), `mustRunAfter` the two sweeps (ordering only, so it can run
  from existing exec data without forcing a server spin-up), and derives its class
  directories lazily from `sourceSets.main.output` (the eager `classDirectories.files.collect`
  form resolves at configuration time and discards the dependency on `classes`).
- Warns, without failing, when an exec file is missing, empty, or older than the compiled
  classes. Each of those is a partial number under the "merged" label: JaCoCo keys execution
  data by class id, so a stale exec contributes nothing and recompiled classes read 0%.
- Excludes from the measured classes:
  - **Mixins** (`com/rfizzle/<id>/mixin/**`). Their bodies execute inside the transformed
    vanilla classes, so the agent can never attribute coverage to the mixin class files.
    Mixins stay thin (see `mc-mixin-craft`), so nothing measurable hides.
  - **Datagen providers.** They run only under `runDatagen`, never under `test` or
    `runGametest`, so they sit in the denominator as permanently uncovered (a quarter to
    two-fifths of a member's missed lines before the exclusion). The convention: anything under
    a `datagen` package, plus any `*DataGenerator*` or `*Provider*` class inside the `data`
    package. The rule is scoped to `data/` on purpose: the compat packages hold runtime
    Jade/WTHIT `*Provider` classes, and `data/` also holds runtime persistence classes
    (`PlayerData`, `*Attachments`, `*Data` records) that must stay measured. **Put datagen
    classes in the `data` package, named `*DataGenerator` or `*Provider`** (or in a `datagen`
    package) and they are excluded; name a runtime class in `data/` anything with `Provider`
    in it and it silently drops out of the number.

```bash
./gradlew test runGametest jacocoMergedReport 2>&1   # or: make coverage
```

The report lands in `build/reports/jacoco/jacocoMergedReport/` (HTML + XML). What counts
toward the number, which residual misses are acceptable, and the target live in the
`mc-mod-testing` skill's coverage-accounting section.

## Dependency scoping

| Scope | What it means | Use for |
|-------|---------------|---------|
| `modImplementation` | Compile + runtime, bundled in jar | Core deps (Fabric API) |
| `modCompileOnly` | Compile only, NOT in jar | Optional compat (JEI, EMI, Jade, Trinkets) |
| `modLocalRuntime` | Dev runtime only, NOT in jar | Full mod jar for dev testing (EMI, Jade) |
| `include` | Jar-in-Jar, embedded in your mod jar | Small required libraries |

Pattern for optional compat:
```groovy
modCompileOnly "dev.emi:emi-fabric:${project.emi_version}:api"
modLocalRuntime "dev.emi:emi-fabric:${project.emi_version}"
```

## Dependency-repository hygiene

### Scope every third-party repository

The shared base (`mavenCentral()`, Shedaniel for `me.shedaniel.cloth`, Modrinth, Bai) comes
from `gradle/concord-common.gradle`. Declare in the member's own `repositories {}` only what
the base does not cover, with the same rule: every repository beyond `mavenCentral()` gets a `content { includeGroup ... }` block. Without it, Gradle probes every repo for every artifact — resolution is slower and non-deterministic, and any repo can answer for (and shadow) a group it doesn't own:

```groovy
repositories {
    mavenCentral()
    maven {
        name = 'TerraformersMC'
        url = 'https://maven.terraformersmc.com/'
        content { includeGroup 'dev.emi' }
    }
    maven {
        name = 'Modrinth'
        url = 'https://api.modrinth.com/maven'
        content { includeGroup 'maven.modrinth' }
    }
}
```

One repo per block, one `includeGroup` per group it actually serves. A repo with no content filter is a review flag.

### Suite siblings from GitHub release jars

Concord sibling mods that aren't publicly resolvable on a Maven repo resolve straight from their GitHub release jars via an artifact-only ivy repository. `metadataSources { artifact() }` skips POM/ivy metadata (there is none), and the content filter pins it to the owner group so it can never shadow a real Maven group:

```groovy
ivy {
    name = 'GitHubReleases'
    url = 'https://github.com'
    patternLayout {
        artifact '/[organisation]/[module]/releases/download/v[revision]/[module]-[revision].jar'
    }
    metadataSources { artifact() }
    content { includeGroup '<owner>' }
}
```

Then consume the sibling as `<owner>:<repo>:<version>`:

```groovy
// Sibling Concord mod — soft dependency, compile-only against its api package behind
// isModLoaded guards, never bundled. Swap to maven.modrinth once publicly resolvable.
modCompileOnly "<owner>:sibling-mod:${project.sibling_mod_version}"
```

### `transitive = false` — always with the reason next to it

Disable transitives per-dependency, never globally, and say why on the line:

```groovy
// JEI — the `fabric` runtime jar shades in all `common-api` classes, so `transitive = false`
// prevents Loom remap from seeing the same classes twice ("duplicate input class" warnings).
modCompileOnly "mezz.jei:jei-${mc}-fabric-api:${project.jei_version}"
modLocalRuntime("mezz.jei:jei-${mc}-fabric:${project.jei_version}") { transitive = false }

// FTB Teams / FTB Library — `transitive = false` keeps FTB's own fabric-api/loader/
// architectury pins from shadowing the versions pinned above.
modCompileOnly("dev.ftb.mods:ftb-teams-fabric:${project.ftb_teams_version}") { transitive = false }
modCompileOnly("dev.ftb.mods:ftb-library-fabric:${project.ftb_library_version}") { transitive = false }
```

### The soft-dep rule

The compile surface of a soft dep is its **API artifact** via `modCompileOnly`; the full jar appears only as `modLocalRuntime` (and only when you need it in the dev client — a pure server-side soft dep needs no dev runtime at all). Nothing optional is ever `modImplementation` or `include`d. Document WHY next to each non-obvious dependency line — what integration it serves, why the scope, why any `transitive = false`. See the mc-compat skill for the recipe-viewer (EMI/REI/JEI) instances of this pattern.

## Version management

Externalize all versions — never inline a version literal in `build.gradle`. Two
files hold them, split by ownership:

**Suite toolchain pins — `versions-common.properties` (concord-owned).** The
Minecraft target and build toolchain are identical across every Concord mod, so
they live in one concord-owned file synced into each member by the concord-sync
PR. A suite-wide bump (new Minecraft, new Loom) is one commit in concord; the
sync PR carries it to every member for its CI to validate before merge. Do not
hand-edit this file in a member repo — bump it in concord.
```properties
minecraft_version=1.21.1
loader_version=0.16.10
fabric_version=0.116.1+1.21.1
loom_version=1.9-SNAPSHOT
java_version=21
```

**Per-repo values — `gradle.properties`.** Everything a mod varies on its own:
```properties
# Local/dev base only — releases derive the version from the pushed tag.
mod_version=0.0.0
maven_group=com.rfizzle
archives_base_name=<modid>
# Per-mod integration pins (soft deps): EMI, REI, JEI, Jade, sibling mods, …
emi_version=1.1.22+1.21.1
# Gradle daemon / JVM tuning is per-repo (memory, WSL2 launch workarounds).
org.gradle.jvmargs=-Xmx3g -XX:+UseParallelGC
```

### Consuming the suite pins

Gradle auto-loads `gradle.properties` but not `versions-common.properties`, so
load it in `settings.gradle` before any project is configured — then
`build.gradle` reads the pins as ordinary project properties, unchanged:
```groovy
// Concord suite toolchain — concord-owned, synced via the concord-sync PR.
def suitePins = new Properties()
def suiteFile = file('versions-common.properties')
if (suiteFile.exists()) { suiteFile.withInputStream { suitePins.load(it) } }
gradle.rootProject {
    suitePins.each { key, value -> ext[key as String] = value }
}
```
`gradle.rootProject { … }` runs when the root project is created, before
`build.gradle` is evaluated, so the pins are set in time for the `plugins {}`
block (`id 'fabric-loom' version "${loom_version}"`) and the `dependencies` block
(`"com.mojang:minecraft:${project.minecraft_version}"`, etc.).

Adopt it as one PR per member, on top of the merged sync PR that provides the
file: add the loader above and delete the five toolchain keys from
`gradle.properties` in the same commit — keep a key in only one file so there's
no ambiguity about which value the build uses. `toolchain-drift.yml` in concord
reports any member still pinning a toolchain key to a stale value.

`gradle/concord-common.gradle` injects the version into `fabric.mod.json` (a captured
`resolvedVersion`, declared as a `processResources` input) and excludes `**/.cache` and
`**/.gitkeep` from `processResources`, `processClientResources`, `jar`, and `sourcesJar`. The
`.cache` excludes keep Fabric datagen's hash index (`src/main/generated/.cache/`, see
`mc-datagen`) out of the shipped jar and the sources jar: the datagen output dir is a resource
root, so without them the index is packaged. Verify with
`unzip -l build/libs/<modid>-*.jar | grep -c .cache` after a `runDatagen` (expect 0), and the
same on the `-sources.jar`.

This configures the `main` source set only. Other source sets get an unconfigured
`ProcessResources` task, so a `${version}` placeholder in a manifest they own is copied through
untouched and reaches the loader as that literal string — the gametest manifest carries a
literal version for exactly this reason. See `mc-mod-testing` for that split.

## SemVer from the pushed tag

The pushed `v*` tag is the single source of version truth. The release workflow injects
it as `-Pmod_version=<tag>`, so the built jar's version is exactly the tag. The
`mod_version=0.0.0` in `gradle.properties` is only the local/dev base: with no tag
injected, `computeModVersion()` derives `0.0.0+g<sha>` from git state so local builds are
clearly non-releases, and it returns the injected tag verbatim on a release build (the
`described == "v${base}"` branch) — so no build.gradle change is needed to adopt
tag-driven releases.

`computeModVersion()` (in `gradle/concord-common.gradle`) maps the describe output onto SemVer
build metadata: an exact tag is the bare version, commits past a tag are `+N.g<sha>`, a dirty tree
adds `.dirty`, and no matching tag at all is `+g<sha>`.

The `shaOnly` branch is what delivers the `0.0.0+g<sha>` promised above, and it is
not optional. `git describe --always` falls back to a **bare sha** in a repo with
no matching tag — a fresh member, a shallow CI clone, a fork — which the post-tag
pattern does not match. Without that branch the version silently degrades to a
bare `0.0.0`, and every untagged local build becomes indistinguishable from a
release build of `0.0.0`.

The shared file runs git through a helper with a **timeout**, never a bare `waitFor()`:
`waitFor(10, TimeUnit.SECONDS)`, then `destroyForcibly()` and a `logger.warn` on timeout.

`waitFor()` with no bound blocks the **configuration phase** — every task in the
build, not just the version computation — for as long as git hangs. Git does hang:
a stale `index.lock` from a killed process, an unresponsive network filesystem, or
a credential helper waiting on a prompt that has no terminal attached. The bounded
form degrades to the `mod_version` base after ten seconds instead of wedging the
build indefinitely; `destroyForcibly()` is what stops the orphaned git process from
outliving the Gradle daemon.

Warn on the way down. Degrading to the base version is the right behavior, but
doing it silently is not: on a machine where git is slow rather than hung, every
Gradle invocation pays the full ten seconds and then produces a jar versioned
`0.0.0` with nothing in the log to say why.

The shared file also exposes `printVersion` for CI and `make version`. It captures the version
at configuration time (`def resolvedVersion = project.version.toString()`) because reaching for
`project` inside a task action is removed in Gradle 10.

## Performance

Add to `gradle.properties`:
```properties
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.daemon=true
org.gradle.jvmargs=-Xmx2G
```

## testRuntimeClasspath fix

With `splitEnvironmentSourceSets`, an unmapped fabric-api sibling on `testRuntimeClasspath` conflicts with fabric-loader-junit. `gradle/concord-common.gradle` applies the exclusion
(`exclude group: 'net.fabricmc.fabric-api', module: 'fabric-api'` on `configurations.testRuntimeClasspath`), so a member does not repeat it.

### Client output on the test classpath

`splitEnvironmentSourceSets()` puts client-only classes in a source set the
`test` source set cannot see, so any pure logic living there — HUD offset math,
a client config holder, a formatter — is unreachable from plain JUnit. The fix is
to add the client source set's **compiled output**, and only its output, to both
test classpaths:

```groovy
sourceSets {
    // Put the client source set's compiled output (not its dependency configurations) on the
    // test classpath so unit tests can reach client-only classes such as ClientMymodConfig.
    // Only .output is added — never client.compileClasspath/runtimeClasspath — so the unmapped
    // fabric-api sibling access widener that the testRuntimeClasspath exclusion below guards
    // against is never pulled in.
    test {
        compileClasspath += sourceSets.client.output
        runtimeClasspath += sourceSets.client.output
    }
}
```

Both classpaths, always. `compileClasspath` alone compiles the test and then
fails it at runtime with `NoClassDefFoundError`, which reads as a broken test
rather than as missing wiring.

`.output` is the whole point of the pattern: adding `sourceSets.client.compileClasspath`
or `.runtimeClasspath` would drag the unmapped fabric-api sibling back in and
re-break exactly what the `testRuntimeClasspath` exclusion above exists to prevent.

This wiring widens what the pure-core split can reach, but it does not replace it —
prefer moving genuinely pure helpers into `main` (see the "Pure core, thin Minecraft
shell" section of `AGENTS.md`). Reach for this when a class is legitimately
client-only and still has testable logic. Render methods that take a live
`GuiGraphics` are not testable at this tier no matter which classpath they sit on.

## Guardrails

- **Never** re-run a full build just to see test results — read the report files.
- **Never** use `-x test` to skip tests without understanding why they fail.
- **Never** pipe Gradle output through partial-read commands as the first pass.
- **Always** tee to a file when build duration is uncertain.
- **Always** set generous timeouts (up to 600000ms) for builds that include gametest server startup.