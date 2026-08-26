// 역할: 엣지에 연결된 근거의 개수/타입/샘플을 요약한다.
package com.example.ossdoc.domain.classmap.artifact.output;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ClassDiagramEvidence {
    private int count;
    private List<String> evidenceTypes;
    private List<ClassDiagramEvidenceSample> samples;
}
