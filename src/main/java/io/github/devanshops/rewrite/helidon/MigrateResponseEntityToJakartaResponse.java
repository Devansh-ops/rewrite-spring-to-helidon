package io.github.devanshops.rewrite.helidon;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

import java.time.Duration;

/** Preserves Spring {@code ResponseEntity} contracts and marks them for explicit migration. */
public class MigrateResponseEntityToJakartaResponse extends Recipe {
    private static final String SPRING_RESPONSE = "org.springframework.http.ResponseEntity";
    static final String MANUAL_MIGRATION =
            "Manual migration: v0.1 preserves ResponseEntity because status, headers, entity providers, and " +
            "caller contracts are not yet proven equivalent; no response types or builders were changed";

    @Override
    public String getDisplayName() {
        return "Assess Spring `ResponseEntity` for Jakarta REST migration";
    }

    @Override
    public String getDescription() {
        return "Preserves each source file that uses Spring `ResponseEntity` and marks it for explicit " +
               "response-contract migration because Jakarta REST status, header, entity-provider, and caller " +
               "semantics are not automatically equivalent.";
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(7);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
                new UsesType<>(SPRING_RESPONSE, false),
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.CompilationUnit visitCompilationUnit(J.CompilationUnit compilationUnit,
                                                                   ExecutionContext ctx) {
                        J.CompilationUnit cu = super.visitCompilationUnit(compilationUnit, ctx);
                        final boolean[] marked = new boolean[1];
                        cu = cu.withImports(ListUtils.map(cu.getImports(), anImport -> {
                            if (!marked[0] && SPRING_RESPONSE.equals(anImport.getTypeName())) {
                                marked[0] = true;
                                return SearchResult.found(anImport, MANUAL_MIGRATION);
                            }
                            return anImport;
                        }));
                        return marked[0] ? cu : SearchResult.found(cu, MANUAL_MIGRATION);
                    }
                });
    }
}
