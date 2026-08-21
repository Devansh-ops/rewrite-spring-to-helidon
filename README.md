# OpenRewrite Spring Boot to Helidon MP

An independent OpenRewrite recipe library for assessing and incrementally migrating Spring Boot
applications to Helidon MP. The recipes are generalized for conventional Maven and
Gradle projects, including large multi-module Maven reactors.

This project is not affiliated with or endorsed by OpenRewrite, Spring, or Helidon.

The current release is `0.2.0` and targets
[Helidon MP 4.5.3](https://github.com/helidon-io/helidon/releases/tag/4.5.3). This is intentionally
an assessment-first migration scaffold, not a one-command framework replacement. The canonical
recipe inventories Spring source and project usage and changes no application semantics, build
files, resources, or runtime launcher. Bounded migration leaves are available for deliberate,
separately reviewed steps.

> **Preview status:** v0.2 does not provide an application-wide atomic
> runtime migration. Do not compose mutating leaf recipes as though they were a complete migration
> pipeline. Always use an isolated branch, dry-run one leaf at a time, and compile and test the
> affected module.

> **Distribution status:** `0.2.0` remains a source-only GitHub release and has never been
> published to Maven Central. The repository is currently on `0.2.1-SNAPSHOT` and includes a
> protected, signed Central publication pipeline for the next release. See
> [publishing to Maven Central](docs/publishing.md) for the credential gate and release process.

## In plain English

This project first answers: “Where does this application depend on Spring, and which target API
might replace each dependency?” The top-level recipes export a Java type inventory and a bounded
project-artifact inventory. They add precise review markers for Java and build constructs.
Configuration files, registration metadata, and non-POM XML are table-only so their values and
neighboring content are not reproduced in a dry-run patch. Activating a top-level recipe does not
change the running framework.

It follows one safety rule: **when the recipe cannot prove that a change preserves behavior, it
leaves that code on Spring and marks it for review.** The expected canonical result is an actionable
inventory, not a partly converted or production-ready application.

### What the opt-in leaf recipes can change

These transformations are not part of the canonical recipe. Activate one explicitly only after
reviewing its exact boundary and the surrounding module.

| Existing Spring code | Helidon MP-compatible result |
| --- | --- |
| `@Service` or `@Component` | CDI `@ApplicationScoped` and `@Named` |
| Eligible `@Autowired` injection | Jakarta `@Inject` |
| A zero-argument `@Bean(destroyMethod = "")` | CDI `@Produces`, `@Singleton`, and `@Named` |
| An executable Maven module, when the build/resource leaves are selected | Additive Helidon dependency management, MP core, CDI discovery, and an empty MP Config scaffold |
| A direct, attributed Spring `@Transactional` that satisfies every v0.2 transaction preflight | Jakarta `@Transactional` with an equivalent transaction type and explicit rollback rules |

Spring MVC, `ResponseEntity`, Spring Boot launcher, and `@Value` leaf recipes remain
assessment-only. In v0.2, `MigrateSpringTransactionalToJakarta` is a bounded migration for a
deliberately narrow transaction subset, and
`MigrateSpringTransactionalToJakartaIncludingSupports` is a separate opt-in for the additional
SUPPORTS policy. The CDI leaves preserve an entire Spring bean when any member uses `@Value`.

The recipe does **not** automatically port security, repositories, application property files,
messaging, reactive code, tests, or deployment architecture. It also does not remove Spring
dependencies. The combined reports inventory remaining Spring types and bounded build,
configuration, XML, metadata, and test evidence; they are not a complete migration checklist or
runtime-readiness certification. Packaging, deployment architecture, runtime-generated behavior,
inherited external configuration, and other application-specific concerns require a separate
manual audit.

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
   `SpringBoot4ToHelidonMp`; both remain assessment-only in v0.2.
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
Jakarta-compatible Spring Boot baseline, but it uses the same assessment steps as the Boot 4
recipe. Older Spring applications should be upgraded first. Building and testing this recipe
library itself also requires JDK 21.

Helidon MP 4 implements MicroProfile 6.1 and Jakarta EE 10 Core Profile APIs, including
Jakarta REST 3.1, CDI 4.0, MicroProfile Config 3.1, Jakarta Transactions 2.0, Jakarta
Validation 3.0, and Jakarta Persistence 3.1. Availability of an API does not imply that this
project migrates every Spring feature mapped to it.

## Choose a top-level recipe

Start with either assessment entry point. They intentionally have the same v0.2 behavior; the
additional names preserve a stable path for later bounded compositions.

| Recipe | Purpose |
| --- | --- |
| `io.github.devanshops.rewrite.helidon.AnalyzeSpringBootToHelidonMp` | Inventories Spring Java types and bounded project artifacts without changing application semantics. Java/build evidence is marked; configuration, registration metadata, and non-POM XML are table-only. |
| `io.github.devanshops.rewrite.helidon.SpringBoot4ToHelidonMp` | Canonical, fail-closed assessment for applications already on Spring Boot 4; changes no source semantics, build files, resources, or launcher. |
| `io.github.devanshops.rewrite.helidon.SpringBootToHelidonMp` | General assessment alias for applications already on a modern Jakarta-compatible Spring baseline. |
| `io.github.devanshops.rewrite.helidon.SpringBootToHelidonMpViaBoot4` | Optional wrapper that first runs the separately supplied Spring Boot 4 upgrade recipe, then assesses the result. The upstream upgrade is a real source/build mutation. See [licensing](#optional-spring-boot-4-normalization-and-licensing). |

The canonical composition runs in this order:

```text
SpringBoot4ToHelidonMp
  1. FindSpringUsage
  2. FindSpringProjectUsage
```

Earlier prototypes composed independent source, build, resource, and launcher transformations.
That can create a broken hybrid when one Spring bean converts to CDI while unsupported Spring code
keeps the Spring runtime active. Until an application-wide atomic preflight exists, v0.2 keeps
those mutations out of the canonical recipe.

See [the detailed automation boundary](docs/automation-boundary.md) for the exact supported
subsets and manual-review cases.

Every leaf recipe remains directly activatable for focused experiments and staged engineering.
Mutation leaves are advanced opt-ins: their local checks do not prove that the whole module is
ready to leave Spring.

| Leaf recipe | Scope |
| --- | --- |
| `AddHelidonMpResources` | Opt-in: adds missing CDI and MicroProfile Config scaffolds to executable modules. |
| `AssessSpringBootModuleMigrationReadiness` | Unreleased, read-only: resolves supplied Maven/Gradle module ownership and reports whether each complete supplied module has any blocker for the `HELIDON_MP_CONSERVATIVE` profile. It does not certify runtime readiness or mutate source. |
| `FindSpringUsage` | Marks and exports a classified inventory of remaining Spring types. |
| `FindSpringProjectUsage` | Exports occurrence-level bounded Maven, Gradle, Java source-set, configuration, XML, and Spring registration metadata evidence without changing semantics. |
| `PrepareMavenBuildForHelidonMp` | Opt-in: adds Helidon dependency management and MP core without removing Spring. |
| `MigrateResponseEntityToJakartaResponse` | Assessment-only: preserves and marks `ResponseEntity` use. |
| `MigrateSpringBootMain` | Assessment-only: preserves and marks Spring Boot startup. |
| `MigrateSpringDiToCdi` | Opt-in: converts a locally proxy-safe subset of stereotypes, injection points, and producers to CDI. |
| `MigrateSpringMvcToJakartaRest` | Assessment-only: preserves and marks Spring MVC REST controllers. |
| `MigrateSpringNamedBeansToCdi` | Opt-in: converts a locally safe named-bean subset to CDI `@Named`. |
| `MigrateSpringTransactionalToJakarta` | v0.2 opt-in: migrates a module-gated, class-hierarchy-atomic subset of direct Spring transaction annotations; otherwise records a refusal and preserves the whole affected hierarchy. |
| `MigrateSpringTransactionalToJakartaIncludingSupports` | v0.2 opt-in: runs the same transaction preflight and additionally accepts the documented SUPPORTS synchronization-scope difference. |
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

The public `@Value` leaf recipe ID remains available for compatibility, but it is
assessment/refusal-only. A later bounded or compatibility-generating recipe can automate this
after proving the source and target configuration semantics.

## Quick start with Maven

Version `0.2.0` is distributed as source through GitHub Releases and is not published to Maven
Central. Download its source archive or check out tag `v0.2.0`, then build and install it into the
local Maven repository:

```bash
./mvnw --batch-mode verify
./mvnw --batch-mode install
```

From the target application's root, assess the code without changing it:

```bash
mvn -U org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun \
  -Drewrite.recipeArtifactCoordinates=io.github.devansh-ops:rewrite-spring-to-helidon:0.2.0 \
  -Drewrite.activeRecipes=io.github.devanshops.rewrite.helidon.AnalyzeSpringBootToHelidonMp \
  -Drewrite.exportDatatables=true
```

Review both the dry-run patch and the CSVs under
`target/rewrite/datatables/<timestamp>/`. The Spring usage inventory contains the source
path, feature family, Spring type, support level, and suggested replacement. The project assessment
table adds source kind, artifact construct, outcome, stable reason code, and target direction. v0.2
reports bounded leaf coverage as `PARTIAL` and assessment-only families as `MANUAL`; `AUTOMATIC` is
reserved for a future application-atomic canonical migration.

The canonical compatibility entry point produces the same assessment in v0.2:

```bash
mvn -U org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun \
  -Drewrite.recipeArtifactCoordinates=io.github.devansh-ops:rewrite-spring-to-helidon:0.2.0 \
  -Drewrite.activeRecipes=io.github.devanshops.rewrite.helidon.SpringBoot4ToHelidonMp \
  -Drewrite.exportDatatables=true
```

There is normally no reason to replace `dryRun` with `run` for a top-level v0.2 recipe: its printed
changes are search markers. To experiment with a mutating leaf, replace the active recipe with that
leaf's fully qualified ID, keep `dryRun`, and review its section in
[the detailed automation boundary](docs/automation-boundary.md). Apply it only in an isolated branch
or worktree and validate one executable module before widening the scope.

### Run the bounded v0.2 transaction migration

After installing version `0.2.0` locally, dry-run the bounded base transaction recipe from the
target application's root:

```bash
mvn -U org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun \
  -Drewrite.recipeArtifactCoordinates=io.github.devansh-ops:rewrite-spring-to-helidon:0.2.0 \
  -Drewrite.activeRecipes=io.github.devanshops.rewrite.helidon.MigrateSpringTransactionalToJakarta \
  -Drewrite.exportDatatables=true
```

The dry run either shows a complete migration for an eligible source-visible class hierarchy or
preserves its Spring annotations and emits stable refusal reason codes. Use
`MigrateSpringTransactionalToJakartaIncludingSupports` only after accepting the SUPPORTS boundary
described below. Neither transaction leaf is part of a top-level recipe.

## Gradle consumption

The Java source recipes can run through the OpenRewrite Gradle plugin. The v0.2 build recipe
only edits Maven POMs, so Gradle dependency management and packaging must be migrated manually.
After installing version `0.2.0` locally, a Groovy build can load it as follows:

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
    rewrite 'io.github.devansh-ops:rewrite-spring-to-helidon:0.2.0'
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

The build and resource recipes below are direct opt-ins and are **not** in the canonical v0.2
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

## Bounded transaction migration in v0.2

`MigrateSpringTransactionalToJakarta` is a directly activated bounded migration. In plain English,
it changes Spring's annotation only when it can see enough of the module and the connected class
hierarchy to preserve the supported behavior. If one annotation in that atomic scope is unsafe,
the recipe preserves every Spring transaction annotation in the scope and reports why.

The base recipe supports direct, attributed annotations on interceptable CDI beans in main source
for these Spring propagation modes:

- the default and explicit `REQUIRED`;
- `REQUIRES_NEW`;
- `MANDATORY`;
- `NOT_SUPPORTED`; and
- `NEVER`.

It maps them to Jakarta Transactions 2.0 transaction types. It also makes Spring's Error rollback
default explicit, honors a resolved Spring 7 `ALL_EXCEPTIONS` global default, and carries local
type-based `rollbackFor` and `noRollbackFor` rules only when their precedence remains equivalent.
Explicit Spring default values are normalized rather than treated as custom policy.

The base recipe deliberately refuses SUPPORTS because Spring can create a non-transactional
resource-synchronization scope where Jakarta Transactions promises only that no transaction is
started. `MigrateSpringTransactionalToJakartaIncludingSupports` has the same scanner, atomicity,
rollback handling, and refusal gates; its only policy difference is accepting that SUPPORTS
synchronization-scope difference. It maps SUPPORTS both when joining an active transaction and
when running without one.

Both recipes preserve the affected hierarchy and emit a marker plus a
`MigrationAssessmentTable` row with a stable reason code when they encounter any of these
boundaries:

- test-managed transactions, composed annotations, missing type attribution, an external or
  unresolved hierarchy, or an existing Jakarta `@Transactional` on the same target;
- a non-CDI or non-interceptable target, class-level advice governing an unsafe member, lifecycle
  callbacks, direct `UserTransaction` use, or reactive return types;
- `NESTED`, unresolved propagation, non-default isolation, timeout, read-only, labels, manager
  selection, string-pattern rollback rules, unattributed rollback classes, or rollback precedence
  that Jakarta cannot preserve;
- unresolved, conflicting, custom, or AspectJ global transaction policy;
- multiple or qualified transaction managers, reactive managers/APIs, programmatic transaction
  policy, or Spring XML transaction advice; and
- source code coupled to Spring's transaction-state exceptions for MANDATORY or NEVER.

This is fail-closed behavior: a refusal is a deliberate preservation, not a partial conversion or
an accidental skip. The transaction leaves do not configure a datasource, select a transaction
provider, convert transaction-manager beans or XML, remove Spring, or prove that the application is
ready to switch runtimes. See [the exact transaction boundary](docs/automation-boundary.md#bounded-transaction-migration)
for the complete contract.

The deterministic H2 contract fixture executes the supported behavior first on Spring Boot 4.1.0
and then on rewritten source compiled and run with Helidon MP 4.5.3. It covers propagation,
commit/rollback behavior, safe rollback-rule precedence, source compilation, and absence of Spring
source and runtime dependencies. The separately activated SUPPORTS fixture compares the common
transaction behavior while explicitly excluding Spring's extra synchronization-scope observation.
The fixture uses pinned provider assumptions; it is evidence for this bounded recipe, not a promise
about an application's datasource, transaction manager, or deployment environment.

## Exact canonical safety boundary

The canonical guarantee is intentionally small:

- it runs `FindSpringUsage` and `FindSpringProjectUsage`;
- it exports one classified Java row per Spring type per source file plus occurrence-level bounded
  project evidence;
- it marks Java and build occurrences while keeping configuration, registration metadata, and
  non-POM XML table-only; and
- it does not change application semantics, POMs, resources, dependencies, or launchers.

The directly activatable CDI and v0.2 transaction leaves contain bounded migrations. The build and
resource leaves provide additive scaffolding. MVC, `ResponseEntity`, `@Value`, and launcher leaves
remain assessment-only. None of these leaf-level decisions proves that an entire application can
switch runtimes atomically, and combining mutations can create a broken Spring/CDI hybrid. Treat
the leaves as independent engineering tools, not as a complete pipeline.

The following remain explicit engineering work in v0.2:

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
- transaction isolation, timeout, read-only hints, labels, manager routing, XML/programmatic
  policy, reactive managers, `UserTransaction`, AspectJ mode, composed annotations, test-managed
  transactions, unsafe rollback precedence, and any hierarchy the transaction preflight cannot
  resolve completely;
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
There is no application-wide atomic runtime switch or Spring-removal finalizer in v0.2.

## Module-atomic readiness assessment in development

The current development source adds a separately activated, no-option recipe:

```text
io.github.devanshops.rewrite.helidon.AssessSpringBootModuleMigrationReadiness
```

It builds a module index from the complete set of source files supplied to OpenRewrite. The
deepest Maven or Gradle build root owns each artifact. Missing or incompatible reactor children,
ambiguous ownership, unparsed known artifacts, unsupported Kotlin/Groovy application source,
missing Java attribution, unresolved build declarations, application configuration, Spring XML,
registration metadata, and remaining Spring source/build evidence all refuse the affected module.
An `ELIGIBLE_FOR_PROFILE` row means only that no supplied artifact violates the documented
`HELIDON_MP_CONSERVATIVE` profile. It is not proof that omitted files, runtime-generated behavior,
infrastructure, packaging, or deployment are ready for Helidon.

Refused modules receive exactly one sanitized marker at a safe module anchor. Every blocker is
exported separately through `ModuleMigrationReadinessTable`; configuration values and neighboring
XML or metadata content are never copied into marker text or table cells. Buildless groups that
have no safe anchor remain table-only.

The package-private coordinator behind this assessment is the atomic seam for future bounded
migration families. Families must claim exact evidence occurrences, propose replacements for the
exact collected source, and declare generated paths before the plan freezes. The coordinator then
re-scans the projected module. Duplicate claims, unclaimed removals, remaining or newly introduced
residue, invalid replacements, generated-path collisions, or any other refusal discard every
planned change for that module while leaving eligible siblings independent. The apply phase only
reads the frozen plan; it makes no new decisions.

This recipe is intentionally absent from the v0.2 canonical top-level recipes. The readiness
assessment is read-only, and the module-atomic MVC, response, launcher, and finalizer families are
separate future work.

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
./mvnw -Dtest=MigrateSpringTransactionalToJakartaTest,MigrateSpringTransactionalToJakartaIncludingSupportsTest test
./mvnw install
./scripts/smoke-test.sh
./scripts/transaction-contract-test.sh
```

Recipe implementations live under `src/main/java`, declarative compositions under
`src/main/resources/META-INF/rewrite`, and focused `RewriteTest` cases under `src/test/java`.
When adding automation, include both a successful conversion test and a test proving that
unsupported semantics are preserved and marked for review. The transaction contract installs the
current version locally, creates generated source under an operating-system temporary directory,
and runs deterministic Spring Boot and Helidon MP H2 fixtures; allow time for both nested reactors.

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
