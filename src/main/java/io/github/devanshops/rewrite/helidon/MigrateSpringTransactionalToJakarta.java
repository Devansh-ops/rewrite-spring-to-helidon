package io.github.devanshops.rewrite.helidon;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.ShortenFullyQualifiedTypeReferences;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Migrates the semantics-preserving subset of Spring transaction annotations. */
public class MigrateSpringTransactionalToJakarta extends
        ScanningRecipe<MigrateSpringTransactionalToJakarta.Accumulator> {
    private static final AnnotationMatcher TRANSACTIONAL =
            new AnnotationMatcher("@org.springframework.transaction.annotation.Transactional");
    private static final String[] CDI_BEAN_DEFINING_ANNOTATIONS = {
            "jakarta.enterprise.context.ApplicationScoped",
            "jakarta.enterprise.context.RequestScoped",
            "jakarta.enterprise.context.SessionScoped",
            "jakarta.enterprise.context.ConversationScoped",
            "jakarta.enterprise.context.Dependent"
    };
    private static final String NORMAL_SCOPE = "jakarta.enterprise.context.NormalScope";
    private static final String STEREOTYPE = "jakarta.enterprise.inject.Stereotype";
    private static final String PROJECT_SPRING_SECURITY =
            "Manual migration: transaction conversion was deferred because Spring Security is present " +
            "in this migration scope";
    private static final String PROJECT_SPRING_WEB_INFRASTRUCTURE =
            "Manual migration: transaction conversion was deferred because Spring Web or servlet runtime " +
            "infrastructure is present in this migration scope";

    @Override
    public String getDisplayName() {
        return "Migrate Spring transactions to Jakarta Transactions";
    }

    @Override
    public String getDescription() {
        return "Migrates default `@Transactional` and maps `rollbackFor`/`noRollbackFor` to Jakarta " +
               "`rollbackOn`/`dontRollbackOn` on proven CDI beans; marks unsafe targets, propagation, " +
               "isolation, timeout, read-only, and manager settings.";
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(5);
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(final Accumulator accumulator) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree preVisit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sourceFile = (SourceFile) tree;
                    SpringSecurityProjectGate.scanSource(sourceFile, accumulator.springSecurity);
                    SpringWebProjectGate.scanSource(sourceFile, accumulator.springWeb);
                }
                return tree;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(final Accumulator accumulator) {
        return Preconditions.check(
                new UsesType<>("org.springframework.transaction.annotation.Transactional", false),
                new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                J.Annotation a = super.visitAnnotation(annotation, ctx);
                if (!TRANSACTIONAL.matches(a)) {
                    return a;
                }

                J.ClassDeclaration beanClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
                J.CompilationUnit compilationUnit = getCursor().firstEnclosing(J.CompilationUnit.class);
                if (beanClass != null) {
                    MigrateSpringMvcToJakartaRest.ControllerPreflight controllerPreflight =
                            MigrateSpringMvcToJakartaRest.classLocalPreflight(
                                    beanClass, isTopLevelClass(beanClass, compilationUnit));
                    if (controllerPreflight.controller && !controllerPreflight.supported) {
                        return SearchResult.found(a, controllerPreflight.message);
                    }
                    if (controllerPreflight.controller && compilationUnit != null &&
                            SpringSecurityProjectGate.isBlocked(
                                    accumulator.springSecurity, compilationUnit.getSourcePath())) {
                        return SearchResult.found(a, PROJECT_SPRING_SECURITY);
                    }
                    if (controllerPreflight.controller && compilationUnit != null &&
                            SpringWebProjectGate.isBlocked(
                                    accumulator.springWeb, compilationUnit.getSourcePath())) {
                        return SearchResult.found(a, PROJECT_SPRING_WEB_INFRASTRUCTURE);
                    }
                }
                if (beanClass == null || !hasBeanDefiningAnnotation(beanClass)) {
                    return SearchResult.found(a,
                            "Manual migration: Jakarta @Transactional requires an enclosing " +
                            "CDI bean-defining annotation");
                }
                Object annotationTarget = getCursor().getParentOrThrow().getValue();
                J.MethodDeclaration method = annotationTarget instanceof J.MethodDeclaration ?
                        (J.MethodDeclaration) annotationTarget : null;
                if (!isInterceptable(beanClass, method)) {
                    return SearchResult.found(a,
                            "Manual migration: Jakarta @Transactional requires an interceptable CDI class and method");
                }

                List<Attribute> attributes = supportedAttributes(a);
                if (attributes == null) {
                    return SearchResult.found(a,
                            "Manual migration: Spring propagation, isolation, timeout, readOnly, labels, " +
                            "and transaction manager selection require semantic review");
                }

                // Keep the target import request first for this same-simple-name replacement. Removing
                // the Spring import first makes JavaTemplate render a newly imported simple name rather
                // than the recipe's established fully qualified target annotation.
                maybeAddImport("jakarta.transaction.Transactional", false);
                maybeRemoveImport("org.springframework.transaction.annotation.Transactional");
                doAfterVisit(new ShortenFullyQualifiedTypeReferences().getVisitor());
                if (attributes.isEmpty()) {
                    return HelidonJavaTemplate.builder("@Transactional")
                            .imports("jakarta.transaction.Transactional")
                            .build()
                            .apply(getCursor(), a.getCoordinates().replace());
                }
                if (attributes.size() == 1) {
                    Attribute attribute = attributes.get(0);
                    return HelidonJavaTemplate.builder(
                                    "@Transactional(" + attribute.jakartaName + " = #{any()})")
                            .imports("jakarta.transaction.Transactional")
                            .build()
                            .apply(getCursor(), a.getCoordinates().replace(), attribute.value);
                }
                return HelidonJavaTemplate.builder(
                                "@Transactional(rollbackOn = #{any()}, dontRollbackOn = #{any()})")
                        .imports("jakarta.transaction.Transactional")
                        .build()
                        .apply(getCursor(), a.getCoordinates().replace(), attributes.get(0).value,
                                attributes.get(1).value);
            }
        });
    }

    private static boolean isTopLevelClass(J.ClassDeclaration classDeclaration,
                                           J.CompilationUnit compilationUnit) {
        if (compilationUnit == null) {
            return false;
        }
        for (J.ClassDeclaration topLevel : compilationUnit.getClasses()) {
            if (topLevel.getId().equals(classDeclaration.getId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBeanDefiningAnnotation(J.ClassDeclaration classDeclaration) {
        for (J.Annotation annotation : classDeclaration.getLeadingAnnotations()) {
            for (String fullyQualifiedName : CDI_BEAN_DEFINING_ANNOTATIONS) {
                if (TypeUtils.isOfClassType(annotation.getType(), fullyQualifiedName)) {
                    return true;
                }
            }
            JavaType.FullyQualified annotationType = TypeUtils.asFullyQualified(annotation.getType());
            if (hasMetaAnnotation(annotationType, NORMAL_SCOPE, new HashSet<String>()) ||
                    hasMetaAnnotation(annotationType, STEREOTYPE, new HashSet<String>())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMetaAnnotation(JavaType.FullyQualified annotationType, String expectedType,
                                             Set<String> visited) {
        if (annotationType == null || !visited.add(annotationType.getFullyQualifiedName())) {
            return false;
        }
        for (JavaType.FullyQualified metaAnnotation : annotationType.getAnnotations()) {
            if (TypeUtils.isOfClassType(metaAnnotation, expectedType) ||
                    hasMetaAnnotation(metaAnnotation, expectedType, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInterceptable(J.ClassDeclaration beanClass, J.MethodDeclaration method) {
        if (beanClass.getKind() != J.ClassDeclaration.Kind.Type.Class ||
                beanClass.hasModifier(J.Modifier.Type.Abstract) ||
                beanClass.hasModifier(J.Modifier.Type.Final) ||
                beanClass.hasModifier(J.Modifier.Type.Private) ||
                beanClass.hasModifier(J.Modifier.Type.Sealed) ||
                hasNonTrivialSuperclass(beanClass) ||
                !hasNonPrivateNoArgConstructor(beanClass) ||
                hasAccessibleFinalMethod(beanClass)) {
            return false;
        }
        if (method != null) {
            return !method.hasModifier(J.Modifier.Type.Final) &&
                   !method.hasModifier(J.Modifier.Type.Private) &&
                   !method.hasModifier(J.Modifier.Type.Static);
        }
        return true;
    }

    private static boolean hasNonTrivialSuperclass(J.ClassDeclaration beanClass) {
        return beanClass.getExtends() != null &&
               !TypeUtils.isOfClassType(beanClass.getExtends().getType(), "java.lang.Object");
    }

    private static boolean hasNonPrivateNoArgConstructor(J.ClassDeclaration beanClass) {
        boolean declaresConstructor = false;
        for (org.openrewrite.java.tree.Statement statement : beanClass.getBody().getStatements()) {
            if (statement instanceof J.MethodDeclaration) {
                J.MethodDeclaration declaredMethod = (J.MethodDeclaration) statement;
                if (declaredMethod.isConstructor()) {
                    declaresConstructor = true;
                    if (!declaredMethod.hasModifier(J.Modifier.Type.Private) &&
                            hasNoParameters(declaredMethod)) {
                        return true;
                    }
                }
            }
        }
        return !declaresConstructor;
    }

    private static boolean hasAccessibleFinalMethod(J.ClassDeclaration beanClass) {
        for (org.openrewrite.java.tree.Statement statement : beanClass.getBody().getStatements()) {
            if (statement instanceof J.MethodDeclaration) {
                J.MethodDeclaration method = (J.MethodDeclaration) statement;
                if (method.hasModifier(J.Modifier.Type.Final) &&
                        !method.hasModifier(J.Modifier.Type.Private) &&
                        !method.hasModifier(J.Modifier.Type.Static)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasNoParameters(J.MethodDeclaration method) {
        for (org.openrewrite.java.tree.Statement parameter : method.getParameters()) {
            if (!(parameter instanceof J.Empty)) {
                return false;
            }
        }
        return true;
    }

    private static List<Attribute> supportedAttributes(J.Annotation annotation) {
        List<Expression> arguments = annotation.getArguments();
        List<Attribute> result = new ArrayList<Attribute>();
        if (arguments == null || arguments.isEmpty()) {
            return result;
        }
        for (Expression argument : arguments) {
            if (!(argument instanceof J.Assignment)) {
                return null;
            }
            J.Assignment assignment = (J.Assignment) argument;
            String name = assignment.getVariable().printTrimmed();
            if ("rollbackFor".equals(name)) {
                result.add(0, new Attribute("rollbackOn", assignment.getAssignment()));
            } else if ("noRollbackFor".equals(name)) {
                result.add(new Attribute("dontRollbackOn", assignment.getAssignment()));
            } else {
                return null;
            }
        }
        return result;
    }

    private static final class Attribute {
        private final String jakartaName;
        private final Expression value;

        private Attribute(String jakartaName, Expression value) {
            this.jakartaName = jakartaName;
            this.value = value;
        }
    }

    static final class Accumulator {
        private final SpringSecurityProjectGate.State springSecurity =
                SpringSecurityProjectGate.newAccumulator();
        private final SpringWebProjectGate.State springWeb =
                SpringWebProjectGate.newAccumulator();
    }
}
