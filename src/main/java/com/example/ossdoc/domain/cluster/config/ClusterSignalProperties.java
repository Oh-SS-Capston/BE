package com.example.ossdoc.domain.cluster.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * cluster 도메인 군집 보정/신호 설정.
 * refactplan.md 1순위(SubsystemRefiner) + 2~5순위(PackageSignal, NameSignal, DocSignal, ApiFlowSignal) 토글을 정의한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ossdoc.cluster")
public class ClusterSignalProperties {

    private Refiner refiner = new Refiner();

    /** refactplan.md 2~5순위 의미 신호(가상 엣지). 실험적 augmentation 이라 기본 OFF. */
    private Signals signals = new Signals();

    /** level-2 super-cluster 설정. 기본 OFF로 도입, 검증 후 ON 전환. */
    private SuperCluster superCluster = new SuperCluster();

    @Getter
    @Setter
    public static class Refiner {
        /** Refiner 전체 ON/OFF. 결정적 보정이라 기본 ON. */
        private boolean enabled = true;
        private Rules rules = new Rules();
    }

    @Getter
    @Setter
    public static class Rules {
        private OwnerRule owner = new OwnerRule();
        private ExceptionRule exception = new ExceptionRule();
    }

    @Getter
    @Setter
    public static class OwnerRule {
        private boolean enabled = true;
        /** owner 체인 흡수 깊이 상한. 순환/과도 흡수를 방지한다. */
        private int maxDepth = 3;
    }

    @Getter
    @Setter
    public static class ExceptionRule {
        private boolean enabled = true;
    }

    /**
     * 의미 신호(가상 엣지) 묶음. yaml 키 {@code cluster.signals.package} 는
     * Java 예약어라 필드명을 {@code pkg} 로 두고 접근자명({@code getPackage}/{@code setPackage})으로 바인딩한다.
     */
    public static class Signals {
        private PackageSignal pkg = new PackageSignal();
        private NameSignal name = new NameSignal();
        private DocSignal doc = new DocSignal();
        private ApiFlowSignal apiFlow = new ApiFlowSignal();

        public PackageSignal getPackage() {
            return pkg;
        }

        public void setPackage(PackageSignal pkg) {
            this.pkg = pkg;
        }

        public NameSignal getName() {
            return name;
        }

        public void setName(NameSignal name) {
            this.name = name;
        }

        public DocSignal getDoc() {
            return doc;
        }

        public void setDoc(DocSignal doc) {
            this.doc = doc;
        }

        public ApiFlowSignal getApiFlow() {
            return apiFlow;
        }

        public void setApiFlow(ApiFlowSignal apiFlow) {
            this.apiFlow = apiFlow;
        }
    }

    /**
     * 3순위 NameSimilaritySignalProvider 설정.
     * CamelCase 토큰 자카드 유사도 기반 신호. 토큰 수 &lt; 2인 노드는 신호 대상 제외.
     */
    @Getter
    @Setter
    public static class NameSignal {
        /** 통계적 신호라 기본 OFF. 검증 통과 후 PR에서 ON 한다. */
        private boolean enabled = false;
        /** 가상 엣지 가중치 = baseWeight × jaccard. */
        private double baseWeight = 0.10;
        /** 이 임계값 이상인 노드 쌍에만 가상 엣지를 추가한다. */
        private double jaccardThreshold = 0.5;
    }

    /**
     * 4순위 DocCommentSignalProvider 설정.
     * Javadoc TF-IDF 코사인 유사도 기반 신호. doc 커버리지 &lt; 30% 이면 자동 비활성화.
     */
    @Getter
    @Setter
    public static class DocSignal {
        /** 통계적 신호라 기본 OFF. 검증 통과 후 PR에서 ON 한다. */
        private boolean enabled = false;
        /** 가상 엣지 가중치 = baseWeight × cosine. */
        private double baseWeight = 0.10;
        /** 이 임계값 이상인 노드 쌍에만 가상 엣지를 추가한다. */
        private double cosineThreshold = 0.4;
    }

    /**
     * 5순위 PublicApiFlowSignalProvider 설정.
     * 공개 API 진입점에서 BFS로 도달 가능한 노드 집합 기반 신호.
     * 한 쌍당 최대 가중치 보존(다중 진입점 누적 방지), flow set 과도 팽창 시 자동 차단.
     */
    @Getter
    @Setter
    public static class ApiFlowSignal {
        /** 통계적 신호라 기본 OFF. 검증 통과 후 PR에서 ON 한다. */
        private boolean enabled = false;
        /** 가상 엣지 가중치 = baseWeight / (depth_i + depth_j). */
        private double baseWeight = 0.10;
        /** BFS 깊이 상한. 이 깊이를 초과하는 노드는 flow set 에서 제외한다. */
        private int maxBfsDepth = 4;
        /** 진입점 seed 최소 confidence. 이 등급 미만은 seed에서 제외된다. HIGH|MED|LOW */
        private String minConfidence = "MED";
    }

    @Getter
    @Setter
    public static class SuperCluster {
        /** true 이면 level-2 super-cluster 산출 활성화. 검증 완료 후 ON 전환. */
        private boolean enabled = false;
        /** 병합 전략. MODULE_ID(결정적) 또는 META_LEIDEN(통계적, Phase 2). */
        private String strategy = "MODULE_ID";
        /** 이 크기 미만의 super-cluster는 부모 모듈로 흡수하거나 super_misc로 분리. */
        private int minSuperSize = 10;
        private Fallback fallback = new Fallback();
    }

    @Getter
    @Setter
    public static class Fallback {
        /** moduleId 미보유 노드(외부·생성 코드)에만 적용되는 packageRoot prefix 깊이. */
        private int packageRootCommonPrefixDepth = 4;
    }

    /**
     * 2순위 PackageSignalProvider 설정.
     * baseWeight 는 EdgeWeightPolicy 최저값(ANNOTATED_WITH 0.5)보다 낮게 두어 보수적으로 주입한다.
     */
    @Getter
    @Setter
    public static class PackageSignal {
        /** 통계적 신호라 기본 OFF. 검증 통과 후 PR에서 ON 한다. */
        private boolean enabled = false;
        /** 가상 엣지 기준 가중치. 실제 가중치는 baseWeight / log2(packageSize + 1) 로 감쇠된다. */
        private double baseWeight = 0.15;
        /** 이 크기 이상이면 완전 그래프 대신 star 토폴로지(허브 1개)를 사용한다. */
        private int starTopologyFrom = 9;
        /** 이 크기를 초과하면 신호를 차단한다(루트 패키지/과도 결합 방지). */
        private int skipFrom = 30;
    }
}
