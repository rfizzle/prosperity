Evaluate the quality of the Concord member mod in this repository.

Launch 4 parallel evaluation agents examining different quality dimensions, then compile the results into a unified grade report.

## 1. Folder Structure & Architecture

Assess:
- Source set separation (main, client, test, gametest) — is the client/server split clean?
- Package organization by domain/feature — are there god packages or classes?
- Resource layout — are assets/, data/, and generated output properly separated?
- Build files (build.gradle, gradle.properties, settings.gradle) — best practices, dependency scoping, version externalization
- Documentation — README, docs/, any inline design docs
- Top-level hygiene — .gitignore correctness, stray artifacts, logs or build output tracked by mistake

## 2. Testing Patterns & Coverage

Assess:
- Test coverage breadth — count test files vs source files, estimate which features/classes are tested vs untested
- Test quality — meaningful behavior tests vs trivial assertions, edge case coverage, clear failure messages
- Unit vs integration test split — pure JUnit for math, fabric-loader-junit for registry-dependent logic, Fabric Gametest for world-state tests
- Test infrastructure — utilities, fixtures, builders, helpers, test config/data
- Mocking strategy — avoidance of mock frameworks in favor of real registries, test subclasses, null args where safe
- Naming conventions — clear test class and method names (subject_condition_expectedOutcome)
- Gametest quality — real in-game scenario coverage, entity spawning, tick-based assertions

## 3. Code Quality & Patterns

Assess:
- Fabric API idiom usage — registries, events, mixins, codecs, ComponentType, PayloadTypeRegistry, StreamCodec
- Class design — single responsibility, separation of concerns, class sizes (flag anything over 500 lines)
- Mixin quality — targeted and minimal injections, correct injection points, mod-namespaced prefixes (meridian$), justified Javadoc on complex mixins
- Error handling — null safety, invalid state handling, graceful degradation on malformed data
- Configuration — validation, migration infrastructure, hot-reload support
- Networking — well-defined payloads as records, proper StreamCodec usage
- Data generation — comprehensiveness, organization, idempotency checks
- Code smells — god classes, deep nesting, magic numbers, copy-paste duplication, unused/dead code, over-engineering

## 4. Feature Completeness

Assess:
- Documented vs implemented — are all README/docs features actually built? Are there stub placeholders?
- Data completeness — missing lang translations, incomplete JSON data files, proper tag definitions
- Client-server contract — matched networking payloads, proper client/server entrypoints
- Mod compatibility — EMI/REI/JEI/Jade/WTHIT/ModMenu integrations, shared compat layers
- Configuration coverage — can users configure important aspects? Is config documented accurately (defaults match code)?
- Polish — lang file completeness, proper item/block names, tooltips, advancement integration, recipe integration, death messages

## Output Format

Compile all agent results into a unified report:

1. **Overall Grade** (A+ to F) — weighted average of the four dimensions
2. **Per-Dimension Grades** — letter grade + 1-2 sentence summary for each
3. **Key Strengths** — bullet list of what the mod does well (be specific, cite patterns)
4. **Issues Found** — table with columns: Severity (Critical/High/Medium/Low/Trivial) | Issue description
5. **Bottom Line** — 2-3 sentence summary of overall quality and what kind of developer would produce this
