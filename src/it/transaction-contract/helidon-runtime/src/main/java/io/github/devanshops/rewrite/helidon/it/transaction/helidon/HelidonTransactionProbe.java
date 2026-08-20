package io.github.devanshops.rewrite.helidon.it.transaction.helidon;

import io.github.devanshops.rewrite.helidon.it.transaction.ContextRequirement;
import io.github.devanshops.rewrite.helidon.it.transaction.TransactionProbe;
import io.github.devanshops.rewrite.helidon.it.transaction.TransactionSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.InvalidTransactionException;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionRequiredException;
import jakarta.transaction.TransactionSynchronizationRegistry;

@ApplicationScoped
public class HelidonTransactionProbe implements TransactionProbe {
    @Inject
    TransactionSynchronizationRegistry transactions;

    @Override
    public TransactionSnapshot current() {
        int status = transactions.getTransactionStatus();
        boolean active = status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK;
        Object key = transactions.getTransactionKey();
        return new TransactionSnapshot(active, key);
    }

    @Override
    public boolean isContextRejected(Throwable failure, ContextRequirement requirement) {
        Class<? extends Throwable> expected = requirement == ContextRequirement.MANDATORY
                ? TransactionRequiredException.class : InvalidTransactionException.class;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (expected.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
