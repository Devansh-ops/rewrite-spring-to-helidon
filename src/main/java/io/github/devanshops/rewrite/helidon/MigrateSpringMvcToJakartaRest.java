package io.github.devanshops.rewrite.helidon;

import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Migrates the common, semantics-preserving Spring MVC annotation subset to
 * Jakarta REST. Anything conditional or Spring-specific is marked for review.
 */
public class MigrateSpringMvcToJakartaRest extends ScanningRecipe<MigrateSpringMvcToJakartaRest.Accumulator> {
    private static final String SPRING_RESPONSE = "org.springframework.http.ResponseEntity";
    private static final String UNSUPPORTED_CONTROLLER =
            "Manual migration: controller contains unsupported Spring MVC mapping or binding semantics; " +
            "no Spring MVC annotations were changed";
    private static final String RESPONSE_ENTITY_CONTROLLER =
            "Manual migration: controller still uses Spring ResponseEntity; migrate response status and " +
            "header semantics before converting Spring MVC annotations";
    private static final String SPRING_SECURITY_CONTROLLER =
            "Manual migration: controller uses Spring Security annotations; preserve endpoint authorization " +
            "before converting Spring MVC annotations";
    private static final String FINAL_CONTROLLER =
            "Manual migration: final Spring controller is not proxyable as a CDI ApplicationScoped bean; " +
            "no Spring MVC annotations were changed";
    private static final String SEALED_CONTROLLER =
            "Manual migration: sealed Spring controller is not proxyable as a CDI ApplicationScoped bean; " +
            "no Spring MVC annotations were changed";
    private static final String FINAL_RESOURCE_METHOD =
            "Manual migration: final resource method is not proxyable on a CDI ApplicationScoped bean; " +
            "no Spring MVC annotations were changed";
    private static final String UNSUPPORTED_RETURN_TYPE =
            "Manual migration: controller uses an async, reactive, or streaming return type that needs " +
            "explicit Jakarta REST design; no Spring MVC annotations were changed";
    private static final String UNSUPPORTED_CONSTRUCTOR =
            "Manual migration: controller has no public no-argument constructor required for a " +
            "portable CDI ApplicationScoped proxy; " +
            "no Spring MVC annotations were changed";
    private static final String UNSUPPORTED_SUPERTYPE =
            "Manual migration: controller inheritance needs CDI proxyability and inherited endpoint review; " +
            "no Spring MVC annotations were changed";
    private static final String SPRING_CONTRACT_CONTROLLER =
            "Manual migration: controller implements or inherits a Spring framework contract whose lifecycle " +
            "semantics need explicit migration; no Spring MVC annotations were changed";
    private static final String UNSUPPORTED_RESOURCE_CLASS =
            "Manual migration: a portable Jakarta REST root resource must be a public top-level class; " +
            "no Spring MVC annotations were changed";
    private static final String NON_PUBLIC_RESOURCE_METHOD =
            "Manual migration: Jakarta REST resource methods must be public and non-static; " +
            "no Spring MVC annotations were changed";
    private static final String VOID_RETURN_TYPE =
            "Manual migration: Spring MVC and Jakarta REST empty-response semantics need explicit status review; " +
            "no Spring MVC annotations were changed";
    private static final String SPRING_BEHAVIOR_CONTROLLER =
            "Manual migration: controller retains Spring behavior annotations that would be inert after " +
            "Jakarta REST conversion; no Spring MVC annotations were changed";
    private static final String UNVERIFIED_PATH_SYNTAX =
            "Manual migration: controller uses Spring-only or unverified path syntax; " +
            "no Spring MVC annotations were changed";
    private static final String PROJECT_SPRING_SECURITY =
            "Manual migration: Spring Security is present in this migration scope; preserve global filters and " +
            "endpoint authorization before converting Spring MVC annotations";
    private static final String PROJECT_SPRING_WEB_INFRASTRUCTURE =
            "Manual migration: Spring Web or servlet runtime infrastructure is present in this migration scope; " +
            "preserve filters, advice, exception, and request/response semantics before converting Spring MVC annotations";
    private static final AnnotationMatcher REST_CONTROLLER =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.RestController");
    private static final AnnotationMatcher REQUEST_MAPPING =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.RequestMapping");
    private static final AnnotationMatcher GET_MAPPING =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.GetMapping");
    private static final AnnotationMatcher POST_MAPPING =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.PostMapping");
    private static final AnnotationMatcher PUT_MAPPING =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.PutMapping");
    private static final AnnotationMatcher DELETE_MAPPING =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.DeleteMapping");
    private static final AnnotationMatcher PATCH_MAPPING =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.PatchMapping");
    private static final AnnotationMatcher REQUEST_PARAM =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.RequestParam");
    private static final AnnotationMatcher PATH_VARIABLE =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.PathVariable");
    private static final AnnotationMatcher REQUEST_HEADER =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.RequestHeader");
    private static final AnnotationMatcher REQUEST_BODY =
            new AnnotationMatcher("@org.springframework.web.bind.annotation.RequestBody");
    private static final String[] UNSUPPORTED_RETURN_TYPES = {
            "java.io.InputStream",
            "java.util.concurrent.Callable",
            "java.util.concurrent.CompletableFuture",
            "java.util.concurrent.CompletionStage",
            "java.util.concurrent.Future",
            "java.util.stream.Stream",
            "org.reactivestreams.Publisher",
            "org.springframework.core.io.Resource",
            "org.springframework.web.context.request.async.DeferredResult",
            "org.springframework.web.context.request.async.WebAsyncTask",
            "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter",
            "org.springframework.web.servlet.mvc.method.annotation.SseEmitter",
            "org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody",
            "reactor.core.publisher.Flux",
            "reactor.core.publisher.Mono"
    };

    private static final Comparator<J.Annotation> ANNOTATION_ORDER =
            new Comparator<J.Annotation>() {
                @Override
                public int compare(J.Annotation left, J.Annotation right) {
                    return left.getSimpleName().compareTo(right.getSimpleName());
                }
            };

    @Override
    public String getDisplayName() {
        return "Migrate Spring MVC to Jakarta REST";
    }

    @Override
    public String getDescription() {
        return "Migrates REST controllers, HTTP method mappings, paths, query/path/header parameters, " +
               "and request bodies to Jakarta REST while marking conditional Spring MVC semantics for review.";
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(8);
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
                    StringConstantProjectIndex.scanSource(sourceFile, accumulator.stringConstants);
                }
                return tree;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(final Accumulator accumulator) {
        return Preconditions.check(
                new UsesType<>("org.springframework.web.bind.annotation.RestController", false),
                new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable,
                                                                      ExecutionContext ctx) {
                J.VariableDeclarations variables = super.visitVariableDeclarations(multiVariable, ctx);
                if (!isInsideSupportedMappedMethod()) {
                    return variables;
                }
                final J.VariableDeclarations originalVariables = variables;
                final Expression[] defaultValue = new Expression[1];
                final Space[] removedLeadingPrefix = new Space[1];
                List<J.Annotation> annotations = ListUtils.map(variables.getLeadingAnnotations(), annotation -> {
                    if (REQUEST_BODY.matches(annotation)) {
                        if (isRequiredFalse(annotation) && !isPrimitive(originalVariables)) {
                            if (!originalVariables.getLeadingAnnotations().isEmpty() &&
                                    originalVariables.getLeadingAnnotations().get(0) == annotation) {
                                removedLeadingPrefix[0] = annotation.getPrefix();
                            }
                            maybeRemoveImport("org.springframework.web.bind.annotation.RequestBody");
                            return null;
                        }
                        return SearchResult.found(annotation,
                                "Manual migration: Spring request-body required/null semantics need explicit Jakarta REST validation");
                    }

                    Binding binding = binding(annotation);
                    if (binding == null) {
                        return annotation;
                    }
                    if (binding.kind == BindingKind.HEADER && isWholeHeadersType(originalVariables)) {
                        return SearchResult.found(annotation,
                                "Manual migration: whole-header injection should use jakarta.ws.rs.core.HttpHeaders");
                    }

                    Parameter parameter = parameter(annotation, binding.kind);
                    if (!parameter.supported ||
                            ((binding.kind == BindingKind.QUERY || binding.kind == BindingKind.HEADER) &&
                             parameter.required && parameter.defaultValue == null) ||
                            (!parameter.required && parameter.defaultValue == null && isPrimitive(originalVariables))) {
                        return SearchResult.found(annotation,
                                "Manual migration: Spring parameter required/default/null semantics need explicit Jakarta REST handling");
                    }

                    Expression name = parameter.name;
                    if (name == null) {
                        if (originalVariables.getVariables().size() != 1) {
                            return SearchResult.found(annotation,
                                    "Manual migration: parameter name could not be inferred safely");
                        }
                        name = stringLiteral(originalVariables.getVariables().get(0).getSimpleName());
                    }
                    defaultValue[0] = parameter.defaultValue;
                    removeParameterImport(annotation);
                    maybeAddImport(binding.targetType, false);
                    Cursor annotationCursor = new Cursor(updateCursor(originalVariables), annotation);
                    return HelidonJavaTemplate.builder("@" + binding.targetSimpleName +
                                                "(#{any(java.lang.String)})")
                            .imports(binding.targetType)
                            .build()
                            .apply(annotationCursor, annotation.getCoordinates().replace(), name);
                });
                variables = variables.withLeadingAnnotations(annotations);
                if (removedLeadingPrefix[0] != null) {
                    if (variables.getLeadingAnnotations().isEmpty() && variables.getTypeExpression() != null) {
                        variables = variables.withTypeExpression(
                                variables.getTypeExpression().withPrefix(removedLeadingPrefix[0]));
                    } else if (!variables.getLeadingAnnotations().isEmpty()) {
                        final Space prefix = removedLeadingPrefix[0];
                        variables = variables.withLeadingAnnotations(
                                ListUtils.mapFirst(variables.getLeadingAnnotations(), first ->
                                        first.withPrefix(prefix)));
                    }
                }
                if (defaultValue[0] != null) {
                    maybeAddImport("jakarta.ws.rs.DefaultValue", false);
                    variables = HelidonJavaTemplate.builder("@DefaultValue(#{any(java.lang.String)})")
                            .imports("jakarta.ws.rs.DefaultValue")
                            .build()
                            .apply(updateCursor(variables),
                                    variables.getCoordinates().addAnnotation(ANNOTATION_ORDER), defaultValue[0]);
                }
                return maybeAutoFormat(multiVariable, variables, ctx);
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method,
                                                               ExecutionContext ctx) {
                if (!isInsideMigratableRestController()) {
                    return super.visitMethodDeclaration(method, ctx);
                }
                J.Annotation springMapping = null;
                for (J.Annotation annotation : method.getLeadingAnnotations()) {
                    if (isMethodMapping(annotation)) {
                        if (springMapping != null) {
                            return method.withLeadingAnnotations(ListUtils.map(method.getLeadingAnnotations(), candidate ->
                                    candidate == annotation ? SearchResult.found(candidate,
                                            "Manual migration: multiple Spring request mappings on one method") : candidate));
                        }
                        springMapping = annotation;
                    }
                }
                if (springMapping == null) {
                    return super.visitMethodDeclaration(method, ctx);
                }

                Mapping mapping = mapping(springMapping);
                if (!mapping.supported) {
                    final J.Annotation unsupported = springMapping;
                    return method.withLeadingAnnotations(ListUtils.map(method.getLeadingAnnotations(), candidate ->
                            candidate == unsupported ? SearchResult.found(candidate,
                                    "Manual migration: produces, consumes, params, headers, multiple paths, or " +
                                    "unbounded @RequestMapping semantics need Jakarta REST review") : candidate));
                }

                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
                final J.Annotation migrated = findMethodMapping(m.getLeadingAnnotations());
                m = m.withLeadingAnnotations(ListUtils.map(m.getLeadingAnnotations(), candidate ->
                        candidate == migrated ? null : candidate));
                maybeRemoveImport(mapping.springType);
                m = addMarkerAnnotation(m, mapping.verb, "jakarta.ws.rs." + mapping.verb);
                if (mapping.path != null) {
                    m = addPath(m, mapping.path);
                }
                return m;
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl,
                                                             ExecutionContext ctx) {
                J.Annotation restController = find(classDecl.getLeadingAnnotations(), REST_CONTROLLER);
                if (restController == null) {
                    return super.visitClassDeclaration(classDecl, ctx);
                }

                boolean topLevel = getCursor().getParentTreeCursor()
                        .firstEnclosing(J.ClassDeclaration.class) == null;
                J.CompilationUnit compilationUnit = getCursor().firstEnclosing(J.CompilationUnit.class);
                Path sourcePath = compilationUnit == null ? java.nio.file.Paths.get("") :
                        compilationUnit.getSourcePath();
                ControllerAssessment assessment = assessController(classDecl, true, topLevel,
                        accumulator.stringConstants, sourcePath, compilationUnit);
                if (assessment.supported && SpringSecurityProjectGate.isBlocked(
                        accumulator.springSecurity, sourcePath)) {
                    assessment = ControllerAssessment.unsupported(PROJECT_SPRING_SECURITY);
                }
                if (assessment.supported && SpringWebProjectGate.isBlocked(
                        accumulator.springWeb, sourcePath)) {
                    assessment = ControllerAssessment.unsupported(PROJECT_SPRING_WEB_INFRASTRUCTURE);
                }
                if (!assessment.supported) {
                    final J.Annotation unsupported = restController;
                    final String assessmentMessage = assessment.message;
                    return classDecl.withLeadingAnnotations(
                            ListUtils.map(classDecl.getLeadingAnnotations(), annotation ->
                                    annotation == unsupported ? SearchResult.found(annotation,
                                            assessmentMessage) :
                                            annotation));
                }

                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
                restController = find(cd.getLeadingAnnotations(), REST_CONTROLLER);
                J.Annotation requestMapping = find(cd.getLeadingAnnotations(), REQUEST_MAPPING);
                Mapping classPath = requestMapping == null ? Mapping.classPath(null, true) : classPath(requestMapping);
                final J.Annotation removeController = restController;
                final J.Annotation removeMapping = requestMapping;
                cd = cd.withLeadingAnnotations(ListUtils.map(cd.getLeadingAnnotations(), annotation -> {
                    if (annotation == removeController || annotation == removeMapping) {
                        return null;
                    }
                    return annotation;
                }));
                maybeRemoveImport("org.springframework.web.bind.annotation.RestController");
                if (requestMapping != null) {
                    maybeRemoveImport("org.springframework.web.bind.annotation.RequestMapping");
                }
                // Spring MVC controllers are singletons by default. ApplicationScoped is the
                // closest CDI lifecycle; RequestScoped would silently change instance semantics.
                cd = addMarkerAnnotation(cd, "ApplicationScoped", "jakarta.enterprise.context.ApplicationScoped");
                cd = addPath(cd, classPath.path == null ? stringLiteral("/") : classPath.path);
                return cd;
            }

            private J.MethodDeclaration addMarkerAnnotation(J.MethodDeclaration method, String simpleName,
                                                              String fullyQualifiedName) {
                maybeAddImport(fullyQualifiedName, false);
                return HelidonJavaTemplate.builder("@" + simpleName)
                        .imports(fullyQualifiedName)
                        .build()
                        .apply(updateCursor(method), method.getCoordinates().addAnnotation(ANNOTATION_ORDER));
            }

            private J.ClassDeclaration addMarkerAnnotation(J.ClassDeclaration classDecl, String simpleName,
                                                             String fullyQualifiedName) {
                maybeAddImport(fullyQualifiedName, false);
                return HelidonJavaTemplate.builder("@" + simpleName)
                        .imports(fullyQualifiedName)
                        .build()
                        .apply(updateCursor(classDecl), classDecl.getCoordinates().addAnnotation(ANNOTATION_ORDER));
            }

            private J.MethodDeclaration addPath(J.MethodDeclaration method, Expression path) {
                maybeAddImport("jakarta.ws.rs.Path", false);
                return HelidonJavaTemplate.builder("@Path(#{any(java.lang.String)})")
                        .imports("jakarta.ws.rs.Path")
                        .build()
                        .apply(updateCursor(method), method.getCoordinates().addAnnotation(ANNOTATION_ORDER), path);
            }

            private J.ClassDeclaration addPath(J.ClassDeclaration classDecl, Expression path) {
                maybeAddImport("jakarta.ws.rs.Path", false);
                return HelidonJavaTemplate.builder("@Path(#{any(java.lang.String)})")
                        .imports("jakarta.ws.rs.Path")
                        .build()
                        .apply(updateCursor(classDecl), classDecl.getCoordinates().addAnnotation(ANNOTATION_ORDER), path);
            }

            private boolean isInsideMigratableRestController() {
                Cursor cursor = getCursor();
                J.ClassDeclaration enclosing = cursor.firstEnclosing(J.ClassDeclaration.class);
                if (enclosing == null || find(enclosing.getLeadingAnnotations(), REST_CONTROLLER) == null) {
                    return false;
                }
                J.Annotation classMapping = find(enclosing.getLeadingAnnotations(), REQUEST_MAPPING);
                return classMapping == null || classPath(classMapping).supported;
            }

            private boolean isInsideSupportedMappedMethod() {
                if (!isInsideMigratableRestController()) {
                    return false;
                }
                J.MethodDeclaration enclosing = getCursor().firstEnclosing(J.MethodDeclaration.class);
                if (enclosing == null) {
                    return false;
                }
                J.Annotation mappingAnnotation = null;
                for (J.Annotation annotation : enclosing.getLeadingAnnotations()) {
                    if (isMethodMapping(annotation)) {
                        if (mappingAnnotation != null) {
                            return false;
                        }
                        mappingAnnotation = annotation;
                    }
                }
                return mappingAnnotation != null && mapping(mappingAnnotation).supported;
            }

            private void removeParameterImport(J.Annotation annotation) {
                if (REQUEST_PARAM.matches(annotation)) {
                    maybeRemoveImport("org.springframework.web.bind.annotation.RequestParam");
                } else if (PATH_VARIABLE.matches(annotation)) {
                    maybeRemoveImport("org.springframework.web.bind.annotation.PathVariable");
                } else if (REQUEST_HEADER.matches(annotation)) {
                    maybeRemoveImport("org.springframework.web.bind.annotation.RequestHeader");
                }
            }

        });
    }

    /**
     * A Spring controller is migrated as one semantic unit. Mixing Jakarta REST routes with Spring
     * parameter binding (or with Spring response builders) makes the resulting endpoint compile
     * while silently changing its runtime behavior, so any unsafe member keeps the whole controller
     * on Spring MVC.
     */
    static boolean mustPreserveSpringControllerBeforeResponseMigration(J.ClassDeclaration controller,
                                                                        boolean topLevel) {
        ControllerPreflight preflight = classLocalPreflightBeforeResponseMigration(controller, topLevel,
                StringConstantProjectIndex.newAccumulator(), java.nio.file.Paths.get(""), null);
        return preflight.controller && !preflight.supported;
    }

    static boolean isSpringControllerEligibleForResponseMigration(J.ClassDeclaration controller,
                                                                    boolean topLevel) {
        ControllerPreflight preflight = classLocalPreflightBeforeResponseMigration(controller, topLevel,
                StringConstantProjectIndex.newAccumulator(), java.nio.file.Paths.get(""), null);
        return preflight.controller && preflight.supported;
    }

    static ControllerPreflight classLocalPreflight(J.ClassDeclaration controller, boolean topLevel) {
        if (find(controller.getLeadingAnnotations(), REST_CONTROLLER) == null) {
            return ControllerPreflight.notController();
        }
        ControllerAssessment assessment = assessController(controller, true, topLevel,
                StringConstantProjectIndex.newAccumulator(), java.nio.file.Paths.get(""), null);
        return ControllerPreflight.controller(assessment.supported, assessment.message);
    }

    static ControllerPreflight classLocalPreflightBeforeResponseMigration(
            J.ClassDeclaration controller,
            boolean topLevel,
            StringConstantProjectIndex.State constants,
            Path sourcePath,
            J.CompilationUnit compilationUnit) {
        if (find(controller.getLeadingAnnotations(), REST_CONTROLLER) == null) {
            return ControllerPreflight.notController();
        }
        ControllerAssessment assessment = assessController(controller, false, topLevel,
                constants, sourcePath, compilationUnit);
        return ControllerPreflight.controller(assessment.supported, assessment.message);
    }

    private static ControllerAssessment assessController(J.ClassDeclaration controller,
                                                          boolean inspectResponseEntity,
                                                          boolean topLevel,
                                                          StringConstantProjectIndex.State stringConstants,
                                                          Path sourcePath,
                                                          J.CompilationUnit compilationUnit) {
        if (hasModifier(controller, J.Modifier.Type.Final)) {
            return ControllerAssessment.unsupported(FINAL_CONTROLLER);
        }
        if (hasModifier(controller, J.Modifier.Type.Sealed)) {
            return ControllerAssessment.unsupported(SEALED_CONTROLLER);
        }
        if (controller.getExtends() != null &&
                !TypeUtils.isOfClassType(controller.getExtends().getType(), "java.lang.Object")) {
            return ControllerAssessment.unsupported(UNSUPPORTED_SUPERTYPE);
        }
        if (hasSpringFrameworkContract(controller)) {
            return ControllerAssessment.unsupported(SPRING_CONTRACT_CONTROLLER);
        }
        if (hasSpringSecurityAnnotations(controller)) {
            return ControllerAssessment.unsupported(SPRING_SECURITY_CONTROLLER);
        }
        if (inspectResponseEntity && usesResponseEntity(controller)) {
            return ControllerAssessment.unsupported(RESPONSE_ENTITY_CONTROLLER);
        }
        if (hasUnsafeConstructorShape(controller)) {
            return ControllerAssessment.unsupported(UNSUPPORTED_CONSTRUCTOR);
        }
        if (hasUnsupportedClassAnnotations(controller)) {
            return ControllerAssessment.unsupported(SPRING_BEHAVIOR_CONTROLLER);
        }
        if (hasUnsupportedFieldAnnotations(controller)) {
            return ControllerAssessment.unsupported(SPRING_BEHAVIOR_CONTROLLER);
        }

        J.Annotation classMapping = find(controller.getLeadingAnnotations(), REQUEST_MAPPING);
        if (classMapping != null && hasUnverifiedPathSyntax(classMapping, stringConstants,
                sourcePath, compilationUnit)) {
            return ControllerAssessment.unsupported(UNVERIFIED_PATH_SYNTAX);
        }
        if (classMapping != null && !classPath(classMapping).supported) {
            return ControllerAssessment.unsupported(UNSUPPORTED_CONTROLLER);
        }

        for (J statement : controller.getBody().getStatements()) {
            if (!(statement instanceof J.MethodDeclaration)) {
                continue;
            }
            J.MethodDeclaration method = (J.MethodDeclaration) statement;
            int mappingCount = 0;
            J.Annotation methodMapping = null;
            for (J.Annotation annotation : method.getLeadingAnnotations()) {
                if (isMethodMapping(annotation)) {
                    mappingCount++;
                    methodMapping = annotation;
                }
            }
            if (methodMapping != null && hasUnverifiedPathSyntax(methodMapping, stringConstants,
                    sourcePath, compilationUnit)) {
                return ControllerAssessment.unsupported(UNVERIFIED_PATH_SYNTAX);
            }
            if (mappingCount > 1 || (methodMapping != null && !mapping(methodMapping).supported)) {
                return ControllerAssessment.unsupported(UNSUPPORTED_CONTROLLER);
            }
            if (methodMapping != null && hasModifier(method, J.Modifier.Type.Final)) {
                return ControllerAssessment.unsupported(FINAL_RESOURCE_METHOD);
            }
            if (hasUnsupportedMethodAnnotations(method)) {
                return ControllerAssessment.unsupported(SPRING_BEHAVIOR_CONTROLLER);
            }
            if (methodMapping != null && isUnsupportedReturnType(method.getReturnTypeExpression() == null ?
                    null : method.getReturnTypeExpression().getType())) {
                return ControllerAssessment.unsupported(UNSUPPORTED_RETURN_TYPE);
            }
            if (hasUnsupportedBindings(method, methodMapping != null)) {
                return ControllerAssessment.unsupported(UNSUPPORTED_CONTROLLER);
            }
            if (methodMapping != null && isVoidReturnType(method.getReturnTypeExpression() == null ?
                    null : method.getReturnTypeExpression().getType())) {
                return ControllerAssessment.unsupported(VOID_RETURN_TYPE);
            }
            if (methodMapping != null &&
                    (!hasModifier(method, J.Modifier.Type.Public) ||
                     hasModifier(method, J.Modifier.Type.Static) ||
                     hasModifier(method, J.Modifier.Type.Abstract))) {
                return ControllerAssessment.unsupported(NON_PUBLIC_RESOURCE_METHOD);
            }
        }
        if (!topLevel || controller.getKind() != J.ClassDeclaration.Kind.Type.Class ||
                !hasModifier(controller, J.Modifier.Type.Public) ||
                hasModifier(controller, J.Modifier.Type.Abstract)) {
            return ControllerAssessment.unsupported(UNSUPPORTED_RESOURCE_CLASS);
        }
        return ControllerAssessment.supported();
    }

    private static boolean hasModifier(J.ClassDeclaration controller, J.Modifier.Type modifierType) {
        for (J.Modifier modifier : controller.getModifiers()) {
            if (modifier.getType() == modifierType) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasModifier(J.MethodDeclaration method, J.Modifier.Type modifierType) {
        for (J.Modifier modifier : method.getModifiers()) {
            if (modifier.getType() == modifierType) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUnsupportedBindings(J.MethodDeclaration method, boolean mappedMethod) {
        for (org.openrewrite.java.tree.Statement parameterStatement : method.getParameters()) {
            if (!(parameterStatement instanceof J.VariableDeclarations)) {
                continue;
            }
            J.VariableDeclarations variables = (J.VariableDeclarations) parameterStatement;
            boolean hasExplicitEndpointRole = false;
            for (J.Annotation annotation : variables.getLeadingAnnotations()) {
                if (REQUEST_BODY.matches(annotation)) {
                    hasExplicitEndpointRole = true;
                    if (!mappedMethod || !isRequiredFalse(annotation) || isPrimitive(variables)) {
                        return true;
                    }
                    continue;
                }

                Binding parameterBinding = binding(annotation);
                if (parameterBinding != null) {
                    hasExplicitEndpointRole = true;
                    if (!mappedMethod || !isSupportedBinding(annotation, parameterBinding, variables)) {
                        return true;
                    }
                    continue;
                }

                JavaType.FullyQualified annotationType = TypeUtils.asFullyQualified(annotation.getType());
                if (annotationType != null && isJakartaRestParameterAnnotation(
                        annotationType.getFullyQualifiedName())) {
                    hasExplicitEndpointRole = true;
                    continue;
                }
                if (annotationType != null && annotationType.getFullyQualifiedName()
                        .startsWith("org.springframework.web.bind.annotation.")) {
                    return true;
                }
                if (isSpringBehaviorAnnotation(annotation)) {
                    return true;
                }
            }
            if (mappedMethod && !hasExplicitEndpointRole) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJakartaRestParameterAnnotation(String fullyQualifiedName) {
        return "jakarta.ws.rs.BeanParam".equals(fullyQualifiedName) ||
               "jakarta.ws.rs.CookieParam".equals(fullyQualifiedName) ||
               "jakarta.ws.rs.FormParam".equals(fullyQualifiedName) ||
               "jakarta.ws.rs.HeaderParam".equals(fullyQualifiedName) ||
               "jakarta.ws.rs.MatrixParam".equals(fullyQualifiedName) ||
               "jakarta.ws.rs.PathParam".equals(fullyQualifiedName) ||
               "jakarta.ws.rs.QueryParam".equals(fullyQualifiedName) ||
               "jakarta.ws.rs.core.Context".equals(fullyQualifiedName);
    }

    private static boolean isSupportedBinding(J.Annotation annotation, Binding parameterBinding,
                                                J.VariableDeclarations variables) {
        if (parameterBinding.kind == BindingKind.HEADER && isWholeHeadersType(variables)) {
            return false;
        }
        if (!isSupportedJakartaRestScalar(variables.getType())) {
            return false;
        }
        Parameter parameter = parameter(annotation, parameterBinding.kind);
        if (!parameter.supported) {
            return false;
        }
        // Spring applies a declared default to both absent and empty request values, while
        // Jakarta REST @DefaultValue only guarantees substitution for an absent/null value.
        if (parameter.defaultValue != null) {
            return false;
        }
        if ((parameterBinding.kind == BindingKind.QUERY || parameterBinding.kind == BindingKind.HEADER) &&
                parameter.required && parameter.defaultValue == null) {
            return false;
        }
        if (!parameter.required && parameter.defaultValue == null && isPrimitive(variables)) {
            return false;
        }
        return parameter.name != null || variables.getVariables().size() == 1;
    }

    private static boolean isSupportedJakartaRestScalar(JavaType type) {
        if (type == JavaType.Primitive.Boolean || type == JavaType.Primitive.Byte ||
                type == JavaType.Primitive.Short || type == JavaType.Primitive.Int ||
                type == JavaType.Primitive.Long || type == JavaType.Primitive.Float ||
                type == JavaType.Primitive.Double) {
            return true;
        }
        return TypeUtils.isOfClassType(type, "java.lang.String") ||
               TypeUtils.isOfClassType(type, "java.lang.Boolean") ||
               TypeUtils.isOfClassType(type, "java.lang.Byte") ||
               TypeUtils.isOfClassType(type, "java.lang.Short") ||
               TypeUtils.isOfClassType(type, "java.lang.Integer") ||
               TypeUtils.isOfClassType(type, "java.lang.Long") ||
               TypeUtils.isOfClassType(type, "java.lang.Float") ||
               TypeUtils.isOfClassType(type, "java.lang.Double");
    }

    private static boolean isWholeHeadersType(J.VariableDeclarations variables) {
        return TypeUtils.isAssignableTo("java.util.Map", variables.getType()) ||
               TypeUtils.isOfClassType(variables.getType(), "org.springframework.http.HttpHeaders");
    }

    private static boolean hasUnsupportedFieldAnnotations(J.ClassDeclaration controller) {
        for (J statement : controller.getBody().getStatements()) {
            if (!(statement instanceof J.VariableDeclarations)) {
                continue;
            }
            for (J.Annotation annotation : ((J.VariableDeclarations) statement).getLeadingAnnotations()) {
                if (isSpringBehaviorAnnotation(annotation)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasUnsupportedClassAnnotations(J.ClassDeclaration controller) {
        for (J.Annotation annotation : controller.getLeadingAnnotations()) {
            if (REST_CONTROLLER.matches(annotation) || REQUEST_MAPPING.matches(annotation)) {
                continue;
            }
            if (isSpringBehaviorAnnotation(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUnsupportedMethodAnnotations(J.MethodDeclaration method) {
        for (J.Annotation annotation : method.getLeadingAnnotations()) {
            if (isMethodMapping(annotation)) {
                continue;
            }
            if (isSpringBehaviorAnnotation(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSpringBehaviorAnnotation(J.Annotation annotation) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
        if (type == null) {
            return false;
        }
        String name = type.getFullyQualifiedName();
        if ("org.springframework.transaction.annotation.Transactional".equals(name) ||
                "org.springframework.beans.factory.annotation.Value".equals(name)) {
            return false;
        }
        return hasTypeOrMetaAnnotationInPackage(type,
                "org.springframework.", new HashSet<String>());
    }

    private static boolean isUnsupportedReturnType(JavaType returnType) {
        if (returnType == null) {
            return false;
        }
        for (String unsupportedType : UNSUPPORTED_RETURN_TYPES) {
            if (TypeUtils.isAssignableTo(unsupportedType, returnType) ||
                    TypeUtils.isOfClassType(returnType, unsupportedType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVoidReturnType(JavaType returnType) {
        return returnType == JavaType.Primitive.Void ||
               TypeUtils.isOfClassType(returnType, "java.lang.Void");
    }

    private static boolean hasUnsafeConstructorShape(J.ClassDeclaration controller) {
        boolean hasExplicitConstructor = false;
        boolean hasEligibleNoArgConstructor = false;
        for (J statement : controller.getBody().getStatements()) {
            if (!(statement instanceof J.MethodDeclaration) ||
                    !((J.MethodDeclaration) statement).isConstructor()) {
                continue;
            }
            J.MethodDeclaration constructor = (J.MethodDeclaration) statement;
            hasExplicitConstructor = true;
            if (constructorParameterCount(constructor) == 0 &&
                    hasModifier(constructor, J.Modifier.Type.Public)) {
                hasEligibleNoArgConstructor = true;
            }
        }
        if (hasEligibleNoArgConstructor) {
            return false;
        }
        return hasExplicitConstructor || hasLombokConstructorGenerator(controller);
    }

    private static boolean hasLombokConstructorGenerator(J.ClassDeclaration controller) {
        for (J.Annotation annotation : controller.getLeadingAnnotations()) {
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
            if (type == null) {
                continue;
            }
            String name = type.getFullyQualifiedName();
            if ("lombok.AllArgsConstructor".equals(name) ||
                    "lombok.Builder".equals(name) ||
                    "lombok.Data".equals(name) ||
                    "lombok.NoArgsConstructor".equals(name) ||
                    "lombok.RequiredArgsConstructor".equals(name) ||
                    "lombok.Value".equals(name) ||
                    "lombok.experimental.SuperBuilder".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static int constructorParameterCount(J.MethodDeclaration constructor) {
        int count = 0;
        for (org.openrewrite.java.tree.Statement statement : constructor.getParameters()) {
            if (statement instanceof J.VariableDeclarations) {
                count += ((J.VariableDeclarations) statement).getVariables().size();
            }
        }
        return count;
    }

    private static boolean hasSpringSecurityAnnotations(J.ClassDeclaration controller) {
        if (hasSpringSecurityAnnotation(controller.getLeadingAnnotations())) {
            return true;
        }
        for (J statement : controller.getBody().getStatements()) {
            if (statement instanceof J.MethodDeclaration &&
                    hasSpringSecurityAnnotation(((J.MethodDeclaration) statement).getLeadingAnnotations())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSpringFrameworkContract(J.ClassDeclaration controller) {
        JavaType.FullyQualified controllerType = TypeUtils.asFullyQualified(controller.getType());
        if (controllerType == null) {
            return false;
        }
        Set<String> visited = new HashSet<String>();
        for (JavaType.FullyQualified implemented : controllerType.getInterfaces()) {
            if (hasTypeHierarchyInPackage(implemented, "org.springframework.", visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTypeHierarchyInPackage(JavaType.FullyQualified type,
                                                      String packagePrefix,
                                                      Set<String> visited) {
        String name = type.getFullyQualifiedName();
        if (name.startsWith(packagePrefix)) {
            return true;
        }
        if (!visited.add(name)) {
            return false;
        }
        JavaType.FullyQualified supertype = type.getSupertype();
        if (supertype != null && hasTypeHierarchyInPackage(supertype, packagePrefix, visited)) {
            return true;
        }
        for (JavaType.FullyQualified implemented : type.getInterfaces()) {
            if (hasTypeHierarchyInPackage(implemented, packagePrefix, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSpringSecurityAnnotation(List<J.Annotation> annotations) {
        for (J.Annotation annotation : annotations) {
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
            if (type != null && hasTypeOrMetaAnnotationInPackage(type,
                    "org.springframework.security.", new HashSet<String>())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTypeOrMetaAnnotationInPackage(JavaType.FullyQualified type,
                                                             String packagePrefix,
                                                             Set<String> visited) {
        String name = type.getFullyQualifiedName();
        if (name.startsWith(packagePrefix)) {
            return true;
        }
        if (!visited.add(name)) {
            return false;
        }
        for (JavaType.FullyQualified metaAnnotation : type.getAnnotations()) {
            if (hasTypeOrMetaAnnotationInPackage(metaAnnotation, packagePrefix, visited)) {
                return true;
            }
        }
        return false;
    }

    static boolean usesResponseEntity(J.ClassDeclaration controller) {
        final AtomicBoolean found = new AtomicBoolean(false);
        new ResponseEntityVisitor(controller.getId()).visit(controller, found);
        return found.get();
    }

    private static boolean isResponseEntityType(JavaType type) {
        return TypeUtils.isAssignableTo(SPRING_RESPONSE, type) ||
               TypeUtils.isOfClassType(type, SPRING_RESPONSE);
    }

    private static J.Annotation find(List<J.Annotation> annotations, AnnotationMatcher matcher) {
        for (J.Annotation annotation : annotations) {
            if (matcher.matches(annotation)) {
                return annotation;
            }
        }
        return null;
    }

    private static boolean isMethodMapping(J.Annotation annotation) {
        return GET_MAPPING.matches(annotation) || POST_MAPPING.matches(annotation) ||
               PUT_MAPPING.matches(annotation) || DELETE_MAPPING.matches(annotation) ||
               PATCH_MAPPING.matches(annotation) || REQUEST_MAPPING.matches(annotation);
    }

    private static J.Annotation findMethodMapping(List<J.Annotation> annotations) {
        for (J.Annotation annotation : annotations) {
            if (isMethodMapping(annotation)) {
                return annotation;
            }
        }
        return null;
    }

    private static Mapping mapping(J.Annotation annotation) {
        String verb;
        String springType;
        if (GET_MAPPING.matches(annotation)) {
            verb = "GET";
            springType = "org.springframework.web.bind.annotation.GetMapping";
        } else if (POST_MAPPING.matches(annotation)) {
            verb = "POST";
            springType = "org.springframework.web.bind.annotation.PostMapping";
        } else if (PUT_MAPPING.matches(annotation)) {
            verb = "PUT";
            springType = "org.springframework.web.bind.annotation.PutMapping";
        } else if (DELETE_MAPPING.matches(annotation)) {
            verb = "DELETE";
            springType = "org.springframework.web.bind.annotation.DeleteMapping";
        } else if (PATCH_MAPPING.matches(annotation)) {
            verb = "PATCH";
            springType = "org.springframework.web.bind.annotation.PatchMapping";
        } else {
            verb = null;
            springType = "org.springframework.web.bind.annotation.RequestMapping";
        }

        Expression path = null;
        List<Expression> arguments = annotation.getArguments();
        if (arguments != null) {
            for (Expression argument : arguments) {
                if (!(argument instanceof J.Assignment) && path == null) {
                    path = singleStringValue(argument);
                    if (path == null) {
                        return Mapping.unsupported(springType);
                    }
                } else if (argument instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) argument;
                    String name = assignment.getVariable().printTrimmed();
                    if ("value".equals(name) || "path".equals(name)) {
                        if (path != null) {
                            return Mapping.unsupported(springType);
                        }
                        path = singleStringValue(assignment.getAssignment());
                        if (path == null) {
                            return Mapping.unsupported(springType);
                        }
                    } else if ("method".equals(name) && REQUEST_MAPPING.matches(annotation)) {
                        verb = requestMethod(assignment.getAssignment());
                        if (verb == null) {
                            return Mapping.unsupported(springType);
                        }
                    } else {
                        return Mapping.unsupported(springType);
                    }
                } else {
                    return Mapping.unsupported(springType);
                }
            }
        }
        if (verb == null) {
            return Mapping.unsupported(springType);
        }
        return new Mapping(verb, path, springType, true);
    }

    private static Mapping classPath(J.Annotation annotation) {
        Expression path = null;
        List<Expression> arguments = annotation.getArguments();
        if (arguments != null) {
            for (Expression argument : arguments) {
                if (!(argument instanceof J.Assignment) && path == null) {
                    path = singleStringValue(argument);
                } else if (argument instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) argument;
                    String name = assignment.getVariable().printTrimmed();
                    if (("value".equals(name) || "path".equals(name)) && path == null) {
                        path = singleStringValue(assignment.getAssignment());
                    } else {
                        return Mapping.classPath(path, false);
                    }
                } else {
                    return Mapping.classPath(path, false);
                }
                if (path == null) {
                    return Mapping.classPath(null, false);
                }
            }
        }
        return Mapping.classPath(path, true);
    }

    private static boolean hasUnverifiedPathSyntax(J.Annotation annotation,
                                                   StringConstantProjectIndex.State stringConstants,
                                                   Path sourcePath,
                                                   J.CompilationUnit compilationUnit) {
        List<Expression> arguments = annotation.getArguments();
        if (arguments == null) {
            return false;
        }
        for (Expression argument : arguments) {
            Expression candidate = null;
            if (!(argument instanceof J.Assignment)) {
                candidate = argument;
            } else {
                J.Assignment assignment = (J.Assignment) argument;
                String attribute = assignment.getVariable().printTrimmed();
                if ("value".equals(attribute) || "path".equals(attribute)) {
                    candidate = assignment.getAssignment();
                }
            }
            if (candidate != null && containsUnverifiedPath(candidate, stringConstants,
                    sourcePath, compilationUnit)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsUnverifiedPath(Expression expression,
                                                  StringConstantProjectIndex.State stringConstants,
                                                  Path sourcePath,
                                                  J.CompilationUnit compilationUnit) {
        if (expression instanceof J.NewArray) {
            List<Expression> initializer = ((J.NewArray) expression).getInitializer();
            if (initializer == null) {
                return true;
            }
            for (Expression element : initializer) {
                if (containsUnverifiedPath(element, stringConstants, sourcePath, compilationUnit)) {
                    return true;
                }
            }
            return false;
        }
        String path = StringConstantProjectIndex.resolve(expression, stringConstants,
                sourcePath, compilationUnit);
        return path == null || !isPortableJakartaPath(path);
    }

    private static boolean isPortableJakartaPath(String path) {
        if (path.contains("//")) {
            return false;
        }
        int index = 0;
        while (index < path.length()) {
            char current = path.charAt(index);
            if (isPortableLiteralPathCharacter(current)) {
                index++;
                continue;
            }
            if (current != '{') {
                return false;
            }
            int closingBrace = path.indexOf('}', index + 1);
            if (closingBrace < 0 || !isSimpleTemplateName(path.substring(index + 1, closingBrace))) {
                return false;
            }
            index = closingBrace + 1;
        }
        return true;
    }

    private static boolean isPortableLiteralPathCharacter(char character) {
        return character >= 'a' && character <= 'z' ||
               character >= 'A' && character <= 'Z' ||
               character >= '0' && character <= '9' ||
               character == '/' || character == '-' || character == '_' ||
               character == '.' || character == '~';
    }

    private static boolean isSimpleTemplateName(String name) {
        if (name.isEmpty() || !(name.charAt(0) >= 'a' && name.charAt(0) <= 'z' ||
                name.charAt(0) >= 'A' && name.charAt(0) <= 'Z' || name.charAt(0) == '_')) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char character = name.charAt(i);
            if (!(character >= 'a' && character <= 'z' ||
                    character >= 'A' && character <= 'Z' ||
                    character >= '0' && character <= '9' || character == '_')) {
                return false;
            }
        }
        return true;
    }

    private static Parameter parameter(J.Annotation annotation, BindingKind kind) {
        Expression name = null;
        Expression defaultValue = null;
        boolean required = true;
        List<Expression> arguments = annotation.getArguments();
        if (arguments == null || arguments.isEmpty()) {
            return new Parameter(null, null, true, true);
        }
        for (Expression argument : arguments) {
            if (!(argument instanceof J.Assignment) && name == null) {
                name = singleStringValue(argument);
                if (name == null) {
                    return Parameter.unsupported();
                }
            } else if (argument instanceof J.Assignment) {
                J.Assignment assignment = (J.Assignment) argument;
                String attribute = assignment.getVariable().printTrimmed();
                if (("value".equals(attribute) || "name".equals(attribute)) && name == null) {
                    name = singleStringValue(assignment.getAssignment());
                    if (name == null) {
                        return Parameter.unsupported();
                    }
                } else if ("required".equals(attribute)) {
                    Boolean requiredValue = booleanLiteral(assignment.getAssignment());
                    if (requiredValue == null || (kind == BindingKind.PATH && !requiredValue.booleanValue())) {
                        return Parameter.unsupported();
                    }
                    required = requiredValue.booleanValue();
                } else if ("defaultValue".equals(attribute) &&
                           (kind == BindingKind.QUERY || kind == BindingKind.HEADER) && defaultValue == null) {
                    defaultValue = singleStringValue(assignment.getAssignment());
                    if (defaultValue == null) {
                        return Parameter.unsupported();
                    }
                    required = false;
                } else {
                    return Parameter.unsupported();
                }
            } else {
                return Parameter.unsupported();
            }
        }
        return new Parameter(name, defaultValue, required, true);
    }

    private static boolean isRequiredFalse(J.Annotation annotation) {
        List<Expression> arguments = annotation.getArguments();
        return arguments != null && arguments.size() == 1 && arguments.get(0) instanceof J.Assignment &&
               "required".equals(((J.Assignment) arguments.get(0)).getVariable().printTrimmed()) &&
               Boolean.FALSE.equals(booleanLiteral(((J.Assignment) arguments.get(0)).getAssignment()));
    }

    private static Boolean booleanLiteral(Expression expression) {
        return expression instanceof J.Literal && ((J.Literal) expression).getValue() instanceof Boolean ?
                (Boolean) ((J.Literal) expression).getValue() : null;
    }

    private static Expression singleStringValue(Expression expression) {
        if (expression instanceof J.Literal && ((J.Literal) expression).getValue() instanceof String) {
            return expression;
        }
        if (expression instanceof J.NewArray) {
            List<Expression> initializer = ((J.NewArray) expression).getInitializer();
            if (initializer != null && initializer.size() == 1) {
                return singleStringValue(initializer.get(0));
            }
        }
        return TypeUtils.isOfClassType(expression.getType(), "java.lang.String") ||
               expression.getType() == org.openrewrite.java.tree.JavaType.Primitive.String ? expression : null;
    }

    private static boolean isPrimitive(J.VariableDeclarations variables) {
        return variables.getType() instanceof org.openrewrite.java.tree.JavaType.Primitive &&
               variables.getType() != org.openrewrite.java.tree.JavaType.Primitive.String;
    }

    private static Binding binding(J.Annotation annotation) {
        if (REQUEST_PARAM.matches(annotation)) {
            return new Binding(BindingKind.QUERY, "QueryParam", "jakarta.ws.rs.QueryParam");
        }
        if (PATH_VARIABLE.matches(annotation)) {
            return new Binding(BindingKind.PATH, "PathParam", "jakarta.ws.rs.PathParam");
        }
        if (REQUEST_HEADER.matches(annotation)) {
            return new Binding(BindingKind.HEADER, "HeaderParam", "jakarta.ws.rs.HeaderParam");
        }
        return null;
    }

    private static String requestMethod(Expression expression) {
        if (expression instanceof J.NewArray) {
            List<Expression> initializer = ((J.NewArray) expression).getInitializer();
            if (initializer == null || initializer.size() != 1) {
                return null;
            }
            expression = initializer.get(0);
        }
        String printed = expression.printTrimmed();
        int dot = printed.lastIndexOf('.');
        String method = dot < 0 ? printed : printed.substring(dot + 1);
        if ("GET".equals(method) || "POST".equals(method) || "PUT".equals(method) ||
                "DELETE".equals(method) || "PATCH".equals(method)) {
            return method;
        }
        return null;
    }

    private static J.Literal stringLiteral(String value) {
        return new J.Literal(
                java.util.UUID.randomUUID(),
                org.openrewrite.java.tree.Space.EMPTY,
                org.openrewrite.marker.Markers.EMPTY,
                value,
                '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"',
                null,
                org.openrewrite.java.tree.JavaType.Primitive.String
        );
    }

    private static final class Mapping {
        private final String verb;
        private final Expression path;
        private final String springType;
        private final boolean supported;

        private Mapping(String verb, Expression path, String springType, boolean supported) {
            this.verb = verb;
            this.path = path;
            this.springType = springType;
            this.supported = supported;
        }

        private static Mapping unsupported(String springType) {
            return new Mapping(null, null, springType, false);
        }

        private static Mapping classPath(Expression path, boolean supported) {
            return new Mapping(null, path, "org.springframework.web.bind.annotation.RequestMapping", supported);
        }
    }

    private static final class Parameter {
        private final Expression name;
        private final Expression defaultValue;
        private final boolean required;
        private final boolean supported;

        private Parameter(Expression name, Expression defaultValue, boolean required, boolean supported) {
            this.name = name;
            this.defaultValue = defaultValue;
            this.required = required;
            this.supported = supported;
        }

        private static Parameter unsupported() {
            return new Parameter(null, null, true, false);
        }
    }

    private static final class ControllerAssessment {
        private final boolean supported;
        private final String message;

        private ControllerAssessment(boolean supported, String message) {
            this.supported = supported;
            this.message = message;
        }

        private static ControllerAssessment supported() {
            return new ControllerAssessment(true, null);
        }

        private static ControllerAssessment unsupported(String message) {
            return new ControllerAssessment(false, message);
        }
    }

    static final class ControllerPreflight {
        final boolean controller;
        final boolean supported;
        final String message;

        private ControllerPreflight(boolean controller, boolean supported, String message) {
            this.controller = controller;
            this.supported = supported;
            this.message = message;
        }

        private static ControllerPreflight notController() {
            return new ControllerPreflight(false, true, null);
        }

        private static ControllerPreflight controller(boolean supported, String message) {
            return new ControllerPreflight(true, supported, message);
        }
    }

    static final class Accumulator {
        private final SpringSecurityProjectGate.State springSecurity =
                SpringSecurityProjectGate.newAccumulator();
        private final SpringWebProjectGate.State springWeb =
                SpringWebProjectGate.newAccumulator();
        private final StringConstantProjectIndex.State stringConstants =
                StringConstantProjectIndex.newAccumulator();
    }

    private static final class ResponseEntityVisitor extends JavaIsoVisitor<AtomicBoolean> {
        private final java.util.UUID controllerId;

        private ResponseEntityVisitor(java.util.UUID controllerId) {
            this.controllerId = controllerId;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl,
                                                         AtomicBoolean responseFound) {
            if (!controllerId.equals(classDecl.getId())) {
                return classDecl;
            }
            return super.visitClassDeclaration(classDecl, responseFound);
        }

        @Override
        public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean responseFound) {
            J.Identifier id = super.visitIdentifier(identifier, responseFound);
            if (isResponseEntityType(id.getType())) {
                responseFound.set(true);
            }
            return id;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
                                                         AtomicBoolean responseFound) {
            J.MethodInvocation invocation = super.visitMethodInvocation(method, responseFound);
            JavaType.Method methodType = invocation.getMethodType();
            if (methodType != null && (isResponseEntityType(methodType.getDeclaringType()) ||
                    isResponseEntityType(methodType.getReturnType()))) {
                responseFound.set(true);
            }
            return invocation;
        }
    }

    private enum BindingKind {
        QUERY,
        PATH,
        HEADER
    }

    private static final class Binding {
        private final BindingKind kind;
        private final String targetSimpleName;
        private final String targetType;

        private Binding(BindingKind kind, String targetSimpleName, String targetType) {
            this.kind = kind;
            this.targetSimpleName = targetSimpleName;
            this.targetType = targetType;
        }
    }
}
