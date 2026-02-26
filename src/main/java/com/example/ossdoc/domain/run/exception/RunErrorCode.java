// domain/run/exception/RunErrorCode.java
package com.example.ossdoc.domain.run.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum RunErrorCode {
    INVALID_REPO_URL(HttpStatus.BAD_REQUEST, "RUN_400_001", "Invalid GitHub repo URL."),
    GITHUB_API_FAILED(HttpStatus.BAD_GATEWAY, "RUN_502_001", "Failed to resolve repo info from GitHub."),
    DOWNLOAD_FAILED(HttpStatus.BAD_GATEWAY, "RUN_502_002", "Failed to download repository zip."),
    UNZIP_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "RUN_500_001", "Failed to unzip repository."),
    MANIFEST_WRITE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "RUN_500_002", "Failed to write job_manifest.json.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    RunErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }
}