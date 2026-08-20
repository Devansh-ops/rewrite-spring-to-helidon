package io.github.devanshops.rewrite.helidon.it.transaction.supports;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Transactional;

/** Establishes the transaction a SUPPORTS operation must join. */
@ApplicationScoped
public class SupportsOuter {
    @Inject
    SupportsOperation operation;

    @Inject
    SupportsProbe transactions;

    @Transactional
    public SupportsJoinResult execute() throws Exception {
        SupportsSnapshot before = transactions.current();
        SupportsSnapshot inner = operation.execute("supports-join");
        SupportsSnapshot after = transactions.current();
        return new SupportsJoinResult(before, inner, after);
    }
}
