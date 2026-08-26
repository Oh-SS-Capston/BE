package com.example.ossdoc.domain.publicapi.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.DerivationKind;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.OriginKind;
import com.example.ossdoc.domain.graphstore.enums.ResolutionStatus;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.graphstore.support.EdgeInferencePolicy;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiFlowTraceServiceSemanticEdgeTest {

    @Mock private RepoRunRepository repoRunRepository;
    @Mock private SymbolRepository symbolRepository;
    @Mock private EdgeRepository edgeRepository;
    @Mock private ArtifactService artifactService;
    @Mock private ArtifactRepository artifactRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    @Spy private EdgeInferencePolicy edgeInferencePolicy = new EdgeInferencePolicy();

    @InjectMocks
    private ApiFlowTraceService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "maxEntryPoints", 50);
    }

    @Test
    @DisplayName("API flow는 low-confidence CALLS를 제외하고 relation metadata를 1.1 JSON까지 전달한다")
    void trace_shouldFilterLowConfidenceAndPropagateRelationMetadata() {
        String runId = "run-flow";
        RepoRun run = mock(RepoRun.class);
        when(repoRunRepository.findById(runId)).thenReturn(Optional.of(run));

        SymbolEntity type = symbol("type:org.acme.Api", SymbolKind.TYPE, "org.acme.Api");
        SymbolEntity entryMethod = symbol("method:org.acme.Api#start", SymbolKind.METHOD, "org.acme.Api#start");
        SymbolEntity calledMethod = symbol("method:org.acme.Service#work", SymbolKind.METHOD, "org.acme.Service#work");
        when(entryMethod.getOwner()).thenReturn(type);

        Artifact apiMap = mock(Artifact.class);
        var apiMapJson = objectMapper.createObjectNode();
        var entryPointNode = apiMapJson.putArray("entry_points").addObject();
        entryPointNode.put("symbol_id", type.getSymbolId());
        entryPointNode.put("role", "PRIMARY");
        entryPointNode.put("confidence", "HIGH");
        entryPointNode.putArray("entry_methods").addObject()
                .put("symbol_id", entryMethod.getSymbolId());
        when(apiMap.getMeta()).thenReturn(apiMapJson);
        when(artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.API_MAP_JSON))
                .thenReturn(Optional.of(apiMap));
        when(artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.BUILD_MANIFEST))
                .thenReturn(Optional.empty());

        when(symbolRepository.findAllByRun_RunIdAndSymbolKind(runId, SymbolKind.METHOD))
                .thenReturn(List.of(entryMethod, calledMethod));
        when(symbolRepository.findAllByRun_RunIdAndSymbolKind(runId, SymbolKind.TYPE))
                .thenReturn(List.of(type));

        Edge high = callEdge(1L, entryMethod, calledMethod, new BigDecimal("0.91"));
        Edge low = callEdge(2L, calledMethod, entryMethod, new BigDecimal("0.20"));
        when(edgeRepository.findAllByRun_RunIdAndEdgeTypeIn(runId, List.of(EdgeType.CALLS)))
                .thenReturn(List.of(high, low));

        Artifact savedArtifact = mock(Artifact.class);
        when(artifactService.saveJsonArtifact(any(), any(), any(), any(), any()))
                .thenReturn(savedArtifact);

        service.trace(runId);

        ArgumentCaptor<JsonNode> jsonCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(artifactService).saveJsonArtifact(
                eq(run), eq(ArtifactKind.API_FLOW_TRACE_JSON), eq("1.1"), eq("publicapi/api_flow_trace.json"),
                jsonCaptor.capture());

        JsonNode root = jsonCaptor.getValue();
        assertThat(root.path("schemaVersion").asText()).isEqualTo("1.1");
        JsonNode edges = root.path("traces").get(0).path("reachableEdges");
        assertThat(edges.size()).isEqualTo(1);
        JsonNode edge = edges.get(0);
        assertThat(edge.path("confidence").asDouble()).isEqualTo(0.91d);
        assertThat(edge.path("resolution").asText()).isEqualTo("RESOLVED");
        assertThat(edge.path("resolutionReason").asText()).isEqualTo("symbol solver exact");
        assertThat(edge.path("origin").asText()).isEqualTo("AST");
        assertThat(edge.path("derivationKind").asText()).isEqualTo("DIRECT");
        assertThat(edge.path("callSiteLine").asInt()).isEqualTo(77);
        assertThat(edge.path("defaultVisible").asBoolean()).isTrue();
        assertThat(edge.path("attrs").path("confidence_band").asText()).isEqualTo("high");
    }

    private SymbolEntity symbol(String id, SymbolKind kind, String qn) {
        SymbolEntity symbol = mock(SymbolEntity.class);
        when(symbol.getSymbolId()).thenReturn(id);
        when(symbol.getSymbolKind()).thenReturn(kind);
        when(symbol.getQualifiedName()).thenReturn(qn);
        return symbol;
    }

    private Edge callEdge(Long id, SymbolEntity from, SymbolEntity to, BigDecimal confidence) {
        Edge edge = mock(Edge.class);
        when(edge.getEdgeId()).thenReturn(id);
        when(edge.getEdgeType()).thenReturn(EdgeType.CALLS);
        when(edge.getFromSymbol()).thenReturn(from);
        when(edge.getToSymbol()).thenReturn(to);
        when(edge.getConfidence()).thenReturn(confidence);
        when(edge.getResolution()).thenReturn(ResolutionStatus.RESOLVED);
        if (confidence.compareTo(new BigDecimal("0.4")) >= 0) {
            when(edge.getResolutionReason()).thenReturn("symbol solver exact");
            when(edge.getOrigin()).thenReturn(OriginKind.AST);
            when(edge.getDerivationKind()).thenReturn(DerivationKind.DIRECT);
            when(edge.getCallSiteLine()).thenReturn(77);
            when(edge.getAttrs()).thenReturn(JsonNodeFactory.instance.objectNode()
                    .put("confidence_band", "high")
                    .put("default_visible", true));
        }
        return edge;
    }
}
