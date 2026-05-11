package com.example.ossdoc.domain.publicapi.model;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ExtensionPointCandidate {
    private String symbolId;
    private String qualifiedName;
    private String simpleName;
    private String typeKind;               // "interface" | "abstract"
    private String subsystemId;
    private String subsystemLabel;
    private int linkedImplementorCount;
    private int linkedExtenderCount;
    private boolean readmeMentioned;       // README evidence 미구현 → 항상 false
    private String confidence;             // "HIGH" | "MEDIUM" | "LOW"
}
