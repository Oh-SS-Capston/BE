package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.dto.request.ClusterParameterRecommendRequest;
import com.example.ossdoc.domain.cluster.dto.response.ClusterParameterRecommendResponse;
import com.example.ossdoc.domain.cluster.exception.ClusterException;
import com.example.ossdoc.domain.cluster.exception.code.ClusterErrorCode;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClusterParameterRecommendServiceTest {

    @Mock
    private RepoRunRepository repoRunRepository;

    @Mock
    private SymbolRepository symbolRepository;

    @Mock
    private EdgeRepository edgeRepository;

    @InjectMocks
    private ClusterParameterRecommendService clusterParameterRecommendService;

    @Test
    @DisplayName("SMALL 고밀도 입력에서 추천값을 보정해 반환한다")
    void recommend_shouldReturnSmallDenseRecommendation() {
        // given
        ClusterParameterRecommendRequest request = mock(ClusterParameterRecommendRequest.class);
        when(request.getRunId()).thenReturn("run-small");

        when(repoRunRepository.findById("run-small")).thenReturn(Optional.of(mock(RepoRun.class)));
        when(symbolRepository.countByRun_RunIdAndSymbolKind("run-small", SymbolKind.TYPE)).thenReturn(126L);
        when(edgeRepository.countByRun_RunId("run-small")).thenReturn(13_624L);

        // when
        ClusterParameterRecommendResponse response = clusterParameterRecommendService.recommend(request);

        // then
        assertThat(response.getSizeTier()).isEqualTo("SMALL");
        assertThat(response.getEdgePerType()).isEqualTo(108.127);
        assertThat(response.getClusterRecommended().getResolution()).isEqualTo(0.22);
        assertThat(response.getClusterRecommended().getIterations()).isEqualTo(8);
        assertThat(response.getClusterRecommended().getMinClusterSize()).isEqualTo(3);
        assertThat(response.getClusterRecommended().getTopK()).isEqualTo(25);
        assertThat(response.getClassMapRecommended().getMaxNodes()).isEqualTo(24);
        assertThat(response.getClassMapRecommended().getMaxEdges()).isEqualTo(50);
        assertThat(response.getClassMapRecommended().getStartHereTopN()).isEqualTo(3);
    }

    @Test
    @DisplayName("MEDIUM 저밀도 입력에서 분할 강도를 높이는 추천을 반환한다")
    void recommend_shouldReturnMediumSparseRecommendation() {
        // given
        ClusterParameterRecommendRequest request = mock(ClusterParameterRecommendRequest.class);
        when(request.getRunId()).thenReturn("run-medium");

        when(repoRunRepository.findById("run-medium")).thenReturn(Optional.of(mock(RepoRun.class)));
        when(symbolRepository.countByRun_RunIdAndSymbolKind("run-medium", SymbolKind.TYPE)).thenReturn(300L);
        when(edgeRepository.countByRun_RunId("run-medium")).thenReturn(3_000L);

        // when
        ClusterParameterRecommendResponse response = clusterParameterRecommendService.recommend(request);

        // then
        assertThat(response.getSizeTier()).isEqualTo("MEDIUM");
        assertThat(response.getEdgePerType()).isEqualTo(10.0);
        assertThat(response.getClusterRecommended().getResolution()).isEqualTo(0.2);
        assertThat(response.getClusterRecommended().getIterations()).isEqualTo(12);
        assertThat(response.getClusterRecommended().getMinClusterSize()).isEqualTo(2);
        assertThat(response.getClusterRecommended().getTopK()).isEqualTo(30);
        assertThat(response.getClassMapRecommended().getMaxNodes()).isEqualTo(36);
        assertThat(response.getClassMapRecommended().getMaxEdges()).isEqualTo(80);
        assertThat(response.getClassMapRecommended().getStartHereTopN()).isEqualTo(5);
    }

    @Test
    @DisplayName("runId가 없으면 CLUSTER_RUN_NOT_FOUND를 던진다")
    void recommend_shouldThrowWhenRunNotFound() {
        // given
        ClusterParameterRecommendRequest request = mock(ClusterParameterRecommendRequest.class);
        when(request.getRunId()).thenReturn("missing-run");
        when(repoRunRepository.findById("missing-run")).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> clusterParameterRecommendService.recommend(request))
                .isInstanceOf(ClusterException.class)
                .extracting("code")
                .isEqualTo(ClusterErrorCode.CLUSTER_RUN_NOT_FOUND);
    }
}
