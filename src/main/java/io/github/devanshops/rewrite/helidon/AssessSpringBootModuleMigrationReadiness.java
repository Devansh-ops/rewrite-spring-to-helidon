package io.github.devanshops.rewrite.helidon;

import io.github.devanshops.rewrite.helidon.table.ModuleMigrationReadinessTable;
import org.openrewrite.ExecutionContext;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Read-only, module-atomic eligibility assessment for the conservative Helidon MP profile. */
public final class AssessSpringBootModuleMigrationReadiness
        extends ModuleAtomicMigrationRecipe {
    private static final String REPORTED_ROWS =
            AssessSpringBootModuleMigrationReadiness.class.getName() + ".reportedRows";

    private transient ModuleMigrationReadinessTable readiness =
            new ModuleMigrationReadinessTable(this);

    @Override
    public String getDisplayName() {
        return "Assess Spring Boot module migration eligibility";
    }

    @Override
    public String getDescription() {
        return "Builds a complete supplied-module index and reports deterministic eligibility " +
               "or refusal for the conservative Helidon MP migration profile without changing semantics.";
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(5);
    }

    @Override
    protected void reportFrozenPlan(ModuleAtomicMigrationCoordinator coordinator,
                                    ExecutionContext ctx) {
        Set<String> reported = ctx.computeMessageIfAbsent(REPORTED_ROWS,
                key -> ConcurrentHashMap.newKeySet());
        for (ModuleReadinessDecision decision : coordinator.decisions()) {
            if (reported.add(decision.rowKey())) {
                if (readiness == null) {
                    readiness = new ModuleMigrationReadinessTable(this);
                }
                readiness.insertRow(ctx, decision.toRow());
            }
        }
    }
}
