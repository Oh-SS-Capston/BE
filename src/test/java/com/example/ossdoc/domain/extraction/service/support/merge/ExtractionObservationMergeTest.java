package com.example.ossdoc.domain.extraction.service.support.merge;

import com.example.ossdoc.domain.extraction.dto.context.ExtractionSink;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.RootMergeResult;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractionObservationMergeTest {

    @Test
    @DisplayName("ExtractionSink — 같은 생성자의 서로 다른 주입 파라미터를 모두 유지")
    void extractionSink_keepsDifferentInjectionTargetTypes() {
        ExtractionSink sink = new ExtractionSink();

        sink.addObservation(injectionSite(
                "constructor:sample.Controller(sample.UserService,sample.AuditService)",
                "sample.UserService",
                "userService"
        ));

        sink.addObservation(injectionSite(
                "constructor:sample.Controller(sample.UserService,sample.AuditService)",
                "sample.AuditService",
                "auditService"
        ));

        List<ObservationFact> injectionSites = sink
                .toExtractedFacts()
                .observations()
                .diInjectionSites();

        assertEquals(
                2,
                injectionSites.size(),
                "targetTypeRef가 다른 생성자 파라미터 observation은 중복 제거되면 안 됨"
        );

        assertTrue(
                injectionSites.stream().anyMatch(observation ->
                        "sample.UserService".equals(observation.targetTypeRef().raw())
                )
        );

        assertTrue(
                injectionSites.stream().anyMatch(observation ->
                        "sample.AuditService".equals(observation.targetTypeRef().raw())
                )
        );
    }

    @Test
    @DisplayName("ExtractionMergeSupport — AST와 ASM의 동일 Provider observation 병합")
    void extractionMergeSupport_mergesAstAndBytecodeObservation() {
        String providerSymbol =
                "method:sample.AppConfig#objectMapper()";

        ExtractionSink astSink = new ExtractionSink();
        astSink.addObservation(ObservationFact.builder()
                .kind(ObservationKind.DI_PROVIDER)
                .siteSymbol(providerSymbol)
                .targetTypeRef(TypeRef.builder()
                        .raw("com.fasterxml.jackson.databind.ObjectMapper")
                        .arrayDim(0)
                        .primitive(false)
                        .unresolved(false)
                        .sourceText("ObjectMapper")
                        .build())
                .note("provider method from AST")
                .evidenceIds(List.of("evidence-ast"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.7)
                .attrs(Map.of("ast_attribute", true))
                .build());

        ExtractionSink bytecodeSink = new ExtractionSink();
        bytecodeSink.addObservation(ObservationFact.builder()
                .kind(ObservationKind.DI_PROVIDER)
                .siteSymbol(providerSymbol)
                .targetTypeRef(TypeRef.builder()
                        .raw("com.fasterxml.jackson.databind.ObjectMapper")
                        .arrayDim(0)
                        .primitive(false)
                        .unresolved(false)
                        .build())
                .note("provider method from bytecode")
                .evidenceIds(List.of("evidence-bytecode"))
                .origin(FactOriginKind.BYTECODE)
                .confidenceHint(0.9)
                .attrs(Map.of("bytecode_attribute", true))
                .build());

        RootMergeResult result = new ExtractionMergeSupport().mergeRoot(
                "sample",
                "src/main/java",
                List.of(
                        astSink.toChunkResult(null),
                        bytecodeSink.toChunkResult(null)
                )
        );

        assertEquals(
                1,
                result.observations().size(),
                "동일 Provider가 AST와 ASM에서 발견되어도 observation은 한 건이어야 함"
        );

        ObservationFact merged = result.observations().get(0);

        assertEquals(
                FactOriginKind.AST_AND_BYTECODE,
                merged.origin()
        );

        assertEquals(
                2,
                merged.evidenceIds().size()
        );

        assertTrue(merged.evidenceIds().contains("evidence-ast"));
        assertTrue(merged.evidenceIds().contains("evidence-bytecode"));

        assertEquals(0.9, merged.confidenceHint());
        assertTrue(Boolean.TRUE.equals(merged.attrs().get("ast_attribute")));
        assertTrue(Boolean.TRUE.equals(merged.attrs().get("bytecode_attribute")));

        assertFalse(
                Boolean.TRUE.equals(merged.targetTypeRef().unresolved()),
                "둘 중 하나라도 resolved 타입이면 병합 결과도 resolved여야 함"
        );
    }

    private ObservationFact injectionSite(
            String constructorSymbol,
            String targetType,
            String parameterName
    ) {
        return ObservationFact.builder()
                .kind(ObservationKind.DI_INJECTION_SITE)
                .siteSymbol(constructorSymbol)
                .targetTypeRef(TypeRef.builder()
                        .raw(targetType)
                        .arrayDim(0)
                        .primitive(false)
                        .unresolved(false)
                        .sourceText(targetType.substring(
                                targetType.lastIndexOf('.') + 1
                        ))
                        .build())
                .note("constructor injection parameter")
                .evidenceIds(List.of("evidence-" + parameterName))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.7)
                .attrs(Map.of("parameter", parameterName))
                .build();
    }
}
