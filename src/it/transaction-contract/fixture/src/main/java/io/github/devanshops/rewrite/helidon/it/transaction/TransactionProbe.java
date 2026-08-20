package io.github.devanshops.rewrite.helidon.it.transaction;

/** Adapter implemented by each source and target transaction runtime. */
public interface TransactionProbe {
    TransactionSnapshot current();

    boolean isContextRejected(Throwable failure, ContextRequirement requirement);
}
