package io.github.devanshops.rewrite.helidon;

import org.openrewrite.SourceFile;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.tree.J;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.xml.tree.Xml;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Builds and freezes module-scoped readiness decisions before reporting. */
final class ModuleReadinessIndex {
    private final Map<String, List<ModuleBuild>> builds =
            new LinkedHashMap<String, List<ModuleBuild>>();
    private final Map<String, ModuleReadinessEvidence> evidence =
            new LinkedHashMap<String, ModuleReadinessEvidence>();
    private final Map<String, ModuleArtifact> artifacts =
            new LinkedHashMap<String, ModuleArtifact>();
    private final Map<UUID, String> markers = new HashMap<UUID, String>();
    private final List<ExpectedModule> expectedModules = new ArrayList<ExpectedModule>();
    private final Map<String, Boolean> eligibilityByRoot =
            new HashMap<String, Boolean>();
    private List<ModuleReadinessDecision> planned;

    synchronized void record(SourceFile sourceFile) {
        String path = normalize(sourceFile.getSourcePath());
        artifacts.put(path, new ModuleArtifact(path, sourceFile.getId(),
                sourceFile instanceof J.CompilationUnit));
        String fileName = sourceFile.getSourcePath().getFileName().toString();
        if ("pom.xml".equals(fileName)) {
            String root = parent(path);
            UUID anchorId = sourceFile instanceof Xml.Document ?
                    ((Xml.Document) sourceFile).getRoot().getId() : sourceFile.getId();
            recordBuild(new ModuleBuild(root, path, "MAVEN", anchorId));
            if (sourceFile instanceof Xml.Document) {
                recordMavenModules((Xml.Document) sourceFile, root, path);
                java.util.Optional<MavenResolutionResult> resolution =
                        sourceFile.getMarkers().findFirst(MavenResolutionResult.class);
                if (resolution.isPresent()) {
                    String markerPath = normalize(resolution.get().getPom()
                            .getRequested().getSourcePath());
                    if (!path.equals(markerPath)) {
                        recordEvidence(new ModuleReadinessEvidence(
                                path, "MODULE_TOPOLOGY", "Module ownership topology",
                                "Maven marker/path disagreement",
                                "MODULE_OWNERSHIP_DISAGREEMENT",
                                "The Maven build marker and supplied source path disagree",
                                "Regenerate authoritative project markers before module migration",
                                anchorId, true));
                    }
                }
            }
            if (!(sourceFile instanceof Xml.Document)) {
                recordEvidence(unparsedBuild(path, "MAVEN", sourceFile.getId()));
            }
        } else if ("build.gradle".equals(fileName) || "build.gradle.kts".equals(fileName)) {
            String root = parent(path);
            String buildSystem = "build.gradle".equals(fileName) ?
                    "GRADLE_GROOVY" : "GRADLE_KOTLIN";
            recordBuild(new ModuleBuild(root, path, buildSystem, sourceFile.getId()));
            boolean parsed = "build.gradle".equals(fileName) ?
                    sourceFile instanceof G.CompilationUnit :
                    sourceFile instanceof K.CompilationUnit;
            if (!parsed) {
                recordEvidence(unparsedBuild(path, buildSystem, sourceFile.getId()));
            }
        }
    }

    synchronized void recordGradleProjectReference(
            String sourcePath, String notation, String declarationKind, UUID markerId) {
        String normalizedNotation = notation.startsWith(":") ?
                notation.substring(1) : notation;
        if (normalizedNotation.isEmpty()) {
            return;
        }
        String relative = normalizedNotation.replace(':', '/');
        String declaringRoot = parent(sourcePath);
        Path base = java.nio.file.Paths.get(declaringRoot);
        if ("includeFlat".equals(declarationKind) && base.getParent() != null) {
            base = base.getParent();
        }
        String expectedRoot = normalize(base.resolve(relative));
        expectedModules.add(new ExpectedModule(sourcePath, expectedRoot,
                notation, markerId, "GRADLE"));
    }

    private void recordMavenModules(Xml.Document document, String root, String pomPath) {
        java.util.Optional<Xml.Tag> modules = document.getRoot().getChild("modules");
        if (!modules.isPresent()) {
            return;
        }
        for (Xml.Tag child : modules.get().getChildren("module")) {
            String value = child.getValue().orElse("").trim();
            if (value.isEmpty()) {
                continue;
            }
            String expectedRoot = normalize(java.nio.file.Paths.get(root).resolve(value));
            expectedModules.add(new ExpectedModule(pomPath, expectedRoot,
                    value, child.getId(), "MAVEN"));
        }
    }

    private static ModuleReadinessEvidence unparsedBuild(
            String path, String buildSystem, UUID markerId) {
        return new ModuleReadinessEvidence(path, buildSystem,
                "Incomplete build evidence", "Unparsed build descriptor",
                "MODULE_BUILD_DESCRIPTOR_UNPARSED",
                "The supplied build descriptor was not parsed into its authoritative model",
                "Supply a parseable build descriptor before module migration",
                markerId, true);
    }

    private void recordBuild(ModuleBuild build) {
        List<ModuleBuild> descriptors = builds.get(build.root);
        if (descriptors == null) {
            descriptors = new ArrayList<ModuleBuild>();
            builds.put(build.root, descriptors);
        }
        descriptors.add(build);
    }

    synchronized void recordEvidence(ModuleReadinessEvidence finding) {
        ModuleReadinessEvidence occurrence = finding;
        int suffix = 2;
        while (evidence.containsKey(occurrence.occurrenceKey())) {
            occurrence = finding.withOccurrenceSuffix(suffix++);
        }
        evidence.put(occurrence.occurrenceKey(), occurrence);
    }

    synchronized List<ModuleReadinessEvidence> evidenceSnapshot() {
        return new ArrayList<ModuleReadinessEvidence>(evidence.values());
    }

    synchronized List<ModuleReadinessDecision> decisions() {
        if (planned != null) {
            return planned;
        }
        recordIncompleteReactors();
        List<ResolvedModule> ordered = new ArrayList<ResolvedModule>();
        for (Map.Entry<String, List<ModuleBuild>> entry : builds.entrySet()) {
            ordered.add(resolve(entry.getKey(), entry.getValue()));
        }
        Map<String, List<ModuleArtifact>> orphanGroups = orphanGroups();
        for (Map.Entry<String, List<ModuleArtifact>> entry : orphanGroups.entrySet()) {
            List<ModuleArtifact> group = entry.getValue();
            Collections.sort(group, new Comparator<ModuleArtifact>() {
                @Override
                public int compare(ModuleArtifact left, ModuleArtifact right) {
                    return left.path.compareTo(right.path);
                }
            });
            ModuleArtifact anchor = safeAnchor(group);
            if (anchor == null) {
                anchor = group.get(0);
            }
            ModuleReadinessEvidence missingBuild = new ModuleReadinessEvidence(
                    anchor.path, "MODULE_TOPOLOGY", "Module build topology",
                    displayRoot(entry.getKey()), "MODULE_BUILD_ROOT_MISSING",
                    "No Maven or Gradle build descriptor owns this supplied artifact",
                    "Supply the authoritative module build descriptor before migration",
                    null, false);
            ordered.add(new ResolvedModule(entry.getKey(), anchor.path, "UNRESOLVED",
                    anchor.markerSafe ? anchor.id : null, missingBuild));
        }
        Collections.sort(ordered, new Comparator<ResolvedModule>() {
            @Override
            public int compare(ResolvedModule left, ResolvedModule right) {
                return left.root.compareTo(right.root);
            }
        });
        List<ModuleReadinessDecision> decisions =
                new ArrayList<ModuleReadinessDecision>();
        for (ResolvedModule build : ordered) {
            List<ModuleReadinessEvidence> blockers = evidenceFor(build);
            if (build.ambiguity != null) {
                blockers.add(build.ambiguity);
                sortEvidence(blockers);
            }
            if (blockers.isEmpty()) {
                eligibilityByRoot.put(build.root, true);
                decisions.add(ModuleReadinessDecision.eligible(
                        displayRoot(build.root), build.buildSystem, build.buildPath));
                continue;
            }
            eligibilityByRoot.put(build.root, false);
            if (build.anchorId != null) {
                markers.put(build.anchorId, "REFUSED [MODULE_REFUSED]: " + blockers.size() +
                        (blockers.size() == 1 ? " blocker" : " blockers") +
                        "; no module changes were applied");
            }
            for (ModuleReadinessEvidence blocker : blockers) {
                decisions.add(ModuleReadinessDecision.refused(
                        displayRoot(build.root), build.buildSystem, blocker));
            }
        }
        planned = Collections.unmodifiableList(decisions);
        return planned;
    }

    private void recordIncompleteReactors() {
        for (ExpectedModule expected : expectedModules) {
            List<ModuleBuild> childBuilds = builds.get(expected.expectedRoot);
            boolean compatible = false;
            if (childBuilds != null) {
                for (ModuleBuild childBuild : childBuilds) {
                    if (("MAVEN".equals(expected.buildSystem) &&
                            "MAVEN".equals(childBuild.buildSystem)) ||
                            ("GRADLE".equals(expected.buildSystem) &&
                                    childBuild.buildSystem.startsWith("GRADLE_"))) {
                        compatible = true;
                        break;
                    }
                }
            }
            if (!compatible) {
                boolean mismatch = childBuilds != null && !childBuilds.isEmpty();
                recordEvidence(new ModuleReadinessEvidence(
                        expected.declarationPath, expected.buildSystem,
                        "Incomplete reactor topology", expected.notation,
                        mismatch ? "MODULE_REACTOR_BUILD_MISMATCH" :
                                "MODULE_INCOMPLETE_REACTOR",
                        mismatch ?
                                "A declared child is owned only by an incompatible build system" :
                                "A declared child module build root is absent from the supplied sources",
                        mismatch ?
                                "Supply the child descriptor required by the declaring reactor" :
                                "Supply every declared child build descriptor before module migration",
                        expected.markerId, true));
            }
        }
    }

    synchronized String markerDescription(UUID id) {
        if (planned == null) {
            throw new IllegalStateException("Module readiness plan must be frozen before apply");
        }
        return markers.get(id);
    }

    synchronized boolean isEligibleSourcePath(String sourcePath) {
        if (planned == null) {
            throw new IllegalStateException("Module readiness plan must be frozen before apply");
        }
        String normalized = normalize(java.nio.file.Paths.get(sourcePath));
        String owner = ownerRoot(normalized);
        if (owner == null) {
            owner = inferArtifactRoot(normalized);
        }
        return Boolean.TRUE.equals(eligibilityByRoot.get(owner));
    }

    private List<ModuleReadinessEvidence> evidenceFor(ResolvedModule module) {
        List<ModuleReadinessEvidence> owned = new ArrayList<ModuleReadinessEvidence>();
        for (ModuleReadinessEvidence finding : evidence.values()) {
            if (ModuleReadinessEvidence.ALL_MODULES.equals(finding.sourcePath)) {
                owned.add(finding.atSourcePath(module.buildPath));
                continue;
            }
            String owner = ownerRoot(finding.sourcePath);
            if (module.root.equals(owner) ||
                    ("UNRESOLVED".equals(module.buildSystem) && owner == null &&
                            module.root.equals(inferArtifactRoot(finding.sourcePath)))) {
                owned.add(finding);
            }
        }
        sortEvidence(owned);
        return owned;
    }

    private static void sortEvidence(List<ModuleReadinessEvidence> evidence) {
        Collections.sort(evidence, new Comparator<ModuleReadinessEvidence>() {
            @Override
            public int compare(ModuleReadinessEvidence left, ModuleReadinessEvidence right) {
                int path = left.sourcePath.compareTo(right.sourcePath);
                if (path != 0) {
                    return path;
                }
                int reason = left.reasonCode.compareTo(right.reasonCode);
                if (reason != 0) {
                    return reason;
                }
                int construct = left.construct.compareTo(right.construct);
                return construct != 0 ? construct :
                        left.occurrenceKey().compareTo(right.occurrenceKey());
            }
        });
    }

    private static ResolvedModule resolve(String root, List<ModuleBuild> descriptors) {
        List<ModuleBuild> ordered = new ArrayList<ModuleBuild>(descriptors);
        Collections.sort(ordered, new Comparator<ModuleBuild>() {
            @Override
            public int compare(ModuleBuild left, ModuleBuild right) {
                int mavenFirst = buildPriority(left.buildSystem) -
                                 buildPriority(right.buildSystem);
                return mavenFirst != 0 ? mavenFirst :
                        left.buildPath.compareTo(right.buildPath);
            }
        });
        ModuleBuild anchor = ordered.get(0);
        if (ordered.size() == 1) {
            return new ResolvedModule(root, anchor.buildPath, anchor.buildSystem,
                    anchor.anchorId, null);
        }
        List<String> systems = new ArrayList<String>();
        for (ModuleBuild descriptor : ordered) {
            if (!systems.contains(descriptor.buildSystem)) {
                systems.add(descriptor.buildSystem);
            }
        }
        String construct = String.join("+", systems);
        ModuleReadinessEvidence ambiguity = new ModuleReadinessEvidence(
                anchor.buildPath, "MODULE_TOPOLOGY", "Module build topology", construct,
                "MODULE_BUILD_ROOT_AMBIGUOUS",
                "More than one build descriptor claims the same module root",
                "Select one authoritative build descriptor before module migration",
                null, false);
        return new ResolvedModule(root, anchor.buildPath, "AMBIGUOUS",
                anchor.anchorId, ambiguity);
    }

    private static int buildPriority(String buildSystem) {
        if ("MAVEN".equals(buildSystem)) {
            return 0;
        }
        if ("GRADLE_GROOVY".equals(buildSystem)) {
            return 1;
        }
        return 2;
    }

    private String ownerRoot(String sourcePath) {
        String owner = null;
        for (String root : builds.keySet()) {
            if (owns(root, sourcePath) && (owner == null || root.length() > owner.length())) {
                owner = root;
            }
        }
        return owner;
    }

    private Map<String, List<ModuleArtifact>> orphanGroups() {
        Map<String, List<ModuleArtifact>> groups =
                new LinkedHashMap<String, List<ModuleArtifact>>();
        for (ModuleArtifact artifact : artifacts.values()) {
            if (ownerRoot(artifact.path) != null) {
                continue;
            }
            String root = inferArtifactRoot(artifact.path);
            List<ModuleArtifact> group = groups.get(root);
            if (group == null) {
                group = new ArrayList<ModuleArtifact>();
                groups.put(root, group);
            }
            group.add(artifact);
        }
        return groups;
    }

    private static ModuleArtifact safeAnchor(List<ModuleArtifact> artifacts) {
        for (ModuleArtifact artifact : artifacts) {
            if (artifact.markerSafe) {
                return artifact;
            }
        }
        return null;
    }

    private static String inferArtifactRoot(String sourcePath) {
        int source = sourcePath.indexOf("/src/");
        if (source >= 0) {
            return sourcePath.substring(0, source);
        }
        if (sourcePath.startsWith("src/")) {
            return "";
        }
        return parent(sourcePath);
    }

    private static boolean owns(String root, String sourcePath) {
        return root.isEmpty() || sourcePath.equals(root) || sourcePath.startsWith(root + '/');
    }

    static String normalize(Path path) {
        return path.normalize().toString().replace(File.separatorChar, '/');
    }

    static String javaSourceKind(String sourcePath) {
        String[] segments = sourcePath.split("/");
        for (int i = 0; i + 2 < segments.length; i++) {
            if (!"src".equals(segments[i]) || !"java".equals(segments[i + 2])) {
                continue;
            }
            String sourceSet = segments[i + 1].toLowerCase(java.util.Locale.ROOT);
            if ("main".equals(sourceSet)) {
                return "JAVA_MAIN";
            }
            if (sourceSet.contains("test") || "it".equals(sourceSet) ||
                    sourceSet.contains("integration") || sourceSet.contains("acceptance")) {
                return "JAVA_TEST";
            }
            return "JAVA_SOURCE";
        }
        return "JAVA_SOURCE";
    }

    private static String parent(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator);
    }

    private static String displayRoot(String root) {
        return root.isEmpty() ? "." : root;
    }

    private static final class ModuleBuild {
        private final String root;
        private final String buildPath;
        private final String buildSystem;
        private final UUID anchorId;

        private ModuleBuild(String root, String buildPath, String buildSystem, UUID anchorId) {
            this.root = root;
            this.buildPath = buildPath;
            this.buildSystem = buildSystem;
            this.anchorId = anchorId;
        }
    }

    private static final class ModuleArtifact {
        private final String path;
        private final UUID id;
        private final boolean markerSafe;

        private ModuleArtifact(String path, UUID id, boolean markerSafe) {
            this.path = path;
            this.id = id;
            this.markerSafe = markerSafe;
        }
    }

    private static final class ExpectedModule {
        private final String declarationPath;
        private final String expectedRoot;
        private final String notation;
        private final UUID markerId;
        private final String buildSystem;

        private ExpectedModule(String declarationPath, String expectedRoot,
                               String notation, UUID markerId, String buildSystem) {
            this.declarationPath = declarationPath;
            this.expectedRoot = expectedRoot;
            this.notation = notation;
            this.markerId = markerId;
            this.buildSystem = buildSystem;
        }
    }

    private static final class ResolvedModule {
        private final String root;
        private final String buildPath;
        private final String buildSystem;
        private final UUID anchorId;
        private final ModuleReadinessEvidence ambiguity;

        private ResolvedModule(String root, String buildPath, String buildSystem,
                               UUID anchorId, ModuleReadinessEvidence ambiguity) {
            this.root = root;
            this.buildPath = buildPath;
            this.buildSystem = buildSystem;
            this.anchorId = anchorId;
            this.ambiguity = ambiguity;
        }
    }
}
