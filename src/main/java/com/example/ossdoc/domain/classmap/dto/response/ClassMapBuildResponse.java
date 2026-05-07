// 역할: 클래스 맵 결과 요약과 산출물 artifact id를 반환한다.
package com.example.ossdoc.domain.classmap.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ClassMapBuildResponse {
    private String runId;
    private int nodeCount;
    private int edgeCount;
    private Long classDiagramArtifactId;
}
