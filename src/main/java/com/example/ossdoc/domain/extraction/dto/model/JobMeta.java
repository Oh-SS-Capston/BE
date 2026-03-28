package com.example.ossdoc.domain.extraction.dto.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * 어떤 job / repo / commit에 대해 facts를 생성했는지 나타내는 재현성 메타
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobMeta(
        @JsonProperty("job_id")
        String jobId,

        @JsonProperty("repo_url")
        String repoUrl,

        @JsonProperty("commit_sha")
        String commitSha
) {
}