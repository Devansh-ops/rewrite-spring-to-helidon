# v0.1 automation boundary

This project treats a Spring Boot to Helidon MP migration as two related products:

1. an assessment that locates and classifies the Spring programming model; and
2. a conservative set of source and build transformations for patterns with a defensible
   Jakarta, CDI, or MicroProfile equivalent.

The boundary is part of the contract. A recipe that preserves unsupported code and marks it for
review is preferable to a transformation that compiles while silently changing runtime behavior.

## What the canonical recipe changes

| Area | Automated subset | Preserved and marked for review |
| --- | --- | --- |
| Maven build | Imports `io.helidon:helidon-dependencies:4.5.3` into root dependency management and direct Spring Boot POMs, then adds managed `helidon-microprofile-core` to modules using `SpringApplication`. | Parent replacement, Spring removal, Java version, packaging plugins, full MP bundle, feature-specific runtime dependencies, and non-Maven builds. |
| Application entry point | Atomically replaces a structurally plain, option-free launcher containing exactly one standalone `SpringApplication.run` as the only statement in its only method, `public static void main`; the primary source must be the enclosing `@SpringBootApplication` class and the second argument a `String[]`. It delegates to `io.helidon.Main.main(args)` and removes `@SpringBootApplication` only after the same module has no other production Spring runtime usage. | Remaining production Spring runtime types, multiple startup calls, a different or composite primary source, a used return value, literal varargs, setup or cleanup statements, extra fields/methods/nested types, inheritance/interfaces, direct or composed additional Spring annotations, Spring Security source/build usage, unsupported overloads, `SpringApplicationBuilder`, servlet initializer behavior, and `@SpringBootApplication` exclusions/options preserve the whole launcher. |
| CDI scopes and injection | `@Service` and `@Component` with class-atomically safe CDI bean shapes become `@ApplicationScoped` and receive a stable `@Named`; explicit `@Configuration(proxyBeanMethods = false)` is supported. Eligible, explicitly resolvable field and constructor `@Autowired` sites become `@Inject`; normal-scoped beans must retain an implicit or non-private no-argument constructor. Safe zero-argument producers with the literal `@Bean(destroyMethod = "")` opt-out gain `@Produces`, `@Singleton`, and `@Named`. | Any unsupported injection point preserves the whole class. `@Repository` remains manual because Spring persistence-exception translation is not a CDI scope. Default/proxied configuration, unqualified name-fallback resolution, producer parameters, missing or non-literal destroy-method opt-outs, multiple or ambiguous constructors, final/sealed/static/optional/aggregate injection, unproxyable bean classes, inherited or Spring-interface contracts, lifecycle attributes, aliases, Spring web behavior, scopes/profiles/conditions/imports/scans/property sources/laziness/dependency ordering, lifecycle callbacks, and unsafe producer return contracts are refused. |
| Named beans | One literal stereotype/bean name and matching literal `@Qualifier` become CDI `@Named`; safely converted unnamed stereotypes and lifecycle-safe zero-argument producers receive their Spring default bean name. | Multiple aliases, computed names, composed qualifiers, aggregate injection, producer parameters, inferred Spring `close`/`shutdown` destruction, and cases where Spring name-based fallback has broader semantics. |
| External configuration | In a proven CDI bean, literal `${name}` and `${name:non-empty-default}` `@Value` expressions targeting strings, booleans, or numeric primitives/wrappers become MicroProfile Config `@ConfigProperty`; fields and unique eligible constructors gain `@Inject`, while actual CDI producer parameters remain producer injection points. | Unconverted Spring beans, final/static fields, ambiguous constructors, Spring `${name:}` empty defaults, ordinary method parameters, SpEL, nested placeholders, non-literals, collections, durations and other non-scalar or non-equivalent conversions, `@ConfigurationProperties`, validation, property sources, and Spring configuration files. |
| Transactions | In a proven, proxyable CDI bean, default `@Transactional`; `rollbackFor` and `noRollbackFor` map to `rollbackOn` and `dontRollbackOn` on interceptable targets. | Spring-only or unproxyable beans, non-interceptable methods, propagation, isolation, timeout, read-only, labels, manager selection, reactive transactions, and provider configuration. |
| REST controllers | When no Spring Security or unsupported Spring Web/Servlet infrastructure is present in the same module, a controller-atomic conversion changes a public, top-level, proxyable `@RestController` to `@ApplicationScoped` plus `@Path`; supported public GET/POST/PUT/DELETE/PATCH methods become Jakarta REST resource methods. | Global advice/interceptors/filters/converters/exception resolvers, direct or composed Spring Security, residual Spring Web/Servlet types, unsupported or Spring-specific path syntax, any unsupported mapping/binding, residual Spring behavior annotation, residual `ResponseEntity`, non-public/final/sealed/static methods, unsafe constructors, inheritance or Spring-interface contracts, MVC view, void, async, or streaming contracts preserve the whole controller. |
| REST parameters | Safe scalar query, path, and header bindings become `@QueryParam`, `@PathParam`, and `@HeaderParam`; safe request bodies drop the Spring annotation. Every mapped parameter must have a recognized portable binding. | Spring `defaultValue`, unannotated Spring-resolved parameters, required/null differences, optional primitive parameters, collections and non-scalar targets, whole-header maps, ambiguous names, multipart, model binding, binding results, validation order, and advice/exception semantics. |
| HTTP responses | In a proven Spring MVC or Jakarta REST resource, and only when the same module-level security/Web and controller preflights permit migration, `ResponseEntity<T>` becomes Jakarta REST `Response` for common `ok`, `status`, `badRequest`, `notFound`, `noContent`, `accepted`, and `created` factories/builders with integer expressions or attributed Spring `HttpStatus` constants. Conversion is compilation-unit atomic and is deferred when the enclosing controller cannot migrate atomically. | Spring Security or unsupported Spring Web/Servlet infrastructure in the module, service/client uses, dynamic/custom `HttpStatusCode`, member references, subclasses, arbitrary headers, cookies, content types, cache controls, nested builder chains, and unsupported factories. |
| Bootstrap resources | Adds missing CDI 4 `beans.xml` and a comment-only `microprofile-config.properties` to each detected executable root or nested module. | Existing files and all application-specific configuration values. |
| Residue | Marks Spring imports and typed uses and exports a classified data table. | No dependency deletion or build failure based on residue in v0.1. |

## What v0.1 does not migrate

These feature families require separate recipes or application-specific design decisions:

| Spring area | Likely target direction | Why it is not mechanical |
| --- | --- | --- |
| Spring Security | Helidon Security, MicroProfile JWT, or Jakarta Security | Filter chains, authentication providers, method rules, CSRF, sessions, and identity propagation are policy. |
| Spring Data | Jakarta Persistence DAO/repository code or an evaluated Helidon Data design | Derived queries, repository fragments, paging, auditing, and transaction boundaries carry behavior. |
| WebFlux/Reactor | Jakarta REST async APIs or a deliberate Helidon SE design | Backpressure, threading, context propagation, and streaming change architecture. |
| Spring Cloud | Component-specific MicroProfile or Helidon facilities | Configuration, discovery, gateways, circuit breakers, and tracing need independent choices. |
| Batch/Integration/messaging | Jakarta Batch or selected clients/connectors | Delivery, retry, ordering, checkpoint, and transaction semantics are workload-specific. |
| Actuator/metrics | MicroProfile Health/Metrics or selected observability stack | Endpoint exposure, probe meaning, tags, registry, and operational contracts must be retained deliberately. |
| Scheduling/caching | Helidon-compatible scheduler/concurrency and selected cache | Execution guarantees, keys, eviction, locking, and cluster behavior are not annotation synonyms. |
| AOP/events/application context | CDI interceptors, decorators, events, `Instance`, or `BeanManager` | Proxy boundaries, ordering, lifecycle, and dynamic lookup semantics differ. |
| HTTP clients | MicroProfile REST Client, Jakarta REST Client, or Helidon WebClient | Error mapping, interceptors, timeouts, retries, pooling, and observability must be redesigned. |
| Tests | Helidon MP testing plus JUnit 5 | Spring test context, slices, mocks, profiles, and web-test clients are framework-specific. |

Portable Jakarta Validation and Persistence annotations may already survive a Boot 4 baseline,
but v0.1 does not configure a validation provider, persistence unit, datasource, JPA provider,
transaction manager, schema lifecycle, or their tests.

## Build coexistence is intentional

The canonical recipe creates a transition state in which Spring and Helidon dependencies can
coexist. This is useful for an incremental multi-module migration and makes build edits
recoverable, but it is not a final deployable architecture.

Specifically, `PrepareMavenBuildForHelidonMp`:

- keeps the existing parent, including a Spring Boot parent;
- keeps every existing Spring dependency;
- imports the Helidon Dependencies POM at the Maven root and in directly identifiable Spring Boot POMs, including reactors where the aggregator is not the modules' parent;
- adds the minimal Helidon MP core bundle to modules using `SpringApplication`; and
- does not add feature-specific persistence, health, security, metrics, or packaging choices.

After a module compiles and its behavior is validated on Helidon, a later finalization phase can
remove proven-unused Spring artifacts and select packaging. That finalizer is intentionally absent
from v0.1.

## Search markers and support levels

`AnalyzeSpringBootToHelidonMp` and the last phase of the canonical migration run
`FindSpringUsage`. It records one row per Spring type per source file with:

- source path;
- feature family;
- fully qualified Spring type;
- support level; and
- suggested replacement.

The support level describes the recipe family's current coverage:

- `AUTOMATIC`: an implementation exists for the common form of this type;
- `PARTIAL`: only a deliberately bounded subset is automated; and
- `MANUAL`: no v0.1 source transformation is intended.

`AUTOMATIC` is not an assertion that every annotation argument or surrounding usage is safe.
The transformation recipes perform a finer semantic check and leave a targeted marker when a
specific occurrence is unsupported. Always review both the CSV and patch.

## Expected post-run state

A successful recipe run should yield:

- a minimal Helidon dependency and resource foundation in applicable Maven modules;
- migrated source only for supported occurrences;
- original source retained for unsupported occurrences, with review markers;
- an inventory of Spring residue; and
- no implicit claim that the module is ready to deploy.

Completion still requires compiling on Java 21+, resolving every marker and residue row,
selecting target runtime integrations, migrating configuration and tests, and validating API and
operational behavior.
