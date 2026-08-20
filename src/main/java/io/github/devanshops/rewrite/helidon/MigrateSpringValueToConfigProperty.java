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

/**
 * Preserves Spring {@code @Value} injection points and marks them for explicit
 * configuration-contract migration.
 */
public class MigrateSpringValueToConfigProperty extends Recipe {
    private static final AnnotationMatcher VALUE =
            new AnnotationMatcher("@org.springframework.beans.factory.annotation.Value");
    static final String MANUAL_MIGRATION =
            "Manual migration: bare Spring @Value injection has no behavior-preserving " +
            "direct MicroProfile Config mapping";

    @Override
    public String getDisplayName() {
        return "Assess Spring `@Value` for MicroProfile Config migration";
    }

    @Override
    public String getDescription() {
        return "Preserves every Spring `@Value` injection point and marks it for explicit " +
               "configuration-contract migration because Spring and MicroProfile Config differ " +
               "in missing, empty, and converted value semantics.";
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(3);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
                new UsesType<>("org.springframework.beans.factory.annotation.Value", false),
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                        J.Annotation a = super.visitAnnotation(annotation, ctx);
                        return VALUE.matches(a) ? SearchResult.found(a, MANUAL_MIGRATION) : a;
                    }
                });
    }
}
