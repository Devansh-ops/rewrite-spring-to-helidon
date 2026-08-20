package io.github.devanshops.rewrite.helidon;

import org.openrewrite.Recipe;
import org.openrewrite.Validated;
import org.openrewrite.config.Environment;
import org.openrewrite.config.RecipeDescriptor;

/** Fails validation when the separately licensed Spring Boot 4 upgrade recipe is unavailable. */
public final class RequireSpringBoot4UpgradeRecipe extends Recipe {
    static final String REQUIRED_RECIPE = "org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0";

    @Override
    public String getDisplayName() {
        return "Require the optional Spring Boot 4 upgrade recipe";
    }

    @Override
    public String getDescription() {
        return "Prevents the optional Boot 4 normalization wrapper from silently running without " +
               "the separately supplied rewrite-spring recipe.";
    }

    @Override
    public Validated<Object> validate() {
        boolean available = false;
        Environment environment = Environment.builder().scanRuntimeClasspath().build();
        for (RecipeDescriptor descriptor : environment.listRecipeDescriptors()) {
            if (REQUIRED_RECIPE.equals(descriptor.getName())) {
                available = true;
                break;
            }
        }
        return super.validate().and(Validated.test(
                "rewriteSpring",
                "Add org.openrewrite.recipe:rewrite-spring:6.37.1 to the recipe runtime " +
                "before activating SpringBootToHelidonMpViaBoot4",
                Boolean.valueOf(available),
                value -> value.booleanValue()));
    }
}
