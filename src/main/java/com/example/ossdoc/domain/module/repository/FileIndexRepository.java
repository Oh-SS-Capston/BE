package com.example.ossdoc.domain.module.repository;

import com.example.ossdoc.domain.module.entity.FileIndex;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FileIndexRepository extends JpaRepository<FileIndex, Long> {

    /**
     * run과 경로가 일치하는 파일 인덱스를 조회한다.
     */
    Optional<FileIndex> findFirstByRun_RunIdAndPath(String runId, String path);
}
