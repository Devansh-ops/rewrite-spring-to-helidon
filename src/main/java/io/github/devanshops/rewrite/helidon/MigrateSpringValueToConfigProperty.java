package io.github.devanshops.rewrite.helidon;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the portable subset of Spring {@code @Value} placeholders to
 * MicroProfile Config {@code @ConfigProperty} annotations and establishes the
 * corresponding CDI injection point where one is required.
 */
public class MigrateSpringValueToConfigProperty extends
        ScanningRecipe<MigrateSpringValueToConfigProperty.Accumulator> {
    private static final AnnotationMatcher VALUE =
            new AnnotationMatcher("@org.springframework.beans.factory.annotation.Value");
    private static final AnnotationMatcher INJECT = new AnnotationMatcher("@jakarta.inject.Inject");
    private static final AnnotationMatcher PRODUCES =
            new AnnotationMatcher("@jakarta.enterprise.inject.Produces");
    private static final String[] CDI_BEAN_DEFINING_ANNOTATIONS = {
            "jakarta.enterprise.context.ApplicationScoped",
            "jakarta.enterprise.context.RequestScoped",
            "jakarta.enterprise.context.SessionScoped",
            "jakarta.enterprise.context.ConversationScoped",
            "jakarta.enterprise.context.Dependent"
    };
    private static final String[] NORMAL_SCOPED_ANNOTATIONS = {
            "jakarta.enterprise.context.ApplicationScoped",
            "jakarta.enterprise.context.RequestScoped",
            "jakarta.enterprise.context.SessionScoped",
            "jakarta.enterprise.context.ConversationScoped"
    };
    private static final String NORMAL_SCOPE = "jakarta.enterprise.context.NormalScope";
    private static final String STEREOTYPE = "jakarta.enterprise.inject.Stereotype";
    private static final String PROJECT_SPRING_SECURITY =
            "Manual migration: configuration conversion was deferred because Spring Security is present " +
            "in this migration scope";
    private static final String PROJECT_SPRING_WEB_INFRASTRUCTURE =
            "Manual migration: configuration conversion was deferred because Spring Web or servlet runtime " +
            "infrastructure is present in this migration scope";
    private static final String[] EQUIVALENT_SCALAR_TYPES = {
            "java.lang.String",
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double"
    };
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^:{}]+)(?::([^{}]*))?}$");
    private static final String ADD_INJECT = MigrateSpringValueToConfigProperty.class.getName() + ".addInject";
    private static final Comparator<J.Annotation> INJECT_FIRST = new Comparator<J.Annotation>() {
        @Override
        public int compare(J.Annotation left, J.Annotation right) {
            if ("Inject".equals(left.getSimpleName())) {
                return "Inject".equals(right.getSimpleName()) ? 0 : -1;
            }
            if ("Inject".equals(right.getSimpleName())) {
                return 1;
            }
            return left.getSimpleName().compareTo(right.getSimpleName());
        }
    };

    @Override
    public String getDisplayName() {
        return "Migrate Spring `@Value` to MicroProfile Config";
    }

    @Override
    public String getDescription() {
        return "Converts simple `${name}` and `${name:default}` placeholders on proven-equivalent " +
               "scalar target types to `@ConfigProperty`, adds CDI injection where required, and " +
               "marks unsupported injection points, conversions, or Spring-only expressions for manual migration.";
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(3);
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
                new UsesType<>("org.springframework.beans.factory.annotation.Value", false),
                new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable,
                                                                      ExecutionContext ctx) {
                J.VariableDeclarations variables = super.visitVariableDeclarations(multiVariable, ctx);
                if (!Boolean.TRUE.equals(getCursor().pollMessage(ADD_INJECT)) || hasInject(variables)) {
                    return variables;
                }
                maybeAddImport("jakarta.inject.Inject", false);
                return HelidonJavaTemplate.builder("@Inject")
                        .imports("jakarta.inject.Inject")
                        .build()
                        .apply(updateCursor(variables),
                                variables.getCoordinates().addAnnotation(INJECT_FIRST));
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method,
                                                               ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
                if (!Boolean.TRUE.equals(getCursor().pollMessage(ADD_INJECT)) || hasInject(m)) {
                    return m;
                }
                maybeAddImport("jakarta.inject.Inject", false);
                return HelidonJavaTemplate.builder("@Inject")
                        .imports("jakarta.inject.Inject")
                        .build()
                        .apply(updateCursor(m), m.getCoordinates().addAnnotation(INJECT_FIRST));
            }

            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                J.Annotation a = super.visitAnnotation(annotation, ctx);
                if (!VALUE.matches(a)) {
                    return a;
                }

                J.ClassDeclaration declaringClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
                J.CompilationUnit compilationUnit = getCursor().firstEnclosing(J.CompilationUnit.class);
                if (declaringClass != null) {
                    MigrateSpringMvcToJakartaRest.ControllerPreflight controllerPreflight =
                            MigrateSpringMvcToJakartaRest.classLocalPreflight(
                                    declaringClass, isTopLevelClass(declaringClass, compilationUnit));
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

                J.Literal value = annotationValue(a);
                if (value == null || !(value.getValue() instanceof String)) {
                    return SearchResult.found(a,
                            "Manual migration: @Value must contain one literal ${name[:default]} placeholder");
                }

                Matcher matcher = PLACEHOLDER.matcher((String) value.getValue());
                if (!matcher.matches()) {
                    return SearchResult.found(a,
                            "Manual migration: Spring SpEL and nested placeholders have no mechanical MP Config mapping");
                }
                if (matcher.group(2) != null && matcher.group(2).isEmpty()) {
                    return SearchResult.found(a,
                            "Manual migration: MicroProfile Config ignores empty annotation defaults");
                }

                J.VariableDeclarations variables = getCursor().firstEnclosing(J.VariableDeclarations.class);
                J.MethodDeclaration method = getCursor().firstEnclosing(J.MethodDeclaration.class);
                if (declaringClass == null || !hasBeanDefiningAnnotation(declaringClass)) {
                    return SearchResult.found(a,
                            "Manual migration: @ConfigProperty injection requires a proven CDI bean");
                }
                if (variables == null || !hasEquivalentScalarConversion(variables.getType())) {
                    return SearchResult.found(a,
                            "Manual migration: Spring and MicroProfile Config do not have " +
                            "proven-equivalent conversion semantics for this target type");
                }
                if (variables != null && variables.hasModifier(J.Modifier.Type.Static)) {
                    return SearchResult.found(a,
                            "Manual migration: CDI does not inject static @ConfigProperty fields");
                }
                if (method == null && variables != null && variables.hasModifier(J.Modifier.Type.Final)) {
                    return SearchResult.found(a,
                            "Manual migration: CDI cannot inject a final @ConfigProperty field");
                }
                if (method == null) {
                    getCursor().putMessageOnFirstEnclosing(J.VariableDeclarations.class, ADD_INJECT, true);
                } else if (method.hasModifier(J.Modifier.Type.Static)) {
                    return SearchResult.found(a,
                            "Manual migration: CDI does not inject static @ConfigProperty parameters");
                } else if (method.isConstructor()) {
                    if (requiresClientProxy(declaringClass) &&
                            !hasNonPrivateNoArgConstructor(declaringClass)) {
                        return SearchResult.found(a,
                                "Manual migration: a normal-scoped CDI bean needs a non-private " +
                                "no-arg constructor for client proxying");
                    }
                    if (method.hasModifier(J.Modifier.Type.Private) ||
                            hasAnotherInjectConstructor(declaringClass, method)) {
                        return SearchResult.found(a,
                                "Manual migration: constructor is not a unique eligible CDI injection point");
                    }
                    if (!hasInject(method)) {
                        getCursor().putMessageOnFirstEnclosing(J.MethodDeclaration.class, ADD_INJECT, true);
                    }
                } else if (!hasProducerAnnotation(method)) {
                    return SearchResult.found(a,
                            "Manual migration: @Value on a non-producer method parameter needs a CDI injection design");
                }

                J.Literal name = stringLiteral(value, matcher.group(1));
                J.Annotation migrated;
                if (matcher.group(2) == null) {
                    migrated = HelidonJavaTemplate.builder("@ConfigProperty(name = #{any(java.lang.String)})")
                            .imports("org.eclipse.microprofile.config.inject.ConfigProperty")
                            .build()
                            .apply(getCursor(), a.getCoordinates().replace(), name);
                } else {
                    J.Literal defaultValue = stringLiteral(value, matcher.group(2));
                    migrated = HelidonJavaTemplate.builder(
                                    "@ConfigProperty(name = #{any(java.lang.String)}, " +
                                    "defaultValue = #{any(java.lang.String)})")
                            .imports("org.eclipse.microprofile.config.inject.ConfigProperty")
                            .build()
                            .apply(getCursor(), a.getCoordinates().replace(), name, defaultValue);
                }
                maybeRemoveImport("org.springframework.beans.factory.annotation.Value");
                maybeAddImport("org.eclipse.microprofile.config.inject.ConfigProperty", false);
                return migrated;
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

    private static boolean hasInject(J.VariableDeclarations variables) {
        return hasAnnotation(variables.getLeadingAnnotations(), INJECT, "jakarta.inject.Inject");
    }

    private static boolean hasInject(J.MethodDeclaration method) {
        return hasAnnotation(method.getLeadingAnnotations(), INJECT, "jakarta.inject.Inject");
    }

    private static boolean hasProducerAnnotation(J.MethodDeclaration method) {
        for (J.Annotation annotation : method.getLeadingAnnotations()) {
            if (PRODUCES.matches(annotation)) {
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

    private static boolean requiresClientProxy(J.ClassDeclaration classDeclaration) {
        for (J.Annotation annotation : classDeclaration.getLeadingAnnotations()) {
            for (String fullyQualifiedName : NORMAL_SCOPED_ANNOTATIONS) {
                if (TypeUtils.isOfClassType(annotation.getType(), fullyQualifiedName)) {
                    return true;
                }
            }
            if (hasMetaAnnotation(TypeUtils.asFullyQualified(annotation.getType()), NORMAL_SCOPE,
                    new HashSet<String>())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNonPrivateNoArgConstructor(J.ClassDeclaration classDeclaration) {
        boolean declaresConstructor = false;
        for (org.openrewrite.java.tree.Statement statement : classDeclaration.getBody().getStatements()) {
            if (!(statement instanceof J.MethodDeclaration)) {
                continue;
            }
            J.MethodDeclaration method = (J.MethodDeclaration) statement;
            if (!method.isConstructor()) {
                continue;
            }
            declaresConstructor = true;
            if (!method.hasModifier(J.Modifier.Type.Private) && hasNoParameters(method)) {
                return true;
            }
        }
        return !declaresConstructor;
    }

    private static boolean hasNoParameters(J.MethodDeclaration method) {
        for (org.openrewrite.java.tree.Statement parameter : method.getParameters()) {
            if (!(parameter instanceof J.Empty)) {
                return false;
            }
        }
        return true;
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

    private static boolean hasEquivalentScalarConversion(JavaType type) {
        if (type instanceof JavaType.Primitive) {
            JavaType.Primitive primitive = (JavaType.Primitive) type;
            return primitive != JavaType.Primitive.Void &&
                   primitive != JavaType.Primitive.None &&
                   primitive != JavaType.Primitive.Null &&
                   primitive != JavaType.Primitive.Char;
        }
        for (String fullyQualifiedName : EQUIVALENT_SCALAR_TYPES) {
            if (TypeUtils.isOfClassType(type, fullyQualifiedName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnotherInjectConstructor(J.ClassDeclaration classDeclaration,
                                                       J.MethodDeclaration current) {
        for (org.openrewrite.java.tree.Statement statement : classDeclaration.getBody().getStatements()) {
            if (statement instanceof J.MethodDeclaration) {
                J.MethodDeclaration candidate = (J.MethodDeclaration) statement;
                if (candidate != current && candidate.isConstructor() && hasInject(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasAnnotation(List<J.Annotation> annotations, AnnotationMatcher matcher,
                                         String fullyQualifiedName) {
        for (J.Annotation annotation : annotations) {
            if (matcher.matches(annotation) || TypeUtils.isOfClassType(annotation.getType(), fullyQualifiedName)) {
                return true;
            }
        }
        return false;
    }

    private static J.Literal annotationValue(J.Annotation annotation) {
        List<Expression> arguments = annotation.getArguments();
        if (arguments == null || arguments.size() != 1) {
            return null;
        }
        Expression argument = arguments.get(0);
        if (argument instanceof J.Assignment) {
            J.Assignment assignment = (J.Assignment) argument;
            if (!"value".equals(assignment.getVariable().printTrimmed())) {
                return null;
            }
            argument = assignment.getAssignment();
        }
        return argument instanceof J.Literal ? (J.Literal) argument : null;
    }

    private static J.Literal stringLiteral(J.Literal seed, String value) {
        return seed.withValue(value).withValueSource('"' + escape(value) + '"');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static final class Accumulator {
        private final SpringSecurityProjectGate.State springSecurity =
                SpringSecurityProjectGate.newAccumulator();
        private final SpringWebProjectGate.State springWeb =
                SpringWebProjectGate.newAccumulator();
    }
}
