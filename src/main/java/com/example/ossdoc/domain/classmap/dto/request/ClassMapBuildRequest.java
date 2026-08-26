package com.example.ossdoc.domain.classmap.dto.request;

import com.example.ossdoc.domain.classmap.enums.ClassMapScope;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassMapBuildRequest {
    @NotBlank
    private String runId;

    private ClassMapScope scope = ClassMapScope.OVERVIEW;

    private String subsystemId;

    @Min(10)
    private Integer maxNodes = 40;

    @Min(20)
    private Integer maxEdges = 120;

    @Min(1)
    private Integer startHereTopN = 8;

    public ClassMapScope safeScope() {
        return scope == null ? ClassMapScope.OVERVIEW : scope;
    }

    public int safeMaxNodes() {
        int raw = maxNodes == null ? 40 : maxNodes;
        return Math.max(10, Math.min(400, raw));
    }

    public int safeMaxEdges() {
        int raw = maxEdges == null ? 120 : maxEdges;
        return Math.max(20, Math.min(1000, raw));
    }

    public int safeStartHereTopN() {
        int raw = startHereTopN == null ? 8 : startHereTopN;
        return Math.max(1, Math.min(30, raw));
    }
}
