package io.github.devanshops.rewrite.helidon.it.transaction.supports.spring;

import io.github.devanshops.rewrite.helidon.it.transaction.supports.SupportsContractRunner;
import io.github.devanshops.rewrite.helidon.it.transaction.supports.SupportsJdbcOutcomeStore;
import io.github.devanshops.rewrite.helidon.it.transaction.supports.SupportsOperation;
import io.github.devanshops.rewrite.helidon.it.transaction.supports.SupportsOuter;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

@SpringBootConfiguration
@EnableAutoConfiguration
@EnableTransactionManagement
@Import({SupportsOperation.class, SupportsOuter.class, SupportsJdbcOutcomeStore.class,
         SupportsContractRunner.class, SupportsSpringProbe.class})
public class SupportsSpringApplication {
    @Bean(name = "transactionalResource")
    DataSource transactionalResource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:spring-supports-contract;DB_CLOSE_DELAY=-1");
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
