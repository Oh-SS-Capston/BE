package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolTable;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import com.example.ossdoc.domain.extraction.enums.SymbolKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiObservationResolverTest {

    private final DiObservationResolver resolver =
            new DiObservationResolver();

    @Test
    @DisplayName("주입 타입을 구현하는 단일 provider를 찾아 소유 타입의 INJECTS 관계를 생성한다")
    void resolvesSingleProviderByExposedInterfaceType() {
        String fieldSymbol = "field:sample.UserController#userService";
        String controllerSymbol = "type:sample.UserController";
        String providerSymbol = "type:sample.UserServiceImpl";

        ObservationFact injection = injection(
                fieldSymbol,
                "sample.UserService",
                Map.of(),
                List.of("ev-injection"),
                FactOriginKind.AST
        );

        ObservationFact provider = provider(
                providerSymbol,
                "sample.UserServiceImpl",
                List.of("userServiceImpl"),
                List.of(),
                false,
                List.of("ev-provider"),
                FactOriginKind.BYTECODE
        );

        SymbolFact controllerField = SymbolFact.builder()
                .symbol(fieldSymbol)
                .kind(SymbolKind.FIELD)
                .ownerSymbol(controllerSymbol)
                .build();

        SymbolFact providerType = SymbolFact.builder()
                .symbol(providerSymbol)
                .kind(SymbolKind.TYPE)
                .qualifiedName("sample.UserServiceImpl")
                .interfaceTypeRefs(List.of(typeRef("sample.UserService")))
                .build();

        ObservationResolutionResult result = resolver.resolve(
                contextOf(
                        List.of(injection),
                        List.of(provider),
                        List.of(controllerField, providerType)
                )
        );

        assertTrue(result.warnings().isEmpty());
        assertEquals(1, result.relations().size());

        RelationFact relation = result.relations().get(0);
        assertEquals(RelationKind.INJECTS, relation.kind());
        assertEquals(controllerSymbol, relation.srcSymbol());
        assertEquals(providerSymbol, relation.dstSymbol());
        assertNull(relation.dstRawRef());
        assertEquals(
                ResolutionStatus.RESOLVED,
                relation.resolution().status()
        );
        assertEquals(DerivationKind.DERIVED, relation.derivation());
        assertEquals(
                FactOriginKind.AST_AND_BYTECODE,
                relation.origin()
        );
        assertEquals(
                List.of("ev-injection", "ev-provider"),
                relation.evidenceIds()
        );
        assertEquals(0.975, relation.confidenceHint(), 0.0001);
        assertEquals(
                "exact_type",
                relation.attrs().get("match_strategy")
        );
        assertEquals(
                fieldSymbol,
                relation.attrs().get("injection_site_symbol")
        );
        assertEquals("exact_symbol", relation.attrs().get("resolution_basis"));
        assertEquals("high", relation.attrs().get("confidence_band"));
        assertEquals(true, relation.attrs().get("default_visible"));
    }

    @Test
    @DisplayName("동일 타입 provider가 여러 개이면 qualifier와 Bean 이름으로 하나를 선택한다")
    void resolvesQualifiedProvider() {
        String fieldSymbol = "field:sample.PaymentController#gateway";

        ObservationFact injection = injection(
                fieldSymbol,
                "sample.PaymentGateway",
                Map.of("qualifier", "backupGateway"),
                List.of("ev-injection"),
                FactOriginKind.AST
        );

        ObservationFact primary = provider(
                "method:sample.PaymentConfig#primaryGateway()",
                "sample.PaymentGateway",
                List.of("primaryGateway"),
                List.of("primary"),
                true,
                List.of("ev-primary"),
                FactOriginKind.AST
        );

        ObservationFact backup = provider(
                "method:sample.PaymentConfig#backupGateway()",
                "sample.PaymentGateway",
                List.of("backupGateway"),
                List.of("backup"),
                false,
                List.of("ev-backup"),
                FactOriginKind.AST
        );

        SymbolFact field = SymbolFact.builder()
                .symbol(fieldSymbol)
                .kind(SymbolKind.FIELD)
                .ownerSymbol("type:sample.PaymentController")
                .build();

        ObservationResolutionResult result = resolver.resolve(
                contextOf(
                        List.of(injection),
                        List.of(primary, backup),
                        List.of(field)
                )
        );

        assertEquals(1, result.relations().size());
        RelationFact relation = result.relations().get(0);

        assertEquals("bean:backupGateway", relation.dstRawRef());
        assertNull(relation.dstSymbol());
        assertEquals(
                "method:sample.PaymentConfig#backupGateway()",
                relation.attrs().get("provider_symbol")
        );
        assertEquals(
                "qualifier",
                relation.attrs().get("match_strategy")
        );
        assertEquals(0.96, relation.confidenceHint(), 0.0001);
        assertEquals("exact_reference", relation.attrs().get("resolution_basis"));
        assertEquals("high", relation.attrs().get("confidence_band"));
        assertEquals(true, relation.attrs().get("default_visible"));
    }

    @Test
    @DisplayName("동일 타입 후보 중 Primary provider가 하나면 해당 후보를 선택한다")
    void resolvesUniquePrimaryProvider() {
        String ctorSymbol = "ctor:sample.OrderService#<init>(sample.PaymentGateway)";

        ObservationFact injection = injection(
                ctorSymbol,
                "sample.PaymentGateway",
                Map.of("parameter", "gateway"),
                List.of("ev-injection"),
                FactOriginKind.AST
        );

        ObservationFact first = provider(
                "method:sample.PaymentConfig#firstGateway()",
                "sample.PaymentGateway",
                List.of("firstGateway"),
                List.of(),
                false,
                List.of("ev-first"),
                FactOriginKind.AST
        );
        ObservationFact preferred = provider(
                "method:sample.PaymentConfig#preferredGateway()",
                "sample.PaymentGateway",
                List.of("preferredGateway"),
                List.of(),
                true,
                List.of("ev-preferred"),
                FactOriginKind.AST
        );

        SymbolFact constructor = SymbolFact.builder()
                .symbol(ctorSymbol)
                .kind(SymbolKind.CONSTRUCTOR)
                .ownerSymbol("type:sample.OrderService")
                .build();

        ObservationResolutionResult result = resolver.resolve(
                contextOf(
                        List.of(injection),
                        List.of(first, preferred),
                        List.of(constructor)
                )
        );

        assertEquals(1, result.relations().size());
        RelationFact relation = result.relations().get(0);
        assertEquals("bean:preferredGateway", relation.dstRawRef());
        assertEquals(
                "primary",
                relation.attrs().get("match_strategy")
        );
        assertEquals(0.945, relation.confidenceHint(), 0.0001);
        assertEquals("exact_reference", relation.attrs().get("resolution_basis"));
        assertEquals("high", relation.attrs().get("confidence_band"));
        assertEquals(true, relation.attrs().get("default_visible"));
    }

    @Test
    @DisplayName("후보를 하나로 확정할 수 없으면 임의 선택 없이 타입 대상 PARTIAL 관계를 생성한다")
    void keepsAmbiguousProvidersAsPartialRelation() {
        String fieldSymbol = "field:sample.PaymentController#gateway";

        ObservationFact injection = injection(
                fieldSymbol,
                "sample.PaymentGateway",
                Map.of(),
                List.of("ev-injection"),
                FactOriginKind.AST
        );

        ObservationFact first = provider(
                "method:sample.PaymentConfig#firstGateway()",
                "sample.PaymentGateway",
                List.of("firstGateway"),
                List.of(),
                false,
                List.of("ev-first"),
                FactOriginKind.AST
        );
        ObservationFact second = provider(
                "method:sample.PaymentConfig#secondGateway()",
                "sample.PaymentGateway",
                List.of("secondGateway"),
                List.of(),
                false,
                List.of("ev-second"),
                FactOriginKind.AST
        );

        SymbolFact field = SymbolFact.builder()
                .symbol(fieldSymbol)
                .kind(SymbolKind.FIELD)
                .ownerSymbol("type:sample.PaymentController")
                .build();

        ObservationResolutionResult result = resolver.resolve(
                contextOf(
                        List.of(injection),
                        List.of(first, second),
                        List.of(field)
                )
        );

        assertEquals(1, result.relations().size());
        RelationFact relation = result.relations().get(0);

        assertNull(relation.dstSymbol());
        assertEquals("type:sample.PaymentGateway", relation.dstRawRef());
        assertEquals(
                ResolutionStatus.PARTIAL,
                relation.resolution().status()
        );
        assertEquals(
                "ambiguous",
                relation.attrs().get("match_strategy")
        );
        assertEquals(2, relation.attrs().get("candidate_count"));
        assertEquals(0.6, relation.confidenceHint(), 0.0001);
        assertEquals(
                "ambiguous_candidates",
                relation.attrs().get("resolution_basis")
        );
        assertEquals("medium", relation.attrs().get("confidence_band"));
        assertEquals(false, relation.attrs().get("default_visible"));
    }

    @Test
    @DisplayName("파라미터명으로 선택한 provider는 추론 기반 PARTIAL 관계로 분류한다")
    void classifiesParameterNameMatchAsInferredPartial() {
        String ctorSymbol = "ctor:sample.ReportService#<init>(sample.ReportStore)";

        ObservationFact injection = injection(
                ctorSymbol,
                "sample.ReportStore",
                Map.of("parameter", "archiveStore"),
                List.of("ev-injection"),
                FactOriginKind.AST
        );

        ObservationFact current = provider(
                "method:sample.ReportConfig#currentStore()",
                "sample.ReportStore",
                List.of("currentStore"),
                List.of(),
                false,
                List.of("ev-current"),
                FactOriginKind.AST
        );
        ObservationFact archive = provider(
                "method:sample.ReportConfig#archiveStore()",
                "sample.ReportStore",
                List.of("archiveStore"),
                List.of(),
                false,
                List.of("ev-archive"),
                FactOriginKind.AST
        );

        SymbolFact constructor = SymbolFact.builder()
                .symbol(ctorSymbol)
                .kind(SymbolKind.CONSTRUCTOR)
                .ownerSymbol("type:sample.ReportService")
                .build();

        ObservationResolutionResult result = resolver.resolve(
                contextOf(
                        List.of(injection),
                        List.of(current, archive),
                        List.of(constructor)
                )
        );

        assertEquals(1, result.relations().size());
        RelationFact relation = result.relations().get(0);

        assertEquals("bean:archiveStore", relation.dstRawRef());
        assertEquals(
                ResolutionStatus.PARTIAL,
                relation.resolution().status()
        );
        assertEquals("parameter_name", relation.attrs().get("match_strategy"));
        assertEquals(
                "inferred_reference",
                relation.attrs().get("resolution_basis")
        );
        assertEquals(0.57, relation.confidenceHint(), 0.0001);
        assertEquals("medium", relation.attrs().get("confidence_band"));
        assertEquals(false, relation.attrs().get("default_visible"));
    }

    @Test
    @DisplayName("siteSymbol이 없는 주입 observation은 경고 후 건너뛴다")
    void skipsInjectionWithoutSiteSymbol() {
        ObservationFact injection = ObservationFact.builder()
                .kind(ObservationKind.DI_INJECTION_SITE)
                .targetTypeRef(typeRef("sample.UserService"))
                .build();

        ObservationResolutionResult result = resolver.resolve(
                contextOf(
                        List.of(injection),
                        List.of(),
                        List.of()
                )
        );

        assertTrue(result.relations().isEmpty());
        assertFalse(result.warnings().isEmpty());
        assertTrue(result.warnings().get(0).contains("siteSymbol"));
    }

    private ObservationFact injection(
            String siteSymbol,
            String targetType,
            Map<String, Object> attrs,
            List<String> evidenceIds,
            FactOriginKind origin
    ) {
        return ObservationFact.builder()
                .kind(ObservationKind.DI_INJECTION_SITE)
                .siteSymbol(siteSymbol)
                .targetTypeRef(typeRef(targetType))
                .attrs(attrs)
                .evidenceIds(evidenceIds)
                .origin(origin)
                .confidenceHint(0.9)
                .build();
    }

    private ObservationFact provider(
            String siteSymbol,
            String providedType,
            List<String> beanNames,
            List<String> qualifiers,
            boolean primary,
            List<String> evidenceIds,
            FactOriginKind origin
    ) {
        return ObservationFact.builder()
                .kind(ObservationKind.DI_PROVIDER)
                .siteSymbol(siteSymbol)
                .targetTypeRef(typeRef(providedType))
                .evidenceIds(evidenceIds)
                .origin(origin)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "provided_type", providedType,
                        "bean_names", beanNames,
                        "qualifiers", qualifiers,
                        "primary", primary,
                        "provider_kind", siteSymbol.startsWith("method:")
                                ? "bean_method"
                                : "service_type"
                ))
                .build();
    }

    private TypeRef typeRef(String raw) {
        return TypeRef.builder()
                .raw(raw)
                .arrayDim(0)
                .primitive(false)
                .unresolved(false)
                .build();
    }

    private ObservationResolutionContext contextOf(
            List<ObservationFact> injectionSites,
            List<ObservationFact> providers,
            List<SymbolFact> symbols
    ) {
        SymbolTable symbolTable = SymbolTable.builder()
                .types(symbols.stream()
                        .filter(symbol -> symbol.kind() == SymbolKind.TYPE)
                        .toList())
                .constructors(symbols.stream()
                        .filter(symbol -> symbol.kind() == SymbolKind.CONSTRUCTOR)
                        .toList())
                .methods(symbols.stream()
                        .filter(symbol -> symbol.kind() == SymbolKind.METHOD)
                        .toList())
                .fields(symbols.stream()
                        .filter(symbol -> symbol.kind() == SymbolKind.FIELD)
                        .toList())
                .build();

        ExtractionAggregate aggregate = ExtractionAggregate.builder()
                .symbols(symbolTable)
                .observations(ObservationTable.builder()
                        .diInjectionSites(injectionSites)
                        .diProviders(providers)
                        .build())
                .build();

        return ObservationResolutionContext.from(aggregate);
    }
}
