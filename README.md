# OpenRewrite Spring Boot to Helidon MP

An independent OpenRewrite recipe library for assessing and incrementally migrating Spring Boot
applications to Helidon MP. The recipes are generalized for conventional Maven and
Gradle projects, including large multi-module Maven reactors.

This project is not affiliated with or endorsed by OpenRewrite, Spring, or Helidon.

Version `0.1.0` targets [Helidon MP 4.5.3](https://github.com/helidon-io/helidon/releases/tag/4.5.3).
It is intentionally a migration scaffold, not a one-command framework replacement.
It automates source changes whose semantics can be preserved, adds a minimal Helidon
foundation, and reports the Spring surface that still needs engineering work.

> **Preview status:** always run the analysis and a dry run first. The migration recipe
> does not promise a compiling or production-ready Helidon application, remove Spring,
> or make architecture-specific choices on the application's behalf.

## In plain English

This project handles the first, mechanical part of moving a conventional Spring Boot REST
application to Helidon MP. It can translate familiar annotations and APIs, add the minimum
Helidon build and resource foundation, and produce a report of the Spring work that remains.

It follows one safety rule: **when the recipe cannot prove that a change preserves behavior, it
leaves that code on Spring and marks it for review.** The expected result is a partly migrated
codebase and an actionable to-do list—not a finished production application.

### What it can change automatically

Each change below is made only when the surrounding class and module pass the recipe's safety
checks.

| Existing Spring code | Helidon MP-compatible result |
| --- | --- |
| `@Service` or `@Component` | CDI `@ApplicationScoped` and `@Named` |
| Eligible `@Autowired` injection | Jakarta `@Inject` |
| A zero-argument `@Bean(destroyMethod = "")` | CDI `@Produces`, `@Singleton`, and `@Named` |
| A simple scalar `@Value` placeholder | MicroProfile `@ConfigProperty` |
| A straightforward Spring MVC REST controller | Jakarta REST `@Path`, `@GET`, `@POST`, and related annotations |
| Supported `ResponseEntity` builders | Jakarta REST `Response` |
| A supported Spring transaction annotation | Jakarta Transactions `@Transactional` |
| A plain Spring Boot launcher with no remaining Spring runtime work in its module | `io.helidon.Main.main(args)` |
| An executable Maven module | Additive Helidon dependency management, MP core, CDI discovery, and an empty MP Config scaffold |

The recipe does **not** automatically port security, repositories, application property files,
messaging, reactive code, tests, or deployment architecture. It also does not remove Spring
dependencies. The final report inventories remaining Spring types in source code; it is not a
complete migration checklist. Configuration files, packaging, deployment architecture, test
behavior, and other application-specific concerns require a separate manual audit.

### Is this a good fit?

| Good starting candidate | Expect mostly analysis and manual work |
| --- | --- |
| Spring Boot 4 or another modern Jakarta-compatible baseline | Spring Boot 2 or an application still using `javax.*` APIs |
| Conventional Spring MVC REST services | WebFlux, Reactor, streaming, or heavily customized MVC |
| `@Service`, `@Component`, `@Autowired`, and simple `@Value` usage | Extensive custom scopes, bean lifecycle hooks, AOP, or dynamic application-context access |
| Maven projects, including multi-module reactors | Gradle projects needing automatic build migration |
| A staged migration where each module will be compiled and tested | An expectation that one recipe run will produce a deployable Helidon application |

### Recommended workflow

```text
Analyze without edits -> Review the report -> Preview a migration -> Apply one module -> Finish manual work
```

1. Run `AnalyzeSpringBootToHelidonMp` to inventory Spring usage without changing application
   behavior.
2. Review its patch markers and exported data tables, especially all `PARTIAL` and `MANUAL`
   findings.
3. Dry-run `SpringBoot4ToHelidonMp` and inspect every proposed change.
4. Apply it to one executable module, then compile, test, and review security and configuration.
5. Migrate the reported unsupported areas deliberately before removing Spring.

The [quick start](#quick-start-with-maven) contains the commands for the first three steps.

## Target and prerequisites

For the application being migrated:

- use JDK 21 or newer; Helidon recommends JDK 25+ for current development, but Java 21 is
  this project's minimum target;
- use Maven 3.8+ if Maven build changes should be automated;
- start from Spring Boot 4 on Jakarta APIs for the canonical path; and
- ensure application dependencies resolve so OpenRewrite can identify Spring types accurately.

The direct `SpringBootToHelidonMp` entry point can also assess another modern,
Jakarta-compatible Spring Boot baseline, but it uses the same migration steps as the Boot 4
recipe. Older Spring applications should be upgraded first. Building and testing this recipe
library itself also requires JDK 21.

Helidon MP 4 implements MicroProfile 6.1 and Jakarta EE 10 Core Profile APIs, including
Jakarta REST 3.1, CDI 4.0, MicroProfile Config 3.1, Jakarta Transactions 2.0, Jakarta
Validation 3.0, and Jakarta Persistence 3.1. Availability of an API does not imply that
v0.1 migrates every Spring feature mapped to it.

## Choose a top-level recipe

Start with the analysis recipe. Use a migration recipe only after its report and a dry run have
been reviewed.

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
eligibility has been decided. The launcher changes only when no other production Spring runtime
references remain in its module. Project-scoped safety scans run before edits in each OpenRewrite
cycle, so the canonical recipe deliberately requests follow-up cycles before it replaces the
runtime entry point.
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

Version `0.1.0` is distributed as source through GitHub Releases and is not published to Maven
Central. Build and install it into the local Maven repository first:

```bash
./mvnw --batch-mode verify
./mvnw --batch-mode install
```

From the target application's root, assess the code without changing it:

```bash
mvn -U org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun \
  -Drewrite.recipeArtifactCoordinates=io.github.devansh-ops:rewrite-spring-to-helidon:0.1.0 \
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
  -Drewrite.recipeArtifactCoordinates=io.github.devansh-ops:rewrite-spring-to-helidon:0.1.0 \
  -Drewrite.activeRecipes=io.github.devanshops.rewrite.helidon.SpringBoot4ToHelidonMp \
  -Drewrite.exportDatatables=true
```

Only after reviewing the proposed changes, apply them by replacing `dryRun` with `run`.
Use an isolated branch or worktree and validate one executable module before widening the
scope.

## Gradle consumption

The Java source recipes can run through the OpenRewrite Gradle plugin. The v0.1 build recipe
only edits Maven POMs, so Gradle dependency management and packaging must be migrated manually.
After installing version `0.1.0` locally, a Groovy build can load it as follows:

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
    rewrite 'io.github.devansh-ops:rewrite-spring-to-helidon:0.1.0'
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

## Exact v0.1 safety boundary

The detailed rules below use a few OpenRewrite and CDI terms:

- **Class-atomic** or **controller-atomic** means the whole class or controller is converted, or
  none of it is. The recipe avoids leaving a half-Spring, half-CDI class.
- **Proxy-safe** means CDI can construct, proxy, and intercept the class without a deployment-time
  failure.
- **Spring runtime residue** means Spring types, dependencies, or container behavior that the
  application still needs at runtime.

The automated core covers these REST-service mechanics:

- class-atomic conversion of proxy-safe `@Service` and `@Component` beans and eligible injection
  points;
- zero-argument producers only when `@Bean` contains the literal lifecycle opt-out
  `destroyMethod = ""`;
- one literal bean name or qualifier;
- simple `@Value` placeholders with non-empty defaults for equivalent scalar target types at
  eligible CDI injection points;
- the supported subset of transaction annotations;
- controller-atomic MVC conversion for public, proxy-safe resource classes in modules without
  unsupported Spring Web or Security infrastructure;
- common `ResponseEntity` builders inside eligible REST resources;
- one structurally plain application launcher after its module no longer needs the Spring runtime;
  and
- additive Maven preparation and missing Helidon resource scaffolds.

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
