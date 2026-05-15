package com.example.ossdoc.domain.publicapi.model;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class EntryPointCandidate {
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
}
