package io.github.devanshops.rewrite.helidon.it.transaction.spring;

import io.github.devanshops.rewrite.helidon.it.transaction.ContextRequirement;
import io.github.devanshops.rewrite.helidon.it.transaction.TransactionProbe;
import io.github.devanshops.rewrite.helidon.it.transaction.TransactionSnapshot;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;

public class SpringTransactionProbe implements TransactionProbe {
    @Inject
    @Named("transactionalResource")
    DataSource dataSource;

    @Override
    public TransactionSnapshot current() {
        boolean active = TransactionSynchronizationManager.isActualTransactionActive();
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        return new TransactionSnapshot(active, resource);
    }

    @Override
    public boolean isContextRejected(Throwable failure, ContextRequirement requirement) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalTransactionStateException) {
                return true;
            }
        }
        return false;
    }
}
