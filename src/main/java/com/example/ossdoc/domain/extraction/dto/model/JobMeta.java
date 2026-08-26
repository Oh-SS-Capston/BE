package com.example.ossdoc.domain.extraction.dto.model;

import com.example.ossdoc.domain.extraction.enums.ClassifiedBy;
import com.example.ossdoc.domain.extraction.enums.PipelineProfile;
import com.example.ossdoc.domain.extraction.enums.RepoType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

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
        String commitSha,

        @JsonProperty("repo_classification")
        RepoClassification repoClassification
) {

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RepoClassification(
            @JsonProperty("detected_type")
            RepoType detectedType,

            @JsonProperty("effective_type")
            RepoType effectiveType,

            @JsonProperty("pipeline_profile")
            PipelineProfile pipelineProfile,

            @JsonProperty("confidence")
            Double confidence,

            @JsonProperty("classified_by")
            ClassifiedBy classifiedBy,

            @JsonProperty("override_reason")
            String overrideReason,

            @JsonProperty("signals")
            List<String> signals
    ) {
    }
}
