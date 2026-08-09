package com.example.ossdoc.domain.license.service;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.license.artifact.output.LicenseAnalysisJson;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 대표 라이선스 분석 결과를 OSSDoc 공통 Artifact 저장 규칙으로 발행하는 서비스입니다.
 *
 * <p>역할:
 * - LicenseAnalysisService가 만든 순수 결과 객체를 JSON 트리로 변환합니다.
 * - 공통 ArtifactService를 사용해 S3, 로컬 artifacts 디렉터리, DB 메타데이터에 같은 결과를 저장합니다.
 * - 저장되는 산출물 종류는 LICENSE_ANALYSIS_JSON이고, 경로는 analysis/license_analysis.json으로 고정합니다.
 *
 * <p>중요한 설계 이유:
 * 라이선스 분석 핵심 로직이 ArtifactService나 S3 저장 정책을 직접 알게 되면 테스트와 수동 실행이 무거워집니다.
 * 그래서 분석 서비스와 저장 서비스를 분리하고, 파이프라인 연결 지점에서만 이 publisher를 사용합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LicenseAnalysisArtifactPublisher {

    private static final String SCHEMA_VERSION = "1.0";
    private static final String RELATIVE_PATH = "analysis/license_analysis.json";

    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    /**
     * license_analysis.json Artifact를 저장하고 저장된 Artifact 엔티티를 반환합니다.
     *
     * @param run 이번 분석 실행을 표현하는 RepoRun 엔티티
     * @param output 대표 라이선스 분석 결과 JSON 모델
     * @return DB에 저장되었거나 갱신된 Artifact 엔티티
     */
    public Artifact publish(RepoRun run, LicenseAnalysisJson output) {
        JsonNode content = objectMapper.valueToTree(output);

        Artifact artifact = artifactService.saveJsonArtifact(
                run,
                ArtifactKind.LICENSE_ANALYSIS_JSON,
                SCHEMA_VERSION,
                RELATIVE_PATH,
                content
        );

        log.info(
                "[LICENSE] license_analysis.json published. runId={}, artifactId={}, relativePath={}",
                run.getRunId(),
                artifact.getArtifactId(),
                RELATIVE_PATH
        );

        return artifact;
    }
}
