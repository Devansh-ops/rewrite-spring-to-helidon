# OpenRewrite Spring Boot to Helidon MP

An independent OpenRewrite recipe library for assessing and incrementally migrating Spring Boot
applications to Helidon MP. The recipes are generalized for conventional Maven and
Gradle projects, including large multi-module Maven reactors.

This project is not affiliated with or endorsed by OpenRewrite, Spring, or Helidon.

Version `0.1.0` targets [Helidon MP 4.5.3](https://github.com/helidon-io/helidon/releases/tag/4.5.3).
It is intentionally an assessment-first migration scaffold, not a one-command framework
replacement. The canonical v0.1 recipe inventories Spring source usage and changes no application
semantics, build files, resources, or runtime launcher. Bounded leaf recipes are available for
deliberate, separately reviewed migration steps.

> **Preview status:** v0.1 does not provide a module-atomic runtime migration. Do not compose its
> mutating leaf recipes as though they were a complete migration pipeline. Always use an isolated
> branch, dry-run one leaf at a time, and compile and test the affected module.

## In plain English

This project first answers: “Where does this application depend on Spring, and which target API
might replace each dependency?” It adds review markers to a dry-run patch and exports a data table.
It does not change the running framework when you activate a top-level v0.1 recipe.

It follows one safety rule: **when the recipe cannot prove that a change preserves behavior, it
leaves that code on Spring and marks it for review.** The expected canonical result is an actionable
inventory, not a partly converted or production-ready application.

### What the opt-in leaf recipes can change

These transformations are not part of the canonical v0.1 recipe. Activate one explicitly only
after reviewing its exact boundary and the surrounding module.

| Existing Spring code | Helidon MP-compatible result |
| --- | --- |
| `@Service` or `@Component` | CDI `@ApplicationScoped` and `@Named` |
| Eligible `@Autowired` injection | Jakarta `@Inject` |
| A zero-argument `@Bean(destroyMethod = "")` | CDI `@Produces`, `@Singleton`, and `@Named` |
| A bare default Spring transaction annotation on an eligible CDI bean | Jakarta Transactions `@Transactional(rollbackOn = Error.class)` |
| An executable Maven module, when the build/resource leaves are selected | Additive Helidon dependency management, MP core, CDI discovery, and an empty MP Config scaffold |

Spring MVC, `ResponseEntity`, Spring Boot launcher, and `@Value` leaf recipes are assessment-only in
v0.1. They preserve source behavior and mark the relevant code for explicit migration. The CDI
leaves also preserve an entire Spring bean when any member uses `@Value`.

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
| `@Service`, `@Component`, and explicitly resolvable `@Autowired` usage | Extensive custom scopes, bean lifecycle hooks, AOP, `@Value`, or dynamic application-context access |
| Maven projects, including multi-module reactors | Gradle projects needing automatic build migration |
| A staged migration where each module will be compiled and tested | An expectation that one recipe run will produce a deployable Helidon application |

### Recommended workflow

```text
Assess without semantic edits -> Review the report -> Select one leaf -> Dry-run -> Compile and test one module
```

1. Run `AnalyzeSpringBootToHelidonMp` or the compatibility entry point
   `SpringBoot4ToHelidonMp`; both are assessment-only in v0.1.
2. Review its patch markers and exported data tables, especially all `PARTIAL` and `MANUAL`
   findings.
3. Choose one directly activatable leaf recipe whose documented boundary matches the module.
4. Dry-run that leaf, inspect every change, then compile and test the affected module.
5. Do not switch launchers or remove Spring until all runtime, configuration, dependency, test,
   and deployment contracts have been migrated and validated.

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

Start with either assessment entry point. They intentionally have the same v0.1 behavior; the
additional names preserve a stable path for later bounded compositions.

| Recipe | Purpose |
| --- | --- |
| `io.github.devanshops.rewrite.helidon.AnalyzeSpringBootToHelidonMp` | Finds Spring types, adds source markers in the dry-run patch, and exports a support-level data table without changing application semantics. |
| `io.github.devanshops.rewrite.helidon.SpringBoot4ToHelidonMp` | Canonical, fail-closed v0.1 assessment for applications already on Spring Boot 4; changes no source semantics, build files, resources, or launcher. |
| `io.github.devanshops.rewrite.helidon.SpringBootToHelidonMp` | General assessment alias for applications already on a modern Jakarta-compatible Spring baseline. |
| `io.github.devanshops.rewrite.helidon.SpringBootToHelidonMpViaBoot4` | Optional wrapper that first runs the separately supplied Spring Boot 4 upgrade recipe, then assesses the result. The upstream upgrade is a real source/build mutation. See [licensing](#optional-spring-boot-4-normalization-and-licensing). |

The canonical composition runs in this order:

```text
SpringBoot4ToHelidonMp
  1. FindSpringUsage
```

Earlier prototypes composed independent source, build, resource, and launcher transformations.
That can create a broken hybrid when one Spring bean converts to CDI while unsupported Spring code
keeps the Spring runtime active. Until a module-wide atomic preflight exists, v0.1 keeps those
mutations out of the canonical recipe.

See [the detailed automation boundary](docs/automation-boundary.md) for the exact supported
subsets and manual-review cases.

Every leaf recipe remains directly activatable for focused experiments and staged engineering.
Mutation leaves are advanced opt-ins: their local checks do not prove that the whole module is
ready to leave Spring.

| Leaf recipe | Scope |
| --- | --- |
| `AddHelidonMpResources` | Opt-in: adds missing CDI and MicroProfile Config scaffolds to executable modules. |
| `FindSpringUsage` | Marks and exports a classified inventory of remaining Spring types. |
| `PrepareMavenBuildForHelidonMp` | Opt-in: adds Helidon dependency management and MP core without removing Spring. |
| `MigrateResponseEntityToJakartaResponse` | Assessment-only in v0.1: preserves and marks `ResponseEntity` use. |
| `MigrateSpringBootMain` | Assessment-only in v0.1: preserves and marks Spring Boot startup. |
| `MigrateSpringDiToCdi` | Opt-in: converts a locally proxy-safe subset of stereotypes, injection points, and producers to CDI. |
| `MigrateSpringMvcToJakartaRest` | Assessment-only in v0.1: preserves and marks Spring MVC REST controllers. |
| `MigrateSpringNamedBeansToCdi` | Opt-in: converts a locally safe named-bean subset to CDI `@Named`. |
| `MigrateSpringTransactionalToJakarta` | Opt-in: converts only bare default Spring transactions on proven CDI beans and marks all other transaction semantics. |
| `MigrateSpringValueToConfigProperty` | Preserves and marks every `@Value` injection point for explicit configuration-contract migration. |

For example, this service is deliberately not half-converted:

```java
@Service
class CatalogService {
    @Value("${catalog.region:us-east}")
    String region;
}
```

The canonical recipe preserves both Spring annotations and adds review markers in the dry-run
patch. It does not produce the tempting bare `@ConfigProperty` substitution because missing values,
empty values, scalar conversion, and application-provided converters can behave differently. The
markers are shown as ordinary comments here for readability:

```java
/* manual migration: keep this bean on Spring until its configuration contract is migrated */
@Service
class CatalogService {
    /* manual migration: choose compatible MP Config lookup/conversion behavior */
    @Value("${catalog.region:us-east}")
    String region;
}
```

The public leaf recipe ID remains available for compatibility, but it is assessment/refusal-only in
v0.1. A later bounded or compatibility-generating recipe can automate this after proving the
source and target configuration semantics.

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
path, feature family, Spring type, support level, and suggested replacement. v0.1 reports bounded
leaf coverage as `PARTIAL` and assessment-only families as `MANUAL`; `AUTOMATIC` is reserved for a
future module-atomic canonical migration.

The canonical compatibility entry point produces the same assessment in v0.1:

```bash
mvn -U org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun \
  -Drewrite.recipeArtifactCoordinates=io.github.devansh-ops:rewrite-spring-to-helidon:0.1.0 \
  -Drewrite.activeRecipes=io.github.devanshops.rewrite.helidon.SpringBoot4ToHelidonMp \
  -Drewrite.exportDatatables=true
```

There is normally no reason to replace `dryRun` with `run` for a top-level v0.1 recipe: its printed
changes are search markers. To experiment with a mutating leaf, replace the active recipe with that
leaf's fully qualified ID, keep `dryRun`, and review its section in
[the detailed automation boundary](docs/automation-boundary.md). Apply it only in an isolated branch
or worktree and validate one executable module before widening the scope.

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

The build and resource recipes below are direct opt-ins and are **not** in the canonical v0.1
composition. Adding target dependencies can change dependency mediation even though nothing is
removed, so review and test the affected reactor.

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

The canonical v0.1 guarantee is intentionally small:

- it runs `FindSpringUsage` only;
- it adds search markers and exports one classified row per Spring type per Java source file; and
- it does not change application semantics, POMs, resources, dependencies, or launchers.

The directly activatable CDI and transaction leaves contain bounded transformations. The build and
resource leaves provide additive scaffolding. MVC, `ResponseEntity`, `@Value`, and launcher leaves
are assessment-only in v0.1. None of these leaf-level decisions proves that an entire module can
switch runtimes atomically, and combining them can create a broken Spring/CDI hybrid. Treat them as
independent engineering tools, not as a complete pipeline.

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
- static `@Autowired` injection;
- transaction rollback rules, propagation, isolation, timeout, read-only hints, labels, manager
  selection, class-local partial conversion, reactive managers, `UserTransaction`, AspectJ mode,
  and explicit global transaction-management settings;
- every bare Spring `@Value` injection point, including simple scalars, because Spring and
  MicroProfile Config differ for missing values, empty values, scalar conversion, and custom
  converters;
- every Spring MVC controller and `ResponseEntity` contract, including parameter binding, content
  negotiation, status, headers, generic entities, error handling, and direct Java callers;
- `RestTemplate`, `WebClient`, declarative clients, and client resilience behavior;
- JPA provider, transaction manager, datasource, validation, and test-runtime configuration;
- Spring test slices, mocks, test lifecycle, deployment descriptors, containers, and packaging; and
- final Spring dependency removal and residue enforcement.

The canonical recipe leaves all Spring code in place and adds search markers instead of guessing.
There is no module-atomic runtime switch or Spring-removal finalizer in v0.1.

## Optional Spring Boot 4 normalization and licensing

`SpringBootToHelidonMpViaBoot4` composes
`org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0` before the canonical assessment. It is
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
