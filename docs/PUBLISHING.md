# Publishing

Publishing is configured for Sonatype Central via the OSSRH Staging API compatibility service, matching the HudRendererLib release setup.

Before publishing, create a Sonatype Central account, verify the `io.github.brainage04` namespace, generate a Central Portal user token, and create a GPG key for signing releases.

## Secret handling

Never store publishing secrets in `~/.bashrc`, `$PROFILE`, project files, Gradle command-line arguments, or a repository `.env` file.

### Local workstation

Store the Central Portal token in a password manager. Keep the signing private key in the local GnuPG keyring and let `gpg-agent` provide its passphrase.

Configure only the non-secret GPG selector in `~/.gradle/gradle.properties`:

```properties
signing.gnupg.executable=gpg
signing.gnupg.keyName=<long-signing-key-id>
```

The owner workstation provides `unlock-central-publishing`, `with-central-publishing`, and `lock-central-publishing` helpers. Unlock once to create a mode-`0600` Bitwarden CLI session under `$XDG_RUNTIME_DIR`; each `with-central-publishing` invocation retrieves the Portal token, exposes it only to the child command, and leaves that session available for subsequent publishing commands. Run `lock-central-publishing` when the publishing session is finished.

### CI

CI may continue using secret-store-provided environment variables:

- `CENTRAL_PORTAL_USERNAME`
- `CENTRAL_PORTAL_PASSWORD`
- `SIGNING_KEY`
- `SIGNING_PASSWORD`

The build uses in-memory PGP signing when the signing environment variables are present and GPG-agent signing when `useGpgAgentSigning=true`.

## Publish

On the owner workstation, unlock once and then run as many publishing commands as needed:

```bash
unlock-central-publishing
with-central-publishing ./gradlew --no-daemon publishToMavenCentral
```

By default, the staged deployment is uploaded to the Central Portal for manual review (`user_managed` mode). To ask Sonatype to release automatically after validation, run:

```bash
with-central-publishing ./gradlew --no-daemon publishToMavenCentral -PcentralPublishingType=automatic
```

Lock the reusable vault session explicitly when publishing is complete:

```bash
lock-central-publishing
```
