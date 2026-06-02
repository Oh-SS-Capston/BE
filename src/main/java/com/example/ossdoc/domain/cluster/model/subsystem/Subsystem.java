package com.example.ossdoc.domain.cluster.model.subsystem;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

@Getter
@Builder(toBuilder = true)
@Jacksonized
public class Subsystem {
    private String subsystemId;
    private String name;
    private double score;
    private List<String> memberSymbolIds;
    private List<String> entrySymbolIds;
    private List<String> coreSymbolIds;
    private List<String> packageRoots;
}
