package io.github.devanshops.rewrite.helidon;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;
import org.openrewrite.marker.SearchResult;

import java.time.Duration;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Converts common Spring {@code ResponseEntity} builders to Jakarta REST {@code Response}. */
public class MigrateResponseEntityToJakartaResponse extends
        ScanningRecipe<MigrateResponseEntityToJakartaResponse.Accumulator> {
    private static final String SPRING_RESPONSE = "org.springframework.http.ResponseEntity";
    private static final String JAKARTA_RESPONSE = "jakarta.ws.rs.core.Response";
    private static final String UNSUPPORTED_RESPONSE_USE =
            "Manual migration: this file mixes ResponseEntity with unsupported APIs; no response types were changed";
    private static final String BLOCKED_SPRING_CONTROLLER =
            "Manual migration: ResponseEntity conversion was deferred because this file contains a Spring MVC " +
            "controller that cannot migrate atomically";
    private static final String OUTSIDE_REST_SCOPE =
            "Manual migration: ResponseEntity conversion is limited to a proven Spring MVC or Jakarta REST " +
            "resource; no response types were changed";
    private static final String PROJECT_SPRING_SECURITY =
            "Manual migration: ResponseEntity conversion was deferred because Spring Security is present " +
            "in this migration scope";
    private static final String PROJECT_SPRING_WEB_INFRASTRUCTURE =
            "Manual migration: ResponseEntity conversion was deferred because Spring Web or servlet runtime " +
            "infrastructure is present in this migration scope";
    private static final MethodMatcher RESPONSE_FACTORY =
            new MethodMatcher("org.springframework.http.ResponseEntity *(..)", true);
    private static final Map<String, Integer> HTTP_STATUS_CODES = statusCodes();

    @Override
    public String getDisplayName() {
        return "Migrate Spring `ResponseEntity` to Jakarta REST `Response`";
    }

    @Override
    public String getDescription() {
        return "Converts common `ok`, `status`, `badRequest`, `notFound`, `noContent`, `accepted`, " +
               "and `created` response builders, including standard `HttpStatus` constants.";
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(7);
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
        return Preconditions.check(new UsesType<>(SPRING_RESPONSE, false),
                new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                Conversion conversion = conversion(method);
                if (conversion != null) {
                    if (!conversion.supported) {
                        return SearchResult.found(method,
                                "Manual migration: ResponseEntity headers, dynamic HttpStatusCode, or an " +
                                "unsupported builder chain needs Jakarta REST review");
                    }
                    maybeRemoveImport("org.springframework.http.HttpStatus");
                    maybeRemoveImport("org.springframework.http.HttpStatusCode");
                    maybeAddImport(JAKARTA_RESPONSE, false);
                    if (conversion.kind == Kind.OK) {
                        return template("Response.ok(#{any()}).build()")
                                .apply(getCursor(), method.getCoordinates().replace(), conversion.entity);
                    }
                    if (conversion.kind == Kind.NO_CONTENT) {
                        return template("Response.noContent().build()")
                                .apply(getCursor(), method.getCoordinates().replace());
                    }
                    if (conversion.kind == Kind.CREATED) {
                        if (conversion.entity == null) {
                            return template("Response.created(#{any(java.net.URI)}).build()")
                                    .apply(getCursor(), method.getCoordinates().replace(), conversion.location);
                        }
                        return template("Response.created(#{any(java.net.URI)}).entity(#{any()}).build()")
                                .apply(getCursor(), method.getCoordinates().replace(), conversion.location,
                                        conversion.entity);
                    }
                    if (conversion.entity == null) {
                        return template("Response.status(#{any()}).build()")
                                .apply(getCursor(), method.getCoordinates().replace(), conversion.status);
                    }
                    return template("Response.status(#{any()}).entity(#{any()}).build()")
                            .apply(getCursor(), method.getCoordinates().replace(), conversion.status,
                                    conversion.entity);
                }

                J visited = super.visitMethodInvocation(method, ctx);
                if (visited instanceof J.MethodInvocation && isResponseFactory((J.MethodInvocation) visited) &&
                        !isSelectOfParent(method)) {
                    return SearchResult.found(visited,
                            "Manual migration: unsupported ResponseEntity factory or incomplete builder");
                }
                return visited;
            }

            @Override
            public J visitCompilationUnit(J.CompilationUnit compilationUnit, ExecutionContext ctx) {
                Path sourcePath = compilationUnit.getSourcePath();
                if (!isSafeCompilationUnit(compilationUnit)) {
                    return markAtomicRefusal(compilationUnit, UNSUPPORTED_RESPONSE_USE);
                }
                if (hasSpringControllerThatMustRemainAtomic(compilationUnit,
                        accumulator.stringConstants, sourcePath)) {
                    return markAtomicRefusal(compilationUnit, BLOCKED_SPRING_CONTROLLER);
                }
                if (SpringSecurityProjectGate.isBlocked(accumulator.springSecurity, sourcePath)) {
                    return markAtomicRefusal(compilationUnit, PROJECT_SPRING_SECURITY);
                }
                if (SpringWebProjectGate.isBlocked(accumulator.springWeb, sourcePath)) {
                    return markAtomicRefusal(compilationUnit, PROJECT_SPRING_WEB_INFRASTRUCTURE);
                }
                if (hasResponseUseOutsideProvenRestResource(compilationUnit,
                        accumulator.stringConstants, sourcePath)) {
                    return markAtomicRefusal(compilationUnit, OUTSIDE_REST_SCOPE);
                }
                J cu = super.visitCompilationUnit(compilationUnit, ctx);
                doAfterVisit(new ChangeType(SPRING_RESPONSE, JAKARTA_RESPONSE, false).getVisitor());
                doAfterVisit(new DropJakartaResponseTypeParameters());
                return cu;
            }

            private JavaTemplate template(String source) {
                return HelidonJavaTemplate.builder(source).imports(JAKARTA_RESPONSE).build();
            }

            private boolean isSelectOfParent(J.MethodInvocation method) {
                Object parent = getCursor().getParentTreeCursor().getValue();
                return parent instanceof J.MethodInvocation &&
                       ((J.MethodInvocation) parent).getSelect() == method;
            }
        });
    }

    private static Conversion conversion(J.MethodInvocation outer) {
        if (isResponseFactory(outer)) {
            String name = outer.getSimpleName();
            Expression entity = singleArgument(outer);
            if ("ok".equals(name) && entity != null) {
                return Conversion.ok(entity);
            }
            return null;
        }
        if (!(outer.getSelect() instanceof J.MethodInvocation)) {
            return null;
        }
        J.MethodInvocation root = (J.MethodInvocation) outer.getSelect();
        if (!isResponseFactory(root)) {
            // A second builder hop (headers/contentType/etc.) is intentionally not rewritten yet.
            if (root.getSelect() instanceof J.MethodInvocation &&
                    isResponseFactory((J.MethodInvocation) root.getSelect())) {
                return Conversion.unsupported();
            }
            return null;
        }
        String terminal = outer.getSimpleName();
        if (!("body".equals(terminal) || "build".equals(terminal))) {
            return Conversion.unsupported();
        }
        Expression entity = "body".equals(terminal) ? singleArgument(outer) : null;
        if (("body".equals(terminal) && entity == null) ||
                ("build".equals(terminal) && !hasNoArguments(outer))) {
            return Conversion.unsupported();
        }

        String factory = root.getSimpleName();
        if ("ok".equals(factory) && hasNoArguments(root)) {
            return entity == null ? Conversion.status(200, null) : Conversion.ok(entity);
        }
        if ("noContent".equals(factory) && hasNoArguments(root) && entity == null) {
            return Conversion.noContent();
        }
        if ("badRequest".equals(factory) && hasNoArguments(root)) {
            return Conversion.status(400, entity);
        }
        if ("notFound".equals(factory) && hasNoArguments(root) && entity == null) {
            return Conversion.status(404, null);
        }
        if ("accepted".equals(factory) && hasNoArguments(root)) {
            return Conversion.status(202, entity);
        }
        Expression factoryArgument = singleArgument(root);
        if ("created".equals(factory) && factoryArgument != null) {
            return Conversion.created(factoryArgument, entity);
        }
        if ("status".equals(factory) && factoryArgument != null) {
            Expression status = status(factoryArgument);
            return status == null ? Conversion.unsupported() : Conversion.status(status, entity);
        }
        return Conversion.unsupported();
    }

    private static boolean isResponseFactory(J.MethodInvocation invocation) {
        if (RESPONSE_FACTORY.matches(invocation)) {
            return true;
        }
        JavaType.Method type = invocation.getMethodType();
        return type != null && TypeUtils.isOfClassType(type.getDeclaringType(), SPRING_RESPONSE);
    }

    private static Expression status(Expression expression) {
        if (expression instanceof J.Literal && ((J.Literal) expression).getValue() instanceof Number) {
            return expression;
        }
        if (expression.getType() == JavaType.Primitive.Int ||
                TypeUtils.isOfClassType(expression.getType(), "java.lang.Integer")) {
            return expression;
        }
        if (expression instanceof J.MethodInvocation) {
            J.MethodInvocation invocation = (J.MethodInvocation) expression;
            JavaType.Method methodType = invocation.getMethodType();
            Expression valueOfArgument = singleArgument(invocation);
            if ("valueOf".equals(invocation.getSimpleName()) && valueOfArgument != null &&
                    methodType != null && TypeUtils.isOfClassType(methodType.getDeclaringType(),
                            "org.springframework.http.HttpStatusCode")) {
                Expression code = valueOfArgument;
                if (code.getType() == JavaType.Primitive.Int ||
                        TypeUtils.isOfClassType(code.getType(), "java.lang.Integer")) {
                    return code;
                }
            }
        }
        if (!TypeUtils.isOfClassType(expression.getType(), "org.springframework.http.HttpStatus")) {
            return null;
        }
        String printed = expression.printTrimmed();
        int dot = printed.lastIndexOf('.');
        String constant = dot < 0 ? printed : printed.substring(dot + 1);
        Integer code = HTTP_STATUS_CODES.get(constant);
        return code == null ? null : intLiteral(code);
    }

    /**
     * Type replacement is compilation-unit atomic. A partial replacement can turn an unsupported
     * builder into invalid Jakarta REST source, so one unsupported use keeps the whole file intact.
     */
    private static boolean isSafeCompilationUnit(J.CompilationUnit compilationUnit) {
        final AtomicBoolean safe = new AtomicBoolean(true);
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, AtomicBoolean atomicSafe) {
                boolean selectedByParent = isSelectOfParent(getCursor(), method);
                J.MethodInvocation m = super.visitMethodInvocation(method, atomicSafe);
                if (!atomicSafe.get() || selectedByParent) {
                    return m;
                }
                if (involvesSpringResponse(m)) {
                    Conversion conversion = conversion(m);
                    if (conversion == null || !conversion.supported) {
                        atomicSafe.set(false);
                    }
                }
                return m;
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(
                    J.VariableDeclarations variables,
                    AtomicBoolean atomicSafe) {
                J.VariableDeclarations v = super.visitVariableDeclarations(variables, atomicSafe);
                if (TypeUtils.isAssignableTo(SPRING_RESPONSE, v.getType())) {
                    atomicSafe.set(false);
                }
                return v;
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, AtomicBoolean atomicSafe) {
                J.NewClass n = super.visitNewClass(newClass, atomicSafe);
                if (TypeUtils.isAssignableTo(SPRING_RESPONSE, n.getType())) {
                    atomicSafe.set(false);
                }
                return n;
            }

            @Override
            public J.MemberReference visitMemberReference(J.MemberReference memberReference,
                                                           AtomicBoolean atomicSafe) {
                J.MemberReference reference = super.visitMemberReference(memberReference, atomicSafe);
                JavaType.Method methodType = reference.getMethodType();
                if (methodType != null &&
                        (TypeUtils.isAssignableTo(SPRING_RESPONSE, methodType.getDeclaringType()) ||
                         TypeUtils.isAssignableTo(SPRING_RESPONSE, methodType.getReturnType()))) {
                    atomicSafe.set(false);
                }
                return reference;
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration,
                                                             AtomicBoolean atomicSafe) {
                J.ClassDeclaration declaration = super.visitClassDeclaration(classDeclaration, atomicSafe);
                if (TypeUtils.isAssignableTo(SPRING_RESPONSE, declaration.getType())) {
                    atomicSafe.set(false);
                }
                return declaration;
            }
        }.visit(compilationUnit, safe);
        return safe.get();
    }

    private static boolean hasSpringControllerThatMustRemainAtomic(
            final J.CompilationUnit compilationUnit,
            final StringConstantProjectIndex.State stringConstants,
            final Path sourcePath) {
        final AtomicBoolean blocked = new AtomicBoolean(false);
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration,
                                                             AtomicBoolean controllerBlocked) {
                boolean topLevel = getCursor().getParentTreeCursor()
                        .firstEnclosing(J.ClassDeclaration.class) == null;
                MigrateSpringMvcToJakartaRest.ControllerPreflight preflight =
                        MigrateSpringMvcToJakartaRest.classLocalPreflightBeforeResponseMigration(
                                classDeclaration, topLevel, stringConstants, sourcePath, compilationUnit);
                if (preflight.controller && !preflight.supported) {
                    controllerBlocked.set(true);
                    return classDeclaration;
                }
                return super.visitClassDeclaration(classDeclaration, controllerBlocked);
            }
        }.visit(compilationUnit, blocked);
        return blocked.get();
    }

    private static boolean hasResponseUseOutsideProvenRestResource(
            final J.CompilationUnit compilationUnit,
            final StringConstantProjectIndex.State stringConstants,
            final Path sourcePath) {
        final AtomicBoolean outsideRestScope = new AtomicBoolean(false);
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration,
                                                             AtomicBoolean outsideScope) {
                boolean topLevel = getCursor().getParentTreeCursor()
                        .firstEnclosing(J.ClassDeclaration.class) == null;
                MigrateSpringMvcToJakartaRest.ControllerPreflight preflight =
                        MigrateSpringMvcToJakartaRest.classLocalPreflightBeforeResponseMigration(
                                classDeclaration, topLevel, stringConstants, sourcePath, compilationUnit);
                if (MigrateSpringMvcToJakartaRest.usesResponseEntity(classDeclaration) &&
                        !(preflight.controller && preflight.supported) &&
                        !isProvenJakartaRestResource(classDeclaration, topLevel)) {
                    outsideScope.set(true);
                    return classDeclaration;
                }
                return super.visitClassDeclaration(classDeclaration, outsideScope);
            }
        }.visit(compilationUnit, outsideRestScope);
        return outsideRestScope.get();
    }

    private static boolean isProvenJakartaRestResource(J.ClassDeclaration classDeclaration,
                                                        boolean topLevel) {
        if (!topLevel || classDeclaration.getKind() != J.ClassDeclaration.Kind.Type.Class ||
                !hasModifier(classDeclaration, J.Modifier.Type.Public) ||
                hasModifier(classDeclaration, J.Modifier.Type.Abstract) ||
                hasModifier(classDeclaration, J.Modifier.Type.Final) ||
                classDeclaration.getExtends() != null) {
            return false;
        }
        boolean hasPath = false;
        for (J.Annotation annotation : classDeclaration.getLeadingAnnotations()) {
            if (TypeUtils.isOfClassType(annotation.getType(), "jakarta.ws.rs.Path")) {
                hasPath = true;
                break;
            }
        }
        if (!hasPath) {
            return false;
        }

        boolean hasExplicitConstructor = false;
        for (J statement : classDeclaration.getBody().getStatements()) {
            if (!(statement instanceof J.MethodDeclaration) ||
                    !((J.MethodDeclaration) statement).isConstructor()) {
                continue;
            }
            hasExplicitConstructor = true;
            J.MethodDeclaration constructor = (J.MethodDeclaration) statement;
            if (constructorParameterCount(constructor) == 0 &&
                    hasModifier(constructor, J.Modifier.Type.Public)) {
                return true;
            }
        }
        return !hasExplicitConstructor;
    }

    private static int constructorParameterCount(J.MethodDeclaration constructor) {
        int count = 0;
        for (org.openrewrite.java.tree.Statement parameter : constructor.getParameters()) {
            if (parameter instanceof J.VariableDeclarations) {
                count += ((J.VariableDeclarations) parameter).getVariables().size();
            }
        }
        return count;
    }

    private static boolean hasModifier(J.ClassDeclaration classDeclaration,
                                       J.Modifier.Type modifierType) {
        for (J.Modifier modifier : classDeclaration.getModifiers()) {
            if (modifier.getType() == modifierType) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasModifier(J.MethodDeclaration method,
                                       J.Modifier.Type modifierType) {
        for (J.Modifier modifier : method.getModifiers()) {
            if (modifier.getType() == modifierType) {
                return true;
            }
        }
        return false;
    }

    private static boolean involvesSpringResponse(J.MethodInvocation invocation) {
        if (isResponseFactory(invocation) || chainContainsResponseFactory(invocation)) {
            return true;
        }
        JavaType.Method type = invocation.getMethodType();
        return type != null &&
               (TypeUtils.isAssignableTo(SPRING_RESPONSE, type.getDeclaringType()) ||
                TypeUtils.isAssignableTo(SPRING_RESPONSE, type.getReturnType()));
    }

    private static boolean chainContainsResponseFactory(J.MethodInvocation invocation) {
        Expression select = invocation.getSelect();
        while (select instanceof J.MethodInvocation) {
            J.MethodInvocation selectedInvocation = (J.MethodInvocation) select;
            if (isResponseFactory(selectedInvocation)) {
                return true;
            }
            select = selectedInvocation.getSelect();
        }
        return false;
    }

    private static boolean isSelectOfParent(org.openrewrite.Cursor cursor, J.MethodInvocation method) {
        Object parent = cursor.getParentTreeCursor().getValue();
        return parent instanceof J.MethodInvocation && ((J.MethodInvocation) parent).getSelect() == method;
    }

    private static J.CompilationUnit markAtomicRefusal(J.CompilationUnit compilationUnit, String reason) {
        final boolean[] marked = new boolean[1];
        J.CompilationUnit cu = compilationUnit.withImports(ListUtils.map(compilationUnit.getImports(), anImport -> {
            if (!marked[0] && SPRING_RESPONSE.equals(anImport.getTypeName())) {
                marked[0] = true;
                return SearchResult.found(anImport, reason);
            }
            return anImport;
        }));
        return marked[0] ? cu : SearchResult.found(cu, reason);
    }

    private static J.Literal intLiteral(int value) {
        return new J.Literal(UUID.randomUUID(), Space.EMPTY, Markers.EMPTY, value,
                Integer.toString(value), null, JavaType.Primitive.Int);
    }

    private static boolean hasNoArguments(J.MethodInvocation invocation) {
        List<Expression> arguments = invocation.getArguments();
        return arguments.isEmpty() || (arguments.size() == 1 && arguments.get(0) instanceof J.Empty);
    }

    private static Expression singleArgument(J.MethodInvocation invocation) {
        List<Expression> arguments = invocation.getArguments();
        return arguments.size() == 1 && !(arguments.get(0) instanceof J.Empty) ? arguments.get(0) : null;
    }

    private static Map<String, Integer> statusCodes() {
        Map<String, Integer> codes = new HashMap<String, Integer>();
        codes.put("OK", 200);
        codes.put("CREATED", 201);
        codes.put("ACCEPTED", 202);
        codes.put("NON_AUTHORITATIVE_INFORMATION", 203);
        codes.put("NO_CONTENT", 204);
        codes.put("RESET_CONTENT", 205);
        codes.put("PARTIAL_CONTENT", 206);
        codes.put("MULTI_STATUS", 207);
        codes.put("ALREADY_REPORTED", 208);
        codes.put("IM_USED", 226);
        codes.put("MULTIPLE_CHOICES", 300);
        codes.put("MOVED_PERMANENTLY", 301);
        codes.put("FOUND", 302);
        codes.put("NOT_MODIFIED", 304);
        codes.put("TEMPORARY_REDIRECT", 307);
        codes.put("PERMANENT_REDIRECT", 308);
        codes.put("BAD_REQUEST", 400);
        codes.put("UNAUTHORIZED", 401);
        codes.put("FORBIDDEN", 403);
        codes.put("NOT_FOUND", 404);
        codes.put("METHOD_NOT_ALLOWED", 405);
        codes.put("NOT_ACCEPTABLE", 406);
        codes.put("PROXY_AUTHENTICATION_REQUIRED", 407);
        codes.put("REQUEST_TIMEOUT", 408);
        codes.put("CONFLICT", 409);
        codes.put("GONE", 410);
        codes.put("LENGTH_REQUIRED", 411);
        codes.put("PRECONDITION_FAILED", 412);
        codes.put("PAYLOAD_TOO_LARGE", 413);
        codes.put("URI_TOO_LONG", 414);
        codes.put("UNSUPPORTED_MEDIA_TYPE", 415);
        codes.put("REQUESTED_RANGE_NOT_SATISFIABLE", 416);
        codes.put("EXPECTATION_FAILED", 417);
        codes.put("I_AM_A_TEAPOT", 418);
        codes.put("MISDIRECTED_REQUEST", 421);
        codes.put("UNPROCESSABLE_ENTITY", 422);
        codes.put("LOCKED", 423);
        codes.put("FAILED_DEPENDENCY", 424);
        codes.put("TOO_EARLY", 425);
        codes.put("UPGRADE_REQUIRED", 426);
        codes.put("PRECONDITION_REQUIRED", 428);
        codes.put("TOO_MANY_REQUESTS", 429);
        codes.put("REQUEST_HEADER_FIELDS_TOO_LARGE", 431);
        codes.put("UNAVAILABLE_FOR_LEGAL_REASONS", 451);
        codes.put("INTERNAL_SERVER_ERROR", 500);
        codes.put("NOT_IMPLEMENTED", 501);
        codes.put("BAD_GATEWAY", 502);
        codes.put("SERVICE_UNAVAILABLE", 503);
        codes.put("GATEWAY_TIMEOUT", 504);
        codes.put("HTTP_VERSION_NOT_SUPPORTED", 505);
        codes.put("VARIANT_ALSO_NEGOTIATES", 506);
        codes.put("INSUFFICIENT_STORAGE", 507);
        codes.put("LOOP_DETECTED", 508);
        codes.put("BANDWIDTH_LIMIT_EXCEEDED", 509);
        codes.put("NOT_EXTENDED", 510);
        codes.put("NETWORK_AUTHENTICATION_REQUIRED", 511);
        return Collections.unmodifiableMap(codes);
    }

    private static final class DropJakartaResponseTypeParameters extends JavaVisitor<ExecutionContext> {
        @Override
        public J visitParameterizedType(J.ParameterizedType parameterizedType, ExecutionContext ctx) {
            if (TypeUtils.isOfClassType(parameterizedType.getType(), JAKARTA_RESPONSE)) {
                return ((J) parameterizedType.getClazz()).withPrefix(parameterizedType.getPrefix());
            }
            return super.visitParameterizedType(parameterizedType, ctx);
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

    private enum Kind {
        OK,
        STATUS,
        NO_CONTENT,
        CREATED
    }

    private static final class Conversion {
        private final Kind kind;
        private final Expression status;
        private final Expression entity;
        private final Expression location;
        private final boolean supported;

        private Conversion(Kind kind, Expression status, Expression entity, Expression location,
                           boolean supported) {
            this.kind = kind;
            this.status = status;
            this.entity = entity;
            this.location = location;
            this.supported = supported;
        }

        private static Conversion ok(Expression entity) {
            return new Conversion(Kind.OK, null, entity, null, true);
        }

        private static Conversion status(int status, Expression entity) {
            return status(intLiteral(status), entity);
        }

        private static Conversion status(Expression status, Expression entity) {
            return new Conversion(Kind.STATUS, status, entity, null, true);
        }

        private static Conversion noContent() {
            return new Conversion(Kind.NO_CONTENT, null, null, null, true);
        }

        private static Conversion created(Expression location, Expression entity) {
            return new Conversion(Kind.CREATED, null, entity, location, true);
        }

        private static Conversion unsupported() {
            return new Conversion(Kind.STATUS, null, null, null, false);
        }
    }
}
