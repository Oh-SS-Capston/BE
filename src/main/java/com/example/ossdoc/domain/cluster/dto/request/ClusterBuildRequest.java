package com.example.ossdoc.domain.cluster.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
/* 군집화 알고리즘 : Leiden알고리즘
* resolution : 군집 세분화 정도 파라미터. 값이 높을 수록 군집이 세분화됨. 범위(0~1)
* iterations : 품질 개선을 위해 노드 이동, 정교화, 집계 과정을 반복할 횟수
* minClusterSize : 군집 내의 멤버가 너무 적으면, 그 군집을 subsystem으로 인정할지에 대한 기준값 / 군집(subsystem) 필터
* topK : symbol을 상위 몇개까지 ranking 결과를 뽑을지 정하는 값 / 랭킹(symbol) 필터
 * */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterBuildRequest {
    @NotBlank
    private String runId;

    /*Leiden 군집화 알고리즘 관련*/
    private Double resolution = 0.012;

    @Min(1)
    private Integer iterations = 10;

    @Min(1)
    private Integer minClusterSize = 3;

    @Min(1)
    private Integer topK = 30;
}
