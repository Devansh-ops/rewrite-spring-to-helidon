package io.github.devanshops.rewrite.helidon.it.transaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Runs only when no transaction context exists. */
@ApplicationScoped
public class NeverOperation {
    @Inject
    TransactionProbe transactions;

    @Inject
    JdbcOutcomeStore outcomes;

    @Transactional(propagation = Propagation.NEVER)
    public TransactionSnapshot execute(String marker) throws Exception {
        outcomes.writeEffect(marker);
        return transactions.current();
    }
}
