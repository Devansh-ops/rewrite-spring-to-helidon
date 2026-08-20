package io.github.devanshops.rewrite.helidon.it.transaction.supports;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Joins a transaction when present and creates none when absent. */
@ApplicationScoped
public class SupportsOperation {
    @Inject
    SupportsProbe transactions;

    @Inject
    SupportsJdbcOutcomeStore outcomes;

    @Transactional(propagation = Propagation.SUPPORTS)
    public SupportsSnapshot execute(String marker) throws Exception {
        outcomes.writeEffect(marker);
        return transactions.current();
    }
}
