package com.example.ossdoc.domain.extraction.dto.request;

import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.util.List;

/**
 * facts 추출 요청 입력
 */
@Builder
public record FactsExtractRequest(
        @NotBlank
        String runId,

        /**
         * null이면 service가 build 정보 등을 보고 자동 결정
         */
        ExtractionMode mode,

        /**
         * observation 추출 포함 여부
         */
        boolean includeObservations,

        /**
         * test source / test bytecode 포함 여부
         */
        boolean includeTests,

        /**
         * 일부 파일 실패 시 즉시 중단할지 여부
         */
        boolean failFast,

        /**
         * 특정 모듈만 대상으로 제한하고 싶을 때(옵션)
         * 예: ["root", "module-a", "module-b"]
         */
        List<String> targetModules,

        /**
         * 특정 경로만 스캔하고 싶을 때(옵션)
         * 예: ["src/main/java", "module-a/src/main/java"]
         */
        List<String> includeGlobs,

        /**
         * 제외할 경로 패턴(옵션)
         * 예: ["##/generated/##", "##/build/##"]
         */
        List<String> excludeGlobs
) {
    public FactsExtractRequest {
        targetModules = targetModules == null ? List.of() : List.copyOf(targetModules);
        includeGlobs = includeGlobs == null ? List.of() : List.copyOf(includeGlobs);
        excludeGlobs = excludeGlobs == null ? List.of() : List.copyOf(excludeGlobs);
    }
}
