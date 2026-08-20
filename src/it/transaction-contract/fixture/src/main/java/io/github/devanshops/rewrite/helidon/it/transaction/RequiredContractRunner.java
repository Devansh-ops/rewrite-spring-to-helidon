package io.github.devanshops.rewrite.helidon.it.transaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Executes the public transaction contract and emits stable, runtime-neutral outcomes. */
@ApplicationScoped
public class RequiredContractRunner {
    @Inject
    RequiredOperation requiredOperation;

    @Inject
    RequiredJoin requiredJoin;

    @Inject
    RequiresNewOuter requiresNewOuter;

    @Inject
    MandatoryOperation mandatoryOperation;

    @Inject
    MandatoryOuter mandatoryOuter;

    @Inject
    NeverOperation neverOperation;

    @Inject
    NeverOuter neverOuter;

    @Inject
    NotSupportedOuter notSupportedOuter;

    @Inject
    RollbackOperation rollbackOperation;

    @Inject
    TransactionProbe transactions;

    @Inject
    JdbcOutcomeStore outcomes;

    public void run(Path output) throws Exception {
        outcomes.reset();

        TransactionSnapshot created = requiredOperation.observe("required-create");
        String createOutcome = state(created) + "," +
                               persisted("required-create");
        outcomes.record(1, "REQUIRED_CREATE", createOutcome);

        RequiredJoinResult joined = requiredJoin.execute();
        boolean sameContext = Objects.equals(joined.before().contextId(),
                joined.inner().contextId());
        boolean resumed = Objects.equals(joined.before().contextId(), joined.after().contextId());
        String joinOutcome = state(joined.before()) + "," +
                             (sameContext ? "SAME_CONTEXT" : "DIFFERENT_CONTEXT") + "," +
                             (resumed ? "OUTER_RESUMED" : "OUTER_NOT_RESUMED") + "," +
                             persisted("required-join");
        outcomes.record(2, "REQUIRED_JOIN", joinOutcome);

        RequiresNewResult requiresNew = executeRequiresNew();
        boolean independentContext = requiresNew.outerBefore().active() &&
                                     requiresNew.inner().active() &&
                                     !Objects.equals(requiresNew.outerBefore().contextId(),
                                             requiresNew.inner().contextId());
        boolean outerResumed = requiresNew.outerAfter().active() &&
                               Objects.equals(requiresNew.outerBefore().contextId(),
                                       requiresNew.outerAfter().contextId());
        String requiresNewOutcome =
                (outcomes.hasEffect("requires-new-inner")
                        ? "INNER_COMMITTED" : "INNER_NOT_COMMITTED") + "," +
                (!outcomes.hasEffect("requires-new-outer")
                        ? "OUTER_ROLLED_BACK" : "OUTER_COMMITTED") + "," +
                (independentContext
                        ? "INDEPENDENT_CONTEXT" : "SHARED_OR_INACTIVE_CONTEXT") + "," +
                (outerResumed ? "OUTER_RESUMED" : "OUTER_NOT_RESUMED");
        outcomes.record(3, "REQUIRES_NEW", requiresNewOutcome);

        boolean mandatoryRejected = mandatoryWithoutContextRejected();
        RequiredJoinResult mandatory = mandatoryOuter.execute();
        boolean mandatorySameContext = mandatory.before().active() &&
                                       mandatory.inner().active() &&
                                       mandatory.after().active() &&
                                       Objects.equals(mandatory.before().contextId(),
                                               mandatory.inner().contextId()) &&
                                       Objects.equals(mandatory.before().contextId(),
                                               mandatory.after().contextId());
        String mandatoryOutcome = state(mandatory.inner()) + "," +
                                  (mandatorySameContext
                                          ? "SAME_CONTEXT" : "DIFFERENT_CONTEXT") + "," +
                                  persisted("mandatory-with-context") + "," +
                                  (mandatoryRejected
                                          ? "NO_CONTEXT:CONTEXT_REJECTED"
                                          : "NO_CONTEXT:NOT_REJECTED");
        outcomes.record(4, "MANDATORY", mandatoryOutcome);

        TransactionSnapshot neverWithoutContext =
                neverOperation.execute("never-without-context");
        NeverResult neverWithContext = neverOuter.execute();
        boolean neverRejected = neverWithContext.contextRejected() &&
                                !outcomes.hasEffect("never-with-context");
        boolean neverOuterResumed = neverWithContext.before().active() &&
                                    neverWithContext.after().active() &&
                                    Objects.equals(neverWithContext.before().contextId(),
                                            neverWithContext.after().contextId());
        String neverOutcome = state(neverWithoutContext) + "," +
                              persisted("never-without-context") + "," +
                              (neverRejected
                                      ? "WITH_CONTEXT:CONTEXT_REJECTED"
                                      : "WITH_CONTEXT:NOT_REJECTED") + "," +
                              (neverOuterResumed
                                      ? "OUTER_RESUMED" : "OUTER_NOT_RESUMED") + "," +
                              (outcomes.hasEffect("never-outer")
                                      ? "OUTER_COMMITTED" : "OUTER_NOT_COMMITTED");
        outcomes.record(5, "NEVER", neverOutcome);

        RequiredJoinResult notSupported = notSupportedOuter.execute();
        boolean notSupportedResumed = notSupported.before().active() &&
                                      !notSupported.inner().active() &&
                                      notSupported.after().active() &&
                                      Objects.equals(notSupported.before().contextId(),
                                              notSupported.after().contextId());
        String notSupportedOutcome =
                (notSupported.inner().active()
                        ? "INNER_ACTIVE" : "INNER_INACTIVE") + "," +
                (outcomes.hasEffect("not-supported-inner")
                        ? "INNER_COMMITTED" : "INNER_NOT_COMMITTED") + "," +
                (notSupportedResumed
                        ? "OUTER_RESUMED" : "OUTER_NOT_RESUMED") + "," +
                (outcomes.hasEffect("not-supported-outer")
                        ? "OUTER_COMMITTED" : "OUTER_NOT_COMMITTED");
        outcomes.record(6, "NOT_SUPPORTED", notSupportedOutcome);

        outcomes.record(7, "CHECKED_EXCEPTION",
                thrown(checkedExceptionThrown()) + "," + committed("checked-exception"));
        outcomes.record(8, "RUNTIME_EXCEPTION",
                thrown(runtimeExceptionThrown()) + "," + rolledBack("runtime-exception"));
        outcomes.record(9, "ERROR",
                thrown(errorThrown()) + "," + rolledBack("error"));
        outcomes.record(10, "ROLLBACK_RULE_NEGATIVE_SUBTYPE",
                thrown(negativeSubtypeThrown()) + "," +
                committed("rollback-rule-negative-subtype"));
        outcomes.record(11, "ROLLBACK_RULE_POSITIVE_SIBLING",
                thrown(positiveSiblingThrown()) + "," +
                rolledBack("rollback-rule-positive-sibling"));

        List<String> normalized = outcomes.normalizedOutcomes();
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.write(output, normalized, StandardCharsets.UTF_8);
    }

    private static String state(TransactionSnapshot snapshot) {
        return snapshot.active() ? "ACTIVE" : "INACTIVE";
    }

    private String persisted(String marker) throws SQLException {
        return outcomes.hasEffect(marker) ? "COMMITTED" : "NOT_COMMITTED";
    }

    private RequiresNewResult executeRequiresNew() throws Exception {
        try {
            requiresNewOuter.execute();
        } catch (RequiresNewRollback expected) {
            return expected.result();
        }
        throw new IllegalStateException("The outer REQUIRES_NEW contract transaction committed");
    }

    private boolean mandatoryWithoutContextRejected() throws Exception {
        try {
            mandatoryOperation.execute("mandatory-without-context");
            return false;
        } catch (RuntimeException failure) {
            boolean rejected = transactions.isContextRejected(
                    failure, ContextRequirement.MANDATORY);
            return rejected && !outcomes.hasEffect("mandatory-without-context");
        }
    }

    private boolean checkedExceptionThrown() throws SQLException {
        try {
            rollbackOperation.checkedFailure();
            return false;
        } catch (ContractCheckedException expected) {
            return true;
        }
    }

    private boolean runtimeExceptionThrown() throws SQLException {
        try {
            rollbackOperation.runtimeFailure();
            return false;
        } catch (ContractRuntimeException expected) {
            return true;
        }
    }

    private boolean errorThrown() throws SQLException {
        try {
            rollbackOperation.errorFailure();
            return false;
        } catch (ContractError expected) {
            return true;
        }
    }

    private boolean negativeSubtypeThrown() throws SQLException {
        try {
            rollbackOperation.configuredFailure(
                    "rollback-rule-negative-subtype", new RuleNegativeChildException());
            return false;
        } catch (RuleParentException expected) {
            return expected instanceof RuleNegativeChildException;
        }
    }

    private boolean positiveSiblingThrown() throws SQLException {
        try {
            rollbackOperation.configuredFailure(
                    "rollback-rule-positive-sibling", new RulePositiveSiblingException());
            return false;
        } catch (RuleParentException expected) {
            return expected instanceof RulePositiveSiblingException;
        }
    }

    private static String thrown(boolean thrown) {
        return thrown ? "THROWN" : "NOT_THROWN";
    }

    private String committed(String marker) throws SQLException {
        return outcomes.hasEffect(marker) ? "COMMITTED" : "NOT_COMMITTED";
    }

    private String rolledBack(String marker) throws SQLException {
        return outcomes.hasEffect(marker) ? "COMMITTED" : "ROLLED_BACK";
    }
}
