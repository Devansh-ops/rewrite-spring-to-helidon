package io.github.devanshops.rewrite.helidon.it.transaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Transactional;

/** Verifies NEVER rejects an active context without disrupting the caller. */
@ApplicationScoped
public class NeverOuter {
    @Inject
    NeverOperation operation;

    @Inject
    TransactionProbe transactions;

    @Inject
    JdbcOutcomeStore outcomes;

    @Transactional
    public NeverResult execute() throws Exception {
        TransactionSnapshot before = transactions.current();
        boolean rejected;
        try {
            operation.execute("never-with-context");
            rejected = false;
        } catch (RuntimeException failure) {
            rejected = transactions.isContextRejected(failure, ContextRequirement.NEVER);
            if (!rejected) {
                throw failure;
            }
        }
        TransactionSnapshot after = transactions.current();
        outcomes.writeEffect("never-outer");
        return new NeverResult(before, after, rejected);
    }
}
