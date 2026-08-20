package io.github.devanshops.rewrite.helidon.it.transaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Runs with any caller transaction suspended. */
@ApplicationScoped
public class NotSupportedOperation {
    @Inject
    TransactionProbe transactions;

    @Inject
    JdbcOutcomeStore outcomes;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TransactionSnapshot execute() throws Exception {
        outcomes.writeEffect("not-supported-inner");
        return transactions.current();
    }
}
