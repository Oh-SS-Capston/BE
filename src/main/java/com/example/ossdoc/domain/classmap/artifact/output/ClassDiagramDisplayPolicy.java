// 역할: 클래스 맵에 적용된 필터/표시 정책을 명시한다.
package com.example.ossdoc.domain.classmap.artifact.output;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ClassDiagramDisplayPolicy {
    private List<String> hiddenPackageTokens;
    private List<String> includedEdgeTypes;
    private List<String> hiddenEdgeTypes;
}
