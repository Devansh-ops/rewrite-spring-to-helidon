package io.github.devanshops.rewrite.helidon.it.transaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits work in a transaction that is independent from its caller. */
@ApplicationScoped
public class RequiresNewOperation {
    @Inject
    TransactionProbe transactions;

    @Inject
    JdbcOutcomeStore outcomes;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransactionSnapshot execute() throws Exception {
        outcomes.writeEffect("requires-new-inner");
        return transactions.current();
    }
}
