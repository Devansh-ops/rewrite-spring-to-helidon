package io.github.devanshops.rewrite.helidon.it.transaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;

/** Produces persisted evidence for default and configured rollback behavior. */
@ApplicationScoped
public class RollbackOperation {
    @Inject
    JdbcOutcomeStore outcomes;

    @Transactional
    public void checkedFailure() throws SQLException, ContractCheckedException {
        outcomes.writeEffect("checked-exception");
        throw new ContractCheckedException();
    }

    @Transactional
    public void runtimeFailure() throws SQLException {
        outcomes.writeEffect("runtime-exception");
        throw new ContractRuntimeException();
    }

    @Transactional
    public void errorFailure() throws SQLException {
        outcomes.writeEffect("error");
        throw new ContractError();
    }

    @Transactional(rollbackFor = RuleParentException.class,
                   noRollbackFor = RuleNegativeChildException.class)
    public void configuredFailure(String marker, RuleParentException failure)
            throws SQLException, RuleParentException {
        outcomes.writeEffect(marker);
        throw failure;
    }
}
