package com.example.ossdoc.domain.publicapi.service;

import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.AccessLevel;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.ResolutionStatus;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.graphstore.support.EdgeInferencePolicy;
import com.example.ossdoc.domain.publicapi.model.ExtensionPointCandidate;
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
class ExtensionPointDetectServiceSemanticEdgeTest {

    @Mock private SymbolRepository symbolRepository;
    @Mock private EdgeRepository edgeRepository;
    @Mock private ArtifactRepository artifactRepository;
    @Spy private EdgeInferencePolicy edgeInferencePolicy = new EdgeInferencePolicy();

    @InjectMocks
    private ExtensionPointDetectService service;

    @Test
    @DisplayName("고신뢰 PROVIDES_SPI edge의 target 타입은 즉시 HIGH extension point가 된다")
    void resolvedProvidesSpi_shouldBecomeHighExtensionPoint() {
        String runId = "run-spi";
        SymbolEntity extensionType = publicInterface("type:org.acme.spi.Plugin", "org.acme.spi.Plugin");
        SymbolEntity provider = mock(SymbolEntity.class);

        Edge spi = mock(Edge.class);
        when(spi.getEdgeType()).thenReturn(EdgeType.PROVIDES_SPI);
        when(spi.getFromSymbol()).thenReturn(provider);
        when(spi.getToSymbol()).thenReturn(extensionType);
        when(spi.getResolution()).thenReturn(ResolutionStatus.RESOLVED);
        when(spi.getConfidence()).thenReturn(new BigDecimal("0.94"));
        when(spi.getAttrs()).thenReturn(JsonNodeFactory.instance.objectNode().put("default_visible", true));

        when(symbolRepository.findAllByRun_RunId(runId)).thenReturn(List.of(extensionType));
        when(edgeRepository.findAllByRun_RunIdAndEdgeTypeAndToSymbolIsNotNull(runId, EdgeType.IMPLEMENTS))
                .thenReturn(List.of());
        when(edgeRepository.findAllByRun_RunIdAndEdgeTypeAndToSymbolIsNotNull(runId, EdgeType.EXTENDS))
                .thenReturn(List.of());
        when(edgeRepository.findAllByRun_RunIdAndEdgeTypeAndToSymbolIsNotNull(runId, EdgeType.PARAM))
                .thenReturn(List.of());
        when(edgeRepository.findAllByRun_RunIdAndEdgeTypeIn(runId, List.of(EdgeType.PROVIDES_SPI, EdgeType.LOADS_SERVICE)))
                .thenReturn(List.of(spi));
        when(artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.FACTS_JSON))
                .thenReturn(Optional.empty());
        when(artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.SUBSYSTEMS_JSON))
                .thenReturn(Optional.empty());

        List<ExtensionPointCandidate> result = service.detect(runId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSymbolId()).isEqualTo(extensionType.getSymbolId());
        assertThat(result.get(0).getConfidence()).isEqualTo("HIGH");
        assertThat(result.get(0).getSignals()).contains("SPI_RELATION_RESOLVED");
        assertThat(result.get(0).getSemanticRelations()).hasSize(1);
        assertThat(result.get(0).getSemanticRelations().get(0).getEdgeType()).isEqualTo("PROVIDES_SPI");
        assertThat(result.get(0).getSemanticRelations().get(0).getConfidence()).isEqualTo(0.94d);
        assertThat(result.get(0).getSemanticRelations().get(0).getResolution()).isEqualTo("RESOLVED");
        assertThat(result.get(0).getSemanticRelations().get(0).getDefaultVisible()).isTrue();
    }

    @Test
    @DisplayName("PARTIAL이지만 usable한 SPI relation은 MED extension point 근거가 된다")
    void partialUsableSpi_shouldBecomeMediumExtensionPoint() {
        String runId = "run-spi-partial";
        SymbolEntity extensionType = publicInterface("type:org.acme.contract.ExtensionContract", "org.acme.contract.ExtensionContract");
        SymbolEntity provider = mock(SymbolEntity.class);

        Edge spi = mock(Edge.class);
        when(spi.getEdgeType()).thenReturn(EdgeType.LOADS_SERVICE);
        when(spi.getFromSymbol()).thenReturn(provider);
        when(spi.getToSymbol()).thenReturn(extensionType);
        when(spi.getResolution()).thenReturn(ResolutionStatus.PARTIAL);
        when(spi.getConfidence()).thenReturn(new BigDecimal("0.60"));
        when(spi.getAttrs()).thenReturn(JsonNodeFactory.instance.objectNode().put("default_visible", false));

        when(symbolRepository.findAllByRun_RunId(runId)).thenReturn(List.of(extensionType));
        when(edgeRepository.findAllByRun_RunIdAndEdgeTypeAndToSymbolIsNotNull(runId, EdgeType.IMPLEMENTS))
                .thenReturn(List.of());
        when(edgeRepository.findAllByRun_RunIdAndEdgeTypeAndToSymbolIsNotNull(runId, EdgeType.EXTENDS))
                .thenReturn(List.of());
        when(edgeRepository.findAllByRun_RunIdAndEdgeTypeAndToSymbolIsNotNull(runId, EdgeType.PARAM))
                .thenReturn(List.of());
        when(edgeRepository.findAllByRun_RunIdAndEdgeTypeIn(runId, List.of(EdgeType.PROVIDES_SPI, EdgeType.LOADS_SERVICE)))
                .thenReturn(List.of(spi));
        when(artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.FACTS_JSON))
                .thenReturn(Optional.empty());
        when(artifactRepository.findTopByRun_RunIdAndKindOrderByCreatedAtDesc(runId, ArtifactKind.SUBSYSTEMS_JSON))
                .thenReturn(Optional.empty());

        List<ExtensionPointCandidate> result = service.detect(runId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getConfidence()).isEqualTo("MED");
        assertThat(result.get(0).getSignals()).contains("SPI_RELATION_INFERRED");
        assertThat(result.get(0).getSemanticRelations()).hasSize(1);
        assertThat(result.get(0).getSemanticRelations().get(0).getEdgeType()).isEqualTo("LOADS_SERVICE");
        assertThat(result.get(0).getSemanticRelations().get(0).getConfidence()).isEqualTo(0.60d);
        assertThat(result.get(0).getSemanticRelations().get(0).getResolution()).isEqualTo("PARTIAL");
        assertThat(result.get(0).getSemanticRelations().get(0).getDefaultVisible()).isFalse();
    }

    private SymbolEntity publicInterface(String symbolId, String qn) {
        SymbolEntity symbol = mock(SymbolEntity.class);
        when(symbol.getSymbolId()).thenReturn(symbolId);
        when(symbol.getSymbolKind()).thenReturn(SymbolKind.TYPE);
        when(symbol.getAccess()).thenReturn(AccessLevel.PUBLIC);
        when(symbol.getQualifiedName()).thenReturn(qn);
        when(symbol.getSimpleName()).thenReturn(qn.substring(qn.lastIndexOf('.') + 1));
        when(symbol.getSignature()).thenReturn(JsonNodeFactory.instance.objectNode().put("typeKind", "interface"));
        when(symbol.getModifiers()).thenReturn(JsonNodeFactory.instance.arrayNode());
        return symbol;
    }
}
