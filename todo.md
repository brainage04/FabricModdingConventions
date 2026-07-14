# FabricModdingConventions todo

`FabricModdingConventions` is the working name for the broader shared tooling direction. The current project owns the shared recorder/runtime API and independently applicable Gradle convention components; future features should remain opt-in and avoid changing existing consumer behavior by default.

## Accepted near-term roadmap

The current priorities are:

1. Replace copied Modrinth shell behavior with typed validation/publishing tasks where practical.
2. Explore GitHub Actions de-duplication through reusable workflows or wrapper workflows.
3. Verify production GameTest tasks in real CI as consumer workflows evolve.

Deferred decisions:

- Keep `io.github.brainage04.fabric-conventions:1.0.0` documented only as the retired compatibility aggregate; do not publish that marker in later releases.
- Do not use GitHub Packages or GitHub release/Ivy artifact repositories as new dependency fallbacks. Prefer local sibling Maven repositories for active development and Maven Central for released artifacts.
- Do not implement recorded production client GameTests yet. Keep an experiment note for comparing dev-client and production-client recordings before deciding.
- Do not implement Architectury / multi-loader conventions until there is a real multi-loader mod to validate against.


## Good Gradle-plugin candidates

These are good fits for typed Gradle plugin features because they are build logic, publishing logic, or repeatable project conventions that should be parameterized and tested once.

1. Fabric mod convention boilerplate
   - Standard Fabric/Minecraft repositories.
   - Java release/toolchain configuration from `java_version`.
   - Common `processResources` expansion for `fabric.mod.json`.
   - Common `sourcesJar` and license-in-jar behavior.
   - Maven publication setup for mod artifacts.
   - Optional access widener conventions.
   - Optional `splitEnvironmentSourceSets()` conventions.
   - Fabric API GameTest/client GameTest source-set configuration.
   - Standard `test { useJUnitPlatform(); systemProperty "fabric.side", ... }` setup.

2. Client GameTest and recorder setup
   - The current `io.github.brainage04.client-gametest-recorder` component behavior.
   - Deterministic `prepareClientGameTestRun` / `options.txt` generation.
   - `runClientGameTest` JVM args such as `-Dfabric.client.gametest.disableNetworkSynchronizer=true`.
   - Recorder task orchestration for Xvfb, PipeWire, ffmpeg, metadata, kept run directories, and file-signal handshake.
   - Do not implement production client recording yet; track it as a future experiment comparing dev-client and production-client recordings.

3. Production GameTest run support
   - Initial opt-in support exists via `productionGameTests { enabled = true }`.
   - Current tasks: `runProductionClientGameTest`, `runProductionServerGameTest`, and `runAllProductionGameTests`.
   - Next improvements: verify production task behavior in real CI, expose more Loom production task options only when needed, and optionally run a future experiment comparing dev-client recordings against production-client recordings before adding recorder support to production runs.
   - Keep dev tasks (`runGameTest`, `runClientGameTest`) and `test` unchanged unless a consumer explicitly opts in.

4. Maven Central library publishing conventions
   - Shared POM metadata conventions.
   - Signing configuration.
   - Central Portal upload task.
   - Local repository publication conventions.
   - Parameterized GitHub repository URL, artifact description, and Central namespace.

5. Modrinth publishing tasks
   - Replace copied shell scripts with typed tasks where practical.
   - Parse `fabric.mod.json`, `gradle.properties`, README, LICENSE, GitHub metadata, and `.modrinth/project.json`.
   - Ensure/update Modrinth project metadata.
   - Sync project icon.
   - Publish versions from built release artifacts.
   - Validate tag/version consistency.

6. Local sibling/release fallback dependency conventions
   - Standardize local Maven repository lookup for sibling projects.
   - Use one local-first policy: resolve the declared coordinate from the sibling repository when present, otherwise fall back to Maven Central, otherwise fail normally.
   - Keep repo-specific dependencies declared in each consumer.

7. Architectury / multi-loader project conventions
   - Future support for Architectury-style common + loader-specific modules.
   - Keep Fabric-only conventions cleanly separated from cross-loader conventions.
   - Defer this until there is an actual Architectury/multi-loader mod to validate against.
   - Likely split into a dedicated multi-loader/Architectury Gradle plugin rather than folding into the Fabric-only convention plugin.
   - Revisit reusable workflow names before adding shared workflows; avoid a `fabric-` prefix for workflows that may later support Architectury or other loaders.
   - Candidate workflow names: `build.yml`, `release.yml`, `client-gametests.yml`, or reusable workflow files such as `mod-build.yml`, `mod-release.yml`, and `client-gametests.yml`.

## Better as reusable workflow/template, not Gradle plugin only

These should not be forced into Gradle plugin code. They either live outside Gradle, are repository scaffolding, or are intentionally per-repo data.

1. GitHub Actions workflow skeletons
   - Build workflow YAML.
   - Release workflow YAML.
   - Client GameTest workflow jobs.
   - Template init/smoke workflow jobs.
   - These can become reusable workflows or template files, while Gradle tasks own the domain logic.
   - Workflow de-duplication should be explored as GitHub reusable workflows or wrapper workflows, not as Gradle plugin behavior.

2. Documentation templates
   - `docs/RELEASE.md`.
   - `docs/MODRINTH.md`.
   - `docs/PUBLISHING.md`.
   - Keep a source-of-truth template, then copy/generate into repos as needed.

3. FabricModdingTemplate scaffolding
   - `init.sh`.
   - `init.yml`.
   - `smoke_template_generation.sh`.
   - Example mod source files.
   - Generated package/class/mod-id replacement logic.

4. Per-repo Modrinth data
   - `.modrinth/project.json` contents should remain per repo.
   - A plugin can validate schema/defaults, but should not centralize project-specific slug/categories/dependency overrides as code.

5. Repo-specific behavior
   - Gameplay code.
   - Benchmark tasks.
   - Manual trace-analysis scripts.
   - Special local forks such as Baritone setup.
   - Product-specific README content and release notes.

## Naming notes

- `FabricModdingConventions` is the current project name.
- Retired compatibility aggregate plugin id (`1.0.0` only; no `2.0.0` marker):
  - `io.github.brainage04.fabric-conventions`
- Implemented component plugin ids:
  - `io.github.brainage04.fabric-mod-conventions`
  - `io.github.brainage04.client-gametest-recorder`
  - `io.github.brainage04.production-gametests`
  - `io.github.brainage04.workspace-dependencies`
- Implemented opt-in publishing plugin ids:
  - `io.github.brainage04.maven-central-publishing`
- Planned opt-in publishing plugin ids:
  - `io.github.brainage04.modrinth-publishing`

## Implemented baseline

Production Run Task support is available as an opt-in Gradle feature and does not mutate existing development GameTest tasks by default. Version `2.0.0` exposes independently applicable base, recorder, production GameTest, workspace dependency, and publishing components; it does not publish the retired aggregate marker. The base component owns the standard Fabric/Minecraft repositories, `java_version` release and toolchain configuration, sources JAR generation, typed `fabric.mod.json` expansion with consumer additions, and license inclusion. Template, FortniteInMinecraft, and TwitchPlaysMinecraft declare their component capabilities explicitly without duplicating the shared boilerplate.

FabricModdingTemplate initialization now uses atomic `jq` transformations for both main and GameTest `fabric.mod.json` files. The generation smoke matrix validates exact both/server/client entrypoint and mixin structures, exercises reformatted JSON input, preserves `schemaVersion` as the first field, and builds every generated variant.

The opt-in `io.github.brainage04.maven-central-publishing` component now owns shared POM metadata, local and CI signing modes, local publication layout, Central credential validation, and staged Portal uploads. FabricModdingConventions `2.0.0` is published on Maven Central with component-specific marker metadata and no aggregate marker; clean consumer copies without the sibling conventions checkout resolve the component markers and implementation module from Central. The `1.0.0` aggregate remains available only as the historical compatibility release.

HudRendererLib `1.0.4` is published to Maven Central. TwitchPlaysMinecraft uses the same `io.github.brainage04:hudrendererlib:1.0.4` coordinate for local and released resolution, contains no GitHub Release/Ivy fallback or coordinate switch, and passes production GameTests with the sibling HudRendererLib repository removed. Its CI now resolves the released artifact from Maven Central instead of cloning and locally publishing HudRendererLib.

The workspace dependency component provides one typed local-first declaration: each module-filtered sibling Maven repository precedes Maven Central, so the same coordinate resolves locally when published and falls back to Central otherwise. Template, FortniteInMinecraft, and TwitchPlaysMinecraft use it instead of declaring sibling project repositories manually. The Baritone fork uses the owned `io.github.brainage04:baritone-fabric` coordinate so it cannot be confused with an upstream `baritone` group artifact.
