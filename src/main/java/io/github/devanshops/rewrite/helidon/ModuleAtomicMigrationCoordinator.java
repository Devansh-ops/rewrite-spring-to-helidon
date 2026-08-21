package io.github.devanshops.rewrite.helidon;

import org.openrewrite.SourceFile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Collects family claims and candidates, validates a projected module, freezes
 * complete decisions, then exposes a decision-free apply plan.
 */
final class ModuleAtomicMigrationCoordinator {
    private final ModuleReadinessIndex collectedReadiness = new ModuleReadinessIndex();
    private final Map<UUID, SourceFile> sourcesById =
            new LinkedHashMap<UUID, SourceFile>();
    private final Map<String, SourceFile> sourcesByPath =
            new LinkedHashMap<String, SourceFile>();
    private final Map<UUID, List<ReplacementCandidate>> replacementCandidates =
            new LinkedHashMap<UUID, List<ReplacementCandidate>>();
    private final Map<String, List<SourceFile>> generationCandidates =
            new LinkedHashMap<String, List<SourceFile>>();
    private final Map<UUID, Integer> occurrenceClaims = new HashMap<UUID, Integer>();
    private final Map<String, Integer> evidenceKeyClaims = new HashMap<String, Integer>();
    private final List<ModuleReadinessEvidence> collectionProblems =
            new ArrayList<ModuleReadinessEvidence>();
    private ModuleReadinessIndex frozenReadiness;
    private Map<UUID, SourceFile> replacements = Collections.emptyMap();
    private List<SourceFile> generated = Collections.emptyList();
    private boolean frozen;

    synchronized void record(SourceFile sourceFile) {
        requireCollecting();
        String path = normalize(sourceFile);
        SourceFile atPath = sourcesByPath.get(path);
        SourceFile atId = sourcesById.get(sourceFile.getId());
        if (atPath != null && !atPath.getId().equals(sourceFile.getId())) {
            collectionProblems.add(planProblem(path, "MODULE_PLAN_CONFLICT",
                    "More than one supplied source claims the same path"));
            return;
        }
        if (atId != null && !normalize(atId).equals(path)) {
            collectionProblems.add(planProblem(path, "MODULE_PLAN_CONFLICT",
                    "One supplied source identity claims conflicting paths"));
            return;
        }
        collectedReadiness.record(sourceFile);
        sourcesById.put(sourceFile.getId(), sourceFile);
        sourcesByPath.put(path, sourceFile);
    }

    ModuleReadinessIndex readinessIndex() {
        return collectedReadiness;
    }

    synchronized void claimEvidence(UUID occurrenceId) {
        requireCollecting();
        occurrenceClaims.put(occurrenceId,
                occurrenceClaims.containsKey(occurrenceId) ?
                        occurrenceClaims.get(occurrenceId) + 1 : 1);
    }

    synchronized void claimEvidenceKey(String evidenceKey) {
        requireCollecting();
        evidenceKeyClaims.put(evidenceKey,
                evidenceKeyClaims.containsKey(evidenceKey) ?
                        evidenceKeyClaims.get(evidenceKey) + 1 : 1);
    }

    synchronized void proposeReplacement(SourceFile before, SourceFile after) {
        requireCollecting();
        String beforePath = normalize(before);
        String afterPath = normalize(after);
        SourceFile collected = sourcesById.get(before.getId());
        if (collected != before || sourcesByPath.get(beforePath) != before ||
                !before.getId().equals(after.getId()) || !beforePath.equals(afterPath)) {
            addGlobalProblem(collectionProblems, "MODULE_PLAN_CONFLICT",
                    "A replacement must reference the exact collected source and preserve its ID and path");
            return;
        }
        List<ReplacementCandidate> candidates = replacementCandidates.get(before.getId());
        if (candidates == null) {
            candidates = new ArrayList<ReplacementCandidate>();
            replacementCandidates.put(before.getId(), candidates);
        }
        candidates.add(new ReplacementCandidate(beforePath, after));
    }

    synchronized void proposeGeneration(SourceFile sourceFile) {
        requireCollecting();
        String path = normalize(sourceFile);
        List<SourceFile> candidates = generationCandidates.get(path);
        if (candidates == null) {
            candidates = new ArrayList<SourceFile>();
            generationCandidates.put(path, candidates);
        }
        candidates.add(sourceFile);
    }

    synchronized void freeze(Collection<SourceFile> generatedInThisCycle) {
        if (frozen) {
            return;
        }

        List<ModuleReadinessEvidence> planProblems =
                new ArrayList<ModuleReadinessEvidence>();
        planProblems.addAll(collectionProblems);
        Map<UUID, ReplacementCandidate> selectedReplacements =
                selectReplacements(planProblems);
        Map<String, List<SourceFile>> externalGeneratedByPath =
                new HashMap<String, List<SourceFile>>();
        for (SourceFile sourceFile : generatedInThisCycle) {
            String path = normalize(sourceFile);
            List<SourceFile> atPath = externalGeneratedByPath.get(path);
            if (atPath == null) {
                atPath = new ArrayList<SourceFile>();
                externalGeneratedByPath.put(path, atPath);
            }
            atPath.add(sourceFile);
        }
        Set<String> externalGeneratedPaths = externalGeneratedByPath.keySet();
        for (Map.Entry<String, List<SourceFile>> external :
                externalGeneratedByPath.entrySet()) {
            if (external.getValue().size() > 1) {
                planProblems.add(planProblem(external.getKey(),
                        "MODULE_GENERATED_PATH_COLLISION",
                        "More than one external generated source claims the same path"));
            }
            SourceFile recorded = sourcesByPath.get(external.getKey());
            if (recorded != null && containsNonEquivalent(
                    external.getValue(), recorded)) {
                planProblems.add(planProblem(external.getKey(),
                        "MODULE_GENERATED_PATH_COLLISION",
                        "An external generated source collides with a supplied source path"));
            }
        }
        Map<String, SourceFile> selectedGeneration = selectGeneration(
                externalGeneratedPaths, planProblems);

        Map<String, SourceFile> projected = new LinkedHashMap<String, SourceFile>();
        for (SourceFile original : ordered(sourcesByPath.values())) {
            ReplacementCandidate replacement = selectedReplacements.get(original.getId());
            SourceFile projectedSource = replacement == null ? original : replacement.after;
            projected.put(normalize(projectedSource), projectedSource);
        }
        for (SourceFile sourceFile : ordered(generatedInThisCycle)) {
            if (!projected.containsKey(normalize(sourceFile))) {
                projected.put(normalize(sourceFile), sourceFile);
            }
        }
        for (Map.Entry<String, SourceFile> generation : selectedGeneration.entrySet()) {
            projected.put(generation.getKey(), generation.getValue());
        }

        ModuleReadinessIndex projectedReadiness = new ModuleReadinessIndex();
        for (SourceFile sourceFile : ordered(projected.values())) {
            projectedReadiness.record(sourceFile);
            ModuleReadinessEvidenceScanner.scanSource(sourceFile, projectedReadiness);
        }
        validateEvidenceClaims(selectedReplacements, projectedReadiness, planProblems);
        for (ModuleReadinessEvidence problem : planProblems) {
            projectedReadiness.recordEvidence(problem);
        }
        projectedReadiness.decisions();

        Map<UUID, SourceFile> committedReplacements = new HashMap<UUID, SourceFile>();
        for (ReplacementCandidate candidate : orderedReplacements(
                selectedReplacements.values())) {
            if (projectedReadiness.isEligibleSourcePath(candidate.path)) {
                committedReplacements.put(candidate.after.getId(), candidate.after);
            }
        }
        List<SourceFile> committedGenerated = new ArrayList<SourceFile>();
        List<String> generationPaths = new ArrayList<String>(selectedGeneration.keySet());
        Collections.sort(generationPaths);
        for (String path : generationPaths) {
            if (projectedReadiness.isEligibleSourcePath(path)) {
                committedGenerated.add(selectedGeneration.get(path));
            }
        }

        frozenReadiness = projectedReadiness;
        replacements = Collections.unmodifiableMap(committedReplacements);
        generated = Collections.unmodifiableList(committedGenerated);
        frozen = true;
    }

    private static boolean containsNonEquivalent(
            List<SourceFile> sources, SourceFile recorded) {
        for (SourceFile source : sources) {
            if (!recorded.getId().equals(source.getId()) ||
                    !equivalent(recorded, source)) {
                return true;
            }
        }
        return false;
    }

    synchronized List<ModuleReadinessDecision> decisions() {
        requireFrozen();
        return frozenReadiness.decisions();
    }

    synchronized Collection<? extends SourceFile> generatedSources() {
        requireFrozen();
        return generated;
    }

    synchronized SourceFile applyReplacement(SourceFile sourceFile) {
        requireFrozen();
        SourceFile replacement = replacements.get(sourceFile.getId());
        return replacement == null ? sourceFile : replacement;
    }

    synchronized String markerDescription(UUID id) {
        requireFrozen();
        return frozenReadiness.markerDescription(id);
    }

    private Map<UUID, ReplacementCandidate> selectReplacements(
            List<ModuleReadinessEvidence> problems) {
        Map<UUID, ReplacementCandidate> selected =
                new LinkedHashMap<UUID, ReplacementCandidate>();
        for (Map.Entry<UUID, List<ReplacementCandidate>> entry :
                replacementCandidates.entrySet()) {
            List<ReplacementCandidate> candidates = entry.getValue();
            if (candidates.size() != 1) {
                problems.add(planProblem(candidates.get(0).path, "MODULE_PLAN_CONFLICT",
                        "Multiple replacement candidates claim the same source"));
            } else {
                selected.put(entry.getKey(), candidates.get(0));
            }
        }
        return selected;
    }

    private Map<String, SourceFile> selectGeneration(
            Set<String> externalGeneratedPaths,
            List<ModuleReadinessEvidence> problems) {
        Map<String, SourceFile> selected = new LinkedHashMap<String, SourceFile>();
        List<String> paths = new ArrayList<String>(generationCandidates.keySet());
        Collections.sort(paths);
        for (String path : paths) {
            List<SourceFile> candidates = generationCandidates.get(path);
            if (candidates.size() != 1) {
                problems.add(planProblem(path, "MODULE_PLAN_CONFLICT",
                        "Multiple generated candidates claim the same path"));
                continue;
            }
            SourceFile existing = sourcesByPath.get(path);
            if (existing != null && equivalent(existing, candidates.get(0))) {
                continue;
            }
            if (existing != null || externalGeneratedPaths.contains(path)) {
                problems.add(planProblem(path, "MODULE_GENERATED_PATH_COLLISION",
                        "A planned generated path is already owned or claimed"));
                continue;
            }
            selected.put(path, candidates.get(0));
        }
        return selected;
    }

    private void validateEvidenceClaims(
            Map<UUID, ReplacementCandidate> selectedReplacements,
            ModuleReadinessIndex projectedReadiness,
            List<ModuleReadinessEvidence> problems) {
        List<ModuleReadinessEvidence> originals = collectedReadiness.evidenceSnapshot();
        List<ModuleReadinessEvidence> projected = projectedReadiness.evidenceSnapshot();
        Map<UUID, List<ModuleReadinessEvidence>> originalById =
                new HashMap<UUID, List<ModuleReadinessEvidence>>();
        Map<String, List<ModuleReadinessEvidence>> originalByKey =
                new HashMap<String, List<ModuleReadinessEvidence>>();
        Set<UUID> projectedOccurrenceIds = new HashSet<UUID>();
        for (ModuleReadinessEvidence evidence : originals) {
            List<ModuleReadinessEvidence> atKey = originalByKey.get(evidence.evidenceKey());
            if (atKey == null) {
                atKey = new ArrayList<ModuleReadinessEvidence>();
                originalByKey.put(evidence.evidenceKey(), atKey);
            }
            atKey.add(evidence);
            if (evidence.markerId != null) {
                List<ModuleReadinessEvidence> atId = originalById.get(evidence.markerId);
                if (atId == null) {
                    atId = new ArrayList<ModuleReadinessEvidence>();
                    originalById.put(evidence.markerId, atId);
                }
                atId.add(evidence);
            }
        }
        for (ModuleReadinessEvidence evidence : projected) {
            if (evidence.markerId != null) {
                projectedOccurrenceIds.add(evidence.markerId);
            }
        }

        Set<String> claimedOccurrences = new HashSet<String>();
        for (Map.Entry<UUID, Integer> claim : occurrenceClaims.entrySet()) {
            List<ModuleReadinessEvidence> matches = originalById.get(claim.getKey());
            if (claim.getValue() != 1 || matches == null || matches.size() != 1) {
                addGlobalProblem(problems, "MODULE_PLAN_CONFLICT",
                        "An occurrence must resolve and be claimed exactly once by one family");
                continue;
            }
            validateClaim(matches.get(0), selectedReplacements, projectedOccurrenceIds,
                    claimedOccurrences, problems);
        }
        for (Map.Entry<String, Integer> claim : evidenceKeyClaims.entrySet()) {
            List<ModuleReadinessEvidence> matches = originalByKey.get(claim.getKey());
            if (claim.getValue() != 1 || matches == null || matches.size() != 1) {
                addGlobalProblem(problems, "MODULE_PLAN_CONFLICT",
                        "An evidence key must resolve and be claimed exactly once by one family");
                continue;
            }
            ModuleReadinessEvidence match = matches.get(0);
            if (claimedOccurrences.contains(match.occurrenceKey())) {
                addGlobalProblem(problems, "MODULE_PLAN_CONFLICT",
                        "The same evidence was claimed through more than one claim API");
                continue;
            }
            validateClaim(match, selectedReplacements, projectedOccurrenceIds,
                    claimedOccurrences, problems);
        }

        Set<String> replacementPaths = new HashSet<String>();
        for (ReplacementCandidate replacement : selectedReplacements.values()) {
            replacementPaths.add(replacement.path);
        }
        for (ModuleReadinessEvidence original : originals) {
            if (replacementPaths.contains(original.sourcePath) &&
                    (original.markerId == null ||
                            !projectedOccurrenceIds.contains(original.markerId)) &&
                    !claimedOccurrences.contains(original.occurrenceKey())) {
                problems.add(planProblem(original.sourcePath,
                        "MODULE_UNCLAIMED_EVIDENCE_REMOVAL",
                        "A replacement removed migration evidence it did not explicitly claim"));
            }
        }
    }

    private static void validateClaim(
            ModuleReadinessEvidence evidence,
            Map<UUID, ReplacementCandidate> selectedReplacements,
            Set<UUID> projectedOccurrenceIds,
            Set<String> claimedOccurrences,
            List<ModuleReadinessEvidence> problems) {
        claimedOccurrences.add(evidence.occurrenceKey());
        boolean hasReplacement = false;
        for (ReplacementCandidate replacement : selectedReplacements.values()) {
            if (evidence.sourcePath.equals(replacement.path)) {
                hasReplacement = true;
                break;
            }
        }
        if (!hasReplacement) {
            problems.add(planProblem(evidence.sourcePath, "MODULE_PLAN_CONFLICT",
                    "Claimed evidence has no replacement in the same source"));
        } else if (evidence.markerId != null &&
                projectedOccurrenceIds.contains(evidence.markerId)) {
            problems.add(planProblem(evidence.sourcePath,
                    "MODULE_CLAIM_NOT_NEUTRALIZED",
                    "The projected replacement still contains the claimed evidence"));
        }
    }

    private void addGlobalProblem(List<ModuleReadinessEvidence> problems,
                                  String reasonCode, String reason) {
        problems.add(planProblem(ModuleReadinessEvidence.ALL_MODULES,
                reasonCode, reason));
    }

    private static ModuleReadinessEvidence planProblem(
            String path, String reasonCode, String reason) {
        return new ModuleReadinessEvidence(path, "MODULE_PLAN", "Module migration plan",
                path, reasonCode, reason,
                "Resolve the family plan conflict before module migration", null, false);
    }

    private static boolean equivalent(SourceFile left, SourceFile right) {
        return left.getClass().equals(right.getClass()) &&
               left.printAll().equals(right.printAll());
    }

    private static List<SourceFile> ordered(Collection<? extends SourceFile> sources) {
        List<SourceFile> ordered = new ArrayList<SourceFile>(sources);
        Collections.sort(ordered, new Comparator<SourceFile>() {
            @Override
            public int compare(SourceFile left, SourceFile right) {
                return normalize(left).compareTo(normalize(right));
            }
        });
        return ordered;
    }

    private static List<ReplacementCandidate> orderedReplacements(
            Collection<ReplacementCandidate> candidates) {
        List<ReplacementCandidate> ordered =
                new ArrayList<ReplacementCandidate>(candidates);
        Collections.sort(ordered, new Comparator<ReplacementCandidate>() {
            @Override
            public int compare(ReplacementCandidate left, ReplacementCandidate right) {
                return left.path.compareTo(right.path);
            }
        });
        return ordered;
    }

    private static String normalize(SourceFile sourceFile) {
        return ModuleReadinessIndex.normalize(sourceFile.getSourcePath());
    }

    private void requireCollecting() {
        if (frozen) {
            throw new IllegalStateException("The module plan is already frozen");
        }
    }

    private void requireFrozen() {
        if (!frozen) {
            throw new IllegalStateException("The module plan has not been frozen");
        }
    }

    private static final class ReplacementCandidate {
        private final String path;
        private final SourceFile after;

        private ReplacementCandidate(String path, SourceFile after) {
            this.path = path;
            this.after = after;
        }
    }
}
