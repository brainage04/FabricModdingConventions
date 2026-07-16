# FabricModdingConventions todo

The project owns the shared recorder/runtime API plus six independently applicable Gradle plugin components. Consumers apply only the leaf capabilities they need; there is no aggregate compatibility plugin.

## 2.2.0 release follow-up

The signed `2.2.0` runtime, all six plugin markers, and all six dedicated implementation modules are published on Maven Central. A clean consumer with an isolated Gradle user home resolved and applied every component directly from Central.

The build now resolves the released `2.2.0` Central publishing plugin, exposes each component's independently runnable finalizer, and uses the qualified root `:publishToMavenCentral` task for the single combined Portal deployment. Release names are derived from the pushed tag.

All reusable workflow consumers now reference the released `v2.2.0` tag.

## CI verification

Run the real consumer workflows and confirm:

- Template, FortniteInMinecraft, and TwitchPlaysMinecraft resolve the component markers from Central without a sibling conventions repository.
- Their production client and server GameTests pass through `reusable-production-gametests.yml`.
- The reusable release-preparation and per-destination publishing workflows retain independently retryable GitHub, Modrinth, and CurseForge jobs.
- Ordinary `build` and `check` jobs do not contact publication endpoints.

## Deferred until a concrete consumer exists

Do not implement these speculatively:

- Recorded production-client GameTests. First run a focused dev-client versus production-client recording experiment and require an observable benefit.
- Architectury or other multi-loader conventions. A real multi-loader mod must define and verify the contract.
- More Loom production-run options, access-widener helpers, or split-environment source-set helpers. Add only the option required by an active consumer.
- A generalized recorder session/process abstraction. Extract one only if a second recorder backend or production-client recording creates a real second execution path.

## Dependency policy

- Prefer a module-filtered local sibling Maven repository during active multi-repository development, then Maven Central for released artifacts.
- Do not add GitHub Packages or GitHub release/Ivy repositories as dependency fallbacks.
- Keep repository-specific dependencies in their consumer projects.
- Keep the owned Baritone fork required-local until upstream publishes a usable Fabric artifact or redistribution is explicitly approved.
