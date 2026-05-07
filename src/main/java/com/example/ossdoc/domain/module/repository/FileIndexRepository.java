package com.example.ossdoc.domain.module.repository;

import com.example.ossdoc.domain.module.entity.FileIndex;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FileIndexRepository extends JpaRepository<FileIndex, Long> {

    /**
     * 성능 최적화를 위해 run 범위 file_index를 한 번에 로드한다.
     */
    List<FileIndex> findAllByRun_RunId(String runId);

    Optional<FileIndex> findFirstByRun_RunIdAndPath(String runId, String path);
}
