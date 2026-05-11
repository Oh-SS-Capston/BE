package com.example.ossdoc.domain.rule.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
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
import java.util.List;

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

    @Transactional
    public RuleCandidateMineResponse mine(RuleCandidateMineRequest request, Long userId) {
        validateRequest(request, userId);

        RepoRun run = repoRunRepository.findById(request.runId())
                .orElseThrow(() -> new RuleCandidateException(RuleCandidateErrorCode.RUN_NOT_FOUND));

        validateRunOwner(run, userId);

        boolean forceRebuildApplied = determineForceRebuild(run.getRunId(), request);

        RuleMiningSignalIngestService.RuleMiningSignalIngestResult ingestResult =
                ruleMiningSignalIngestService.ingest(
                        request.runId(),
                        forceRebuildApplied
                );

        int minedCandidateCount = runMiners(run);

        Artifact artifact = ruleCandidateArtifactPublisher.publish(run);

        RuleCandidateMineResponse response = RuleCandidateMineResponse.builder()
                .runId(run.getRunId())
                .ruleCandidatesArtifactId(artifact.getArtifactId())
                .totalCandidates(Math.toIntExact(ruleCandidateRepository.countByRun_RunId(run.getRunId())))
                .highConfidenceCount(Math.toIntExact(
                        ruleCandidateRepository.countByRun_RunIdAndConfidence(
                                run.getRunId(),
                                RuleCandidateConfidence.HIGH
                        )
                ))
                .mediumConfidenceCount(Math.toIntExact(
                        ruleCandidateRepository.countByRun_RunIdAndConfidence(
                                run.getRunId(),
                                RuleCandidateConfidence.MEDIUM
                        )
                ))
                .lowConfidenceCount(Math.toIntExact(
                        ruleCandidateRepository.countByRun_RunIdAndConfidence(
                                run.getRunId(),
                                RuleCandidateConfidence.LOW
                        )
                ))
                .forceRebuildApplied(forceRebuildApplied)
                .build();

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

    /**
     * forceRebuild 적용값을 결정한다.
     * 1) 요청에 명시값이 있으면 사용자 의도를 우선한다.
     * 2) 명시값이 없으면 그래프 최신성 비교 결과로 자동 결정한다.
     */
    private boolean determineForceRebuild(String runId, RuleCandidateMineRequest request) {
        if (request.hasForceRebuildFlag()) {
            return request.isForceRebuild();
        }
        return isGraphDataFresherThanSignals(runId);
    }

    /**
     * 그래프 데이터(Edge/Symbol/Evidence)가 기존 룰 신호보다 최신이면
     * 신호를 다시 구성하도록 true를 반환한다.
     */
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

    /**
     * run 기준으로 그래프 관련 데이터의 최신 시각을 계산한다.
     */
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

    /**
     * Edge는 updatedAt이 비어 있을 수 있어 createdAt으로 안전 폴백한다.
     */
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
