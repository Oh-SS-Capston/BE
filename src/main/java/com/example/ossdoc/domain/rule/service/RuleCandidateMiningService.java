package com.example.ossdoc.domain.rule.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.EvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.rule.dto.request.RuleCandidateMineRequest;
import com.example.ossdoc.domain.rule.dto.response.RuleCandidateMineResponse;
import com.example.ossdoc.domain.rule.enums.RuleCandidateConfidence;
import com.example.ossdoc.domain.rule.exception.RuleCandidateException;
import com.example.ossdoc.domain.rule.exception.code.RuleCandidateErrorCode;
import com.example.ossdoc.domain.rule.repository.RuleCandidateRepository;
import com.example.ossdoc.domain.rule.repository.RuleMiningSignalRepository;
import com.example.ossdoc.domain.rule.service.miner.RuleCandidateMiner;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleCandidateMiningService {

    private final RepoRunRepository repoRunRepository;
    private final RuleMiningSignalIngestService ruleMiningSignalIngestService;
    private final List<RuleCandidateMiner> miners;
    private final RuleCandidateRepository ruleCandidateRepository;
    private final RuleCandidateArtifactPublisher ruleCandidateArtifactPublisher;
    private final RuleMiningSignalRepository ruleMiningSignalRepository;
    private final EdgeRepository edgeRepository;
    private final SymbolRepository symbolRepository;
    private final EvidenceRepository evidenceRepository;
    private final ArtifactRepository artifactRepository;

    @Transactional
    public RuleCandidateMineResponse mine(RuleCandidateMineRequest request, Long userId) {
        validateRequest(request, userId);

        RepoRun run = repoRunRepository.findById(request.runId())
                .orElseThrow(() -> new RuleCandidateException(RuleCandidateErrorCode.RUN_NOT_FOUND));

        validateRunOwner(run, userId);

        boolean forceRebuildApplied = determineForceRebuild(run.getRunId(), request);

        Optional<Artifact> reusableArtifact = findReusableArtifact(run.getRunId(), forceRebuildApplied);
        if (reusableArtifact.isPresent()) {
            Artifact artifact = reusableArtifact.get();

            log.info(
                    "[RULE-MINING] reused existing result. runId={}, artifactId={}",
                    run.getRunId(),
                    artifact.getArtifactId()
            );

            return buildResponse(
                    run,
                    artifact.getArtifactId(),
                    false
            );
        }

        RuleMiningSignalIngestService.RuleMiningSignalIngestResult ingestResult =
                ruleMiningSignalIngestService.ingest(
                        request.runId(),
                        forceRebuildApplied
                );

        int minedCandidateCount = runMiners(run);

        Artifact artifact = ruleCandidateArtifactPublisher.publish(run);

        RuleCandidateMineResponse response = buildResponse(
                run,
                artifact.getArtifactId(),
                forceRebuildApplied
        );

        log.info(
                "[RULE-MINING] mining completed. runId={}, forceRebuildApplied={}, skippedSignalIngest={}, signals={}, minedCandidates={}, artifactId={}",
                run.getRunId(),
                forceRebuildApplied,
                ingestResult.skipped(),
                ingestResult.totalSignals(),
                minedCandidateCount,
                artifact.getArtifactId()
        );

        return response;
    }

    private int runMiners(RepoRun run) {
        if (miners == null || miners.isEmpty()) {
            return 0;
        }

        return miners.stream()
                .sorted(Comparator.comparing(miner -> miner.supports().name()))
                .mapToInt(miner -> miner.mine(run))
                .sum();
    }

    private Optional<Artifact> findReusableArtifact(String runId, boolean forceRebuildApplied) {
        if (forceRebuildApplied) {
            return Optional.empty();
        }

        if (!ruleMiningSignalRepository.existsByRun_RunId(runId)) {
            return Optional.empty();
        }

        if (!ruleCandidateRepository.existsByRun_RunId(runId)) {
            return Optional.empty();
        }

        return artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(
                runId,
                ArtifactKind.RULE_CANDIDATES_JSON
        );
    }

    /**
     * 기존에는 total/high/medium/low count를 위해 쿼리 4번을 호출했다.
     * 이제 confidence group by 쿼리 1번으로 count를 계산한다.
     */
    private RuleCandidateMineResponse buildResponse(
            RepoRun run,
            Long artifactId,
            boolean forceRebuildApplied
    ) {
        Map<RuleCandidateConfidence, Integer> counts = loadConfidenceCounts(run.getRunId());

        int high = counts.getOrDefault(RuleCandidateConfidence.HIGH, 0);
        int medium = counts.getOrDefault(RuleCandidateConfidence.MEDIUM, 0);
        int low = counts.getOrDefault(RuleCandidateConfidence.LOW, 0);
        int total = high + medium + low;

        return RuleCandidateMineResponse.builder()
                .runId(run.getRunId())
                .ruleCandidatesArtifactId(artifactId)
                .totalCandidates(total)
                .highConfidenceCount(high)
                .mediumConfidenceCount(medium)
                .lowConfidenceCount(low)
                .forceRebuildApplied(forceRebuildApplied)
                .build();
    }

    private Map<RuleCandidateConfidence, Integer> loadConfidenceCounts(String runId) {
        Map<RuleCandidateConfidence, Integer> result =
                new EnumMap<>(RuleCandidateConfidence.class);

        List<Object[]> rows = ruleCandidateRepository.countGroupByConfidence(runId);
        if (rows == null || rows.isEmpty()) {
            return result;
        }

        for (Object[] row : rows) {
            if (row == null || row.length < 2) {
                continue;
            }

            RuleCandidateConfidence confidence = (RuleCandidateConfidence) row[0];
            Number count = (Number) row[1];

            if (confidence == null || count == null) {
                continue;
            }

            result.put(confidence, Math.toIntExact(count.longValue()));
        }

        return result;
    }

    private boolean determineForceRebuild(String runId, RuleCandidateMineRequest request) {
        if (request.hasForceRebuildFlag()) {
            return request.isForceRebuild();
        }
        return isGraphDataFresherThanSignals(runId);
    }

    private boolean isGraphDataFresherThanSignals(String runId) {
        LocalDateTime lastSignalCreatedAt = ruleMiningSignalRepository
                .findTopByRun_RunIdOrderByCreatedAtDesc(runId)
                .map(signal -> signal.getCreatedAt())
                .orElse(null);

        if (lastSignalCreatedAt == null) {
            return false;
        }

        LocalDateTime lastGraphUpdatedAt = latestGraphDataTimestamp(runId);
        if (lastGraphUpdatedAt == null) {
            return false;
        }

        return lastGraphUpdatedAt.isAfter(lastSignalCreatedAt);
    }

    private LocalDateTime latestGraphDataTimestamp(String runId) {
        LocalDateTime edgeUpdatedAt = edgeRepository.findTopByRun_RunIdOrderByUpdatedAtDesc(runId)
                .map(this::edgeUpdatedAt)
                .orElse(null);

        LocalDateTime symbolUpdatedAt = symbolRepository.findTopByRun_RunIdOrderByUpdatedAtDesc(runId)
                .map(SymbolEntity::getUpdatedAt)
                .orElse(null);

        LocalDateTime evidenceCreatedAt = evidenceRepository.findTopByRun_RunIdOrderByCreatedAtDesc(runId)
                .map(Evidence::getCreatedAt)
                .orElse(null);

        return maxTimestamp(edgeUpdatedAt, symbolUpdatedAt, evidenceCreatedAt);
    }

    private LocalDateTime edgeUpdatedAt(Edge edge) {
        if (edge == null) {
            return null;
        }
        return edge.getUpdatedAt() != null ? edge.getUpdatedAt() : edge.getCreatedAt();
    }

    private LocalDateTime maxTimestamp(LocalDateTime first, LocalDateTime second, LocalDateTime third) {
        LocalDateTime max = first;
        if (second != null && (max == null || second.isAfter(max))) {
            max = second;
        }
        if (third != null && (max == null || third.isAfter(max))) {
            max = third;
        }
        return max;
    }

    private void validateRequest(RuleCandidateMineRequest request, Long userId) {
        if (request == null || request.runId() == null || request.runId().isBlank()) {
            throw new RuleCandidateException(RuleCandidateErrorCode.INVALID_RULE_MINE_REQUEST);
        }

        if (userId == null) {
            throw new RuleCandidateException(RuleCandidateErrorCode.RUN_ACCESS_DENIED);
        }
    }

    private void validateRunOwner(RepoRun run, Long userId) {
        if (run.getOwner() == null || run.getOwner().getId() == null) {
            throw new RuleCandidateException(RuleCandidateErrorCode.RUN_ACCESS_DENIED);
        }

        if (!run.getOwner().getId().equals(userId)) {
            throw new RuleCandidateException(RuleCandidateErrorCode.RUN_ACCESS_DENIED);
        }
    }
}