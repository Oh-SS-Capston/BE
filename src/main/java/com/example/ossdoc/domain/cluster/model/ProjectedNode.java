package com.example.ossdoc.domain.cluster.model;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/*
 * ProjectedNode:
 * - 그래프/랭킹 계산에 필요한 SymbolEntity 핵심 정보를 담는 경량 투영 객체.
 * - rankings.json 보강을 위해 symbol kind, owner, source span 메타를 함께 운반한다.
 */
@Getter
@Builder
public class ProjectedNode {
    private String symbolId;
    private String qualifiedName; // rankingJson 생성 시 표시 이름
    private String simpleName;
    private String packageName; // subsystem 라벨링 단위
    private String moduleId; // 동일 모듈 묶음 계산 시 사용
    private boolean publicApi; // entry symbol 가중치 판단

    // rankings.json 보강용 메타데이터
    private String symbolKind;
    private String ownerSymbol;
    private String sourceFile;
    private Integer sourceStartLine;
    private Integer sourceEndLine;

    // evidence 커버리지 — evidenceScore 계산에 사용
    private int evidenceCount;

    // refactplan.md 4순위: Javadoc 토큰 (HTML·지시어 제거 + 영어 불용어 필터링 후 소문자 토큰 리스트).
    // GraphProjectionService.tokenizeDocComment()에서 투영 시 생성. doc 없으면 빈 리스트.
    private List<String> docTokens;
}
