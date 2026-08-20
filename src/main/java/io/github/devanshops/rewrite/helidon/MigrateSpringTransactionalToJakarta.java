package io.github.devanshops.rewrite.helidon;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

import java.time.Duration;

/**
 * Preserves Spring transaction annotations and marks them for an explicit
 * Jakarta Transactions migration decision.
 */
public class MigrateSpringTransactionalToJakarta extends Recipe {
    private static final AnnotationMatcher TRANSACTIONAL =
            new AnnotationMatcher("@org.springframework.transaction.annotation.Transactional");
    static final String MANUAL_MIGRATION =
            "Manual migration: Spring transaction semantics require explicit Jakarta Transactions review";

    @Override
    public String getDisplayName() {
        return "Assess Spring transactions for Jakarta Transactions migration";
    }

    @Override
    public String getDescription() {
        return "Preserves and marks direct Spring `@Transactional` annotations, including source " +
               "meta-annotation declarations; composed annotation usages, propagation, rollback rules, " +
               "manager selection, global policy, and interception semantics require a separate audit.";
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(5);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                J.Annotation a = super.visitAnnotation(annotation, ctx);
                return isSpringTransactional(a) ? SearchResult.found(a, MANUAL_MIGRATION) : a;
            }
        };
    }

    private static boolean isSpringTransactional(J.Annotation annotation) {
        if (TRANSACTIONAL.matches(annotation)) {
            return true;
        }
        JavaType.FullyQualified annotationType = TypeUtils.asFullyQualified(annotation.getType());
        return annotationType != null &&
               TRANSACTIONAL.matchesAnnotationOrMetaAnnotation(annotationType);
    }
}
