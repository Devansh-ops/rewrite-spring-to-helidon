# Publishing to Maven Central

This project is prepared to publish signed releases under
`io.github.devansh-ops:rewrite-spring-to-helidon`. Publication is intentionally gated by a GitHub
environment and a stable Git tag. Pull requests, Dependabot branches, ordinary branch pushes, and
manual workflow dispatches cannot enter the publication job.

Version `0.2.0` was released through GitHub as source only. It has not been published to Maven
Central, and this project must never imply otherwise. The development POM starts at
`0.2.1-SNAPSHOT`; the first Central release must use an unpublished version such as `0.2.1` or
later.

Maven Central releases are immutable. Treat every attempt that may have reached the Central Portal
as consumed, even if a later workflow step failed. Fix the problem and choose a new version rather
than trying to replace published bytes.

## One-time publisher setup

These steps require the repository owner because they create external credentials and protection
rules. Do not paste any resulting token, private key, or passphrase into an issue, pull request,
repository file, build log, or chat.

1. Sign in to the [Central Publisher Portal](https://central.sonatype.com/) with the GitHub identity
   that controls `Devansh-ops`.
2. Confirm that the `io.github.devansh-ops` namespace is verified. Central often provisions the
   matching personal GitHub namespace automatically. If it does not, follow Central's
   [namespace verification](https://central.sonatype.org/register/namespace/) flow and create only
   the temporary public repository whose exact name Central supplies. Delete that temporary
   repository after verification.
3. Generate a passphrase-protected OpenPGP key whose public identity is appropriate for this
   project. Central's current guidance requires signing with the primary key. Publish the public
   key to a supported server such as `keyserver.ubuntu.com`; see Central's
   [GPG instructions](https://central.sonatype.org/publish/requirements/gpg/). Keep an encrypted,
   offline backup of the private key and its revocation certificate.
4. Generate a Central Portal user token. The token provides a username and password; it is not the
   interactive portal password.
5. In GitHub, create or verify an environment named exactly `maven-central` with:

   - deployment tags restricted to protected release tags matching `v*.*.*` (GitHub environment
     tag filters are globs; the workflow's exact `vMAJOR.MINOR.PATCH` guard is authoritative);
   - required approval from `Devansh-ops`;
   - a 5-minute wait timer;
   - prevention of self-review when that would not make releases impossible; and
   - administrator bypass disabled where the repository's plan and ownership model permit it.

   GitHub withholds environment secrets until its protection rules pass. See
   [GitHub's environment documentation](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments).

6. Add these four **environment secrets** to `maven-central`, not repository-wide secrets:

   | Secret | Value |
   | --- | --- |
   | `MAVEN_CENTRAL_USERNAME` | Username from the Central Portal user token |
   | `MAVEN_CENTRAL_TOKEN` | Password from the Central Portal user token |
   | `MAVEN_GPG_PRIVATE_KEY` | ASCII-armored export of the primary private key |
   | `MAVEN_GPG_PASSPHRASE` | Passphrase protecting that private key |

The workflow gives its default token only `contents: read`. The protected publication job adds only
the OIDC and attestation permissions needed for GitHub build provenance. Every external action is
pinned to a complete commit SHA, checkout credentials are not persisted, and Central credentials
are passed to the Maven publication step through environment variables only. The private signing
key is imported into an isolated temporary GPG home for that step and erased when the step exits;
later bundle and readback checks use only the exported public key.

## Prepare a release

Work from a reviewed commit on `main`.

1. Replace `0.2.1-SNAPSHOT` with the intended stable version in `pom.xml`.
2. Replace the POM SCM tag `HEAD` with the exact tag, for example `v0.2.1`.
3. Update `CHANGELOG.md` and release-facing README text. Do not state that a version is on Central
   before readback succeeds.
4. Run the normal checks:

   ```bash
   ./mvnw --batch-mode --no-transfer-progress clean verify
   ./scripts/smoke-test.sh
   ./scripts/test-release-tooling.sh stable-tag v0.2.1
   ```

5. With a disposable or real signing key available in the local GPG keyring, build the exact
   Central bundle without uploading it:

   ```bash
   ./scripts/build-release-bundle-locally.sh v0.2.1
   ```

   This command stops at Maven `verify` with `central.skipPublishing=true`, then assembles and
   validates a clearly named Central-layout structural test bundle containing the main JAR,
   sources JAR, Javadoc JAR, POM, detached signatures, and
   MD5/SHA-1/SHA-256/SHA-512 checksums. The local surrogate does not invoke the plugin's staging or
   upload path and is not evidence that Central accepted the bundle. Only the protected publish job
   validates the Sonatype plugin-produced bundle.
6. Verify the unsigned primary artifacts and published POM are reproducible across two isolated,
   upload-free builds:

   ```bash
   ./scripts/verify-reproducible-release.sh v0.2.1
   ```

   This comparison intentionally excludes detached signatures and the aggregate bundle because
   signing metadata is not byte-reproducible.
7. Merge the release metadata to `main`, create the exact `vMAJOR.MINOR.PATCH` tag on that commit,
   and push the tag. Tag creation is deliberately outside the publication workflow. A tag whose
   commit is not reachable from `origin/main` is rejected.

## What the tag workflow does

`.github/workflows/publish-central.yml` has two jobs:

1. `verify-release` runs without publisher credentials. It fetches full history, proves the tag
   commit is reachable from `origin/main`, validates the exact tag/POM/SCM relationship, exercises
   all release guards, runs the project tests and external recipe smoke test, and byte-compares the
   main, sources, Javadoc, and published POM from two isolated upload-free builds. Each build uses
   its own clean project copy, Maven repository, home directories, and disposable signing key.
2. `publish` waits at the protected `maven-central` environment. After approval it checks that the
   exact coordinate does not already exist, imports the protected signing key into a temporary
   keyring for the Maven step, and runs the pinned Sonatype `central-publishing-maven-plugin`
   `0.11.0`. The plugin auto-publishes and waits up to 120 minutes for the `PUBLISHED` state,
   polling every 10 seconds. All readback downloads share one 20-minute deadline and use
   10-second request caps. The clean consumer probe uses at most 24 attempts with 15-second pauses
   and 10-second request caps, keeping its repository-visibility wait below 10 minutes. These
   limits leave roughly 30 minutes of the publication job's 180-minute timeout for the build,
   attestation, and final consumer execution. A public-key-only keyring is retained for
   verification; the temporary private-key material is removed.

After upload, the job validates the generated bundle, creates a GitHub/Sigstore build-provenance
attestation, downloads every published file back from Maven Central to verify its checksums and
signatures, and finally runs the recipe from a clean external Maven project whose isolated local
repository is mirrored exclusively to `https://repo.maven.apache.org/maven2`.

The preflight duplicate check is an early guard, not a substitute for Central's immutability
enforcement. The plugin keeps `ignorePublishedComponents=false`, so Central remains authoritative
if two attempts race.

## Verify a published release

The workflow performs both commands automatically after Central reports `PUBLISHED`:

```bash
./scripts/verify-central-publication.sh 0.2.1 \
  target/central-publishing/central-bundle.zip .
./scripts/central-consumer-smoke-test.sh 0.2.1
```

`verify-central-publication.sh` requires the publisher's public key in the local GPG keyring. It
first proves that the plugin-produced bundle contains the exact local main JAR, sources JAR,
Javadoc JAR, and POM. It downloads those four files, their signatures, and every checksum from
Central, validates them, and byte-compares all four downloads with the submitted bundle before
printing their SHA-256 digests. `central-consumer-smoke-test.sh` creates a temporary consumer and
temporary Maven repository and refuses to use `mavenLocal` or any non-Central mirror.

GitHub provenance can be checked separately after downloading an attested artifact:

```bash
gh attestation verify rewrite-spring-to-helidon-0.2.1.jar \
  --repo Devansh-ops/rewrite-spring-to-helidon
```

## Failure handling

- If `verify-release` fails, do not approve the environment. Correct the release metadata and use a
  new reviewed tag according to the repository's release policy.
- If the duplicate guard finds the coordinate, stop. Central artifacts cannot be overwritten.
- If Maven reports an upload, validation, or publishing failure, inspect the Central Portal before
  retrying. If the deployment could have published, increment the version.
- If provenance or readback fails after `PUBLISHED`, the Central artifact still exists and is
  immutable. Preserve the logs, fix the pipeline, and publish a new patch version if artifact bytes
  or metadata are wrong.
- Rotate the Central token or GPG key by replacing the environment secret. Never commit a local
  Maven `settings.xml`, exported private key, passphrase, or token.

The required artifact set and metadata follow Central's
[publishing requirements](https://central.sonatype.org/publish/requirements/), and the release
profile follows the supported
[Central Maven plugin](https://central.sonatype.org/publish/publish-portal-maven/) workflow.
