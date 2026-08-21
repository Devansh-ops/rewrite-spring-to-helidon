package io.github.devanshops.rewrite.helidon;

import java.util.UUID;

/** Redacted, occurrence-level evidence considered by the module readiness planner. */
final class ModuleReadinessEvidence {
    static final String ALL_MODULES = "<all-supplied-modules>";
    final String sourcePath;
    final String sourceKind;
    final String feature;
    final String construct;
    final String reasonCode;
    final String reason;
    final String suggestedDirection;
    final UUID markerId;
    final boolean markerSafe;
    private final String occurrenceToken;

    ModuleReadinessEvidence(String sourcePath, String sourceKind, String feature,
                            String construct, String reasonCode, String reason,
                            String suggestedDirection, UUID markerId, boolean markerSafe) {
        this(sourcePath, sourceKind, feature, construct, reasonCode, reason,
                suggestedDirection, markerId, markerSafe, null);
    }

    private ModuleReadinessEvidence(String sourcePath, String sourceKind, String feature,
                                    String construct, String reasonCode, String reason,
                                    String suggestedDirection, UUID markerId,
                                    boolean markerSafe, String occurrenceToken) {
        this.sourcePath = sourcePath;
        this.sourceKind = sourceKind;
        this.feature = feature;
        this.construct = construct;
        this.reasonCode = reasonCode;
        this.reason = reason;
        this.suggestedDirection = suggestedDirection;
        this.markerId = markerId;
        this.markerSafe = markerSafe;
        this.occurrenceToken = occurrenceToken;
    }

    String evidenceKey() {
        return sourcePath + '\u0000' + sourceKind + '\u0000' + feature + '\u0000' +
               construct + '\u0000' + reasonCode;
    }

    String occurrenceKey() {
        return evidenceKey() + '\u0000' + locationToken();
    }

    String markerDescription() {
        return "REFUSED [" + reasonCode + "]: " + reason;
    }

    ModuleReadinessEvidence atSourcePath(String path) {
        return new ModuleReadinessEvidence(path, sourceKind, feature, construct,
                reasonCode, reason, suggestedDirection, markerId, markerSafe,
                occurrenceToken);
    }

    ModuleReadinessEvidence withOccurrenceSuffix(int suffix) {
        return new ModuleReadinessEvidence(sourcePath, sourceKind, feature, construct,
                reasonCode, reason, suggestedDirection, markerId, markerSafe,
                locationToken() + '#' + suffix);
    }

    private String locationToken() {
        if (occurrenceToken != null) {
            return occurrenceToken;
        }
        return markerId == null ? "unlocated" : markerId.toString();
    }
}
