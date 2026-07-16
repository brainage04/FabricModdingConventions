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

Consumers in this workspace are configured to prefer `../FabricModdingConventions/build/local-repo` when present and otherwise resolve the Maven Central artifact.

## Gradle plugin components

Available components:

- `io.github.brainage04.fabric-mod-conventions` — applies Fabric Loom and owns the standard Fabric/Minecraft repositories, Java release/toolchain defaults, sources JAR generation, typed `fabric.mod.json` expansion, and license inclusion.
- `io.github.brainage04.client-gametest-recorder` — applies the base plugin and owns `clientGameTestRecorder`, `prepareClientGameTestRun`, and `recordClientGameTest`.
- `io.github.brainage04.production-gametests` — applies the base plugin and owns the opt-in `productionGameTests` extension and production run tasks without forcing the recorder component.
- `io.github.brainage04.workspace-dependencies` — declares module-filtered sibling Maven repositories before Maven Central so local publications are preferred without requiring them.
- `io.github.brainage04.maven-central-publishing` — configures shared POM metadata, local and Central publication repositories, GPG-agent or in-memory signing, and Central Portal upload orchestration.
- `io.github.brainage04.mod-publishing` — configures validated, opt-in GitHub, Modrinth, and CurseForge distribution tasks around the upstream Mod Publish Plugin.

The recorder and production GameTest components apply the base plugin internally, so consumers should apply the leaf capabilities they need rather than redundantly declaring the base. Workspace dependency policy and both publishing plugins remain explicit consumer opt-ins.

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

The base plugin reads `java_version`, `mod_id`, `mod_version`, `mod_name`, `loader_version`, and `minecraft_version` from Gradle properties. Its defaults can be narrowed per consumer:

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

`additionalFabricModJsonProperties` extends the required resource-expansion property set without replacing it. Missing or blank properties fail with a configuration error when `fabric.mod.json` is processed. `licenseFile` defaults to the root project's `LICENSE`.

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

The Java-side helpers live under `io.github.brainage04.fabricmoddingconventions`.

## Production GameTest tasks

Production GameTest support is opt-in. It keeps Loom's normal development GameTest tasks unchanged and registers separate production tasks when enabled:

```gradle
productionGameTests {
    enabled = true
}
```

The plugin adds Fabric API to Loom's `productionRuntimeMods` configuration from `fabric_api_version` by default. It packages `sourceSets.gametest.output` into a dedicated mod jar, adds that jar to both production runs, and writes `eula=true` in their isolated run directories. Consumers must declare extra Fabric mods through `runtimeModDependencies` and ordinary JVM libraries through `runtimeLibraryDependencies`; Loom's production tasks do not inherit the development runtime classpath. The plugin registers:

- `productionGameTestJar` — packages the processed `gametest` source-set classes and resources as `*-production-gametest.jar`.
- `prepareProductionGameTestRuns` — writes the client embedded-server and standalone-server EULA files.
- `runProductionClientGameTest` — runs the packaged GameTest mod through Loom's production client with `-Dfabric.client.gametest`, `-Dfabric.client.gametest.disableNetworkSynchronizer=true`, Xvfb enabled by default, and `build/run/productionClientGameTest` as the run directory.
- `runProductionServerGameTest` — runs the packaged GameTest mod through Loom's production server with `-Dfabric-api.gametest` and `build/run/productionServerGameTest` as the run directory.
- `runAllProductionGameTests` — aggregate task depending on the enabled production tasks.

Useful switches:

```gradle
productionGameTests {
    enabled = true
    includeClient = true
    includeServer = false
    clientUseXvfb = true
    runtimeModDependencies.add("me.fzzyhmstrs:fzzy_config:${project.fzzy_config_version}")
    runtimeLibraryDependencies.add("com.github.twitch4j:twitch4j:${project.twitch4j_version}")
    clientJvmArgs.add("-Dmy.flag=true")
    serverProgramArgs.add("nogui")
}
```
