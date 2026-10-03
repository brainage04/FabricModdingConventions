# FabricModdingConventions

Shared Gradle plugins, GitHub workflows and client GameTest helpers for my Minecraft mods.

## Plugins

| Plugin (`io.github.brainage04.…`) | Apply to | Purpose |
| --- | --- | --- |
| `multiloader-mod-conventions` | root of a `common`/`fabric`/`neoforge` mod | **The standard entry point.** Configures all three modules with Architectury Loom and applies every plugin below except `fabric-mod-conventions` and `maven-central-publishing`. |
| `fabric-mod-conventions` | single-loader Fabric project | Fabric Loom, project identity, standard dependencies and repositories, Java settings, side-aware source layout, access widener, sources JAR, `fabric.mod.json` expansion, license in the JAR. |
| `client-gametest-recorder` | Fabric project | `recordClientGameTest`: records client GameTests to MP4 in an isolated Xvfb display and audio sink. Adds the runtime helpers to the `gametest` source set. |
| `production-gametests` | Fabric project | Creates the `gametest` source set and runs GameTests against the packaged mod through Loom's production client and server. |
| `workspace-dependencies` | any project | Prefers sibling checkouts' `build/local-repo` over Maven Central, and can share one dev-client `options.txt`. |
| `java-quality-conventions` | root project | Spotless (google-java-format, AOSP), Checkstyle and javac lint; enforced only with `-PstrictQuality=true`. |
| `mod-publishing` | Fabric/NeoForge project | Opt-in GitHub, Modrinth and CurseForge release tasks around the Mod Publish Plugin. |
| `maven-central-publishing` | libraries only | POM metadata, signing and Central Portal upload. |

Plugins are resolved from `../FabricModdingConventions/build/local-repo` when present, otherwise from Maven Central. Consumers map each plugin ID to its module in `settings.gradle`; copy `pluginManagement` from ModernMinecraftModTemplate.

## Multi-loader mods

```gradle
plugins {
    id "io.github.brainage04.multiloader-mod-conventions" version "${fabricmoddingconventions_version}"
}
```

Requires subprojects `common`, `fabric` and `neoforge`, and these Gradle properties: `mod_id`, `mod_name`, `mod_version`, `maven_group`, `archives_base_name`, `java_version`, `minecraft_version`, `loader_version`, `fabric_api_version`, `neoforge_version`, `fabricmoddingconventions_version`. `mod_side` (`both`, `client` or `server`; default `both`) splits Fabric client sources for `both`, turns off Fabric production server GameTests for `client`, and sets the CurseForge environment.

- Shared GameTests go in `common/src/gametest/java` and `common/src/gametest/resources`; both loaders compile and package them.
- Both loaders' `gametest` source sets also get the structure `fabricmoddingconventions:empty`, 8x8x8 blocks of air (`minecraft:empty` is 1x1x1). Use it in test instances with `"structure": "fabricmoddingconventions:empty"`; it is never packaged into the release JARs.
- The access widener is `<mod_id>.accesswidener` in `common` (or `fabric`); the NeoForge access transformer is `neoforge/src/main/resources/META-INF/accesstransformer.cfg`.
- Tasks that run one loader live in that loader's project under the same name on both; the root only has tasks spanning both loaders. Loom's development `clientGameTest` run is not created (there is no `:fabric:runClientGameTest`); client GameTests run in Loom's production client.

  | Task | Runs |
  | --- | --- |
  | `:fabric:runClient`, `:neoforge:runClient` | development client |
  | `:fabric:runServer`, `:neoforge:runServer` | development dedicated server |
  | `:fabric:runGameTest`, `:neoforge:runGameTest` | development server GameTests |
  | `:fabric:runProductionServerGameTest` | Fabric server GameTests against the packaged mod |
  | `:neoforge:runProductionServerGameTest` | NeoForge server GameTests against the packaged mod, on a NeoForge server installed with the official installer |
  | `:fabric:runProductionClientGameTest` | Fabric client GameTests against the packaged mod, in Xvfb |
  | `:fabric:recordClientGameTest` | the production client GameTests, recorded to MP4 |
  | `runAllGameTests` | the three production GameTest runs: Fabric server and client, NeoForge server |
  | `collectReleaseArtifacts` | copies both loader JARs to `build/libs` |

  With `fabricClientGameTests = false`, `:fabric:runProductionClientGameTest` and `:fabric:recordClientGameTest` do nothing; with `neoForgeGameTests = false`, neither do `:neoforge:runGameTest` and `:neoforge:runProductionServerGameTest` (no server is installed). `common` has no loader: Loom still lists `:common:runClient`, `:common:runServer` and `:common:runClientRenderDoc`, but they do nothing useful; run the loader tasks instead.
- `:neoforge:runProductionServerGameTest` is the NeoForge counterpart of Loom's Fabric production server run (Loom's production run tasks only launch Fabric). `:neoforge:installProductionServer` runs the official installer for `neoforge_version` (resolved and cached by Gradle as `net.neoforged:neoforge:<version>:installer`) with `--install-server` into `neoforge/build/fabricmoddingconventions/neoforge-server/<version>`, once per version. The run then starts that installation's own launcher arguments with FML's `GameTestServer` entrypoint, which runs every GameTest and exits with the number of failed required tests; any failure or crash fails the task.
  - The mod is the release JAR (`:neoforge:jar`, what `collectReleaseArtifacts` ships) plus `:neoforge:productionGameTestJar` (classifier `production-gametest`: the `gametest` source set, including `common/src/gametest` and `fabricmoddingconventions:empty`), loaded as one mod through FML's `fml.modFolders`, so `@EventBusSubscriber(modid = <mod_id>)` classes in the GameTest source set register as they do in development. Both sit in `mod-under-test/`, not `mods/`.
  - Other mods the server needs (BrainageLib, Cloth Config, ...) are not taken from the development classpath: declare them in `:neoforge`'s `productionRuntimeMods`, as on Fabric. They go into `mods/`.
  - NeoForge only ticks GameTests outside production, so the plugin adds a generated `ProductionGameTestTicker` to the `gametest` source set that ticks them on a production GameTest server (it does nothing in development). `RegisterGameTestsEvent` is not posted in production; register test functions with `RegisterEvent` and `test_instance` data, as the template does.
  - Run directory `neoforge/build/run/productionServerGameTest` (log in `logs/latest.log`), JUnit-like report `neoforge/build/test-results/runProductionServerGameTest/TEST-gametest.xml`. The task's `jvmArgs` and `programArgs` (for example `--tests <mod_id>:some_test*`) add to the launch.

  ```gradle
  // neoforge/build.gradle
  dependencies {
      productionRuntimeMods "io.github.brainage04:brainagelib-neoforge:${rootProject.brainagelib_version}"
  }
  ```
- For `mod_side` `client` or `both`, [DevAuth](https://github.com/DJtheRedstoner/DevAuth) (`DevAuth-fabric`/`DevAuth-neoforge`, version `1.2.2` or the `devauth_version` property) is on the development runtime of both loaders (Loom's `localRuntime`). It is not in the JARs, publications or production runs, and stays inactive until you enable it (`-Ddevauth.enabled=true` or its config file). Turn it off with `devAuth = false`.
- `neoforge.mods.toml` can use `${minecraft_version_range}`, which the plugin computes from `minecraft_version` to match Fabric's `"minecraft": "~${minecraft_version}"`: from that version up to, not including, the next minor version (`26.2` → `[26.2,26.3)`, `26.2.1` → `[26.2.1,26.3)`, `26.3-pre1` → `[26.3-pre1,26.4)`). Any other shape, such as a weekly snapshot (`26w14a`), fails the build. Minecraft is the only bounded dependency: on both loaders, the loader and libraries are open-ended (`>=x` in `fabric.mod.json`, `[x,)` in `neoforge.mods.toml`).

Turn off parts that don't apply:

```gradle
multiLoaderModConventions {
    fabricClientGameTests = true
    fabricServerGameTests = true
    neoForgeGameTests = true
    publishing = true
    devAuth = true                  // default: true unless mod_side=server
}
```

### Repositories

Every module gets only Maven Central, `https://maven.fabricmc.net/` (`net.fabricmc` groups), `https://maven.neoforged.net/releases/` (`net.neoforged` groups) and `https://maven.architectury.dev/` (`dev.architectury` groups); owned libraries (the conventions runtime, HudRendererLib, BrainageLib) come from Maven Central or a sibling `build/local-repo` (see [Workspace dependencies](#workspace-dependencies)). There is no `mavenLocal()` and no GitHub-release repository. A mod that needs another repository declares it in the module that declares the dependency, restricted to that dependency's group:

```gradle
repositories {
    exclusiveContent {
        forRepository { maven { url = "https://maven.shedaniel.me/" } }
        filter { includeGroup "me.shedaniel.cloth" }
    }
}
```

## Single-loader Fabric mods

Apply `fabric-mod-conventions` plus the leaf plugins you need. It requires the multi-loader properties minus `neoforge_version`, and uses this layout:

| `mod_side` | Main source layout | Client GameTests | Server GameTests |
| --- | --- | --- | --- |
| `both` | `src/main` plus `src/client` | yes | yes |
| `client` | `src/main` | yes | no |
| `server` | `src/main` | no | yes |

```gradle
fabricModConventions {
    repositoriesEnabled = true
    javaEnabled = true
    sourcesJarEnabled = true
    resourceExpansionEnabled = true
    licenseJarEnabled = true
    additionalFabricModJsonProperties.add("dependency_version")
}
```

Every option defaults to `true`. `fabric.mod.json` expansion fails on a missing or blank property.

## Workspace dependencies

```gradle
workspaceDependencies {
    siblingMaven("HudRendererLib") {
        coordinate.set("io.github.brainage04:hudrendererlib:${hudrendererlib_version}")
    }
}
```

The sibling repository (`../<name>/build/local-repo` by default; override with `siblingDirectory` or `localRepository`) serves only that module and is checked before Maven Central. Publish a sibling with `./gradlew publishAllPublicationsToLocalRepository`.

To give every dev client the same keybinds and video settings, set this in `~/.gradle/gradle.properties`:

```properties
fabricmoddingconventions.devClientOptions=/path/to/options.txt
```

The file is copied into the client run directory before every launch, overwriting changes made in-game.

## Client GameTest recording

```shell
GTR_RECORDING_PROFILE=smoke ./gradlew --no-daemon :fabric:recordClientGameTest
```

Needs `ffmpeg`/`ffprobe` (X11 capture, PulseAudio input, H.264/AAC), Xvfb with `xdpyinfo`, and `pactl`. Set `PULSE_SERVER` for a fully isolated session. The desktop's default sink is never changed.

```gradle
clientGameTestRecorder {
    guiScale = "2"                          // default "1"
    disableUnsecureChatToast = true
    disableSocialInteractionsToast = true
    disableRecipeToasts = true
    disableAdvancementToasts = true
    disableAdvancementChatMessages = true
}
```

- These settings only apply to recording runs; the five `disable…` options default to `true`.
- `GTR_RECORDING_PROFILE=showcase` shows a title card instead of the diagnostic overlay.
- `GTR_RECORDING_PRE_PADDING_SECONDS` / `GTR_RECORDING_POST_PADDING_SECONDS`: `0`–`60`, default `0.5`.
- Each recording writes metadata with tick and frame boundaries, effective padding and any capture gaps. A run fails if game audio is not routed to the recorder's sink or cannot be decoded.

### Dedicated-server harness

```java
ClientGameTestServers.withDedicatedServer(context, "Example GameTest", server -> {
    server.runOnServer(ExampleClientGameTest::prepare);
    // Exercise and assert the connected client.
});
```

The harness starts the server, connects the client, and always disconnects, finishes the recording and stops the server, even when the callback fails. Java helpers live in `io.github.brainage04.fabricmoddingconventions`.

## Production GameTests

```gradle
productionGameTests {
    runtimeModDependencies.add("me.fzzyhmstrs:fzzy_config:${fzzy_config_version}")
    runtimeLibraryDependencies.add("com.github.twitch4j:twitch4j:${twitch4j_version}")
}
```

Loom's production runs do not inherit the dev classpath, so extra mods and libraries must be listed here; Fabric API is added automatically. Tasks: `productionGameTestJar`, `runProductionClientGameTest` (Xvfb by default), `runProductionServerGameTest`, and in single-loader builds `runAllProductionGameTests` (multi-loader builds use the root `runAllGameTests`). `includeClient`, `includeServer`, `clientUseXvfb`, `clientJvmArgs` and `serverProgramArgs` override the defaults.

## Publishing

`mod-publishing` enables a destination only when its block is configured:

```gradle
modPublishing {
    github { repository.set("brainage04/example-mod") }
    modrinth { projectId.set("example-project") }
    curseforge { projectId.set("123456") }
}
```

`publishGithub`, `publishModrinth` and `publishCurseforge` can be retried independently; `publishMods` runs all enabled ones. The Modrinth description is synced from `README.md` and the icon from `assets/<mod_id>/icon.png`. `build` and `check` never contact these services.

`maven-central-publishing` is for libraries. See [docs/PUBLISHING.md](docs/PUBLISHING.md) for credentials and the release command.

## Workflows

Consumer workflows call these and only supply triggers, profiles, artifact patterns and project IDs:

- `reusable-mod-build.yml` — build
- `reusable-client-gametests.yml` — Fabric client GameTests and recordings (`:fabric:runProductionClientGameTest`, `:fabric:recordClientGameTest`)
- `reusable-production-gametests.yml` — `runAllGameTests`, every production GameTest run (`gradle_task` overrides it)
- `reusable-neoforge-gametests.yml` — `:neoforge:runProductionServerGameTest` alone (`gradle_task` overrides it, for example with `:neoforge:runGameTest`)
- `reusable-multiloader-release.yml` — GitHub, Modrinth and CurseForge release of both loader JARs

Every reusable workflow takes a `runner` input, a JSON `runs-on` value defaulting to `"ubuntu-24.04"`; private mods pass their self-hosted runner labels:

```yaml
    with:
      runner: '["self-hosted","minecraft"]'
```

A mod that depends on an owned library that is not on Maven Central (BrainageLib) passes `prepare_siblings`, a space-separated list of brainage04 repositories, to `reusable-mod-build.yml`, the three GameTest workflows and `reusable-multiloader-release.yml`. Each one is cloned (default branch) into `../<name>` and published with `publishAllPublicationsToLocalRepository`, so its `siblingMaven(...)` declaration resolves from `../<name>/build/local-repo`:

```yaml
    with:
      prepare_siblings: BrainageLib
```

A mod that jar-in-jars the Baritone fork (TwitchPlaysMinecraft) passes `prepare_baritone: true` to the same workflows. Each Gradle build then first clones the fork's `minecraft-<minecraft_version>` branch into `../baritone` and runs its `publishAllPublicationsToLocalBaritoneRepository`.

Self-hosted runners keep their work tree between jobs, so every reusable workflow job deletes everything beside its checkout (`..`) right after checking out, before any `prepare_*` step; a stale `../FabricModdingConventions` would otherwise be picked up by `settings.gradle`'s `includeBuild` in place of the released plugin.

## Fleet audit

`scripts/mod_fleet.py` checks every sibling mod against the versions, structure, workflows and recording policy in `scripts/mod-fleet.json`, and can record them all.

```shell
./scripts/mod_fleet.py audit --github --strict
./scripts/mod_fleet.py record [--include <repository>] [--dry-run]
```

`record` writes to a timestamped `~/Downloads/minecraft-mod-gametest-recordings-*` directory; `--output <dir> --resume` reruns only the repositories that failed. For a new Minecraft release, pass `--minecraft-version`, `--loader-version`, `--fabric-api-version`, `--java-version` and `--conventions-version` to `audit`, then update the manifest.
