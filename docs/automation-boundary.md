# v0.2 automation boundary

Version 0.2 separates project assessment from opt-in mutation. This is a safety contract: a collection of
locally valid edits is not automatically a valid Spring-to-Helidon runtime migration.

## Canonical recipe

`AnalyzeSpringBootToHelidonMp`, `SpringBoot4ToHelidonMp`, and its general alias remain
assessment-only in v0.2. They run two complementary scanners:

- `FindSpringUsage` marks attributed Spring imports and uses in Java source, exports one
  `SpringUsageTable` row per Spring type per source file, and suggests a target direction; and
- `FindSpringProjectUsage` exports occurrence-level `MigrationAssessmentTable` rows for bounded
  Maven; literal plugins-block IDs and string dependency/BOM coordinates in Groovy and Kotlin
  Gradle; common literal Groovy `group`/`name` map notation; Java main/test; application
  configuration; Spring XML; and Spring registration metadata evidence.

Java and build occurrences receive precise search markers. Non-POM XML, properties, YAML, and
plain-text registration metadata are table-only: their paths, constructs or keys, and reason codes
are reported without creating a dry-run patch that could reproduce configuration values,
registered class names, or neighboring XML content.

The canonical recipe does not change Java semantics, POMs, resources, dependencies, application
configuration, or the Spring Boot launcher. It is intentionally equivalent in scope to
`AnalyzeSpringBootToHelidonMp` for this release.

This boundary prevents a broken hybrid such as a CDI-only service inside an application whose
unsupported controllers, configuration, and launcher still require the Spring container.

## Directly activatable leaf recipes

Every public leaf recipe remains available, but it must be selected explicitly. A leaf's local
preconditions do not prove that the whole module is ready to leave Spring.

| Leaf | v0.2 behavior | Important boundary |
| --- | --- | --- |
| `AssessSpringBootModuleMigrationReadiness` | Unreleased read-only module assessment | Resolves supplied Maven/Gradle ownership and exports `ModuleMigrationReadinessTable` rows for the `HELIDON_MP_CONSERVATIVE` profile. Eligibility means only that no supplied artifact produced a profile blocker; it is not runtime certification and the recipe is not in a top-level composition. |
| `FindSpringUsage` | Assessment | Type-family classification is not occurrence-level proof. Review every marker and row. |
| `FindSpringProjectUsage` | Assessment | Inventories bounded project evidence without certifying readiness. Gradle detection covers literal plugins-block IDs, literal string coordinates in recognized dependency/BOM calls, and common literal Groovy `group`/`name` map notation; legacy `apply plugin:`, Kotlin named arguments, dynamic expressions, version catalogs, aliases, and unresolved models are not guessed. |
| `PrepareMavenBuildForHelidonMp` | Opt-in mutation | Additively imports `io.helidon:helidon-dependencies:4.5.3` and adds `helidon-microprofile-core` to detected executable Maven modules. It does not replace parents, remove Spring, change Java, or select feature runtimes. Dependency mediation can still change. |
| `AddHelidonMpResources` | Opt-in mutation | Adds only missing CDI 4 `beans.xml` and a comment-only `microprofile-config.properties`; existing resources are preserved. It does not migrate application configuration. |
| `MigrateSpringDiToCdi` | Opt-in mutation | Converts a class-atomically safe subset of `@Service`, `@Component`, `@Autowired`, explicit non-proxying configuration, and lifecycle-opted-out zero-argument producers. It refuses repository semantics, `@Value`, unsafe scopes/lifecycle, ambiguous injection, inheritance, and non-proxyable shapes. |
| `MigrateSpringNamedBeansToCdi` | Opt-in mutation | Preserves a bounded set of literal Spring bean names and qualifiers with CDI `@Named`. It refuses aliases, computed/composed names, producer parameters, inferred destruction, and name-fallback cases it cannot prove. |
| `MigrateSpringTransactionalToJakarta` | Opt-in bounded migration | Migrates direct, attributed Spring `@Transactional` for REQUIRED, REQUIRES_NEW, MANDATORY, NOT_SUPPORTED, and NEVER only after module and source-visible class-hierarchy preflight. One refusal preserves the atomic scope and produces a stable reason code. SUPPORTS is refused by policy. |
| `MigrateSpringTransactionalToJakartaIncludingSupports` | Opt-in bounded migration with explicit policy | Uses the exact same transaction engine and refusal gates as the base recipe, but additionally maps SUPPORTS after the caller accepts that Jakarta does not promise Spring's possible non-transactional resource-synchronization scope. |
| `MigrateSpringMvcToJakartaRest` | Assessment-only | Preserves and marks every `@RestController`; no route, parameter, return, security, validation, or error contract is rewritten in v0.2. |
| `MigrateResponseEntityToJakartaResponse` | Assessment-only | Preserves and precisely marks the direct import plus every attributed `ResponseEntity` use, including fully qualified syntax. No status, header, entity, builder, or method signature is rewritten. |
| `MigrateSpringValueToConfigProperty` | Assessment-only | Preserves and marks every `@Value`. Spring and MicroProfile Config differ for missing/empty values, scalar conversion, defaults, and application-provided converters. |
| `MigrateSpringBootMain` | Assessment-only | Preserves and marks Spring Boot bootstrap code. Source-only inspection cannot prove that starters, auto-configuration, XML, property files, service loaders, packaging, or deployment contracts are ready for a runtime switch. |

Do not blindly compose mutating leaves. Apply one to an isolated branch or worktree, inspect the
dry-run patch, compile and test the module, and retain Spring startup until all source, dependency,
configuration, test, packaging, and deployment contracts have been deliberately migrated.

## Module-atomic readiness profile in development

`AssessSpringBootModuleMigrationReadiness` is a separately activated, no-option, read-only recipe.
It evaluates the complete `SourceFile` set supplied to OpenRewrite against the stable
`HELIDON_MP_CONSERVATIVE` profile and reports `ELIGIBLE_FOR_PROFILE` or `REFUSED` per resolved
module. It does not probe the filesystem for omitted files and does not claim runnable output.

The index assigns each artifact to the deepest compatible Maven or Gradle build root. It refuses
missing, ambiguous, incomplete, cross-build, or model-disagreeing topology; known artifacts that
did not parse; unsupported Kotlin/Groovy application sources; incomplete Java attribution;
unresolved dependency, plugin, or reactor declarations; nonempty `application*` configuration;
Spring XML and registration metadata; and remaining Spring Java or build evidence. Resolved
non-Spring Maven declarations remain eligible. Ordinary Gradle `project(...)` dependencies are not
treated as reactor membership; membership comes from settings declarations.

The public output has two complementary levels:

- exactly one sanitized `MODULE_REFUSED` marker at a safe module anchor; and
- one occurrence-preserving `ModuleMigrationReadinessTable` row per blocker, with stable reason
  codes and no configuration values.

The internal coordinator freezes a complete plan in the three-argument `generate` phase. A future
migration family must claim exact evidence, replace the exact collected source, and declare every
generated path. Before commit, the coordinator rejects duplicate or unresolved claims, unclaimed
evidence removal, ineffective claims, fabricated replacements, path collisions, and Spring
residue introduced or retained by the projected originals or generated sources. Any refusal
cancels all proposed changes in that module; eligible siblings remain independent. The visitor is
decision-free and applies only frozen replacements.

This development seam does not yet supply a mutating migration family. MVC, response, launcher,
packaging, final Spring removal, and runtime validation remain separate bounded work.

## Bounded transaction migration

The v0.2 transaction recipes are directly activatable leaves and are not in any canonical
top-level recipe. They are bounded migrations: unsupported forms produce a refusal, keep the
Spring annotation, and prevent a partial rewrite of the affected atomic scope.

### Supported mapping

The base `MigrateSpringTransactionalToJakarta` recipe accepts only direct, attributed Spring
annotations on interceptable CDI beans in application source. Its propagation mapping is:

| Spring propagation | Jakarta Transactions 2.0 output |
| --- | --- |
| default or explicit `REQUIRED` | default `@Transactional` transaction type |
| `REQUIRES_NEW` | `TxType.REQUIRES_NEW` |
| `MANDATORY` | `TxType.MANDATORY` |
| `NOT_SUPPORTED` | `TxType.NOT_SUPPORTED` |
| `NEVER` | `TxType.NEVER` |
| `SUPPORTS` | refused by the base recipe; mapped to `TxType.SUPPORTS` only by `MigrateSpringTransactionalToJakartaIncludingSupports` |
| `NESTED` | refused because Jakarta Transactions has no equivalent transaction type |

Spring's default runtime-exception rollback already matches Jakarta's default. The recipe adds
`rollbackOn = Error.class` so Spring's Error behavior is not lost. A resolved Spring 7 global
`ALL_EXCEPTIONS` policy additionally produces `rollbackOn = Exception.class`; the default or
explicit `RUNTIME_EXCEPTIONS` policy does not. Attributed type-based `rollbackFor` and
`noRollbackFor` rules become `rollbackOn` and `dontRollbackOn` only when Jakarta's negative-rule
precedence produces the same answer for every supplied type relationship. Empty rule arrays and
other explicit Spring defaults are normalized safely.

### Atomic preflight and refusals

The scanner indexes the source module before editing. An unsupported annotation, target, or
governed member refuses every Spring transaction annotation in its connected source-visible class
hierarchy. A module-level policy finding refuses all transaction annotations in that module. This
is the fail-closed boundary required by ADR 0001: a refusal is deliberate source preservation, not
a best-effort partial conversion.

Each migrated or refused occurrence receives a marker and a `MigrationAssessmentTable` row. The
row reports `MIGRATED` or `REFUSED`, a stable reason code, a human-readable reason, and the target
direction. The important refusal groups are:

| Boundary | Stable reason codes |
| --- | --- |
| Source and hierarchy proof | `TX_TEST_TRANSACTION`, `TX_MISSING_ATTRIBUTION`, `TX_EXTERNAL_HIERARCHY`, `TX_COMPOSED_TRANSACTION`, `TX_ATOMIC_SCOPE_REFUSED` |
| CDI interception | `TX_NON_CDI_TARGET`, `TX_NON_INTERCEPTABLE_TARGET`, `TX_LIFECYCLE_METHOD`, `TX_USER_TRANSACTION`, `TX_REACTIVE_RETURN`, `TX_JAKARTA_COLLISION` |
| Annotation semantics | `TX_NESTED_NO_EQUIVALENT`, `TX_SUPPORTS_POLICY`, `TX_UNRESOLVED_PROPAGATION`, `TX_NON_DEFAULT_ISOLATION`, `TX_TIMEOUT_POLICY`, `TX_READ_ONLY_POLICY`, `TX_LABEL_POLICY`, `TX_MANAGER_SELECTION`, `TX_UNRESOLVED_ATTRIBUTE` |
| Rollback semantics | `TX_UNATTRIBUTED_ROLLBACK_TYPE`, `TX_PATTERN_ROLLBACK_RULE`, `TX_ROLLBACK_PRECEDENCE`, `TX_GLOBAL_ROLLBACK_UNRESOLVED`, `TX_GLOBAL_ROLLBACK_CONFLICT`, `TX_CUSTOM_ROLLBACK_POLICY` |
| Module transaction policy | `TX_ASPECTJ_MODE`, `TX_MULTIPLE_TRANSACTION_MANAGERS`, `TX_QUALIFIED_TRANSACTION_MANAGER`, `TX_XML_TRANSACTION_POLICY`, `TX_PROGRAMMATIC_TRANSACTION_POLICY`, `TX_REACTIVE_TRANSACTION_MANAGER`, `TX_REACTIVE_TRANSACTION_API`, `TX_SPRING_TRANSACTION_EXCEPTION_COUPLING` |

In practical terms, the recipe refuses final/abstract or otherwise non-interceptable targets;
unsafe class-level governed members; lifecycle callbacks; test transactions; direct
`UserTransaction` use; reactive completion; composed annotations; existing Jakarta annotation
collisions; external or unresolved hierarchy members; custom isolation, timeout, read-only,
labels, manager selection, string-pattern rules, unsafe or unattributed rollback rules; unresolved,
conflicting, custom, or AspectJ global defaults; multiple/qualified/reactive managers;
programmatic transaction policy; and Spring XML advice. Source coupling to Spring's
`IllegalTransactionStateException` refuses MANDATORY and NEVER because changing the provider's
exception type could change application behavior.

The preflight can reason only over source files and XML supplied to the OpenRewrite run. Run it
over the complete module with dependencies resolvable for type attribution. It does not infer
policy hidden in excluded files, external bytecode, generated runtime state, infrastructure, or
deployment configuration.

### Explicit SUPPORTS policy

Spring SUPPORTS can establish a resource-synchronization scope even without an actual transaction.
Jakarta SUPPORTS promises only that an existing transaction is joined and that a new transaction
is not started. For that reason, the base recipe reports `TX_SUPPORTS_POLICY`.

`MigrateSpringTransactionalToJakartaIncludingSupports` is the explicit policy opt-in. It uses the
same scanner, rollback rules, module gates, hierarchy atomicity, markers, and refusal codes. Its
only semantic delta is accepting that Spring's extra non-transactional synchronization scope is
not part of the target contract. Running the base recipe and then the opt-in is not a supported way
to split a hierarchy; activate one recipe for the whole source module.

### Differential runtime contract

`scripts/transaction-contract-test.sh` runs a deterministic, real-resource differential contract:

- Spring Boot 4.1.0 executes the unchanged CDI-ready subject against in-memory H2;
- the base recipe rewrites an operating-system-temporary copy, which must compile with Helidon MP
  4.5.3 and contain no Spring source or runtime dependency;
- the two runtimes must produce identical evidence for REQUIRED create/join, REQUIRES_NEW
  independence and outer resume, MANDATORY and NEVER context rejection, NOT_SUPPORTED
  suspend/resume, checked/runtime/Error outcomes, and a safe positive/negative rollback hierarchy;
  and
- separate base and opt-in subjects ensure SUPPORTS remains refused by default. The opt-in must
  match join/no-transaction commit behavior; provider-specific synchronization evidence is checked
  against separate literal expectations and excluded from the common comparison.

The fixture pins Spring's `JdbcTransactionManager` plus `TransactionAwareDataSourceProxy` and
Helidon's Narayana-backed Jakarta Transactions CDI integration plus `JtaAdaptingDataSource` with
immediate enlistment. It assumes public, non-final cross-bean interception and checks each
provider's documented illegal-context exception in the cause chain. It uses Java 21, local H2, no
containers, no credentials, and no external service. These are provider assumptions for the
bounded evidence, not a claim that an arbitrary application has the same datasource, interception,
or deployment contract. See the [fixture documentation](../src/it/transaction-contract/README.md)
for the executable details.

## Search support levels

The inventory records a support level for the type family:

- `PARTIAL`: a bounded, directly activatable transformation exists for some occurrences, but the
  canonical recipe does not apply it and occurrence-level preconditions still decide eligibility;
- `MANUAL`: v0.2 preserves this family and intends no canonical source-semantic transformation; and
- `AUTOMATIC`: reserved by the data-table schema for a future canonical transformation with an
  adequate atomicity boundary. v0.2 currently reports no Spring type family as automatic.

The combined inventory is not a complete migration checklist. It records bounded source and
project evidence, but it does not resolve dynamic build expressions, inherited or externally
managed configuration, runtime-generated behavior, packaging, deployment descriptors, or
external infrastructure. Absence of a row is not proof that a module is ready to leave Spring.

## What v0.2 does not migrate

These areas need bounded future recipes, explicit target policies, or application-specific design:

| Spring area | Likely target direction | Why it is not a direct annotation swap |
| --- | --- | --- |
| Spring MVC and HTTP responses | Jakarta REST resources, providers, and exception mappers | Binding sources, required/default values, conversion failures, negotiation, status, headers, generic entities, and direct callers differ. |
| Spring Security | Helidon Security, MicroProfile JWT, or Jakarta Security | Filter chains, providers, CSRF, sessions, URL rules, method rules, and identity propagation are policy. |
| Configuration | MicroProfile Config plus explicit compatibility code | Precedence, profiles, missing/empty behavior, converters, secrets, and file formats differ. |
| Transactions outside the bounded direct-annotation subset | Jakarta Transactions plus an explicitly selected provider and datasource policy | NESTED, synchronization scope, isolation, timeout, read-only behavior, routing, reactive completion, tests, XML/programmatic advice, and deployment integration require target policy. |
| Spring Data and persistence | Jakarta Persistence DAOs or an evaluated data framework | Derived queries, fragments, paging, auditing, provider setup, and transaction boundaries carry behavior. |
| WebFlux/Reactor | Jakarta REST async APIs or a deliberate Helidon SE design | Backpressure, threading, context propagation, cancellation, and streaming change architecture. |
| Spring Cloud | Component-specific MicroProfile or Helidon facilities | Configuration, discovery, gateways, resilience, and tracing need independent choices. |
| Batch, Integration, Kafka, messaging | Jakarta Batch or selected clients/connectors | Delivery, retry, ordering, checkpoint, and transaction guarantees are workload-specific. |
| Actuator, metrics, and health | MicroProfile Health/Metrics or a selected observability stack | Endpoint exposure, probe meaning, tags, registries, and operational contracts must be retained deliberately. |
| Scheduling and caching | Selected scheduler/concurrency and cache integrations | Timing, overlap, keys, eviction, locking, and cluster behavior are not annotation synonyms. |
| AOP, events, application context | CDI interceptors, decorators, events, `Instance`, or `BeanManager` | Proxy boundaries, ordering, lifecycle, and dynamic lookup semantics differ. |
| HTTP clients | MicroProfile REST Client, Jakarta REST Client, or Helidon WebClient | Errors, interceptors, timeouts, retries, pooling, and observability must be redesigned. |
| Tests and deployment | Helidon testing plus selected packaging/runtime | Spring test contexts, slices, mocks, profiles, containers, native images, and deployment descriptors are framework-specific. |

Portable Jakarta Validation and Persistence annotations may already survive a Boot 4 baseline, but
v0.2 does not configure their providers, datasource, persistence unit, transaction manager, schema
lifecycle, tests, or runtime packaging. It also has no Spring-removal finalizer.
