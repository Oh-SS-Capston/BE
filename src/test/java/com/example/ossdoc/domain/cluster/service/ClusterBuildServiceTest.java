package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.cluster.artifact.output.SubsystemsJson;
import com.example.ossdoc.domain.cluster.config.ClusterSignalProperties;
import com.example.ossdoc.domain.cluster.dto.request.ClusterBuildRequest;
import com.example.ossdoc.domain.cluster.dto.response.ClusterBuildResponse;
import com.example.ossdoc.domain.cluster.model.CommunityResult;
import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import com.example.ossdoc.domain.cluster.support.SubsystemAssembler;
import com.example.ossdoc.domain.publicapi.service.PublicApiEntrySyncService;
import com.example.ossdoc.domain.publicapi.support.EntryPointJsonCodec;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClusterBuildServiceTest {

    @Mock
    private RepoRunRepository repoRunRepository;

    @Mock
    private GraphProjectionService graphProjectionService;

    @Mock
    private ResolutionProbeService resolutionProbeService;

    @Mock
    private SubsystemAssembler subsystemAssembler;

    @Mock
    private SubsystemRefinerService subsystemRefinerService;

    // Refiner 이후 최종 subsystem ID를 deterministic하게 생성하는 서비스.
    @Mock
    private SubsystemIdentityService subsystemIdentityService;

    @Mock
    private RankingService rankingService;

    @Mock
    private ClusterArtifactPublisher clusterArtifactPublisher;

    @Mock
    private PublicApiEntrySyncService publicApiEntrySyncService;

    @Mock
    private ArtifactRepository artifactRepository;

    @Mock
    private EntryPointJsonCodec entryPointJsonCodec;

    @Mock
    private ClusterSignalProperties clusterSignalProperties;

    // 각 테스트에서 공통으로 사용하는 signal 설정 mock.
    @Mock
    private ClusterSignalProperties.Signals signals;

    @Mock
    private ClusterSignalProperties.ApiFlowSignal apiFlowConfig;

    @InjectMocks
    private ClusterBuildService clusterBuildService;

    @BeforeEach
    void setUp() {
        /*
         * ClusterBuildService는 ENTRY_POINTS_JSON 조회 전에
         * cluster signal 설정에서 ApiFlowSignal을 조회한다.
         */
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

        /*
         * deterministic subsystem ID 생성에 commitSha가 사용되므로
         * runId뿐 아니라 commitSha도 설정한다.
         */
        RepoRun run = mock(RepoRun.class);
        when(run.getRunId()).thenReturn("run-1");
        when(run.getCommitSha()).thenReturn("commit-abc123");

        when(repoRunRepository.findById("run-1"))
                .thenReturn(Optional.of(run));

        /*
         * clustering 전에 public API TYPE entry가 존재해야 한다.
         */
        when(publicApiEntrySyncService.ensureTypeEntries(run))
                .thenReturn(Set.of("A1"));

        /*
         * ENTRY_POINTS_JSON은 별도로 stubbing하지 않는다.
         * Mockito의 Optional 기본 반환값인 Optional.empty()가 사용되고,
         * ClusterBuildService는 refined entry point 없이 계속 진행한다.
         */
        ProjectedNode projectedNode = mock(ProjectedNode.class);

        ProjectedGraph projectedGraph = mock(ProjectedGraph.class);
        when(projectedGraph.getNodes())
                .thenReturn(List.of(projectedNode));

        when(graphProjectionService.loadProjectedGraph(
                eq("run-1"),
                any()
        )).thenReturn(projectedGraph);

        /*
         * Leiden resolution 탐색 결과.
         *
         * ProbeResult 인자 순서:
         * 1. resolution
         * 2. CommunityResult
         * 3. modularity
         * 4. cpmQuality
         * 5. clusterCount
         */
        CommunityResult communityResult =
                new CommunityResult(new int[]{0, 0, 1, 1});

        ResolutionProbeService.ProbeResult probeResult =
                new ResolutionProbeService.ProbeResult(
                        0.012,
                        communityResult,
                        0.71,
                        0.42,
                        2
                );

        when(resolutionProbeService.findBest(
                projectedGraph,
                3,
                10
        )).thenReturn(probeResult);

        /*
         * Leiden 결과를 두 개의 subsystem으로 조립했다고 가정한다.
         */
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

        when(subsystemAssembler.assemble(
                projectedGraph,
                communityResult.getClusters(),
                3
        )).thenReturn(subsystems);

        /*
         * 이번 테스트에서는 Refiner가 subsystem member 구성을
         * 변경하지 않는다고 가정한다.
         */
        when(subsystemRefinerService.refine(
                subsystems,
                projectedGraph.getNodes(),
                "run-1"
        )).thenReturn(
                new SubsystemRefinerService.RefineOutcome(
                        subsystems,
                        Map.of()
                )
        );

        /*
         * deterministic ID 생성 단계 역시 orchestration만 검증하기 위해
         * 동일한 subsystem 목록을 반환하도록 한다.
         *
         * 실제 ID 생성 규칙은 SubsystemIdentityServiceTest에서 검증한다.
         */
        when(subsystemIdentityService.rekey(
                subsystems,
                "commit-abc123"
        )).thenReturn(subsystems);

        /*
         * Ranking 결과.
         *
         * symbol/subsystem ranking 자체는 빈 목록으로 두고
         * enrichedSubsystems에는 기존 subsystem을 그대로 반환한다.
         */
        when(rankingService.rank(
                projectedGraph,
                subsystems,
                20
        )).thenReturn(
                new RankingService.RankingResult(
                        List.of(),
                        List.of(),
                        subsystems
                )
        );

        Artifact rankingsArtifact = mock(Artifact.class);
        when(rankingsArtifact.getArtifactId()).thenReturn(101L);

        Artifact subsystemsArtifact = mock(Artifact.class);
        when(subsystemsArtifact.getArtifactId()).thenReturn(202L);

        when(clusterArtifactPublisher.publishSubsystems(
                eq(run),
                any()
        )).thenReturn(subsystemsArtifact);

        when(clusterArtifactPublisher.publishRankings(
                eq(run),
                any()
        )).thenReturn(rankingsArtifact);

        // when

        ClusterBuildResponse response =
                clusterBuildService.build(request);

        // then

        assertThat(response.getRunId())
                .isEqualTo("run-1");

        assertThat(response.getSubsystemCount())
                .isEqualTo(2);

        assertThat(response.getRankedSymbolCount())
                .isEqualTo(0);

        assertThat(response.getRankingsArtifactId())
                .isEqualTo(101L);

        assertThat(response.getSubsystemsArtifactId())
                .isEqualTo(202L);

        /*
         * clustering pipeline이 의도한 순서로 호출되는지 확인한다.
         */
        verify(repoRunRepository)
                .findById("run-1");

        verify(publicApiEntrySyncService)
                .ensureTypeEntries(run);

        verify(graphProjectionService)
                .loadProjectedGraph(
                        eq("run-1"),
                        any()
                );

        verify(resolutionProbeService)
                .findBest(
                        projectedGraph,
                        3,
                        10
                );

        verify(subsystemAssembler)
                .assemble(
                        projectedGraph,
                        communityResult.getClusters(),
                        3
                );

        verify(subsystemRefinerService)
                .refine(
                        subsystems,
                        projectedGraph.getNodes(),
                        "run-1"
                );

        /*
         * 개선된 pipeline에서는 Refiner 이후
         * deterministic subsystem ID 생성이 반드시 수행되어야 한다.
         */
        verify(subsystemIdentityService)
                .rekey(
                        subsystems,
                        "commit-abc123"
                );

        verify(rankingService)
                .rank(
                        projectedGraph,
                        subsystems,
                        20
                );

        /*
         * 실제 SUBSYSTEMS_JSON에 전달된 데이터를 capture하여
         * 개선된 CPM metadata가 기록되는지도 확인한다.
         */
        ArgumentCaptor<SubsystemsJson> subsystemsDtoCaptor =
                ArgumentCaptor.forClass(SubsystemsJson.class);

        verify(clusterArtifactPublisher)
                .publishSubsystems(
                        eq(run),
                        subsystemsDtoCaptor.capture()
                );

        SubsystemsJson captured =
                subsystemsDtoCaptor.getValue();

        assertThat(captured)
                .isNotNull();

        assertThat(captured.getSubsystems())
                .hasSize(2);

        assertThat(captured.getSubsystems().get(0).getSubsystemId())
                .isEqualTo("ss_001");

        /*
         * Leiden algorithm metadata 검증.
         *
         * CPM은 Leiden의 실제 quality function이고,
         * Modularity는 보조 분석 지표로 유지한다.
         */
        assertThat(captured.getAlgorithm())
                .containsEntry("name", "Leiden")
                .containsEntry("qualityFunction", "CPM")
                .containsEntry("cpmQuality", 0.42)
                .containsEntry("modularity", 0.71)
                .containsEntry("resolution", 0.012)
                .containsEntry("clusterCount", 2);

        /*
         * algorithm.metrics 내부 값도 확인한다.
         */
        Object metricsObject =
                captured.getAlgorithm().get("metrics");

        assertThat(metricsObject)
                .isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> metrics =
                (Map<String, Object>) metricsObject;

        assertThat(metrics)
                .containsEntry("cpm_quality", 0.42)
                .containsEntry("modularity", 0.71)
                .containsEntry("subsystem_count", 2)
                .containsEntry("total_node_count", 4)
                .containsEntry("misc_node_count", 0);
    }

    @Test
    @DisplayName("ENTRY_POINTS_JSON에서 HIGH·MED만 GraphProjectionService에 전달되고 LOW는 제외된다")
    @SuppressWarnings("unchecked")
    void build_apiFlowEnabled_passesFilteredEntryPointsToProjection() {
        // given

        /*
         * MED 이상의 entry point만 clustering API-flow signal로 사용한다.
         */
        when(apiFlowConfig.getMinConfidence())
                .thenReturn("MED");

        ClusterBuildRequest request = mock(ClusterBuildRequest.class);
        when(request.getRunId()).thenReturn("run-1");
        when(request.getIterations()).thenReturn(10);
        when(request.getMinClusterSize()).thenReturn(3);
        when(request.getTopK()).thenReturn(20);

        RepoRun run = mock(RepoRun.class);
        when(run.getRunId()).thenReturn("run-1");
        when(run.getCommitSha()).thenReturn("commit-abc123");

        when(repoRunRepository.findById("run-1"))
                .thenReturn(Optional.of(run));

        when(publicApiEntrySyncService.ensureTypeEntries(run))
                .thenReturn(Set.of("H1"));

        /*
         * ENTRY_POINTS_JSON artifact가 존재하는 상황.
         */
        Artifact entryPointsArtifact = mock(Artifact.class);

        when(
                artifactRepository
                        .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(
                                "run-1",
                                ArtifactKind.ENTRY_POINTS_JSON
                        )
        ).thenReturn(Optional.of(entryPointsArtifact));

        /*
         * codec이 minConfidence=MED를 적용한 결과,
         * HIGH와 MED entry point만 반환했다고 가정한다.
         */
        when(entryPointJsonCodec.readSymbolIds(
                any(),
                eq("MED")
        )).thenReturn(
                Set.of(
                        "sym-high",
                        "sym-med"
                )
        );

        ProjectedNode projectedNode = mock(ProjectedNode.class);

        ProjectedGraph projectedGraph = mock(ProjectedGraph.class);
        when(projectedGraph.getNodes())
                .thenReturn(List.of(projectedNode));

        when(graphProjectionService.loadProjectedGraph(
                eq("run-1"),
                any()
        )).thenReturn(projectedGraph);

        CommunityResult communityResult =
                new CommunityResult(new int[]{0, 1});

        when(resolutionProbeService.findBest(
                projectedGraph,
                3,
                10
        )).thenReturn(
                new ResolutionProbeService.ProbeResult(
                        0.012,
                        communityResult,
                        0.71,
                        0.42,
                        2
                )
        );

        List<Subsystem> subsystems = List.of(
                Subsystem.builder()
                        .subsystemId("ss_001")
                        .name("core")
                        .score(0.0)
                        .memberSymbolIds(List.of("H1"))
                        .entrySymbolIds(List.of("H1"))
                        .coreSymbolIds(List.of())
                        .packageRoots(List.of())
                        .build()
        );

        when(subsystemAssembler.assemble(
                any(),
                any(),
                anyInt()
        )).thenReturn(subsystems);

        when(subsystemRefinerService.refine(
                any(),
                any(),
                anyString()
        )).thenReturn(
                new SubsystemRefinerService.RefineOutcome(
                        subsystems,
                        Map.of()
                )
        );

        /*
         * Refiner 이후 deterministic ID 단계.
         */
        when(subsystemIdentityService.rekey(
                subsystems,
                "commit-abc123"
        )).thenReturn(subsystems);

        when(rankingService.rank(
                any(),
                any(),
                anyInt()
        )).thenReturn(
                new RankingService.RankingResult(
                        List.of(),
                        List.of(),
                        subsystems
                )
        );

        Artifact rankingsArtifact = mock(Artifact.class);
        when(rankingsArtifact.getArtifactId()).thenReturn(1L);

        Artifact subsystemsArtifact = mock(Artifact.class);
        when(subsystemsArtifact.getArtifactId()).thenReturn(2L);

        when(clusterArtifactPublisher.publishSubsystems(
                any(),
                any()
        )).thenReturn(subsystemsArtifact);

        when(clusterArtifactPublisher.publishRankings(
                any(),
                any()
        )).thenReturn(rankingsArtifact);

        // when

        clusterBuildService.build(request);

        // then

        /*
         * GraphProjectionService에 전달된 refined entry point를 capture한다.
         */
        ArgumentCaptor<Set<String>> entryIdsCaptor =
                ArgumentCaptor.forClass(Set.class);

        verify(graphProjectionService)
                .loadProjectedGraph(
                        eq("run-1"),
                        entryIdsCaptor.capture()
                );

        Set<String> passedIds =
                entryIdsCaptor.getValue();

        assertThat(passedIds)
                .containsExactlyInAnyOrder(
                        "sym-high",
                        "sym-med"
                );

        assertThat(passedIds)
                .doesNotContain("sym-low");

        /*
         * EntryPointJsonCodec이 MED threshold로 실행됐는지 확인한다.
         */
        verify(entryPointJsonCodec)
                .readSymbolIds(
                        any(),
                        eq("MED")
                );

        /*
         * 개선된 deterministic ID 단계가 실행되었는지도 확인한다.
         */
        verify(subsystemIdentityService)
                .rekey(
                        subsystems,
                        "commit-abc123"
                );
    }

    @Test
    @DisplayName("ENTRY_POINTS_JSON 부재 시 빈 set이 GraphProjectionService에 전달되어 API-flow signal이 auto-disable된다")
    @SuppressWarnings("unchecked")
    void build_entryPointsJsonMissing_passesEmptySetToProjection() {
        // given

        /*
         * ENTRYPOINT 단계가 실행되지 않았거나
         * ENTRY_POINTS_JSON 생성에 실패한 상황을 가정한다.
         */
        ClusterBuildRequest request = mock(ClusterBuildRequest.class);
        when(request.getRunId()).thenReturn("run-1");
        when(request.getIterations()).thenReturn(10);
        when(request.getMinClusterSize()).thenReturn(3);
        when(request.getTopK()).thenReturn(20);

        RepoRun run = mock(RepoRun.class);
        when(run.getRunId()).thenReturn("run-1");
        when(run.getCommitSha()).thenReturn("commit-abc123");

        when(repoRunRepository.findById("run-1"))
                .thenReturn(Optional.of(run));

        when(publicApiEntrySyncService.ensureTypeEntries(run))
                .thenReturn(Set.of("H1"));

        /*
         * ENTRY_POINTS_JSON artifact가 존재하지 않는다.
         */
        when(
                artifactRepository
                        .findTopByRun_RunIdAndKindOrderByCreatedAtDesc(
                                "run-1",
                                ArtifactKind.ENTRY_POINTS_JSON
                        )
        ).thenReturn(Optional.empty());

        ProjectedNode projectedNode = mock(ProjectedNode.class);

        ProjectedGraph projectedGraph = mock(ProjectedGraph.class);
        when(projectedGraph.getNodes())
                .thenReturn(List.of(projectedNode));

        when(graphProjectionService.loadProjectedGraph(
                eq("run-1"),
                any()
        )).thenReturn(projectedGraph);

        CommunityResult communityResult =
                new CommunityResult(new int[]{0});

        when(resolutionProbeService.findBest(
                projectedGraph,
                3,
                10
        )).thenReturn(
                new ResolutionProbeService.ProbeResult(
                        0.012,
                        communityResult,
                        0.71,
                        0.42,
                        1
                )
        );

        List<Subsystem> subsystems = List.of(
                Subsystem.builder()
                        .subsystemId("ss_001")
                        .name("core")
                        .score(0.0)
                        .memberSymbolIds(List.of("H1"))
                        .entrySymbolIds(List.of("H1"))
                        .coreSymbolIds(List.of())
                        .packageRoots(List.of())
                        .build()
        );

        when(subsystemAssembler.assemble(
                any(),
                any(),
                anyInt()
        )).thenReturn(subsystems);

        when(subsystemRefinerService.refine(
                any(),
                any(),
                anyString()
        )).thenReturn(
                new SubsystemRefinerService.RefineOutcome(
                        subsystems,
                        Map.of()
                )
        );

        /*
         * Refiner 이후 deterministic ID 생성.
         */
        when(subsystemIdentityService.rekey(
                subsystems,
                "commit-abc123"
        )).thenReturn(subsystems);

        when(rankingService.rank(
                any(),
                any(),
                anyInt()
        )).thenReturn(
                new RankingService.RankingResult(
                        List.of(),
                        List.of(),
                        subsystems
                )
        );

        Artifact rankingsArtifact = mock(Artifact.class);
        when(rankingsArtifact.getArtifactId()).thenReturn(1L);

        Artifact subsystemsArtifact = mock(Artifact.class);
        when(subsystemsArtifact.getArtifactId()).thenReturn(2L);

        when(clusterArtifactPublisher.publishSubsystems(
                any(),
                any()
        )).thenReturn(subsystemsArtifact);

        when(clusterArtifactPublisher.publishRankings(
                any(),
                any()
        )).thenReturn(rankingsArtifact);

        // when

        clusterBuildService.build(request);

        // then

        /*
         * ENTRY_POINTS_JSON이 없으므로
         * GraphProjectionService에는 빈 Set이 전달되어야 한다.
         */
        ArgumentCaptor<Set<String>> entryIdsCaptor =
                ArgumentCaptor.forClass(Set.class);

        verify(graphProjectionService)
                .loadProjectedGraph(
                        eq("run-1"),
                        entryIdsCaptor.capture()
                );

        assertThat(entryIdsCaptor.getValue())
                .isEmpty();

        /*
         * artifact가 존재하지 않기 때문에
         * EntryPointJsonCodec은 호출되지 않아야 한다.
         */
        verify(entryPointJsonCodec, never())
                .readSymbolIds(
                        any(),
                        anyString()
                );

        /*
         * ENTRY_POINTS_JSON 존재 여부와 관계없이
         * 최종 subsystem ID 생성 단계는 정상 실행되어야 한다.
         */
        verify(subsystemIdentityService)
                .rekey(
                        subsystems,
                        "commit-abc123"
                );
    }
}