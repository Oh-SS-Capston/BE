package com.example.ossdoc.domain.publicapi.service;

import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.AccessLevel;
import com.example.ossdoc.domain.graphstore.enums.DerivationKind;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.OriginKind;
import com.example.ossdoc.domain.graphstore.enums.ResolutionStatus;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.graphstore.support.EdgeInferencePolicy;
import com.example.ossdoc.domain.publicapi.model.EntryPointCandidate;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EntryPointDetectServiceSemanticEdgeTest {

    @Mock private SymbolRepository symbolRepository;
    @Mock private EdgeRepository edgeRepository;
    @Mock private ArtifactRepository artifactRepository;
    @Spy private EdgeInferencePolicy edgeInferencePolicy = new EdgeInferencePolicy();

    @InjectMocks
    private EntryPointDetectService service;

    @Test
    @DisplayName("고신뢰 HANDLES_ENDPOINT는 Controller를 HIGH 진입점으로 승격하고 HTTP 메타를 entry method에 전달한다")
    void resolvedEndpoint_shouldBecomeHighEntryPoint() {
        String runId = "run-endpoint";
        SymbolEntity controller = publicType("type:org.acme.web.UserController", "org.acme.web.UserController");
        SymbolEntity endpointMethod = publicMethod(
                "method:org.acme.web.UserController#getUser", "getUser", controller);

        Edge endpoint = endpointEdge(endpointMethod, new BigDecimal("0.95"), ResolutionStatus.RESOLVED, true);

        when(symbolRepository.findAllByRun_RunId(runId)).thenReturn(List.of(controller, endpointMethod));
        when(edgeRepository.findAllByRun_RunIdAndEdgeTypeIn(runId, List.of(EdgeType.HANDLES_ENDPOINT)))
                .thenReturn(List.of(endpoint));
        when(edgeRepository.findAllByRun_RunIdAndEdgeTypeIn(runId, List.of(EdgeType.IMPLEMENTS, EdgeType.EXTENDS)))
                .thenReturn(List.of());
        when(edgeRepository.findAllByRun_RunIdAndEdgeTypeAndToSymbolIsNotNull(runId, EdgeType.RETURNS))
                .thenReturn(List.of());
        when(edgeRepository.findAllByRun_RunIdAndEdgeTypeAndToSymbolIsNotNull(runId, EdgeType.ANNOTATED_WITH))
                .thenReturn(List.of());
        when(artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.FACTS_JSON))
                .thenReturn(Optional.empty());
        when(artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.BUILD_MANIFEST))
                .thenReturn(Optional.empty());

        List<EntryPointCandidate> result = service.detect(runId);

        assertThat(result).hasSize(1);
        EntryPointCandidate candidate = result.get(0);
        assertThat(candidate.getConfidence()).isEqualTo("HIGH");
        assertThat(candidate.getSignals()).contains("HANDLES_ENDPOINT_RESOLVED");
        assertThat(candidate.getEntryMethods()).hasSize(1);
        EntryPointCandidate.EntryMethodInfo method = candidate.getEntryMethods().get(0);
        assertThat(method.getReason()).isEqualTo("HTTP_ENDPOINT");
        assertThat(method.getHttpEndpoints()).hasSize(1);
        EntryPointCandidate.HttpEndpointInfo http = method.getHttpEndpoints().get(0);
        assertThat(http.getHttpMethod()).isEqualTo("GET");
        assertThat(http.getPath()).isEqualTo("/users/{id}");
        assertThat(http.getConfidence()).isEqualTo(0.95d);
        assertThat(http.getResolution()).isEqualTo("RESOLVED");
        assertThat(http.getDefaultVisible()).isTrue();
    }

    @Test
    @DisplayName("confidence 0.4 미만 HANDLES_ENDPOINT는 진입점 추론에 사용하지 않는다")
    void lowConfidenceEndpoint_shouldNotBypassNormalEntryFilter() {
        String runId = "run-low-endpoint";
        SymbolEntity controller = publicType("type:org.acme.web.HiddenController", "org.acme.web.HiddenController");
        SymbolEntity endpointMethod = publicMethod(
                "method:org.acme.web.HiddenController#get", "get", controller);
        Edge endpoint = endpointEdge(endpointMethod, new BigDecimal("0.20"), ResolutionStatus.PARTIAL, false);

        when(symbolRepository.findAllByRun_RunId(runId)).thenReturn(List.of(controller, endpointMethod));
        when(edgeRepository.findAllByRun_RunIdAndEdgeTypeIn(runId, List.of(EdgeType.HANDLES_ENDPOINT)))
                .thenReturn(List.of(endpoint));
        when(edgeRepository.findAllByRun_RunIdAndEdgeTypeIn(runId, List.of(EdgeType.IMPLEMENTS, EdgeType.EXTENDS)))
                .thenReturn(List.of());
        when(edgeRepository.findAllByRun_RunIdAndEdgeTypeAndToSymbolIsNotNull(runId, EdgeType.RETURNS))
                .thenReturn(List.of());
        when(edgeRepository.findAllByRun_RunIdAndEdgeTypeAndToSymbolIsNotNull(runId, EdgeType.ANNOTATED_WITH))
                .thenReturn(List.of());
        when(artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.FACTS_JSON))
                .thenReturn(Optional.empty());
        when(artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.BUILD_MANIFEST))
                .thenReturn(Optional.empty());

        assertThat(service.detect(runId)).isEmpty();
    }

    private SymbolEntity publicType(String symbolId, String qn) {
        SymbolEntity symbol = mock(SymbolEntity.class);
        when(symbol.getSymbolId()).thenReturn(symbolId);
        when(symbol.getSymbolKind()).thenReturn(SymbolKind.TYPE);
        when(symbol.getAccess()).thenReturn(AccessLevel.PUBLIC);
        when(symbol.getQualifiedName()).thenReturn(qn);
        when(symbol.getSimpleName()).thenReturn(qn.substring(qn.lastIndexOf('.') + 1));
        when(symbol.getTypeKind()).thenReturn("class");
        when(symbol.getModifiers()).thenReturn(JsonNodeFactory.instance.arrayNode());
        when(symbol.getSignature()).thenReturn(JsonNodeFactory.instance.objectNode().put("typeKind", "class"));
        when(symbol.getAnnotations()).thenReturn(JsonNodeFactory.instance.arrayNode());
        return symbol;
    }

    private SymbolEntity publicMethod(String symbolId, String name, SymbolEntity owner) {
        SymbolEntity method = mock(SymbolEntity.class);
        when(method.getSymbolId()).thenReturn(symbolId);
        when(method.getSymbolKind()).thenReturn(SymbolKind.METHOD);
        when(method.getAccess()).thenReturn(AccessLevel.PUBLIC);
        when(method.getQualifiedName()).thenReturn(symbolId.substring("method:".length()));
        when(method.getSimpleName()).thenReturn(name);
        when(method.getOwner()).thenReturn(owner);
        when(method.getModifiers()).thenReturn(JsonNodeFactory.instance.arrayNode());
        return method;
    }

    private Edge endpointEdge(SymbolEntity method,
                              BigDecimal confidence,
                              ResolutionStatus resolution,
                              boolean defaultVisible) {
        Edge edge = mock(Edge.class);
        when(edge.getEdgeType()).thenReturn(EdgeType.HANDLES_ENDPOINT);
        when(edge.getFromSymbol()).thenReturn(method);
        when(edge.getConfidence()).thenReturn(confidence);
        when(edge.getResolution()).thenReturn(resolution);
        when(edge.getResolutionReason()).thenReturn("endpoint mapping resolved");
        when(edge.getOrigin()).thenReturn(OriginKind.OBSERVED);
        when(edge.getDerivationKind()).thenReturn(DerivationKind.DERIVED);
        when(edge.getAttrs()).thenReturn(JsonNodeFactory.instance.objectNode()
                .put("http_method", "GET")
                .put("path", "/users/{id}")
                .put("default_visible", defaultVisible));
        return edge;
    }
}
