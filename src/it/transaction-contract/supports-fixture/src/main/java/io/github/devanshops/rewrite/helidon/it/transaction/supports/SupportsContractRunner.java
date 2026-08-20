package io.github.devanshops.rewrite.helidon.it.transaction.supports;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Emits provider evidence while excluding synchronization scope from target equivalence. */
@ApplicationScoped
public class SupportsContractRunner {
    @Inject
    SupportsOperation operation;

    @Inject
    SupportsOuter outer;

    @Inject
    SupportsJdbcOutcomeStore outcomes;

    public void run(Path output) throws Exception {
        outcomes.reset();

        SupportsJoinResult joined = outer.execute();
        boolean sameContext = joined.before().active() && joined.inner().active() &&
                              joined.after().active() &&
                              Objects.equals(joined.before().contextId(),
                                      joined.inner().contextId()) &&
                              Objects.equals(joined.before().contextId(),
                                      joined.after().contextId());
        String joinOutcome = "ACTIVE," +
                             (sameContext ? "SAME_CONTEXT" : "DIFFERENT_CONTEXT") + "," +
                             (outcomes.hasEffect("supports-join")
                                     ? "COMMITTED" : "NOT_COMMITTED");
        outcomes.record(1, "SUPPORTS_JOIN", joinOutcome);

        SupportsSnapshot withoutTransaction =
                operation.execute("supports-no-transaction");
        String noTransactionOutcome =
                (withoutTransaction.active() ? "ACTIVE" : "INACTIVE") + "," +
                (outcomes.hasEffect("supports-no-transaction")
                        ? "COMMITTED" : "NOT_COMMITTED") + "," +
                withoutTransaction.synchronizationObservation();
        outcomes.record(2, "SUPPORTS_NO_TRANSACTION", noTransactionOutcome);

        List<String> normalized = outcomes.normalizedOutcomes();
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.write(output, normalized, StandardCharsets.UTF_8);
    }
}
