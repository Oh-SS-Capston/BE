package com.example.ossdoc.domain.rule.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.rule.dto.request.RuleCandidateMineRequest;
import com.example.ossdoc.domain.rule.dto.response.RuleCandidateMineResponse;
import com.example.ossdoc.domain.rule.enums.RuleCandidateConfidence;
import com.example.ossdoc.domain.rule.exception.RuleCandidateException;
import com.example.ossdoc.domain.rule.exception.code.RuleCandidateErrorCode;
import com.example.ossdoc.domain.rule.repository.RuleCandidateRepository;
import com.example.ossdoc.domain.rule.service.miner.RuleCandidateMiner;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public RuleCandidateMineResponse mine(RuleCandidateMineRequest request, Long userId) {
        validateRequest(request, userId);

        RepoRun run = repoRunRepository.findById(request.runId())
                .orElseThrow(() -> new RuleCandidateException(RuleCandidateErrorCode.RUN_NOT_FOUND));

        validateRunOwner(run, userId);

        RuleMiningSignalIngestService.RuleMiningSignalIngestResult ingestResult =
                ruleMiningSignalIngestService.ingest(
                        request.runId(),
                        request.isForceRebuild()
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
                .build();

        log.info(
                "[RULE-MINING] mining completed. runId={}, skippedSignalIngest={}, signals={}, minedCandidates={}, artifactId={}",
                run.getRunId(),
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