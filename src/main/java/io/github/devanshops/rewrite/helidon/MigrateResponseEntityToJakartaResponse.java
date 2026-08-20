package io.github.devanshops.rewrite.helidon;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

import java.time.Duration;

/** Preserves Spring {@code ResponseEntity} contracts and marks them for explicit migration. */
public class MigrateResponseEntityToJakartaResponse extends Recipe {
    private static final String SPRING_RESPONSE = "org.springframework.http.ResponseEntity";
    static final String MANUAL_MIGRATION =
            "Manual migration: this assessment preserves ResponseEntity because status, headers, entity providers, and " +
            "caller contracts are not yet proven equivalent; no response types or builders were changed";

    @Override
    public String getDisplayName() {
        return "Assess Spring `ResponseEntity` for Jakarta REST migration";
    }

    @Override
    public String getDescription() {
        return "Preserves Spring `ResponseEntity` and marks its imports and attributed uses for explicit " +
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
                    public J.Import visitImport(J.Import anImport, ExecutionContext ctx) {
                        J.Import visited = super.visitImport(anImport, ctx);
                        return SPRING_RESPONSE.equals(visited.getTypeName()) ?
                                SearchResult.found(visited, MANUAL_MIGRATION) : visited;
                    }

                    @Override
                    public J.Identifier visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
                        J.Identifier visited = super.visitIdentifier(identifier, ctx);
                        if (getCursor().firstEnclosing(J.Import.class) != null ||
                                !TypeUtils.isOfClassType(visited.getType(), SPRING_RESPONSE)) {
                            return visited;
                        }
                        return SearchResult.found(visited, MANUAL_MIGRATION);
                    }
                });
    }
}
