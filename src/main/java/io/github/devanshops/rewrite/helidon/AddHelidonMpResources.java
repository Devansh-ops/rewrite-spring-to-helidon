package io.github.devanshops.rewrite.helidon;

import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.text.PlainText;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Adds the standard resources needed to bootstrap an executable Helidon MP module. */
public class AddHelidonMpResources extends ScanningRecipe<AddHelidonMpResources.Accumulator> {
    private static final String SPRING_BOOT_APPLICATION =
            "org.springframework.boot.autoconfigure.SpringBootApplication";
    private static final String SPRING_APPLICATION = "org.springframework.boot.SpringApplication";
    private static final MethodMatcher SPRING_APPLICATION_RUN =
            new MethodMatcher("org.springframework.boot.SpringApplication run(..)");

    private static final String BEANS_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<beans xmlns=\"https://jakarta.ee/xml/ns/jakartaee\"\n" +
            "       xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
            "       xsi:schemaLocation=\"https://jakarta.ee/xml/ns/jakartaee\n" +
            "                           https://jakarta.ee/xml/ns/jakartaee/beans_4_0.xsd\"\n" +
            "       version=\"4.0\"\n" +
            "       bean-discovery-mode=\"annotated\">\n" +
            "</beans>\n";

    private static final String MICROPROFILE_CONFIG =
            "# Helidon MP / MicroProfile Config\n" +
            "# No defaults are generated: migrate each Spring property only after semantic review.\n";

    @Override
    public String getDisplayName() {
        return "Add Helidon MP bootstrap resources";
    }

    @Override
    public String getDescription() {
        return "Adds missing CDI bean discovery and MicroProfile Config resources to executable Spring Boot modules.";
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator accumulator) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree preVisit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }

                SourceFile sourceFile = (SourceFile) tree;
                Path sourcePath = sourceFile.getSourcePath().normalize();
                accumulator.recordExistingSourcePath(sourcePath);
                if (sourceFile instanceof J.CompilationUnit) {
                    Path moduleRoot = moduleRoot(sourcePath);
                    if (moduleRoot != null && isSpringBootApplication((J.CompilationUnit) sourceFile)) {
                        accumulator.recordExecutableModuleRoot(moduleRoot);
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(
            Accumulator accumulator,
            Collection<SourceFile> generatedInThisCycle,
            ExecutionContext ctx) {
        Set<Path> unavailablePaths = new HashSet<Path>(accumulator.existingSourcePaths);
        for (SourceFile sourceFile : generatedInThisCycle) {
            unavailablePaths.add(sourceFile.getSourcePath().normalize());
        }

        List<Path> moduleRoots = new ArrayList<Path>(accumulator.executableModuleRoots);
        Collections.sort(moduleRoots, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return left.toString().compareTo(right.toString());
            }
        });

        List<SourceFile> generated = new ArrayList<SourceFile>();
        for (Path moduleRoot : moduleRoots) {
            addIfMissing(generated, unavailablePaths,
                    moduleRoot.resolve("src/main/resources/META-INF/beans.xml"), BEANS_XML);
            addIfMissing(generated, unavailablePaths,
                    moduleRoot.resolve("src/main/resources/META-INF/microprofile-config.properties"),
                    MICROPROFILE_CONFIG);
        }
        return generated;
    }

    static final class Accumulator {
        private final Set<Path> executableModuleRoots = Collections.newSetFromMap(
                new ConcurrentHashMap<Path, Boolean>());
        private final Set<Path> existingSourcePaths = Collections.newSetFromMap(
                new ConcurrentHashMap<Path, Boolean>());

        void recordExecutableModuleRoot(Path moduleRoot) {
            executableModuleRoots.add(moduleRoot);
        }

        void recordExistingSourcePath(Path sourcePath) {
            existingSourcePaths.add(sourcePath);
        }

        int executableModuleRootCount() {
            return executableModuleRoots.size();
        }

        int existingSourcePathCount() {
            return existingSourcePaths.size();
        }
    }

    private static void addIfMissing(
            List<SourceFile> generated,
            Set<Path> unavailablePaths,
            Path sourcePath,
            String text) {
        Path normalizedPath = sourcePath.normalize();
        if (unavailablePaths.add(normalizedPath)) {
            generated.add(PlainText.builder()
                    .sourcePath(normalizedPath)
                    .text(text)
                    .build());
        }
    }

    private static Path moduleRoot(Path sourcePath) {
        for (int i = 0; i + 2 < sourcePath.getNameCount(); i++) {
            if ("src".equals(sourcePath.getName(i).toString()) &&
                    "main".equals(sourcePath.getName(i + 1).toString()) &&
                    "java".equals(sourcePath.getName(i + 2).toString())) {
                return i == 0 ? Paths.get("") : sourcePath.subpath(0, i);
            }
        }
        return null;
    }

    private static boolean isSpringBootApplication(J.CompilationUnit cu) {
        final boolean importsBootApplication = imports(cu, SPRING_BOOT_APPLICATION);
        final boolean importsSpringApplication = imports(cu, SPRING_APPLICATION);
        final boolean[] found = new boolean[1];

        new JavaIsoVisitor<boolean[]>() {
            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, boolean[] detected) {
                J.Annotation a = super.visitAnnotation(annotation, detected);
                if (TypeUtils.isOfClassType(a.getType(), SPRING_BOOT_APPLICATION) ||
                        SPRING_BOOT_APPLICATION.equals(a.getAnnotationType().printTrimmed()) ||
                        (importsBootApplication && "SpringBootApplication".equals(a.getSimpleName()))) {
                    detected[0] = true;
                }
                return a;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(
                    J.MethodInvocation method,
                    boolean[] detected) {
                J.MethodInvocation m = super.visitMethodInvocation(method, detected);
                if (SPRING_APPLICATION_RUN.matches(m) || isImportedSpringApplicationRun(m, importsSpringApplication)) {
                    detected[0] = true;
                }
                return m;
            }
        }.visit(cu, found);

        return found[0];
    }

    private static boolean imports(J.CompilationUnit cu, String fullyQualifiedType) {
        String packageWildcard = fullyQualifiedType.substring(0, fullyQualifiedType.lastIndexOf('.')) + ".*";
        for (J.Import anImport : cu.getImports()) {
            if (!anImport.isStatic() &&
                    (fullyQualifiedType.equals(anImport.getTypeName()) ||
                            packageWildcard.equals(anImport.getTypeName()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isImportedSpringApplicationRun(
            J.MethodInvocation method,
            boolean importsSpringApplication) {
        if (!"run".equals(method.getSimpleName()) || method.getSelect() == null) {
            return false;
        }
        String select = method.getSelect().printTrimmed();
        return SPRING_APPLICATION.equals(select) ||
                (importsSpringApplication && "SpringApplication".equals(select));
    }
}
