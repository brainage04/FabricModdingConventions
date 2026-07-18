# FabricModdingConventions todo

The active roadmap is to stabilize TwitchPlaysMinecraft on Fabric, then deliver a Fabric/NeoForge Architectury build. Forge, Quilt-specific work, the Kotlin migration, and reusable multi-loader conventions remain deferred until the two-loader consumer proves the contract.

## Planned work

### 1. TwitchPlaysMinecraft stabilization gate

- [ ] Establish a current green Fabric baseline before any multi-loader restructuring.
  - Publish the required-local Baritone Fabric artifact, then pass the Twitch build, GameTest compilation, the complete client GameTest profile, and all production GameTests.
  - Run a real Fabric client and verify initialization, Twitch authorization/chat dispatch, representative success and failure feedback, commands, HUDs, reconnect behavior, keybindings, config/Mod Menu integration, Baritone, and mixins.
  - Review a fresh complete-suite recording, resolve user-visible failures or unreadable outcomes, and record the exact green commit and CI runs.
  - Treat `TwitchPlaysMinecraft/todo.md` as the checklist for remaining stabilization and demo-readability work.

### 2. TwitchPlaysMinecraft NeoForge-first prototype

#### Research conclusions

- [x] Compared Architectury itself, its official template, Roughly Enough Items, Cloth Config, and Emotecraft.
  - Reuse the conventional `common` plus thin loader-module layout. Loader modules own entrypoints, metadata, loader APIs, dependency variants, and final jars; common owns gameplay code and narrow loader-neutral contracts.
  - Do not copy the large repository-specific build logic, compatibility matrices, or publishing abstractions from those projects.
- [x] Verified the current toolchain and loader boundary for Minecraft 26.1.2.
  - Architectury Plugin 3.5 still defines Fabric, NeoForge, Forge, and Quilt transforms.
  - Architectury API 20.0.x publishes Fabric and NeoForge variants for 26.1.2, but no current Forge or Quilt variants. Common Twitch code therefore must depend on small Twitch-owned platform contracts rather than assuming Architectury API covers all four runtimes.
  - Cloth Config 26.1.154 likewise publishes Fabric and NeoForge variants while its 26.1 Forge module is disabled.
  - Quilt Loader retains Fabric-loader compatibility APIs while QSL, Quilt Kotlin Libraries, and Quilted Fabric API are retired for 26.1; verify the Fabric artifact with Fabric API directly on Quilt before creating a duplicate Quilt module.
- [x] Measured the Twitch porting surface.
  - 91 of 157 main Java files import Fabric APIs, but the coupling is only 15 unique API types: 71 files import `FabricClientCommandSource`, 20 import the tick event, and the remaining production imports are command builders, entrypoint/datagen, HUD, key mapping, level/connection lifecycle, and loader environment checks.
  - Architectury exposes equivalent client command, tick, lifecycle, and key-mapping concepts. The command migration is broad and mechanical; HUD registration, config UI, loader metadata, GameTests, and dependency variants are the actual platform seams.
- [x] Probed the owned Baritone fork without source changes.
  - `:fabric:compileJava` remains green against Fabric for Minecraft 26.1.2.
  - `:neoforge:compileJava` succeeds against NeoForge 26.1.2.82.
  - `:forge:compileJava` succeeds against Forge 26.1.2-64.0.11.
  - Keep Baritone required-local; enable and verify Fabric and NeoForge for the immediate delivery, preserve the already-probed Forge module for later, and eventually test its Fabric artifact on Quilt rather than redesigning its common core.

#### Immediate implementation sequence

- [ ] Prove an empty `common`/`fabric`/`neoforge` Architectury build at the pinned Minecraft 26.1.2 toolchain; do not create placeholder Forge or Quilt modules.
- [ ] Define only the observed common platform contracts: client-command registration/source access, client ticks and connection lifecycle, key mappings, environment checks, HUD layers, and config-screen integration.
  - Keep `common` free of Fabric and NeoForge packages, loader-name switches, and Architectury API runtime types that would prevent a later direct Forge adapter.
- [ ] Port HudRendererLib to a loader-neutral HUD element/layer contract with Fabric and NeoForge adapters while preserving two-stage rendering, layer ordering, visibility, and config behavior.
- [ ] Enable and publish the owned Baritone NeoForge artifact alongside Fabric only to the sibling local Maven repository, then prove both required-local coordinates from Twitch; leave its already-probed Forge module untouched.
- [ ] Move Twitch gameplay, command trees, state, vanilla-targeting mixins, access transformation, and loader-neutral resources into `common` without changing observable command behavior.
- [ ] Rebuild the Fabric module as the first platform adapter and restore the complete baseline: build, client GameTests, production GameTests, recorder workflow, datagen, Mod Menu/config integration, and real-client smoke behavior.
- [ ] Add the NeoForge module using the released Architectury API and Cloth Config variants, with loader-owned entrypoint, metadata, dependencies, events, HUD/config registration, mixins/resources, and final jar.
- [ ] Run a real NeoForge client and exercise initialization, representative commands and feedback, ticks, reconnect, HUD ordering/visibility, keybindings, config, Baritone, and mixins.
- [ ] Inspect both release jars for correct loader metadata and entrypoints, expected common classes, isolated loader resources, and absence of development-only or opposite-loader dependencies.
- [ ] Add only Fabric/NeoForge CI build entries and retain their artifacts privately after both local launch contracts are green; do not add public distribution, source publication, or dormant Forge/Quilt matrix entries.

#### Deferred extension sequence

- [ ] Add Forge after the Fabric/NeoForge delivery by implementing the existing platform contracts directly, adding the Forge HudRendererLib adapter and Baritone artifact, and resolving the absent current Architectury API and Cloth Config Forge variants without using NeoForge binaries.
- [ ] After Forge, launch the Fabric artifact under current Quilt Loader with Fabric API and exercise the same smoke contract; advertise Quilt compatibility if it passes and create a Quilt module only for a reproduced incompatibility.
- [ ] Extract a new opt-in multi-loader conventions plugin only after the Twitch build exposes repeated, stable build logic; do not expand the existing Fabric-only component plugins or migrate Fortnite/template consumers speculatively.

### 3. Kotlin migration (deferred)

- [ ] Migrate the Gradle plugins, tests, and shipped Fabric runtime helpers to Kotlin as a separate delivery after the NeoForge-first work.
  - Introduce Fabric Language Kotlin as an explicit runtime dependency of the shipped helper and generated/consumer mods.
  - Preserve every published plugin ID and coordinate, extension/task DSL, Java-callable helper API, runtime-helper dependency contract, and current consumer behavior.
  - Migrate mixins only if Kotlin produces equally clear, reliable bytecode and Mixin behavior; otherwise keep mixins in Java.
  - Migrate incrementally while keeping the plugin tests, marker-resolution tests, component-isolation tests, consumer builds/GameTests, and Maven Central publication dry-run green.

## Completed 2.3.0 ownership rollout

- [x] Published and tagged `v2.3.0`, including the runtime artifact and all six component plugin marker/implementation pairs on Maven Central.
- [x] Moved canonical project identity, dependency repositories, Java conventions, Loom source layout, GameTest defaults, and runtime-helper wiring into the owning component plugins.
- [x] Added reusable build and client-GameTest workflows, reduced the template/Fortnite/Twitch callers to project-specific configuration, and removed their duplicated workflow orchestration.
- [x] Verified the conventions suite, Central-only resolution, all generated template side variants, and build/client/production GameTest jobs for all three consumers.

## Guardrails, not tasks

- Prefer a module-filtered local sibling Maven repository during active multi-repository development, then Maven Central for released artifacts.
- Do not add GitHub Packages or GitHub release/Ivy repositories as dependency fallbacks.
- Keep repository-specific dependencies in their consumer projects.
- Keep the owned Baritone fork required-local until upstream publishes a usable Fabric artifact or redistribution is explicitly approved.
- Keep TwitchPlaysMinecraft private and prohibit public source or artifact distribution until the user explicitly approves a public release.
