package io.github.devanshops.rewrite.helidon.it.transaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Transactional;

/** Suspends and resumes an outer transaction around a REQUIRES_NEW call. */
@ApplicationScoped
public class RequiresNewOuter {
    @Inject
    RequiresNewOperation operation;

    @Inject
    TransactionProbe transactions;

    @Inject
    JdbcOutcomeStore outcomes;

    @Transactional
    public void execute() throws Exception {
        TransactionSnapshot before = transactions.current();
        TransactionSnapshot inner = operation.execute();
        TransactionSnapshot after = transactions.current();
        outcomes.writeEffect("requires-new-outer");
        throw new RequiresNewRollback(new RequiresNewResult(before, inner, after));
    }
}
