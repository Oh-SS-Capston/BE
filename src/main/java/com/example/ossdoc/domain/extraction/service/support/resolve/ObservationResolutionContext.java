package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationTable;
import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.dto.model.SymbolTable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Observation을 의미 관계로 해석할 때 사용하는 읽기 전용 컨텍스트.
 *
 * <p>Resolver가 extraction aggregate 전체 구조에 직접 결합되지 않도록
 * 필요한 section 접근을 한 곳에 모은다.</p>
 */
public record ObservationResolutionContext(
        ExtractionAggregate aggregate
) {

    public ObservationResolutionContext {
        Objects.requireNonNull(aggregate, "aggregate must not be null");
    }

    public static ObservationResolutionContext from(
            ExtractionAggregate aggregate
    ) {
        return new ObservationResolutionContext(aggregate);
    }

    public Map<String, EvidenceFact> evidence() {
        Map<String, EvidenceFact> evidence = aggregate.evidence();
        return evidence == null ? Map.of() : evidence;
    }

    public SymbolTable symbols() {
        return aggregate.symbols();
    }

    public RelationTable relations() {
        return aggregate.relations();
    }

    public ObservationTable observations() {
        return aggregate.observations();
    }

    public StatsMeta stats() {
        return aggregate.stats();
    }

    public List<String> warnings() {
        List<String> warnings = aggregate.warnings();
        return warnings == null ? List.of() : warnings;
    }
}
