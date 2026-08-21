package io.github.devanshops.rewrite.helidon;

import io.github.devanshops.rewrite.helidon.table.ModuleMigrationReadinessTable;

/** One deterministic module eligibility or refusal record. */
final class ModuleReadinessDecision {
    static final String PROFILE = "HELIDON_MP_CONSERVATIVE";

    private final String modulePath;
    private final String buildSystem;
    private final String outcome;
    private final String sourcePath;
    private final String sourceKind;
    private final String feature;
    private final String construct;
    private final String reasonCode;
    private final String reason;
    private final String suggestedDirection;
    private final String occurrenceKey;

    private ModuleReadinessDecision(String modulePath, String buildSystem, String outcome,
                                    String sourcePath, String sourceKind, String feature,
                                    String construct, String reasonCode, String reason,
                                    String suggestedDirection, String occurrenceKey) {
        this.modulePath = modulePath;
        this.buildSystem = buildSystem;
        this.outcome = outcome;
        this.sourcePath = sourcePath;
        this.sourceKind = sourceKind;
        this.feature = feature;
        this.construct = construct;
        this.reasonCode = reasonCode;
        this.reason = reason;
        this.suggestedDirection = suggestedDirection;
        this.occurrenceKey = occurrenceKey;
    }

    static ModuleReadinessDecision eligible(String modulePath, String buildSystem,
                                             String buildPath) {
        return new ModuleReadinessDecision(modulePath, buildSystem,
                "ELIGIBLE_FOR_PROFILE", buildPath, buildSystem,
                "Module eligibility", modulePath,
                "MODULE_ELIGIBLE_FOR_PROFILE",
                "No blocker was found in the supplied artifacts for this conservative profile; this is not runtime certification.",
                "Review the profile boundary before activating a future module-atomic migration",
                modulePath + "\u0000eligible");
    }

    static ModuleReadinessDecision refused(String modulePath, String buildSystem,
                                            ModuleReadinessEvidence evidence) {
        return new ModuleReadinessDecision(modulePath, buildSystem, "REFUSED",
                evidence.sourcePath, evidence.sourceKind, evidence.feature,
                evidence.construct, evidence.reasonCode, evidence.reason,
                evidence.suggestedDirection, evidence.occurrenceKey());
    }

    String rowKey() {
        return modulePath + '\u0000' + outcome + '\u0000' + sourcePath + '\u0000' +
               reasonCode + '\u0000' + construct + '\u0000' + occurrenceKey;
    }

    ModuleMigrationReadinessTable.Row toRow() {
        return new ModuleMigrationReadinessTable.Row(modulePath, buildSystem, PROFILE, outcome,
                sourcePath, sourceKind, feature, construct, reasonCode, reason,
                suggestedDirection);
    }
}
