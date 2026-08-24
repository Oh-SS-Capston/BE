package com.example.ossdoc.domain.publicapi.model;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder(toBuilder = true)
public class EntryPointCandidate {

    @Getter
    @Builder(toBuilder = true)
    public static class EvidenceCompleteness {
        private boolean sourceAvailable;
        private boolean javadocAvailable;
        private boolean annotationsAvailable;
        /** BUILD_MANIFEST에서 읽은 모듈 빌드 모드. FULL / COMPILE_ONLY / SOURCE_ONLY / FAILED / UNKNOWN */
        private String buildMode;
        /** true면 confidence가 HIGH → MED로 캡됐음 (javadoc·annotation 모두 미추출). */
        private boolean degraded;
    }

    @Getter
    @Builder
    public static class EntryMethodInfo {
        private String symbolId;
        private String simpleName;
        /** HTTP_ENDPOINT | STATIC_FACTORY | PUBLIC_STATIC | PUBLIC_INSTANCE */
        private String reason;
        /** HANDLES_ENDPOINT relation이 존재하는 경우 method 단위 HTTP 의미 정보를 보존한다. */
        private List<HttpEndpointInfo> httpEndpoints;
    }

    @Getter
    @Builder
    public static class HttpEndpointInfo {
        private String httpMethod;
        private String path;
        private Double confidence;
        private String resolution;
        private String resolutionReason;
        private String origin;
        private String derivationKind;
        private Boolean defaultVisible;
    }
    private String symbolId;
    private String qualifiedName;
    /**
     * owner_type_fqn:
     * - 현재 entry point는 TYPE 단위라 기본적으로 자기 자신 FQN을 owner로 둔다.
     * - 향후 method 단위 entry 확장 시 owner type 앵커로 그대로 재사용한다.
     */
    private String ownerTypeFqn;
    private String simpleName;
    private String typeKind;           // "class" | "interface" | "enum" | "record"
    /**
     * LLM 파일 트리/코드 위치 앵커용 메타데이터.
     * - sourceFile: 소스 파일 상대 경로
     * - startLine/endLine: 심볼 선언 라인 범위
     */
    private String sourceFile;
    private Integer startLine;
    private Integer endLine;
    private String subsystemId;
    private String subsystemLabel;
    private String role;               // "PRIMARY" | "SECONDARY"
    private String confidence;         // "HIGH" | "MED" | "LOW"
    private List<String> signals;      // fired signal names
    private int score;
    /** #2: TYPE 진입점의 실제 진입 메서드 목록. BFS 트레이스 시드로 사용. */
    private List<EntryMethodInfo> entryMethods;
    /** #6: 근거(javadoc·annotation·source) 추출 완전성 메타. confidence 강등 여부 포함. */
    private EvidenceCompleteness evidenceCompleteness;
}
