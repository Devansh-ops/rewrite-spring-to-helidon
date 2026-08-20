package io.github.devanshops.rewrite.helidon.it.transaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Transactional;

/** The same CDI-ready source is executed by Spring and, after rewriting, Helidon. */
@ApplicationScoped
public class RequiredOperation {
    @Inject
    TransactionProbe transactions;

    @Inject
    JdbcOutcomeStore outcomes;

    @Transactional
    public TransactionSnapshot observe(String marker) throws Exception {
        outcomes.writeEffect(marker);
        return transactions.current();
    }
}
