package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.artifact.output.SubsystemsJsonDto;
import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.cluster.dto.request.ClusterBuildRequest;
import com.example.ossdoc.domain.cluster.dto.response.ClusterBuildResponse;
import com.example.ossdoc.domain.cluster.model.CommunityResult;
import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import com.example.ossdoc.domain.cluster.support.SubsystemAssembler;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClusterBuildServiceTest {

    @Mock
    private RepoRunRepository repoRunRepository;

    @Mock
    private GraphProjectionService graphProjectionService;

    @Mock
    private LeidenCommunityService leidenCommunityService;

    @Mock
    private SubsystemAssembler subsystemAssembler;

    @Mock
    private RankingService rankingService;

    @Mock
    private ClusterArtifactPublisher clusterArtifactPublisher;

    @InjectMocks
    private ClusterBuildService clusterBuildService;

    @Test
    @DisplayName("ClusterBuildService는 graphstore 기반 subsystem/ranking 산출물을 생성하고 발행한다")
    void build_shouldGenerateAndPublishArtifacts() {
        // given
        ClusterBuildRequest request = mock(ClusterBuildRequest.class);
        when(request.getRunId()).thenReturn("run-1");
        when(request.getResolution()).thenReturn(0.012);
        when(request.getIterations()).thenReturn(10);
        when(request.getMinClusterSize()).thenReturn(3);
        when(request.getTopK()).thenReturn(20);

        RepoRun run = mock(RepoRun.class);
        when(run.getRunId()).thenReturn("run-1");
        when(repoRunRepository.findById("run-1")).thenReturn(Optional.of(run));

        ProjectedGraph projectedGraph = mock(ProjectedGraph.class);
        when(graphProjectionService.loadProjectedGraph("run-1")).thenReturn(projectedGraph);

        CommunityResult communityResult = new CommunityResult(new int[]{0, 0, 1, 1});
        when(leidenCommunityService.detect(projectedGraph, 0.012, 10))
                .thenReturn(communityResult);

        List<Subsystem> subsystems = List.of(
                Subsystem.builder()
                        .subsystemId("ss_001")
                        .name("auth / token")
                        .score(0.0)
                        .memberSymbolIds(List.of("A1", "A2"))
                        .entrySymbolIds(List.of("A1"))
                        .coreSymbolIds(List.of("A2"))
                        .packageRoots(List.of("com.example.auth"))
                        .build(),
                Subsystem.builder()
                        .subsystemId("ss_002")
                        .name("user / account")
                        .score(0.0)
                        .memberSymbolIds(List.of("B1", "B2"))
                        .entrySymbolIds(List.of("B1"))
                        .coreSymbolIds(List.of("B2"))
                        .packageRoots(List.of("com.example.user"))
                        .build()
        );

        when(subsystemAssembler.assemble(projectedGraph, communityResult.getClusters(), 3))
                .thenReturn(subsystems);

        RankingService.RankingResult rankingResult = new RankingService.RankingResult(
                List.of(), //symbolRankings
                List.of(), //subsystemRankings
                subsystems //enrichedSubsystems
        );
        when(rankingService.rank(projectedGraph, subsystems, 20))
                .thenReturn(rankingResult);

        Artifact rankingsArtifact = mock(Artifact.class);
        when(rankingsArtifact.getArtifactId()).thenReturn(101L);

        Artifact subsystemsArtifact = mock(Artifact.class);
        when(subsystemsArtifact.getArtifactId()).thenReturn(202L);

        when(clusterArtifactPublisher.publishSubsystems(eq(run), any()))
                .thenReturn(subsystemsArtifact);
        when(clusterArtifactPublisher.publishRankings(eq(run), any()))
                .thenReturn(rankingsArtifact);

        // when
        ClusterBuildResponse response = clusterBuildService.build(request);

        // then
        assertThat(response.getRunId()).isEqualTo("run-1");
        assertThat(response.getSubsystemCount()).isEqualTo(2);
        assertThat(response.getRankedSymbolCount()).isEqualTo(0);
        assertThat(response.getRankingsArtifactId()).isEqualTo(101L);
        assertThat(response.getSubsystemsArtifactId()).isEqualTo(202L);

        verify(repoRunRepository).findById("run-1");
        verify(graphProjectionService).loadProjectedGraph("run-1");
        verify(leidenCommunityService).detect(projectedGraph, 0.012, 10);
        verify(subsystemAssembler).assemble(projectedGraph, communityResult.getClusters(), 3);
        verify(rankingService).rank(projectedGraph, subsystems, 20);
        verify(clusterArtifactPublisher).publishSubsystems(eq(run), any());
        verify(clusterArtifactPublisher).publishRankings(eq(run), any());

        ArgumentCaptor<SubsystemsJsonDto> subsystemsDtoCaptor =
                ArgumentCaptor.forClass(SubsystemsJsonDto.class);

        verify(clusterArtifactPublisher).publishSubsystems(eq(run), subsystemsDtoCaptor.capture());

        SubsystemsJsonDto captured = subsystemsDtoCaptor.getValue();
        assertThat(captured).isNotNull();
        assertThat(captured.getSubsystems()).hasSize(2);
        assertThat(captured.getSubsystems().get(0).getSubsystemId()).isEqualTo("ss_001");
    }
}