# Gradle run tasks

The `run*` and `record*` tasks a multi-loader mod gets from `io.github.brainage04.multiloader-mod-conventions`, run from the mod's root. Anything that runs one loader lives in that loader's project and has the same name on both loaders; the root only has tasks that span both.

## Development (dev environment, sources on the classpath)

| Task | What it runs |
|---|---|
| `:fabric:runClient` / `:neoforge:runClient` | Dev client with the mod and DevAuth (DevAuth signs in only if enabled in `~/.config/devauth/config.toml`) |
| `:fabric:runServer` / `:neoforge:runServer` | Dev dedicated server |
| `:fabric:runGameTest` | Fabric server GameTests in the dev environment |
| `:neoforge:runGameTest` | NeoForge server GameTests in the dev environment |

## Release jar (production environment)

| Task | What it runs |
|---|---|
| `:fabric:runProductionServerGameTest` | Fabric server GameTests on a production Fabric server, against the release jar |
| `:fabric:runProductionClientGameTest` | Fabric client GameTests in a production Fabric client under Xvfb; does nothing when `fabricClientGameTests = false` |
| `:fabric:recordClientGameTest` | The same client GameTests, recorded to video (Xvfb, PipeWire, ffmpeg); skipped when client GameTests are off |
| `:neoforge:runProductionServerGameTest` | NeoForge server GameTests on a real NeoForge server installed with the official installer, against the release jar; skipped when `neoForgeGameTests = false` |

There is no NeoForge client GameTest task: NeoForge has no client GameTest framework.

## Root

| Task | What it runs |
|---|---|
| `runAllGameTests` | `:fabric:runProductionServerGameTest`, `:fabric:runProductionClientGameTest` (when enabled) and `:neoforge:runProductionServerGameTest` |

## Tasks that do nothing

Loom also creates `:common:runClient`, `:common:runServer` and `:common:runClientRenderDoc`. `common` has no loader and the conventions clear its run configurations, so these tasks are skipped. Use the `:fabric:` or `:neoforge:` task instead.
