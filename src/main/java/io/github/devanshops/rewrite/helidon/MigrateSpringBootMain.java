package io.github.devanshops.rewrite.helidon;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

import java.time.Duration;

/** Assesses Spring Boot bootstrap sites without changing their runtime semantics. */
public class MigrateSpringBootMain extends Recipe {
    private static final AnnotationMatcher SPRING_BOOT_APPLICATION =
            new AnnotationMatcher("@org.springframework.boot.autoconfigure.SpringBootApplication");
    private static final MethodMatcher SPRING_APPLICATION_RUN =
            new MethodMatcher("org.springframework.boot.SpringApplication run(..)");

    private static final String APPLICATION_REVIEW =
            "Manual migration: preserve @SpringBootApplication until Helidon bootstrap " +
            "dependencies, configuration, and lifecycle semantics are reviewed";
    private static final String RUN_REVIEW =
            "Manual migration: preserve SpringApplication.run until Helidon bootstrap " +
            "dependencies, configuration, and lifecycle semantics are reviewed";

    @Override
    public String getDisplayName() {
        return "Assess the Spring Boot application entry point";
    }

    @Override
    public String getDescription() {
        return "Preserves every `@SpringBootApplication` annotation and `SpringApplication.run` " +
               "invocation while marking the exact bootstrap sites that require dependency, " +
               "configuration, and lifecycle review before changing to the Helidon runtime.";
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(10);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                J.Annotation visited = super.visitAnnotation(annotation, ctx);
                return SPRING_BOOT_APPLICATION.matches(visited) ?
                        SearchResult.found(visited, APPLICATION_REVIEW) : visited;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
                                                             ExecutionContext ctx) {
                J.MethodInvocation visited = super.visitMethodInvocation(method, ctx);
                return SPRING_APPLICATION_RUN.matches(visited) ?
                        SearchResult.found(visited, RUN_REVIEW) : visited;
            }
        };
    }
}
