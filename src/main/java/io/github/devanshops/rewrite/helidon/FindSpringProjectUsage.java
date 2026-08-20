package io.github.devanshops.rewrite.helidon;

import io.github.devanshops.rewrite.helidon.table.MigrationAssessmentTable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Cursor;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.groovy.GroovyIsoVisitor;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.kotlin.KotlinIsoVisitor;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.properties.PropertiesIsoVisitor;
import org.openrewrite.properties.tree.Properties;
import org.openrewrite.text.PlainText;
import org.openrewrite.xml.XmlIsoVisitor;
import org.openrewrite.xml.tree.Xml;
import org.openrewrite.yaml.YamlIsoVisitor;
import org.openrewrite.yaml.tree.Yaml;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Performs a read-only, project-wide inventory of Spring migration constructs.
 */
public final class FindSpringProjectUsage extends ScanningRecipe<FindSpringProjectUsage.Accumulator> {

    private static final String SPRING_PREFIX = "org.springframework.";
    private static final String SPRING_XML_NAMESPACE_PREFIX =
            "http://www.springframework.org/schema/";
    private static final String REPORTED_ROWS = FindSpringProjectUsage.class.getName() + ".reportedRows";

    private transient MigrationAssessmentTable projectUsage = new MigrationAssessmentTable(this);

    @Override
    public String getDisplayName() {
        return "Find project-wide Spring usage requiring Helidon migration";
    }

    @Override
    public String getDescription() {
        return "Inventories Spring build, source, configuration, XML, and metadata constructs without changing project semantics or exposing configuration values.";
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
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }
                stopAfterPreVisit();
                SourceFile sourceFile = (SourceFile) tree;
                if (sourceFile instanceof G.CompilationUnit && isGradleBuild(sourceFile.getSourcePath())) {
                    scanGradle((G.CompilationUnit) sourceFile, accumulator);
                } else if (sourceFile instanceof K.CompilationUnit &&
                        isKotlinGradleBuild(sourceFile.getSourcePath())) {
                    scanKotlinGradle((K.CompilationUnit) sourceFile, accumulator);
                } else if (sourceFile instanceof J.CompilationUnit) {
                    scanJava((J.CompilationUnit) sourceFile, accumulator);
                } else if (sourceFile instanceof Xml.Document) {
                    if (sourceFile.getSourcePath().getFileName().toString().equals("pom.xml")) {
                        scanMaven((Xml.Document) sourceFile, accumulator);
                    } else {
                        scanSpringXml((Xml.Document) sourceFile, accumulator);
                    }
                } else if (sourceFile instanceof Properties.File) {
                    if (isSpringFactories(sourceFile.getSourcePath())) {
                        scanSpringFactories((Properties.File) sourceFile, accumulator);
                    } else if (isApplicationProperties(sourceFile.getSourcePath())) {
                        scanApplicationProperties((Properties.File) sourceFile, accumulator);
                    }
                } else if (sourceFile instanceof Yaml.Documents &&
                           isApplicationYaml(sourceFile.getSourcePath())) {
                    scanApplicationYaml((Yaml.Documents) sourceFile, accumulator);
                } else if (sourceFile instanceof PlainText &&
                           isAutoConfigurationImports(sourceFile.getSourcePath())) {
                    scanAutoConfigurationImports((PlainText) sourceFile, accumulator);
                }
                return tree;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(final Accumulator accumulator) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree preVisit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }
                stopAfterPreVisit();
                SourceFile sourceFile = (SourceFile) tree;
                final List<Finding> findings = accumulator.forPath(normalize(sourceFile.getSourcePath()));
                if (findings.isEmpty()) {
                    return tree;
                }
                insertRows(findings, ctx);
                if (sourceFile instanceof G.CompilationUnit) {
                    return new GroovyIsoVisitor<ExecutionContext>() {
                        @Override
                        public J.MethodInvocation visitMethodInvocation(
                                J.MethodInvocation method, ExecutionContext executionContext) {
                            J.MethodInvocation visited = super.visitMethodInvocation(method, executionContext);
                            return mark(visited, findings);
                        }
                    }.visit(sourceFile, ctx);
                }
                if (sourceFile instanceof K.CompilationUnit) {
                    return new KotlinIsoVisitor<ExecutionContext>() {
                        @Override
                        public J.MethodInvocation visitMethodInvocation(
                                J.MethodInvocation method, ExecutionContext executionContext) {
                            J.MethodInvocation visited = super.visitMethodInvocation(method,
                                    executionContext);
                            return mark(visited, findings);
                        }
                    }.visit(sourceFile, ctx);
                }
                if (sourceFile instanceof J.CompilationUnit) {
                    return new JavaIsoVisitor<ExecutionContext>() {
                        @Override
                        public J.Import visitImport(J.Import anImport, ExecutionContext executionContext) {
                            J.Import visited = super.visitImport(anImport, executionContext);
                            return mark(visited, findings);
                        }

                        @Override
                        public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess,
                                                              ExecutionContext executionContext) {
                            J.FieldAccess visited = super.visitFieldAccess(fieldAccess,
                                    executionContext);
                            return mark(visited, findings);
                        }
                    }.visit(sourceFile, ctx);
                }
                if (sourceFile instanceof Xml.Document) {
                    // Non-POM XML may hold values in arbitrary Spring namespaces or neighboring
                    // elements. Keep its findings table-only: a unified-diff context hunk around
                    // even a safe-looking namespace marker could reproduce those values.
                    if (!"pom.xml".equals(sourceFile.getSourcePath().getFileName().toString())) {
                        return sourceFile;
                    }
                    return new XmlIsoVisitor<ExecutionContext>() {
                        @Override
                        public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext executionContext) {
                            Xml.Tag visited = super.visitTag(tag, executionContext);
                            return mark(visited, findings);
                        }
                    }.visit(sourceFile, ctx);
                }
                // Configuration values must never be surfaced by a search-result patch. The
                // assessment table reports property keys and reasons without modifying the file.
                if (sourceFile instanceof Properties.File) {
                    return sourceFile;
                }
                // As with properties, YAML findings are table-only so a dry-run patch cannot
                // reproduce secret or otherwise sensitive configuration values.
                if (sourceFile instanceof Yaml.Documents) {
                    return sourceFile;
                }
                if (sourceFile instanceof PlainText) {
                    // Plain-text registration files may contain application-specific material;
                    // keep the file byte-for-byte unchanged and expose metadata only in the table.
                    return sourceFile;
                }
                return tree;
            }
        };
    }

    private void insertRows(List<Finding> findings, ExecutionContext ctx) {
        Set<String> reported = ctx.computeMessageIfAbsent(REPORTED_ROWS,
                key -> ConcurrentHashMap.newKeySet());
        for (Finding finding : findings) {
            if (reported.add(finding.rowKey())) {
                projectUsage.insertRow(ctx, finding.toRow());
            }
        }
    }

    private static void scanJava(final J.CompilationUnit compilationUnit,
                                 final Accumulator accumulator) {
        final String sourcePath = normalize(compilationUnit.getSourcePath());
        final String sourceKind = javaSourceKind(sourcePath);
        new JavaIsoVisitor<Accumulator>() {
            @Override
            public J.Import visitImport(J.Import anImport, Accumulator acc) {
                J.Import visited = super.visitImport(anImport, acc);
                String typeName = visited.getTypeName();
                if (typeName.startsWith(SPRING_PREFIX)) {
                    acc.add(javaFinding(sourcePath, sourceKind, visited.getId(), typeName));
                }
                return visited;
            }

            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, Accumulator acc) {
                J.FieldAccess visited = super.visitFieldAccess(fieldAccess, acc);
                if (getCursor().firstEnclosing(J.Import.class) != null) {
                    return visited;
                }
                String typeName = fullyQualifiedName(visited.getType());
                if (isSpringType(typeName) && typeName.equals(visited.printTrimmed())) {
                    acc.add(javaFinding(sourcePath, sourceKind, visited.getId(), typeName));
                }
                return visited;
            }
        }.visit(compilationUnit, accumulator);
    }

    private static String fullyQualifiedName(JavaType type) {
        JavaType.FullyQualified fullyQualified = TypeUtils.asFullyQualified(type);
        return fullyQualified == null ? "" : fullyQualified.getFullyQualifiedName();
    }

    private static boolean isSpringType(String typeName) {
        return typeName != null && typeName.startsWith(SPRING_PREFIX);
    }

    private static void scanGradle(final G.CompilationUnit compilationUnit,
                                   final Accumulator accumulator) {
        final String sourcePath = normalize(compilationUnit.getSourcePath());
        new GroovyIsoVisitor<Accumulator>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
                                                            Accumulator acc) {
                J.MethodInvocation visited = super.visitMethodInvocation(method, acc);
                assessGradleMethod(visited, acc, sourcePath, "GRADLE_GROOVY");
                return visited;
            }
        }.visit(compilationUnit, accumulator);
    }

    private static void scanKotlinGradle(final K.CompilationUnit compilationUnit,
                                         final Accumulator accumulator) {
        final String sourcePath = normalize(compilationUnit.getSourcePath());
        new KotlinIsoVisitor<Accumulator>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
                                                            Accumulator acc) {
                J.MethodInvocation visited = super.visitMethodInvocation(method, acc);
                assessGradleMethod(visited, acc, sourcePath, "GRADLE_KOTLIN");
                return visited;
            }
        }.visit(compilationUnit, accumulator);
    }

    private static void assessGradleMethod(J.MethodInvocation method, Accumulator accumulator,
                                           String sourcePath, String sourceKind) {
        String methodName = method.getSimpleName();
        String literal = firstStringArgument(method);
        if ("id".equals(methodName) && literal != null && isSpringPluginId(literal)) {
            accumulator.add(manual(sourcePath, sourceKind, "Spring Gradle plugin",
                    literal, "SPRING_GRADLE_PLUGIN",
                    "No bounded Gradle build migration is selected for this Spring plugin.",
                    "Migrate the Gradle build with an explicit Helidon module policy",
                    method.getId()));
            return;
        }

        String coordinate = literal == null ? null : springCoordinate(literal);
        if (coordinate == null && isGradleDependencyMethod(methodName)) {
            coordinate = springMapCoordinate(method);
        }
        if (coordinate == null) {
            return;
        }
        if ("platform".equals(methodName) || "enforcedPlatform".equals(methodName)) {
            accumulator.add(manual(sourcePath, sourceKind, "Spring Gradle BOM",
                    coordinate, "SPRING_GRADLE_BOM",
                    "No bounded Gradle dependency-management migration is selected.",
                    "Migrate the Gradle build with an explicit Helidon module policy",
                    method.getId()));
        } else if (isGradleDependencyMethod(methodName)) {
            accumulator.add(manual(sourcePath, sourceKind, "Spring Gradle dependency",
                    coordinate, "SPRING_GRADLE_DEPENDENCY",
                    "Removing a Spring dependency requires module-level migration readiness.",
                    "Migrate the Gradle build with an explicit Helidon module policy",
                    method.getId()));
        }
    }

    private static String firstStringArgument(J.MethodInvocation method) {
        if (method.getArguments().isEmpty()) {
            return null;
        }
        Object argument = method.getArguments().get(0);
        if (!(argument instanceof J.Literal)) {
            return null;
        }
        Object value = ((J.Literal) argument).getValue();
        return value instanceof String ? (String) value : null;
    }

    private static String springMapCoordinate(J.MethodInvocation method) {
        List<G.MapEntry> entries = new ArrayList<G.MapEntry>();
        for (org.openrewrite.java.tree.Expression argument : method.getArguments()) {
            if (argument instanceof G.MapEntry) {
                entries.add((G.MapEntry) argument);
            } else if (argument instanceof G.MapLiteral) {
                entries.addAll(((G.MapLiteral) argument).getElements());
            }
        }
        String group = mapString(entries, "group");
        String name = mapString(entries, "name");
        return group != null && name != null && isSpringBuildGroup(group) ?
                group + ':' + name : null;
    }

    private static String mapString(List<G.MapEntry> entries, String expectedKey) {
        for (G.MapEntry entry : entries) {
            String key = entry.getKey().printTrimmed();
            if ((key.startsWith("'") && key.endsWith("'")) ||
                    (key.startsWith("\"") && key.endsWith("\""))) {
                key = key.substring(1, key.length() - 1);
            }
            if (!expectedKey.equals(key) || !(entry.getValue() instanceof J.Literal)) {
                continue;
            }
            Object value = ((J.Literal) entry.getValue()).getValue();
            if (value instanceof String) {
                return (String) value;
            }
        }
        return null;
    }

    private static boolean isSpringPluginId(String pluginId) {
        return pluginId.equals("org.springframework.boot") ||
               pluginId.startsWith("org.springframework.") ||
               pluginId.equals("io.spring.dependency-management") ||
               pluginId.startsWith("io.spring.");
    }

    private static String springCoordinate(String declaration) {
        String[] parts = declaration.split(":");
        if (parts.length < 2 || !isSpringBuildGroup(parts[0]) || parts[1].isEmpty()) {
            return null;
        }
        return parts[0] + ':' + parts[1];
    }

    private static boolean isGradleDependencyMethod(String methodName) {
        return "implementation".equals(methodName) || "api".equals(methodName) ||
               "compileOnly".equals(methodName) || "runtimeOnly".equals(methodName) ||
               "testImplementation".equals(methodName) ||
               "testCompileOnly".equals(methodName) ||
               "testRuntimeOnly".equals(methodName) ||
               "annotationProcessor".equals(methodName) || "classpath".equals(methodName);
    }

    private static boolean isGradleBuild(Path path) {
        return "build.gradle".equals(path.getFileName().toString());
    }

    private static boolean isKotlinGradleBuild(Path path) {
        return "build.gradle.kts".equals(path.getFileName().toString());
    }

    private static Finding javaFinding(String sourcePath, String sourceKind, UUID markerId,
                                       String typeName) {
        if ("org.springframework.transaction.annotation.Transactional".equals(typeName)) {
            return new Finding(sourcePath, sourceKind, "Transactions", typeName,
                    "PARTIAL", "BOUNDED_RECIPE_AVAILABLE", "SPRING_TRANSACTION_ANNOTATION",
                    "A bounded transaction annotation recipe exists, but transaction semantics still require review.",
                    "io.github.devanshops.rewrite.helidon.MigrateSpringTransactionalToJakarta",
                    markerId);
        }
        if (typeName.startsWith("org.springframework.transaction.")) {
            return manual(sourcePath, sourceKind, "Spring transaction infrastructure", typeName,
                    "SPRING_TRANSACTION_INFRASTRUCTURE",
                    "The bounded transaction recipe handles annotations only; this runtime API requires an explicit target design.",
                    "Design explicit Jakarta transaction infrastructure before migration", markerId);
        }
        if (typeName.startsWith("org.springframework.test.") ||
                typeName.startsWith("org.springframework.boot.test.")) {
            return manual(sourcePath, sourceKind, "Spring test context", typeName,
                    "SPRING_TEST_CONTEXT",
                    "Spring test context behavior depends on the target test runtime.",
                    "Migrate to Helidon MP and CDI test support", markerId);
        }
        return manual(sourcePath, sourceKind, "Spring Java API", typeName,
                "SPRING_JAVA_API",
                "No project-wide behavior-preserving migration is established for this Spring API.",
                "Inspect for a Jakarta, MicroProfile, or Helidon equivalent", markerId);
    }

    private static void scanApplicationProperties(final Properties.File properties,
                                                  final Accumulator accumulator) {
        final String sourcePath = normalize(properties.getSourcePath());
        final String sourceKind = applicationPropertiesKind(properties.getSourcePath());
        new PropertiesIsoVisitor<Accumulator>() {
            @Override
            public Properties.Entry visitEntry(Properties.Entry entry, Accumulator acc) {
                Properties.Entry visited = super.visitEntry(entry, acc);
                acc.add(configurationFinding(sourcePath, sourceKind, visited.getId(),
                        visited.getKey(), visited.getValue().getText()));
                return visited;
            }
        }.visit(properties, accumulator);
    }

    private static void scanSpringFactories(final Properties.File properties,
                                            final Accumulator accumulator) {
        final String sourcePath = normalize(properties.getSourcePath());
        new PropertiesIsoVisitor<Accumulator>() {
            @Override
            public Properties.Entry visitEntry(Properties.Entry entry, Accumulator acc) {
                Properties.Entry visited = super.visitEntry(entry, acc);
                acc.add(manual(sourcePath, "SPRING_FACTORIES", "Spring factories metadata",
                        visited.getKey(), "SPRING_FACTORIES_ENTRY",
                        "Spring factories registration depends on Spring runtime discovery semantics.",
                        "Replace registration with explicit CDI discovery or target runtime metadata",
                        visited.getId()));
                return visited;
            }
        }.visit(properties, accumulator);
    }

    private static void scanAutoConfigurationImports(PlainText imports,
                                                     Accumulator accumulator) {
        if (!hasRegistrationLine(imports.getText())) {
            return;
        }
        String sourcePath = normalize(imports.getSourcePath());
        accumulator.add(manual(sourcePath, "SPRING_AUTOCONFIG_IMPORTS",
                "Spring Boot auto-configuration metadata", "AutoConfiguration.imports entry",
                "SPRING_AUTOCONFIG_IMPORTS",
                "Spring Boot auto-configuration registration requires Spring runtime discovery.",
                "Replace registration with explicit CDI discovery or target runtime metadata",
                imports.getId()));
    }

    private static boolean hasRegistrationLine(String text) {
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSpringFactories(Path path) {
        return "spring.factories".equals(path.getFileName().toString()) &&
               normalize(path).contains("/META-INF/");
    }

    private static boolean isAutoConfigurationImports(Path path) {
        return "org.springframework.boot.autoconfigure.AutoConfiguration.imports"
                .equals(path.getFileName().toString()) &&
               normalize(path).contains("/META-INF/spring/");
    }

    private static Finding configurationFinding(String sourcePath, String sourceKind,
                                                UUID markerId, String key, String value) {
        if ("spring.config.import".equals(key)) {
            if (value.contains("configtree:")) {
                return manual(sourcePath, sourceKind, "Spring Config Data", key,
                        "SPRING_CONFIG_TREE_IMPORT",
                        "A Spring Config Tree import requires an explicit target config-source and secret policy.",
                        "Review imports and map each source to MicroProfile Config", markerId);
            }
            return manual(sourcePath, sourceKind, "Spring Config Data", key,
                    "SPRING_CONFIG_IMPORT",
                    "A Spring Config Data import may have ordering, optionality, and source semantics.",
                    "Review imports and map each source to MicroProfile Config", markerId);
        }
        if (key.startsWith("spring.profiles.group.")) {
            return manual(sourcePath, sourceKind, "Spring profiles", key,
                    "SPRING_PROFILE_GROUP",
                    "Spring profile groups require an explicit target environment policy.",
                    "Define an explicit target environment/profile policy", markerId);
        }
        if ("spring.profiles.include".equals(key)) {
            return manual(sourcePath, sourceKind, "Spring profiles", key,
                    "SPRING_PROFILE_INCLUDE",
                    "Included Spring profiles require an explicit target environment policy.",
                    "Define an explicit target environment/profile policy", markerId);
        }
        if ("spring.profiles.active".equals(key) ||
                "spring.config.activate.on-profile".equals(key)) {
            return manual(sourcePath, sourceKind, "Spring profiles", key,
                    "SPRING_PROFILE_ACTIVATION",
                    "Spring profile activation requires an explicit target environment policy.",
                    "Define an explicit target environment/profile policy", markerId);
        }
        if ("spring.config.location".equals(key) ||
                "spring.config.additional-location".equals(key) ||
                "spring.config.name".equals(key)) {
            return manual(sourcePath, sourceKind, "Spring Config Data", key,
                    "SPRING_CONFIG_CUSTOM_LOCATION",
                    "Custom Spring configuration locations have source ordering and loading semantics.",
                    "Review locations and configure equivalent MicroProfile ConfigSources", markerId);
        }
        if (key.startsWith("spring.")) {
            return manual(sourcePath, sourceKind, "Spring configuration", key,
                    "SPRING_CONFIGURATION_KEY",
                    "Spring owns the runtime semantics of this configuration key.",
                    "Map the key to a documented MicroProfile Config contract", markerId);
        }
        return manual(sourcePath, sourceKind, "Application configuration", key,
                "APPLICATION_CONFIGURATION_KEY",
                "Application configuration requires a typed target contract before migration.",
                "Define a typed MicroProfile Config contract", markerId);
    }

    private static boolean isApplicationProperties(Path path) {
        String fileName = path.getFileName().toString();
        return "application.properties".equals(fileName) ||
               (fileName.startsWith("application-") && fileName.endsWith(".properties"));
    }

    private static String applicationPropertiesKind(Path path) {
        return "application.properties".equals(path.getFileName().toString()) ?
                "SPRING_PROPERTIES" : "SPRING_PROPERTIES_PROFILE";
    }

    private static void scanApplicationYaml(final Yaml.Documents documents,
                                            final Accumulator accumulator) {
        final String sourcePath = normalize(documents.getSourcePath());
        final String sourceKind = applicationYamlKind(documents.getSourcePath());
        new YamlIsoVisitor<Accumulator>() {
            @Override
            public Yaml.Mapping.Entry visitMappingEntry(Yaml.Mapping.Entry entry,
                                                        Accumulator acc) {
                Yaml.Mapping.Entry visited = super.visitMappingEntry(entry, acc);
                if (visited.getValue() instanceof Yaml.Mapping) {
                    return visited;
                }
                String value = visited.getValue() instanceof Yaml.Scalar ?
                        ((Yaml.Scalar) visited.getValue()).getValue() : "";
                acc.add(configurationFinding(sourcePath, sourceKind, visited.getId(),
                        yamlKey(getCursor()), value));
                return visited;
            }
        }.visit(documents, accumulator);
    }

    private static String yamlKey(Cursor cursor) {
        List<String> parts = new ArrayList<String>();
        Cursor current = cursor;
        while (current != null) {
            Object value = current.getValue();
            if (value instanceof Yaml.Mapping.Entry) {
                parts.add(0, ((Yaml.Mapping.Entry) value).getKey().getValue());
            }
            current = current.getParent();
        }
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            if (key.length() > 0) {
                key.append('.');
            }
            key.append(part);
        }
        return key.toString();
    }

    private static boolean isApplicationYaml(Path path) {
        String fileName = path.getFileName().toString();
        return "application.yml".equals(fileName) || "application.yaml".equals(fileName) ||
               (fileName.startsWith("application-") &&
                       (fileName.endsWith(".yml") || fileName.endsWith(".yaml")));
    }

    private static String applicationYamlKind(Path path) {
        String fileName = path.getFileName().toString();
        return ("application.yml".equals(fileName) || "application.yaml".equals(fileName)) ?
                "SPRING_YAML" : "SPRING_YAML_PROFILE";
    }

    private static void scanMaven(final Xml.Document document, final Accumulator accumulator) {
        final String sourcePath = normalize(document.getSourcePath());
        new XmlIsoVisitor<Accumulator>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Accumulator acc) {
                Xml.Tag visited = super.visitTag(tag, acc);
                String name = localName(visited.getName());
                if ("parent".equals(name) && hasDirectParentTag(getCursor(), "project")) {
                    addMavenCoordinate(acc, sourcePath, visited, "Spring Maven parent",
                            "SPRING_MAVEN_PARENT");
                } else if ("dependency".equals(name)) {
                    String artifactId = visited.getChildValue("artifactId").orElse("");
                    String type = visited.getChildValue("type").orElse("");
                    String scope = visited.getChildValue("scope").orElse("");
                    if ("spring-boot-dependencies".equals(artifactId) &&
                            "pom".equals(type) && "import".equals(scope)) {
                        addMavenCoordinate(acc, sourcePath, visited, "Spring Maven BOM",
                                "SPRING_MAVEN_BOM");
                    } else {
                        addMavenCoordinate(acc, sourcePath, visited, "Spring Maven dependency",
                                "SPRING_MAVEN_DEPENDENCY");
                    }
                } else if ("plugin".equals(name)) {
                    addMavenCoordinate(acc, sourcePath, visited, "Spring Maven plugin",
                            "SPRING_MAVEN_PLUGIN");
                }
                return visited;
            }
        }.visit(document, accumulator);
    }

    private static void addMavenCoordinate(Accumulator accumulator, String sourcePath,
                                           Xml.Tag tag, String feature, String reasonCode) {
        String groupId = tag.getChildValue("groupId").orElse("");
        String artifactId = tag.getChildValue("artifactId").orElse("");
        if (!isSpringBuildGroup(groupId) || artifactId.isEmpty()) {
            return;
        }
        accumulator.add(new Finding(sourcePath, "MAVEN", feature,
                groupId + ':' + artifactId,
                "PARTIAL", "BOUNDED_RECIPE_AVAILABLE", reasonCode,
                "Helidon build preparation is available, but removing this Spring build construct requires module-level readiness review.",
                "io.github.devanshops.rewrite.helidon.PrepareMavenBuildForHelidonMp",
                tag.getId()));
    }

    private static void scanSpringXml(final Xml.Document document,
                                      final Accumulator accumulator) {
        final String sourcePath = normalize(document.getSourcePath());
        new XmlIsoVisitor<Accumulator>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Accumulator acc) {
                Xml.Tag visited = super.visitTag(tag, acc);
                if (declaresSpringNamespace(visited)) {
                    acc.add(manual(sourcePath, "SPRING_XML", "Spring XML namespace",
                            "Spring XML namespace", "SPRING_XML_NAMESPACE",
                            "Spring XML namespaces indicate container-managed wiring or infrastructure.",
                            "Replace Spring XML wiring with explicit CDI beans and producers",
                            visited.getId()));
                }

                String qualifiedName = visited.getName();
                String name = localName(qualifiedName);
                String namespaceUri = namespaceUri(visited, getCursor());
                if ("bean".equals(name) &&
                        (SPRING_XML_NAMESPACE_PREFIX + "beans").equals(namespaceUri)) {
                    acc.add(manual(sourcePath, "SPRING_XML", "Spring XML bean", "bean",
                            "SPRING_XML_BEAN",
                            "XML bean construction and lifecycle semantics require CDI review.",
                            "Replace XML bean wiring with CDI beans or producers", visited.getId()));
                } else if ("component-scan".equals(name) &&
                        (SPRING_XML_NAMESPACE_PREFIX + "context").equals(namespaceUri)) {
                    acc.add(manual(sourcePath, "SPRING_XML", "Spring XML component scanning",
                            qualifiedName, "SPRING_XML_COMPONENT_SCAN",
                            "Spring package scanning does not establish an equivalent CDI discovery policy.",
                            "Replace package scanning policy with explicit CDI discovery", visited.getId()));
                } else if ("advice".equals(name) &&
                        (SPRING_XML_NAMESPACE_PREFIX + "tx").equals(namespaceUri)) {
                    acc.add(manual(sourcePath, "SPRING_XML", "Spring XML transactions",
                            qualifiedName, "SPRING_XML_TRANSACTION_ADVICE",
                            "The Java annotation recipe does not migrate XML advice, pointcuts, or rollback policy.",
                            "Design XML transaction advice and pointcuts explicitly for Jakarta Transactions",
                            visited.getId()));
                }
                return visited;
            }
        }.visit(document, accumulator);
    }

    private static boolean declaresSpringNamespace(Xml.Tag tag) {
        for (Xml.Attribute attribute : tag.getAttributes()) {
            if (attribute.getKeyAsString().startsWith("xmlns") &&
                    isSpringNamespace(attribute.getValueAsString())) {
                return true;
            }
        }
        return false;
    }

    private static String namespaceUri(Xml.Tag tag, Cursor cursor) {
        String qualifiedName = tag.getName();
        int colon = qualifiedName.indexOf(':');
        String declaration = colon < 0 ? "xmlns" : "xmlns:" + qualifiedName.substring(0, colon);
        String ownNamespace = namespaceAttribute(tag, declaration);
        if (ownNamespace != null) {
            return ownNamespace;
        }
        Cursor parent = cursor.getParentTreeCursor();
        while (parent != null) {
            Object value = parent.getValue();
            if (value instanceof Xml.Tag) {
                String inherited = namespaceAttribute((Xml.Tag) value, declaration);
                if (inherited != null) {
                    return inherited;
                }
            }
            parent = parent.getParentTreeCursor();
        }
        return "";
    }

    private static String namespaceAttribute(Xml.Tag tag, String declaration) {
        for (Xml.Attribute attribute : tag.getAttributes()) {
            if (declaration.equals(attribute.getKeyAsString())) {
                return attribute.getValueAsString();
            }
        }
        return null;
    }

    private static boolean isSpringNamespace(String namespaceUri) {
        return namespaceUri != null && namespaceUri.startsWith(SPRING_XML_NAMESPACE_PREFIX);
    }

    private static boolean isSpringBuildGroup(String groupId) {
        return groupId.equals("org.springframework") ||
               groupId.startsWith("org.springframework.") ||
               groupId.equals("io.spring") || groupId.startsWith("io.spring.");
    }

    private static boolean hasDirectParentTag(Cursor cursor, String name) {
        Cursor parent = cursor.getParentTreeCursor();
        while (parent != null) {
            Object value = parent.getValue();
            if (value instanceof Xml.Tag) {
                return name.equals(localName(((Xml.Tag) value).getName()));
            }
            parent = parent.getParent();
        }
        return false;
    }

    private static String localName(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static Finding manual(String sourcePath, String sourceKind, String feature,
                                  String construct, String reasonCode, String reason,
                                  String direction, UUID markerId) {
        return new Finding(sourcePath, sourceKind, feature, construct,
                "MANUAL", "MANUAL_REVIEW_REQUIRED", reasonCode, reason, direction, markerId);
    }

    private static String javaSourceKind(String sourcePath) {
        String normalized = '/' + sourcePath + '/';
        if (normalized.contains("/src/test/java/")) {
            return "JAVA_TEST";
        }
        if (normalized.contains("/src/main/java/")) {
            return "JAVA_MAIN";
        }
        return "JAVA_SOURCE";
    }

    private static String normalize(Path path) {
        return path.normalize().toString().replace(File.separatorChar, '/');
    }

    private static <T extends Tree> T mark(T tree, List<Finding> findings) {
        for (Finding finding : findings) {
            if (finding.markerId.equals(tree.getId())) {
                String description = finding.markerDescription();
                for (SearchResult existing : tree.getMarkers().findAll(SearchResult.class)) {
                    if (description.equals(existing.getDescription())) {
                        return tree;
                    }
                }
                return SearchResult.found(tree, description);
            }
        }
        return tree;
    }

    static final class Accumulator {
        private final Map<String, List<Finding>> byPath =
                new ConcurrentHashMap<String, List<Finding>>();
        private final Set<String> findingKeys = ConcurrentHashMap.newKeySet();

        void add(Finding finding) {
            if (!findingKeys.add(finding.rowKey())) {
                return;
            }
            byPath.computeIfAbsent(finding.sourcePath,
                    key -> Collections.synchronizedList(new ArrayList<Finding>())).add(finding);
        }

        List<Finding> forPath(String sourcePath) {
            List<Finding> findings = byPath.get(sourcePath);
            return findings == null ? Collections.<Finding>emptyList() : findings;
        }
    }

    private static final class Finding {
        private final String sourcePath;
        private final String sourceKind;
        private final String feature;
        private final String construct;
        private final String supportLevel;
        private final String outcome;
        private final String reasonCode;
        private final String reason;
        private final String suggestedDirection;
        private final UUID markerId;

        private Finding(String sourcePath, String sourceKind, String feature, String construct,
                        String supportLevel, String outcome, String reasonCode, String reason,
                        String suggestedDirection, UUID markerId) {
            this.sourcePath = sourcePath;
            this.sourceKind = sourceKind;
            this.feature = feature;
            this.construct = construct;
            this.supportLevel = supportLevel;
            this.outcome = outcome;
            this.reasonCode = reasonCode;
            this.reason = reason;
            this.suggestedDirection = suggestedDirection;
            this.markerId = markerId;
        }

        private String rowKey() {
            return sourcePath + '\u0000' + sourceKind + '\u0000' + feature + '\u0000' +
                   construct + '\u0000' + reasonCode + '\u0000' + markerId;
        }

        private String markerDescription() {
            return supportLevel + ": " + feature + " [" + reasonCode + "] -> " + suggestedDirection;
        }

        private MigrationAssessmentTable.Row toRow() {
            return new MigrationAssessmentTable.Row(sourcePath, sourceKind, feature, construct,
                    supportLevel, outcome, reasonCode, reason, suggestedDirection);
        }
    }
}
