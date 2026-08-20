# Transaction runtime contract fixture

This nested reactor is test infrastructure only. Its artifacts set both Maven install and deploy to
skip and are not part of the recipe's published dependency graph.

The fixture executes one CDI-ready source tree twice:

1. unchanged on Spring Boot 4.1.0 with Spring's proxy-based transaction management; and
2. after `MigrateSpringTransactionalToJakarta` rewrites a generated copy, on Helidon MP 4.5.3.

A second, isolated source tree exercises the explicit
`MigrateSpringTransactionalToJakartaIncludingSupports` opt-in. Keeping SUPPORTS separate proves the
base recipe still rewrites its entire supported subject with zero Spring residue while the base
recipe continues to refuse SUPPORTS by default.

Both executions use an isolated in-memory H2 database. Spring uses `JdbcTransactionManager` with a
`TransactionAwareDataSourceProxy`, while Helidon uses its Narayana-backed Jakarta Transactions CDI
integration and `JtaAdaptingDataSource` with immediate enlistment. Contract outcomes are derived
from actual rows committed to or rolled back from H2, not from mocks or synchronization callbacks.
Opaque transaction identifiers are compared only within one runtime execution and are never
compared between providers.

The contract intentionally relies on these pinned provider behaviors:

- transaction interception occurs on public, non-final cross-bean calls;
- `JtaAdaptingDataSource` permits non-transactional access and immediately enlists connections when
  a JTA transaction exists;
- Spring rejects both illegal context cases with `IllegalTransactionStateException`, while the
  Helidon/Narayana interceptor exposes `TransactionRequiredException` for MANDATORY and
  `InvalidTransactionException` for NEVER somewhere in the cause chain;
- Spring's default checked/unchecked/Error rollback behavior is matched by Jakarta's default plus
  the recipe's explicit `rollbackOn = Error.class`; and
- a negative rollback rule that is a strict subtype of a positive rule wins for that subtype, while
  a sibling matched only by the positive rule still rolls back.

The Helidon execution uses a separate Java 21 process with the module runtime classpath. Running
Helidon inside Maven's plugin classloader prevents Weld/Jandex from loading application beans and
does not represent the application launch model being tested.

## SUPPORTS boundary

The opt-in contract proves that SUPPORTS joins an existing transaction and uses no actual
transaction when called without one. Both paths perform H2 writes and verify the persisted rows
after the interceptor boundary returns.

Spring's `JdbcTransactionManager` also creates a non-transactional synchronization scope for the
no-transaction call in this pinned configuration. Jakarta Transactions does not promise an
equivalent Spring synchronization scope, so that observation is deliberately excluded from target
equivalence. The two literal expectations encode the boundary explicitly:

- Spring reports `SPRING_SYNCHRONIZATION_ACTIVE`.
- Helidon reports `SPRING_SYNCHRONIZATION_NOT_PROMISED`.

The script compares each provider to its own literal evidence and separately compares the common
transaction/commit projection. Any difference outside that final synchronization token fails the
contract.

The script also rejects a generated Helidon source tree or dependency tree that still contains
Spring. It requires deterministic local execution on Java 21 and uses no containers, external
services, credentials, or network calls after Maven dependencies are available.

These provider assumptions are part of the migration boundary. If either pinned runtime disagrees
on a claimed mapping, the corresponding migration must be removed from the supported set until a
narrower precondition or an explicit target policy restores equivalent behavior.
