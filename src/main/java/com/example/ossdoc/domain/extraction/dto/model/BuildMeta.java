package com.example.ossdoc.domain.extraction.dto.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

/**
 * 빌드 단계 결과 요약
 *
 * status 는 빌드 결과 상태(success/failed/partial/skipped/unknown),
 * mode 는 빌드 수행 형태(FULL / COMPILE_ONLY / SOURCE_ONLY / FAILED 등)를 담는다.
 *
 * build 도메인 enum이 확정되면 그 enum을 직접 재사용하거나
 * mapper에서 문자열로 변환해 넣는 것을 권장한다.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BuildMeta(
        @JsonProperty("status")
        String status,

        @JsonProperty("mode")
        String mode,

        @JsonProperty("tool")
        String tool,

        @JsonProperty("java_version_used")
        String javaVersionUsed,

        @JsonProperty("classpath_fingerprint")
        String classpathFingerprint,

        @JsonProperty("modules")
        List<String> modules,

        @JsonProperty("bytecode_roots")
        List<String> bytecodeRoots,

        @JsonProperty("classpath_entries")
        List<String> classpathEntries
) {
}