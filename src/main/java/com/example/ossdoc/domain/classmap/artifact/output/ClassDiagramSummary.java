package com.example.ossdoc.domain.classmap.artifact.output;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ClassDiagramSummary {
    private String scope;
    private String subsystemId;
    private String subsystemName;
    private int totalTypeCount;
    private int candidateTypeCount;
    private int selectedNodeCount;
    private int selectedEdgeCount;
    private int hiddenByAccessCount;
    private int hiddenByPackageCount;
}
