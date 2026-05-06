// 역할: 클래스 맵에 표시할 타입 노드 정보를 담는다.
package com.example.ossdoc.domain.classmap.artifact.output;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ClassDiagramNode {
    private String symbolId;
    private String label;
    private String qualifiedName;
    private String packageName;
    private String moduleId;
    private String access;
    private Double score;
    private List<String> badges;
    private List<String> reasons;
}
