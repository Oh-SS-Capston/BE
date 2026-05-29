package com.example.ossdoc.domain.artifact.service;

import com.example.ossdoc.domain.artifact.dto.response.ArtifactJsonResponse;
import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.exception.ArtifactException;
import com.example.ossdoc.domain.artifact.exception.code.ArtifactErrorCode;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ArtifactQueryService {

    private final ArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;

    public ArtifactJsonResponse getJsonArtifact(Long artifactId, Long userId) {
        Artifact artifact = artifactRepository.findById(artifactId)
                .orElseThrow(() -> new ArtifactException(ArtifactErrorCode.ARTIFACT_NOT_FOUND));

        validateOwner(artifact, userId);
        validateJsonArtifact(artifact);

        /*
         * 중요:
         * JsonNode 자체를 응답으로 넘기지 않고,
         * 일반 Java 구조(Map/List 등)로 변환해서 넘깁니다.
         */
        Object plainJsonContent = objectMapper.convertValue(
                artifact.getMeta(),
                Object.class
        );

        return ArtifactJsonResponse.from(artifact, plainJsonContent);
    }

    /*
     * 토큰 기반 정책에서는 멤버십 활성 여부가 아니라,
     * 현재 로그인 사용자가 해당 artifact의 run owner인지로만 조회 권한을 판단합니다.
     */
    private void validateOwner(Artifact artifact, Long userId) {
        RepoRun run = artifact.getRun();
        User owner = run.getOwner();

        if (owner == null || !Objects.equals(owner.getId(), userId)) {
            throw new ArtifactException(ArtifactErrorCode.ARTIFACT_FORBIDDEN);
        }
    }

    private void validateJsonArtifact(Artifact artifact) {
        if (!"application/json".equalsIgnoreCase(artifact.getContentType())) {
            throw new ArtifactException(ArtifactErrorCode.ARTIFACT_NOT_JSON);
        }
    }
}