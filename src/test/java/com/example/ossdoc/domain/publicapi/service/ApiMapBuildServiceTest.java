package com.example.ossdoc.domain.publicapi.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.publicapi.model.EntryPointCandidate;
import com.example.ossdoc.domain.publicapi.support.EntryPointJsonCodec;
import com.example.ossdoc.domain.publicapi.support.EntryPointSubsystemLabeler;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiMapBuildServiceTest {

    @Mock private RepoRunRepository           repoRunRepository;
    @Mock private PublicApiEntrySyncService   publicApiEntrySyncService;
    @Mock private EntryPointDetectService     entryPointDetectService;
    @Mock private ExtensionPointDetectService extensionPointDetectService;
    @Mock private ArtifactService             artifactService;
    @Mock private ArtifactRepository          artifactRepository;
    @Mock private EntryPointJsonCodec         entryPointJsonCodec;
    @Mock private EntryPointSubsystemLabeler  entryPointSubsystemLabeler;

    /**
     * ObjectMapper는 ApiMapBuildService 내부에서 createObjectNode/createArrayNode 등으로
     * JSON 트리를 직접 빌드한다. mock으로 stub하기에 호출 표면이 넓어 실제 인스턴스를 spy로 둔다.
     */
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ApiMapBuildService apiMapBuildService;

    @Test
    @DisplayName("ENTRY_POINTS_JSON 부재 시 detect() fallback이 호출되고 Labeler는 호출되지 않는다")
    void build_entryPointsJsonMissing_fallsBackToDetect() {
        // given — ENTRYPOINT 단계 실패/스킵 시나리오
        String runId = "run-fallback";
        RepoRun run = mock(RepoRun.class);
        when(repoRunRepository.findById(runId)).thenReturn(Optional.of(run));

        // ENTRY_POINTS_JSON 부재 → fallback 경로
        when(artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(
                runId, ArtifactKind.ENTRY_POINTS_JSON))
                .thenReturn(Optional.empty());

        // FACTS_JSON 부재 (readme_mentions 빌더는 빈 배열 반환)
        when(artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(
                runId, ArtifactKind.FACTS_JSON))
                .thenReturn(Optional.empty());

        // fallback이 detect() 직접 호출 — 빈 리스트로 충분 (라벨 채우기는 detect() 자체 책임)
        when(entryPointDetectService.detect(runId)).thenReturn(List.of());
        when(extensionPointDetectService.detect(runId)).thenReturn(List.of());

        Artifact saved = mock(Artifact.class);
        when(artifactService.saveJsonArtifact(any(), any(), any(), any(), any()))
                .thenReturn(saved);

        // when
        apiMapBuildService.build(runId);

        // then — fallback 경로: detect() 호출됨, codec/Labeler 경로는 안 탐
        verify(entryPointDetectService).detect(runId);
        verify(entryPointJsonCodec, never()).deserialize(any());
        verify(entryPointSubsystemLabeler, never()).label(any(), any());
    }

    @Test
    @DisplayName("ENTRY_POINTS_JSON 존재 시 Labeler 경로를 타고 detect()는 호출되지 않는다")
    void build_entryPointsJsonPresent_usesLabelerPath() {
        // given
        String runId = "run-normal";
        RepoRun run = mock(RepoRun.class);
        when(repoRunRepository.findById(runId)).thenReturn(Optional.of(run));

        // ENTRY_POINTS_JSON 정상 경로
        Artifact entryPointsArtifact = mock(Artifact.class);
        when(artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(
                runId, ArtifactKind.ENTRY_POINTS_JSON))
                .thenReturn(Optional.of(entryPointsArtifact));
        when(artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(
                runId, ArtifactKind.FACTS_JSON))
                .thenReturn(Optional.empty());

        // codec.deserialize → 빈 리스트, labeler.label → 그대로 빈 리스트
        when(entryPointJsonCodec.deserialize(any())).thenReturn(List.<EntryPointCandidate>of());
        when(entryPointSubsystemLabeler.label(any(), any())).thenReturn(List.<EntryPointCandidate>of());

        when(extensionPointDetectService.detect(runId)).thenReturn(List.of());

        Artifact saved = mock(Artifact.class);
        when(artifactService.saveJsonArtifact(any(), any(), any(), any(), any()))
                .thenReturn(saved);

        // when
        apiMapBuildService.build(runId);

        // then — Labeler 경로: detect()는 호출되지 않음
        verify(entryPointJsonCodec).deserialize(any());
        verify(entryPointSubsystemLabeler).label(any(), any());
        verify(entryPointDetectService, never()).detect(runId);
    }
}
