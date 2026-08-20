# v0.1 automation boundary

Version 0.1 separates assessment from mutation. This is a safety contract: a collection of
locally valid edits is not automatically a valid Spring-to-Helidon runtime migration.

## Canonical recipe

`SpringBoot4ToHelidonMp` and its general alias are assessment-only in v0.1. They run
`FindSpringUsage`, which:

- marks attributed Spring imports and uses in Java source;
- exports one data-table row per Spring type per source file; and
- suggests a Jakarta, MicroProfile, Helidon, or manual migration direction.

The canonical recipe does not change Java semantics, POMs, resources, dependencies, application
configuration, or the Spring Boot launcher. It is intentionally equivalent in scope to
`AnalyzeSpringBootToHelidonMp` for this release.

This boundary prevents a broken hybrid such as a CDI-only service inside an application whose
unsupported controllers, configuration, and launcher still require the Spring container.

## Directly activatable leaf recipes

Every public leaf recipe remains available, but it must be selected explicitly. A leaf's local
preconditions do not prove that the whole module is ready to leave Spring.

| Leaf | v0.1 behavior | Important boundary |
| --- | --- | --- |
| `FindSpringUsage` | Assessment | Type-family classification is not occurrence-level proof. Review every marker and row. |
| `PrepareMavenBuildForHelidonMp` | Opt-in mutation | Additively imports `io.helidon:helidon-dependencies:4.5.3` and adds `helidon-microprofile-core` to detected executable Maven modules. It does not replace parents, remove Spring, change Java, or select feature runtimes. Dependency mediation can still change. |
| `AddHelidonMpResources` | Opt-in mutation | Adds only missing CDI 4 `beans.xml` and a comment-only `microprofile-config.properties`; existing resources are preserved. It does not migrate application configuration. |
| `MigrateSpringDiToCdi` | Opt-in mutation | Converts a class-atomically safe subset of `@Service`, `@Component`, `@Autowired`, explicit non-proxying configuration, and lifecycle-opted-out zero-argument producers. It refuses repository semantics, `@Value`, unsafe scopes/lifecycle, ambiguous injection, inheritance, and non-proxyable shapes. |
| `MigrateSpringNamedBeansToCdi` | Opt-in mutation | Preserves a bounded set of literal Spring bean names and qualifiers with CDI `@Named`. It refuses aliases, computed/composed names, producer parameters, inferred destruction, and name-fallback cases it cannot prove. |
| `MigrateSpringTransactionalToJakarta` | Opt-in mutation | On a proven, interceptable CDI bean, converts only bare Spring `@Transactional` to Jakarta `@Transactional(rollbackOn = Error.class)`. It refuses rollback rules, propagation, isolation, timeout, read-only, labels, manager selection, unsupported overrides, reactive infrastructure, `UserTransaction`, AspectJ mode, and explicit global settings. XML and custom programmatic policies still require audit. |
| `MigrateSpringMvcToJakartaRest` | Assessment-only | Preserves and marks every `@RestController`; no route, parameter, return, security, validation, or error contract is rewritten in v0.1. |
| `MigrateResponseEntityToJakartaResponse` | Assessment-only | Preserves and marks every attributed or fully qualified `ResponseEntity` use; no status, header, entity, builder, or method signature is rewritten. |
| `MigrateSpringValueToConfigProperty` | Assessment-only | Preserves and marks every `@Value`. Spring and MicroProfile Config differ for missing/empty values, scalar conversion, defaults, and application-provided converters. |
| `MigrateSpringBootMain` | Assessment-only | Preserves and marks Spring Boot bootstrap code. Source-only inspection cannot prove that starters, auto-configuration, XML, property files, service loaders, packaging, or deployment contracts are ready for a runtime switch. |

Do not blindly compose mutating leaves. Apply one to an isolated branch or worktree, inspect the
dry-run patch, compile and test the module, and retain Spring startup until all source, dependency,
configuration, test, packaging, and deployment contracts have been deliberately migrated.

## Search support levels

The inventory records a support level for the type family:

- `PARTIAL`: a bounded, directly activatable transformation exists for some occurrences, but the
  canonical recipe does not apply it and occurrence-level preconditions still decide eligibility;
- `MANUAL`: v0.1 preserves this family and intends no source-semantic transformation; and
- `AUTOMATIC`: reserved by the data-table schema for a future canonical transformation with an
  adequate atomicity boundary. v0.1 currently reports no Spring type family as automatic.

The inventory is a Java Spring-type report, not a complete migration checklist. It does not prove
the absence of Spring behavior in dependencies, plugins, property or YAML files, XML, tests,
service-loader metadata, packaging, deployment descriptors, or external infrastructure.

## What v0.1 does not migrate

These areas need bounded future recipes, explicit target policies, or application-specific design:

| Spring area | Likely target direction | Why it is not a direct annotation swap |
| --- | --- | --- |
| Spring MVC and HTTP responses | Jakarta REST resources, providers, and exception mappers | Binding sources, required/default values, conversion failures, negotiation, status, headers, generic entities, and direct callers differ. |
| Spring Security | Helidon Security, MicroProfile JWT, or Jakarta Security | Filter chains, providers, CSRF, sessions, URL rules, method rules, and identity propagation are policy. |
| Configuration | MicroProfile Config plus explicit compatibility code | Precedence, profiles, missing/empty behavior, converters, secrets, and file formats differ. |
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
v0.1 does not configure their providers, datasource, persistence unit, transaction manager, schema
lifecycle, tests, or runtime packaging. It also has no Spring-removal finalizer.
