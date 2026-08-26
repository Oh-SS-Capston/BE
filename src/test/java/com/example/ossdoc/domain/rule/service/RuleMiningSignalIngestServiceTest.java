package com.example.ossdoc.domain.rule.service;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.EdgeEvidence;
import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.DerivationKind;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import com.example.ossdoc.domain.graphstore.enums.OriginKind;
import com.example.ossdoc.domain.graphstore.enums.ResolutionStatus;
import com.example.ossdoc.domain.graphstore.repository.EdgeEvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.EvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.graphstore.support.EdgeInferencePolicy;
import com.example.ossdoc.domain.rule.entity.RuleMiningSignal;
import com.example.ossdoc.domain.rule.enums.RuleMiningSignalType;
import com.example.ossdoc.domain.rule.repository.RuleCandidateEvidenceRepository;
import com.example.ossdoc.domain.rule.repository.RuleCandidateRepository;
import com.example.ossdoc.domain.rule.repository.RuleMiningSignalRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RuleMiningSignalIngestServiceTest {

    @Mock private RepoRunRepository repoRunRepository;
    @Mock private EdgeRepository edgeRepository;
    @Mock private EdgeEvidenceRepository edgeEvidenceRepository;
    @Mock private EvidenceRepository evidenceRepository;
    @Mock private SymbolRepository symbolRepository;
    @Mock private RuleMiningSignalRepository ruleMiningSignalRepository;
    @Mock private RuleCandidateRepository ruleCandidateRepository;
    @Mock private RuleCandidateEvidenceRepository ruleCandidateEvidenceRepository;
    @Spy private EdgeInferencePolicy edgeInferencePolicy = new EdgeInferencePolicy();

    @InjectMocks
    private RuleMiningSignalIngestService service;

    @Test
    @DisplayName("CREATES를 OBJECT_CREATION으로 신호화하고 edge confidence/meta를 보존하며 중복 evidence는 1개만 선택한다")
    void createsEdge_shouldProduceSingleObjectCreationSignalWithSemanticMetadata() {
        String runId = "run-rule";
        RepoRun run = mock(RepoRun.class);
        SymbolEntity sourceMethod = mock(SymbolEntity.class);
        SymbolEntity createdType = mock(SymbolEntity.class);
        when(sourceMethod.getSymbolId()).thenReturn("method:org.acme.OrderService#create");
        when(createdType.getQualifiedName()).thenReturn("org.acme.Order");

        Edge creates = mock(Edge.class);
        when(creates.getEdgeId()).thenReturn(101L);
        when(creates.getEdgeType()).thenReturn(EdgeType.CREATES);
        when(creates.getFromSymbol()).thenReturn(sourceMethod);
        when(creates.getToSymbol()).thenReturn(createdType);
        when(creates.getResolution()).thenReturn(ResolutionStatus.RESOLVED);
        when(creates.getResolutionReason()).thenReturn("type resolved");
        when(creates.getConfidence()).thenReturn(new BigDecimal("0.9200"));
        when(creates.getOrigin()).thenReturn(OriginKind.MERGED);
        when(creates.getDerivationKind()).thenReturn(DerivationKind.DIRECT);
        when(creates.getCallSiteLine()).thenReturn(41);
        when(creates.getAttrs()).thenReturn(JsonNodeFactory.instance.objectNode()
                .put("resolution_basis", "symbol_solver")
                .put("confidence_band", "high")
                .put("default_visible", true));

        Evidence astExpression = mock(Evidence.class);
        when(astExpression.getEvidenceId()).thenReturn(201L);
        when(astExpression.getEvidenceType()).thenReturn(EvidenceType.AST);
        when(astExpression.getAttrs()).thenReturn(JsonNodeFactory.instance.objectNode().put("granularity", "expression"));
        when(astExpression.getSnippet()).thenReturn("new Order(request)");
        when(astExpression.getStartLine()).thenReturn(null);
        when(astExpression.getEndLine()).thenReturn(null);

        Evidence asmInstruction = mock(Evidence.class);
        when(asmInstruction.getEvidenceType()).thenReturn(EvidenceType.BYTECODE);
        when(asmInstruction.getAttrs()).thenReturn(JsonNodeFactory.instance.objectNode().put("granularity", "instruction"));
        when(asmInstruction.getSnippet()).thenReturn("NEW org/acme/Order");
        when(asmInstruction.getStartLine()).thenReturn(41);

        EdgeEvidence link1 = mock(EdgeEvidence.class);
        when(link1.getEdge()).thenReturn(creates);
        when(link1.getEvidence()).thenReturn(astExpression);
        EdgeEvidence link2 = mock(EdgeEvidence.class);
        when(link2.getEdge()).thenReturn(creates);
        when(link2.getEvidence()).thenReturn(asmInstruction);

        when(repoRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(ruleMiningSignalRepository.existsByRun_RunId(runId)).thenReturn(false);
        when(edgeRepository.findAllByRun_RunId(runId)).thenReturn(List.of(creates));
        when(evidenceRepository.findAllByRun_RunId(runId)).thenReturn(List.of(astExpression, asmInstruction));
        when(symbolRepository.findAllByRun_RunId(runId)).thenReturn(List.of());
        when(edgeEvidenceRepository.findAllByEdge_EdgeIdIn(Set.of(101L))).thenReturn(List.of(link1, link2));
        when(ruleMiningSignalRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.ingest(runId, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RuleMiningSignal>> captor = ArgumentCaptor.forClass(List.class);
        verify(ruleMiningSignalRepository).saveAll(captor.capture());
        List<RuleMiningSignal> saved = captor.getValue();

        assertThat(saved).hasSize(1);
        RuleMiningSignal signal = saved.get(0);
        assertThat(signal.getSignalType()).isEqualTo(RuleMiningSignalType.OBJECT_CREATION);
        assertThat(signal.getEvidence()).isSameAs(astExpression);
        assertThat(signal.getConfidenceHint()).isEqualByComparingTo("0.9200");
        assertThat(signal.getStartLine()).isEqualTo(41);
        assertThat(signal.getEndLine()).isEqualTo(41);
        assertThat(signal.getMeta().path("edgeType").asText()).isEqualTo("CREATES");
        assertThat(signal.getMeta().path("resolution").asText()).isEqualTo("RESOLVED");
        assertThat(signal.getMeta().path("resolutionReason").asText()).isEqualTo("type resolved");
        assertThat(signal.getMeta().path("origin").asText()).isEqualTo("MERGED");
        assertThat(signal.getMeta().path("derivationKind").asText()).isEqualTo("DIRECT");
        assertThat(signal.getMeta().path("callSiteLine").asInt()).isEqualTo(41);
        assertThat(signal.getMeta().path("defaultVisible").asBoolean()).isTrue();
        assertThat(signal.getMeta().path("attrs").path("confidence_band").asText()).isEqualTo("high");
    }
}
