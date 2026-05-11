package com.example.ossdoc.domain.publicapi.model;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class EntryPointCandidate {
    private String symbolId;
    private String qualifiedName;
    private String simpleName;
    private String typeKind;           // "class" | "interface" | "enum" | "record"
    private String subsystemId;
    private String subsystemLabel;
    private String role;               // "PRIMARY" | "SECONDARY"
    private String confidence;         // "HIGH" | "MED" | "LOW"
    private List<String> signals;      // fired signal names
    private int score;
}
