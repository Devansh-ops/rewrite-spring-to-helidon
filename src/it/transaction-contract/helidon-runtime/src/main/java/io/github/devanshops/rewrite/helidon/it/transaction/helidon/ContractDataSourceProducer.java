package io.github.devanshops.rewrite.helidon.it.transaction.helidon;

import io.helidon.integrations.jta.jdbc.JtaAdaptingDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;

@ApplicationScoped
public class ContractDataSourceProducer {
    @Produces
    @ApplicationScoped
    @Named("contract")
    DataSource contractDataSource(TransactionManager transactionManager,
                                  TransactionSynchronizationRegistry registry) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:helidon-transaction-contract;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return new JtaAdaptingDataSource(transactionManager::getTransaction, registry,
                true, null, dataSource, true);
    }
}
