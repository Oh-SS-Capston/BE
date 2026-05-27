package com.example.ossdoc.domain.artifact.service;

import com.example.ossdoc.domain.artifact.dto.response.ArtifactJsonResponse;
import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.exception.ArtifactException;
import com.example.ossdoc.domain.artifact.exception.code.ArtifactErrorCode;
import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.membership.exception.MembershipException;
import com.example.ossdoc.domain.membership.exception.code.MembershipErrorCode;
import com.example.ossdoc.domain.membership.service.MembershipAccessService;
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
    private final MembershipAccessService membershipAccessService;

    public ArtifactJsonResponse getJsonArtifact(Long artifactId, Long userId) {
        Artifact artifact = artifactRepository.findById(artifactId)
                .orElseThrow(() -> new ArtifactException(ArtifactErrorCode.ARTIFACT_NOT_FOUND));

        validateOwnerAndMembership(artifact, userId);
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

    private void validateOwnerAndMembership(Artifact artifact, Long userId) {
        RepoRun run = artifact.getRun();
        User owner = run.getOwner();

        if (owner == null || !Objects.equals(owner.getId(), userId)) {
            throw new ArtifactException(ArtifactErrorCode.ARTIFACT_FORBIDDEN);
        }

        if (!membershipAccessService.canViewRun(run, owner)) {
            throw new MembershipException(MembershipErrorCode.MEMBERSHIP_REQUIRED);
        }
    }

    private void validateJsonArtifact(Artifact artifact) {
        if (!"application/json".equalsIgnoreCase(artifact.getContentType())) {
            throw new ArtifactException(ArtifactErrorCode.ARTIFACT_NOT_JSON);
        }
    }
}