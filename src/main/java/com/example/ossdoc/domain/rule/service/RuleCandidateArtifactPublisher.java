package com.example.ossdoc.domain.rule.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.rule.dto.json.RuleCandidateDisplayPolicyJson;
import com.example.ossdoc.domain.rule.dto.json.RuleCandidateEvidenceJson;
import com.example.ossdoc.domain.rule.dto.json.RuleCandidateItem;
import com.example.ossdoc.domain.rule.dto.json.RuleCandidateSummaryJson;
import com.example.ossdoc.domain.rule.dto.json.RuleCandidatesJson;
import com.example.ossdoc.domain.rule.entity.RuleCandidate;
import com.example.ossdoc.domain.rule.entity.RuleCandidateEvidence;
import com.example.ossdoc.domain.rule.enums.RuleCandidateConfidence;
import com.example.ossdoc.domain.rule.repository.RuleCandidateEvidenceRepository;
import com.example.ossdoc.domain.rule.repository.RuleCandidateRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleCandidateArtifactPublisher {

    private static final String SCHEMA_VERSION = "1.2";
    private static final String RELATIVE_PATH = "rule/rule_candidates.json";
    private static final BigDecimal LOW_SCORE_THRESHOLD = new BigDecimal("0.6000");

    private static final Set<String> PRIMARY_EVIDENCE_ROLES = Set.of(
            "IF_CONDITION",
            "THROW_STATEMENT",
            "RETURN_STATEMENT",
            "ERROR_RESPONSE",
            "REPOSITORY_SAVE",
            "REPOSITORY_UPDATE",
            "REPOSITORY_DELETE",
            "ASSERTION_CALL",
            "REQUIRE_CALL"
    );

    private final RuleCandidateRepository ruleCandidateRepository;
    private final RuleCandidateEvidenceRepository ruleCandidateEvidenceRepository;
    private final EdgeRepository edgeRepository;
    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    @Transactional
    public Artifact publish(RepoRun run) {
        List<RuleCandidate> candidates =
                ruleCandidateRepository.findAllWithSubjectByRunIdOrderByScoreDesc(run.getRunId());

        List<Long> candidateIds = candidates.stream()
                .map(RuleCandidate::getCandidateId)
                .toList();

        Map<Long, List<RuleCandidateEvidence>> evidencesByCandidateId =
                loadEvidencesByCandidateId(candidateIds);

        RuleCandidateSummaryJson summary = buildSummary(candidates);

        List<RuleCandidateItem> items = new ArrayList<>();
        for (RuleCandidate candidate : candidates) {
            List<RuleCandidateEvidence> evidences =
                    evidencesByCandidateId.getOrDefault(candidate.getCandidateId(), List.of());

            CandidateQuality quality = evaluateQuality(candidate, evidences);
            items.add(toItem(candidate, evidences, quality));
        }

        long edgeCount = edgeRepository.countByRun_RunId(run.getRunId());
        RuleCandidateDisplayPolicyJson displayPolicy = buildDisplayPolicy(candidateIds, edgeCount);

        RuleCandidatesJson output = RuleCandidatesJson.builder()
                .schemaVersion(SCHEMA_VERSION)
                .runId(run.getRunId())
                .generatedAt(OffsetDateTime.now().toString())
                .summary(summary)
                .displayPolicy(displayPolicy)
                .candidates(items)
                .build();

        JsonNode content = objectMapper.valueToTree(output);

        Artifact artifact = artifactService.saveJsonArtifact(
                run,
                ArtifactKind.RULE_CANDIDATES_JSON,
                SCHEMA_VERSION,
                RELATIVE_PATH,
                content
        );

        log.info(
                "[RULE-MINING] rule_candidates.json published. runId={}, artifactId={}, candidates={}, primary={}",
                run.getRunId(),
                artifact.getArtifactId(),
                candidates.size(),
                displayPolicy.recommendedPrimaryCount()
        );

        return artifact;
    }

    private Map<Long, List<RuleCandidateEvidence>> loadEvidencesByCandidateId(List<Long> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return Map.of();
        }

        return ruleCandidateEvidenceRepository.findAllWithRelationsByCandidateIdIn(candidateIds)
                .stream()
                .collect(Collectors.groupingBy(evidence -> evidence.getCandidate().getCandidateId()));
    }

    private RuleCandidateSummaryJson buildSummary(List<RuleCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return RuleCandidateSummaryJson.builder()
                    .totalCandidates(0)
                    .highConfidenceCount(0)
                    .mediumConfidenceCount(0)
                    .lowConfidenceCount(0)
                    .build();
        }

        int high = 0;
        int medium = 0;
        int low = 0;

        for (RuleCandidate candidate : candidates) {
            if (candidate.getConfidence() == RuleCandidateConfidence.HIGH) {
                high++;
            } else if (candidate.getConfidence() == RuleCandidateConfidence.MEDIUM) {
                medium++;
            } else if (candidate.getConfidence() == RuleCandidateConfidence.LOW) {
                low++;
            }
        }

        return RuleCandidateSummaryJson.builder()
                .totalCandidates(candidates.size())
                .highConfidenceCount(high)
                .mediumConfidenceCount(medium)
                .lowConfidenceCount(low)
                .build();
    }

    private RuleCandidateItem toItem(
            RuleCandidate candidate,
            List<RuleCandidateEvidence> evidences,
            CandidateQuality quality
    ) {
        SymbolEntity subject = candidate.getSubjectSymbol();
        List<RuleCandidateEvidenceJson> evidenceJson = toEvidenceJsonList(evidences);

        return RuleCandidateItem.builder()
                .candidateId(candidate.getCandidateId())
                .ruleKey(candidate.getRuleKey())
                .candidateKind(candidate.getCandidateKind() == null ? null : candidate.getCandidateKind().name())
                .confidence(candidate.getConfidence() == null ? null : candidate.getConfidence().name())
                .status(candidate.getStatus() == null ? null : candidate.getStatus().name())
                .source(candidate.getSource() == null ? null : candidate.getSource().name())
                .subjectSymbolId(subject == null ? null : subject.getSymbolId())
                .subjectQualifiedName(subject == null ? null : subject.getQualifiedName())
                .groupId(candidate.getGroupId())
                .title(candidate.getTitle())
                .description(candidate.getDescription())
                .fingerprint(candidate.getFingerprint())
                .score(candidate.getScore())
                .supportCount(candidate.getSupportCount())
                .publicApiRelated(candidate.getPublicApiRelated())
                .summary(candidate.getSummary())
                .impact(candidate.getImpact())
                .meta(candidate.getMeta())
                .estimated(quality.estimated())
                .qualityLabel(quality.estimated() ? "ESTIMATED" : "CONFIRMED")
                .qualityReason(quality.reason())
                .evidenceCount(evidenceJson.size())
                .evidences(evidenceJson)
                .build();
    }

    private CandidateQuality evaluateQuality(RuleCandidate candidate, List<RuleCandidateEvidence> evidences) {
        int evidenceCount = evidences == null ? 0 : evidences.size();
        int primaryEvidenceCount = countPrimaryEvidence(evidences);

        boolean lowConfidence = candidate.getConfidence() == RuleCandidateConfidence.LOW;
        boolean sparseEvidence = evidenceCount == 0 || (evidenceCount == 1 && primaryEvidenceCount == 0);
        boolean lowScore = candidate.getScore() != null
                && candidate.getScore().compareTo(LOW_SCORE_THRESHOLD) < 0;
        boolean weakSupport = candidate.getSupportCount() == null || candidate.getSupportCount() <= 1;

        if (lowConfidence) {
            return new CandidateQuality(true, "LOW confidence");
        }
        if (sparseEvidence) {
            return new CandidateQuality(true, "insufficient evidence links");
        }
        if (lowScore && weakSupport) {
            return new CandidateQuality(true, "low score and weak support");
        }
        return new CandidateQuality(false, "sufficient evidence");
    }

    private int countPrimaryEvidence(List<RuleCandidateEvidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return 0;
        }

        return (int) evidences.stream()
                .map(RuleCandidateEvidence::getRole)
                .filter(role -> role != null && PRIMARY_EVIDENCE_ROLES.contains(role))
                .count();
    }

    private RuleCandidateDisplayPolicyJson buildDisplayPolicy(List<Long> orderedCandidateIds, long edgeCount) {
        int total = orderedCandidateIds.size();
        DisplayTier tier = resolveDisplayTier(total, edgeCount);

        List<Long> primaryIds = new ArrayList<>();
        for (Long candidateId : orderedCandidateIds) {
            if (primaryIds.size() >= tier.targetMax()) {
                break;
            }
            primaryIds.add(candidateId);
        }

        if (primaryIds.isEmpty() && !orderedCandidateIds.isEmpty()) {
            primaryIds.add(orderedCandidateIds.get(0));
        }

        Set<Long> primaryIdSet = new HashSet<>(primaryIds);
        List<Long> exploratoryIds = orderedCandidateIds.stream()
                .filter(id -> !primaryIdSet.contains(id))
                .toList();

        return RuleCandidateDisplayPolicyJson.builder()
                .sizeTier(tier.name())
                .totalCandidates(total)
                .targetMin(tier.targetMin())
                .targetMax(tier.targetMax())
                .recommendedPrimaryCount(primaryIds.size())
                .primaryCandidateIds(primaryIds)
                .exploratoryCandidateIds(exploratoryIds)
                .build();
    }

    private DisplayTier resolveDisplayTier(int totalCandidates, long edgeCount) {
        DisplayTier baseTier;
        if (totalCandidates <= 40) {
            baseTier = DisplayTier.SMALL;
        } else if (totalCandidates <= 120) {
            baseTier = DisplayTier.MEDIUM;
        } else if (totalCandidates <= 260) {
            baseTier = DisplayTier.LARGE;
        } else {
            baseTier = DisplayTier.XLARGE;
        }

        int boost = edgeCount >= 30_000 ? 2 : edgeCount >= 12_000 ? 1 : 0;
        return DisplayTier.byIndex(baseTier.ordinal() + boost);
    }

    private List<RuleCandidateEvidenceJson> toEvidenceJsonList(List<RuleCandidateEvidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return List.of();
        }

        return evidences.stream()
                .map(this::toEvidenceJson)
                .toList();
    }

    private RuleCandidateEvidenceJson toEvidenceJson(RuleCandidateEvidence evidence) {
        return RuleCandidateEvidenceJson.builder()
                .candidateEvidenceId(evidence.getCandidateEvidenceId())
                .signalId(evidence.getSignal() == null ? null : evidence.getSignal().getSignalId())
                .evidenceId(evidence.getEvidence() == null ? null : evidence.getEvidence().getEvidenceId())
                .edgeId(evidence.getEdge() == null ? null : evidence.getEdge().getEdgeId())
                .role(evidence.getRole())
                .weight(evidence.getWeight())
                .filePath(evidence.getFilePath())
                .startLine(evidence.getStartLine())
                .endLine(evidence.getEndLine())
                .snippet(evidence.getSnippet())
                .note(evidence.getNote())
                .build();
    }

    private enum DisplayTier {
        SMALL(10, 14),
        MEDIUM(14, 22),
        LARGE(22, 35),
        XLARGE(30, 45);

        private final int targetMin;
        private final int targetMax;

        DisplayTier(int targetMin, int targetMax) {
            this.targetMin = targetMin;
            this.targetMax = targetMax;
        }

        int targetMin() {
            return targetMin;
        }

        int targetMax() {
            return targetMax;
        }

        static DisplayTier byIndex(int index) {
            DisplayTier[] values = DisplayTier.values();
            int bounded = Math.max(0, Math.min(values.length - 1, index));
            return values[bounded];
        }
    }

    private record CandidateQuality(boolean estimated, String reason) {
    }
}