package com.example.ossdoc.domain.extraction.service.composer;

import com.example.ossdoc.domain.extraction.dto.context.FactsCompositionContext;
import com.example.ossdoc.domain.extraction.dto.model.BuildMeta;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.FactsDocument;
import com.example.ossdoc.domain.extraction.dto.model.JobMeta;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolTable;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import com.example.ossdoc.domain.extraction.enums.SymbolKind;
import com.example.ossdoc.domain.extraction.service.support.resolve.DiObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.ObservationRelationResolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiRelationCompositionTest {

    @Test
    @DisplayName("Composer가 DI 주입 지점과 provider를 INJECTS로 승격해 relations와 stats에 반영한다")
    void composesInjectsRelation() {
        String controllerSymbol = "type:sample.UserController";
        String fieldSymbol = "field:sample.UserController#userService";
        String serviceSymbol = "type:sample.UserService";
        String injectionEvidenceId = "ev-di-injection";
        String providerEvidenceId = "ev-di-provider";

        EvidenceFact injectionEvidence = EvidenceFact.builder()
                .id(injectionEvidenceId)
                .type(EvidenceType.AST)
                .path("src/main/java/sample/UserController.java")
                .symbol(fieldSymbol)
                .snippet("@Autowired private UserService userService;")
                .build();

        EvidenceFact providerEvidence = EvidenceFact.builder()
                .id(providerEvidenceId)
                .type(EvidenceType.AST)
                .path("src/main/java/sample/UserService.java")
                .symbol(serviceSymbol)
                .snippet("@Service class UserService")
                .build();

        TypeRef serviceType = typeRef("sample.UserService");

        ObservationFact injection = ObservationFact.builder()
                .kind(ObservationKind.DI_INJECTION_SITE)
                .siteSymbol(fieldSymbol)
                .targetTypeRef(serviceType)
                .evidenceIds(List.of(injectionEvidenceId))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "annotations",
                        List.of("org.springframework.beans.factory.annotation.Autowired")
                ))
                .build();

        ObservationFact provider = ObservationFact.builder()
                .kind(ObservationKind.DI_PROVIDER)
                .siteSymbol(serviceSymbol)
                .targetTypeRef(serviceType)
                .evidenceIds(List.of(providerEvidenceId))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "provider_kind", "service_type",
                        "provided_type", "sample.UserService",
                        "bean_names", List.of("userService"),
                        "qualifiers", List.of(),
                        "primary", false
                ))
                .build();

        SymbolFact controllerType = SymbolFact.builder()
                .symbol(controllerSymbol)
                .kind(SymbolKind.TYPE)
                .qualifiedName("sample.UserController")
                .build();

        SymbolFact field = SymbolFact.builder()
                .symbol(fieldSymbol)
                .kind(SymbolKind.FIELD)
                .ownerSymbol(controllerSymbol)
                .build();

        SymbolFact service = SymbolFact.builder()
                .symbol(serviceSymbol)
                .kind(SymbolKind.TYPE)
                .qualifiedName("sample.UserService")
                .build();

        ExtractionAggregate aggregate = ExtractionAggregate.builder()
                .evidence(Map.of(
                        injectionEvidenceId,
                        injectionEvidence,
                        providerEvidenceId,
                        providerEvidence
                ))
                .symbols(SymbolTable.builder()
                        .types(List.of(controllerType, service))
                        .constructors(List.of())
                        .methods(List.of())
                        .fields(List.of(field))
                        .build())
                .observations(ObservationTable.builder()
                        .diInjectionSites(List.of(injection))
                        .diProviders(List.of(provider))
                        .build())
                .stats(StatsMeta.builder().build())
                .warnings(List.of())
                .build();

        ObservationRelationResolutionService resolutionService =
                new ObservationRelationResolutionService(
                        List.of(new DiObservationResolver())
                );

        DefaultFactsComposer composer = new DefaultFactsComposer(
                new FactsSectionFactory(),
                new FactsStatsCalculator(),
                resolutionService
        );

        OffsetDateTime now = OffsetDateTime.now();
        FactsCompositionContext context = new FactsCompositionContext(
                "test-schema",
                JobMeta.builder().build(),
                BuildMeta.builder().build(),
                ExtractionMode.AST_ONLY,
                now,
                now,
                List.of(),
                true,
                aggregate
        );

        FactsDocument document = composer.compose(context);

        assertNotNull(document);
        assertNotNull(document.relations());
        assertNotNull(document.relations().injects());
        assertEquals(1, document.relations().injects().size());

        var relation = document.relations().injects().get(0);
        assertEquals(RelationKind.INJECTS, relation.kind());
        assertEquals(controllerSymbol, relation.srcSymbol());
        assertEquals(serviceSymbol, relation.dstSymbol());
        assertEquals(
                ResolutionStatus.RESOLVED,
                relation.resolution().status()
        );
        assertEquals(
                List.of(injectionEvidenceId, providerEvidenceId),
                relation.evidenceIds()
        );

        assertEquals(1, document.stats().relations());
        assertEquals(2, document.stats().observations());
        assertTrue(document.extraction().warnings().isEmpty());

        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules();
        JsonNode json = objectMapper.valueToTree(document);

        JsonNode injects = json.path("relations")
                .path("injects");

        assertTrue(injects.isArray());
        assertEquals(1, injects.size());
        assertEquals(
                controllerSymbol,
                injects.get(0).path("src_symbol").asText()
        );
        assertEquals(
                serviceSymbol,
                injects.get(0).path("dst_symbol").asText()
        );
    }

    private TypeRef typeRef(String raw) {
        return TypeRef.builder()
                .raw(raw)
                .arrayDim(0)
                .primitive(false)
                .unresolved(false)
                .build();
    }
}
