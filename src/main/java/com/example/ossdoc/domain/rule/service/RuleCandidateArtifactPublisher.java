package com.example.ossdoc.domain.rule.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleCandidateArtifactPublisher {

    private static final String SCHEMA_VERSION = "1.0";
    private static final String RELATIVE_PATH = "rule/rule_candidates.json";

    private final RuleCandidateRepository ruleCandidateRepository;
    private final RuleCandidateEvidenceRepository ruleCandidateEvidenceRepository;
    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    @Transactional
    public Artifact publish(RepoRun run) {
        List<RuleCandidate> candidates =
                ruleCandidateRepository.findAllByRun_RunIdOrderByScoreDesc(run.getRunId());

        List<Long> candidateIds = candidates.stream()
                .map(RuleCandidate::getCandidateId)
                .toList();

        Map<Long, List<RuleCandidateEvidence>> evidencesByCandidateId =
                loadEvidencesByCandidateId(candidateIds);

        RuleCandidateSummaryJson summary = buildSummary(run.getRunId());

        List<RuleCandidateItem> items = new ArrayList<>();
        for (RuleCandidate candidate : candidates) {
            List<RuleCandidateEvidence> evidences =
                    evidencesByCandidateId.getOrDefault(candidate.getCandidateId(), List.of());

            items.add(toItem(candidate, evidences));
        }

        RuleCandidatesJson output = RuleCandidatesJson.builder()
                .schemaVersion(SCHEMA_VERSION)
                .runId(run.getRunId())
                .generatedAt(OffsetDateTime.now().toString())
                .summary(summary)
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
                "[RULE-MINING] rule_candidates.json published. runId={}, artifactId={}, candidates={}",
                run.getRunId(),
                artifact.getArtifactId(),
                candidates.size()
        );

        return artifact;
    }

    private Map<Long, List<RuleCandidateEvidence>> loadEvidencesByCandidateId(List<Long> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return Map.of();
        }

        return ruleCandidateEvidenceRepository.findAllByCandidate_CandidateIdIn(candidateIds)
                .stream()
                .collect(Collectors.groupingBy(evidence -> evidence.getCandidate().getCandidateId()));
    }

    private RuleCandidateSummaryJson buildSummary(String runId) {
        return RuleCandidateSummaryJson.builder()
                .totalCandidates(Math.toIntExact(ruleCandidateRepository.countByRun_RunId(runId)))
                .highConfidenceCount(Math.toIntExact(
                        ruleCandidateRepository.countByRun_RunIdAndConfidence(
                                runId,
                                RuleCandidateConfidence.HIGH
                        )
                ))
                .mediumConfidenceCount(Math.toIntExact(
                        ruleCandidateRepository.countByRun_RunIdAndConfidence(
                                runId,
                                RuleCandidateConfidence.MEDIUM
                        )
                ))
                .lowConfidenceCount(Math.toIntExact(
                        ruleCandidateRepository.countByRun_RunIdAndConfidence(
                                runId,
                                RuleCandidateConfidence.LOW
                        )
                ))
                .build();
    }

    private RuleCandidateItem toItem(
            RuleCandidate candidate,
            List<RuleCandidateEvidence> evidences
    ) {
        SymbolEntity subject = candidate.getSubjectSymbol();

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
                .evidences(toEvidenceJsonList(evidences))
                .build();
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
}