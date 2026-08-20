package io.github.devanshops.rewrite.helidon;

import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Preserves the existing application entry point while delegating startup to Helidon. */
public class MigrateSpringBootMain extends ScanningRecipe<MigrateSpringBootMain.MigrationScope> {
    private static final String BOOTSTRAP_ASSESSMENT =
            MigrateSpringBootMain.class.getName() + ".bootstrapAssessment";
    private static final String BOOTSTRAP_MIGRATED =
            MigrateSpringBootMain.class.getName() + ".bootstrapMigrated";
    private static final String SPRING_APPLICATION_BUILDER =
            "org.springframework.boot.builder.SpringApplicationBuilder";
    private static final String SPRING_BOOT_SERVLET_INITIALIZER =
            "org.springframework.boot.web.servlet.support.SpringBootServletInitializer";
    private static final String SPRING_APPLICATION =
            "org.springframework.boot.SpringApplication";
    private static final String SPRING_BOOT_APPLICATION_TYPE =
            "org.springframework.boot.autoconfigure.SpringBootApplication";
    private static final String SPRING_SECURITY_REVIEW =
            "Manual migration: Spring Security is present in this migration scope; preserve global filter and " +
            "authorization semantics before changing runtimes";
    private static final String SPRING_RUNTIME_REVIEW =
            "Manual migration: Spring runtime usage remains in this module; migrate production Spring behavior " +
            "before changing runtimes";
    private static final MethodMatcher SPRING_RUN =
            new MethodMatcher("org.springframework.boot.SpringApplication run(..)");
    private static final AnnotationMatcher SPRING_BOOT_APPLICATION =
            new AnnotationMatcher("@org.springframework.boot.autoconfigure.SpringBootApplication");
    private static final int MAX_META_ANNOTATION_DEPTH = 16;

    @Override
    public String getDisplayName() {
        return "Migrate the Spring Boot application entry point";
    }

    @Override
    public String getDescription() {
        return "Replaces a plain, single-statement `SpringApplication.run` launcher with " +
               "`io.helidon.Main.main`, and removes only an option-free `@SpringBootApplication` " +
               "whose class has no additional lifecycle, member, hierarchy, or Spring annotation semantics.";
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(5);
    }

    @Override
    public MigrationScope getInitialValue(ExecutionContext ctx) {
        return new MigrationScope();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(final MigrationScope accumulator) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree preVisit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sourceFile = (SourceFile) tree;
                    SpringSecurityProjectGate.scanSource(sourceFile, accumulator.security);
                    scanSpringRuntimeResidue(sourceFile, accumulator.runtimeResidue);
                }
                return tree;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(final MigrationScope accumulator) {
        // A UsesType precondition is deliberately avoided here. After the launcher is migrated,
        // OpenRewrite can retain the original class type attribution for the rest of the run;
        // reapplying UsesType would rebuild an otherwise unchanged compilation unit every cycle.
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit compilationUnit,
                                                           ExecutionContext ctx) {
                J.CompilationUnit visited = super.visitCompilationUnit(compilationUnit, ctx);
                if (!Boolean.TRUE.equals(getCursor().pollMessage(BOOTSTRAP_MIGRATED))) {
                    return visited;
                }
                J.CompilationUnit withoutBootstrapImports = visited.withImports(
                        ListUtils.map(visited.getImports(), anImport ->
                                SPRING_APPLICATION.equals(anImport.getTypeName()) ||
                                SPRING_BOOT_APPLICATION_TYPE.equals(anImport.getTypeName()) ?
                                        null : anImport));
                return maybeAutoFormat(compilationUnit, withoutBootstrapImports, ctx);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
                                                             ExecutionContext ctx) {
                J.MethodInvocation invocation = super.visitMethodInvocation(method, ctx);
                if (!SPRING_RUN.matches(invocation)) {
                    return invocation;
                }
                BootstrapAssessment assessment = getCursor().getNearestMessage(BOOTSTRAP_ASSESSMENT);
                if (assessment == null) {
                    return invocation;
                }
                if (!assessment.safe) {
                    return SearchResult.found(invocation, assessment.message);
                }
                List<Expression> arguments = invocation.getArguments();
                Expression args = arguments.get(arguments.size() - 1);
                getCursor().putMessageOnFirstEnclosing(J.CompilationUnit.class,
                        BOOTSTRAP_MIGRATED, true);
                return HelidonJavaTemplate.builder("io.helidon.Main.main(#{any()})")
                        .build()
                        .apply(getCursor(), invocation.getCoordinates().replace(), args);
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl,
                                                             ExecutionContext ctx) {
                J.Annotation originalBoot = findSpringBootApplication(classDecl);
                if (originalBoot == null) {
                    return super.visitClassDeclaration(classDecl, ctx);
                }
                J.CompilationUnit compilationUnit = getCursor().firstEnclosing(J.CompilationUnit.class);
                Path sourcePath = compilationUnit == null ? null : compilationUnit.getSourcePath();
                BootstrapAssessment assessment = assess(classDecl, originalBoot);
                if (assessment.safe && sourcePath != null) {
                    if (SpringSecurityProjectGate.isBlocked(accumulator.security, sourcePath)) {
                        assessment = BootstrapAssessment.unsafe(SPRING_SECURITY_REVIEW);
                    } else if (accumulator.runtimeResidue.isBlocked(sourcePath)) {
                        assessment = BootstrapAssessment.unsafe(SPRING_RUNTIME_REVIEW);
                    }
                }
                getCursor().putMessage(BOOTSTRAP_ASSESSMENT, assessment);
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
                J.Annotation boot = findSpringBootApplication(cd);
                if (boot == null) {
                    return cd;
                }
                if (!assessment.safe) {
                    final J.Annotation unsupported = boot;
                    final String assessmentMessage = assessment.message;
                    return cd.withLeadingAnnotations(ListUtils.map(cd.getLeadingAnnotations(), annotation ->
                            annotation == unsupported ? SearchResult.found(annotation,
                                    assessmentMessage) :
                                    annotation));
                }
                final J.Annotation remove = boot;
                getCursor().putMessageOnFirstEnclosing(J.CompilationUnit.class,
                        BOOTSTRAP_MIGRATED, true);
                J.ClassDeclaration migrated = cd.withLeadingAnnotations(
                        ListUtils.map(cd.getLeadingAnnotations(), annotation ->
                                annotation == remove ? null : annotation));
                return maybeAutoFormat(classDecl, migrated, ctx);
            }
        };
    }

    private static J.Annotation findSpringBootApplication(J.ClassDeclaration classDecl) {
        for (J.Annotation annotation : classDecl.getLeadingAnnotations()) {
            if (SPRING_BOOT_APPLICATION.matches(annotation)) {
                return annotation;
            }
        }
        return null;
    }

    private static BootstrapAssessment assess(J.ClassDeclaration classDecl, J.Annotation boot) {
        if (boot == null) {
            return BootstrapAssessment.unsafe(
                    "Manual migration: bootstrap is not inside an option-free Spring Boot application");
        }
        if (!isEmptyArguments(boot.getArguments())) {
            return BootstrapAssessment.unsafe(
                    "Manual migration: Spring Boot application options require Helidon dependency/config review");
        }

        final BootstrapScan scan = new BootstrapScan(classDecl);
        new JavaIsoVisitor<BootstrapScan>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration nested,
                                                             BootstrapScan accumulator) {
                if (!accumulator.rootId.equals(nested.getId())) {
                    return nested;
                }
                return super.visitClassDeclaration(nested, accumulator);
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, BootstrapScan accumulator) {
                J.Identifier id = super.visitIdentifier(identifier, accumulator);
                if (isLifecycleType(id.getType())) {
                    accumulator.lifecycle = true;
                }
                return id;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
                                                             BootstrapScan accumulator) {
                J.MethodInvocation invocation = super.visitMethodInvocation(method, accumulator);
                if (SPRING_RUN.matches(invocation)) {
                    accumulator.runCount++;
                    if (!isSupportedRunShape(getCursor(), invocation)) {
                        accumulator.unsupportedRun = true;
                    } else if (!usesEnclosingApplicationClass(invocation, accumulator.applicationType)) {
                        accumulator.invalidPrimarySource = true;
                    } else if (!isOnlyMainStatement(getCursor(), invocation)) {
                        accumulator.nonPlainMain = true;
                    }
                }
                return invocation;
            }
        }.visit(classDecl, scan);

        if (scan.lifecycle) {
            return BootstrapAssessment.unsafe(
                    "Manual migration: SpringApplicationBuilder or servlet initializer lifecycle is in use");
        }
        if (hasNonTrivialHierarchy(classDecl)) {
            return BootstrapAssessment.unsafe(
                    "Manual migration: application superclass or interfaces require lifecycle review");
        }
        if (hasAdditionalSpringAnnotations(classDecl)) {
            return BootstrapAssessment.unsafe(
                    "Manual migration: additional Spring annotations on the application launcher require review");
        }
        if (scan.runCount == 0) {
            return BootstrapAssessment.unsafe(
                    "Manual migration: no supported SpringApplication.run bootstrap was found");
        }
        if (scan.runCount != 1) {
            return BootstrapAssessment.unsafe(
                    "Manual migration: exactly one SpringApplication.run bootstrap is required");
        }
        if (scan.invalidPrimarySource) {
            return BootstrapAssessment.unsafe(
                    "Manual migration: SpringApplication.run must use the enclosing " +
                    "@SpringBootApplication class as its primary source");
        }
        if (scan.unsupportedRun) {
            return BootstrapAssessment.unsafe(
                    "Manual migration: SpringApplication.run must be a standalone call in " +
                    "public static void main with a String[] argument");
        }
        if (!hasOnlyMainMethod(classDecl)) {
            return BootstrapAssessment.unsafe(
                    "Manual migration: application launcher must contain only one plain main method");
        }
        if (scan.nonPlainMain) {
            return BootstrapAssessment.unsafe(
                    "Manual migration: SpringApplication.run must be the only statement in public static void main");
        }
        return BootstrapAssessment.safe();
    }

    private static boolean isSupportedRunShape(org.openrewrite.Cursor cursor, J.MethodInvocation invocation) {
        J.MethodDeclaration method = cursor.firstEnclosing(J.MethodDeclaration.class);
        if (method == null || !"main".equals(method.getSimpleName()) ||
                !method.hasModifier(J.Modifier.Type.Public) ||
                !method.hasModifier(J.Modifier.Type.Static) ||
                method.getReturnTypeExpression() == null ||
                method.getReturnTypeExpression().getType() != JavaType.Primitive.Void ||
                !(cursor.getParentTreeCursor().getValue() instanceof J.Block)) {
            return false;
        }
        List<Expression> arguments = invocation.getArguments();
        return arguments.size() == 2 && isStringArray(arguments.get(1).getType());
    }

    private static boolean usesEnclosingApplicationClass(J.MethodInvocation invocation,
                                                          JavaType applicationType) {
        List<Expression> arguments = invocation.getArguments();
        if (arguments.size() != 2 || !(arguments.get(0) instanceof J.FieldAccess)) {
            return false;
        }
        J.FieldAccess classLiteral = (J.FieldAccess) arguments.get(0);
        return "class".equals(classLiteral.getName().getSimpleName()) &&
               TypeUtils.isOfType(applicationType, classLiteral.getTarget().getType());
    }

    private static boolean isOnlyMainStatement(org.openrewrite.Cursor cursor,
                                                J.MethodInvocation invocation) {
        J.MethodDeclaration method = cursor.firstEnclosing(J.MethodDeclaration.class);
        return method != null && method.getBody() != null &&
               method.getBody().getStatements().size() == 1 &&
               method.getBody().getStatements().get(0) == invocation;
    }

    private static boolean hasNonTrivialHierarchy(J.ClassDeclaration classDecl) {
        return (classDecl.getExtends() != null &&
                !TypeUtils.isOfClassType(classDecl.getExtends().getType(), "java.lang.Object")) ||
               (classDecl.getImplements() != null && !classDecl.getImplements().isEmpty());
    }

    private static boolean hasAdditionalSpringAnnotations(J.ClassDeclaration classDecl) {
        final boolean[] found = new boolean[1];
        new JavaIsoVisitor<boolean[]>() {
            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, boolean[] accumulator) {
                J.Annotation a = super.visitAnnotation(annotation, accumulator);
                if (!SPRING_BOOT_APPLICATION.matches(a)) {
                    JavaType.FullyQualified type = TypeUtils.asFullyQualified(a.getType());
                    if (hasSpringBehavior(type, new HashSet<String>(), 0)) {
                        accumulator[0] = true;
                    }
                }
                return a;
            }
        }.visit(classDecl, found);
        return found[0];
    }

    private static boolean hasSpringBehavior(JavaType.FullyQualified annotationType,
                                             Set<String> visited, int depth) {
        if (annotationType == null) {
            return false;
        }
        if (annotationType.getFullyQualifiedName().startsWith("org.springframework.")) {
            return true;
        }
        // A very deep composed annotation graph is not safe to declare Spring-free. Fail closed
        // instead of allowing nesting depth to bypass this bootstrap boundary.
        if (depth >= MAX_META_ANNOTATION_DEPTH) {
            return true;
        }
        if (!visited.add(annotationType.getFullyQualifiedName())) {
            return false;
        }
        for (JavaType.FullyQualified metaAnnotation : annotationType.getAnnotations()) {
            if (hasSpringBehavior(metaAnnotation, visited, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasOnlyMainMethod(J.ClassDeclaration classDecl) {
        List<org.openrewrite.java.tree.Statement> statements = classDecl.getBody().getStatements();
        return statements.size() == 1 && statements.get(0) instanceof J.MethodDeclaration &&
               "main".equals(((J.MethodDeclaration) statements.get(0)).getSimpleName());
    }

    private static boolean isStringArray(JavaType type) {
        if (!(type instanceof JavaType.Array)) {
            return false;
        }
        JavaType element = ((JavaType.Array) type).getElemType();
        return element == JavaType.Primitive.String || TypeUtils.isOfClassType(element, "java.lang.String");
    }

    private static boolean isLifecycleType(JavaType type) {
        return TypeUtils.isAssignableTo(SPRING_APPLICATION_BUILDER, type) ||
               TypeUtils.isAssignableTo(SPRING_BOOT_SERVLET_INITIALIZER, type);
    }

    private static boolean isEmptyArguments(List<Expression> arguments) {
        return arguments == null || arguments.isEmpty() ||
               (arguments.size() == 1 && arguments.get(0) instanceof J.Empty);
    }

    private static void scanSpringRuntimeResidue(SourceFile sourceFile,
                                                 SpringRuntimeResidueState state) {
        if (!(sourceFile instanceof J.CompilationUnit) || isTestSource(sourceFile.getSourcePath())) {
            return;
        }
        J.CompilationUnit compilationUnit = (J.CompilationUnit) sourceFile;
        boolean supportedLauncher = isSupportedLauncherCompilationUnit(compilationUnit);
        if (supportedLauncher) {
            state.recordCandidate(sourceFile.getSourcePath());
        }
        boolean printedResidue = containsForbiddenSpringName(compilationUnit.printAll(), supportedLauncher);
        boolean attributedResidue = containsAttributedSpringRuntimeType(compilationUnit, supportedLauncher);
        if (printedResidue || attributedResidue) {
            state.block(sourceFile.getSourcePath());
        }
    }

    private static boolean isSupportedLauncherCompilationUnit(J.CompilationUnit compilationUnit) {
        if (compilationUnit.getClasses().size() != 1) {
            return false;
        }
        J.ClassDeclaration candidate = compilationUnit.getClasses().get(0);
        J.Annotation boot = findSpringBootApplication(candidate);
        if (!assess(candidate, boot).safe) {
            return false;
        }
        final int[] bootAnnotations = new int[1];
        new JavaIsoVisitor<int[]>() {
            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, int[] count) {
                J.Annotation a = super.visitAnnotation(annotation, count);
                if (SPRING_BOOT_APPLICATION.matches(a)) {
                    count[0]++;
                }
                return a;
            }
        }.visit(compilationUnit, bootAnnotations);
        return bootAnnotations[0] == 1;
    }

    private static boolean isTestSource(Path sourcePath) {
        Path normalized = sourcePath.normalize();
        for (int i = 0; i + 1 < normalized.getNameCount(); i++) {
            String sourceSet = normalized.getName(i + 1).toString();
            if ("src".equals(normalized.getName(i).toString()) &&
                    ("test".equals(sourceSet) || "it".equals(sourceSet))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsForbiddenSpringName(String source, boolean allowBootstrapPrimitives) {
        final String prefix = "org.springframework.";
        int offset = 0;
        while ((offset = source.indexOf(prefix, offset)) >= 0) {
            int end = offset + prefix.length();
            while (end < source.length()) {
                char candidate = source.charAt(end);
                if (candidate != '.' && candidate != '$' && !Character.isJavaIdentifierPart(candidate)) {
                    break;
                }
                end++;
            }
            if (!allowBootstrapPrimitives || !isAllowedBootstrapName(source.substring(offset, end))) {
                return true;
            }
            offset = end;
        }
        return false;
    }

    private static boolean isAllowedBootstrapName(String name) {
        return SPRING_APPLICATION.equals(name) ||
               (SPRING_APPLICATION + ".run").equals(name) ||
               (SPRING_APPLICATION + ".class").equals(name) ||
               SPRING_BOOT_APPLICATION_TYPE.equals(name) ||
               (SPRING_BOOT_APPLICATION_TYPE + ".class").equals(name);
    }

    private static boolean containsAttributedSpringRuntimeType(J.CompilationUnit compilationUnit,
                                                                final boolean allowBootstrapPrimitives) {
        final AtomicBoolean detected = new AtomicBoolean(false);
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, AtomicBoolean found) {
                J.Annotation a = super.visitAnnotation(annotation, found);
                if (containsSpringRuntimeAnnotation(a.getType(), new HashSet<String>(), 0,
                        allowBootstrapPrimitives)) {
                    found.set(true);
                }
                return a;
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl,
                                                             AtomicBoolean found) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, found);
                if (cd.getExtends() != null && containsSpringRuntimeType(cd.getExtends().getType(),
                        new HashSet<String>(), 0, allowBootstrapPrimitives)) {
                    found.set(true);
                }
                if (cd.getImplements() != null) {
                    for (org.openrewrite.java.tree.TypeTree implemented : cd.getImplements()) {
                        if (containsSpringRuntimeType(implemented.getType(), new HashSet<String>(), 0,
                                allowBootstrapPrimitives)) {
                            found.set(true);
                        }
                    }
                }
                return cd;
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean found) {
                J.Identifier id = super.visitIdentifier(identifier, found);
                J.MethodInvocation enclosingInvocation = getCursor().firstEnclosing(J.MethodInvocation.class);
                if (allowBootstrapPrimitives && enclosingInvocation != null &&
                        SPRING_RUN.matches(enclosingInvocation)) {
                    return id;
                }
                Object parent = getCursor().getParentOrThrow().getValue();
                if ((parent instanceof J.MethodDeclaration &&
                        ((J.MethodDeclaration) parent).getName() == id) ||
                        (parent instanceof J.MethodInvocation &&
                                ((J.MethodInvocation) parent).getName() == id) ||
                        (parent instanceof J.MemberReference &&
                                ((J.MemberReference) parent).getReference() == id) ||
                        (parent instanceof J.ClassDeclaration &&
                                ((J.ClassDeclaration) parent).getName() == id) ||
                        (parent instanceof J.VariableDeclarations.NamedVariable &&
                                ((J.VariableDeclarations.NamedVariable) parent).getName() == id)) {
                    return id;
                }
                if (containsSpringRuntimeType(id.getType(), new HashSet<String>(), 0,
                        allowBootstrapPrimitives) ||
                        id.getFieldType() != null && containsSpringRuntimeType(
                                id.getFieldType(), new HashSet<String>(), 0, allowBootstrapPrimitives)) {
                    found.set(true);
                }
                return id;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
                                                             AtomicBoolean found) {
                J.MethodInvocation invocation = super.visitMethodInvocation(method, found);
                if (containsSpringRuntimeMethod(invocation.getMethodType(), allowBootstrapPrimitives)) {
                    found.set(true);
                }
                return invocation;
            }

            @Override
            public J.MemberReference visitMemberReference(J.MemberReference memberReference,
                                                           AtomicBoolean found) {
                J.MemberReference reference = super.visitMemberReference(memberReference, found);
                if (containsSpringRuntimeMethod(reference.getMethodType(), allowBootstrapPrimitives)) {
                    found.set(true);
                }
                return reference;
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, AtomicBoolean found) {
                J.NewClass created = super.visitNewClass(newClass, found);
                if (containsSpringRuntimeType(created.getType(), new HashSet<String>(), 0,
                        allowBootstrapPrimitives)) {
                    found.set(true);
                }
                return created;
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations variables,
                                                                      AtomicBoolean found) {
                J.VariableDeclarations declarations = super.visitVariableDeclarations(variables, found);
                if (containsSpringRuntimeType(declarations.getType(), new HashSet<String>(), 0,
                        allowBootstrapPrimitives)) {
                    found.set(true);
                }
                return declarations;
            }

            @Override
            public J.Literal visitLiteral(J.Literal literal, AtomicBoolean found) {
                J.Literal l = super.visitLiteral(literal, found);
                if (l.getValue() instanceof String &&
                        containsForbiddenSpringName((String) l.getValue(), false)) {
                    found.set(true);
                }
                return l;
            }
        }.visit(compilationUnit, detected);
        return detected.get();
    }

    private static boolean containsSpringRuntimeMethod(JavaType.Method method,
                                                       boolean allowBootstrapPrimitives) {
        if (method == null || allowBootstrapPrimitives && isAllowedSpringRun(method)) {
            return false;
        }
        if (containsSpringRuntimeType(method.getDeclaringType(), new HashSet<String>(), 0,
                allowBootstrapPrimitives) ||
                containsSpringRuntimeType(method.getReturnType(), new HashSet<String>(), 0,
                        allowBootstrapPrimitives)) {
            return true;
        }
        for (JavaType parameterType : method.getParameterTypes()) {
            if (containsSpringRuntimeType(parameterType, new HashSet<String>(), 0,
                    allowBootstrapPrimitives)) {
                return true;
            }
        }
        for (JavaType thrownType : method.getThrownExceptions()) {
            if (containsSpringRuntimeType(thrownType, new HashSet<String>(), 0,
                    allowBootstrapPrimitives)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSpringRuntimeAnnotation(JavaType type, Set<String> visited, int depth,
                                                           boolean allowBootstrapPrimitives) {
        JavaType.FullyQualified annotationType = TypeUtils.asFullyQualified(type);
        if (annotationType == null) {
            return false;
        }
        String name = annotationType.getFullyQualifiedName();
        if (allowBootstrapPrimitives && SPRING_BOOT_APPLICATION_TYPE.equals(name)) {
            return false;
        }
        if (name.startsWith("org.springframework.")) {
            return true;
        }
        if (depth > 32 || !visited.add("annotation:" + name)) {
            return depth > 32;
        }
        for (JavaType.FullyQualified metaAnnotation : annotationType.getAnnotations()) {
            if (containsSpringRuntimeAnnotation(metaAnnotation, visited, depth + 1,
                    allowBootstrapPrimitives)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllowedSpringRun(JavaType.Method method) {
        JavaType.FullyQualified declaringType = method.getDeclaringType();
        return declaringType != null && SPRING_APPLICATION.equals(declaringType.getFullyQualifiedName()) &&
               "run".equals(method.getName());
    }

    private static boolean containsSpringRuntimeType(JavaType type, Set<String> visited, int depth,
                                                     boolean allowBootstrapPrimitives) {
        if (type == null) {
            return false;
        }
        if (depth > 32) {
            return true;
        }
        if (type instanceof JavaType.Array) {
            JavaType.Array array = (JavaType.Array) type;
            if (containsSpringRuntimeType(array.getElemType(), visited, depth + 1,
                    allowBootstrapPrimitives)) {
                return true;
            }
            for (JavaType.FullyQualified annotation : array.getAnnotations()) {
                if (containsSpringRuntimeType(annotation, visited, depth + 1,
                        allowBootstrapPrimitives)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof JavaType.Parameterized) {
            JavaType.Parameterized parameterized = (JavaType.Parameterized) type;
            if (containsSpringRuntimeType(parameterized.getType(), visited, depth + 1,
                    allowBootstrapPrimitives)) {
                return true;
            }
            for (JavaType typeParameter : parameterized.getTypeParameters()) {
                if (containsSpringRuntimeType(typeParameter, visited, depth + 1,
                        allowBootstrapPrimitives)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof JavaType.GenericTypeVariable) {
            JavaType.GenericTypeVariable generic = (JavaType.GenericTypeVariable) type;
            String key = "generic:" + generic.getName() + ':' + System.identityHashCode(generic);
            if (!visited.add(key)) {
                return false;
            }
            for (JavaType bound : generic.getBounds()) {
                if (containsSpringRuntimeType(bound, visited, depth + 1,
                        allowBootstrapPrimitives)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof JavaType.Variable) {
            JavaType.Variable variable = (JavaType.Variable) type;
            String key = "variable:" + System.identityHashCode(variable);
            if (!visited.add(key)) {
                return false;
            }
            return containsSpringRuntimeType(variable.getType(), visited, depth + 1,
                    allowBootstrapPrimitives);
        }
        if (type instanceof JavaType.Method) {
            return containsSpringRuntimeMethod((JavaType.Method) type, allowBootstrapPrimitives);
        }

        JavaType.FullyQualified fullyQualified = TypeUtils.asFullyQualified(type);
        if (fullyQualified == null) {
            return false;
        }
        String name = fullyQualified.getFullyQualifiedName();
        if (allowBootstrapPrimitives &&
                (SPRING_APPLICATION.equals(name) || SPRING_BOOT_APPLICATION_TYPE.equals(name))) {
            return false;
        }
        if (name.startsWith("org.springframework.")) {
            return true;
        }
        if (!visited.add("type:" + name)) {
            return false;
        }
        if (containsSpringRuntimeType(fullyQualified.getSupertype(), visited, depth + 1,
                allowBootstrapPrimitives)) {
            return true;
        }
        for (JavaType.FullyQualified implemented : fullyQualified.getInterfaces()) {
            if (containsSpringRuntimeType(implemented, visited, depth + 1,
                    allowBootstrapPrimitives)) {
                return true;
            }
        }
        for (JavaType typeParameter : fullyQualified.getTypeParameters()) {
            if (containsSpringRuntimeType(typeParameter, visited, depth + 1,
                    allowBootstrapPrimitives)) {
                return true;
            }
        }
        return false;
    }

    static final class MigrationScope {
        private final SpringSecurityProjectGate.State security = SpringSecurityProjectGate.newAccumulator();
        private final SpringRuntimeResidueState runtimeResidue = new SpringRuntimeResidueState();
    }

    private static final class SpringRuntimeResidueState {
        private final Set<Path> blockedModules = ConcurrentHashMap.newKeySet();
        private final Map<Path, Set<Path>> launcherCandidates =
                new ConcurrentHashMap<Path, Set<Path>>();

        private void recordCandidate(Path sourcePath) {
            Path module = SpringSecurityProjectGate.moduleRoot(sourcePath);
            Set<Path> newCandidates = ConcurrentHashMap.newKeySet();
            Set<Path> candidates = launcherCandidates.putIfAbsent(module, newCandidates);
            if (candidates == null) {
                candidates = newCandidates;
            }
            candidates.add(sourcePath.normalize());
            if (candidates.size() > 1) {
                blockedModules.add(module);
            }
        }

        private void block(Path sourcePath) {
            blockedModules.add(SpringSecurityProjectGate.moduleRoot(sourcePath));
        }

        private boolean isBlocked(Path sourcePath) {
            return blockedModules.contains(SpringSecurityProjectGate.moduleRoot(sourcePath));
        }
    }

    private static final class BootstrapScan {
        private final java.util.UUID rootId;
        private final JavaType applicationType;
        private int runCount;
        private boolean unsupportedRun;
        private boolean invalidPrimarySource;
        private boolean nonPlainMain;
        private boolean lifecycle;

        private BootstrapScan(J.ClassDeclaration root) {
            this.rootId = root.getId();
            this.applicationType = root.getType();
        }
    }

    private static final class BootstrapAssessment {
        private final boolean safe;
        private final String message;

        private BootstrapAssessment(boolean safe, String message) {
            this.safe = safe;
            this.message = message;
        }

        private static BootstrapAssessment safe() {
            return new BootstrapAssessment(true, "");
        }

        private static BootstrapAssessment unsafe(String message) {
            return new BootstrapAssessment(false, message);
        }
    }
}
