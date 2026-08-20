package io.github.devanshops.rewrite.helidon.it.transaction.spring;

import io.github.devanshops.rewrite.helidon.it.transaction.JdbcOutcomeStore;
import io.github.devanshops.rewrite.helidon.it.transaction.MandatoryOperation;
import io.github.devanshops.rewrite.helidon.it.transaction.MandatoryOuter;
import io.github.devanshops.rewrite.helidon.it.transaction.NeverOperation;
import io.github.devanshops.rewrite.helidon.it.transaction.NeverOuter;
import io.github.devanshops.rewrite.helidon.it.transaction.NotSupportedOperation;
import io.github.devanshops.rewrite.helidon.it.transaction.NotSupportedOuter;
import io.github.devanshops.rewrite.helidon.it.transaction.RequiredContractRunner;
import io.github.devanshops.rewrite.helidon.it.transaction.RequiredJoin;
import io.github.devanshops.rewrite.helidon.it.transaction.RequiredOperation;
import io.github.devanshops.rewrite.helidon.it.transaction.RollbackOperation;
import io.github.devanshops.rewrite.helidon.it.transaction.RequiresNewOperation;
import io.github.devanshops.rewrite.helidon.it.transaction.RequiresNewOuter;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

@SpringBootConfiguration
@EnableAutoConfiguration
@EnableTransactionManagement
@Import({RequiredOperation.class, RequiredJoin.class, RequiresNewOperation.class,
         RequiresNewOuter.class, MandatoryOperation.class, MandatoryOuter.class,
         NeverOperation.class, NeverOuter.class, NotSupportedOperation.class,
         NotSupportedOuter.class, RollbackOperation.class, JdbcOutcomeStore.class,
         RequiredContractRunner.class, SpringTransactionProbe.class})
public class SpringContractApplication {
    @Bean(name = "transactionalResource")
    DataSource transactionalResource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:spring-transaction-contract;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    @Bean(name = "contract")
    @Primary
    DataSource contractDataSource(
            @Qualifier("transactionalResource") DataSource transactionalResource) {
        return new TransactionAwareDataSourceProxy(transactionalResource);
    }

    @Bean
    PlatformTransactionManager transactionManager(
            @Qualifier("transactionalResource") DataSource transactionalResource) {
        return new JdbcTransactionManager(transactionalResource);
    }
}
