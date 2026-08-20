package io.github.devanshops.rewrite.helidon.it.transaction.supports.helidon;

import io.github.devanshops.rewrite.helidon.it.transaction.supports.SupportsProbe;
import io.github.devanshops.rewrite.helidon.it.transaction.supports.SupportsSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionSynchronizationRegistry;

@ApplicationScoped
public class SupportsHelidonProbe implements SupportsProbe {
    @Inject
    TransactionSynchronizationRegistry transactions;

    @Override
    public SupportsSnapshot current() {
        int status = transactions.getTransactionStatus();
        boolean active = status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK;
        return new SupportsSnapshot(active, transactions.getTransactionKey(),
                "SPRING_SYNCHRONIZATION_NOT_PROMISED");
    }
}
