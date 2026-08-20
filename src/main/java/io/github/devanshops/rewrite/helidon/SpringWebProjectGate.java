package io.github.devanshops.rewrite.helidon;

import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Module-scoped gate for Spring Web and servlet runtime semantics which cannot be inferred from a
 * controller in isolation.
 *
 * <p>The allowlist is deliberately narrow: it contains only the Spring MVC annotations migrated by
 * {@link MigrateSpringMvcToJakartaRest} and the response types migrated by
 * {@link MigrateResponseEntityToJakartaResponse}. Every other attributed Spring Web, Spring HTTP,
 * or servlet contract keeps all controllers in that module on Spring.</p>
 */
final class SpringWebProjectGate {
    private static final String SPRING_WEB = "org.springframework.web.";
    private static final String SPRING_HTTP = "org.springframework.http.";
    private static final String SPRING_BOOT_SERVLET = "org.springframework.boot.web.servlet.";
    private static final String SPRING_BOOT_WEB_AUTOCONFIGURE =
            "org.springframework.boot.autoconfigure.web.";
    private static final String JAKARTA_SERVLET = "jakarta.servlet.";
    private static final String JAVAX_SERVLET = "javax.servlet.";

    private static final Set<String> ALLOWLIST = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(
                    "org.springframework.web.bind.annotation.RestController",
                    "org.springframework.web.bind.annotation.RequestMapping",
                    "org.springframework.web.bind.annotation.GetMapping",
                    "org.springframework.web.bind.annotation.PostMapping",
                    "org.springframework.web.bind.annotation.PutMapping",
                    "org.springframework.web.bind.annotation.DeleteMapping",
                    "org.springframework.web.bind.annotation.PatchMapping",
                    "org.springframework.web.bind.annotation.RequestParam",
                    "org.springframework.web.bind.annotation.PathVariable",
                    "org.springframework.web.bind.annotation.RequestHeader",
                    "org.springframework.web.bind.annotation.RequestBody",
                    "org.springframework.web.bind.annotation.RequestMethod",
                    "org.springframework.http.ResponseEntity",
                    "org.springframework.http.HttpStatus"
            )));

    private SpringWebProjectGate() {
    }

    static State newAccumulator() {
        return new State();
    }

    static boolean isBlocked(State state, Path sourcePath) {
        return state.blockedModules.contains(SpringSecurityProjectGate.moduleRoot(sourcePath));
    }

    static void scanSource(SourceFile sourceFile, State state) {
        if (SpringSecurityProjectGate.isTestSource(sourceFile.getSourcePath())) {
            return;
        }
        if (sourceFile instanceof J.CompilationUnit && scanJava((J.CompilationUnit) sourceFile)) {
            state.blockedModules.add(SpringSecurityProjectGate.moduleRoot(sourceFile.getSourcePath()));
        }
    }

    private static boolean scanJava(J.CompilationUnit compilationUnit) {
        for (J.Import anImport : compilationUnit.getImports()) {
            String importedType = anImport.getTypeName();
            if (isTargetName(importedType) && !isAllowlistedReference(importedType)) {
                return true;
            }
        }

        final AtomicBoolean detected = new AtomicBoolean(false);
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean found) {
                J.Identifier id = super.visitIdentifier(identifier, found);
                if (containsUnsupportedWebType(id.getType(), new HashSet<String>(), 0) ||
                        id.getFieldType() != null && containsUnsupportedWebType(
                                id.getFieldType().getType(), new HashSet<String>(), 0)) {
                    found.set(true);
                }
                return id;
            }

            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, AtomicBoolean found) {
                J.FieldAccess access = super.visitFieldAccess(fieldAccess, found);
                if (containsUnsupportedWebType(access.getType(), new HashSet<String>(), 0)) {
                    found.set(true);
                } else if (access.getType() == null) {
                    String printed = access.printTrimmed();
                    if (isTargetName(printed) && !isAllowlistedReference(printed)) {
                        found.set(true);
                    }
                }
                return access;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
                                                              AtomicBoolean found) {
                J.MethodInvocation invocation = super.visitMethodInvocation(method, found);
                if (containsUnsupportedWebMethod(invocation.getMethodType())) {
                    found.set(true);
                }
                return invocation;
            }

            @Override
            public J.MemberReference visitMemberReference(J.MemberReference memberReference,
                                                           AtomicBoolean found) {
                J.MemberReference reference = super.visitMemberReference(memberReference, found);
                if (containsUnsupportedWebMethod(reference.getMethodType())) {
                    found.set(true);
                }
                return reference;
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, AtomicBoolean found) {
                J.NewClass created = super.visitNewClass(newClass, found);
                if (containsUnsupportedWebType(created.getType(), new HashSet<String>(), 0)) {
                    found.set(true);
                }
                return created;
            }
        }.visit(compilationUnit, detected);
        return detected.get();
    }

    private static boolean containsUnsupportedWebMethod(JavaType.Method method) {
        if (method == null) {
            return false;
        }
        JavaType.FullyQualified declaringType = TypeUtils.asFullyQualified(method.getDeclaringType());
        if (declaringType != null && isAllowlistedReference(declaringType.getFullyQualifiedName())) {
            // The owning recipe validates the exposed operation and builder chain. Do not reject a
            // supported call because its library signature mentions an internal Spring helper type.
            return false;
        }
        if (containsUnsupportedWebType(method.getDeclaringType(), new HashSet<String>(), 0) ||
                containsUnsupportedWebType(method.getReturnType(), new HashSet<String>(), 0)) {
            return true;
        }
        for (JavaType parameterType : method.getParameterTypes()) {
            if (containsUnsupportedWebType(parameterType, new HashSet<String>(), 0)) {
                return true;
            }
        }
        for (JavaType thrownType : method.getThrownExceptions()) {
            if (containsUnsupportedWebType(thrownType, new HashSet<String>(), 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsUnsupportedWebType(JavaType type, Set<String> visited, int depth) {
        if (depth > 32) {
            return true;
        }
        JavaType.FullyQualified fullyQualified = TypeUtils.asFullyQualified(type);
        if (fullyQualified == null) {
            return false;
        }
        String name = fullyQualified.getFullyQualifiedName();
        if (isAllowlistedReference(name)) {
            // Do not recurse into framework meta-annotations or supertypes of an explicitly supported
            // source API. For example, @RestController is itself meta-annotated with @ResponseBody.
            return false;
        }
        if (isTargetName(name)) {
            return true;
        }
        if (!visited.add(name)) {
            return false;
        }
        JavaType.FullyQualified supertype = fullyQualified.getSupertype();
        if (supertype != null && containsUnsupportedWebType(supertype, visited, depth + 1)) {
            return true;
        }
        for (JavaType.FullyQualified implemented : fullyQualified.getInterfaces()) {
            if (containsUnsupportedWebType(implemented, visited, depth + 1)) {
                return true;
            }
        }
        for (JavaType.FullyQualified annotation : fullyQualified.getAnnotations()) {
            if (containsUnsupportedWebType(annotation, visited, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTargetName(String name) {
        return name.startsWith(SPRING_WEB) || name.startsWith(SPRING_HTTP) ||
               name.startsWith(SPRING_BOOT_SERVLET) ||
               name.startsWith(SPRING_BOOT_WEB_AUTOCONFIGURE) ||
               name.startsWith(JAKARTA_SERVLET) || name.startsWith(JAVAX_SERVLET);
    }

    private static boolean isAllowlistedReference(String name) {
        for (String allowed : ALLOWLIST) {
            if (name.equals(allowed) || name.startsWith(allowed + '.') ||
                    name.startsWith(allowed + '$')) {
                return true;
            }
        }
        return false;
    }

    static final class State {
        private final Set<Path> blockedModules = ConcurrentHashMap.newKeySet();
    }
}
