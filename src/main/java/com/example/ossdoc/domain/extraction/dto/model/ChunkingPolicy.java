package com.example.ossdoc.domain.extraction.dto.model;

import lombok.Builder;

/**
 * 병렬 청크 분할 / worker pool 정책
 */
@Builder
public record ChunkingPolicy(
        int astMaxFiles,
        long astMaxBytes,
        int asmMaxFiles,
        long asmMaxBytes,
        int workerCount
) {
    public ChunkingPolicy {
        if (astMaxFiles <= 0) {
            throw new IllegalArgumentException("astMaxFiles must be greater than 0");
        }
        if (astMaxBytes <= 0) {
            throw new IllegalArgumentException("astMaxBytes must be greater than 0");
        }
        if (asmMaxFiles <= 0) {
            throw new IllegalArgumentException("asmMaxFiles must be greater than 0");
        }
        if (asmMaxBytes <= 0) {
            throw new IllegalArgumentException("asmMaxBytes must be greater than 0");
        }
        if (workerCount <= 0) {
            throw new IllegalArgumentException("workerCount must be greater than 0");
        }
    }

    public static ChunkingPolicy standard() {
        int processors = Runtime.getRuntime().availableProcessors();
        int workers = Math.max(1, Math.min(processors, 8));
        return new ChunkingPolicy(30, 1_500_000L, 100, 3_000_000L, workers);
    }
}
