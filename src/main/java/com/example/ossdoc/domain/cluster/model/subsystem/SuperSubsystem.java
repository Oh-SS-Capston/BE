package com.example.ossdoc.domain.cluster.model.subsystem;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.util.List;
import java.util.Map;

@Getter
@Builder(toBuilder = true)
@Jacksonized
public class SuperSubsystem {
    private String superSubsystemId;          // "sup_001"
    private String canonicalKey;              // moduleId 또는 packageRoot fallback 값
    private String displayName;               // ModuleEntity.artifactId 우선
    private List<String> memberSubsystemIds;  // level-1 subsystemId 목록
    private List<String> memberSymbolIds;     // 펼쳐진 전체 type symbolId
    private int memberCount;                  // = memberSymbolIds.size()
    private Map<String, Double> moduleAffinity; // moduleId → 노드 비율 (합=1.0)
    private List<String> packageRoots;        // 참고용 패키지 루트 목록
}
