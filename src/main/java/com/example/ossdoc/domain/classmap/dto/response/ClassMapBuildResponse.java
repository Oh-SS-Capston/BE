package com.example.ossdoc.domain.classmap.dto.response;

import com.example.ossdoc.domain.classmap.enums.ClassMapScope;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ClassMapBuildResponse {
    private String runId;
    private ClassMapScope scope;
    private String subsystemId;
    private String subsystemName;
    private int nodeCount;
    private int edgeCount;
    private Long classDiagramArtifactId;
}
