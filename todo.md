# FabricModdingConventions todo

No active feature work is queued. New work must satisfy one of the concrete triggers below.

## 2.2.1 release follow-up

- [ ] Publish and tag the reviewed production GameTest correctness changes as `v2.2.1`.
- [ ] Update Template, FortniteInMinecraft, and TwitchPlaysMinecraft to `2.2.1`; move Twitch4J from Loom's native client-test library configuration to `runtimeLibraryDependencies`; then verify all production GameTests in CI.

## Trigger-gated work

These are not active tasks:

- Add Architectury or other multi-loader conventions only when a real multi-loader mod can define and verify the contract.
- Extract a generalized recorder session/process abstraction only when a second recorder backend or production-client recording creates a real second execution path.
- Add more Loom production-run options only when an active consumer needs one.
- Keep access-widener paths and split-environment source sets in Loom's native DSL unless consumers develop shared behavior or validation beyond their current one-line declarations.

## Guardrails, not tasks

- Prefer a module-filtered local sibling Maven repository during active multi-repository development, then Maven Central for released artifacts.
- Do not add GitHub Packages or GitHub release/Ivy repositories as dependency fallbacks.
- Keep repository-specific dependencies in their consumer projects.
- Keep the owned Baritone fork required-local until upstream publishes a usable Fabric artifact or redistribution is explicitly approved.
