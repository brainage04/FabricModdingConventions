# FabricModdingConventions

Reusable Fabric modding conventions for Minecraft mods.

The library provides:

- client GameTest recording helpers and a file-signal handshake between the Gradle recorder task and the running test client;
- a lightweight recording HUD for scenario step/log output;
- defensive helpers for launching and joining an in-process dedicated server from Fabric client GameTests;
- a compiled Gradle plugin for deterministic client GameTest run setup;
- a reusable `recordClientGameTest` Gradle task using `GTR_*` environment variables;
- opt-in Fabric Loom production GameTest tasks.

The recorder task requires `ffmpeg`, `ffprobe`, Xvfb/`xdpyinfo`, and PipeWire tools (`pw-cli`, `wpctl`) on the recording host.

## Dependency

```gradle
repositories {
    mavenCentral()
}

dependencies {
    implementation "io.github.brainage04:fabricmoddingconventions:<version>"
}
```

For local development with a sibling checkout, publish the library to its local build repository first:

```shell
./gradlew --no-daemon publishAllPublicationsToLocalRepository
```

Consumers in this workspace prefer `../FabricModdingConventions/build/local-repo`, then use normal plugin repositories when the component is published there, and finally resolve the Gradle module metadata and artifacts attached to the matching public GitHub Release.

## Gradle plugin components

Available components:

- `io.github.brainage04.fabric-mod-conventions` — applies Fabric Loom and owns project identity, standard Minecraft/Fabric dependencies and repositories, Java compile/test conventions, side-aware Loom source layout, access-widener discovery, sources JAR generation, typed `fabric.mod.json` expansion, and license inclusion.
- `io.github.brainage04.client-gametest-recorder` — applies the base plugin, owns `clientGameTestRecorder`, `prepareClientGameTestRun`, and `recordClientGameTest`, and wires the runtime helper into GameTest compilation and production runs when the production component is present.
- `io.github.brainage04.production-gametests` — applies the base plugin, creates and registers the `gametest` source set from `mod_id`, configures Loom's development GameTest runs from `mod_side`, and owns the `productionGameTests` extension and production run tasks without forcing the recorder component.
- `io.github.brainage04.workspace-dependencies` — declares module-filtered sibling Maven repositories before Maven Central so local publications are preferred without requiring them.
- `io.github.brainage04.maven-central-publishing` — configures shared POM metadata, local and Central publication repositories, GPG-agent or in-memory signing, and Central Portal upload orchestration.
- `io.github.brainage04.mod-publishing` — configures validated, opt-in GitHub, Modrinth, and CurseForge distribution tasks around the upstream Mod Publish Plugin.

The recorder and production GameTest components apply the base plugin internally, so consumers apply only the leaf capabilities they need. Workspace dependency policy and both publishing plugins remain explicit consumer opt-ins.

`mod_side` is the stable environment model:

| Value | Main source layout | Development client GameTests | Development server GameTests |
| --- | --- | --- | --- |
| `both` | `src/main` plus split `src/client` | enabled | enabled |
| `client` | client-only code in `src/main` | enabled | disabled |
| `server` | server/common code in `src/main` | disabled | enabled |

The reusable workflows `reusable-mod-build.yml`, `reusable-client-gametests.yml`, `reusable-production-gametests.yml`, and `reusable-neoforge-gametests.yml` own standard build, client GameTest/recording, Fabric production GameTest, and NeoForge GameTest orchestration. `reusable-multiloader-release.yml` owns the shared Fabric/NeoForge GitHub, Modrinth, and CurseForge release matrix. Consumer workflows provide only trigger policy, profiles, artifact patterns, project identifiers, and genuine dependency preparation.

```gradle
plugins {
    id "io.github.brainage04.workspace-dependencies" version "<version>"
}

workspaceDependencies {
    siblingMaven("HudRendererLib") {
        coordinate.set("io.github.brainage04:hudrendererlib:${hudrendererlib_version}")
    }
    siblingMaven("baritone") {
        coordinate.set("io.github.brainage04:baritone-fabric:${baritone_version}")
    }
}
```

Sibling repositories default to `../<name>/build/local-repo`, are restricted to the declared Maven module, and are ordered before Maven Central. Gradle uses the local publication when the requested version is present, falls back to Maven Central when it is absent, and reports an ordinary resolution failure when neither repository contains it. `siblingDirectory` and `localRepository` can override the default layout.

### Maven Central publishing

The Central component configures existing Maven publications rather than creating project-specific artifacts:

```gradle
plugins {
    id "io.github.brainage04.maven-central-publishing" version "<version>"
}

mavenCentralPublishing {
    repository.set("brainage04/FabricModdingConventions")
    publicationName.set("FabricModdingConventions")
    description.set("Reusable Fabric modding conventions and GameTest helpers.")
}
```

It adds `build/local-repo` as the `local` publication repository and the Sonatype staging API as `central`. `publishToMavenCentral` validates metadata, Portal credentials, signing configuration, and `centralPublishingType` before uploading. Local publication needs no Central credentials. Local releases use `-PuseGpgAgentSigning=true`; CI uses `CENTRAL_PORTAL_USERNAME`, `CENTRAL_PORTAL_PASSWORD`, `SIGNING_KEY`, and `SIGNING_PASSWORD`. The release mode defaults to `user_managed` and also accepts `automatic` or `portal_api`.

### Mod distribution publishing

The distribution component derives common release metadata from the existing Fabric build, but every destination remains disabled until its DSL block is configured:

```gradle
plugins {
    id "io.github.brainage04.mod-publishing" version "<version>"
}

modPublishing {
    github {
        repository.set("brainage04/example-mod")
    }
    modrinth {
        projectId.set("example-project")
    }
    curseforge {
        projectId.set("123456")
    }
}
```

`publishGithub`, `publishModrinth`, and `publishCurseforge` are independently retryable; `publishMods` runs every enabled destination. Validation rejects malformed booleans, negative retry counts, missing release artifacts, and inconsistent release metadata before network access. Modrinth project metadata and icons are synchronized through typed tasks. Ordinary `build` and `check` execution do not contact publishing endpoints.

Multi-loader release callers use `reusable-multiloader-release.yml`, supplying the NeoForge artifact pattern and destination project identifiers once. The wrapper prepares both exact artifacts, publishes both to one GitHub release, synchronizes Modrinth through the Fabric module before publishing both loader versions, and publishes both CurseForge files. Its boolean destination inputs keep unsupported services disabled without duplicating the release graph.

The base plugin requires `mod_side`, `java_version`, `mod_id`, `mod_version`, `mod_name`, `maven_group`, `archives_base_name`, `loader_version`, `minecraft_version`, and `fabric_api_version` in Gradle properties. Its optional behaviors can be narrowed per consumer:

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

`additionalFabricModJsonProperties` extends the required resource-expansion property set without replacing it. Expansion applies to every `ProcessResources` task, including the plugin-owned GameTest source set. Missing or blank properties fail when metadata is processed. `licenseFile` defaults to the root project's `LICENSE`.

## Recording task

Apply the base and recorder components, then run the shared task directly:

```gradle
plugins {
    id "io.github.brainage04.client-gametest-recorder" version "<version>"
}
```

```shell
GTR_RECORDING_PROFILE=smoke ./gradlew --no-daemon recordClientGameTest
```

The recorder prepares a low-noise client profile by default: GUI scale `1`, the insecure-server and Social Interactions tutorial toasts suppressed, recipe and advancement toasts suppressed, and advancement announcement chat messages suppressed. These settings apply only while the recorder's GameTest JVM property is active; normal clients are unchanged. Override any setting per consumer:

```gradle
clientGameTestRecorder {
    guiScale = "2"
    disableUnsecureChatToast = true
    disableSocialInteractionsToast = true
    disableRecipeToasts = true
    disableAdvancementToasts = true
    disableAdvancementChatMessages = true
}
```

The recording HUD uses Minecraft's scaled GUI coordinates, so its panel and text follow the configured GUI scale instead of shrinking relative to the framebuffer. The five notification controls default to `true`; set an individual property to `false` when that vanilla notification is part of the scenario.

The Java-side helpers live under `io.github.brainage04.fabricmoddingconventions`.

### Dedicated-server client GameTest harness

`ClientGameTestServers.withDedicatedServer` owns server creation, client connection, connection-state validation, disconnection, and server closure. The simple overload uses `flatServerProperties()`; pass explicit properties only when a fixture changes the default server:

```java
ClientGameTestServers.withDedicatedServer(context, "Example GameTest", server -> {
    server.runOnServer(ExampleClientGameTest::prepare);
    // Exercise and assert the connected client.
});
```

The callback may retain a fixture-specific `try`/`finally` for server-state cleanup. Connection cleanup belongs to the harness and runs even when the callback fails.

### Fleet audit and recording

`scripts/mod_fleet.py` applies the shared version, structure, workflow, and recording policy in `scripts/mod-fleet.json` to every owned non-Forge mod checkout. It also discovers unlisted sibling Minecraft repositories; add `--github` to reconcile the policy against every authenticated public and private repository available through the `gh` CLI.

```shell
./scripts/mod_fleet.py audit --github --strict
./scripts/mod_fleet.py record
```

`record` runs each eligible `recordClientGameTest` sequentially, preserves per-repository logs and failure workspaces, and collects successful MP4 files under `recordings/`, normalized JSON metadata under `metadata/`, and fleet reports inside one timestamped `~/Downloads/minecraft-mod-gametest-recordings-*` directory. It does not write individual recordings directly to `~/Downloads`. Limit an iteration with repeated `--include <repository>` arguments or preview every command with `--dry-run`. After correcting a partial failure, pass the existing directory to `--output` with `--resume`; valid passed artifacts are retained and only unfinished repositories run again.

For a new Minecraft release, override the expected baseline without editing the script:

```shell
./scripts/mod_fleet.py audit --github --strict \
  --minecraft-version <version> \
  --loader-version <version> \
  --fabric-api-version <version> \
  --java-version <version> \
  --conventions-version <version>
```

After migrating the fleet, update the manifest baseline and each new repository's explicit recording or exclusion policy. New GitHub or local Minecraft repositories fail strict reconciliation until classified.

## Production GameTest tasks

Applying `io.github.brainage04.production-gametests` creates the `gametest` source set and configures Loom's development GameTest runs from `mod_side`. It also registers production tasks for the applicable sides; consumers only configure genuine runtime differences:

```gradle
productionGameTests {
    runtimeModDependencies.add("me.fzzyhmstrs:fzzy_config:${project.fzzy_config_version}")
    runtimeLibraryDependencies.add("com.github.twitch4j:twitch4j:${project.twitch4j_version}")
}
```

The plugin adds Fabric API to Loom's `productionRuntimeMods` configuration from `fabric_api_version` by default. It packages `sourceSets.gametest.output` into a dedicated mod jar, adds that jar to the enabled production runs, and writes `eula=true` in their isolated run directories. Consumers declare extra Fabric mods through `runtimeModDependencies` and ordinary JVM libraries through `runtimeLibraryDependencies`; Loom's production tasks do not inherit the development runtime classpath. The plugin registers:

- `productionGameTestJar` — packages the processed `gametest` source-set classes and resources as `*-production-gametest.jar`.
- `prepareProductionGameTestRuns` — writes the client embedded-server and standalone-server EULA files.
- `runProductionClientGameTest` — runs the packaged GameTest mod through Loom's production client with `-Dfabric.client.gametest`, `-Dfabric.client.gametest.disableNetworkSynchronizer=true`, Xvfb enabled by default, and `build/run/productionClientGameTest` as the run directory.
- `runProductionServerGameTest` — runs the packaged GameTest mod through Loom's production server with `-Dfabric-api.gametest` and `build/run/productionServerGameTest` as the run directory.
- `runAllProductionGameTests` — aggregate task depending on the enabled production tasks.

Useful switches:

```gradle
productionGameTests {
    // Override the mod_side-derived production task selection only when needed.
    includeClient = true
    includeServer = false
    clientUseXvfb = true
    runtimeModDependencies.add("me.fzzyhmstrs:fzzy_config:${project.fzzy_config_version}")
    runtimeLibraryDependencies.add("com.github.twitch4j:twitch4j:${project.twitch4j_version}")
    clientJvmArgs.add("-Dmy.flag=true")
    serverProgramArgs.add("nogui")
}
```
