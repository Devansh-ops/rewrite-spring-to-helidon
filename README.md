# OpenRewrite Spring Boot to Helidon MP

An independent OpenRewrite recipe library for assessing and incrementally migrating Spring Boot
applications to Helidon MP. The recipes are generalized for conventional Maven and
Gradle projects, including large multi-module Maven reactors.

This project is not affiliated with or endorsed by OpenRewrite, Spring, or Helidon.

Version `0.1.0-SNAPSHOT` targets [Helidon MP 4.5.3](https://github.com/helidon-io/helidon/releases/tag/4.5.3).
It is intentionally a migration scaffold, not a one-command framework replacement.
It automates source changes whose semantics can be preserved, adds a minimal Helidon
foundation, and reports the Spring surface that still needs engineering work.

> **Preview status:** always run the analysis and a dry run first. The migration recipe
> does not promise a compiling or production-ready Helidon application, remove Spring,
> or make architecture-specific choices on the application's behalf.

## Target and prerequisites

- JDK 21 or newer is required for a Helidon 4 application. Helidon recommends JDK 25+
  for current development, but Java 21 is this project's minimum target.
- Maven 3.8+ is recommended for target Maven builds.
- The canonical source baseline is Spring Boot 4 on Jakarta EE 10-era APIs.
- `SpringBootToHelidonMp` can also be used for another modern, Jakarta-compatible Spring
  Boot baseline, but it currently delegates to the same Boot 4 migration composition.
- The recipe library itself is built and tested with JDK 21.

Helidon MP 4 implements MicroProfile 6.1 and Jakarta EE 10 Core Profile APIs, including
Jakarta REST 3.1, CDI 4.0, MicroProfile Config 3.1, Jakarta Transactions 2.0, Jakarta
Validation 3.0, and Jakarta Persistence 3.1. Availability of an API does not imply that
v0.1 migrates every Spring feature mapped to it.

## Recipe catalog

| Recipe | Purpose |
| --- | --- |
| `io.github.devanshops.rewrite.helidon.AnalyzeSpringBootToHelidonMp` | Finds Spring types, adds source markers in the dry-run patch, and exports a support-level data table without changing application semantics. |
| `io.github.devanshops.rewrite.helidon.SpringBoot4ToHelidonMp` | Canonical v0.1 migration for applications already on Spring Boot 4. |
| `io.github.devanshops.rewrite.helidon.SpringBootToHelidonMp` | General direct alias for applications already on a modern Jakarta-compatible Spring baseline. |
| `io.github.devanshops.rewrite.helidon.PrepareMavenBuildForHelidonMp` | Safe, additive Maven preparation only: imports the Helidon Dependencies POM at the Maven root and in direct Boot POMs, then adds the MP core bundle to executable Spring modules. |
| `io.github.devanshops.rewrite.helidon.SpringBootToHelidonMpViaBoot4` | Optional wrapper that first runs the separately supplied Spring Boot 4 upgrade recipe and fails validation if it is absent. See [licensing](#optional-spring-boot-4-normalization-and-licensing). |

The canonical composition runs in this order:

```text
SpringBoot4ToHelidonMp
  1. PrepareMavenBuildForHelidonMp
  2. AddHelidonMpResources
  3. MigrateSpringNamedBeansToCdi
  4. MigrateSpringDiToCdi
  5. MigrateResponseEntityToJakartaResponse
  6. MigrateSpringMvcToJakartaRest
  7. MigrateSpringTransactionalToJakarta
  8. MigrateSpringValueToConfigProperty
  9. MigrateSpringBootMain
 10. FindSpringUsage
```

Resource discovery deliberately precedes entry-point migration, named-bean handling precedes
the general DI conversion, and transaction/configuration injection run only after controller
eligibility has been decided. The launcher changes only when production Spring runtime residue is
clear in its module. Project-scoped safety scans run before edits in each OpenRewrite cycle, so the
canonical recipe deliberately requests follow-up cycles before it replaces the runtime entry point.
The final inventory measures Spring residue after the mechanical changes.

See [the detailed automation boundary](docs/automation-boundary.md) for the exact supported
subsets and manual-review cases.

Every leaf recipe is also directly activatable when a smaller migration step is preferable:

| Leaf recipe | Scope |
| --- | --- |
| `AddHelidonMpResources` | Adds missing CDI and MicroProfile Config scaffolds to executable modules. |
| `FindSpringUsage` | Marks and exports a classified inventory of remaining Spring types. |
| `MigrateResponseEntityToJakartaResponse` | Converts a compilation-unit-safe subset of `ResponseEntity` builders to Jakarta REST `Response`. |
| `MigrateSpringBootMain` | Converts a plain, option-free Spring Boot launcher to `io.helidon.Main`. |
| `MigrateSpringDiToCdi` | Converts proxy-safe stereotypes, injection points, and producers to CDI. |
| `MigrateSpringMvcToJakartaRest` | Converts an atomically safe controller subset to Jakarta REST. |
| `MigrateSpringNamedBeansToCdi` | Preserves supported Spring bean names with CDI `@Named`. |
| `MigrateSpringTransactionalToJakarta` | Converts the supported Spring transaction annotation subset. |
| `MigrateSpringValueToConfigProperty` | Converts simple placeholders at proven CDI injection points. |

For example, a supported service changes from:

```java
@Service
class CatalogService {
    @Value("${catalog.region:us-east}")
    String region;
}
```

to:

```java
@ApplicationScoped
@Named("catalogService")
class CatalogService {
    @Inject
    @ConfigProperty(name = "catalog.region", defaultValue = "us-east")
    String region;
}
```

The recipe adds the corresponding Jakarta and MicroProfile imports. Shapes outside the documented
safe subset remain unchanged and receive a review marker.

## Quick start with Maven

The snapshot is not assumed to be published. Build and install it into the local Maven
repository first:

```bash
./mvnw --batch-mode verify
./mvnw --batch-mode install
```

From the target application's root, assess the code without changing it:

```bash
mvn -U org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun \
  -Drewrite.recipeArtifactCoordinates=io.github.devansh-ops:rewrite-spring-to-helidon:0.1.0-SNAPSHOT \
  -Drewrite.activeRecipes=io.github.devanshops.rewrite.helidon.AnalyzeSpringBootToHelidonMp \
  -Drewrite.exportDatatables=true
```

Review both the dry-run patch and the CSVs under
`target/rewrite/datatables/<timestamp>/`. The Spring usage inventory contains the source
path, feature family, Spring type, support level (`AUTOMATIC`, `PARTIAL`, or `MANUAL`), and
suggested replacement.

Then preview the actual migration:

```bash
mvn -U org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun \
  -Drewrite.recipeArtifactCoordinates=io.github.devansh-ops:rewrite-spring-to-helidon:0.1.0-SNAPSHOT \
  -Drewrite.activeRecipes=io.github.devanshops.rewrite.helidon.SpringBoot4ToHelidonMp \
  -Drewrite.exportDatatables=true
```

Only after reviewing the proposed changes, apply them by replacing `dryRun` with `run`.
Use an isolated branch or worktree and validate one executable module before widening the
scope.

## Gradle consumption

The Java source recipes can run through the OpenRewrite Gradle plugin. The v0.1 build recipe
only edits Maven POMs, so Gradle dependency management and packaging must be migrated manually.
After installing the snapshot locally, a Groovy build can load it as follows:

```groovy
plugins {
    id 'java'
    id 'org.openrewrite.rewrite' version '7.38.0'
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    rewrite 'io.github.devansh-ops:rewrite-spring-to-helidon:0.1.0-SNAPSHOT'
}

rewrite {
    activeRecipe 'io.github.devanshops.rewrite.helidon.AnalyzeSpringBootToHelidonMp'
    exportDatatables = true
}
```

Use `./gradlew rewriteDryRun` to preview and `./gradlew rewriteRun` to apply. Gradle writes
its patch to `build/reports/rewrite/rewrite.patch` and exported data tables beneath
`build/reports/rewrite/datatables/`.

## Build and resource behavior

`PrepareMavenBuildForHelidonMp` is deliberately additive. When it finds Spring Boot Maven
usage, it imports `io.helidon:helidon-dependencies:4.5.3` into dependency management at the
Maven root. Because an aggregator is not necessarily a parent, it also imports that POM into
modules that directly declare a Spring Boot parent, dependency, or build plugin. It adds
`io.helidon.microprofile.bundles:helidon-microprofile-core` to modules that use
`SpringApplication`; the local import supplies its version. It does
**not**:

- replace the existing parent POM;
- remove Spring dependencies or dependency management;
- add or remove application packaging plugins;
- select persistence, security, messaging, health, or database implementations; or
- change the Java source/target version.

`AddHelidonMpResources` finds each root or nested executable module containing
`@SpringBootApplication` or `SpringApplication.run` and creates missing resources:

- `src/main/resources/META-INF/beans.xml`, using CDI 4.0 annotated discovery; and
- `src/main/resources/META-INF/microprofile-config.properties`, containing comments only.

Existing files are never overwritten. The MicroProfile Config scaffold intentionally defines
no host, port, or application values, so running the recipe cannot silently replace an
application's environment-specific settings.

## v0.1 demarcation

The automated core covers common REST-service mechanics: class-atomic, proxy-safe CDI service/component stereotypes
and injection; zero-argument singleton producers only when `@Bean` contains the literal lifecycle opt-out
`destroyMethod = ""`; literal bean names and qualifiers; simple `@Value`
placeholders with non-empty defaults for proven-equivalent scalar target types at proven CDI
injection points; the safe subset of transaction annotations; controller-atomic Spring MVC
conversion for public, proxyable resource shapes without Spring Web infrastructure in the module;
common `ResponseEntity` builders inside proven REST resources; one structurally plain application
launcher after same-module Spring runtime residue is clear; additive Maven preparation; and Helidon
resources.

The following remain explicit engineering work in v0.1:

- Spring Security and authorization semantics;
- Spring `@Repository` persistence-exception translation;
- Spring Data repository behavior and persistence configuration;
- WebFlux/Reactor and asynchronous or streaming MVC behavior;
- global Spring MVC advice, interceptors, filters, converters, and exception resolvers;
- Spring Cloud, Batch, Integration, Kafka, and other messaging stacks;
- Actuator customizations, metrics, and production health checks;
- scheduling, caching, AOP, events, and Spring application-context lookups;
- `@ConfigurationProperties`, Spring property-file conversion, and secret/config-source design;
- default/proxied `@Configuration`, unproxyable CDI bean shapes, parameter-only normal-scoped
  constructors, and aggregate injection;
- Spring scopes, profiles, conditions, imports, component scans, property sources, lazy beans,
  dependency ordering, and other container lifecycle semantics;
- static `@Autowired`/`@Value` injection and `@Value` on ordinary method parameters;
- Spring `${name:}` empty defaults, which MicroProfile Config deliberately treats as absent;
- Spring MVC parameter defaults and non-scalar REST parameter conversions;
- collection, duration, period, enum, and other `@Value` target types whose Spring conversion
  rules are not proven equivalent to MicroProfile Config;
- `RestTemplate`, `WebClient`, declarative clients, and client resilience behavior;
- JPA provider, transaction manager, datasource, validation, and test-runtime configuration;
- Spring test slices, mocks, test lifecycle, deployment descriptors, containers, and packaging; and
- final Spring dependency removal and residue enforcement.

The recipe leaves unsupported code in place and adds search markers instead of guessing. There
is no Spring-removal finalizer in v0.1.

## Optional Spring Boot 4 normalization and licensing

`SpringBootToHelidonMpViaBoot4` composes
`org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0` before the canonical migration. It is
kept out of the core runtime dependency graph. A validation guard prevents the wrapper from
silently skipping normalization when the optional recipe is absent. To activate it, separately supply:

```text
org.openrewrite.recipe:rewrite-spring:6.37.1
```

This project's own recipe code is declared under Apache License 2.0. The upstream artifact and
Boot upgrade recipe are distributed under the
[Moderne Source Available License](https://docs.openrewrite.org/recipes/java/spring/boot4/upgradespringboot_4_0-community-edition),
and access may require authenticated artifact-repository configuration. Review the applicable
terms before use. The direct analysis and Helidon migration recipes do not require
`rewrite-spring`.

## Development

Use JDK 21 and the checksum-pinned Maven Wrapper:

```bash
./mvnw test
./mvnw verify
./mvnw -Dtest=FindSpringUsageTest test
./mvnw -Dtest=AddHelidonMpResourcesTest test
./mvnw install
./scripts/smoke-test.sh
```

Recipe implementations live under `src/main/java`, declarative compositions under
`src/main/resources/META-INF/rewrite`, and focused `RewriteTest` cases under `src/test/java`.
When adding automation, include both a successful conversion test and a test proving that
unsupported semantics are preserved and marked for review.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the complete contribution contract,
[SECURITY.md](SECURITY.md) for private vulnerability reporting, [CHANGELOG.md](CHANGELOG.md)
for release history, and [LICENSE](LICENSE) for licensing.

## References

- [Helidon 4.5.3 release](https://github.com/helidon-io/helidon/releases/tag/4.5.3)
- [Helidon MP specifications](https://helidon.io/docs/v4/mp/specifications)
- [Helidon MP Maven guide](https://helidon.io/docs/latest/mp/guides/maven)
- [Helidon MP server](https://helidon.io/docs/v4/mp/server)
- [Helidon MP configuration](https://helidon.io/docs/v4/mp/config)
- [OpenRewrite Maven and Gradle quickstart](https://docs.openrewrite.org/running-recipes/getting-started)
- [OpenRewrite data tables](https://docs.openrewrite.org/authoring-recipes/data-tables)
