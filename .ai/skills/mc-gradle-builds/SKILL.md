---
name: mc-gradle-builds
description: Best practices for running Gradle builds, tests, and gametests in Fabric Minecraft mods. Prevents wasted reruns from partial output capture. TRIGGER when running any Gradle command (build, test, runGametest, runDatagen, assemble, check), or when inspecting build output/reports.
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

## Version management

Externalize all versions in `gradle.properties`:
```properties
minecraft_version=1.21.1
loader_version=0.16.10
fabric_version=0.116.1+1.21.1
# Local/dev base only — releases derive the version from the pushed tag.
mod_version=0.0.0
```

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