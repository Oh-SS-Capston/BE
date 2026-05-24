package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.artifact.output.SubsystemsJson;
import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.cluster.config.ClusterSignalProperties;
import com.example.ossdoc.domain.cluster.dto.request.ClusterBuildRequest;
import com.example.ossdoc.domain.cluster.dto.response.ClusterBuildResponse;
import com.example.ossdoc.domain.cluster.model.CommunityResult;
import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import com.example.ossdoc.domain.cluster.support.SubsystemAssembler;
import com.example.ossdoc.domain.publicapi.model.EntryPointCandidate;
import com.example.ossdoc.domain.publicapi.service.EntryPointDetectService;
import com.example.ossdoc.domain.publicapi.service.PublicApiEntrySyncService;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClusterBuildServiceTest {

    @Mock private RepoRunRepository repoRunRepository;
    @Mock private GraphProjectionService graphProjectionService;
    @Mock private ResolutionProbeService resolutionProbeService;
    @Mock private SubsystemAssembler subsystemAssembler;
    @Mock private SubsystemRefinerService subsystemRefinerService;
    @Mock private RankingService rankingService;
    @Mock private ClusterArtifactPublisher clusterArtifactPublisher;
    @Mock private PublicApiEntrySyncService publicApiEntrySyncService;
    @Mock private EntryPointDetectService entryPointDetectService;
    @Mock private ClusterSignalProperties clusterSignalProperties;

    // 클래스 필드 mock으로 두면 @BeforeEach stub이 각 테스트에서 재사용됨
    @Mock private ClusterSignalProperties.Signals signals;
    @Mock private ClusterSignalProperties.ApiFlowSignal apiFlowConfig;

    @InjectMocks
    private ClusterBuildService clusterBuildService;

    @BeforeEach
    void setUp() {
        when(clusterSignalProperties.getSignals()).thenReturn(signals);
        when(signals.getApiFlow()).thenReturn(apiFlowConfig);
    }

    @Test
    @DisplayName("ClusterBuildService는 graphstore 기반 subsystem/ranking 산출물을 생성하고 발행한다")
    void build_shouldGenerateAndPublishArtifacts() {
        // given
        ClusterBuildRequest request = mock(ClusterBuildRequest.class);
        when(request.getRunId()).thenReturn("run-1");
        when(request.getIterations()).thenReturn(10);
        when(request.getMinClusterSize()).thenReturn(3);
        when(request.getTopK()).thenReturn(20);

        RepoRun run = mock(RepoRun.class);
        when(run.getRunId()).thenReturn("run-1");
        when(repoRunRepository.findById("run-1")).thenReturn(Optional.of(run));
        when(publicApiEntrySyncService.ensureTypeEntries(run)).thenReturn(Set.of("A1"));

        ProjectedGraph projectedGraph = mock(ProjectedGraph.class);
        when(projectedGraph.getNodes()).thenReturn(List.of(mock(ProjectedNode.class)));
        when(graphProjectionService.loadProjectedGraph(eq("run-1"), any())).thenReturn(projectedGraph);

        CommunityResult communityResult = new CommunityResult(new int[]{0, 0, 1, 1});
        ResolutionProbeService.ProbeResult probeResult =
                new ResolutionProbeService.ProbeResult(0.012, communityResult, 0.71, 2);
        when(resolutionProbeService.findBest(projectedGraph, 3, 10)).thenReturn(probeResult);

        List<Subsystem> subsystems = List.of(
                Subsystem.builder()
                        .subsystemId("ss_001").name("auth / token").score(0.0)
                        .memberSymbolIds(List.of("A1", "A2")).entrySymbolIds(List.of("A1"))
                        .coreSymbolIds(List.of("A2")).packageRoots(List.of("com.example.auth"))
                        .build(),
                Subsystem.builder()
                        .subsystemId("ss_002").name("user / account").score(0.0)
                        .memberSymbolIds(List.of("B1", "B2")).entrySymbolIds(List.of("B1"))
                        .coreSymbolIds(List.of("B2")).packageRoots(List.of("com.example.user"))
                        .build()
        );

        when(subsystemAssembler.assemble(projectedGraph, communityResult.getClusters(), 3))
                .thenReturn(subsystems);
        when(subsystemRefinerService.refine(subsystems, projectedGraph.getNodes(), "run-1"))
                .thenReturn(new SubsystemRefinerService.RefineOutcome(subsystems, Map.of()));
        when(rankingService.rank(projectedGraph, subsystems, 20))
                .thenReturn(new RankingService.RankingResult(List.of(), List.of(), subsystems));

        Artifact rankingsArtifact = mock(Artifact.class);
        when(rankingsArtifact.getArtifactId()).thenReturn(101L);
        Artifact subsystemsArtifact = mock(Artifact.class);
        when(subsystemsArtifact.getArtifactId()).thenReturn(202L);
        when(clusterArtifactPublisher.publishSubsystems(eq(run), any())).thenReturn(subsystemsArtifact);
        when(clusterArtifactPublisher.publishRankings(eq(run), any())).thenReturn(rankingsArtifact);

        // when
        ClusterBuildResponse response = clusterBuildService.build(request);

        // then
        assertThat(response.getRunId()).isEqualTo("run-1");
        assertThat(response.getSubsystemCount()).isEqualTo(2);
        assertThat(response.getRankedSymbolCount()).isEqualTo(0);
        assertThat(response.getRankingsArtifactId()).isEqualTo(101L);
        assertThat(response.getSubsystemsArtifactId()).isEqualTo(202L);

        verify(repoRunRepository).findById("run-1");
        verify(publicApiEntrySyncService).ensureTypeEntries(run);
        verify(graphProjectionService).loadProjectedGraph(eq("run-1"), any());
        verify(resolutionProbeService).findBest(projectedGraph, 3, 10);
        verify(subsystemAssembler).assemble(projectedGraph, communityResult.getClusters(), 3);
        verify(subsystemRefinerService).refine(subsystems, projectedGraph.getNodes(), "run-1");
        verify(rankingService).rank(projectedGraph, subsystems, 20);

        ArgumentCaptor<SubsystemsJson> subsystemsDtoCaptor = ArgumentCaptor.forClass(SubsystemsJson.class);
        verify(clusterArtifactPublisher).publishSubsystems(eq(run), subsystemsDtoCaptor.capture());
        SubsystemsJson captured = subsystemsDtoCaptor.getValue();
        assertThat(captured).isNotNull();
        assertThat(captured.getSubsystems()).hasSize(2);
        assertThat(captured.getSubsystems().get(0).getSubsystemId()).isEqualTo("ss_001");
    }

    @Test
    @DisplayName("HIGH·MED candidate만 entrySymbolIds로 GraphProjectionService에 전달되고 LOW는 제외된다")
    @SuppressWarnings("unchecked")
    void build_apiFlowEnabled_passesFilteredEntryPointsToProjection() {
        // given
        when(apiFlowConfig.getMinConfidence()).thenReturn("MED");

        ClusterBuildRequest request = mock(ClusterBuildRequest.class);
        when(request.getRunId()).thenReturn("run-1");
        when(request.getIterations()).thenReturn(10);
        when(request.getMinClusterSize()).thenReturn(3);
        when(request.getTopK()).thenReturn(20);

        RepoRun run = mock(RepoRun.class);
        when(run.getRunId()).thenReturn("run-1");
        when(repoRunRepository.findById("run-1")).thenReturn(Optional.of(run));
        when(publicApiEntrySyncService.ensureTypeEntries(run)).thenReturn(Set.of("H1"));

        // HIGH → 포함, MED → 포함, LOW → 제외
        when(entryPointDetectService.detect("run-1")).thenReturn(List.of(
                EntryPointCandidate.builder().symbolId("sym-high").confidence("HIGH").build(),
                EntryPointCandidate.builder().symbolId("sym-med").confidence("MED").build(),
                EntryPointCandidate.builder().symbolId("sym-low").confidence("LOW").build()
        ));

        ProjectedGraph projectedGraph = mock(ProjectedGraph.class);
        when(projectedGraph.getNodes()).thenReturn(List.of(mock(ProjectedNode.class)));
        when(graphProjectionService.loadProjectedGraph(eq("run-1"), any())).thenReturn(projectedGraph);

        CommunityResult communityResult = new CommunityResult(new int[]{0, 1});
        when(resolutionProbeService.findBest(projectedGraph, 3, 10))
                .thenReturn(new ResolutionProbeService.ProbeResult(0.012, communityResult, 0.71, 2));

        List<Subsystem> subsystems = List.of(
                Subsystem.builder().subsystemId("ss_001").name("core").score(0.0)
                        .memberSymbolIds(List.of("H1")).entrySymbolIds(List.of("H1"))
                        .coreSymbolIds(List.of()).packageRoots(List.of()).build()
        );
        when(subsystemAssembler.assemble(any(), any(), anyInt())).thenReturn(subsystems);
        when(subsystemRefinerService.refine(any(), any(), anyString()))
                .thenReturn(new SubsystemRefinerService.RefineOutcome(subsystems, Map.of()));
        when(rankingService.rank(any(), any(), anyInt()))
                .thenReturn(new RankingService.RankingResult(List.of(), List.of(), subsystems));

        Artifact ra = mock(Artifact.class); when(ra.getArtifactId()).thenReturn(1L);
        Artifact sa = mock(Artifact.class); when(sa.getArtifactId()).thenReturn(2L);
        when(clusterArtifactPublisher.publishSubsystems(any(), any())).thenReturn(sa);
        when(clusterArtifactPublisher.publishRankings(any(), any())).thenReturn(ra);

        // when
        clusterBuildService.build(request);

        // then — HIGH·MED만 포함, LOW 제외
        ArgumentCaptor<Set<String>> entryIdsCaptor = ArgumentCaptor.forClass(Set.class);
        verify(graphProjectionService).loadProjectedGraph(eq("run-1"), entryIdsCaptor.capture());
        Set<String> passedIds = entryIdsCaptor.getValue();
        assertThat(passedIds).containsExactlyInAnyOrder("sym-high", "sym-med");
        assertThat(passedIds).doesNotContain("sym-low");
    }
}
