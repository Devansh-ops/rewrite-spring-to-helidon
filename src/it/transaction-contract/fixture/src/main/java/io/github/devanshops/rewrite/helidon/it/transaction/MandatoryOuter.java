package io.github.devanshops.rewrite.helidon.it.transaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Transactional;

/** Establishes the context required by a MANDATORY operation. */
@ApplicationScoped
public class MandatoryOuter {
    @Inject
    MandatoryOperation operation;

    @Inject
    TransactionProbe transactions;

    @Transactional
    public RequiredJoinResult execute() throws Exception {
        TransactionSnapshot before = transactions.current();
        TransactionSnapshot inner = operation.execute("mandatory-with-context");
        TransactionSnapshot after = transactions.current();
        return new RequiredJoinResult(before, inner, after);
    }
}
