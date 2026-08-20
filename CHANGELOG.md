# Changelog

All notable changes will be documented here. This project follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- A protected, tag-only Maven Central publication pipeline with pinned build and GitHub Action
  dependencies, signed source/Javadoc artifacts, full checksums, GitHub build provenance,
  immutable-version guards, byte-bound cryptographic Central readback, and a clean Central-only
  consumer smoke test.
- Local release-bundle validation and maintainer documentation for Central namespace, signing-key,
  environment-protection, and secret setup.
- A fail-closed `origin/main` ancestry guard and an upload-free two-build comparison of the exact
  unsigned main, sources, Javadoc, and published POM artifacts.
- Explicit development and stable-tag release-tooling contracts, exercised in ordinary CI with
  publishing disabled unless the protected release workflow opts in.

### Changed

- Development metadata now uses `0.2.1-SNAPSHOT` and SCM tag `HEAD`. Version `0.2.0` remains a
  source-only GitHub release and is not represented as a Maven Central publication.

## [0.2.0] - 2026-08-20

### Added

- `FindSpringProjectUsage` and the canonical assessment now export occurrence-level
  `MigrationAssessmentTable` rows for bounded Maven, literal Gradle, Java source-set,
  configuration, XML, and Spring registration metadata evidence.
- `MigrateSpringTransactionalToJakarta` now provides a directly activated, module-gated and
  source-visible-class-hierarchy-atomic bounded migration for REQUIRED, REQUIRES_NEW, MANDATORY,
  NOT_SUPPORTED, and NEVER with explicit Spring-compatible rollback handling.
- `MigrateSpringTransactionalToJakartaIncludingSupports` provides a separate opt-in for SUPPORTS
  after the caller accepts the documented non-transactional synchronization-scope difference.
- A deterministic H2 differential runtime contract compares Spring Boot 4.1.0 with rewritten
  source compiled and run on Helidon MP 4.5.3, including propagation, rollback, compilation, and
  Spring-residue checks.

### Changed

- The source distribution and SCM metadata now identify the `0.2.0` release and `v0.2.0` tag.
- The canonical assessment composes both `FindSpringUsage` and `FindSpringProjectUsage` while
  remaining read-only. Sensitive configuration, registration metadata, and non-POM XML findings
  are table-only so dry-run patches do not reproduce values or neighboring content.

### Safety

- Transaction mutation remains outside every canonical top-level recipe and refuses an entire
  atomic hierarchy when annotation, CDI interception, rollback, hierarchy, or module-policy proof
  is incomplete.
- Transaction refusals preserve Spring source and export stable reason codes for unsupported
  propagation, custom attributes, composed/test/reactive/programmatic/XML behavior, transaction
  manager selection, unsafe rollback precedence, and incomplete type or hierarchy attribution.
- The base transaction recipe refuses SUPPORTS by default; the opt-in uses the same preflight and
  differs only in the explicitly documented synchronization-scope policy.

## [0.1.0] - 2026-08-20

### Added

- Fail-closed Spring usage assessment with source markers and an exported support-level data table.
- Directly activatable, conservatively gated CDI, named-bean, Maven-build, and Helidon-resource
  leaf recipes for isolated migration experiments.
- Assessment-only v0.1 leaves for Spring MVC, `ResponseEntity`, transactions, `@Value`, and Spring
  Boot startup.

### Safety

- The canonical v0.1 entry points are assessment-only. They do not compose independent mutations
  that could leave a runnable Spring module with CDI-only beans.
- Spring MVC, response, transaction, configuration injection, and launcher code remain unchanged
  when exact runtime behavior cannot be proved from source alone.

[Unreleased]: https://github.com/Devansh-ops/rewrite-spring-to-helidon/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/Devansh-ops/rewrite-spring-to-helidon/releases/tag/v0.2.0
[0.1.0]: https://github.com/Devansh-ops/rewrite-spring-to-helidon/releases/tag/v0.1.0
