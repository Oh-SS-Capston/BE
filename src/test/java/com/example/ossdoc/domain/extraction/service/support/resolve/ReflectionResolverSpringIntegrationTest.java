package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(SpringExtension.class)
@Import({
        ObservationRelationResolutionService.class,
        EventObservationResolver.class,
        SpiObservationResolver.class,
        ReflectionObservationResolver.class
})
class ReflectionResolverSpringIntegrationTest {

    @Autowired
    private ObservationRelationResolutionService resolutionService;

    @Test
    @DisplayName("Spring이 Reflection Resolver를 Event·SPI 다음 순서로 등록한다")
    void registersReflectionResolverAfterEventAndSpi() {
        ObservationFact reflection = ObservationFact.builder()
                .kind(ObservationKind.REFLECTION_SITE)
                .siteSymbol("method:sample.Client#load()")
                .attrs(Map.of(
                        "api_method", "forName",
                        "reflection_kind", "type",
                        "target_type", "sample.Target"
                ))
                .build();

        ObservationResolutionResult result = resolutionService.resolve(
                ExtractionAggregate.builder()
                        .observations(ObservationTable.builder()
                                .reflectionSites(List.of(reflection))
                                .build())
                        .build()
        );

        assertEquals(
                List.of(
                        EventObservationResolver.class,
                        SpiObservationResolver.class,
                        ReflectionObservationResolver.class
                ),
                resolutionService.resolvers().stream()
                        .map(Object::getClass)
                        .toList()
        );
        assertEquals(1, result.relations().size());
        assertEquals(RelationKind.REFLECTS_TYPE, result.relations().get(0).kind());
    }
}
