package io.github.devanshops.rewrite.helidon.it.transaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Transactional;

/** Observes suspension and resumption around a NOT_SUPPORTED operation. */
@ApplicationScoped
public class NotSupportedOuter {
    @Inject
    NotSupportedOperation operation;

    @Inject
    TransactionProbe transactions;

    @Inject
    JdbcOutcomeStore outcomes;

    @Transactional
    public RequiredJoinResult execute() throws Exception {
        TransactionSnapshot before = transactions.current();
        TransactionSnapshot inner = operation.execute();
        TransactionSnapshot after = transactions.current();
        outcomes.writeEffect("not-supported-outer");
        return new RequiredJoinResult(before, inner, after);
    }
}
