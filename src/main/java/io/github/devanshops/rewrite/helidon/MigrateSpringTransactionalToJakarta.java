package io.github.devanshops.rewrite.helidon;

import io.github.devanshops.rewrite.helidon.table.MigrationAssessmentTable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.ShortenFullyQualifiedTypeReferences;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.xml.tree.Xml;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Migrates the bounded, behavior-preserving subset of Spring transaction
 * annotations to Jakarta Transactions.
 */
public class MigrateSpringTransactionalToJakarta
        extends ScanningRecipe<TransactionProjectIndex> {
    private static final AnnotationMatcher TRANSACTIONAL =
            new AnnotationMatcher("@org.springframework.transaction.annotation.Transactional");
    private static final String REPORTED_ROWS =
            MigrateSpringTransactionalToJakarta.class.getName() + ".reportedRows";

    private transient MigrationAssessmentTable assessment = new MigrationAssessmentTable(this);

    @Override
    public String getDisplayName() {
        return "Migrate bounded Spring transactions to Jakarta Transactions";
    }

    @Override
    public String getDescription() {
        return "Migrates direct Spring `@Transactional` annotations only when project and " +
               "interception evidence establishes a behavior-preserving Jakarta Transactions mapping; " +
               "unsupported transaction scopes are preserved with an explicit refusal.";
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(5);
    }

    /** Whether this explicitly activated recipe accepts Spring SUPPORTS semantics. */
    protected boolean includesSupports() {
        return false;
    }

    @Override
    public TransactionProjectIndex getInitialValue(ExecutionContext ctx) {
        return new TransactionProjectIndex();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(final TransactionProjectIndex index) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree preVisit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }
                stopAfterPreVisit();
                if (tree instanceof J.CompilationUnit) {
                    javaScanner(index).visit(tree, ctx);
                } else if (tree instanceof Xml.Document) {
                    Xml.Document document = (Xml.Document) tree;
                    index.recordXmlPolicy(document.getSourcePath(), document.printAll());
                }
                return tree;
            }
        };
    }

    private static JavaIsoVisitor<ExecutionContext> javaScanner(
            final TransactionProjectIndex index) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                J.Annotation visited = super.visitAnnotation(annotation, ctx);
                index.recordProjectAnnotation(visited, getCursor());
                if (TRANSACTIONAL.matches(visited)) {
                    index.record(visited, getCursor(), false, false);
                } else {
                    JavaType.FullyQualified type = TypeUtils.asFullyQualified(visited.getType());
                    if (type == null && isExactSpringTransactionalSyntax(visited, getCursor())) {
                        index.record(visited, getCursor(), false, true);
                    } else if (type != null && (TRANSACTIONAL.matchesAnnotationOrMetaAnnotation(type) ||
                            index.isComposedTransactionAnnotation(type.getFullyQualifiedName()))) {
                        index.record(visited, getCursor(), true, false);
                    }
                }
                return visited;
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl,
                                                             ExecutionContext ctx) {
                J.ClassDeclaration visited = super.visitClassDeclaration(classDecl, ctx);
                index.recordClass(visited, getCursor());
                return visited;
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(
                    J.VariableDeclarations multiVariable, ExecutionContext ctx) {
                J.VariableDeclarations visited = super.visitVariableDeclarations(multiVariable, ctx);
                index.recordManagerDeclaration(visited, getCursor());
                return visited;
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method,
                                                               ExecutionContext ctx) {
                J.MethodDeclaration visited = super.visitMethodDeclaration(method, ctx);
                index.recordManagerMethod(visited, getCursor());
                return visited;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
                                                            ExecutionContext ctx) {
                J.MethodInvocation visited = super.visitMethodInvocation(method, ctx);
                index.recordProgrammaticPolicy(visited, getCursor());
                return visited;
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J.NewClass visited = super.visitNewClass(newClass, ctx);
                index.recordProgrammaticConstruction(visited, getCursor());
                return visited;
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
                J.Identifier visited = super.visitIdentifier(identifier, ctx);
                index.recordSpringTransactionExceptionUse(visited, getCursor());
                return visited;
            }
        };
    }

    private static boolean isExactSpringTransactionalSyntax(J.Annotation annotation,
                                                             org.openrewrite.Cursor cursor) {
        String annotationName = annotation.getAnnotationType().printTrimmed();
        if ("org.springframework.transaction.annotation.Transactional".equals(annotationName)) {
            return true;
        }
        if (!"Transactional".equals(annotationName)) {
            return false;
        }
        J.CompilationUnit compilationUnit = cursor.firstEnclosing(J.CompilationUnit.class);
        if (compilationUnit == null) {
            return false;
        }
        boolean exactImport = false;
        for (J.Import imported : compilationUnit.getImports()) {
            String typeName = imported.getTypeName();
            if ("org.springframework.transaction.annotation.Transactional".equals(typeName)) {
                exactImport = true;
            } else if (typeName.endsWith(".Transactional")) {
                return false;
            }
        }
        return exactImport;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(final TransactionProjectIndex index) {
        index.plan(includesSupports());
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                J.Annotation visited = super.visitAnnotation(annotation, ctx);
                TransactionDecision decision = index.decision(visited.getId());
                if (decision == null) {
                    return visited;
                }
                insertRowOnce(visited, decision, ctx);
                if (decision.isRefused()) {
                    String description = decision.markerDescription();
                    for (SearchResult marker : visited.getMarkers().findAll(SearchResult.class)) {
                        if (description.equals(marker.getDescription())) {
                            return visited;
                        }
                    }
                    return SearchResult.found(visited, description);
                }
                maybeRemoveImport("org.springframework.transaction.annotation.Transactional");
                maybeRemoveImport("org.springframework.transaction.annotation.Propagation");
                maybeRemoveImport("org.springframework.transaction.annotation.Isolation");
                maybeAddImport("jakarta.transaction.Transactional");
                J.Annotation migrated = HelidonJavaTemplate.builder(decision.getTargetAnnotation())
                        .imports("jakarta.transaction.Transactional")
                        .build()
                        .apply(updateCursor(visited), visited.getCoordinates().replace());
                doAfterVisit(new ShortenFullyQualifiedTypeReferences().getVisitor());
                return SearchResult.found(migrated, decision.markerDescription());
            }
        };
    }

    private void insertRowOnce(J.Annotation annotation, TransactionDecision decision,
                               ExecutionContext ctx) {
        Set<String> reported = ctx.computeMessageIfAbsent(REPORTED_ROWS,
                key -> ConcurrentHashMap.newKeySet());
        String key = annotation.getId().toString() + '\u0000' + decision.getReasonCode();
        if (!reported.add(key)) {
            return;
        }
        if (assessment == null) {
            assessment = new MigrationAssessmentTable(this);
        }
        assessment.insertRow(ctx, decision.toRow());
    }
}
