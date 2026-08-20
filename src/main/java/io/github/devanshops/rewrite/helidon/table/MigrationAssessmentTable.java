package io.github.devanshops.rewrite.helidon.table;

import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

/**
 * Project-wide Spring constructs that affect a migration to Helidon MP.
 */
public final class MigrationAssessmentTable extends DataTable<MigrationAssessmentTable.Row> {

    public MigrationAssessmentTable(Recipe recipe) {
        super(recipe,
                "Spring project usage inventory",
                "Read-only inventory of Spring build, source, configuration, XML, and metadata constructs. " +
                "This assessment is not a migration-readiness certification.");
    }

    public static final class Row {
        @Column(displayName = "Source path", description = "Full project-relative path containing the construct.")
        private final String sourcePath;

        @Column(displayName = "Source kind", description = "Artifact kind and, for Java, its main or test source set.")
        private final String sourceKind;

        @Column(displayName = "Feature", description = "Spring feature family represented by the construct.")
        private final String feature;

        @Column(displayName = "Construct", description = "Configuration key, build coordinate, metadata kind, or Java type. Configuration values are never recorded.")
        private final String construct;

        @Column(displayName = "Support level", description = "Migration support: PARTIAL or MANUAL. Project assessment never reports AUTOMATIC.")
        private final String supportLevel;

        @Column(displayName = "Outcome", description = "Assessment outcome, such as a bounded recipe being available or manual review being required.")
        private final String outcome;

        @Column(displayName = "Reason code", description = "Stable machine-readable reason for the assessment outcome.")
        private final String reasonCode;

        @Column(displayName = "Reason", description = "Human-readable explanation of the assessment outcome.")
        private final String reason;

        @Column(displayName = "Suggested recipe or direction", description = "Suggested recipe identifier or migration direction.")
        private final String suggestedRecipeOrDirection;

        public Row(String sourcePath, String sourceKind, String feature, String construct,
                   String supportLevel, String outcome, String reasonCode, String reason,
                   String suggestedRecipeOrDirection) {
            this.sourcePath = sourcePath;
            this.sourceKind = sourceKind;
            this.feature = feature;
            this.construct = construct;
            this.supportLevel = supportLevel;
            this.outcome = outcome;
            this.reasonCode = reasonCode;
            this.reason = reason;
            this.suggestedRecipeOrDirection = suggestedRecipeOrDirection;
        }

        public String getSourcePath() {
            return sourcePath;
        }

        public String getSourceKind() {
            return sourceKind;
        }

        public String getFeature() {
            return feature;
        }

        public String getConstruct() {
            return construct;
        }

        public String getSupportLevel() {
            return supportLevel;
        }

        public String getOutcome() {
            return outcome;
        }

        public String getReasonCode() {
            return reasonCode;
        }

        public String getReason() {
            return reason;
        }

        public String getSuggestedRecipeOrDirection() {
            return suggestedRecipeOrDirection;
        }
    }
}
