package io.github.devanshops.rewrite.helidon.table;

import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

/** Module-scoped eligibility and refusal evidence for the conservative migration profile. */
public final class ModuleMigrationReadinessTable
        extends DataTable<ModuleMigrationReadinessTable.Row> {

    public ModuleMigrationReadinessTable(Recipe recipe) {
        super(recipe,
                "Spring Boot module migration eligibility",
                "Read-only, module-scoped eligibility evidence for a documented migration " +
                "profile. Eligibility is not a runtime-readiness certification.");
    }

    public static final class Row {
        @Column(displayName = "Module path",
                description = "Normalized project-relative root of the owning build module.")
        private final String modulePath;

        @Column(displayName = "Build system",
                description = "Resolved build system for the owning module.")
        private final String buildSystem;

        @Column(displayName = "Profile",
                description = "Stable migration profile against which eligibility was assessed.")
        private final String profile;

        @Column(displayName = "Outcome",
                description = "ELIGIBLE_FOR_PROFILE or REFUSED for the complete supplied module.")
        private final String outcome;

        @Column(displayName = "Source path",
                description = "Project-relative path containing the evidence or module anchor.")
        private final String sourcePath;

        @Column(displayName = "Source kind",
                description = "Artifact or source-set kind containing the evidence.")
        private final String sourceKind;

        @Column(displayName = "Feature",
                description = "Migration feature family represented by the evidence.")
        private final String feature;

        @Column(displayName = "Construct",
                description = "Build, source, resource, or metadata construct; values are omitted.")
        private final String construct;

        @Column(displayName = "Reason code",
                description = "Stable machine-readable eligibility or refusal reason.")
        private final String reasonCode;

        @Column(displayName = "Reason",
                description = "Human-readable explanation of the module outcome.")
        private final String reason;

        @Column(displayName = "Suggested direction",
                description = "Suggested bounded recipe or manual next step.")
        private final String suggestedDirection;

        public Row(String modulePath, String buildSystem, String profile, String outcome,
                   String sourcePath, String sourceKind, String feature, String construct,
                   String reasonCode, String reason, String suggestedDirection) {
            this.modulePath = modulePath;
            this.buildSystem = buildSystem;
            this.profile = profile;
            this.outcome = outcome;
            this.sourcePath = sourcePath;
            this.sourceKind = sourceKind;
            this.feature = feature;
            this.construct = construct;
            this.reasonCode = reasonCode;
            this.reason = reason;
            this.suggestedDirection = suggestedDirection;
        }

        public String getModulePath() {
            return modulePath;
        }

        public String getBuildSystem() {
            return buildSystem;
        }

        public String getProfile() {
            return profile;
        }

        public String getOutcome() {
            return outcome;
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

        public String getReasonCode() {
            return reasonCode;
        }

        public String getReason() {
            return reason;
        }

        public String getSuggestedDirection() {
            return suggestedDirection;
        }
    }
}
