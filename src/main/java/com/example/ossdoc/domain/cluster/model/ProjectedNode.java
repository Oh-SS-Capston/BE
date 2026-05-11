package com.example.ossdoc.domain.cluster.model;

import lombok.Builder;
import lombok.Getter;
/* ProjectedNode.java : 군집화 알고리즘에 넣기 전, DB에 SymbolEntity를 군집화용으로 변환한 노드
* 즉, 군집화에 필요한 최소 정보만 들고 있는 가벼운 객체
* */
@Getter
@Builder
public class ProjectedNode {
    private String symbolId;
    private String qualifiedName; //rankingJson 생성에 필요
    private String simpleName;
    private String packageName; //subsystem 이름
    private String moduleId; //같은 모듈끼리 묶을때 필요
    private boolean publicApi; //entry symbol 판단
}
