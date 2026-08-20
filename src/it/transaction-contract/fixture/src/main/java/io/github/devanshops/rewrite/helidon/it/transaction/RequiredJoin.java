package io.github.devanshops.rewrite.helidon.it.transaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Transactional;

/** Establishes an outer REQUIRED boundary and calls another proxied REQUIRED bean. */
@ApplicationScoped
public class RequiredJoin {
    @Inject
    RequiredOperation operation;

    @Inject
    TransactionProbe transactions;

    @Transactional
    public RequiredJoinResult execute() throws Exception {
        TransactionSnapshot before = transactions.current();
        TransactionSnapshot inner = operation.observe("required-join");
        TransactionSnapshot after = transactions.current();
        return new RequiredJoinResult(before, inner, after);
    }
}
