package io.github.devanshops.rewrite.helidon;

import org.openrewrite.Cursor;
import org.openrewrite.SourceFile;
import org.openrewrite.groovy.GroovyIsoVisitor;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.properties.PropertiesIsoVisitor;
import org.openrewrite.properties.tree.Properties;
import org.openrewrite.kotlin.KotlinIsoVisitor;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.maven.tree.GroupArtifactVersion;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedPom;
import org.openrewrite.text.PlainText;
import org.openrewrite.xml.XmlIsoVisitor;
import org.openrewrite.xml.tree.Xml;
import org.openrewrite.yaml.YamlIsoVisitor;
import org.openrewrite.yaml.tree.Yaml;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Collects redacted readiness evidence without changing the supplied source tree. */
final class ModuleReadinessEvidenceScanner {
    private static final String SPRING_PREFIX = "org.springframework.";
    private static final String SPRING_XML_NAMESPACE_PREFIX =
            "http://www.springframework.org/schema/";

    private ModuleReadinessEvidenceScanner() {
    }

    static void scanSource(SourceFile sourceFile, ModuleReadinessIndex index) {
        String fileName = sourceFile.getSourcePath().getFileName().toString();
        if (recordUnparsedEvidenceArtifact(sourceFile, index, fileName)) {
            return;
        }
        if (sourceFile instanceof G.CompilationUnit &&
                ("build.gradle".equals(fileName) || "settings.gradle".equals(fileName))) {
            scanGradle((G.CompilationUnit) sourceFile, index);
        } else if (sourceFile instanceof G.CompilationUnit) {
            recordUnsupportedLanguage(sourceFile, index, "GROOVY");
        } else if (sourceFile instanceof K.CompilationUnit &&
                ("build.gradle.kts".equals(fileName) ||
                        "settings.gradle.kts".equals(fileName))) {
            scanKotlinGradle((K.CompilationUnit) sourceFile, index);
        } else if (sourceFile instanceof K.CompilationUnit) {
            recordUnsupportedLanguage(sourceFile, index, "KOTLIN");
        } else if (sourceFile instanceof J.CompilationUnit) {
            scanJava((J.CompilationUnit) sourceFile, index);
        } else if (sourceFile instanceof Xml.Document && "pom.xml".equals(fileName)) {
            scanMaven((Xml.Document) sourceFile, index);
        } else if (sourceFile instanceof Xml.Document) {
            scanSpringXml((Xml.Document) sourceFile, index);
        } else if (sourceFile instanceof Properties.File) {
            if (isSpringFactories(sourceFile.getSourcePath())) {
                scanSpringFactories((Properties.File) sourceFile, index);
            } else if (isApplicationProperties(sourceFile.getSourcePath())) {
                scanApplicationProperties((Properties.File) sourceFile, index);
            }
        } else if (sourceFile instanceof Yaml.Documents &&
                isApplicationYaml(sourceFile.getSourcePath())) {
            scanApplicationYaml((Yaml.Documents) sourceFile, index);
        } else if (sourceFile instanceof PlainText &&
                isAutoConfigurationImports(sourceFile.getSourcePath())) {
            scanAutoConfigurationImports((PlainText) sourceFile, index);
        } else if ((fileName.endsWith(".kt") || fileName.endsWith(".kts")) &&
                !"build.gradle.kts".equals(fileName) &&
                !"settings.gradle.kts".equals(fileName)) {
            recordUnsupportedLanguage(sourceFile, index, "KOTLIN");
        } else if (fileName.endsWith(".groovy")) {
            recordUnsupportedLanguage(sourceFile, index, "GROOVY");
        }
    }

    private static boolean recordUnparsedEvidenceArtifact(
            SourceFile sourceFile, ModuleReadinessIndex index, String fileName) {
        String sourceKind = null;
        if (fileName.endsWith(".java") && !(sourceFile instanceof J.CompilationUnit)) {
            sourceKind = ModuleReadinessIndex.javaSourceKind(
                    ModuleReadinessIndex.normalize(sourceFile.getSourcePath()));
        } else if ("settings.gradle".equals(fileName) &&
                !(sourceFile instanceof G.CompilationUnit)) {
            sourceKind = "GRADLE_GROOVY";
        } else if ("settings.gradle.kts".equals(fileName) &&
                !(sourceFile instanceof K.CompilationUnit)) {
            sourceKind = "GRADLE_KOTLIN";
        } else if (isApplicationProperties(sourceFile.getSourcePath()) &&
                !(sourceFile instanceof Properties.File)) {
            sourceKind = "SPRING_PROPERTIES";
        } else if (isApplicationYaml(sourceFile.getSourcePath()) &&
                !(sourceFile instanceof Yaml.Documents)) {
            sourceKind = "SPRING_YAML";
        } else if (isSpringFactories(sourceFile.getSourcePath()) &&
                !(sourceFile instanceof Properties.File)) {
            sourceKind = "SPRING_FACTORIES";
        } else if (fileName.endsWith(".xml") && !"pom.xml".equals(fileName) &&
                !(sourceFile instanceof Xml.Document)) {
            sourceKind = "SPRING_XML";
        } else if (isAutoConfigurationImports(sourceFile.getSourcePath()) &&
                !(sourceFile instanceof PlainText)) {
            sourceKind = "SPRING_AUTOCONFIG_IMPORTS";
        }
        if (sourceKind == null) {
            return false;
        }
        String sourcePath = ModuleReadinessIndex.normalize(sourceFile.getSourcePath());
        index.recordEvidence(new ModuleReadinessEvidence(sourcePath, sourceKind,
                "Incomplete artifact evidence", "Unparsed " + sourceKind + " artifact",
                "MODULE_EVIDENCE_ARTIFACT_UNPARSED",
                "A recognized evidence-bearing artifact was not parsed into its expected model",
                "Supply parseable source before module migration",
                sourceFile.getId(), false));
        return true;
    }

    private static void recordUnsupportedLanguage(
            SourceFile sourceFile, ModuleReadinessIndex index, String language) {
        String sourcePath = ModuleReadinessIndex.normalize(sourceFile.getSourcePath());
        index.recordEvidence(new ModuleReadinessEvidence(sourcePath,
                language + "_SOURCE", "Unsupported application source language",
                language + " application source", "MODULE_UNSUPPORTED_SOURCE_LANGUAGE",
                "The conservative profile does not analyze this application source language",
                "Add a bounded source-language migration family before module migration",
                sourceFile.getId(), true));
    }

    static void scanJava(final J.CompilationUnit compilationUnit,
                         final ModuleReadinessIndex index) {
        final String sourcePath = ModuleReadinessIndex.normalize(
                compilationUnit.getSourcePath());
        final String sourceKind = ModuleReadinessIndex.javaSourceKind(sourcePath);
        new JavaIsoVisitor<ModuleReadinessIndex>() {
            @Override
            public J.Import visitImport(J.Import anImport, ModuleReadinessIndex accumulator) {
                J.Import visited = super.visitImport(anImport, accumulator);
                String typeName = visited.getTypeName();
                if (typeName.startsWith(SPRING_PREFIX)) {
                    JavaType importType = visited.getQualid().getType();
                    if (visited.isStatic() && visited.getQualid().getTarget() != null) {
                        importType = visited.getQualid().getTarget().getType();
                    }
                    accumulator.recordEvidence(javaEvidence(sourcePath, sourceKind, typeName,
                            visited.getId(), TypeUtils.asFullyQualified(importType) == null));
                }
                return visited;
            }

            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess,
                                                  ModuleReadinessIndex accumulator) {
                J.FieldAccess visited = super.visitFieldAccess(fieldAccess, accumulator);
                if (getCursor().firstEnclosing(J.Import.class) != null) {
                    return visited;
                }
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(visited.getType());
                String typeName = type == null ? "" : type.getFullyQualifiedName();
                if (typeName.startsWith(SPRING_PREFIX)) {
                    accumulator.recordEvidence(javaEvidence(sourcePath, sourceKind, typeName,
                            visited.getId(), false));
                } else if (!isTargetOfParentFieldAccess(getCursor(), visited)) {
                    String syntax = qualifiedSyntax(visited);
                    if (syntax.startsWith(SPRING_PREFIX)) {
                        accumulator.recordEvidence(javaEvidence(sourcePath, sourceKind, syntax,
                                visited.getId(), true));
                    }
                }
                return visited;
            }
        }.visit(compilationUnit, index);
    }

    private static ModuleReadinessEvidence javaEvidence(
            String sourcePath, String sourceKind, String construct,
            java.util.UUID markerId, boolean missingAttribution) {
        if (missingAttribution) {
            return new ModuleReadinessEvidence(sourcePath, sourceKind,
                    "Java type attribution", construct, "MODULE_MISSING_ATTRIBUTION",
                    "Spring Java syntax cannot be planned without complete type attribution",
                    "Supply the complete Java parser classpath before module migration",
                    markerId, true);
        }
        return new ModuleReadinessEvidence(sourcePath, sourceKind,
                "Spring Java residue", construct, "MODULE_SPRING_JAVA_RESIDUE",
                "Spring Java residue is outside the conservative module profile",
                "Migrate or remove this Spring API with a bounded recipe",
                markerId, true);
    }

    private static boolean isTargetOfParentFieldAccess(Cursor cursor, J.FieldAccess fieldAccess) {
        Cursor parent = cursor.getParentTreeCursor();
        if (parent == null || !(parent.getValue() instanceof J.FieldAccess)) {
            return false;
        }
        J.FieldAccess parentAccess = (J.FieldAccess) parent.getValue();
        return parentAccess.getTarget().getId().equals(fieldAccess.getId());
    }

    private static String qualifiedSyntax(Expression expression) {
        if (expression instanceof J.Identifier) {
            return ((J.Identifier) expression).getSimpleName();
        }
        if (expression instanceof J.FieldAccess) {
            J.FieldAccess access = (J.FieldAccess) expression;
            String target = qualifiedSyntax(access.getTarget());
            return target.isEmpty() ? access.getSimpleName() :
                    target + '.' + access.getSimpleName();
        }
        return "";
    }

    static void scanMaven(final Xml.Document document,
                          final ModuleReadinessIndex index) {
        final String sourcePath = ModuleReadinessIndex.normalize(document.getSourcePath());
        final ResolvedPom resolvedPom = document.getMarkers()
                .findFirst(MavenResolutionResult.class)
                .map(MavenResolutionResult::getPom)
                .orElse(null);
        new XmlIsoVisitor<ModuleReadinessIndex>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, ModuleReadinessIndex accumulator) {
                Xml.Tag visited = super.visitTag(tag, accumulator);
                String name = localName(visited.getName());
                if (!"parent".equals(name) && !"dependency".equals(name) &&
                        !"plugin".equals(name)) {
                    return visited;
                }
                String groupId = visited.getChildValue("groupId").orElse("");
                String artifactId = visited.getChildValue("artifactId").orElse("");
                String version = visited.getChildValue("version").orElse("");
                String requestedGroup = groupId;
                if ("plugin".equals(name) && requestedGroup.isEmpty()) {
                    requestedGroup = "org.apache.maven.plugins";
                }
                boolean dynamic = containsPlaceholder(requestedGroup) ||
                        containsPlaceholder(artifactId) || containsPlaceholder(version);
                if (dynamic && resolvedPom != null) {
                    GroupArtifactVersion resolved = resolvedPom.getValues(
                            new GroupArtifactVersion(requestedGroup, artifactId, version));
                    if (resolved != null && completeCoordinate(resolved, !version.isEmpty())) {
                        groupId = resolved.getGroupId();
                        artifactId = resolved.getArtifactId();
                        version = resolved.getVersion();
                        dynamic = false;
                    }
                }
                if (dynamic || requestedGroup.isEmpty() || artifactId.isEmpty()) {
                    accumulator.recordEvidence(new ModuleReadinessEvidence(
                            sourcePath, "MAVEN", "Incomplete build evidence",
                            "Unresolved Maven coordinate",
                            "MODULE_BUILD_EVIDENCE_INCOMPLETE",
                            "An unresolved Maven declaration can hide Spring build residue",
                            "Supply a fully resolved Maven model before module migration",
                            visited.getId(), true));
                    return visited;
                }
                if (!isSpringBuildGroup(groupId)) {
                    return visited;
                }
                accumulator.recordEvidence(new ModuleReadinessEvidence(
                        sourcePath, "MAVEN", "Spring build residue",
                        groupId + ':' + artifactId, "MODULE_SPRING_BUILD_RESIDUE",
                        "Spring build residue is outside the conservative module profile",
                        "Migrate the build and remove Spring only after module closure",
                        visited.getId(), true));
                return visited;
            }
        }.visit(document, index);
    }

    private static boolean containsPlaceholder(String value) {
        return value != null && value.contains("${");
    }

    private static boolean completeCoordinate(
            GroupArtifactVersion coordinate, boolean requireVersion) {
        return coordinate.getGroupId() != null && !coordinate.getGroupId().isEmpty() &&
               !containsPlaceholder(coordinate.getGroupId()) &&
               coordinate.getArtifactId() != null && !coordinate.getArtifactId().isEmpty() &&
               !containsPlaceholder(coordinate.getArtifactId()) &&
               (!requireVersion || (coordinate.getVersion() != null &&
                       !coordinate.getVersion().isEmpty() &&
                       !containsPlaceholder(coordinate.getVersion())));
    }

    static void scanGradle(final G.CompilationUnit compilationUnit,
                           final ModuleReadinessIndex index) {
        final String sourcePath = ModuleReadinessIndex.normalize(
                compilationUnit.getSourcePath());
        new GroovyIsoVisitor<ModuleReadinessIndex>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
                                                            ModuleReadinessIndex accumulator) {
                J.MethodInvocation visited = super.visitMethodInvocation(method, accumulator);
                assessGradleMethod(visited, accumulator, sourcePath, "GRADLE_GROOVY",
                        getCursor());
                return visited;
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier,
                                                ModuleReadinessIndex accumulator) {
                J.Identifier visited = super.visitIdentifier(identifier, accumulator);
                assessGradlePluginAccessor(visited, accumulator, sourcePath,
                        "GRADLE_GROOVY", getCursor());
                return visited;
            }

            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess,
                                                  ModuleReadinessIndex accumulator) {
                J.FieldAccess visited = super.visitFieldAccess(fieldAccess, accumulator);
                assessGradlePluginAccessor(visited, accumulator, sourcePath,
                        "GRADLE_GROOVY", getCursor());
                return visited;
            }
        }.visit(compilationUnit, index);
    }

    static void scanKotlinGradle(final K.CompilationUnit compilationUnit,
                                 final ModuleReadinessIndex index) {
        final String sourcePath = ModuleReadinessIndex.normalize(
                compilationUnit.getSourcePath());
        new KotlinIsoVisitor<ModuleReadinessIndex>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
                                                            ModuleReadinessIndex accumulator) {
                J.MethodInvocation visited = super.visitMethodInvocation(method, accumulator);
                assessGradleMethod(visited, accumulator, sourcePath, "GRADLE_KOTLIN",
                        getCursor());
                return visited;
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier,
                                                ModuleReadinessIndex accumulator) {
                J.Identifier visited = super.visitIdentifier(identifier, accumulator);
                assessGradlePluginAccessor(visited, accumulator, sourcePath,
                        "GRADLE_KOTLIN", getCursor());
                return visited;
            }

            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess,
                                                  ModuleReadinessIndex accumulator) {
                J.FieldAccess visited = super.visitFieldAccess(fieldAccess, accumulator);
                assessGradlePluginAccessor(visited, accumulator, sourcePath,
                        "GRADLE_KOTLIN", getCursor());
                return visited;
            }
        }.visit(compilationUnit, index);
    }

    private static void assessGradleMethod(J.MethodInvocation method,
                                           ModuleReadinessIndex index,
                                           String sourcePath, String sourceKind,
                                           Cursor cursor) {
        String methodName = method.getSimpleName();
        String literal = firstStringArgument(method);
        if (isSettingsPath(sourcePath) && isGradleReactorDeclaration(methodName)) {
            boolean unresolved = method.getArguments().isEmpty();
            for (Expression argument : method.getArguments()) {
                if (argument instanceof J.Literal &&
                        ((J.Literal) argument).getValue() instanceof String) {
                    index.recordGradleProjectReference(sourcePath,
                            (String) ((J.Literal) argument).getValue(), methodName,
                            method.getId());
                } else {
                    unresolved = true;
                }
            }
            if (unresolved) {
                index.recordEvidence(new ModuleReadinessEvidence(
                        sourcePath, sourceKind, "Incomplete reactor topology",
                        "Unresolved Gradle " + methodName + " declaration",
                        "MODULE_REACTOR_DECLARATION_UNRESOLVED",
                        "A dynamic Gradle reactor declaration cannot be reconciled " +
                                "with the supplied build roots",
                        "Supply literal Gradle reactor declarations before module migration",
                        method.getId(), true));
            }
            return;
        }
        GradlePluginCall pluginCall = classifyGradlePluginCall(method, cursor);
        if (pluginCall == GradlePluginCall.LEGACY_APPLY) {
            String plugin = mapArgument(method, "plugin");
            if (plugin != null) {
                if (isSpringPluginId(plugin)) {
                    recordGradleEvidence(index, sourcePath, sourceKind, plugin, method.getId());
                }
            } else if (hasNamedArgument(method, "plugin")) {
                recordIncompleteGradle(index, sourcePath, sourceKind,
                        "Unresolved Gradle plugin declaration", method.getId());
            }
            if (hasNamedArgument(method, "from")) {
                recordIncompleteGradle(index, sourcePath, sourceKind,
                        "Unresolved Gradle external script declaration", method.getId());
            }
            return;
        }
        if (pluginCall == GradlePluginCall.APPLY) {
            String chainedPluginId = selectedPluginId(method);
            if (chainedPluginId != null) {
                if (booleanArgument(method, 0) == null) {
                    recordIncompleteGradle(index, sourcePath, sourceKind,
                            "Unresolved Gradle plugin declaration", method.getId());
                }
                if (isSpringPluginId(chainedPluginId)) {
                    recordGradleEvidence(index, sourcePath, sourceKind,
                            chainedPluginId, method.getId());
                }
                return;
            }
        }
        if (pluginCall == GradlePluginCall.ID ||
                pluginCall == GradlePluginCall.APPLY) {
            if (literal == null) {
                recordIncompleteGradle(index, sourcePath, sourceKind,
                        "Unresolved Gradle plugin declaration", method.getId());
            } else if (isSpringPluginId(literal)) {
                recordGradleEvidence(index, sourcePath, sourceKind, literal, method.getId());
            }
            return;
        }
        if (pluginCall == GradlePluginCall.ALIAS) {
            recordIncompleteGradle(index, sourcePath, sourceKind,
                    "Unresolved Gradle dependency declaration", method.getId());
            return;
        }
        if (pluginCall == GradlePluginCall.UNSUPPORTED) {
            recordIncompleteGradle(index, sourcePath, sourceKind,
                    "Unresolved Gradle plugin declaration", method.getId());
            return;
        }
        if (pluginCall == GradlePluginCall.KNOWN_STATIC) {
            String chainedPluginId = selectedPluginId(method);
            if (chainedPluginId != null && isSpringPluginId(chainedPluginId)) {
                recordGradleEvidence(index, sourcePath, sourceKind,
                        chainedPluginId, method.getId());
            }
            return;
        }
        if ("project".equals(methodName)) {
            return;
        }
        GradleDependencyCall dependencyCall = classifyGradleDependencyCall(method, cursor);
        if (dependencyCall == GradleDependencyCall.NONE) {
            return;
        }
        if (dependencyCall == GradleDependencyCall.UNSUPPORTED) {
            recordIncompleteGradle(index, sourcePath, sourceKind,
                    "Unresolved Gradle dependency declaration", method.getId());
            return;
        }
        if (dependencyCall == GradleDependencyCall.ADD) {
            if (isStaticProjectDependency(method, 1)) {
                return;
            }
            String notation = stringArgument(method, 1);
            if (notation == null) {
                recordIncompleteGradle(index, sourcePath, sourceKind,
                        "Unresolved Gradle dependency declaration", method.getId());
            } else {
                String coordinate = springCoordinate(notation);
                if (coordinate != null) {
                    recordGradleEvidence(index, sourcePath, sourceKind, coordinate,
                            method.getId());
                }
            }
            return;
        }
        if (literal != null) {
            String coordinate = springCoordinate(literal);
            if (coordinate != null) {
                recordGradleEvidence(index, sourcePath, sourceKind, coordinate, method.getId());
            }
            return;
        }
        String mapCoordinate = staticMapCoordinate(method);
        if (mapCoordinate != null) {
            String coordinate = springCoordinate(mapCoordinate);
            if (coordinate != null) {
                recordGradleEvidence(index, sourcePath, sourceKind, coordinate, method.getId());
            }
            return;
        }
        if (isStaticProjectDependency(method, 0)) {
            return;
        }
        recordIncompleteGradle(index, sourcePath, sourceKind,
                "Unresolved Gradle dependency declaration", method.getId());
    }

    private static GradleDependencyCall classifyGradleDependencyCall(
            J.MethodInvocation method, Cursor cursor) {
        if (!isDirectDependencyDeclaration(cursor) &&
                !isDependencyHandlerReceiver(method)) {
            return GradleDependencyCall.NONE;
        }
        String methodName = method.getSimpleName();
        if ("add".equals(methodName)) {
            return method.getArguments().size() == 2 ?
                GradleDependencyCall.ADD : GradleDependencyCall.UNSUPPORTED;
        }
        if (methodName.startsWith("add")) {
            return GradleDependencyCall.UNSUPPORTED;
        }
        return GradleDependencyCall.CONFIGURATION_ACCESSOR;
    }

    private static GradlePluginCall classifyGradlePluginCall(
            J.MethodInvocation method, Cursor cursor) {
        String methodName = method.getSimpleName();
        if ("apply".equals(methodName) &&
                (hasNamedArgument(method, "plugin") ||
                        hasNamedArgument(method, "from")) &&
                isGradleProjectOwnedInvocation(method)) {
            return GradlePluginCall.LEGACY_APPLY;
        }
        if (!isDirectPluginBodyInvocation(cursor) &&
                !isPluginHandlerReceiver(method)) {
            return GradlePluginCall.NONE;
        }
        if ("id".equals(methodName)) {
            return method.getArguments().size() == 1 ?
                    GradlePluginCall.ID : GradlePluginCall.UNSUPPORTED;
        }
        if ("apply".equals(methodName)) {
            return method.getArguments().size() == 1 ?
                    GradlePluginCall.APPLY : GradlePluginCall.UNSUPPORTED;
        }
        if ("alias".equals(methodName)) {
            return GradlePluginCall.ALIAS;
        }
        if (isKnownPluginDslMethod(methodName) &&
                method.getArguments().size() == 1 &&
                stringArgument(method, 0) != null) {
            return GradlePluginCall.KNOWN_STATIC;
        }
        return GradlePluginCall.UNSUPPORTED;
    }

    private static boolean isGradleProjectOwnedInvocation(J.MethodInvocation method) {
        if (method.getSelect() == null) {
            return true;
        }
        if (isProjectReference(method.getSelect())) {
            return true;
        }
        ReceiverOwnership ownership = receiverOwnership(
                method.getSelect().getType(), "org.gradle.api.Project");
        if (ownership != ReceiverOwnership.UNKNOWN) {
            return ownership == ReceiverOwnership.MATCH;
        }
        return false;
    }

    private static String selectedPluginId(J.MethodInvocation method) {
        return pluginIdFromRequest(method.getSelect());
    }

    private static String pluginIdFromRequest(Expression expression) {
        if (!(expression instanceof J.MethodInvocation)) {
            return null;
        }
        J.MethodInvocation request = (J.MethodInvocation) expression;
        if ("id".equals(request.getSimpleName()) &&
                request.getArguments().size() == 1) {
            return stringArgument(request, 0);
        }
        if ("version".equals(request.getSimpleName()) &&
                request.getArguments().size() == 1 &&
                stringArgument(request, 0) != null) {
            return pluginIdFromRequest(request.getSelect());
        }
        return null;
    }

    private static Boolean booleanArgument(J.MethodInvocation method, int index) {
        if (method.getArguments().size() <= index ||
                !(method.getArguments().get(index) instanceof J.Literal)) {
            return null;
        }
        Object value = ((J.Literal) method.getArguments().get(index)).getValue();
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static void assessGradlePluginAccessor(
            Expression accessor, ModuleReadinessIndex index, String sourcePath,
            String sourceKind, Cursor cursor) {
        if (!isDirectPluginBodyExpression(cursor)) {
            return;
        }
        String accessorName;
        if (accessor instanceof J.Identifier) {
            J.MethodInvocation enclosing = nearestEnclosingMethod(cursor);
            if (enclosing != null && enclosing.getName().getId()
                    .equals(accessor.getId())) {
                return;
            }
            accessorName = ((J.Identifier) accessor).getSimpleName();
        } else if (accessor instanceof J.FieldAccess) {
            accessorName = qualifiedSyntax(accessor);
        } else {
            return;
        }
        if (!isKnownPluginAccessor(accessorName)) {
            recordIncompleteGradle(index, sourcePath, sourceKind,
                    "Unresolved Gradle plugin declaration", accessor.getId());
        }
    }

    private static boolean isDirectPluginBodyExpression(Cursor cursor) {
        Cursor parent = cursor.getParent();
        while (parent != null) {
            Object value = parent.getValue();
            if (value instanceof J.FieldAccess ||
                    value instanceof J.VariableDeclarations ||
                    value instanceof J.Assignment) {
                return false;
            }
            if (value instanceof J.MethodInvocation) {
                return "plugins".equals(
                        ((J.MethodInvocation) value).getSimpleName());
            }
            parent = parent.getParent();
        }
        return false;
    }

    private static J.MethodInvocation nearestEnclosingMethod(Cursor cursor) {
        Cursor parent = cursor.getParent();
        while (parent != null) {
            if (parent.getValue() instanceof J.MethodInvocation) {
                return (J.MethodInvocation) parent.getValue();
            }
            parent = parent.getParent();
        }
        return null;
    }

    private static boolean isDirectPluginBodyInvocation(Cursor cursor) {
        Cursor parent = cursor.getParent();
        while (parent != null) {
            Object value = parent.getValue();
            if (value instanceof J.MethodInvocation) {
                return "plugins".equals(
                        ((J.MethodInvocation) value).getSimpleName());
            }
            parent = parent.getParent();
        }
        return false;
    }

    private static boolean isKnownPluginDslMethod(String methodName) {
        return "id".equals(methodName) || "alias".equals(methodName) ||
               "kotlin".equals(methodName) || "version".equals(methodName);
    }

    private static boolean isKnownPluginAccessor(String accessorName) {
        return "java".equals(accessorName) || "java-library".equals(accessorName) ||
               "javaLibrary".equals(accessorName) ||
               "java-gradle-plugin".equals(accessorName) ||
               "javaGradlePlugin".equals(accessorName) ||
               "application".equals(accessorName) || "groovy".equals(accessorName) ||
               "scala".equals(accessorName) || "war".equals(accessorName) ||
               "ear".equals(accessorName) || "antlr".equals(accessorName) ||
               "checkstyle".equals(accessorName) || "codenarc".equals(accessorName) ||
               "pmd".equals(accessorName) || "jacoco".equals(accessorName) ||
               "idea".equals(accessorName) || "eclipse".equals(accessorName) ||
               "signing".equals(accessorName) || "maven-publish".equals(accessorName) ||
               "mavenPublish".equals(accessorName) || "kotlin-dsl".equals(accessorName) ||
               "kotlinDsl".equals(accessorName);
    }

    private static boolean isSettingsPath(String sourcePath) {
        return sourcePath.endsWith("/settings.gradle") ||
               sourcePath.endsWith("/settings.gradle.kts") ||
               "settings.gradle".equals(sourcePath) ||
               "settings.gradle.kts".equals(sourcePath);
    }

    private static boolean isPluginHandlerReceiver(J.MethodInvocation method) {
        JavaType.Method methodType = method.getMethodType();
        ReceiverOwnership methodOwnership = methodType == null ?
                ReceiverOwnership.UNKNOWN : receiverOwnership(
                        methodType.getDeclaringType(),
                        "org.gradle.api.plugins.PluginManager",
                        "org.gradle.api.plugins.PluginContainer");
        if (methodOwnership != ReceiverOwnership.UNKNOWN) {
            return methodOwnership == ReceiverOwnership.MATCH;
        }
        Expression select = method.getSelect();
        if (select == null) {
            return false;
        }
        ReceiverOwnership selectOwnership = receiverOwnership(select.getType(),
                "org.gradle.api.plugins.PluginManager",
                "org.gradle.api.plugins.PluginContainer");
        if (selectOwnership == ReceiverOwnership.MATCH) {
            return true;
        }
        String receiver = qualifiedSyntax(select);
        if ("pluginManager".equals(receiver) || "plugins".equals(receiver) ||
                "project.pluginManager".equals(receiver) ||
                "project.plugins".equals(receiver)) {
            return true;
        }
        if (!(select instanceof J.MethodInvocation)) {
            return false;
        }
        J.MethodInvocation getter = (J.MethodInvocation) select;
        if (!("getPluginManager".equals(getter.getSimpleName()) ||
                "getPlugins".equals(getter.getSimpleName())) ||
                !hasNoArguments(getter)) {
            return false;
        }
        JavaType.Method getterType = getter.getMethodType();
        ReceiverOwnership getterOwnership = getterType == null ?
                ReceiverOwnership.UNKNOWN : receiverOwnership(
                        getterType.getReturnType(),
                        "org.gradle.api.plugins.PluginManager",
                        "org.gradle.api.plugins.PluginContainer");
        if (getterOwnership != ReceiverOwnership.UNKNOWN) {
            return getterOwnership == ReceiverOwnership.MATCH;
        }
        Expression getterSelect = getter.getSelect();
        return getterSelect == null || isProjectReference(getterSelect);
    }

    private static boolean isGradleReactorDeclaration(String methodName) {
        return "include".equals(methodName) || "includeFlat".equals(methodName) ||
               "includeBuild".equals(methodName);
    }

    private static boolean isDirectDependencyDeclaration(Cursor cursor) {
        Cursor parent = cursor.getParent();
        while (parent != null) {
            Object value = parent.getValue();
            if (value instanceof J.MethodInvocation) {
                return "dependencies".equals(((J.MethodInvocation) value).getSimpleName());
            }
            parent = parent.getParent();
        }
        return false;
    }

    private static boolean isDependencyHandlerReceiver(J.MethodInvocation method) {
        JavaType.Method methodType = method.getMethodType();
        ReceiverOwnership methodOwnership = methodType == null ?
                ReceiverOwnership.UNKNOWN : receiverOwnership(
                        methodType.getDeclaringType(),
                        "org.gradle.api.artifacts.dsl.DependencyHandler");
        if (methodOwnership != ReceiverOwnership.UNKNOWN) {
            return methodOwnership == ReceiverOwnership.MATCH;
        }
        Expression select = method.getSelect();
        if (select == null) {
            return false;
        }
        ReceiverOwnership selectOwnership = receiverOwnership(select.getType(),
                "org.gradle.api.artifacts.dsl.DependencyHandler");
        if (selectOwnership == ReceiverOwnership.MATCH) {
            return true;
        }
        String receiver = qualifiedSyntax(select);
        if ("dependencies".equals(receiver) ||
                "project.dependencies".equals(receiver)) {
            return true;
        }
        if (!(select instanceof J.MethodInvocation)) {
            return false;
        }
        J.MethodInvocation getter = (J.MethodInvocation) select;
        if (!"getDependencies".equals(getter.getSimpleName()) ||
                !hasNoArguments(getter)) {
            return false;
        }
        JavaType.Method getterType = getter.getMethodType();
        ReceiverOwnership getterOwnership = getterType == null ?
                ReceiverOwnership.UNKNOWN : receiverOwnership(
                        getterType.getReturnType(),
                        "org.gradle.api.artifacts.dsl.DependencyHandler");
        if (getterOwnership != ReceiverOwnership.UNKNOWN) {
            return getterOwnership == ReceiverOwnership.MATCH;
        }
        Expression getterSelect = getter.getSelect();
        return getterSelect == null || isProjectReference(getterSelect);
    }

    private static boolean isProjectReference(Expression expression) {
        if ("project".equals(qualifiedSyntax(expression))) {
            return true;
        }
        if (!(expression instanceof J.MethodInvocation)) {
            return false;
        }
        J.MethodInvocation project = (J.MethodInvocation) expression;
        return ("project".equals(project.getSimpleName()) ||
                "getProject".equals(project.getSimpleName())) &&
               project.getSelect() == null && hasNoArguments(project);
    }

    private static boolean hasNoArguments(J.MethodInvocation method) {
        return method.getArguments().isEmpty() ||
               (method.getArguments().size() == 1 &&
                       method.getArguments().get(0) instanceof J.Empty);
    }

    private static ReceiverOwnership receiverOwnership(
            JavaType type, String... supportedTypes) {
        if (TypeUtils.asFullyQualified(type) == null) {
            return ReceiverOwnership.UNKNOWN;
        }
        for (String supportedType : supportedTypes) {
            if (TypeUtils.isAssignableTo(supportedType, type)) {
                return ReceiverOwnership.MATCH;
            }
        }
        return ReceiverOwnership.INCOMPATIBLE;
    }

    private enum ReceiverOwnership {
        MATCH,
        INCOMPATIBLE,
        UNKNOWN
    }

    private enum GradleDependencyCall {
        NONE,
        ADD,
        CONFIGURATION_ACCESSOR,
        UNSUPPORTED
    }

    private enum GradlePluginCall {
        NONE,
        LEGACY_APPLY,
        ID,
        APPLY,
        ALIAS,
        KNOWN_STATIC,
        UNSUPPORTED
    }

    private static boolean isStaticProjectDependency(
            J.MethodInvocation method, int notationIndex) {
        if (method.getArguments().size() != notationIndex + 1 ||
                !(method.getArguments().get(notationIndex) instanceof J.MethodInvocation)) {
            return false;
        }
        J.MethodInvocation project =
                (J.MethodInvocation) method.getArguments().get(notationIndex);
        String path = firstStringArgument(project);
        return "project".equals(project.getSimpleName()) &&
               project.getSelect() == null && project.getArguments().size() == 1 &&
               path != null && !path.trim().isEmpty();
    }

    private static void recordIncompleteGradle(
            ModuleReadinessIndex index, String sourcePath, String sourceKind,
            String construct, java.util.UUID markerId) {
        index.recordEvidence(new ModuleReadinessEvidence(
                sourcePath, sourceKind, "Incomplete build evidence", construct,
                "MODULE_BUILD_EVIDENCE_INCOMPLETE",
                "An unresolved Gradle declaration can hide Spring build residue",
                "Supply a fully resolved Gradle model before module migration",
                markerId, true));
    }

    private static void recordGradleEvidence(ModuleReadinessIndex index, String sourcePath,
                                             String sourceKind, String construct,
                                             java.util.UUID markerId) {
        index.recordEvidence(new ModuleReadinessEvidence(
                sourcePath, sourceKind, "Spring build residue", construct,
                "MODULE_SPRING_BUILD_RESIDUE",
                "Spring build residue is outside the conservative module profile",
                "Migrate the build and remove Spring only after module closure",
                markerId, true));
    }

    private static String firstStringArgument(J.MethodInvocation method) {
        return stringArgument(method, 0);
    }

    private static String stringArgument(J.MethodInvocation method, int index) {
        if (method.getArguments().size() <= index ||
                !(method.getArguments().get(index) instanceof J.Literal)) {
            return null;
        }
        Object value = ((J.Literal) method.getArguments().get(index)).getValue();
        return value instanceof String ? (String) value : null;
    }

    private static String staticMapCoordinate(J.MethodInvocation method) {
        List<G.MapEntry> entries = mapEntries(method);
        String group = mapString(entries, "group");
        String name = mapString(entries, "name");
        return group != null && name != null ? group + ':' + name : null;
    }

    private static String mapArgument(J.MethodInvocation method, String key) {
        Expression argument = namedArgument(method, key);
        if (!(argument instanceof J.Literal)) {
            return null;
        }
        Object value = ((J.Literal) argument).getValue();
        return value instanceof String ? (String) value : null;
    }

    private static boolean hasNamedArgument(
            J.MethodInvocation method, String expectedKey) {
        return namedArgument(method, expectedKey) != null;
    }

    private static Expression namedArgument(
            J.MethodInvocation method, String expectedKey) {
        for (Expression argument : method.getArguments()) {
            if (argument instanceof G.MapEntry) {
                G.MapEntry entry = (G.MapEntry) argument;
                if (expectedKey.equals(normalizedMapKey(entry.getKey()))) {
                    return entry.getValue();
                }
            } else if (argument instanceof G.MapLiteral) {
                for (G.MapEntry entry : ((G.MapLiteral) argument).getElements()) {
                    if (expectedKey.equals(normalizedMapKey(entry.getKey()))) {
                        return entry.getValue();
                    }
                }
            } else if (argument instanceof J.Assignment) {
                J.Assignment assignment = (J.Assignment) argument;
                if (assignment.getVariable() instanceof J.Identifier &&
                        expectedKey.equals(((J.Identifier) assignment.getVariable())
                                .getSimpleName())) {
                    return assignment.getAssignment();
                }
            }
        }
        return null;
    }

    private static String normalizedMapKey(Expression expression) {
        String key = expression.printTrimmed();
        if ((key.startsWith("'") && key.endsWith("'")) ||
                (key.startsWith("\"") && key.endsWith("\""))) {
            return key.substring(1, key.length() - 1);
        }
        return key;
    }

    private static List<G.MapEntry> mapEntries(J.MethodInvocation method) {
        List<G.MapEntry> entries = new ArrayList<G.MapEntry>();
        for (Expression argument : method.getArguments()) {
            if (argument instanceof G.MapEntry) {
                entries.add((G.MapEntry) argument);
            } else if (argument instanceof G.MapLiteral) {
                entries.addAll(((G.MapLiteral) argument).getElements());
            }
        }
        return entries;
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
        return "org.springframework.boot".equals(pluginId) ||
               pluginId.startsWith("org.springframework.") ||
               "io.spring.dependency-management".equals(pluginId) ||
               pluginId.startsWith("io.spring.");
    }

    private static String springCoordinate(String declaration) {
        String[] parts = declaration.split(":");
        if (parts.length < 2 || !isSpringBuildGroup(parts[0]) || parts[1].isEmpty()) {
            return null;
        }
        return parts[0] + ':' + parts[1];
    }

    private static boolean isSpringBuildGroup(String groupId) {
        return "org.springframework".equals(groupId) ||
               groupId.startsWith("org.springframework.") ||
               "io.spring".equals(groupId) || groupId.startsWith("io.spring.");
    }

    static void scanSpringXml(Xml.Document document, ModuleReadinessIndex index) {
        final String sourcePath = ModuleReadinessIndex.normalize(document.getSourcePath());
        new XmlIsoVisitor<ModuleReadinessIndex>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, ModuleReadinessIndex accumulator) {
                Xml.Tag visited = super.visitTag(tag, accumulator);
                for (Xml.Attribute attribute : visited.getAttributes()) {
                    if (attribute.getKeyAsString().startsWith("xmlns") &&
                            attribute.getValueAsString().startsWith(
                                    SPRING_XML_NAMESPACE_PREFIX)) {
                        accumulator.recordEvidence(privateEvidence(sourcePath,
                                "SPRING_XML", "Spring XML wiring",
                                "Spring XML namespace", "MODULE_SPRING_XML",
                                "Spring XML wiring is outside the conservative module profile",
                                "Replace Spring XML wiring with explicit CDI beans and producers",
                                visited.getId()));
                        break;
                    }
                }
                return visited;
            }
        }.visit(document, index);
    }

    static void scanApplicationProperties(final Properties.File properties,
                                          final ModuleReadinessIndex index) {
        final String sourcePath = ModuleReadinessIndex.normalize(properties.getSourcePath());
        new PropertiesIsoVisitor<ModuleReadinessIndex>() {
            @Override
            public Properties.Entry visitEntry(Properties.Entry entry,
                                                ModuleReadinessIndex accumulator) {
                Properties.Entry visited = super.visitEntry(entry, accumulator);
                accumulator.recordEvidence(configurationEvidence(sourcePath,
                        "SPRING_PROPERTIES", visited.getKey(), visited.getId()));
                return visited;
            }
        }.visit(properties, index);
    }

    static void scanApplicationYaml(final Yaml.Documents documents,
                                    final ModuleReadinessIndex index) {
        final String sourcePath = ModuleReadinessIndex.normalize(documents.getSourcePath());
        new YamlIsoVisitor<ModuleReadinessIndex>() {
            @Override
            public Yaml.Mapping.Entry visitMappingEntry(Yaml.Mapping.Entry entry,
                                                        ModuleReadinessIndex accumulator) {
                Yaml.Mapping.Entry visited = super.visitMappingEntry(entry, accumulator);
                if (!(visited.getValue() instanceof Yaml.Mapping)) {
                    String key = yamlKey(getCursor());
                    accumulator.recordEvidence(configurationEvidence(sourcePath,
                            "SPRING_YAML", key, visited.getId()));
                }
                return visited;
            }
        }.visit(documents, index);
    }

    static void scanSpringFactories(final Properties.File properties,
                                    final ModuleReadinessIndex index) {
        final String sourcePath = ModuleReadinessIndex.normalize(properties.getSourcePath());
        new PropertiesIsoVisitor<ModuleReadinessIndex>() {
            @Override
            public Properties.Entry visitEntry(Properties.Entry entry,
                                                ModuleReadinessIndex accumulator) {
                Properties.Entry visited = super.visitEntry(entry, accumulator);
                accumulator.recordEvidence(privateEvidence(sourcePath,
                        "SPRING_FACTORIES", "Spring factories metadata", visited.getKey(),
                        "MODULE_SPRING_METADATA",
                        "Spring runtime discovery metadata is outside the conservative module profile",
                        "Replace registration with explicit CDI discovery or target runtime metadata",
                        visited.getId()));
                return visited;
            }
        }.visit(properties, index);
    }

    static void scanAutoConfigurationImports(PlainText imports,
                                             ModuleReadinessIndex index) {
        if (!hasRegistrationLine(imports.getText())) {
            return;
        }
        index.recordEvidence(privateEvidence(imports,
                "SPRING_AUTOCONFIG_IMPORTS", "Spring Boot auto-configuration metadata",
                "AutoConfiguration.imports entry", "MODULE_SPRING_METADATA",
                "Spring runtime discovery metadata is outside the conservative module profile",
                "Replace registration with explicit CDI discovery or target runtime metadata"));
    }

    static boolean isApplicationProperties(Path path) {
        String fileName = path.getFileName().toString();
        return "application.properties".equals(fileName) ||
               (fileName.startsWith("application-") && fileName.endsWith(".properties"));
    }

    static boolean isApplicationYaml(Path path) {
        String fileName = path.getFileName().toString();
        return "application.yml".equals(fileName) || "application.yaml".equals(fileName) ||
               (fileName.startsWith("application-") &&
                       (fileName.endsWith(".yml") || fileName.endsWith(".yaml")));
    }

    static boolean isSpringFactories(Path path) {
        return "spring.factories".equals(path.getFileName().toString()) &&
               ModuleReadinessIndex.normalize(path).contains("/META-INF/");
    }

    static boolean isAutoConfigurationImports(Path path) {
        return "org.springframework.boot.autoconfigure.AutoConfiguration.imports"
                .equals(path.getFileName().toString()) &&
               ModuleReadinessIndex.normalize(path).contains("/META-INF/spring/");
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
        return String.join(".", parts);
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

    private static ModuleReadinessEvidence configurationEvidence(
            String sourcePath, String sourceKind, String key, java.util.UUID markerId) {
        boolean springOwned = key.startsWith("spring.");
        return privateEvidence(sourcePath, sourceKind,
                springOwned ? "Spring configuration" : "Application configuration",
                key, springOwned ? "MODULE_SPRING_CONFIGURATION" :
                        "MODULE_APPLICATION_CONFIGURATION",
                springOwned ?
                        "Spring configuration is outside the conservative module profile" :
                        "Application configuration has no proven target contract in the conservative module profile",
                "Map the key to a documented MicroProfile Config contract", markerId);
    }

    private static ModuleReadinessEvidence privateEvidence(
            org.openrewrite.SourceFile sourceFile, String sourceKind, String feature,
            String construct, String reasonCode, String reason, String direction) {
        return privateEvidence(ModuleReadinessIndex.normalize(sourceFile.getSourcePath()),
                sourceKind, feature, construct, reasonCode, reason, direction,
                sourceFile.getId());
    }

    private static ModuleReadinessEvidence privateEvidence(
            String sourcePath, String sourceKind, String feature, String construct,
            String reasonCode, String reason, String direction) {
        return privateEvidence(sourcePath, sourceKind, feature, construct, reasonCode,
                reason, direction, null);
    }

    private static ModuleReadinessEvidence privateEvidence(
            String sourcePath, String sourceKind, String feature, String construct,
            String reasonCode, String reason, String direction, java.util.UUID markerId) {
        return new ModuleReadinessEvidence(sourcePath, sourceKind, feature, construct,
                reasonCode, reason, direction, markerId, false);
    }

    private static String localName(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }
}
