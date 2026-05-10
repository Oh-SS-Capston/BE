// domain/run/dto/RepoRunCreateRequest.java
package com.example.ossdoc.domain.run.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class RepoRunCreateRequest {
    @NotBlank
    private String repoUrl;

    // branch/tag/sha (optional)
    private String ref;
}