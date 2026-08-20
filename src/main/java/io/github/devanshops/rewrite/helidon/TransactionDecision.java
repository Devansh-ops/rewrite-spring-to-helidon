package io.github.devanshops.rewrite.helidon;

import io.github.devanshops.rewrite.helidon.table.MigrationAssessmentTable;

/** A planned, hierarchy-atomic transaction annotation outcome. */
final class TransactionDecision {
    private final String sourcePath;
    private final String sourceKind;
    private final String outcome;
    private final String reasonCode;
    private final String reason;
    private final String targetAnnotation;

    private TransactionDecision(String sourcePath, String sourceKind, String outcome,
                                String reasonCode, String reason, String targetAnnotation) {
        this.sourcePath = sourcePath;
        this.sourceKind = sourceKind;
        this.outcome = outcome;
        this.reasonCode = reasonCode;
        this.reason = reason;
        this.targetAnnotation = targetAnnotation;
    }

    static TransactionDecision refused(String sourcePath, String sourceKind,
                                       String reasonCode, String reason) {
        return new TransactionDecision(sourcePath, sourceKind, "REFUSED", reasonCode, reason, null);
    }

    static TransactionDecision migrated(String sourcePath, String sourceKind,
                                        String reasonCode, String reason,
                                        String targetAnnotation) {
        return new TransactionDecision(sourcePath, sourceKind, "MIGRATED", reasonCode, reason,
                targetAnnotation);
    }

    boolean isRefused() {
        return "REFUSED".equals(outcome);
    }

    String getReasonCode() {
        return reasonCode;
    }

    String getTargetAnnotation() {
        return targetAnnotation;
    }

    String markerDescription() {
        return outcome + " [" + reasonCode + "]: " + reason;
    }

    MigrationAssessmentTable.Row toRow() {
        return new MigrationAssessmentTable.Row(sourcePath, sourceKind, "Transactions",
                "org.springframework.transaction.annotation.Transactional",
                isRefused() ? "MANUAL" : "PARTIAL", outcome, reasonCode, reason,
                "jakarta.transaction.Transactional");
    }
}
