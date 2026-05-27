package com.example.ossdoc.domain.rule.service.miner;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.rule.entity.RuleCandidate;
import com.example.ossdoc.domain.rule.entity.RuleCandidateEvidence;
import com.example.ossdoc.domain.rule.entity.RuleMiningSignal;
import com.example.ossdoc.domain.rule.enums.RuleCandidateConfidence;
import com.example.ossdoc.domain.rule.enums.RuleCandidateKind;
import com.example.ossdoc.domain.rule.enums.RuleCandidateSource;
import com.example.ossdoc.domain.rule.repository.RuleCandidateEvidenceRepository;
import com.example.ossdoc.domain.rule.repository.RuleCandidateRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public abstract class AbstractRuleCandidateMiner implements RuleCandidateMiner {

    protected static final BigDecimal WEIGHT_PRIMARY = new BigDecimal("1.0000");
    protected static final BigDecimal WEIGHT_SUPPORTING = new BigDecimal("0.7000");

    private static final int MAX_SNIPPET_LENGTH = 500;
    private static final int MAX_NOTE_LENGTH = 300;

    protected final RuleCandidateRepository ruleCandidateRepository;
    protected final RuleCandidateEvidenceRepository ruleCandidateEvidenceRepository;

    protected AbstractRuleCandidateMiner(
            RuleCandidateRepository ruleCandidateRepository,
            RuleCandidateEvidenceRepository ruleCandidateEvidenceRepository
    ) {
        this.ruleCandidateRepository = ruleCandidateRepository;
        this.ruleCandidateEvidenceRepository = ruleCandidateEvidenceRepository;
    }

    protected int saveCandidateDrafts(
            RepoRun run,
            Collection<CandidateDraft> drafts
    ) {
        if (run == null || drafts == null || drafts.isEmpty()) {
            return 0;
        }

        List<CandidateDraft> mergedDrafts = mergeDraftsByRuleKey(drafts);
        if (mergedDrafts.isEmpty()) {
            return 0;
        }

        List<String> ruleKeys = mergedDrafts.stream()
                .map(CandidateDraft::ruleKey)
                .filter(ruleKey -> ruleKey != null && !ruleKey.isBlank())
                .toList();

        if (ruleKeys.isEmpty()) {
            return 0;
        }

        Map<String, RuleCandidate> existingByRuleKey =
                loadExistingCandidatesByRuleKey(run.getRunId(), ruleKeys);

        List<Long> existingCandidateIds = existingByRuleKey.values().stream()
                .map(RuleCandidate::getCandidateId)
                .filter(id -> id != null)
                .toList();

        if (!existingCandidateIds.isEmpty()) {
            ruleCandidateEvidenceRepository.deleteByCandidateIdInBulk(existingCandidateIds);
        }

        List<RuleCandidate> candidatesToSave = new ArrayList<>();

        for (CandidateDraft draft : mergedDrafts) {
            if (draft.ruleKey() == null || draft.ruleKey().isBlank()) {
                continue;
            }

            RuleCandidate existing = existingByRuleKey.get(draft.ruleKey());

            if (existing != null) {
                existing.updateMiningResult(
                        draft.confidence(),
                        draft.source(),
                        draft.title(),
                        draft.description(),
                        draft.score(),
                        draft.supportCount(),
                        draft.publicApiRelated(),
                        draft.summary(),
                        draft.impact(),
                        draft.meta()
                );
                candidatesToSave.add(existing);
                continue;
            }

            candidatesToSave.add(RuleCandidate.of(
                    run,
                    draft.ruleKey(),
                    draft.kind(),
                    draft.confidence(),
                    draft.source(),
                    draft.subjectSymbol(),
                    draft.groupId(),
                    draft.title(),
                    draft.description(),
                    draft.fingerprint(),
                    draft.score(),
                    draft.supportCount(),
                    draft.publicApiRelated(),
                    draft.summary(),
                    draft.impact(),
                    draft.meta()
            ));
        }

        if (candidatesToSave.isEmpty()) {
            return 0;
        }

        List<RuleCandidate> savedCandidates = ruleCandidateRepository.saveAll(candidatesToSave);

        Map<String, RuleCandidate> savedByRuleKey = new HashMap<>();
        for (RuleCandidate candidate : savedCandidates) {
            if (candidate.getRuleKey() != null) {
                savedByRuleKey.put(candidate.getRuleKey(), candidate);
            }
        }

        List<RuleCandidateEvidence> evidenceLinks = buildEvidenceLinks(mergedDrafts, savedByRuleKey);

        if (!evidenceLinks.isEmpty()) {
            ruleCandidateEvidenceRepository.saveAll(evidenceLinks);
        }

        return savedCandidates.size();
    }

    private Map<String, RuleCandidate> loadExistingCandidatesByRuleKey(
            String runId,
            Collection<String> ruleKeys
    ) {
        if (runId == null || runId.isBlank() || ruleKeys == null || ruleKeys.isEmpty()) {
            return Map.of();
        }

        List<RuleCandidate> existingCandidates =
                ruleCandidateRepository.findAllByRun_RunIdAndRuleKeyIn(runId, ruleKeys);

        if (existingCandidates == null || existingCandidates.isEmpty()) {
            return Map.of();
        }

        Map<String, RuleCandidate> result = new HashMap<>();
        for (RuleCandidate candidate : existingCandidates) {
            if (candidate.getRuleKey() != null) {
                result.put(candidate.getRuleKey(), candidate);
            }
        }

        return result;
    }

    private List<CandidateDraft> mergeDraftsByRuleKey(Collection<CandidateDraft> drafts) {
        Map<String, CandidateDraft> merged = new LinkedHashMap<>();

        for (CandidateDraft draft : drafts) {
            if (draft == null || draft.ruleKey() == null || draft.ruleKey().isBlank()) {
                continue;
            }

            CandidateDraft existing = merged.get(draft.ruleKey());
            if (existing == null) {
                merged.put(draft.ruleKey(), draft);
                continue;
            }

            existing.evidences().addAll(draft.evidences());
        }

        return new ArrayList<>(merged.values());
    }

    private List<RuleCandidateEvidence> buildEvidenceLinks(
            List<CandidateDraft> drafts,
            Map<String, RuleCandidate> savedByRuleKey
    ) {
        List<RuleCandidateEvidence> links = new ArrayList<>();
        Set<String> evidenceKeys = new HashSet<>();

        for (CandidateDraft draft : drafts) {
            RuleCandidate candidate = savedByRuleKey.get(draft.ruleKey());
            if (candidate == null || candidate.getCandidateId() == null) {
                continue;
            }

            for (CandidateEvidenceDraft evidenceDraft : draft.evidences()) {
                if (evidenceDraft == null) {
                    continue;
                }

                String evidenceKey = evidenceKey(candidate, evidenceDraft);
                if (!evidenceKeys.add(evidenceKey)) {
                    continue;
                }

                Evidence evidence = evidenceDraft.evidence();
                Edge edge = evidenceDraft.edge();

                links.add(RuleCandidateEvidence.of(
                        candidate,
                        evidenceDraft.signal(),
                        evidence,
                        edge,
                        evidenceDraft.role(),
                        evidenceDraft.weight(),
                        filePath(evidence),
                        evidence == null ? null : evidence.getStartLine(),
                        evidence == null ? null : evidence.getEndLine(),
                        evidence == null ? null : truncate(evidence.getSnippet(), MAX_SNIPPET_LENGTH),
                        truncate(evidenceDraft.note(), MAX_NOTE_LENGTH)
                ));
            }
        }

        return links;
    }

    private String evidenceKey(
            RuleCandidate candidate,
            CandidateEvidenceDraft draft
    ) {
        return candidate.getCandidateId() + "|"
                + id(draft.signal()) + "|"
                + id(draft.evidence()) + "|"
                + id(draft.edge()) + "|"
                + safeKey(draft.role());
    }

    private String id(RuleMiningSignal signal) {
        return signal == null || signal.getSignalId() == null
                ? "-"
                : String.valueOf(signal.getSignalId());
    }

    private String id(Evidence evidence) {
        return evidence == null || evidence.getEvidenceId() == null
                ? "-"
                : String.valueOf(evidence.getEvidenceId());
    }

    private String id(Edge edge) {
        return edge == null || edge.getEdgeId() == null
                ? "-"
                : String.valueOf(edge.getEdgeId());
    }

    private String safeKey(String value) {
        return value == null ? "-" : value;
    }

    protected CandidateDraft candidateDraft(
            String ruleKey,
            RuleCandidateKind kind,
            RuleCandidateConfidence confidence,
            RuleCandidateSource source,
            SymbolEntity subjectSymbol,
            String groupId,
            String title,
            String description,
            String fingerprint,
            BigDecimal score,
            Integer supportCount,
            Boolean publicApiRelated,
            JsonNode summary,
            JsonNode impact,
            JsonNode meta,
            List<CandidateEvidenceDraft> evidences
    ) {
        return new CandidateDraft(
                ruleKey,
                kind,
                confidence,
                source,
                subjectSymbol,
                groupId,
                title,
                description,
                fingerprint,
                score,
                supportCount,
                publicApiRelated,
                summary,
                impact,
                meta,
                evidences
        );
    }

    protected CandidateEvidenceDraft evidenceDraft(
            RuleMiningSignal signal,
            String role,
            BigDecimal weight,
            String note
    ) {
        if (signal == null) {
            return null;
        }

        return new CandidateEvidenceDraft(
                signal,
                signal.getEvidence(),
                signal.getEdge(),
                role,
                weight,
                note
        );
    }

    protected String filePath(Evidence evidence) {
        if (evidence == null || evidence.getFile() == null) {
            return null;
        }
        return evidence.getFile().getPath();
    }

    protected String safeSymbolId(SymbolEntity symbol) {
        return symbol == null ? "unknown-symbol" : symbol.getSymbolId();
    }

    protected String safeSymbolName(SymbolEntity symbol) {
        if (symbol == null) {
            return "unknown symbol";
        }
        if (symbol.getQualifiedName() != null && !symbol.getQualifiedName().isBlank()) {
            return symbol.getQualifiedName();
        }
        return symbol.getSymbolId();
    }

    protected String normalizeKeyPart(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }

        return value
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "_")
                .replaceAll("[^a-zA-Z0-9_:#.\\-]", "_");
    }

    protected ObjectNode objectNode() {
        return JsonNodeFactory.instance.objectNode();
    }

    protected ObjectNode summaryNode(String pattern, String condition, String action) {
        ObjectNode node = objectNode();
        node.put("pattern", pattern);
        if (condition != null) {
            node.put("condition", condition);
        }
        if (action != null) {
            node.put("action", action);
        }
        return node;
    }

    protected ObjectNode impactNode(String level, String reason) {
        ObjectNode node = objectNode();
        node.put("level", level);
        node.put("reason", reason);
        return node;
    }

    protected ObjectNode metaNode(String minerName) {
        ObjectNode node = objectNode();
        node.put("miner", minerName);
        node.put("version", "1.0");
        return node;
    }

    protected BigDecimal score(RuleCandidateConfidence confidence, int supportCount) {
        BigDecimal base = switch (confidence) {
            case HIGH -> new BigDecimal("0.8500");
            case MEDIUM -> new BigDecimal("0.6500");
            case LOW -> new BigDecimal("0.4000");
        };

        BigDecimal supportBonus = new BigDecimal(Math.min(supportCount, 5))
                .multiply(new BigDecimal("0.0300"));

        return base.add(supportBonus).min(new BigDecimal("0.9900"));
    }

    protected RuleCandidateConfidence confidenceByDistance(int lineDistance) {
        if (lineDistance >= 0 && lineDistance <= 3) {
            return RuleCandidateConfidence.HIGH;
        }
        if (lineDistance <= 8) {
            return RuleCandidateConfidence.MEDIUM;
        }
        return RuleCandidateConfidence.LOW;
    }

    protected String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    protected String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }

        if (maxLength <= 0 || text.length() <= maxLength) {
            return text;
        }

        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    protected record CandidateDraft(
            String ruleKey,
            RuleCandidateKind kind,
            RuleCandidateConfidence confidence,
            RuleCandidateSource source,
            SymbolEntity subjectSymbol,
            String groupId,
            String title,
            String description,
            String fingerprint,
            BigDecimal score,
            Integer supportCount,
            Boolean publicApiRelated,
            JsonNode summary,
            JsonNode impact,
            JsonNode meta,
            List<CandidateEvidenceDraft> evidences
    ) {
        public CandidateDraft {
            evidences = evidences == null
                    ? new ArrayList<>()
                    : new ArrayList<>(evidences.stream()
                    .filter(evidence -> evidence != null)
                    .toList());
        }
    }

    protected record CandidateEvidenceDraft(
            RuleMiningSignal signal,
            Evidence evidence,
            Edge edge,
            String role,
            BigDecimal weight,
            String note
    ) {
    }
}