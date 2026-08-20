package io.github.devanshops.rewrite.helidon;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

import java.time.Duration;

/** Assesses Spring MVC controllers without changing their runtime semantics. */
public class MigrateSpringMvcToJakartaRest extends Recipe {
    private static final String REST_CONTROLLER_TYPE =
            "org.springframework.web.bind.annotation.RestController";
    private static final AnnotationMatcher REST_CONTROLLER =
            new AnnotationMatcher("@" + REST_CONTROLLER_TYPE);
    private static final String MANUAL_MIGRATION =
            "Manual migration: this assessment preserves the Spring MVC controller because routing, binding, validation, " +
            "and response semantics are not yet proven equivalent; no source code was changed";

    @Override
    public String getDisplayName() {
        return "Assess Spring MVC controllers for Jakarta REST migration";
    }

    @Override
    public String getDescription() {
        return "Preserves every attributed Spring `@RestController` annotation and marks the exact " +
               "controller that requires routing, binding, validation, and response-semantics review " +
               "before migration to Jakarta REST.";
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(8);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
                new UsesType<>(REST_CONTROLLER_TYPE, false),
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                        J.Annotation visited = super.visitAnnotation(annotation, ctx);
                        return REST_CONTROLLER.matches(visited) ?
                                SearchResult.found(visited, MANUAL_MIGRATION) : visited;
                    }
                });
    }
}
