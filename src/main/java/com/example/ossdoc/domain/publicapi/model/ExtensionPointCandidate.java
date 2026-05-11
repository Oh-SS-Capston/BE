package com.example.ossdoc.domain.publicapi.model;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

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
    private String confidence;             // "HIGH" | "MED" | "LOW"
    private List<String> signals;          // fired signal names
    private int score;
}
