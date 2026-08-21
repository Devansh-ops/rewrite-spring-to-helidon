package io.github.devanshops.rewrite.helidon;

import io.github.devanshops.rewrite.helidon.table.ModuleMigrationReadinessTable;
import org.junit.jupiter.api.Test;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.text.PlainText;
import org.openrewrite.properties.tree.Properties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.properties.Assertions.properties;
import static org.openrewrite.test.SourceSpecs.text;

class ModuleAtomicMigrationRecipeTest implements RewriteTest {
    private static final UUID SAME_SOURCE_ID = new UUID(18L, 18L);

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new TestAtomicFamilyRecipe())
                .cycles(2)
                .expectedCyclesThatMakeChanges(1);
    }

    @Test
    void commitsEveryReplacementAndGenerationForAnEligibleModule() {
        rewriteRun(
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("orders/pom.xml")),
          text(
            "first",
            "migrated:first",
            source -> source.path("orders/src/main/resources/first.candidate")),
          text(
            "second",
            "migrated:second",
            source -> source.path("orders/src/testFixtures/resources/second.candidate")),
          text(
            null,
            "generated-by-test-family",
            source -> source.path(
                    "orders/src/main/resources/family-generated.txt"))
        );
    }

    @Test
    void oneRefusalCancelsEveryReplacementAndGenerationInTheModule() {
        rewriteRun(
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("orders/pom.xml")),
          text(
            "first",
            source -> source.path("orders/src/main/resources/first.candidate")),
          properties(
            """
              spring.datasource.password=never-expose-this-value
              """,
            source -> source.path("orders/src/main/resources/application.properties"))
        );
    }

    @Test
    void keepsEligibleSiblingCommitIndependentFromARefusedModule() {
        rewriteRun(
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("orders/pom.xml")),
          text(
            "orders",
            source -> source.path("orders/src/main/resources/orders.candidate")),
          properties(
            """
              spring.main.banner-mode=off
              """,
            source -> source.path("orders/src/main/resources/application.properties")),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>billing</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("billing/pom.xml")),
          text(
            "billing",
            "migrated:billing",
            source -> source.path("billing/src/main/resources/billing.candidate")),
          text(
            null,
            "generated-by-test-family",
            source -> source.path(
                    "billing/src/main/resources/family-generated.txt"))
        );
    }

    @Test
    void generatedPathCollisionRefusesBeforeAnyApplyStep() {
        rewriteRun(
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            """
              <!--~~(REFUSED [MODULE_REFUSED]: 1 blocker; no module changes were applied)~~>--><project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("orders/pom.xml")),
          text(
            "orders",
            source -> source.path("orders/src/main/resources/orders.candidate")),
          text(
            "owned-by-the-application",
            source -> source.path(
                    "orders/src/main/resources/family-generated.txt"))
        );
    }

    @Test
    void explicitlyClaimedEvidenceCommitsOnlyAfterProjectedRescanRemovesIt() {
        rewriteRun(
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("orders/pom.xml")),
          text(
            "com.example.LegacyAutoConfiguration",
            "# migrated-by-test-family",
            source -> source.path(
                    "orders/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")),
          text(
            null,
            "generated-by-test-family",
            source -> source.path(
                    "orders/src/main/resources/family-generated.txt"))
        );
    }

    @Test
    void claimWithoutReplacementRefusesTheFamilyPlan() {
        assertMetadataPlanRefusal("claim-without-replacement",
                "MODULE_PLAN_CONFLICT", 2);
    }

    @Test
    void replacementRemovingUnclaimedEvidenceRefusesTheFamilyPlan() {
        assertMetadataPlanRefusal("remove-without-claim",
                "MODULE_UNCLAIMED_EVIDENCE_REMOVAL", 1);
    }

    @Test
    void duplicateEvidenceClaimRefusesTheFamilyPlan() {
        assertMetadataPlanRefusal("duplicate-claim", "MODULE_PLAN_CONFLICT", 2);
    }

    @Test
    void claimedEvidenceStillPresentInProjectionRefusesTheFamilyPlan() {
        assertMetadataPlanRefusal("claim-but-retain",
                "MODULE_CLAIM_NOT_NEUTRALIZED", 2);
    }

    @Test
    void duplicateReplacementCandidatesRefuseInsteadOfLastWriteWins() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows).singleElement().satisfies(row ->
                          assertThat(row.getReasonCode()).isEqualTo(
                                  "MODULE_PLAN_CONFLICT"))),
          refusingPom(1),
          text(
            "duplicate-replacement",
            source -> source.path("orders/src/main/resources/orders.candidate"))
        );
    }

    @Test
    void generatedSourceIntroducingSpringResidueRefusesTheFamilyPlan() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows).singleElement().satisfies(row ->
                          assertThat(row.getReasonCode()).isEqualTo(
                                  "MODULE_SPRING_METADATA"))),
          refusingPom(1),
          text(
            "introduce-spring-generation",
            source -> source.path("orders/src/main/resources/generation.trigger"))
        );
    }

    @Test
    void oneClaimCannotRemoveTwoIdenticalEvidenceOccurrences() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows).singleElement().satisfies(row ->
                          assertThat(row.getReasonCode()).isEqualTo(
                                  "MODULE_UNCLAIMED_EVIDENCE_REMOVAL"))),
          refusingPom(1),
          properties(
            """
              spring.duplicate=first
              spring.duplicate=second
              """,
            source -> source.path("orders/src/main/resources/application.properties"))
        );
    }

    @Test
    void sameEvidenceClaimedByOccurrenceAndSemanticKeyRefuses() {
        assertMetadataPlanRefusal("claim-by-both-apis", "MODULE_PLAN_CONFLICT", 1);
    }

    @Test
    void fabricatedReplacementAndRealGenerationRefuseWithoutPartialCommit() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows).singleElement().satisfies(row ->
                          assertThat(row.getReasonCode()).isEqualTo(
                                  "MODULE_PLAN_CONFLICT"))),
          refusingPom(1),
          text(
            "fabricated-before",
            source -> source.path("orders/src/main/resources/orders.candidate"))
        );
    }

    @Test
    void unresolvedClaimRefusesEveryCandidateModule() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getModulePath,
                                  ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  org.assertj.core.groups.Tuple.tuple(
                                          "alpha", "MODULE_PLAN_CONFLICT"),
                                  org.assertj.core.groups.Tuple.tuple(
                                          "beta", "MODULE_PLAN_CONFLICT"))),
          refusingPom("alpha", 1),
          refusingPom("beta", 1),
          text(
            "trigger",
            source -> source.path("beta/src/main/resources/family.invalid-claim")),
          text(
            "beta",
            source -> source.path("beta/src/main/resources/beta.candidate"))
        );
    }

    @Test
    void replacementIntroducingNewSpringResidueRefusesBeforeApply() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows).singleElement().satisfies(row ->
                          assertThat(row.getReasonCode()).isEqualTo(
                                  "MODULE_SPRING_METADATA"))),
          refusingPom(1),
          text(
            "# introduce-via-replacement",
            source -> source.path(
                    "orders/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"))
        );
    }

    @Test
    void externallyGeneratedPathConflictRefusesTheFamilyPlan() {
        rewriteRun(
          spec -> spec.recipes(
                          new ExternalGeneratedSourceRecipe(),
                          new TestAtomicFamilyRecipe())
                  .cycles(1)
                  .dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                          assertThat(rows).singleElement().satisfies(row ->
                                  assertThat(row.getReasonCode()).isEqualTo(
                                          "MODULE_GENERATED_PATH_COLLISION"))),
          refusingPom(1),
          text(
            null,
            "owned-by-external-generator",
            source -> source.path(
                    "orders/src/main/resources/family-generated.txt"))
        );
    }

    @Test
    void externallyGeneratedPathCollidingWithSuppliedSourceRefuses() {
        rewriteRun(
          spec -> spec.recipe(new SuppliedPathCollisionFamilyRecipe())
                  .cycles(1)
                  .dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                          assertThat(rows).singleElement().satisfies(row ->
                                  assertThat(row.getReasonCode()).isEqualTo(
                                          "MODULE_GENERATED_PATH_COLLISION"))),
          refusingPom(1),
          text(
            "owned-by-the-supplied-module",
            source -> source.path(
                    "orders/src/main/resources/supplied.txt"))
        );
    }

    @Test
    void externallyGeneratedSourceWithSameIdentityButChangedContentRefuses() {
        rewriteRun(
          spec -> spec.recipe(new SameIdentityChangedContentFamilyRecipe())
                  .cycles(1)
                  .dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                          assertThat(rows).singleElement().satisfies(row ->
                                  assertThat(row.getReasonCode()).isEqualTo(
                                          "MODULE_GENERATED_PATH_COLLISION"))),
          refusingPom(1),
          text(
            "owned-by-the-supplied-module",
            source -> source.path(
                            "orders/src/main/resources/same-id.txt")
                    .mapBeforeRecipe(plainText -> plainText.withId(SAME_SOURCE_ID)))
        );
    }

    @Test
    void exactEquivalentExternalSourceMayDeduplicate() {
        rewriteRun(
          spec -> spec.recipe(new SameIdentityEquivalentSourceFamilyRecipe())
                  .cycles(1)
                  .dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                          assertThat(rows).singleElement().satisfies(row ->
                                  assertThat(row.getOutcome()).isEqualTo(
                                          "ELIGIBLE_FOR_PROFILE"))),
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0.0</version>
              </project>
              """,
            source -> source.path("orders/pom.xml")),
          text(
            "owned-by-the-supplied-module",
            source -> source.path(
                            "orders/src/main/resources/same-id.txt")
                    .mapBeforeRecipe(plainText -> plainText.withId(SAME_SOURCE_ID))),
          text(
            null,
            "generated-by-test-family",
            source -> source.path(
                    "orders/src/main/resources/family-generated.txt"))
        );
    }

    @Test
    void identicalMarkerlessPlanProblemsRemainDistinctAndDeterministic() {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows ->
                  assertThat(rows)
                          .extracting(ModuleMigrationReadinessTable.Row::getReasonCode)
                          .containsExactly(
                                  "MODULE_PLAN_CONFLICT",
                                  "MODULE_PLAN_CONFLICT")),
          refusingPom(2),
          text(
            "trigger",
            source -> source.path(
                    "orders/src/main/resources/family.two-invalid-claims"))
        );
    }

    private void assertMetadataPlanRefusal(String metadata,
                                           String expectedPlanReason,
                                           int blockerCount) {
        rewriteRun(
          spec -> spec.dataTable(ModuleMigrationReadinessTable.Row.class, rows -> {
              assertThat(rows).hasSize(blockerCount);
              assertThat(rows).extracting(ModuleMigrationReadinessTable.Row::getReasonCode)
                      .contains(expectedPlanReason);
          }),
          refusingPom(blockerCount),
          text(
            metadata,
            source -> source.path(
                    "orders/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"))
        );
    }

    private static org.openrewrite.test.SourceSpecs refusingPom(int blockers) {
        return refusingPom("orders", blockers);
    }

    private static org.openrewrite.test.SourceSpecs refusingPom(String module, int blockers) {
        String noun = blockers == 1 ? " blocker" : " blockers";
        return pomXml(
          """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>orders</artifactId>
                <version>1.0.0</version>
            </project>
            """,
          "<!--~~(REFUSED [MODULE_REFUSED]: " + blockers + noun +
          "; no module changes were applied)~~>--><project>\n" +
          "    <modelVersion>4.0.0</modelVersion>\n" +
          "    <groupId>com.example</groupId>\n" +
          "    <artifactId>orders</artifactId>\n" +
          "    <version>1.0.0</version>\n" +
          "</project>",
          source -> source.path(module + "/pom.xml"));
    }

    private static class TestAtomicFamilyRecipe extends ModuleAtomicMigrationRecipe {
        private static final String REPORTED =
                TestAtomicFamilyRecipe.class.getName() + ".reported";
        private transient ModuleMigrationReadinessTable readiness =
                new ModuleMigrationReadinessTable(this);

        @Override
        public String getDisplayName() {
            return "Test module-atomic migration family";
        }

        @Override
        public String getDescription() {
            return "Exercises the package-private module-atomic plan/apply seam.";
        }

        @Override
        protected void scanMigrationFamily(SourceFile sourceFile,
                                           ModuleAtomicMigrationCoordinator coordinator,
                                           ExecutionContext ctx) {
            String fileName = sourceFile.getSourcePath().getFileName().toString();
            if (sourceFile instanceof PlainText && fileName.endsWith(".candidate")) {
                PlainText candidate = (PlainText) sourceFile;
                if ("fabricated-before".equals(candidate.getText())) {
                    PlainText fabricated = candidate.withText("fabricated-source");
                    coordinator.proposeReplacement(fabricated,
                            fabricated.withText("migrated:fabricated-source"));
                } else if ("duplicate-replacement".equals(candidate.getText())) {
                    coordinator.proposeReplacement(candidate,
                            candidate.withText("first-replacement"));
                    coordinator.proposeReplacement(candidate,
                            candidate.withText("second-replacement"));
                } else if (!candidate.getText().startsWith("migrated:")) {
                    coordinator.proposeReplacement(candidate,
                            candidate.withText("migrated:" + candidate.getText()));
                }
            }
            if (sourceFile instanceof PlainText &&
                    "org.springframework.boot.autoconfigure.AutoConfiguration.imports"
                            .equals(fileName)) {
                PlainText metadata = (PlainText) sourceFile;
                if ("claim-without-replacement".equals(metadata.getText())) {
                    coordinator.claimEvidence(metadata.getId());
                } else if ("remove-without-claim".equals(metadata.getText())) {
                    coordinator.proposeReplacement(metadata,
                            metadata.withText("# migrated-by-test-family"));
                } else if ("duplicate-claim".equals(metadata.getText())) {
                    coordinator.claimEvidence(metadata.getId());
                    coordinator.claimEvidence(metadata.getId());
                    coordinator.proposeReplacement(metadata,
                            metadata.withText("# migrated-by-test-family"));
                } else if ("claim-but-retain".equals(metadata.getText())) {
                    coordinator.claimEvidence(metadata.getId());
                    coordinator.proposeReplacement(metadata,
                            metadata.withText("still.spring.Registration"));
                } else if ("claim-by-both-apis".equals(metadata.getText())) {
                    coordinator.claimEvidence(metadata.getId());
                    coordinator.claimEvidenceKey(metadataEvidenceKey(metadata));
                    coordinator.proposeReplacement(metadata,
                            metadata.withText("# migrated-by-test-family"));
                } else if ("# introduce-via-replacement".equals(metadata.getText())) {
                    coordinator.proposeReplacement(metadata,
                            metadata.withText("com.example.NewSpringRegistration"));
                } else if (!"# migrated-by-test-family".equals(metadata.getText())) {
                    coordinator.claimEvidence(metadata.getId());
                    coordinator.proposeReplacement(metadata,
                            metadata.withText("# migrated-by-test-family"));
                }
            }
            if (sourceFile instanceof Properties.File) {
                Properties.File properties = (Properties.File) sourceFile;
                List<Properties.Content> retained =
                        new ArrayList<Properties.Content>();
                Properties.Entry claimed = null;
                for (Properties.Content content : properties.getContent()) {
                    if (content instanceof Properties.Entry &&
                            "spring.duplicate".equals(
                                    ((Properties.Entry) content).getKey())) {
                        if (claimed == null) {
                            claimed = (Properties.Entry) content;
                        }
                    } else {
                        retained.add(content);
                    }
                }
                if (claimed != null) {
                    coordinator.claimEvidence(claimed.getId());
                    coordinator.proposeReplacement(properties,
                            properties.withContent(retained));
                }
            }
            if (fileName.endsWith(".invalid-claim")) {
                coordinator.claimEvidence(new UUID(0L, 18L));
            }
            if (fileName.endsWith(".two-invalid-claims")) {
                coordinator.claimEvidence(new UUID(0L, 1801L));
                coordinator.claimEvidence(new UUID(0L, 1802L));
            }
            if (sourceFile instanceof PlainText &&
                    "introduce-spring-generation".equals(
                            ((PlainText) sourceFile).getText())) {
                Path root = sourceFile.getSourcePath().getParent().getParent()
                        .getParent().getParent();
                coordinator.proposeGeneration(PlainText.builder()
                        .sourcePath(root.resolve(
                                "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"))
                        .text("com.example.NewSpringRegistration")
                        .build());
            }
            if ("pom.xml".equals(fileName)) {
                Path root = sourceFile.getSourcePath().getParent();
                coordinator.proposeGeneration(PlainText.builder()
                        .sourcePath(root.resolve(
                                "src/main/resources/family-generated.txt"))
                        .text("generated-by-test-family")
                        .build());
            }
        }

        private static String metadataEvidenceKey(PlainText metadata) {
            return metadata.getSourcePath().toString().replace('\\', '/') + '\u0000' +
                   "SPRING_AUTOCONFIG_IMPORTS" + '\u0000' +
                   "Spring Boot auto-configuration metadata" + '\u0000' +
                   "AutoConfiguration.imports entry" + '\u0000' +
                   "MODULE_SPRING_METADATA";
        }

        @Override
        protected void reportFrozenPlan(ModuleAtomicMigrationCoordinator coordinator,
                                        ExecutionContext ctx) {
            Set<String> reported = ctx.computeMessageIfAbsent(REPORTED,
                    key -> ConcurrentHashMap.newKeySet());
            for (ModuleReadinessDecision decision : coordinator.decisions()) {
                if (reported.add(decision.rowKey())) {
                    readiness.insertRow(ctx, decision.toRow());
                }
            }
        }
    }

    private static final class SuppliedPathCollisionFamilyRecipe
            extends TestAtomicFamilyRecipe {
        @Override
        protected Collection<SourceFile> generatedSourcesForPlanning(
                ModuleAtomicMigrationCoordinator coordinator,
                Collection<SourceFile> generatedInThisCycle,
                ExecutionContext ctx) {
            List<SourceFile> planning = new ArrayList<SourceFile>(generatedInThisCycle);
            planning.add(PlainText.builder()
                    .sourcePath(Path.of(
                            "orders/src/main/resources/supplied.txt"))
                    .text("owned-by-an-external-generator")
                    .build());
            return planning;
        }
    }

    private static final class SameIdentityChangedContentFamilyRecipe
            extends TestAtomicFamilyRecipe {
        @Override
        protected Collection<SourceFile> generatedSourcesForPlanning(
                ModuleAtomicMigrationCoordinator coordinator,
                Collection<SourceFile> generatedInThisCycle,
                ExecutionContext ctx) {
            List<SourceFile> planning = new ArrayList<SourceFile>(generatedInThisCycle);
            planning.add(PlainText.builder()
                    .id(SAME_SOURCE_ID)
                    .sourcePath(Path.of(
                            "orders/src/main/resources/same-id.txt"))
                    .text("changed-by-external-generator")
                    .build());
            return planning;
        }
    }

    private static final class SameIdentityEquivalentSourceFamilyRecipe
            extends TestAtomicFamilyRecipe {
        @Override
        protected Collection<SourceFile> generatedSourcesForPlanning(
                ModuleAtomicMigrationCoordinator coordinator,
                Collection<SourceFile> generatedInThisCycle,
                ExecutionContext ctx) {
            List<SourceFile> planning = new ArrayList<SourceFile>(generatedInThisCycle);
            planning.add(PlainText.builder()
                    .id(SAME_SOURCE_ID)
                    .sourcePath(Path.of(
                            "orders/src/main/resources/same-id.txt"))
                    .text("owned-by-the-supplied-module")
                    .build());
            return planning;
        }
    }

    private static final class ExternalGeneratedSourceRecipe
            extends ScanningRecipe<AtomicBoolean> {
        @Override
        public String getDisplayName() {
            return "Generate an externally owned test source";
        }

        @Override
        public String getDescription() {
            return "Exercises collision composition through the public recipe lifecycle.";
        }

        @Override
        public AtomicBoolean getInitialValue(ExecutionContext ctx) {
            return new AtomicBoolean();
        }

        @Override
        public TreeVisitor<?, ExecutionContext> getScanner(final AtomicBoolean generate) {
            return new TreeVisitor<Tree, ExecutionContext>() {
                @Override
                public Tree preVisit(Tree tree, ExecutionContext ctx) {
                    if (tree instanceof SourceFile) {
                        stopAfterPreVisit();
                        if ("orders/pom.xml".equals(((SourceFile) tree).getSourcePath()
                                .toString().replace('\\', '/'))) {
                            generate.set(true);
                        }
                    }
                    return tree;
                }
            };
        }

        @Override
        public Collection<? extends SourceFile> generate(
                AtomicBoolean generate,
                Collection<SourceFile> generatedInThisCycle,
                ExecutionContext ctx) {
            if (!generate.get()) {
                return Collections.emptyList();
            }
            return Collections.singletonList(PlainText.builder()
                    .sourcePath(Path.of(
                            "orders/src/main/resources/family-generated.txt"))
                    .text("owned-by-external-generator")
                    .build());
        }

        @Override
        public TreeVisitor<?, ExecutionContext> getVisitor(AtomicBoolean generate) {
            return TreeVisitor.noop();
        }
    }
}
