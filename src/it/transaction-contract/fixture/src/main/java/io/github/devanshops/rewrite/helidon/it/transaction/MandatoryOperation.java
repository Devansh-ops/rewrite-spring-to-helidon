package io.github.devanshops.rewrite.helidon.it.transaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Requires an existing transaction and writes through that transaction. */
@ApplicationScoped
public class MandatoryOperation {
    @Inject
    TransactionProbe transactions;

    @Inject
    JdbcOutcomeStore outcomes;

    @Transactional(propagation = Propagation.MANDATORY)
    public TransactionSnapshot execute(String marker) throws Exception {
        outcomes.writeEffect(marker);
        return transactions.current();
    }
}
