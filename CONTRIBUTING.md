# Contributing

Thank you for helping make Spring Boot to Helidon migrations safer and more repeatable.

## Development setup

Use JDK 21 or newer. The repository includes a checksum-pinned Maven Wrapper, so no separate
Maven installation is required.

```bash
./mvnw --batch-mode verify
```

Keep generated `target/` content out of commits.

## Recipe design rules

- Preserve source and add a precise search marker whenever semantics are not provably portable.
- Keep transformations small and composable; do not hide framework-specific behavior in a broad
  annotation replacement.
- Use attributed types and applicability preconditions. Do not decide from printed names alone.
- Give every generated `JavaTemplate` the target API classpath it needs.
- Keep target build changes additive until source residue, compilation, tests, and runtime behavior
  prove that a dependency can be removed.
- Keep examples generic. Do not submit proprietary source, organization names, credentials, URLs,
  internal package names, or customer data.

Every behavior-changing recipe update should include:

1. a successful conversion test;
2. a test proving unsupported semantics remain unchanged and are marked;
3. two-cycle idempotence with exactly one changing cycle;
4. a `@DocumentExample` on one representative test for a new recipe; and
5. corresponding updates to `README.md` and `docs/automation-boundary.md`.

Run focused tests while developing, followed by the complete suite:

```bash
./mvnw -Dtest=MigrateSpringMvcToJakartaRestTest test
./mvnw --batch-mode clean verify
```

## Pull requests

Keep pull requests focused and explain the source semantics, target semantics, refusal boundary,
and validation evidence. Link an issue when one exists. A maintainer may ask for a smaller recipe
or a manual marker when the proposed conversion cannot preserve behavior.

Security concerns should follow [SECURITY.md](SECURITY.md), not a public issue.

## Licensing

By contributing, you agree that your contribution is provided under the repository's
Apache License 2.0. Do not copy code from dependencies with incompatible terms. The optional
Spring Boot 4 normalization recipe has separate licensing and must remain outside the core
dependency graph.
