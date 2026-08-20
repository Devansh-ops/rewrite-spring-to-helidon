package io.github.devanshops.rewrite.helidon;

import io.github.devanshops.rewrite.helidon.table.MigrationAssessmentTable;
import org.junit.jupiter.api.Test;
import org.openrewrite.config.Environment;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.properties.Assertions.properties;

class ProjectAssessmentTopLevelRecipesTest implements RewriteTest {
    private static final String PREFIX = "io.github.devanshops.rewrite.helidon.";

    @Test
    void analysisEntryPointRunsProjectAssessmentReadOnlyAndIdempotently() {
        assertTopLevelProjectAssessment("AnalyzeSpringBootToHelidonMp");
    }

    @Test
    void boot4EntryPointRunsProjectAssessmentReadOnlyAndIdempotently() {
        assertTopLevelProjectAssessment("SpringBoot4ToHelidonMp");
    }

    @Test
    void generalEntryPointRunsProjectAssessmentReadOnlyAndIdempotently() {
        assertTopLevelProjectAssessment("SpringBootToHelidonMp");
    }

    private void assertTopLevelProjectAssessment(String recipeName) {
        Environment environment = Environment.builder()
                .scanRuntimeClasspath("io.github.devanshops.rewrite.helidon")
                .build();
        rewriteRun(
          spec -> spec.recipe(environment.activateRecipes(PREFIX + recipeName))
                  .cycles(2)
                  .dataTable(MigrationAssessmentTable.Row.class, rows -> {
                      assertThat(rows).singleElement().satisfies(row -> {
                          assertThat(row.getSourcePath())
                                  .isEqualTo("orders/src/main/resources/application.properties");
                          assertThat(row.getConstruct()).isEqualTo("spring.profiles.include");
                          assertThat(row.getSupportLevel()).isEqualTo("MANUAL");
                          assertThat(row.getOutcome()).isEqualTo("MANUAL_REVIEW_REQUIRED");
                      });
                  }),
          properties(
            """
              spring.profiles.include=local
              """,
            source -> source.path("orders/src/main/resources/application.properties"))
        );
    }
}
