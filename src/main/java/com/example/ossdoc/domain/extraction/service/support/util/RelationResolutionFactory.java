package com.example.ossdoc.domain.extraction.service.support.util;

import com.example.ossdoc.domain.extraction.dto.model.RelationResolution;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;

/**
 * relation resolution 생성 보조 유틸
 */
public final class RelationResolutionFactory {

    private RelationResolutionFactory() {
    }

    public static RelationResolution resolved() {
        return RelationResolution.builder()
                .status(ResolutionStatus.RESOLVED)
                .build();
    }

    public static RelationResolution partial(String reason) {
        return RelationResolution.builder()
                .status(ResolutionStatus.PARTIAL)
                .reason(reason)
                .build();
    }

    public static RelationResolution unresolved(String reason) {
        return RelationResolution.builder()
                .status(ResolutionStatus.UNRESOLVED)
                .reason(reason)
                .build();
    }
}