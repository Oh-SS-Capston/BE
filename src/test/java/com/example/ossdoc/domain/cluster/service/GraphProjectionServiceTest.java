package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.support.EdgeWeightPolicy;
import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.ResolutionStatus;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.publicapi.entity.PublicApiEntry;
import com.example.ossdoc.domain.publicapi.repository.PublicApiEntryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphProjectionServiceTest {

    @Test
    @DisplayName("Projects TYPE symbols and merges bidirectional CALLS edges into one undirected weighted edge")
    void loadProjectedGraph_shouldProjectTypeGraphCorrectly() {
        // given
        SymbolRepository symbolRepository = mock(SymbolRepository.class);
        EdgeRepository edgeRepository = mock(EdgeRepository.class);
        PublicApiEntryRepository publicApiEntryRepository = mock(PublicApiEntryRepository.class);
        EdgeWeightPolicy edgeWeightPolicy = new EdgeWeightPolicy();

        GraphProjectionService graphProjectionService = new GraphProjectionService(
                symbolRepository,
                edgeRepository,
                publicApiEntryRepository,
                edgeWeightPolicy
        );

        SymbolEntity authController = mock(SymbolEntity.class);
        when(authController.getSymbolId()).thenReturn("sym-auth-controller");
        when(authController.getQualifiedName()).thenReturn("com.example.auth.AuthController");
        when(authController.getSimpleName()).thenReturn("AuthController");
        when(authController.getModule()).thenReturn(null);

        SymbolEntity authService = mock(SymbolEntity.class);
        when(authService.getSymbolId()).thenReturn("sym-auth-service");
        when(authService.getQualifiedName()).thenReturn("com.example.auth.AuthService");
        when(authService.getSimpleName()).thenReturn("AuthService");
        when(authService.getModule()).thenReturn(null);

        // TYPE 조회 결과에는 이 둘만 들어감
        when(symbolRepository.findAllByRun_RunIdAndSymbolKind("run-1", SymbolKind.TYPE))
                .thenReturn(List.of(authController, authService));

        PublicApiEntry publicApiEntry = mock(PublicApiEntry.class);
        when(publicApiEntry.getSymbol()).thenReturn(authController);

        when(publicApiEntryRepository.findAllByRun_RunId("run-1"))
                .thenReturn(List.of(publicApiEntry));

        Edge edge1 = mock(Edge.class);
        when(edge1.getFromSymbol()).thenReturn(authController);
        when(edge1.getToSymbol()).thenReturn(authService);
        when(edge1.getEdgeType()).thenReturn(EdgeType.CALLS);
        when(edge1.getResolution()).thenReturn(ResolutionStatus.RESOLVED);
        when(edge1.getConfidence()).thenReturn(new BigDecimal("1.0"));

        Edge edge2 = mock(Edge.class);
        when(edge2.getFromSymbol()).thenReturn(authService);
        when(edge2.getToSymbol()).thenReturn(authController);
        when(edge2.getEdgeType()).thenReturn(EdgeType.CALLS);
        when(edge2.getResolution()).thenReturn(ResolutionStatus.RESOLVED);
        when(edge2.getConfidence()).thenReturn(new BigDecimal("0.5"));

        when(edgeRepository.findAllByRun_RunId("run-1"))
                .thenReturn(List.of(edge1, edge2));

        // when
        ProjectedGraph projectedGraph = graphProjectionService.loadProjectedGraph("run-1");

        // then
        assertThat(projectedGraph.getNodes()).hasSize(2);
        assertThat(projectedGraph.getNodeIndexMap())
                .containsKeys("sym-auth-controller", "sym-auth-service");

        ProjectedNode first = projectedGraph.getNodes().get(0);
        ProjectedNode second = projectedGraph.getNodes().get(1);

        assertThat(first.getQualifiedName()).isEqualTo("com.example.auth.AuthController");
        assertThat(first.isPublicApi()).isTrue();

        assertThat(second.getQualifiedName()).isEqualTo("com.example.auth.AuthService");
        assertThat(second.isPublicApi()).isFalse();

        // directed 2개가 하나의 undirected edge로 합쳐져야 함
        assertThat(projectedGraph.getEdges()).hasSize(1);

        ProjectedEdge mergedEdge = projectedGraph.getEdges().get(0);
        assertThat(mergedEdge.getFromIndex()).isEqualTo(0);
        assertThat(mergedEdge.getToIndex()).isEqualTo(1);

        // CALLS = 1.5
        // edge1 = 1.5 * 1.0 * 1.0 = 1.5
        // edge2 = 1.5 * 1.0 * 0.5 = 0.75
        // 합계 = 2.25
        assertThat(mergedEdge.getWeight()).isEqualTo(2.25);
    }
}
