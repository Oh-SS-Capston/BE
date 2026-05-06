// 역할: 클래스 맵에 표시할 관계 엣지 정보를 담는다.
package com.example.ossdoc.domain.classmap.artifact.output;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ClassDiagramEdge {
    private String sourceSymbolId;
    private String targetSymbolId;
    private String edgeType;
    private String label;
    private int evidenceCount;
    private Double confidence;
    private String resolution;
    private List<String> badges;
    private ClassDiagramEvidence evidence;
}
