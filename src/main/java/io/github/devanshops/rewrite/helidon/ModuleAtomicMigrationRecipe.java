package io.github.devanshops.rewrite.helidon;

import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.groovy.GroovyIsoVisitor;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.kotlin.KotlinIsoVisitor;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.properties.tree.Properties;
import org.openrewrite.text.PlainText;
import org.openrewrite.xml.XmlIsoVisitor;
import org.openrewrite.xml.tree.Xml;
import org.openrewrite.yaml.tree.Yaml;

import java.util.Collection;

/**
 * Deep seam for module-atomic migration families: collect, freeze once, then
 * apply only the immutable committed plan.
 */
abstract class ModuleAtomicMigrationRecipe
        extends ScanningRecipe<ModuleAtomicMigrationCoordinator> {

    @Override
    public final ModuleAtomicMigrationCoordinator getInitialValue(ExecutionContext ctx) {
        return new ModuleAtomicMigrationCoordinator();
    }

    @Override
    public final TreeVisitor<?, ExecutionContext> getScanner(
            final ModuleAtomicMigrationCoordinator coordinator) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree preVisit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }
                stopAfterPreVisit();
                SourceFile sourceFile = (SourceFile) tree;
                coordinator.record(sourceFile);
                ModuleReadinessEvidenceScanner.scanSource(
                        sourceFile, coordinator.readinessIndex());
                scanMigrationFamily(sourceFile, coordinator, ctx);
                return sourceFile;
            }
        };
    }

    protected void scanMigrationFamily(SourceFile sourceFile,
                                       ModuleAtomicMigrationCoordinator coordinator,
                                       ExecutionContext ctx) {
        // The public assessment recipe intentionally has no mutating family plan.
    }

    @Override
    public final Collection<? extends SourceFile> generate(
            ModuleAtomicMigrationCoordinator coordinator,
            Collection<SourceFile> generatedInThisCycle,
            ExecutionContext ctx) {
        coordinator.freeze(generatedSourcesForPlanning(
                coordinator, generatedInThisCycle, ctx));
        reportFrozenPlan(coordinator, ctx);
        return coordinator.generatedSources();
    }

    /** Testable composition boundary for the generated sources visible at freeze time. */
    protected Collection<SourceFile> generatedSourcesForPlanning(
            ModuleAtomicMigrationCoordinator coordinator,
            Collection<SourceFile> generatedInThisCycle,
            ExecutionContext ctx) {
        return generatedInThisCycle;
    }

    protected void reportFrozenPlan(ModuleAtomicMigrationCoordinator coordinator,
                                    ExecutionContext ctx) {
        // Reporting is a recipe concern; planning and apply remain table-agnostic.
    }

    @Override
    public final TreeVisitor<?, ExecutionContext> getVisitor(
            final ModuleAtomicMigrationCoordinator coordinator) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree preVisit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }
                stopAfterPreVisit();
                SourceFile sourceFile = coordinator.applyReplacement((SourceFile) tree);
                sourceFile = mark(sourceFile, coordinator);
                if (sourceFile instanceof G.CompilationUnit) {
                    return new GroovyIsoVisitor<ExecutionContext>() {
                        @Override
                        public J.MethodInvocation visitMethodInvocation(
                                J.MethodInvocation method,
                                ExecutionContext executionContext) {
                            return mark(super.visitMethodInvocation(method, executionContext),
                                    coordinator);
                        }
                    }.visit(sourceFile, ctx);
                }
                if (sourceFile instanceof K.CompilationUnit) {
                    return new KotlinIsoVisitor<ExecutionContext>() {
                        @Override
                        public J.MethodInvocation visitMethodInvocation(
                                J.MethodInvocation method,
                                ExecutionContext executionContext) {
                            return mark(super.visitMethodInvocation(method, executionContext),
                                    coordinator);
                        }
                    }.visit(sourceFile, ctx);
                }
                if (sourceFile instanceof J.CompilationUnit) {
                    return new JavaIsoVisitor<ExecutionContext>() {
                        @Override
                        public J.Import visitImport(J.Import anImport,
                                                    ExecutionContext executionContext) {
                            return mark(super.visitImport(anImport, executionContext), coordinator);
                        }

                        @Override
                        public J.FieldAccess visitFieldAccess(
                                J.FieldAccess fieldAccess,
                                ExecutionContext executionContext) {
                            return mark(super.visitFieldAccess(fieldAccess, executionContext),
                                    coordinator);
                        }
                    }.visit(sourceFile, ctx);
                }
                if (sourceFile instanceof Xml.Document) {
                    return new XmlIsoVisitor<ExecutionContext>() {
                        @Override
                        public Xml.Tag visitTag(Xml.Tag tag,
                                               ExecutionContext executionContext) {
                            return mark(super.visitTag(tag, executionContext), coordinator);
                        }
                    }.visit(sourceFile, ctx);
                }
                return sourceFile;
            }
        };
    }

    private static <T extends Tree> T mark(
            T tree, ModuleAtomicMigrationCoordinator coordinator) {
        String description = coordinator.markerDescription(tree.getId());
        if (description == null) {
            return tree;
        }
        for (SearchResult existing : tree.getMarkers().findAll(SearchResult.class)) {
            if (description.equals(existing.getDescription())) {
                return tree;
            }
        }
        return SearchResult.found(tree, description);
    }
}
