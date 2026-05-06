// 역할: 프런트 클래스 맵 렌더링용 JSON 루트 구조를 정의한다.
package com.example.ossdoc.domain.classmap.artifact.output;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

@Getter
@Builder
public class ClassDiagramJson {
    private String schemaVersion;
    private String runId;
    private OffsetDateTime generatedAt;
    private ClassDiagramSummary summary;
    private ClassDiagramDisplayPolicy displayPolicy;
    private List<ClassDiagramNode> nodes;
    private List<ClassDiagramEdge> edges;
}
