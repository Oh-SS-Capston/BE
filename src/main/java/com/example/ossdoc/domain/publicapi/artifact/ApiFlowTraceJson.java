package com.example.ossdoc.domain.publicapi.artifact;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * API 진입점별 BFS 호출 경로 추적 결과물.
 * ArtifactKind.API_FLOW_TRACE_JSON 으로 저장된다.
 */
@Getter
@Builder
@Jacksonized
public class ApiFlowTraceJson {

    private String schemaVersion;
    private String runId;
    private OffsetDateTime generatedAt;
    private TraceMeta meta;
    private List<EntryPointTrace> traces;

    @Getter
    @Builder
    @Jacksonized
    public static class TraceMeta {
        private int entryPointCount;
        private int tracedCount;
        private int maxBfsDepth;
        private double flowSetOverloadRatio;
    }

    /** 진입점 1개의 BFS 도달 가능 서브그래프. */
    @Getter
    @Builder
    @Jacksonized
    public static class EntryPointTrace {
        private String entrySymbolId;
        private String entryName;
        private String entryQualifiedName;
        private String exposure;
        /** BFS 도달 가능 노드 목록 (진입점 포함, 깊이 오름차순 정렬). */
        private List<FlowNode> reachableNodes;
        /** 도달 가능 노드 사이의 CALLS 엣지. */
        private List<FlowEdge> reachableEdges;
        /** 진입점에서 최대로 도달한 BFS 깊이. */
        private int maxDepth;
        /** flow set 과도 팽창 차단으로 인해 BFS가 잘린 경우 true. */
        private boolean truncated;
    }

    @Getter
    @Builder
    @Jacksonized
    public static class FlowNode {
        private String symbolId;
        private String kind;
        private String name;
        private String qualifiedName;
        /** BUILD_MANIFEST 메모리 매칭 결과 (null 가능). */
        private String moduleId;
        private int bfsDepth;
    }

    @Getter
    @Builder
    @Jacksonized
    public static class FlowEdge {
        private String fromSymbolId;
        private String toSymbolId;
        private String edgeKind;
        private double confidence;
        private String evidence;
    }
}
