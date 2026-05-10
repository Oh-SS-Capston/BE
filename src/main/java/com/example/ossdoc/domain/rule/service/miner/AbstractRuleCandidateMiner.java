package com.example.ossdoc.domain.rule.service.miner;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.Evidence;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public abstract class AbstractRuleCandidateMiner implements RuleCandidateMiner {

    protected static final BigDecimal WEIGHT_PRIMARY = new BigDecimal("1.0000");
    protected static final BigDecimal WEIGHT_SUPPORTING = new BigDecimal("0.7000");

    protected final RuleCandidateRepository ruleCandidateRepository;
    protected final RuleCandidateEvidenceRepository ruleCandidateEvidenceRepository;

    protected AbstractRuleCandidateMiner(
            RuleCandidateRepository ruleCandidateRepository,
            RuleCandidateEvidenceRepository ruleCandidateEvidenceRepository
    ) {
        this.ruleCandidateRepository = ruleCandidateRepository;
        this.ruleCandidateEvidenceRepository = ruleCandidateEvidenceRepository;
    }

    protected RuleCandidate upsertCandidate(
            RepoRun run,
            String ruleKey,
            RuleCandidateKind kind,
            RuleCandidateConfidence confidence,
            RuleCandidateSource source,
            com.example.ossdoc.domain.graphstore.entity.SymbolEntity subjectSymbol,
            String groupId,
            String title,
            String description,
            String fingerprint,
            BigDecimal score,
            Integer supportCount,
            Boolean publicApiRelated,
            JsonNode summary,
            JsonNode impact,
            JsonNode meta
    ) {
        Optional<RuleCandidate> existing =
                ruleCandidateRepository.findByRun_RunIdAndRuleKey(run.getRunId(), ruleKey);

        RuleCandidate candidate;

        if (existing.isPresent()) {
            candidate = existing.get();
            candidate.updateMiningResult(
                    confidence,
                    source,
                    title,
                    description,
                    score,
                    supportCount,
                    publicApiRelated,
                    summary,
                    impact,
                    meta
            );
            candidate = ruleCandidateRepository.save(candidate);

            if (candidate.getCandidateId() != null) {
                ruleCandidateEvidenceRepository.deleteAllByCandidate_CandidateId(candidate.getCandidateId());
            }

            return candidate;
        }

        candidate = RuleCandidate.of(
                run,
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
                meta
        );

        return ruleCandidateRepository.save(candidate);
    }

    protected void saveEvidenceLinks(
            RuleCandidate candidate,
            List<CandidateEvidenceDraft> drafts
    ) {
        if (candidate == null || drafts == null || drafts.isEmpty()) {
            return;
        }

        List<RuleCandidateEvidence> links = new ArrayList<>();

        for (CandidateEvidenceDraft draft : drafts) {
            if (draft == null) {
                continue;
            }

            Evidence evidence = draft.evidence();
            Edge edge = draft.edge();

            links.add(RuleCandidateEvidence.of(
                    candidate,
                    draft.signal(),
                    evidence,
                    edge,
                    draft.role(),
                    draft.weight(),
                    filePath(evidence),
                    evidence == null ? null : evidence.getStartLine(),
                    evidence == null ? null : evidence.getEndLine(),
                    evidence == null ? null : evidence.getSnippet(),
                    draft.note()
            ));
        }

        if (!links.isEmpty()) {
            ruleCandidateEvidenceRepository.saveAll(links);
        }
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

    protected String safeSymbolId(com.example.ossdoc.domain.graphstore.entity.SymbolEntity symbol) {
        return symbol == null ? "unknown-symbol" : symbol.getSymbolId();
    }

    protected String safeSymbolName(com.example.ossdoc.domain.graphstore.entity.SymbolEntity symbol) {
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