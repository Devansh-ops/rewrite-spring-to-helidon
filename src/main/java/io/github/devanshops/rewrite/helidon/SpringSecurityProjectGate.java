package io.github.devanshops.rewrite.helidon;

import org.openrewrite.ExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.maven.tree.Scope;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared module-scoped Spring Security presence gate for runtime-replacement recipes. */
final class SpringSecurityProjectGate {
    private static final String SPRING_SECURITY_PACKAGE = "org.springframework.security.";

    private SpringSecurityProjectGate() {
    }

    static State newAccumulator() {
        return new State();
    }

    static boolean isBlocked(State state, Path sourcePath) {
        return state.blockedModules.contains(moduleRoot(sourcePath));
    }

    /** Compatibility bridge while sibling recipes move to the module-scoped API. */
    static boolean isBlocked(AtomicBoolean accumulator) {
        return accumulator.get();
    }

    static TreeVisitor<?, ExecutionContext> scanner(final State state) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree preVisit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    scanSource((SourceFile) tree, state);
                }
                return tree;
            }
        };
    }

    /** Compatibility bridge while sibling recipes move to the module-scoped API. */
    static TreeVisitor<?, ExecutionContext> scanner(final AtomicBoolean accumulator) {
        if (accumulator instanceof State) {
            return scanner((State) accumulator);
        }
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree preVisit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    scanSource((SourceFile) tree, accumulator);
                }
                return tree;
            }
        };
    }

    static void scanSource(SourceFile sourceFile, State state) {
        if (isTestSource(sourceFile.getSourcePath())) {
            return;
        }
        if (scanSourceForSecurity(sourceFile)) {
            state.blockedModules.add(moduleRoot(sourceFile.getSourcePath()));
            state.set(true);
        }
    }

    /** Compatibility bridge while sibling recipes move to the module-scoped API. */
    static void scanSource(SourceFile sourceFile, AtomicBoolean accumulator) {
        if (accumulator instanceof State) {
            scanSource(sourceFile, (State) accumulator);
        } else if (!isTestSource(sourceFile.getSourcePath()) && scanSourceForSecurity(sourceFile)) {
            accumulator.set(true);
        }
    }

    static Path moduleRoot(Path sourcePath) {
        Path normalized = sourcePath.normalize();
        for (int i = 0; i < normalized.getNameCount(); i++) {
            if ("src".equals(normalized.getName(i).toString())) {
                return i == 0 ? Paths.get("") : normalized.subpath(0, i);
            }
        }
        if (isBuildFile(normalized)) {
            Path parent = normalized.getParent();
            return parent == null ? Paths.get("") : parent;
        }
        return Paths.get("");
    }

    private static boolean scanSourceForSecurity(SourceFile sourceFile) {
        if (sourceFile instanceof J.CompilationUnit && scanJava((J.CompilationUnit) sourceFile)) {
            return true;
        }
        if (sourceFile.getMarkers().findFirst(MavenResolutionResult.class).isPresent()) {
            // Resolution provides the effective scope, including inherited dependencies. Falling
            // back to raw text here would incorrectly promote a test-only dependency to runtime.
            return hasResolvedSpringSecurityDependency(sourceFile);
        }
        if (isBuildFile(sourceFile.getSourcePath())) {
            return hasLiteralRuntimeSecurityMarker(sourceFile);
        }
        return false;
    }

    private static boolean hasLiteralRuntimeSecurityMarker(SourceFile sourceFile) {
        String source = sourceFile.printAll().replaceAll("(?s)<!--.*?-->", "");
        Path fileName = sourceFile.getSourcePath().getFileName();
        if (fileName != null && "pom.xml".equals(fileName.toString())) {
            StringBuilder outsideDependencies = new StringBuilder();
            int cursor = 0;
            while (true) {
                int start = source.indexOf("<dependency", cursor);
                if (start < 0) {
                    outsideDependencies.append(source.substring(cursor));
                    break;
                }
                outsideDependencies.append(source, cursor, start);
                int end = source.indexOf("</dependency>", start);
                if (end < 0) {
                    return containsSecurityCoordinate(source.substring(start));
                }
                end += "</dependency>".length();
                String dependency = source.substring(start, end);
                if (containsSecurityCoordinate(dependency) &&
                        !dependency.matches("(?s).*<scope>\\s*test\\s*</scope>.*")) {
                    return true;
                }
                cursor = end;
            }
            return containsSecurityCoordinate(outsideDependencies.toString());
        }

        for (String line : source.split("\\R")) {
            if (!containsSecurityCoordinate(line)) {
                continue;
            }
            String normalized = line.trim().toLowerCase(java.util.Locale.ROOT);
            if (!normalized.matches("test(?:implementation|runtimeonly|compileonly|compile|api|annotationprocessor)\\b.*")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSecurityCoordinate(String source) {
        return source.contains("org.springframework.security") ||
               source.contains("spring-security") ||
               source.contains("spring-boot-starter-security");
    }

    private static boolean hasResolvedSpringSecurityDependency(SourceFile sourceFile) {
        MavenResolutionResult resolution = sourceFile.getMarkers()
                .findFirst(MavenResolutionResult.class)
                .orElse(null);
        if (resolution == null) {
            return false;
        }
        Scope[] classpathScopes = {Scope.Compile, Scope.Runtime, Scope.Provided};
        for (Scope scope : classpathScopes) {
            if (!resolution.findDependencies(SpringSecurityProjectGate::isSpringSecurityDependency, scope)
                    .isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSpringSecurityDependency(ResolvedDependency dependency) {
        String groupId = dependency.getGroupId();
        String artifactId = dependency.getArtifactId();
        return groupId != null && (groupId.equals("org.springframework.security") ||
                                   groupId.startsWith("org.springframework.security.")) ||
               artifactId != null && (artifactId.startsWith("spring-security") ||
                                      artifactId.contains("starter-security"));
    }

    private static boolean scanJava(J.CompilationUnit compilationUnit) {
        for (J.Import anImport : compilationUnit.getImports()) {
            if (anImport.getTypeName().startsWith(SPRING_SECURITY_PACKAGE)) {
                return true;
            }
        }

        final AtomicBoolean detected = new AtomicBoolean(false);
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean found) {
                J.Identifier id = super.visitIdentifier(identifier, found);
                if (containsSpringSecurityType(id.getType(), new HashSet<String>(), 0) ||
                        (id.getFieldType() != null && containsSpringSecurityType(
                                id.getFieldType().getType(), new HashSet<String>(), 0))) {
                    found.set(true);
                }
                return id;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, AtomicBoolean found) {
                J.MethodInvocation invocation = super.visitMethodInvocation(method, found);
                if (containsSpringSecurityMethod(invocation.getMethodType())) {
                    found.set(true);
                }
                return invocation;
            }

            @Override
            public J.MemberReference visitMemberReference(J.MemberReference memberReference,
                                                           AtomicBoolean found) {
                J.MemberReference reference = super.visitMemberReference(memberReference, found);
                if (containsSpringSecurityMethod(reference.getMethodType())) {
                    found.set(true);
                }
                return reference;
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, AtomicBoolean found) {
                J.NewClass created = super.visitNewClass(newClass, found);
                if (containsSpringSecurityType(created.getType(), new HashSet<String>(), 0)) {
                    found.set(true);
                }
                return created;
            }
        }.visit(compilationUnit, detected);
        return detected.get();
    }

    private static boolean containsSpringSecurityMethod(JavaType.Method method) {
        if (method == null) {
            return false;
        }
        if (containsSpringSecurityType(method.getDeclaringType(), new HashSet<String>(), 0) ||
                containsSpringSecurityType(method.getReturnType(), new HashSet<String>(), 0)) {
            return true;
        }
        for (JavaType parameterType : method.getParameterTypes()) {
            if (containsSpringSecurityType(parameterType, new HashSet<String>(), 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSpringSecurityType(JavaType type, Set<String> visited, int depth) {
        if (depth > 32) {
            return true;
        }
        JavaType.FullyQualified fullyQualified = TypeUtils.asFullyQualified(type);
        if (fullyQualified == null) {
            return false;
        }
        String name = fullyQualified.getFullyQualifiedName();
        if (name.startsWith(SPRING_SECURITY_PACKAGE)) {
            return true;
        }
        if (!visited.add(name)) {
            return false;
        }
        JavaType.FullyQualified supertype = fullyQualified.getSupertype();
        if (supertype != null && containsSpringSecurityType(supertype, visited, depth + 1)) {
            return true;
        }
        for (JavaType.FullyQualified implemented : fullyQualified.getInterfaces()) {
            if (containsSpringSecurityType(implemented, visited, depth + 1)) {
                return true;
            }
        }
        for (JavaType.FullyQualified annotation : fullyQualified.getAnnotations()) {
            if (containsSpringSecurityType(annotation, visited, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBuildFile(Path sourcePath) {
        Path fileName = sourcePath.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        return "pom.xml".equals(name) || "build.gradle".equals(name) || "build.gradle.kts".equals(name);
    }

    static boolean isTestSource(Path sourcePath) {
        Path normalized = sourcePath.normalize();
        for (int i = 0; i + 1 < normalized.getNameCount(); i++) {
            if ("src".equals(normalized.getName(i).toString())) {
                String sourceSet = normalized.getName(i + 1).toString();
                return "test".equals(sourceSet) || "it".equals(sourceSet);
            }
        }
        return false;
    }

    static final class State extends AtomicBoolean {
        private static final long serialVersionUID = 1L;
        private final Set<Path> blockedModules = ConcurrentHashMap.newKeySet();
    }
}
