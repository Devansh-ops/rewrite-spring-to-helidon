package io.github.devanshops.rewrite.helidon;

import io.github.devanshops.rewrite.helidon.table.SpringUsageTable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inventories Spring types without retaining visitor state between source files or recipe runs.
 */
public final class FindSpringUsage extends Recipe {

    private static final String SPRING_PREFIX = "org.springframework.";
    private static final String CURRENT_IMPORTS = FindSpringUsage.class.getName() + ".currentImports";
    private static final String REPORTED_USAGE = FindSpringUsage.class.getName() + ".reportedUsage";
    private static final int MAX_TYPE_GRAPH_DEPTH = 32;

    private transient SpringUsageTable springUsage = new SpringUsageTable(this);

    @Override
    public String getDisplayName() {
        return "Find Spring usage requiring Helidon migration";
    }

    @Override
    public String getDescription() {
        return "Finds Spring imports and type usages, classifies migration support, and records suggested Helidon-compatible replacements.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit compilationUnit, ExecutionContext ctx) {
                ImportIndex imports = ImportIndex.from(compilationUnit);
                getCursor().putMessage(CURRENT_IMPORTS, imports);
                Set<String> springTypes = collectSpringTypes(compilationUnit, imports);
                String sourcePath = compilationUnit.getSourcePath().toString()
                        .replace(File.separatorChar, '/');
                Set<String> reported = ctx.computeMessageIfAbsent(REPORTED_USAGE,
                        key -> ConcurrentHashMap.newKeySet());
                for (String springType : springTypes) {
                    if (reported.add(sourcePath + '\u0000' + springType)) {
                        Classification classification = classify(springType);
                        springUsage.insertRow(ctx, new SpringUsageTable.Row(
                                sourcePath,
                                classification.feature,
                                springType,
                                classification.supportLevel,
                                classification.suggestedReplacement));
                    }
                }
                return super.visitCompilationUnit(compilationUnit, ctx);
            }

            @Override
            public J.Import visitImport(J.Import springImport, ExecutionContext ctx) {
                J.Import visited = super.visitImport(springImport, ctx);
                String springType = springImport.getTypeName();
                if (!isSpringType(springType)) {
                    return visited;
                }
                return markIfAbsent(visited, classify(springType));
            }

            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                J.Annotation visited = super.visitAnnotation(annotation, ctx);
                Set<String> springTypes = springAnnotationTypes(visited.getType());
                if (springTypes.isEmpty()) {
                    return visited;
                }
                String directType = fullyQualifiedName(visited.getType());
                ImportIndex imports = getCursor().getNearestMessage(CURRENT_IMPORTS);
                if (isSpringType(directType) && imports != null && imports.supports(directType)) {
                    // The annotation identifier is marked at the narrower source location.
                    return visited;
                }
                return markIfAbsent(visited, classify(springTypes.iterator().next()));
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration,
                                                             ExecutionContext ctx) {
                J.ClassDeclaration visited = super.visitClassDeclaration(classDeclaration, ctx);
                Set<String> hierarchyTypes = explicitHierarchySpringTypes(visited);
                if (hierarchyTypes.isEmpty() || hasDirectSpringHierarchy(visited)) {
                    return visited;
                }
                return markIfAbsent(visited, classify(hierarchyTypes.iterator().next()));
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
                J.Identifier visited = super.visitIdentifier(identifier, ctx);
                if (getCursor().firstEnclosing(J.Import.class) != null ||
                    isSpringFieldAccessName(getCursor(), identifier)) {
                    return visited;
                }
                String springType = fullyQualifiedName(identifier.getType());
                ImportIndex imports = getCursor().getNearestMessage(CURRENT_IMPORTS);
                if (!isSpringType(springType) || imports == null ||
                        !hasCurrentSyntaxEvidence(getCursor(), springType, imports)) {
                    return visited;
                }
                return markIfAbsent(visited, classify(springType));
            }

            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
                J.FieldAccess visited = super.visitFieldAccess(fieldAccess, ctx);
                if (getCursor().firstEnclosing(J.Import.class) != null ||
                    isNestedSpringFieldAccess(getCursor(), fieldAccess)) {
                    return visited;
                }
                String springType = fullyQualifiedName(fieldAccess.getType());
                ImportIndex imports = getCursor().getNearestMessage(CURRENT_IMPORTS);
                if (!isSpringType(springType) || imports == null ||
                        !hasCurrentFieldAccessEvidence(visited, springType, imports)) {
                    return visited;
                }
                return markIfAbsent(visited, classify(springType));
            }
        };
    }

    private static Set<String> collectSpringTypes(J.CompilationUnit compilationUnit,
                                                  final ImportIndex imports) {
        final Set<String> springTypes = new TreeSet<String>();
        new JavaIsoVisitor<Set<String>>() {
            @Override
            public J.Import visitImport(J.Import springImport, Set<String> accumulator) {
                String springType = springImport.getTypeName();
                if (isSpringType(springType)) {
                    accumulator.add(springType);
                }
                return super.visitImport(springImport, accumulator);
            }

            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, Set<String> accumulator) {
                accumulator.addAll(springAnnotationTypes(annotation.getType()));
                return super.visitAnnotation(annotation, accumulator);
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration,
                                                             Set<String> accumulator) {
                accumulator.addAll(explicitHierarchySpringTypes(classDeclaration));
                return super.visitClassDeclaration(classDeclaration, accumulator);
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Set<String> accumulator) {
                String springType = fullyQualifiedName(identifier.getType());
                if (isSpringType(springType) &&
                        getCursor().firstEnclosing(J.Import.class) == null &&
                        !isSpringFieldAccessName(getCursor(), identifier) &&
                        hasCurrentSyntaxEvidence(getCursor(), springType, imports)) {
                    accumulator.add(springType);
                }
                return super.visitIdentifier(identifier, accumulator);
            }

            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, Set<String> accumulator) {
                String springType = fullyQualifiedName(fieldAccess.getType());
                if (isSpringType(springType) &&
                        getCursor().firstEnclosing(J.Import.class) == null &&
                        !isNestedSpringFieldAccess(getCursor(), fieldAccess) &&
                        hasCurrentFieldAccessEvidence(fieldAccess, springType, imports)) {
                    accumulator.add(springType);
                }
                return super.visitFieldAccess(fieldAccess, accumulator);
            }
        }.visit(compilationUnit, springTypes);
        return springTypes;
    }

    private static boolean hasCurrentSyntaxEvidence(Cursor cursor, String springType,
                                                    ImportIndex imports) {
        if (imports.supports(springType) || imports.springPackage) {
            return true;
        }
        J.FieldAccess fieldAccess = cursor.firstEnclosing(J.FieldAccess.class);
        if (fieldAccess != null && fieldAccess.printTrimmed().contains(springType)) {
            return true;
        }
        J.VariableDeclarations variables = cursor.firstEnclosing(J.VariableDeclarations.class);
        return variables != null && variables.getTypeExpression() != null &&
               variables.getTypeExpression().printTrimmed().contains(springType);
    }

    private static boolean hasCurrentFieldAccessEvidence(J.FieldAccess fieldAccess, String springType,
                                                         ImportIndex imports) {
        return imports.supports(springType) || imports.springPackage ||
               fieldAccess.printTrimmed().contains(springType);
    }

    private static boolean isSpringFieldAccessName(Cursor cursor, J.Identifier identifier) {
        Object parent = cursor.getParentTreeCursor().getValue();
        if (!(parent instanceof J.FieldAccess)) {
            return false;
        }
        J.FieldAccess fieldAccess = (J.FieldAccess) parent;
        return fieldAccess.getName() == identifier &&
               isSpringType(fullyQualifiedName(fieldAccess.getType()));
    }

    private static boolean isNestedSpringFieldAccess(Cursor cursor, J.FieldAccess fieldAccess) {
        Object parent = cursor.getParentTreeCursor().getValue();
        if (!(parent instanceof J.FieldAccess)) {
            return false;
        }
        J.FieldAccess parentFieldAccess = (J.FieldAccess) parent;
        return parentFieldAccess.getTarget() == fieldAccess &&
               isSpringType(fullyQualifiedName(parentFieldAccess.getType()));
    }

    private static Set<String> springAnnotationTypes(JavaType type) {
        Set<String> result = new TreeSet<String>();
        collectSpringAnnotationTypes(TypeUtils.asFullyQualified(type), result,
                new HashSet<String>(), 0);
        return result;
    }

    private static void collectSpringAnnotationTypes(JavaType.FullyQualified annotationType,
                                                     Set<String> result, Set<String> visited,
                                                     int depth) {
        if (annotationType == null || depth > MAX_TYPE_GRAPH_DEPTH) {
            return;
        }
        String name = annotationType.getFullyQualifiedName();
        if (isSpringType(name)) {
            result.add(name);
            return;
        }
        if (!visited.add(name)) {
            return;
        }
        for (JavaType.FullyQualified metaAnnotation : annotationType.getAnnotations()) {
            collectSpringAnnotationTypes(metaAnnotation, result, visited, depth + 1);
        }
    }

    private static Set<String> explicitHierarchySpringTypes(J.ClassDeclaration classDeclaration) {
        Set<String> result = new TreeSet<String>();
        if (classDeclaration.getExtends() != null) {
            collectSpringHierarchyTypes(classDeclaration.getExtends().getType(), result,
                    new HashSet<String>(), 0);
        }
        if (classDeclaration.getImplements() != null) {
            for (org.openrewrite.java.tree.TypeTree implemented : classDeclaration.getImplements()) {
                collectSpringHierarchyTypes(implemented.getType(), result,
                        new HashSet<String>(), 0);
            }
        }
        return result;
    }

    private static void collectSpringHierarchyTypes(JavaType type, Set<String> result,
                                                    Set<String> visited, int depth) {
        if (depth > MAX_TYPE_GRAPH_DEPTH) {
            return;
        }
        JavaType.FullyQualified fullyQualified = TypeUtils.asFullyQualified(type);
        if (fullyQualified == null) {
            return;
        }
        String name = fullyQualified.getFullyQualifiedName();
        if (isSpringType(name)) {
            result.add(name);
            return;
        }
        if (!visited.add(name)) {
            return;
        }
        collectSpringHierarchyTypes(fullyQualified.getSupertype(), result, visited, depth + 1);
        for (JavaType.FullyQualified implemented : fullyQualified.getInterfaces()) {
            collectSpringHierarchyTypes(implemented, result, visited, depth + 1);
        }
    }

    private static boolean hasDirectSpringHierarchy(J.ClassDeclaration classDeclaration) {
        if (classDeclaration.getExtends() != null &&
                isSpringType(fullyQualifiedName(classDeclaration.getExtends().getType()))) {
            return true;
        }
        if (classDeclaration.getImplements() != null) {
            for (org.openrewrite.java.tree.TypeTree implemented : classDeclaration.getImplements()) {
                if (isSpringType(fullyQualifiedName(implemented.getType()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class ImportIndex {
        private final Set<String> exactImports = new HashSet<String>();
        private final Set<String> wildcardPackages = new HashSet<String>();
        private final boolean springPackage;

        private ImportIndex(boolean springPackage) {
            this.springPackage = springPackage;
        }

        private static ImportIndex from(J.CompilationUnit compilationUnit) {
            String packageName = compilationUnit.getPackageDeclaration() == null ? "" :
                    compilationUnit.getPackageDeclaration().getExpression().printTrimmed();
            ImportIndex index = new ImportIndex(packageName.startsWith("org.springframework"));
            for (J.Import anImport : compilationUnit.getImports()) {
                String typeName = anImport.getTypeName();
                if (typeName.endsWith(".*")) {
                    index.wildcardPackages.add(typeName.substring(0, typeName.length() - 1));
                } else {
                    index.exactImports.add(typeName);
                }
            }
            return index;
        }

        private boolean supports(String fullyQualifiedName) {
            if (exactImports.contains(fullyQualifiedName)) {
                return true;
            }
            for (String wildcardPackage : wildcardPackages) {
                if (fullyQualifiedName.startsWith(wildcardPackage) &&
                        fullyQualifiedName.indexOf('.', wildcardPackage.length()) < 0) {
                    return true;
                }
            }
            return false;
        }
    }

    private static String fullyQualifiedName(JavaType type) {
        JavaType.FullyQualified fullyQualified = TypeUtils.asFullyQualified(type);
        return fullyQualified == null ? null : fullyQualified.getFullyQualifiedName();
    }

    private static boolean isSpringType(String typeName) {
        return typeName != null && typeName.startsWith(SPRING_PREFIX);
    }

    private static String markerDescription(Classification classification) {
        return classification.supportLevel + ": " + classification.feature + " -> " +
               classification.suggestedReplacement;
    }

    private static <T extends J> T markIfAbsent(T tree, Classification classification) {
        String description = markerDescription(classification);
        for (SearchResult marker : tree.getMarkers().findAll(SearchResult.class)) {
            if (description.equals(marker.getDescription())) {
                return tree;
            }
        }
        return SearchResult.found(tree, description);
    }

    private static Classification classify(String typeName) {
        // The initial migration recipes automate only these exact source-level mappings.
        if (isOneOf(typeName,
                "org.springframework.stereotype.Component",
                "org.springframework.stereotype.Service",
                "org.springframework.context.annotation.Configuration",
                "org.springframework.context.annotation.Bean",
                "org.springframework.beans.factory.annotation.Autowired")) {
            return automatic("Dependency injection", "Jakarta CDI and jakarta.inject");
        }
        if ("org.springframework.stereotype.Repository".equals(typeName)) {
            return manual("Spring repository stereotype",
                    "Explicit CDI bean with reviewed persistence exception mapping");
        }
        if ("org.springframework.beans.factory.annotation.Value".equals(typeName)) {
            return automatic("Externalized configuration", "MicroProfile Config @ConfigProperty");
        }
        if ("org.springframework.transaction.annotation.Transactional".equals(typeName)) {
            return automatic("Transactions", "jakarta.transaction.Transactional");
        }
        if (isOneOf(typeName,
                "org.springframework.web.bind.annotation.RestController",
                "org.springframework.web.bind.annotation.RequestMapping",
                "org.springframework.web.bind.annotation.GetMapping",
                "org.springframework.web.bind.annotation.PostMapping",
                "org.springframework.web.bind.annotation.PutMapping",
                "org.springframework.web.bind.annotation.DeleteMapping",
                "org.springframework.web.bind.annotation.PatchMapping",
                "org.springframework.web.bind.annotation.RequestParam",
                "org.springframework.web.bind.annotation.PathVariable",
                "org.springframework.web.bind.annotation.RequestBody",
                "org.springframework.web.bind.annotation.RequestHeader")) {
            return automatic("Spring MVC", "Jakarta REST annotations");
        }
        if ("org.springframework.stereotype.Controller".equals(typeName)) {
            return manual("Spring MVC view controller", "Redesign as Jakarta REST or retain a dedicated view layer");
        }

        if (typeName.startsWith("org.springframework.data.")) {
            return partial("Spring Data", "Jakarta Persistence repository or DAO");
        }
        if (typeName.startsWith("org.springframework.boot.actuate.")) {
            return partial("Spring Boot Actuator", "MicroProfile Health and Metrics");
        }
        if (typeName.startsWith("org.springframework.scheduling.") ||
            isOneOf(typeName, "org.springframework.context.annotation.EnableScheduling")) {
            return partial("Scheduling", "Helidon scheduling or Jakarta Concurrency");
        }
        if (typeName.startsWith("org.springframework.cache.") ||
            isOneOf(typeName, "org.springframework.cache.annotation.EnableCaching")) {
            return partial("Caching", "Application-specific cache with CDI integration");
        }
        if ("org.springframework.http.ResponseEntity".equals(typeName)) {
            return partial("Spring MVC response type", "jakarta.ws.rs.core.Response");
        }
        if (isOneOf(typeName,
                "org.springframework.web.context.request.async.DeferredResult",
                "org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody")) {
            return manual("Spring MVC asynchronous or streaming API", "Redesign for Jakarta REST async or streaming APIs");
        }
        if (typeName.startsWith("org.springframework.web.reactive.") ||
            typeName.startsWith("org.springframework.web.server.")) {
            return manual("Spring WebFlux", "Redesign for Jakarta REST or Helidon SE");
        }
        if (typeName.startsWith("org.springframework.security.")) {
            return manual("Spring Security", "Helidon Security or Jakarta Security");
        }
        if (typeName.startsWith("org.springframework.cloud.")) {
            return manual("Spring Cloud", "Component-specific MicroProfile or Helidon replacement");
        }
        if (typeName.startsWith("org.springframework.batch.")) {
            return manual("Spring Batch", "Jakarta Batch or an application-specific batch runtime");
        }
        if (typeName.startsWith("org.springframework.integration.")) {
            return manual("Spring Integration", "Redesign integration flows for Helidon-compatible messaging");
        }
        if (typeName.startsWith("org.springframework.kafka.")) {
            return manual("Spring Kafka", "Helidon-compatible Kafka client or messaging integration");
        }
        if (typeName.startsWith("org.springframework.aop.") ||
            typeName.startsWith("org.springframework.aspectj.") ||
            "org.springframework.context.annotation.EnableAspectJAutoProxy".equals(typeName)) {
            return manual("Spring AOP", "CDI interceptors or decorators");
        }
        if (isApplicationContext(typeName)) {
            return manual("Spring ApplicationContext", "CDI Instance, BeanManager, or injection");
        }
        if (typeName.startsWith("org.springframework.boot.autoconfigure.") ||
            typeName.endsWith("AutoConfiguration")) {
            return manual("Spring Boot auto-configuration", "Explicit CDI producers or a CDI portable extension");
        }
        if (typeName.startsWith("org.springframework.web.")) {
            return partial("Spring MVC", "Jakarta REST or a Helidon web API");
        }
        if (typeName.startsWith("org.springframework.transaction.")) {
            return partial("Transactions", "Jakarta Transactions");
        }
        if (typeName.startsWith("org.springframework.beans.") ||
            typeName.startsWith("org.springframework.context.") ||
            typeName.startsWith("org.springframework.stereotype.")) {
            return partial("Dependency injection", "Jakarta CDI and jakarta.inject");
        }
        return manual("Other Spring API", "Inspect for a Jakarta, MicroProfile, or Helidon equivalent");
    }

    private static boolean isApplicationContext(String typeName) {
        return "org.springframework.context.ApplicationContext".equals(typeName) ||
               "org.springframework.context.ApplicationContextAware".equals(typeName) ||
               "org.springframework.context.ConfigurableApplicationContext".equals(typeName) ||
               "org.springframework.context.support.AbstractApplicationContext".equals(typeName);
    }

    private static boolean isOneOf(String candidate, String... values) {
        for (String value : values) {
            if (value.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static Classification automatic(String feature, String replacement) {
        return new Classification(feature, "AUTOMATIC", replacement);
    }

    private static Classification partial(String feature, String replacement) {
        return new Classification(feature, "PARTIAL", replacement);
    }

    private static Classification manual(String feature, String replacement) {
        return new Classification(feature, "MANUAL", replacement);
    }

    private static final class Classification {
        private final String feature;
        private final String supportLevel;
        private final String suggestedReplacement;

        private Classification(String feature, String supportLevel, String suggestedReplacement) {
            this.feature = feature;
            this.supportLevel = supportLevel;
            this.suggestedReplacement = suggestedReplacement;
        }
    }
}
