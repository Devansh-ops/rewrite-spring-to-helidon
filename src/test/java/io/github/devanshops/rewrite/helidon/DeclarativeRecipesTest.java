package io.github.devanshops.rewrite.helidon;

import org.junit.jupiter.api.Test;
import org.openrewrite.config.Environment;
import org.openrewrite.config.RecipeDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DeclarativeRecipesTest {
    private static final String PREFIX = "io.github.devanshops.rewrite.helidon.";

    private final Environment environment = Environment.builder()
            .scanRuntimeClasspath("io.github.devanshops.rewrite.helidon")
            .build();

    @Test
    void discoversEveryTopLevelEntryPoint() {
        assertThat(environment.listRecipeDescriptors())
                .extracting(RecipeDescriptor::getName)
                .contains(
                        PREFIX + "AnalyzeSpringBootToHelidonMp",
                        PREFIX + "SpringBoot4ToHelidonMp",
                        PREFIX + "SpringBootToHelidonMp",
                        PREFIX + "SpringBootToHelidonMpViaBoot4");
    }

    @Test
    void analysisAndGeneralAliasHaveSmallExplicitCompositions() {
        assertSingletonPrecondition(PREFIX + "AnalyzeSpringBootToHelidonMp");
        assertThat(children(PREFIX + "AnalyzeSpringBootToHelidonMp"))
                .containsExactly(PREFIX + "FindSpringUsage");
        assertSingletonPrecondition(PREFIX + "SpringBootToHelidonMp");
        assertThat(children(PREFIX + "SpringBootToHelidonMp"))
                .containsExactly(PREFIX + "SpringBoot4ToHelidonMp");
    }

    @Test
    void canonicalRecipeKeepsSafetyCriticalOrdering() {
        assertSingletonPrecondition(PREFIX + "SpringBoot4ToHelidonMp");
        assertThat(children(PREFIX + "SpringBoot4ToHelidonMp"))
                .containsExactly(
                        PREFIX + "PrepareMavenBuildForHelidonMp",
                        PREFIX + "AddHelidonMpResources",
                        PREFIX + "MigrateSpringNamedBeansToCdi",
                        PREFIX + "MigrateSpringDiToCdi",
                        PREFIX + "MigrateResponseEntityToJakartaResponse",
                        PREFIX + "MigrateSpringMvcToJakartaRest",
                        PREFIX + "MigrateSpringTransactionalToJakarta",
                        PREFIX + "MigrateSpringValueToConfigProperty",
                        PREFIX + "MigrateSpringBootMain",
                        PREFIX + "FindSpringUsage");
    }

    @Test
    void canonicalCoreValidatesWithoutTheOptionalSpringArtifact() {
        assertThat(environment.activateRecipes(PREFIX + "SpringBoot4ToHelidonMp").validateAll())
                .allMatch(validation -> validation.isValid());
    }

    @Test
    void optionalBoot4WrapperFailsValidationWhenItsLicensedDependencyIsAbsent() throws IOException {
        // The unresolved optional child is omitted by OpenRewrite, but the guard prevents a partial run.
        assertSingletonPrecondition(PREFIX + "SpringBootToHelidonMpViaBoot4");
        assertThat(children(PREFIX + "SpringBootToHelidonMpViaBoot4"))
                .containsExactly(
                        PREFIX + "RequireSpringBoot4UpgradeRecipe",
                        PREFIX + "SpringBoot4ToHelidonMp");
        assertThat(environment.activateRecipes(PREFIX + "SpringBootToHelidonMpViaBoot4").validateAll())
                .anyMatch(validation -> validation.isInvalid());

        String yaml;
        try (InputStream resource = getClass().getClassLoader()
                .getResourceAsStream("META-INF/rewrite/optional-spring-boot-4.yml")) {
            assertThat(resource).as("optional wrapper resource").isNotNull();
            yaml = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        int upgrade = yaml.indexOf("org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0");
        int helidon = yaml.indexOf(PREFIX + "SpringBoot4ToHelidonMp", upgrade);
        assertThat(upgrade).isGreaterThanOrEqualTo(0);
        assertThat(helidon).isGreaterThan(upgrade);
        assertThat(yaml)
                .contains(PREFIX + "RequireSpringBoot4UpgradeRecipe")
                .contains("org.openrewrite.recipe:rewrite-spring:6.37.1")
                .contains("Moderne Source Available License (MSAL)")
                .contains("preserves an independently licensed core");
    }

    private List<String> children(String name) {
        return descriptor(name).getRecipeList().stream()
                .map(RecipeDescriptor::getName)
                .collect(Collectors.toList());
    }

    private void assertSingletonPrecondition(String name) {
        assertThat(descriptor(name).getPreconditions())
                .extracting(RecipeDescriptor::getName)
                .containsExactly("org.openrewrite.Singleton");
    }

    private RecipeDescriptor descriptor(String name) {
        return environment.listRecipeDescriptors().stream()
                .filter(candidate -> name.equals(candidate.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Recipe was not discovered: " + name));
    }
}
