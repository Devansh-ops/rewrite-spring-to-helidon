package io.github.devanshops.rewrite.helidon.it.transaction.supports;

/** Runtime-neutral transaction and synchronization observation. */
public record SupportsSnapshot(boolean active,
                               Object contextId,
                               String synchronizationObservation) {
}
