// 역할: 클래스 맵 생성 결과의 핵심 통계를 담는다.
package com.example.ossdoc.domain.classmap.artifact.output;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ClassDiagramSummary {
    private int totalTypeCount;
    private int candidateTypeCount;
    private int selectedNodeCount;
    private int selectedEdgeCount;
    private int hiddenByAccessCount;
    private int hiddenByPackageCount;
}
