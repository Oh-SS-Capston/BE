package com.example.ossdoc.domain.publicapi.model;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ExtensionPointCandidate {
    private String symbolId;
    private String qualifiedName;
    /**
     * owner_type_fqn:
     * - extension point는 TYPE 중심 결과이므로 기본 owner는 자기 자신 FQN.
     * - 파일 트리/메서드 설명 조합 시 type 앵커로 사용한다.
     */
    private String ownerTypeFqn;
    private String simpleName;
    private String typeKind;               // "interface" | "abstract"
    /**
     * LLM 출력 품질 보강을 위한 코드 위치 메타.
     */
    private String sourceFile;
    private Integer startLine;
    private Integer endLine;
    private String subsystemId;
    private String subsystemLabel;
    private int linkedImplementorCount;
    private int linkedExtenderCount;
    private String confidence;             // "HIGH" | "MED" | "LOW"
    private List<String> signals;          // fired signal names
    private int score;
}
