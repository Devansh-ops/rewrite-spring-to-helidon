package io.github.devanshops.rewrite.helidon.table;

import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

/**
 * Spring API occurrences that remain relevant while migrating an application to Helidon MP.
 */
public final class SpringUsageTable extends DataTable<SpringUsageTable.Row> {

    public SpringUsageTable(Recipe recipe) {
        super(recipe,
                "Spring usage inventory",
                "Spring types found in each source file, their migration support, and the suggested target API.");
    }

    public static final class Row {
        @Column(displayName = "Source path", description = "Path of the source file containing the Spring type.")
        private final String sourcePath;

        @Column(displayName = "Feature", description = "Spring feature family represented by the type.")
        private final String feature;

        @Column(displayName = "Spring type", description = "Fully qualified Spring type or imported Spring package.")
        private final String springType;

        @Column(displayName = "Support level", description = "Migration support: AUTOMATIC, PARTIAL, or MANUAL.")
        private final String supportLevel;

        @Column(displayName = "Suggested replacement", description = "Suggested Helidon, Jakarta, or MicroProfile replacement.")
        private final String suggestedReplacement;

        public Row(String sourcePath, String feature, String springType, String supportLevel,
                   String suggestedReplacement) {
            this.sourcePath = sourcePath;
            this.feature = feature;
            this.springType = springType;
            this.supportLevel = supportLevel;
            this.suggestedReplacement = suggestedReplacement;
        }

        public String getSourcePath() {
            return sourcePath;
        }

        public String getFeature() {
            return feature;
        }

        public String getSpringType() {
            return springType;
        }

        public String getSupportLevel() {
            return supportLevel;
        }

        public String getSuggestedReplacement() {
            return suggestedReplacement;
        }
    }
}
