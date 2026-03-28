package com.example.ossdoc.domain.extraction.dto.model;

import com.example.ossdoc.domain.extraction.enums.ChunkKind;
import lombok.Builder;

import java.util.List;

/**
 * worker에 전달되는 청크 작업 단위
 */
@Builder
public record ChunkDescriptor(
        String chunkId,
        ChunkKind kind,
        String module,
        String rootPath,
        List<String> files,
        long totalBytes,
        int fileCount
) {
    public ChunkDescriptor {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
