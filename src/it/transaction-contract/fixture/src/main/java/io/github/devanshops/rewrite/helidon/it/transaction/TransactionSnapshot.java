package io.github.devanshops.rewrite.helidon.it.transaction;

/** Runtime-neutral view of the transaction currently governing an invocation. */
public record TransactionSnapshot(boolean active, Object contextId) {
}
