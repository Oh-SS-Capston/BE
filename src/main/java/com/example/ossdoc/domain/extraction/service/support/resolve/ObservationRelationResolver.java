package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.enums.ObservationKind;

import java.util.Set;

/**
 * ObservationFact를 의미 RelationFact로 승격하는 resolver 계약.
 */
public interface ObservationRelationResolver {

    /**
     * 이 resolver가 처리하는 observation 종류.
     */
    Set<ObservationKind> supportedKinds();

    /**
     * 동일 우선순위에서는 클래스 이름 순으로 실행된다.
     */
    default int order() {
        return 0;
    }

    default boolean supports(ObservationKind kind) {
        return kind != null
                && supportedKinds() != null
                && supportedKinds().contains(kind);
    }

    ObservationResolutionResult resolve(
            ObservationResolutionContext context
    );
}
