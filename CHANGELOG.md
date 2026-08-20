# Changelog

All notable changes will be documented here. This project follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - 2026-08-20

### Added

- Fail-closed Spring usage assessment with source markers and an exported support-level data table.
- Directly activatable, conservatively gated CDI, named-bean, bare-transaction, Maven-build, and
  Helidon-resource leaf recipes for isolated migration experiments.
- Assessment-only v0.1 leaves for Spring MVC, `ResponseEntity`, `@Value`, and Spring Boot startup.

### Safety

- The canonical v0.1 entry points are assessment-only. They do not compose independent mutations
  that could leave a runnable Spring module with CDI-only beans.
- Transaction conversion preserves Spring's default `Error` rollback explicitly and refuses all
  rollback rules and non-default/global transaction semantics.
- Spring MVC, response, configuration injection, and launcher code remain unchanged when exact
  runtime behavior cannot be proved from source alone.

[Unreleased]: https://github.com/Devansh-ops/rewrite-spring-to-helidon/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Devansh-ops/rewrite-spring-to-helidon/releases/tag/v0.1.0
