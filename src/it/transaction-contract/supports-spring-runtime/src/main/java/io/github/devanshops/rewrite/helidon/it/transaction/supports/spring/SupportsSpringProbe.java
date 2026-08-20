package io.github.devanshops.rewrite.helidon.it.transaction.supports.spring;

import io.github.devanshops.rewrite.helidon.it.transaction.supports.SupportsProbe;
import io.github.devanshops.rewrite.helidon.it.transaction.supports.SupportsSnapshot;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;

public class SupportsSpringProbe implements SupportsProbe {
    @Inject
    @Named("transactionalResource")
    DataSource dataSource;

    @Override
    public SupportsSnapshot current() {
        boolean active = TransactionSynchronizationManager.isActualTransactionActive();
        Object contextId = TransactionSynchronizationManager.getResource(dataSource);
        String synchronization = TransactionSynchronizationManager.isSynchronizationActive()
                ? "SPRING_SYNCHRONIZATION_ACTIVE"
                : "SPRING_SYNCHRONIZATION_INACTIVE";
        return new SupportsSnapshot(active, contextId, synchronization);
    }
}
