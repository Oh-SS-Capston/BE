package com.example.ossdoc.domain.run.dto;

import com.example.ossdoc.domain.run.enums.RunStatus;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RepoRunCreateResponse {
    private String runId;
    private RunStatus status;
    private String commitSha;
    private String workspaceRoot;
}