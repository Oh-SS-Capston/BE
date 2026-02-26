// domain/run/support/GithubRepoRef.java
package com.example.ossdoc.domain.run.support;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GithubRepoRef {
    private String owner;
    private String repo;
    private String ref;      // branch/tag/sha
}