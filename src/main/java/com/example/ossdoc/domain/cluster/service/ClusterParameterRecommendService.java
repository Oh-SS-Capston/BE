package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.dto.request.ClusterParameterRecommendRequest;
import com.example.ossdoc.domain.cluster.dto.response.ClusterParameterRecommendResponse;
import com.example.ossdoc.domain.cluster.exception.ClusterException;
import com.example.ossdoc.domain.cluster.exception.code.ClusterErrorCode;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 군집화/클래스맵 파라미터 자동 추천 서비스.
 *
 * 목적:
 * - 레포 크기마다 적정 파라미터가 달라 사용자가 매번 수동 튜닝해야 하는 문제를 줄인다.
 * - 동일 run에서 재현 가능한 기본값을 제공해 프런트 UX를 단순화한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClusterParameterRecommendService {

    private final RepoRunRepository repoRunRepository;
    private final SymbolRepository symbolRepository;
    private final EdgeRepository edgeRepository;

    /**
     * run의 그래프 규모를 바탕으로 cluster/class-map 추천값을 계산한다.
     */
    public ClusterParameterRecommendResponse recommend(ClusterParameterRecommendRequest request) {
        String runId = request.getRunId();
        repoRunRepository.findById(runId)
                .orElseThrow(() -> new ClusterException(ClusterErrorCode.CLUSTER_RUN_NOT_FOUND));

        long typeSymbolCount = symbolRepository.countByRun_RunIdAndSymbolKind(runId, SymbolKind.TYPE);
        long edgeCount = edgeRepository.countByRun_RunId(runId);
        double edgePerType = calculateEdgePerType(typeSymbolCount, edgeCount);

        SizeTier sizeTier = decideSizeTier(typeSymbolCount, edgeCount);
        ClusterParameterRecommendResponse.ClusterRecommended clusterRecommended =
                recommendCluster(sizeTier, edgePerType);
        ClusterParameterRecommendResponse.ClassMapRecommended classMapRecommended =
                recommendClassMap(sizeTier, edgePerType);

        return ClusterParameterRecommendResponse.builder()
                .runId(runId)
                .sizeTier(sizeTier.name())
                .typeSymbolCount(typeSymbolCount)
                .edgeCount(edgeCount)
                .edgePerType(edgePerType)
                .clusterRecommended(clusterRecommended)
                .classMapRecommended(classMapRecommended)
                .build();
    }

    /**
     * TYPE 기준 노드 수와 edge 총량으로 저장소 규모를 3단계로 구분한다.
     * - TYPE 수를 주 지표로 삼고, edge 수는 보조 지표로 사용한다.
     */
    private SizeTier decideSizeTier(long typeSymbolCount, long edgeCount) {
        if (typeSymbolCount == 0L) {
            return SizeTier.SMALL;
        }
        if (typeSymbolCount <= 180L && edgeCount <= 30_000L) {
            return SizeTier.SMALL;
        }
        if (typeSymbolCount <= 750L && edgeCount <= 150_000L) {
            return SizeTier.MEDIUM;
        }
        return SizeTier.LARGE;
    }

    /**
     * 밀도 보정을 적용해 cluster 파라미터를 추천한다.
     * - edgePerType이 높은 그래프는 과분할/노이즈를 줄이기 위해 minClusterSize/topK를 소폭 상향한다.
     */
    private ClusterParameterRecommendResponse.ClusterRecommended recommendCluster(SizeTier sizeTier, double edgePerType) {
        // 기본값을 먼저 두고 tier별로 덮어써 컴파일러가 초기화를 확실히 인지하도록 한다.
        double resolution = 0.22;
        int iterations = 8;
        int minClusterSize = 2;
        int topK = 3;

        switch (sizeTier) {
            case SMALL -> {
                resolution = 0.22;
                iterations = 8;
                minClusterSize = 2;
                topK = 3;
            }
            case MEDIUM -> {
                resolution = 0.17;
                iterations = 10;
                minClusterSize = 3;
                topK = 5;
            }
            case LARGE -> {
                resolution = 0.12;
                iterations = 12;
                minClusterSize = 5;
                topK = 8;
            }
        }

        if (edgePerType >= 80.0) {
            minClusterSize = Math.min(minClusterSize + 1, 10);
            topK = Math.min(topK + 1, 20);
        } else if (edgePerType <= 20.0) {
            resolution = Math.min(resolution + 0.03, 0.35);
            minClusterSize = Math.max(minClusterSize - 1, 1);
        }

        return ClusterParameterRecommendResponse.ClusterRecommended.builder()
                .resolution(round(resolution, 3))
                .iterations(iterations)
                .minClusterSize(minClusterSize)
                .topK(topK)
                .build();
    }

    /**
     * class-map 노출 밀도를 제어하는 추천값을 계산한다.
     * - 군집화와 동일한 tier를 사용해 노드/엣지 상한을 함께 조정한다.
     */
    private ClusterParameterRecommendResponse.ClassMapRecommended recommendClassMap(SizeTier sizeTier, double edgePerType) {
        // 기본값을 먼저 두고 tier별로 덮어써 컴파일러가 초기화를 확실히 인지하도록 한다.
        int maxNodes = 24;
        int maxEdges = 42;
        int startHereTopN = 3;

        switch (sizeTier) {
            case SMALL -> {
                maxNodes = 24;
                maxEdges = 42;
                startHereTopN = 3;
            }
            case MEDIUM -> {
                maxNodes = 40;
                maxEdges = 90;
                startHereTopN = 5;
            }
            case LARGE -> {
                maxNodes = 60;
                maxEdges = 150;
                startHereTopN = 8;
            }
        }

        if (edgePerType >= 80.0) {
            maxEdges = Math.min((int) Math.round(maxEdges * 1.2), 300);
        } else if (edgePerType <= 20.0) {
            maxNodes = Math.max(maxNodes - 4, 16);
            maxEdges = Math.max(maxEdges - 10, 30);
        }

        return ClusterParameterRecommendResponse.ClassMapRecommended.builder()
                .maxNodes(maxNodes)
                .maxEdges(maxEdges)
                .startHereTopN(startHereTopN)
                .build();
    }

    /**
     * 밀도 지표(edge/type)를 소수 3자리로 반환한다.
     * - 타입 수가 0이면 0.0으로 처리해 0 나누기 예외를 방지한다.
     */
    private double calculateEdgePerType(long typeSymbolCount, long edgeCount) {
        if (typeSymbolCount <= 0L) {
            return 0.0;
        }
        return round((double) edgeCount / (double) typeSymbolCount, 3);
    }

    private double round(double value, int scale) {
        return BigDecimal.valueOf(value)
                .setScale(scale, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private enum SizeTier {
        SMALL,
        MEDIUM,
        LARGE
    }
}
