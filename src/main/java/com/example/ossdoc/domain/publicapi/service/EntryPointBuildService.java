package com.example.ossdoc.domain.publicapi.service;

import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.publicapi.dto.response.EntryPointBuildResponse;
import com.example.ossdoc.domain.publicapi.model.EntryPointCandidate;
import com.example.ossdoc.domain.publicapi.support.EntryPointJsonCodec;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.exception.RunException;
import com.example.ossdoc.domain.run.exception.code.RunErrorCode;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ENTRYPOINT 파이프라인 단계 진입점.
 * ensureTypeEntries → detect → ENTRY_POINTS_JSON 저장 순서로 실행한다.
 * CLUSTER 단계와 PUBLICAPI 단계가 이 산출물을 읽어 detect() 중복 호출을 제거한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntryPointBuildService {

    private final RepoRunRepository            repoRunRepository;
    private final PublicApiEntrySyncService    publicApiEntrySyncService;
    private final EntryPointDetectService      entryPointDetectService;
    private final EntryPointJsonCodec          entryPointJsonCodec;
    private final ArtifactService              artifactService;

    @Transactional
    public EntryPointBuildResponse build(String runId) {
        RepoRun run = repoRunRepository.findById(runId)
                .orElseThrow(() -> new RunException(RunErrorCode.RUN_NOT_FOUND));

        publicApiEntrySyncService.ensureTypeEntries(run);

        List<EntryPointCandidate> candidates = entryPointDetectService.detect(runId);

        ObjectNode json = entryPointJsonCodec.serialize(candidates, runId);
        artifactService.saveJsonArtifact(
                run, ArtifactKind.ENTRY_POINTS_JSON, "1.1", "entry_points.json", json);

        log.info("[ENTRYPOINT] ENTRY_POINTS_JSON saved. runId={}, count={}", runId, candidates.size());

        return EntryPointBuildResponse.of(candidates);
    }
}
