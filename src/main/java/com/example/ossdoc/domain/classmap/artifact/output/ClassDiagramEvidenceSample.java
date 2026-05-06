// 역할: 라인 정보가 포함된 근거 스니펫 샘플을 전달한다.
package com.example.ossdoc.domain.classmap.artifact.output;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ClassDiagramEvidenceSample {
    private Integer startLine;
    private Integer endLine;
    private String snippet;
}
