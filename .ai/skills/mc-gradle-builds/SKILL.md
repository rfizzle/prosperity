---
name: mc-gradle-builds
description: Best practices for running Gradle builds, tests, gametests, and coverage in Fabric Minecraft mods. Prevents wasted reruns from partial output capture; wires the merged unit+gametest JaCoCo report. TRIGGER when running any Gradle command (build, test, runGametest, runDatagen, assemble, check), when inspecting build output/reports, when wiring or reading JaCoCo coverage, or when editing repositories/dependencies in build.gradle.
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

## Fabric Loom configuration

### splitEnvironmentSourceSets
Separates `main/` (common) from `client/` (client-only). Required for mods with client-side code:

```groovy
loom {
    splitEnvironmentSourceSets()
    accessWidenerPath = file("src/main/resources/mymod.accesswidener")
}
```

### Source set wiring for gametest
```groovy
sourceSets {
    main {
        resources {
            srcDirs += ["src/main/generated"]
        }
    }
    gametest {
        compileClasspath += sourceSets.main.compileClasspath + sourceSets.main.output
        runtimeClasspath += sourceSets.main.runtimeClasspath + sourceSets.main.output
    }
}

configurations {
    gametestImplementation.extendsFrom implementation
    gametestRuntimeOnly.extendsFrom runtimeOnly
}
```

The source set carries its own `src/gametest/resources/fabric.mod.json` declaring a
`<modid>-gametest` mod, which is where the `fabric-gametest` entrypoints belong — putting them
in the shipped manifest breaks every dev run set. See `mc-mod-testing`, "Registering the suite,"
for the manifest and the literal-version requirement that comes with it.

### Run configurations
```groovy
loom {
    runs {
        datagen {
            inherit server
            name "Data Generation"
            vmArg "-Dfabric-api.datagen"
            vmArg "-Dfabric-api.datagen.output-dir=${file("src/main/generated")}"
            vmArg "-Dfabric-api.datagen.modid=mymod"
            runDir "build/datagen"
        }
        gametest {
            server()
            name "Game Test"
            source sourceSets.gametest
            vmArg "-Dfabric-api.gametest"
            vmArg "-Dfabric-api.gametest.report-file=${layout.buildDirectory.file('junit-gametest.xml').get().asFile}"
            runDir "build/gametest"
        }
    }
}
```

## Coverage: merge unit-test and gametest execution data

JaCoCo's default `jacocoTestReport` instruments only the `test` task, so code
exercised exclusively in-game — event handlers, commands, registration, anything
a gametest drives — reads as 0% even when it is thoroughly gametested. The
merged report below is the mod's real coverage number; read per-package coverage
from it, never from the unit-test-only report.

Attach the agent to the gametest server (Loom's `runGametest` is a `JavaExec`,
so the JaCoCo task extension applies cleanly):

```groovy
// Attach the JaCoCo agent to the gametest server so code only exercised
// in-game counts toward coverage. The includes filter keeps the agent from
// instrumenting Minecraft/Fabric classes.
tasks.named('runGametest') {
    jacoco.applyTo(it)
    it.jacoco.destinationFile = layout.buildDirectory.file('jacoco/runGametest.exec').get().asFile
    it.jacoco.includes = ['com.example.mymod.*']
}
```

Then merge both exec files into one report:

```groovy
// Single source of coverage truth: unit tests + gametests merged over src/main.
tasks.register('jacocoMergedReport', JacocoReport) {
    // Ordering only — not dependsOn, so the report can run from existing exec
    // data without forcing a gametest server spin-up.
    mustRunAfter test, tasks.named('runGametest')
    // fileTree only picks up exec files that exist, so the report still runs
    // when one of the two sweeps hasn't.
    executionData fileTree(layout.buildDirectory.dir('jacoco')) {
        include 'test.exec', 'runGametest.exec'
    }
    sourceSets sourceSets.main
    // Mixin bodies execute inside the transformed vanilla classes, so the
    // agent can never attribute coverage to the mixin class files — excluding
    // them keeps the denominator honest. Mixins stay thin (delegate to
    // handlers, see mc-mixin-craft) so nothing measurable hides here.
    classDirectories.setFrom(files(classDirectories.files.collect {
        fileTree(dir: it, exclude: ['com/example/mymod/mixin/**'])
    }))
    reports {
        xml.required = true
        html.required = true
    }
}
```

Full sweep and report:

```bash
./gradlew test runGametest jacocoMergedReport 2>&1
```

The report lands in `build/reports/jacoco/jacocoMergedReport/` (HTML + XML).
Without `mustRunAfter`, Gradle 8 fails the report task with an
implicit-dependency validation error whenever it runs in the same invocation as
`test`/`runGametest` (both write into `build/jacoco`). What counts toward the
number, which residual misses are acceptable, and the target live in the
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

Every repository beyond `mavenCentral()` gets a `content { includeGroup ... }` block. Without it, Gradle probes every repo for every artifact — resolution is slower and non-deterministic, and any repo can answer for (and shadow) a group it doesn't own:

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

Inject version into `fabric.mod.json`:
```groovy
processResources {
    def projectVersion = project.version
    inputs.property "version", projectVersion
    filesMatching("fabric.mod.json") {
        expand "version": projectVersion
    }
}
```

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
`out == "v${base}"` branch) — so no build.gradle change is needed to adopt tag-driven
releases:

```groovy
version = computeModVersion()

def computeModVersion() {
    def base = project.mod_version
    def tagPrefix = "v"
    try {
        def proc = new ProcessBuilder('git', 'describe', '--tags',
                '--match', "${tagPrefix}*", '--dirty', '--always')
                .directory(rootDir).redirectErrorStream(true).start()
        proc.waitFor()
        def out = proc.inputStream.text.trim()
        if (proc.exitValue() != 0) return base
        if (out == "${tagPrefix}${base}") return base
        if (out == "${tagPrefix}${base}-dirty") return "${base}+dirty"
        def m = out =~ /^\Q${tagPrefix}\E\S+?-(\d+)-g([0-9a-f]+)(-dirty)?$/
        if (m.matches()) return "${base}+${m[0][1]}.g${m[0][2]}${m[0][3] ? '.dirty' : ''}"
        return base
    } catch (Exception ignored) { return base }
}
```

Expose a `printVersion` task for CI:
```groovy
tasks.register('printVersion') {
    doLast { println project.version }
}
```

## Performance

Add to `gradle.properties`:
```properties
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.daemon=true
org.gradle.jvmargs=-Xmx2G
```

## testRuntimeClasspath fix

With `splitEnvironmentSourceSets`, an unmapped fabric-api sibling on `testRuntimeClasspath` conflicts with fabric-loader-junit:

```groovy
configurations.testRuntimeClasspath {
    exclude group: 'net.fabricmc.fabric-api', module: 'fabric-api'
}
```

## Guardrails

- **Never** re-run a full build just to see test results — read the report files.
- **Never** use `-x test` to skip tests without understanding why they fail.
- **Never** pipe Gradle output through partial-read commands as the first pass.
- **Always** tee to a file when build duration is uncertain.
- **Always** set generous timeouts (up to 600000ms) for builds that include gametest server startup.